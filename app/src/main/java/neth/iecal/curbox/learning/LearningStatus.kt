package neth.iecal.curbox.learning

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.todo.LearningAvailability
import java.util.UUID

/** A nonce response proves a currently bound service; silence remains unknown. */
object LearningStatus {
    const val PROBE = "neth.iecal.curbox.learning.PROBE"
    const val RESPONSE = "neth.iecal.curbox.learning.RESPONSE"
    const val NONCE = "nonce"

    suspend fun read(context: Context): LearningAvailability {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(context, AppBlockerService::class.java) }
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!enabled) return LearningAvailability(false, notifications, false)
        val nonce = UUID.randomUUID().toString()
        val connected = withTimeoutOrNull(1500) {
            val response = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getStringExtra(NONCE) == nonce) response.complete(true)
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(RESPONSE), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                context.sendBroadcast(Intent(PROBE).setPackage(context.packageName).putExtra(NONCE, nonce))
                response.await()
            } finally { context.unregisterReceiver(receiver) }
        }
        return LearningAvailability(true, notifications, connected)
    }
}
