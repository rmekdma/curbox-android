package neth.iecal.curbox.domain.apprules

import java.time.ZoneId
import neth.iecal.curbox.data.models.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoSettlementTest {
    private val link = TodoAwardConnection("account", "Child", "rule", "2026-11-02", "revision")
    private val rule = AppRule(id = "rule", guardianExtraTimeAllowed = true, rolloverEnabled = true, unlockDays = setOf(0, 6))
    private val base = Settings(todoAwardConnection = link, appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule)),
        appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to RuleRolloverPool("rule", 20, "2026-11-03"))))

    @Test fun settledReportCreditsOnlyUnpaidMinutesOnce() {
        val initial = base.copy(todoAwardLedger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-02", 60)))
        val now = java.time.Instant.parse("2026-11-03T04:00:00Z").toEpochMilli()
        val first = TodoSettlement.complete(initial, link, "2026-11-02", 75, now, ZoneId.of("UTC"))
        assertEquals(35L, first.appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
        assertEquals(first, TodoSettlement.complete(first, link, "2026-11-02", 75, now, ZoneId.of("UTC")))
    }

    @Test fun expiredReportCannotCreditNextWeekend() {
        val now = java.time.Instant.parse("2026-11-09T04:00:00Z").toEpochMilli()
        assertEquals(base, TodoSettlement.complete(base, link, "2026-11-02", 75, now, ZoneId.of("UTC")))
    }
    @Test fun zeroAndCorrectedReportsLeaveExistingRolloverIntact() {
        val now = java.time.Instant.parse("2026-11-03T04:00:00Z").toEpochMilli()
        val initial = base.copy(todoAwardLedger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-02", 60)))
        assertEquals(20L, TodoSettlement.complete(initial, link, "2026-11-02", 40, now, ZoneId.of("UTC"))
            .appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
        assertEquals(95L, TodoSettlement.complete(base, link, "2026-11-02", 75, now, ZoneId.of("UTC"))
            .appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
    }

    @Test fun resetClockControlsReportDateAndSundayIsExpired() {
        val zone = ZoneId.of("UTC")
        val beforeReset = java.time.Instant.parse("2026-11-03T05:59:00Z").toEpochMilli()
        val afterReset = java.time.Instant.parse("2026-11-03T06:00:00Z").toEpochMilli()
        val settings = base.copy(useDayResetHour = 6)
        assertEquals(emptyList<String>(), TodoSettlement.dates(settings, beforeReset, zone))
        assertEquals(listOf("2026-11-02"), TodoSettlement.dates(settings, afterReset, zone))
        val monday = java.time.Instant.parse("2026-11-09T06:00:00Z").toEpochMilli()
        assertEquals(emptyList<String>(), TodoSettlement.dates(settings, monday, zone))
    }

    @Test fun changedConnectionOrRuleCannotReceiveLateResult() {
        val now = java.time.Instant.parse("2026-11-03T04:00:00Z").toEpochMilli()
        val disconnected = base.copy(todoAwardConnection = null)
        assertEquals(disconnected, TodoSettlement.complete(disconnected, link, "2026-11-02", 75, now, ZoneId.of("UTC")))
        val disabled = base.copy(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule.copy(guardianExtraTimeAllowed = false))))
        assertEquals(disabled, TodoSettlement.complete(disabled, link, "2026-11-02", 75, now, ZoneId.of("UTC")))
    }
}
