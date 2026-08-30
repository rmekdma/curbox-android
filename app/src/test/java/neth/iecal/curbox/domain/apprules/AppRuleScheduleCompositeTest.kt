package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime

class AppRuleScheduleCompositeTest {
    private val zone = ZoneId.of("UTC")

    @Test
    fun equalEndpointsAreTwentyFourHoursAndTouchingRangesAreMerged() {
        val rule = AppRule(
            id = "full",
            name = "Full day",
            weekdays = setOf(1),
            timeRanges = listOf(
                AppRuleTimeRange(9 * 60, 12 * 60),
                AppRuleTimeRange(12 * 60, 14 * 60)
            )
        )

        val merged = AppRuleSchedule.usageWindowsForUseDay(rule, "2026-08-17", zone)

        assertEquals(1, merged.size)
        assertEquals(9 * 60 * 60_000L, merged.single().startMs -
            Instant.parse("2026-08-17T00:00:00Z").toEpochMilli())
        assertEquals(14 * 60 * 60_000L, merged.single().endMs -
            Instant.parse("2026-08-17T00:00:00Z").toEpochMilli())

        val fullDay = rule.copy(timeRanges = listOf(AppRuleTimeRange(10 * 60, 10 * 60)))
        val fullDayWindow = AppRuleSchedule.activeWindow(
            fullDay,
            Instant.parse("2026-08-17T12:00:00Z").toEpochMilli(),
            zone
        )
        assertEquals(24 * 60 * 60_000L, fullDayWindow!!.endMs - fullDayWindow.startMs)
    }

    @Test
    fun overnightRangeBelongsToItsStartWeekday() {
        val rule = AppRule(
            id = "overnight",
            name = "Overnight",
            weekdays = setOf(1),
            timeRanges = listOf(AppRuleTimeRange(22 * 60, 6 * 60))
        )

        assertTrue(
            AppRuleSchedule.activeWindow(
                rule,
                Instant.parse("2026-08-18T01:00:00Z").toEpochMilli(),
                zone
            ) != null
        )
    }

    @Test
    fun nextBoundaryIncludesAStartWhenTheRuleIsCurrentlyInactive() {
        val rule = AppRule(
            id = "upcoming",
            name = "Upcoming",
            weekdays = setOf(1),
            timeRanges = listOf(AppRuleTimeRange(11 * 60, 12 * 60))
        )
        val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()

        assertEquals(
            Instant.parse("2026-08-17T11:00:00Z").toEpochMilli(),
            AppRuleSchedule.nextBoundaryAfter(rule, now, zone)
        )
    }

    @Test
    fun nextBoundaryIncludesTheEndOfAnOvernightWindowStartedYesterday() {
        val rule = AppRule(
            id = "overnight",
            name = "Overnight",
            weekdays = setOf(1),
            timeRanges = listOf(AppRuleTimeRange(22 * 60, 6 * 60))
        )
        val now = Instant.parse("2026-08-18T01:00:00Z").toEpochMilli()

        assertEquals(
            Instant.parse("2026-08-18T06:00:00Z").toEpochMilli(),
            AppRuleSchedule.nextBoundaryAfter(rule, now, zone)
        )
    }

    @Test
    fun nextBoundaryIncludesTheEndOfAWeeklyWindowWhileItIsActive() {
        val rule = AppRule(
            id = "weekly",
            name = "Weekly",
            weekdays = setOf(1),
            timeRanges = listOf(AppRuleTimeRange(9 * 60, 17 * 60))
        )
        val now = Instant.parse("2026-08-24T10:00:00Z").toEpochMilli()

        assertEquals(
            Instant.parse("2026-08-24T17:00:00Z").toEpochMilli(),
            AppRuleSchedule.nextBoundaryAfter(rule, now, zone)
        )
    }

    @Test
    fun nextBoundaryUsesTheMergedEndForOverlappingRanges() {
        val rule = AppRule(
            id = "overlap",
            name = "Overlapping",
            weekdays = setOf(1),
            timeRanges = listOf(
                AppRuleTimeRange(9 * 60, 12 * 60),
                AppRuleTimeRange(11 * 60, 14 * 60)
            )
        )
        val now = Instant.parse("2026-08-17T10:00:00Z").toEpochMilli()

        assertEquals(
            Instant.parse("2026-08-17T14:00:00Z").toEpochMilli(),
            AppRuleSchedule.nextBoundaryAfter(rule, now, zone)
        )
    }

    @Test
    fun continuousAllDayScheduleHasNoArtificialBoundary() {
        val rule = AppRule(
            id = "always",
            name = "Always active",
            weekdays = (0..6).toSet(),
            timeRanges = listOf(AppRuleTimeRange(0, 0))
        )
        val now = Instant.parse("2026-08-17T10:00:00Z").toEpochMilli()

        assertNull(AppRuleSchedule.nextBoundaryAfter(rule, now, zone))
    }

    @Test
    fun recheckPlannerSchedulesAnInactiveRuleThatWillStartForTheForegroundPackage() {
        val rule = AppRule(
            id = "upcoming",
            name = "Upcoming lockdown",
            weekdays = setOf(1),
            timeRanges = listOf(AppRuleTimeRange(11 * 60, 12 * 60)),
            scope = neth.iecal.curbox.data.models.AppRuleScope(includeAllApps = true),
            allowedMinutes = 0
        )
        val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        val snapshot = AppRuleSnapshot(appRules = listOf(rule))
        val evaluation = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.example.reader",
            useDayId = "2026-08-17",
            sessions = emptyList(),
            nowMs = now,
            zone = zone,
            // A non-empty launcher listing may omit the package currently in the foreground.
            availablePackages = setOf("com.example.other")
        )

        val plan = AppRuleRecheckPlanner.nextPlan(
            snapshot = snapshot,
            evaluation = evaluation,
            overrideState = AppRuleOverrideState(),
            useDayId = "2026-08-17",
            nowMs = now,
            zone = zone
        ) ?: error("an applicable upcoming rule must have a recheck plan")

        assertEquals(30 * 60_000L, plan.delayMillis)
        assertEquals(Long.MAX_VALUE, plan.maxDelayMillis)
    }

    @Test
    fun recheckPlannerIncludesTheNextUseDayResetForAnAllDayAllowance() {
        val rule = AppRule(
            id = "always",
            name = "Always active",
            weekdays = (0..6).toSet(),
            timeRanges = listOf(AppRuleTimeRange(0, 0)),
            scope = neth.iecal.curbox.data.models.AppRuleScope(includeAllApps = true),
            allowedMinutes = 24 * 60
        )
        val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        val calculator = ConfigurableUseDayCalculator(
            zone = zone,
            resetTime = UseDayResetTime(hour = 4, minute = 0)
        )
        val snapshot = AppRuleSnapshot(appRules = listOf(rule))
        val evaluation = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.example.reader",
            useDayId = calculator.idAt(now),
            sessions = emptyList(),
            nowMs = now,
            zone = zone,
            useDayCalculator = calculator,
            availablePackages = setOf("com.example.reader")
        )

        val plan = AppRuleRecheckPlanner.nextPlan(
            snapshot = snapshot,
            evaluation = evaluation,
            overrideState = AppRuleOverrideState(),
            useDayId = calculator.idAt(now),
            nowMs = now,
            zone = zone,
            useDayCalculator = calculator
        ) ?: error("an all-day rule must have a reset recheck plan")

        assertEquals(
            Instant.parse("2026-08-18T04:00:00Z").toEpochMilli() - now,
            plan.delayMillis
        )
        assertEquals(Long.MAX_VALUE, plan.maxDelayMillis)
    }
}
