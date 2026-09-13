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
import neth.iecal.curbox.CrashLogger

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
 * monotonic delay conversion, and a capped 20-second [Handler] fallback and recovery mechanism.
 */
class AndroidAppRuleWakeScheduler internal constructor(
    private val alarmPublisher: AlarmPublisher,
    private val handlerPostDelayed: (Runnable, Long) -> Boolean,
    private val handlerRemoveCallbacks: (Runnable) -> Unit,
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
    private val pendingIntentFactory: (key: String, token: Long) -> PendingIntent,
    private val cancelPendingIntent: (PendingIntent) -> Unit,
    private val registerReceiver: (BroadcastReceiver, IntentFilter) -> Unit = { _, _ -> },
    private val unregisterReceiver: (BroadcastReceiver) -> Unit = { _ -> },
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
        onNonFatalError: (Throwable) -> Unit = { error ->
            CrashLogger(context).logNonFatalError(
                if (error is Exception) error else Exception(error)
            )
        },
        onWake: ((key: String, token: Long) -> Unit)? = null
    ) : this(
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
            } catch (t: Throwable) {
                onNonFatalError(t)
            }
        },
        registerReceiver = { receiver, filter ->
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        },
        unregisterReceiver = { receiver ->
            context.unregisterReceiver(receiver)
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
        val handlerRunnable: Runnable?,
        val alarmScheduled: Boolean
    )

    private val lock = Any()
    private val registrations = mutableMapOf<String, ActiveRegistration>()
    @Volatile private var isReceiverRegistered = false

    internal val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onAlarmReceived(intent)
        }
    }

    private fun ensureReceiverRegistered() {
        if (isReceiverRegistered) return
        try {
            registerReceiver(receiver, IntentFilter(ACTION_APP_RULE_WAKE))
            isReceiverRegistered = true
        } catch (t: Throwable) {
            onNonFatalError(t)
        }
    }

    private fun unregisterReceiverIfNeeded() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(receiver)
        } catch (t: Throwable) {
            onNonFatalError(t)
        } finally {
            isReceiverRegistered = false
        }
    }

    override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
        val currentWall = wallClockMs()
        val currentElapsed = elapsedRealtimeMs()
        val remainingDelayMs = AppRuleWallClockScheduler.delayUntil(
            dueAtWallClockMs = dueAtWallClockMs,
            nowWallClockMs = currentWall,
            nowElapsedRealtimeMs = currentElapsed
        )
        val triggerAtElapsed = currentElapsed + remainingDelayMs
        val pendingIntent = pendingIntentFactory(key, token)

        var alarmPublished = false
        try {
            alarmPublisher.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsed,
                pendingIntent
            )
            alarmPublished = true
        } catch (_: SecurityException) {
            try {
                alarmPublisher.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsed,
                    pendingIntent
                )
                alarmPublished = true
            } catch (t: Throwable) {
                onNonFatalError(t)
            }
        } catch (t: Throwable) {
            onNonFatalError(t)
        }

        val handlerRunnable: Runnable?
        val handlerDelayMs: Long?
        if (alarmPublished) {
            if (remainingDelayMs <= MAX_HANDLER_DELAY_MS) {
                handlerRunnable = Runnable { handleWake(key, token) }
                handlerDelayMs = remainingDelayMs.coerceAtLeast(0L)
            } else {
                handlerRunnable = null
                handlerDelayMs = null
            }
        } else {
            // AlarmManager unavailable or failed: fall back to handler with 20s recovery cap
            handlerRunnable = Runnable { handleWake(key, token) }
            handlerDelayMs = minOf(remainingDelayMs, MAX_HANDLER_DELAY_MS).coerceAtLeast(0L)
        }

        val oldRegistration = synchronized(lock) {
            ensureReceiverRegistered()
            val old = registrations.remove(key)
            registrations[key] = ActiveRegistration(
                token = token,
                pendingIntent = pendingIntent,
                handlerRunnable = handlerRunnable,
                alarmScheduled = alarmPublished
            )
            old
        }

        oldRegistration?.let(::disposeRegistration)
        if (handlerRunnable != null && handlerDelayMs != null) {
            handlerPostDelayed(handlerRunnable, handlerDelayMs)
        }
    }

    override fun cancel(key: String) {
        val (removed, shouldUnregister) = synchronized(lock) {
            val rem = registrations.remove(key)
            val shouldUnreg = registrations.isEmpty() && isReceiverRegistered
            rem to shouldUnreg
        }
        removed?.let(::disposeRegistration)
        if (shouldUnregister) {
            unregisterReceiverIfNeeded()
        }
    }

    override fun cancelAll() {
        val (all, shouldUnregister) = synchronized(lock) {
            val copy = registrations.values.toList()
            registrations.clear()
            val shouldUnreg = isReceiverRegistered
            copy to shouldUnreg
        }
        all.forEach(::disposeRegistration)
        if (shouldUnregister) {
            unregisterReceiverIfNeeded()
        }
    }

    internal fun onAlarmReceived(intent: Intent?) {
        if (intent == null) return
        if (!intent.hasExtra(EXTRA_KEY) || !intent.hasExtra(EXTRA_TOKEN)) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val token = intent.getLongExtra(EXTRA_TOKEN, 0L)
        handleWake(key, token)
    }

    internal fun onAlarmTriggered(key: String, token: Long) {
        handleWake(key, token)
    }

    private fun handleWake(key: String, token: Long) {
        val (activeToClean, shouldUnregister) = synchronized(lock) {
            val current = registrations[key]
            if (current != null && current.token == token) {
                registrations.remove(key)
                val shouldUnreg = registrations.isEmpty() && isReceiverRegistered
                current to shouldUnreg
            } else {
                null
            }
        } ?: return

        disposeRegistration(activeToClean)
        if (shouldUnregister) {
            unregisterReceiverIfNeeded()
        }

        try {
            onWake?.invoke(key, token)
        } catch (t: Throwable) {
            onNonFatalError(t)
        }
    }

    private fun disposeRegistration(reg: ActiveRegistration) {
        if (reg.alarmScheduled) {
            cancelAlarmInternal(reg.pendingIntent)
        }
        reg.handlerRunnable?.let(handlerRemoveCallbacks)
    }

    private fun cancelAlarmInternal(pendingIntent: PendingIntent) {
        try {
            alarmPublisher.cancel(pendingIntent)
        } catch (t: Throwable) {
            onNonFatalError(t)
        }
        cancelPendingIntent(pendingIntent)
    }
}
