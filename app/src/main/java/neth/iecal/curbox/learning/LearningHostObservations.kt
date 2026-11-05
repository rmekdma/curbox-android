package neth.iecal.curbox.learning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import neth.iecal.plugin.Connection
import neth.iecal.plugin.Foreground
import neth.iecal.plugin.HostObservation
import neth.iecal.plugin.Screen

data class LearningHostCapture(val accessibilityEnabled: Boolean, val screen: Screen, val foreground: Foreground, val observedAtMillis: Long)

/** Serializes observation publication with connection and foreground invalidation. */
class LearningHostObservations {
    private val lock = Any()
    private val state = MutableStateFlow(HostObservation(connection = Connection.CONNECTED))
    val observations: StateFlow<HostObservation> = state
    private var valid = true
    private var generation = 0L

    fun isActive(): Boolean = synchronized(lock) { valid }

    /** Reads may block; only their publication is serialized with events and shutdown. */
    fun capture(read: () -> LearningHostCapture): HostObservation {
        val token = synchronized(lock) { if (!valid) return state.value else generation }
        val capture = read()
        return synchronized(lock) {
            if (valid && generation == token) {
                state.value = state.value.copy(accessibilityEnabled = capture.accessibilityEnabled, screen = capture.screen,
                    foreground = capture.foreground, observedAtMillis = capture.observedAtMillis)
            }
            state.value
        }
    }

    fun screenChanged(screen: Screen): Boolean = synchronized(lock) {
        if (!valid) return false
        generation++
        state.value = state.value.copy(screen = screen, foreground = Foreground.Unknown)
        true
    }

    fun event(relevant: Boolean): Boolean = synchronized(lock) {
        if (!valid) return false
        if (relevant) generation++
        state.value = state.value.copy(callbackReceived = true,
            foreground = if (relevant) Foreground.Unknown else state.value.foreground)
        relevant
    }

    fun invalidate(): HostObservation = synchronized(lock) {
        if (valid) {
            generation++
            state.value = state.value.copy(foreground = Foreground.Unknown)
        }
        state.value
    }

    fun stop() = synchronized(lock) {
        valid = false
        generation++
        state.value = state.value.copy(connection = Connection.DISCONNECTED, screen = Screen.UNKNOWN, foreground = Foreground.Unknown)
    }
}
