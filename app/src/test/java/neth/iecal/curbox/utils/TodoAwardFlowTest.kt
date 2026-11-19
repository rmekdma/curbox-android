package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.*
import neth.iecal.curbox.domain.apprules.AppRuleRolloverPolicy
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.TodoSettlement
import neth.iecal.curbox.domain.apprules.TodoAwardFormState
import neth.iecal.todo.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.time.format.DateTimeFormatter

class TodoAwardFlowTest {
    @Test fun `fake report partial approval leaves only unpaid learning time`() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
        val day = ConfigurableUseDayCalculator().idAt(now)
        val link = TodoAwardConnection("account", "Child", "rule", day, "first")
        var current = Settings(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(
            AppRule(id = "rule", rolloverEnabled = true, unlockDays = setOf(0, 6))
        )), todoAwardConnection = link)
        val first = GuardianExtraTimeGrantBasis.capture(current, "rule", now)!!
        current = GuardianExtraTimeGrantWrite.nextSettings(current, "", first, 20, now, now)
        val grant = GuardianExtraTimeGrantBasis.capture(current, "rule", now)!!
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "m", Subject.ENGLISH to "e")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                assertEquals(LocalDate.parse(day).format(DateTimeFormatter.BASIC_ISO_DATE), date)
                return DailyReport(0, mapOf("course" to BigDecimal("1800"), "etc" to BigDecimal("999999")))
            }
        }
        val minutes = TodoAwardLookup(api).minutes(TodoSession("account", "fake"), "Child", day.replace("-", ""))
        val offer = TodoAwardGrantBasis.capture(current, grant, minutes)!!
        val form = TodoAwardFormState.initial(grant.currentTotalMinutes).receiveOffer(0, offer.availableMinutes)
        assertEquals(80L, form.totalMinutes)
        val submission = form.editTodo("40").submission()!!
        current = TodoAwardGrantWrite.nextSettings(current, "", offer, submission.first, submission.second, now + 1, now + 1)
        val nextGrant = GuardianExtraTimeGrantBasis.capture(current, "rule", now + 1)!!
        assertEquals(60L, nextGrant.currentTotalMinutes)
        assertEquals(20L, TodoAwardGrantBasis.capture(current, nextGrant, minutes)!!.availableMinutes)
        assertEquals(40L, current.todoAwardLedger.single().grantedMinutes)
    }
    @Test fun `partial approval survives restart and only unpaid report reaches weekend pool`() {
        val zone = ZoneId.of("Asia/Seoul")
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            val friday = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
            val saturday = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
            val day = ConfigurableUseDayCalculator(zone = zone).idAt(friday)
            val nextDay = ConfigurableUseDayCalculator(zone = zone).idAt(saturday)
            val link = TodoAwardConnection("account", "Child", "rule", day, "revision")
            val rule = AppRule(id = "rule", rolloverEnabled = true, unlockDays = setOf(0, 6))
            val initial = Settings(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule)), todoAwardConnection = link,
                appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to RuleRolloverPool("rule", 0, day))))
            val basis = GuardianExtraTimeGrantBasis.capture(initial, "rule", friday, zone)!!
            val offer = TodoAwardGrantBasis.capture(initial, basis, 75)!!
            val approved = TodoAwardGrantWrite.nextSettings(initial, "", offer, 0, 60, friday, friday)
            assertEquals(60L, approved.todoAwardLedger.single().grantedMinutes)
            val pool = AppRuleRolloverPolicy.reconcileSettlement(
                approved.appRuleRolloverState.pools.getValue("rule"), rule, nextDay, 20).pool
            val pending = TodoSettlement.recordPending(approved, AppRuleRolloverState(mapOf("rule" to pool)))
            val restored = com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(pending), Settings::class.java)
            val settled = TodoSettlement.complete(restored, link, day, 75, saturday, zone)
            assertEquals(35L, settled.appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
            assertTrue(settled.todoAwardLedger.single().settled)
            assertEquals(settled, TodoSettlement.complete(settled, link, day, 75, saturday, zone))
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

}
