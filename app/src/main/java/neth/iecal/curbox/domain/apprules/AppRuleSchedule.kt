package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.utils.UseDay
import neth.iecal.curbox.utils.UseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Shared time semantics for rule evaluation and conservative settings comparison. */
object AppRuleSchedule {
    /** All merged windows that contain [nowMs], including an overnight window started yesterday. */
    fun activeWindows(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): List<Pair<Long, Long>> {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        val windows = (-1..0).flatMap { offset ->
            windowsForAnchor(rule, today.plusDays(offset.toLong()), zone)
        }.filter { nowMs >= it.first && nowMs < it.second }
        return merge(windows)
    }

    fun activeWindow(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): Pair<Long, Long>? {
        return activeWindows(rule, nowMs, zone).firstOrNull()
    }

    /** Returns the merged active intervals that fall inside one current use day. */
    fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        zone: ZoneId
    ): List<Pair<Long, Long>> = usageWindowsForUseDay(
        rule = rule,
        useDayId = useDayId,
        zone = zone,
        resetTime = UseDayResetTime()
    )

    fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        zone: ZoneId,
        resetTime: UseDayResetTime
    ): List<Pair<Long, Long>> {
        val useDay = UseDay.windowFor(useDayId, zone, resetTime)
        val date = LocalDate.parse(useDayId)
        val windows = (-2..2).flatMap { offset ->
            windowsForAnchor(rule, date.plusDays(offset.toLong()), zone)
        }.mapNotNull { window ->
            val start = maxOf(window.first, useDay.first)
            val end = minOf(window.second, useDay.last + 1)
            if (start < end) start to end else null
        }
        return merge(windows)
    }

    fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        calculator: UseDayCalculator
    ): List<Pair<Long, Long>> = usageWindowsForUseDay(
        rule,
        useDayId,
        calculator.zone,
        calculator.resetTime
    )

    /** Returns minute coverage for all weekdays, including an overnight continuation. */
    fun weeklyCoverage(rule: AppRule): BooleanArray {
        val coverage = BooleanArray(7 * 24 * 60)
        rule.weekdays.filter { it in 0..6 }.forEach { day ->
            rule.effectiveTimeRanges().forEach { range ->
                val start = range.startMinute.coerceIn(0, 1439)
                val end = range.endMinute.coerceIn(0, 1440)
                val duration = when {
                    start == end -> 24 * 60
                    end > start -> end - start
                    else -> 24 * 60 - start + end
                }
                repeat(duration) { offset ->
                    val absoluteMinute = start + offset
                    val dayOffset = absoluteMinute / (24 * 60)
                    val minute = absoluteMinute % (24 * 60)
                    val coveredDay = (day + dayOffset) % 7
                    coverage[coveredDay * 1440 + minute] = true
                }
            }
        }
        return coverage
    }

    private fun windowsForAnchor(
        rule: AppRule,
        anchor: LocalDate,
        zone: ZoneId
    ): List<Pair<Long, Long>> {
        if (weekday(anchor) !in rule.weekdays) return emptyList()
        return rule.effectiveTimeRanges().map { range ->
            val start = atMinute(anchor, range.startMinute, zone)
            val endDate = if (range.endMinute <= range.startMinute) anchor.plusDays(1) else anchor
            val end = atMinute(endDate, range.endMinute, zone)
            start to end
        }
    }

    private fun merge(windows: Iterable<Pair<Long, Long>>): List<Pair<Long, Long>> =
        windows.filter { it.first < it.second }.sortedBy { it.first }
            .fold(mutableListOf()) { merged, window ->
                val previous = merged.lastOrNull()
                if (previous != null && window.first <= previous.second) {
                    merged[merged.lastIndex] = previous.first to maxOf(previous.second, window.second)
                } else {
                    merged += window
                }
                merged
            }

    private fun atMinute(date: LocalDate, minute: Int, zone: ZoneId): Long =
        LocalDateTime.of(date, java.time.LocalTime.MIDNIGHT)
            .plusMinutes(minute.toLong())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun weekday(date: LocalDate): Int = date.dayOfWeek.value % 7
}
