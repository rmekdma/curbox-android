package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

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
}
