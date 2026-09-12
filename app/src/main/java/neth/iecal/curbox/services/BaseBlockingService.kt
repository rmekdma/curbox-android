package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ServiceProtectionManager
import kotlin.lazy

@SuppressLint("AccessibilityPolicy")
open class BaseBlockingService : AccessibilityService() {

    val dataStoreManager by lazy {
        DataStoreManager(this)
    }

    private val protectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastHeartbeatMs = 0L

    var lastBackPressTimeStamp: Long =
        SystemClock.uptimeMillis() // prevents repetitive global actions

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundService()
        protectionScope.launch { setupProtectionOnConnect() }
    }

    private suspend fun setupProtectionOnConnect() {
        try {
            val config = dataStoreManager.settings.first().serviceProtectionConfig
            if (config.isEnabled) {
                ServiceWatchdogJob.schedule(this)
                ServiceProtectionManager.reinforceBackgroundExecution(this)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Writes the service's "alive" stamp and keeps the background execution exemptions fresh.
     * Throttled so it runs at most twice a minute.
     */
    private fun maybeHeartbeat() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHeartbeatMs < 30_000L) return
        lastHeartbeatMs = now
        protectionScope.launch { runHeartbeat() }
    }

    private suspend fun runHeartbeat() {
        // Lands settings changes whose delay has run out, even when the UI is never opened
        try {
            dataStoreManager.applyDuePendingChanges()
        } catch (_: Exception) {
        }
        try {
            val config = dataStoreManager.settings.first().serviceProtectionConfig
            if (!config.isEnabled) return

            val nowMs = System.currentTimeMillis()
            dataStoreManager.updateServiceProtectionConfig {
                it.copy(appBlockerLastAliveMs = nowMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun startForegroundService() {
        val channelId = "blocking_service_channel"
        val channelName = getString(R.string.blocking_service_channel_name)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.blocking_service_channel_description)
        }
        notificationManager.createNotificationChannel(channel)

        val className = this::class.simpleName
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.blocking_service_notification_title, className))
            .setContentText(getString(R.string.blocking_service_notification_text))
            .setSmallIcon(R.drawable.icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()

        val notificationId = this.javaClass.simpleName.hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    fun updateForegroundNotification(model: LiveRuleNotificationModel) {
        try {
            val channelId = "blocking_service_channel"
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val intent = Intent(this, neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(this, channelId)
                .setContentTitle(model.title)
                .setContentText(model.collapsedText)
                .setSmallIcon(R.drawable.icon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(pendingIntent)

            if (model.expandedLines.size > 1) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(model.expandedText))
            } else if (model.expandedLines.size == 1) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(model.collapsedText))
            }

            val notification = builder.build()
            val notificationId = this.javaClass.simpleName.hashCode()
            notificationManager.notify(notificationId, notification)
        } catch (error: Exception) {
            CrashLogger(this).logNonFatalError(error)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        maybeHeartbeat()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            protectionScope.cancel()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
    }


    fun isDelayOver( delay: Int): Boolean {
        val currentTime = SystemClock.uptimeMillis().toFloat()
        return currentTime - lastBackPressTimeStamp > delay
    }

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        lastBackPressTimeStamp = SystemClock.uptimeMillis()
    }

    fun pressBack() {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()

    }
}
