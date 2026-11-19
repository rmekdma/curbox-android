package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleRestConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RestCycleGuardianSkipTest {
    @Test
    fun suspendedTargetUsageDoesNotChargeOrCountAsRest() {
        val tracker = RestCycleTracker()
        val configs = mapOf("rest" to AppRuleRestConfig(1, 10, 1))
        tracker.observe(configs, mapOf("rest" to true), 0L)
        tracker.observe(configs, mapOf("rest" to null), 30_000L, setOf("rest"))
        tracker.observe(configs, mapOf("rest" to true), 150_000L)
        assertEquals(30_000L, tracker.usedMillis("rest"))
        tracker.observe(configs, mapOf("rest" to true), 180_000L)
        assertEquals(60_000L, tracker.usedMillis("rest"))
    }

    @Test
    fun restOutsideTargetStillCompletesCycleDuringSkip() {
        val tracker = RestCycleTracker()
        val configs = mapOf("rest" to AppRuleRestConfig(1, 10, 1))
        tracker.observe(configs, mapOf("rest" to true), 0L)
        tracker.observe(configs, mapOf("rest" to null), 30_000L, setOf("rest"))
        tracker.observe(configs, mapOf("rest" to false), 60_000L)
        tracker.observe(configs, mapOf("rest" to true), 120_000L)
        assertEquals(0L, tracker.usedMillis("rest"))
    }
}
