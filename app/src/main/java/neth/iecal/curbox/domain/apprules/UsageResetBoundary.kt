package neth.iecal.curbox.domain.apprules

/**
 * Chooses the reset boundary visible to the service process.  A broadcast can arrive after a
 * heartbeat has already committed a later point; moving the boundary forward prevents an
 * update from making a session end before it started and avoids replaying committed time.
 */
object UsageResetBoundary {
    fun effectiveAt(
        requestedAtMs: Long,
        committedBoundaryMs: Long,
        sessionStartedAtMs: Long = Long.MIN_VALUE
    ): Long = maxOf(requestedAtMs, committedBoundaryMs, sessionStartedAtMs)
}
