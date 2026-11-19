package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleRestConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RestCycleRecoveryTest {
    private val config = mapOf("rule" to AppRuleRestConfig(1, 2, 1))

    @Test fun restoresConfirmedUsageWithoutCountingDowntime() {
        val first = RestCycleTracker()
        first.observe(config, mapOf("rule" to true), 1_000L)
        first.observe(config, mapOf("rule" to false), 31_000L)
        val saved = first.snapshot(100_000L)
        val restored = RestCycleTracker()
        restored.restore(saved, config, 300_000L, 2_000L)
        restored.observe(config, mapOf("rule" to true), 2_000L)
        assertEquals(30_000L, restored.usedMillis("rule"))
        assertEquals(30_000L, restored.nextDelayMillis())
    }

    @Test fun recentRestBeforeRebootRemainsEligible() {
        val first = RestCycleTracker()
        first.observe(config, mapOf("rule" to true), 0L)
        first.observe(config, mapOf("rule" to false), 60_000L)
        first.observe(config, mapOf("rule" to true), 90_000L)
        val saved = first.snapshot(1_000_000L)
        val restored = RestCycleTracker()
        restored.restore(saved, config, 1_010_000L, 1_000L)
        restored.observe(config, mapOf("rule" to false), 1_000L)
        assertEquals(30_000L, restored.nextDelayMillis())
        restored.observe(config, mapOf("rule" to false), 31_000L)
        assertEquals(0L, restored.usedMillis("rule"))
    }

    @Test fun oldRestExpiresDuringDowntime() {
        val first = RestCycleTracker()
        first.observe(config, mapOf("rule" to true), 0L)
        first.observe(config, mapOf("rule" to false), 60_000L)
        first.observe(config, mapOf("rule" to true), 90_000L)
        val saved = first.snapshot(1_000_000L)
        val restored = RestCycleTracker()
        restored.restore(saved, config, 1_120_000L, 1_000L)
        restored.observe(config, mapOf("rule" to false), 1_000L)
        assertEquals(60_000L, restored.nextDelayMillis())
    }
}
