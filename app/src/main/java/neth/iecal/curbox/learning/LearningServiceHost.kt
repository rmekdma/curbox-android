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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neth.iecal.curbox.domain.apprules.*
import neth.iecal.plugin.*

/** Connection scoped adapter; framework tree reads occur only on its worker. */
class LearningServiceHost(private val service: AccessibilityService, private val scope: CoroutineScope) : CurboxHost {
    private val state = MutableStateFlow(HostObservation(connection = Connection.CONNECTED))
    override val observations: StateFlow<HostObservation> = state
    private val source = AndroidForegroundObservationSource(service)
    private val evidence = ForegroundEvidenceModule()
    private val sequencer = AtomicConnectionScopedSourceOrderSequencer()
    private data class Request(val result: CompletableDeferred<HostObservation?>? = null)
    private val hints = Channel<Request>(Channel.CONFLATED) { it.result?.complete(null) }
    @Volatile private var valid = true
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            generation.incrementAndGet()
            if (intent.action == Intent.ACTION_SCREEN_OFF) state.value = state.value.copy(screen = Screen.OFF, foreground = Foreground.Unknown)
            else { state.value = state.value.copy(screen = Screen.UNKNOWN, foreground = Foreground.Unknown); hints.trySend(Request()) }
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
    fun event() {
        generation.incrementAndGet()
        state.value = state.value.copy(callbackReceived = true, foreground = Foreground.Unknown)
        hints.trySend(Request())
    }
    override suspend fun refreshObservation(): HostObservation {
        if (!valid) return state.value.copy(foreground = Foreground.Unknown)
        val result = CompletableDeferred<HostObservation?>()
        if (hints.trySend(Request(result)).isFailure) return state.value.copy(foreground = Foreground.Unknown)
        val observation = withTimeoutOrNull(5000) { result.await() }
        if (observation == null) {
            generation.incrementAndGet()
            state.value = state.value.copy(foreground = Foreground.Unknown)
            return state.value
        }
        return observation
    }
    private fun capture(): HostObservation {
        if (!valid) return state.value
        val observedGeneration = generation.get()
        val facts = source.capture(ObservationTrigger(sequencer.nextSourceOrderIdentity(), ObservationKind.REFRESH,
            requestedAtWallMs = System.currentTimeMillis(), requestedAtElapsedMs = SystemClock.elapsedRealtime()))
        evidence.classify(facts, ForegroundEvidencePolicySnapshot())
        val screen = when (facts.displayState) { DisplayState.SCREEN_OFF -> Screen.OFF; DisplayState.UNLOCKED -> Screen.UNLOCKED; DisplayState.KEYGUARD -> Screen.LOCKED; DisplayState.UNKNOWN -> Screen.UNKNOWN }
        val name = facts.activeRoot.packageName
        val app = if (name == null) null else runCatching { service.packageManager.getApplicationInfo(name, 0) }.getOrNull()
        val foreground = if (screen == Screen.UNLOCKED && facts.activeRoot.readState == ForegroundReadState.AVAILABLE && app != null && app.flags and ApplicationInfo.FLAG_SYSTEM == 0) Foreground.Confirmed(name!!) else Foreground.Unknown
        if (valid && generation.get() == observedGeneration) state.value = state.value.copy(accessibilityEnabled = accessibilityEnabled(), screen = screen, foreground = foreground, observedAtMillis = facts.capturedAtWallMs)
        return state.value
    }
    fun stop() {
        valid = false
        generation.incrementAndGet()
        state.value = state.value.copy(connection = Connection.DISCONNECTED, foreground = Foreground.Unknown)
        hints.cancel()
        worker?.cancel()
        if (registered) { service.unregisterReceiver(receiver); registered = false }
    }
}
