package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageTrackingPolicyTest {
    @Test
    fun disabledStatisticsKeepOnlyLedgerWhenRulesNeedIt() {
        val decision = AppUsageTrackingPolicy.decide(
            statisticsTrackingEnabled = false,
            hasActiveTimeBasedRules = true
        )

        assertFalse(decision.recordStatistics)
        assertTrue(decision.recordEnforcementLedger)
        assertTrue(decision.shouldRecordSessions)
    }

    @Test
    fun disabledStatisticsWithoutRulesCanStopRecording() {
        val decision = AppUsageTrackingPolicy.decide(false, false)

        assertFalse(decision.recordStatistics)
        assertFalse(decision.recordEnforcementLedger)
        assertFalse(decision.shouldRecordSessions)
    }
}
