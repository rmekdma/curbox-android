package neth.iecal.curbox.domain.apprules

import java.time.ZoneId
import neth.iecal.curbox.data.models.*
import neth.iecal.curbox.utils.clearGuardianExtraTimeForRules
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoSettlementTest {
    private val link = TodoAwardConnection("account", "Child", "rule", "2026-11-02", "revision")
    private val rule = AppRule(id = "rule", guardianExtraTimeAllowed = true, rolloverEnabled = true, unlockDays = setOf(0, 6))
    private val base = Settings(todoAwardConnection = link, appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule)),
        appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to RuleRolloverPool("rule", 20, "2026-11-03"))))

    @Test fun rolloverPersistsFailedReportForRestartAndCompletesOnlyOnce() {
        val friday = "2026-11-06"
        val saturday = "2026-11-07"
        val prior = base.copy(appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to RuleRolloverPool("rule", 0, friday))))
        val ordinary = AppRuleRolloverPolicy.reconcileSettlement(prior.appRuleRolloverState.pools.getValue("rule"), rule, saturday, 20).pool
        val stored = TodoSettlement.recordPending(prior, AppRuleRolloverState(mapOf("rule" to ordinary)))
        assertEquals(listOf(friday), stored.todoPendingSettlements.map { it.useDayId })
        var offline = true
        val api = object : neth.iecal.todo.TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: neth.iecal.todo.TodoSession) = listOf(
                neth.iecal.todo.TodoChild("Child", mapOf(neth.iecal.todo.Subject.MATH to "math")))
            override fun report(session: neth.iecal.todo.TodoSession, subject: neth.iecal.todo.Subject, userId: String, date: String): neth.iecal.todo.DailyReport {
                assertEquals("20261106", date)
                if (offline) throw IllegalStateException("offline")
                return neth.iecal.todo.DailyReport(0, mapOf("study" to java.math.BigDecimal("4500")))
            }
        }
        val lookup = neth.iecal.todo.TodoAwardLookup(api)
        val session = neth.iecal.todo.TodoSession("account", "fake")
        org.junit.Assert.assertTrue(runCatching { lookup.minutes(session, "Child", "20261106") }.isFailure)
        assertEquals(20L, stored.appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
        val restored = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(stored), Settings::class.java)
        offline = false
        val reported = lookup.minutes(session, "Child", "20261106")
        val now = java.time.Instant.parse("2026-11-07T04:00:00Z").toEpochMilli()
        val completed = TodoSettlement.complete(restored, link, friday, reported, now, ZoneId.of("UTC"))
        assertEquals(95L, completed.appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
        assertEquals(emptyList<Any>(), completed.todoPendingSettlements)
        assertEquals(completed, TodoSettlement.complete(completed, link, friday, 75, now, ZoneId.of("UTC")))
    }

    @Test fun expiredOrReplacedConnectionDropsPendingWithoutCredit() {
        val friday = "2026-11-06"
        val pending = neth.iecal.curbox.data.models.TodoPendingSettlement(link, friday)
        val stored = base.copy(todoPendingSettlements = listOf(pending))
        val monday = java.time.Instant.parse("2026-11-09T04:00:00Z").toEpochMilli()
        val expired = TodoSettlement.prunePending(stored, monday, ZoneId.of("UTC"))
        assertEquals(emptyList<Any>(), expired.todoPendingSettlements)
        assertEquals(expired, TodoSettlement.complete(expired, link, friday, 75, monday, ZoneId.of("UTC")))
        val replaced = stored.copy(todoAwardConnection = link.copy(revision = "new"))
        val saturday = java.time.Instant.parse("2026-11-07T04:00:00Z").toEpochMilli()
        assertEquals(emptyList<Any>(), TodoSettlement.prunePending(replaced, saturday, ZoneId.of("UTC")).todoPendingSettlements)
    }

    @Test fun clockMovingBackRetainsFuturePendingReport() {
        val pending = neth.iecal.curbox.data.models.TodoPendingSettlement(link, "2026-11-06")
        val stored = base.copy(todoPendingSettlements = listOf(pending))
        val wednesday = java.time.Instant.parse("2026-11-04T04:00:00Z").toEpochMilli()
        assertEquals(stored, TodoSettlement.prunePending(stored, wednesday, ZoneId.of("UTC")))
    }

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
    @Test fun disablingAndReenablingCannotRestoreClearedBalanceFromLateReport() {
        val now = java.time.Instant.parse("2026-11-03T04:00:00Z").toEpochMilli()
        val disabled = base.clearGuardianExtraTimeForRules(setOf("rule"), "2026-11-03")
        assertEquals("2026-11-03", disabled.todoAwardConnection?.connectedUseDayId)
        val reenabled = disabled.copy(appRuleSnapshot = base.appRuleSnapshot)
        assertEquals(emptyList<String>(), TodoSettlement.dates(reenabled, now, ZoneId.of("UTC")))
        assertEquals(reenabled, TodoSettlement.complete(reenabled, link, "2026-11-02", 75, now, ZoneId.of("UTC"), base))
    }

    @Test fun fakeReportJoinsOrdinaryRolloverAndExpiresAfterWeekend() {
        val friday = "2026-11-06"
        val saturday = "2026-11-07"
        val sunday = "2026-11-08"
        val monday = "2026-11-09"
        val fake = object : neth.iecal.todo.TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: neth.iecal.todo.TodoSession) = listOf(
                neth.iecal.todo.TodoChild("Child", mapOf(neth.iecal.todo.Subject.MATH to "math")))
            override fun report(session: neth.iecal.todo.TodoSession, subject: neth.iecal.todo.Subject, userId: String, date: String): neth.iecal.todo.DailyReport {
                assertEquals("20261106", date)
                return neth.iecal.todo.DailyReport(0, mapOf("study" to java.math.BigDecimal("4500")))
            }
        }
        val reported = neth.iecal.todo.TodoAwardLookup(fake).minutes(neth.iecal.todo.TodoSession("account", "fake"), "Child", "20261106")
        val ordinary = AppRuleRolloverPolicy.reconcileSettlement(RuleRolloverPool("rule", 0, friday), rule, saturday, 20)
        val settings = base.copy(appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to ordinary.pool)),
            todoAwardLedger = listOf(TodoAwardLedgerEntry("account", "Child", friday, 60)))
        val now = java.time.Instant.parse("2026-11-07T04:00:00Z").toEpochMilli()
        val settled = TodoSettlement.complete(settings, link, friday, reported, now, ZoneId.of("UTC"))
        val pool = settled.appRuleRolloverState.pools.getValue("rule")
        assertEquals(35L, pool.accumulatedMinutes)
        val afterApproval = pool.copy(accumulatedMinutes = pool.accumulatedMinutes - 20)
        val sundayPool = AppRuleRolloverPolicy.reconcileSettlement(afterApproval, rule, sunday, 10).pool
        assertEquals(25L, sundayPool.accumulatedMinutes)
        assertEquals(0L, AppRuleRolloverPolicy.reconcileSettlement(sundayPool, rule, monday).pool.accumulatedMinutes)
    }
}
