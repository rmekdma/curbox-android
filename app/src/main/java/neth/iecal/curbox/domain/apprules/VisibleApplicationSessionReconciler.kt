package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayCalculator
import java.time.ZoneId

/** Handle for a session currently represented in the visible application package set. */
data class TrackedForegroundSession(
    val packageName: String,
    val sessionId: Long,
    val useDayId: String,
    val startedAtMs: Long
)

data class SessionReconciliationResult(
    val activeSessions: Map<String, TrackedForegroundSession>,
    val endedPackages: Set<String>,
    val startedPackages: Set<String>
)

/**
 * Serialized session boundary seam. It reconciles a complete package set, closes only packages
 * that disappeared, and starts new rows after all prior rows have been flushed.
 */
class VisibleApplicationSessionReconciler(
    private val repository: CurrentUseDaySessionRepository,
    private val useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(
        zone = ZoneId.systemDefault()
    )
) {
    suspend fun reconcile(
        current: Map<String, TrackedForegroundSession>,
        visiblePackages: Set<String>,
        nowMs: Long
    ): SessionReconciliationResult {
        val next = LinkedHashMap<String, TrackedForegroundSession>()
        val ended = LinkedHashSet<String>()
        val started = LinkedHashSet<String>()
        val currentUseDayId = useDayCalculator.idAt(nowMs)

        // A reset boundary is a session boundary even when the package remains visible.
        current.forEach { (packageName, session) ->
            if (packageName !in visiblePackages || session.useDayId != currentUseDayId) {
                val boundary = useDayCalculator.windowFor(session.useDayId).last + 1L
                val endedAt = if (session.useDayId != currentUseDayId) {
                    minOf(nowMs, boundary)
                } else {
                    nowMs
                }
                repository.finishSession(session.sessionId, endedAt)
                ended += packageName
                if (packageName in visiblePackages) {
                    val id = repository.startSession(currentUseDayId, packageName, endedAt)
                    next[packageName] = TrackedForegroundSession(
                        packageName,
                        id,
                        currentUseDayId,
                        endedAt
                    )
                    started += packageName
                }
            } else {
                next[packageName] = session
            }
        }

        // Starts are intentionally after all finishes so callers can perform their serialized
        // usage write and then run the next rule decision.
        visiblePackages.forEach { packageName ->
            if (packageName !in next) {
                val useDayId = currentUseDayId
                val id = repository.startSession(useDayId, packageName, nowMs)
                next[packageName] = TrackedForegroundSession(packageName, id, useDayId, nowMs)
                started += packageName
            }
        }
        return SessionReconciliationResult(next, ended, started)
    }
}

/** Short compatibility name for consumers that only need the current use-day seam. */
typealias CurrentUseDayForegroundSessionReconciler = VisibleApplicationSessionReconciler
