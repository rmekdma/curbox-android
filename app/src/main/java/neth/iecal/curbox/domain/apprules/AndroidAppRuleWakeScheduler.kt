package neth.iecal.curbox.domain.apprules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Abstraction for platform AlarmManager operations to enable deterministic unit testing.
 */
interface AlarmPublisher {
    fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)
    fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent)
    fun cancel(operation: PendingIntent)
}

/**
 * Production implementation of [AppRuleWakeScheduler] using platform [AlarmManager],
 * monotonic delay conversion, and a capped 20-second [Handler] fallback.
 */
class AndroidAppRuleWakeScheduler internal constructor(
    private val context: Context,
    private val alarmPublisher: AlarmPublisher,
    private val handlerPostDelayed: (Runnable, Long) -> Boolean,
    private val handlerRemoveCallbacks: (Runnable) -> Unit,
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val pendingIntentFactory: (key: String, token: Long) -> PendingIntent,
    private val cancelPendingIntent: (PendingIntent) -> Unit,
    private val onNonFatalError: (Throwable) -> Unit,
    override var onWake: ((key: String, token: Long) -> Unit)? = null
) : AppRuleWakeScheduler {

    companion object {
        const val ACTION_APP_RULE_WAKE = "neth.iecal.curbox.domain.apprules.ACTION_APP_RULE_WAKE"
        const val EXTRA_KEY = "neth.iecal.curbox.domain.apprules.EXTRA_KEY"
        const val EXTRA_TOKEN = "neth.iecal.curbox.domain.apprules.EXTRA_TOKEN"
        const val MAX_HANDLER_DELAY_MS = 20_000L
    }

    constructor(
        context: Context,
        alarmManager: AlarmManager? = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager,
        handler: Handler = Handler(Looper.getMainLooper()),
        wallClockMs: () -> Long = { System.currentTimeMillis() },
        elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
        onNonFatalError: (Throwable) -> Unit = { },
        onWake: ((key: String, token: Long) -> Unit)? = null
    ) : this(
        context = context,
        alarmPublisher = SystemAlarmPublisher(alarmManager),
        handlerPostDelayed = { runnable, delay -> handler.postDelayed(runnable, delay) },
        handlerRemoveCallbacks = { runnable -> handler.removeCallbacks(runnable) },
        wallClockMs = wallClockMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        pendingIntentFactory = { key, token ->
            val wakeIntent = Intent(ACTION_APP_RULE_WAKE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_TOKEN, token)
            }
            val requestCode = (key.hashCode() xor (token.hashCode() and 0xFFFF))
            PendingIntent.getBroadcast(
                context,
                requestCode,
                wakeIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        },
        cancelPendingIntent = { pendingIntent ->
            try {
                pendingIntent.cancel()
            } catch (_: Throwable) {
            }
        },
        onNonFatalError = onNonFatalError,
        onWake = onWake
    )

    private class SystemAlarmPublisher(
        private val alarmManager: AlarmManager?
    ) : AlarmPublisher {
        override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
            alarmManager?.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
        }

        override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
            alarmManager?.setAndAllowWhileIdle(type, triggerAtMillis, operation)
        }

        override fun cancel(operation: PendingIntent) {
            alarmManager?.cancel(operation)
        }
    }

    private class ActiveRegistration(
        val token: Long,
        val pendingIntent: PendingIntent,
        val handlerRunnable: Runnable
    )

    private val lock = Any()
    private val registrations = mutableMapOf<String, ActiveRegistration>()
    @Volatile private var isReceiverRegistered = false

    val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onAlarmReceived(intent)
        }
    }

    fun registerReceiver() {
        synchronized(lock) {
            if (isReceiverRegistered) return
            try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION_APP_RULE_WAKE),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isReceiverRegistered = true
            } catch (t: Throwable) {
                onNonFatalError(t)
            }
        }
    }

    fun unregisterReceiver() {
        synchronized(lock) {
            if (!isReceiverRegistered) return
            try {
                context.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                onNonFatalError(t)
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
        val (oldRegistration, newPendingIntent, newRunnable, triggerAtElapsedMs, handlerDelayMs) = synchronized(lock) {
            val old = registrations.remove(key)

            val currentWall = wallClockMs()
            val currentElapsed = elapsedRealtimeMs()
            val remainingDelayMs = AppRuleWallClockScheduler.delayUntil(
                dueAtWallClockMs = dueAtWallClockMs,
                nowWallClockMs = currentWall,
                nowElapsedRealtimeMs = currentElapsed
            )
            val triggerAtElapsed = currentElapsed + remainingDelayMs
            val handlerDelay = minOf(remainingDelayMs, MAX_HANDLER_DELAY_MS).coerceAtLeast(0L)

            val pendingIntent = pendingIntentFactory(key, token)
            val runnable = Runnable {
                handleWake(key, token)
            }

            registrations[key] = ActiveRegistration(
                token = token,
                pendingIntent = pendingIntent,
                handlerRunnable = runnable
            )

            ScheduleBundle(old, pendingIntent, runnable, triggerAtElapsed, handlerDelay)
        }

        // Clean up previous registration outside the lock
        oldRegistration?.let { old ->
            cancelAlarmInternal(old.pendingIntent)
            handlerRemoveCallbacks(old.handlerRunnable)
        }

        // Post handler capped to MAX_HANDLER_DELAY_MS
        handlerPostDelayed(newRunnable, handlerDelayMs)

        // Schedule AlarmManager via AlarmPublisher
        try {
            alarmPublisher.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedMs,
                newPendingIntent
            )
        } catch (_: SecurityException) {
            try {
                alarmPublisher.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    newPendingIntent
                )
            } catch (t: Throwable) {
                onNonFatalError(t)
            }
        } catch (t: Throwable) {
            onNonFatalError(t)
        }
    }

    override fun cancel(key: String) {
        val removed = synchronized(lock) {
            registrations.remove(key)
        }
        removed?.let { reg ->
            cancelAlarmInternal(reg.pendingIntent)
            handlerRemoveCallbacks(reg.handlerRunnable)
        }
    }

    override fun cancelAll() {
        val all = synchronized(lock) {
            val copy = registrations.values.toList()
            registrations.clear()
            copy
        }
        all.forEach { reg ->
            cancelAlarmInternal(reg.pendingIntent)
            handlerRemoveCallbacks(reg.handlerRunnable)
        }
    }

    fun onAlarmReceived(intent: Intent?) {
        if (intent == null) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val token = intent.getLongExtra(EXTRA_TOKEN, -1L)
        if (token == -1L) return
        onAlarmTriggered(key, token)
    }

    fun onAlarmTriggered(key: String, token: Long) {
        handleWake(key, token)
    }

    private fun handleWake(key: String, token: Long) {
        val activeToClean = synchronized(lock) {
            val current = registrations[key]
            if (current != null && current.token == token) {
                registrations.remove(key)
                current
            } else {
                null
            }
        } ?: return

        cancelAlarmInternal(activeToClean.pendingIntent)
        handlerRemoveCallbacks(activeToClean.handlerRunnable)

        try {
            onWake?.invoke(key, token)
        } catch (t: Throwable) {
            onNonFatalError(t)
        }
    }

    private fun cancelAlarmInternal(pendingIntent: PendingIntent) {
        try {
            alarmPublisher.cancel(pendingIntent)
        } catch (t: Throwable) {
            onNonFatalError(t)
        }
        cancelPendingIntent(pendingIntent)
    }

    private data class ScheduleBundle(
        val oldRegistration: ActiveRegistration?,
        val pendingIntent: PendingIntent,
        val runnable: Runnable,
        val triggerAtElapsedMs: Long,
        val handlerDelayMs: Long
    )
}
