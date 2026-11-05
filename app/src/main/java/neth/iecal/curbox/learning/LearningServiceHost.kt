package neth.iecal.curbox.learning

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.provider.Settings
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neth.iecal.curbox.domain.apprules.*
import neth.iecal.plugin.*

/** Connection scoped adapter; framework tree reads occur only on its worker. */
class LearningServiceHost(private val service: AccessibilityService, private val scope: CoroutineScope) : CurboxHost {
    private val publication = LearningHostObservations()
    override val observations: StateFlow<HostObservation> = publication.observations
    private val source = AndroidForegroundObservationSource(service)
    private val sequencer = AtomicConnectionScopedSourceOrderSequencer()
    private data class Request(val result: CompletableDeferred<HostObservation?>? = null)
    private val hints = Channel<Request>(Channel.CONFLATED) { it.result?.complete(null) }
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val off = intent.action == Intent.ACTION_SCREEN_OFF
            if (publication.screenChanged(if (off) Screen.OFF else Screen.UNKNOWN) && !off) hints.trySend(Request())
        }
    }
    private var worker: Job? = null
    fun start() {
        service.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        registered = true
        worker = scope.launch(Dispatchers.Default) {
            for (request in hints) {
                val observation = try { capture() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (t: Throwable) {
                    neth.iecal.curbox.CrashLogger(service).logNonFatalError(Exception(t))
                    null
                }
                request.result?.complete(observation)
            }
        }
        hints.trySend(Request())
    }
    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(service.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(service, service.javaClass)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    fun event(type: Int) {
        val relevant = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (publication.event(relevant)) hints.trySend(Request())
    }
    override suspend fun refreshObservation(): HostObservation {
        if (!publication.isActive()) return observations.value.copy(foreground = Foreground.Unknown)
        val result = CompletableDeferred<HostObservation?>()
        if (hints.trySend(Request(result)).isFailure) return observations.value.copy(foreground = Foreground.Unknown)
        val observation = withTimeoutOrNull(5000) { result.await() }
        if (observation == null) {
            return publication.invalidate()
        }
        return observations.value
    }
    private fun capture(): HostObservation = publication.capture {
        val facts = source.capture(ObservationTrigger(sequencer.nextSourceOrderIdentity(), ObservationKind.REFRESH,
            requestedAtWallMs = System.currentTimeMillis(), requestedAtElapsedMs = SystemClock.elapsedRealtime()))
        val name = facts.activeRoot.packageName
        val app = if (name == null) null else runCatching { service.packageManager.getApplicationInfo(name, 0) }.getOrNull()
        val (screen, foreground) = mapForeground(facts, app != null && app.flags and ApplicationInfo.FLAG_SYSTEM == 0)
        LearningHostCapture(accessibilityEnabled(), screen, foreground, facts.capturedAtWallMs)
    }
    fun stop() {
        publication.stop()
        hints.cancel()
        worker?.cancel()
        if (registered) { service.unregisterReceiver(receiver); registered = false }
    }
}
