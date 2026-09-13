package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRuleWallClockSchedulerTest {
    @Test
    fun wallClockDueTimeUsesElapsedDelayAndIsRecomputedAfterWake() {
        val dueAtWallClockMs = 1_000_030_000L

        assertEquals(
            30_000L,
            AppRuleWallClockScheduler.delayUntil(
                dueAtWallClockMs = dueAtWallClockMs,
                nowWallClockMs = 1_000_000_000L,
                nowElapsedRealtimeMs = 500_000L
            )
        )
        assertEquals(
            0L,
            AppRuleWallClockScheduler.delayUntil(
                dueAtWallClockMs = dueAtWallClockMs,
                nowWallClockMs = 1_000_035_000L,
                nowElapsedRealtimeMs = 535_000L
            )
        )
    }
}
