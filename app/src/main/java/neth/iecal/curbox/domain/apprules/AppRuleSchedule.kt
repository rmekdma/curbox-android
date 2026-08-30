package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.utils.UseDay
import neth.iecal.curbox.utils.UseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A named epoch interval shared by schedule and allowance calculations. */
data class AppRuleInterval(
    val startMs: Long,
    val endMs: Long
)

/** Shared time semantics for rule evaluation and conservative settings comparison. */
object AppRuleSchedule {
    /** All merged windows that contain [nowMs], including an overnight window started yesterday. */
    fun activeWindows(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): List<AppRuleInterval> {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        val windows = (-1..0).flatMap { offset ->
            windowsForAnchor(rule, today.plusDays(offset.toLong()), zone)
        }.filter { nowMs >= it.startMs && nowMs < it.endMs }
        return merge(windows)
    }

    fun activeWindow(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): AppRuleInterval? {
        return activeWindows(rule, nowMs, zone).firstOrNull()
    }

    /**
     * Returns the next schedule boundary after [nowMs]. A boundary is either when one of the
     * rule's ranges starts or ends. The window immediately before today is included because an
     * overnight range can end today even though it started yesterday.
     */
    fun nextBoundaryAfter(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): Long? {
        if (rule.weekdays.any { it !in 0..6 }) return null
        if (rule.effectiveTimeRanges().any { range ->
                range.startMinute !in 0 until 24 * 60 || range.endMinute !in 0..24 * 60
            }) {
            return null
        }

        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // Span more than two weekly cycles so a real end boundary is not mistaken for the end
        // of the search horizon when a rule is active only once per week.
        val windows = (-1L..15L)
            .flatMap { offset -> windowsForAnchor(rule, today.plusDays(offset), zone) }
        val horizonEnd = windows.maxOfOrNull { it.endMs } ?: return null
        return merge(windows)
            .asSequence()
            .flatMap { window -> sequenceOf(window.startMs, window.endMs) }
            .filter { it > nowMs }
            // Do not treat the end of the search horizon as a real boundary when the rule stays
            // active continuously through it (for example an all day rule on every weekday).
            .filter { it < horizonEnd }
            .minOrNull()
    }

    /** Returns the merged active intervals that fall inside one current use day. */
    fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        zone: ZoneId
    ): List<AppRuleInterval> = usageWindowsForUseDay(
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
    ): List<AppRuleInterval> {
        val useDay = UseDay.windowFor(useDayId, zone, resetTime)
        val date = LocalDate.parse(useDayId)
        val windows = (-2..2).flatMap { offset ->
            windowsForAnchor(rule, date.plusDays(offset.toLong()), zone)
        }.mapNotNull { window ->
            val start = maxOf(window.startMs, useDay.first)
            val end = minOf(window.endMs, useDay.last + 1)
            if (start < end) start to end else null
        }.map { AppRuleInterval(it.first, it.second) }
        return merge(windows)
    }

    fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        calculator: UseDayCalculator
    ): List<AppRuleInterval> = usageWindowsForUseDay(
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
    ): List<AppRuleInterval> {
        if (weekday(anchor) !in rule.weekdays) return emptyList()
        return rule.effectiveTimeRanges().map { range ->
            val start = atMinute(anchor, range.startMinute, zone)
            val endDate = if (range.endMinute <= range.startMinute) anchor.plusDays(1) else anchor
            val end = atMinute(endDate, range.endMinute, zone)
            AppRuleInterval(start, end)
        }
    }

    private fun merge(windows: Iterable<AppRuleInterval>): List<AppRuleInterval> =
        windows.filter { it.startMs < it.endMs }.sortedBy { it.startMs }
            .fold(mutableListOf()) { merged, window ->
                val previous = merged.lastOrNull()
                if (previous != null && window.startMs <= previous.endMs) {
                    merged[merged.lastIndex] = AppRuleInterval(
                        previous.startMs,
                        maxOf(previous.endMs, window.endMs)
                    )
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
