package neth.iecal.plugin

import kotlinx.coroutines.flow.StateFlow

/** Current host observation. Unknown never establishes the foreground application. */
data class HostObservation(
    val connection: Connection = Connection.DISCONNECTED,
    val accessibilityEnabled: Boolean = false,
    val callbackReceived: Boolean = false,
    val screen: Screen = Screen.UNKNOWN,
    val foreground: Foreground = Foreground.Unknown,
    val observedAtMillis: Long = 0
)
enum class Connection { DISCONNECTED, CONNECTED }
enum class Screen { UNKNOWN, OFF, UNLOCKED, LOCKED }
sealed interface Foreground {
    data object Unknown : Foreground
    data class Confirmed(val packageName: String) : Foreground
}
interface CurboxHost {
    val observations: StateFlow<HostObservation>
    /** Refreshes off the caller thread; cancellation is propagated. */
    suspend fun refreshObservation(): HostObservation
}
/** Runs for the lifetime of the host connection. Cancellation stops its child work. */
interface CurboxPlugin { suspend fun run(host: CurboxHost) }
