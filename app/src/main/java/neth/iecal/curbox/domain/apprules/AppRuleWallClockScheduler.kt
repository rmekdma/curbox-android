package neth.iecal.curbox.domain.apprules

/** Converts a wall clock deadline into a delay for an elapsed time scheduler. */
object AppRuleWallClockScheduler {
    fun delayUntil(
        dueAtWallClockMs: Long,
        nowWallClockMs: Long,
        nowElapsedRealtimeMs: Long
    ): Long {
        if (dueAtWallClockMs <= nowWallClockMs) return 0L
        val wallClockRemainingMs = dueAtWallClockMs - nowWallClockMs
        val monotonicDueAtMs = if (
            wallClockRemainingMs > Long.MAX_VALUE - nowElapsedRealtimeMs
        ) {
            Long.MAX_VALUE
        } else {
            nowElapsedRealtimeMs + wallClockRemainingMs
        }
        return (monotonicDueAtMs - nowElapsedRealtimeMs).coerceAtLeast(0L)
    }
}
