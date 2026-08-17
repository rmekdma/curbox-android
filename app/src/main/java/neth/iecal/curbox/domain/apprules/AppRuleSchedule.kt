package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.utils.UseDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Shared time semantics for rule evaluation and conservative settings comparison. */
object AppRuleSchedule {
    fun activeWindow(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): Pair<Long, Long>? {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        // An overnight interval belongs to the weekday on which it starts. Checking today and
        // yesterday also makes equal start/end intervals represent one full 24 hour period.
        for (anchor in listOf(today, today.minusDays(1))) {
            val window = windowForAnchor(rule, anchor, zone) ?: continue
            if (nowMs >= window.first && nowMs < window.second) return window
        }
        return null
    }

    /** Returns the merged active intervals that fall inside one current use day. */
    fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        zone: ZoneId
    ): List<Pair<Long, Long>> {
        val useDay = UseDay.windowFor(useDayId, zone)
        val date = LocalDate.parse(useDayId)
        val windows = (-1..1).mapNotNull { offset ->
            val window = windowForAnchor(rule, date.plusDays(offset.toLong()), zone)
                ?: return@mapNotNull null
            val start = maxOf(window.first, useDay.first)
            val end = minOf(window.second, useDay.last + 1)
            if (start < end) start to end else null
        }.sortedBy { it.first }

        return windows.fold(mutableListOf()) { merged, window ->
            val previous = merged.lastOrNull()
            if (previous != null && window.first <= previous.second) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, window.second)
            } else {
                merged += window
            }
            merged
        }
    }

    /** Returns minute coverage for all weekdays, including an overnight continuation. */
    fun weeklyCoverage(rule: AppRule): BooleanArray {
        val coverage = BooleanArray(7 * 24 * 60)
        rule.weekdays.filter { it in 0..6 }.forEach { day ->
            val start = rule.startMinute.coerceIn(0, 1439)
            val end = rule.endMinute.coerceIn(0, 1440)
            if (start == end) {
                for (minute in start until 1440) coverage[day * 1440 + minute] = true
                val nextDay = (day + 1) % 7
                for (minute in 0 until start) coverage[nextDay * 1440 + minute] = true
            } else if (start < end) {
                for (minute in start until end) coverage[day * 1440 + minute] = true
            } else {
                for (minute in start until 1440) coverage[day * 1440 + minute] = true
                val nextDay = (day + 1) % 7
                for (minute in 0 until end) coverage[nextDay * 1440 + minute] = true
            }
        }
        return coverage
    }

    private fun windowForAnchor(
        rule: AppRule,
        anchor: LocalDate,
        zone: ZoneId
    ): Pair<Long, Long>? {
        if (weekday(anchor) !in rule.weekdays) return null
        val start = atMinute(anchor, rule.startMinute, zone)
        val endDate = if (rule.endMinute <= rule.startMinute) anchor.plusDays(1) else anchor
        val end = atMinute(endDate, rule.endMinute, zone)
        return start to end
    }

    private fun atMinute(date: LocalDate, minute: Int, zone: ZoneId): Long =
        LocalDateTime.of(date, java.time.LocalTime.MIDNIGHT)
            .plusMinutes(minute.toLong())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun weekday(date: LocalDate): Int = date.dayOfWeek.value % 7
}
