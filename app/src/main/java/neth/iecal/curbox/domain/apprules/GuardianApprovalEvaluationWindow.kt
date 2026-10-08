package neth.iecal.curbox.domain.apprules

/** Keeps an approval result current only until the next policy boundary from its evaluation. */
object GuardianApprovalEvaluationWindow {
    fun isCurrent(
        capturedAtWallClockMs: Long,
        capturedAtElapsedRealtimeMs: Long,
        validUntilWallClockMs: Long,
        nowWallClockMs: Long,
        nowElapsedRealtimeMs: Long
    ): Boolean {
        if (capturedAtWallClockMs < 0L || capturedAtElapsedRealtimeMs < 0L ||
            validUntilWallClockMs < capturedAtWallClockMs ||
            nowWallClockMs < capturedAtWallClockMs ||
            nowElapsedRealtimeMs < capturedAtElapsedRealtimeMs
        ) return false
        if (validUntilWallClockMs == Long.MAX_VALUE) return true

        val boundaryDelayMs = validUntilWallClockMs - capturedAtWallClockMs
        if (boundaryDelayMs <= 0L) return false
        val elapsedDeadlineMs = safeAdd(capturedAtElapsedRealtimeMs, boundaryDelayMs)
        return nowWallClockMs < validUntilWallClockMs &&
            nowElapsedRealtimeMs < elapsedDeadlineMs
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) {
            Long.MAX_VALUE
        } else {
            first + second
        }
}
