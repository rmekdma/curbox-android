package neth.iecal.curbox.plugins

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
import neth.iecal.plugin.HostObservation
import neth.iecal.plugin.Connection
import java.util.UUID

/** A nonce response proves a currently bound service; silence remains unknown. */
object PluginServiceStatus {
    const val PROBE = "neth.iecal.curbox.plugins.PROBE"
    const val RESPONSE = "neth.iecal.curbox.plugins.RESPONSE"
    const val NONCE = "nonce"

    suspend fun read(context: Context): HostObservation {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(context, AppBlockerService::class.java) }
        if (!enabled) return HostObservation(connection = Connection.DISCONNECTED, accessibilityEnabled = false)
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
        return HostObservation(connection = if (connected == true) Connection.CONNECTED else Connection.DISCONNECTED, accessibilityEnabled = true)
    }
}
