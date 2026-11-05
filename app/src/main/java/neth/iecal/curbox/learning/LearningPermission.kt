package neth.iecal.curbox.learning

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import neth.iecal.todo.NotificationPermission

/** Each delivered block transition advances the revision even if its final value repeats. */
class LearningPermission(private val context: Context, private val scope: CoroutineScope) {
    fun isGranted() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val state = MutableStateFlow(NotificationPermission(isGranted()))
    private var worker: Job? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 28 && intent.action == NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED) {
                val blocked = intent.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, true)
                update(!blocked && isGranted(), force = true)
            }
        }
    }
    fun start() {
        if (Build.VERSION.SDK_INT >= 28) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        worker = scope.launch { while (true) { delay(1000); update(isGranted(), force = false) } }
    }
    @Synchronized private fun update(granted: Boolean, force: Boolean) {
        val old = state.value
        if (force || granted != old.granted) state.value = NotificationPermission(granted, Math.addExact(old.revision, 1))
    }
    fun stop() {
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        worker?.cancel()
        update(false, force = true)
    }
}
