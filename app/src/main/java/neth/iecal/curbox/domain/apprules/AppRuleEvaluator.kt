package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.UseDay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class AppRuleEvaluation(
    val ruleId: String,
    val isApplicable: Boolean,
    val isActive: Boolean,
    val usedMillis: Long,
    val allowanceMillis: Long,
    val remainingMillis: Long,
    val isAllowed: Boolean,
    val validationErrors: List<String> = emptyList()
)

data class AppRulesEvaluation(
    val isAllowed: Boolean,
    val denyingRules: List<AppRuleEvaluation>,
    val evaluations: List<AppRuleEvaluation>
)

object AppRuleValidator {
    fun validate(snapshot: AppRuleSnapshot): List<String> = snapshot.validate()

    fun isValid(snapshot: AppRuleSnapshot): Boolean = snapshot.isValid
}

/** Pure rule decision boundary used by both the service and JVM unit tests. */
object AppRuleEvaluator {

    fun evaluate(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AppRulesEvaluation {
        val validationErrors = snapshot.validate()
        if (validationErrors.isNotEmpty()) {
            return AppRulesEvaluation(
                isAllowed = false,
                denyingRules = snapshot.appRules.filter { it.isActive }.map { rule ->
                    AppRuleEvaluation(
                        ruleId = rule.id,
                        isApplicable = true,
                        isActive = false,
                        usedMillis = 0L,
                        allowanceMillis = 0L,
                        remainingMillis = 0L,
                        isAllowed = false,
                        validationErrors = validationErrors
                    )
                },
                evaluations = emptyList()
            )
        }

        val groups = snapshot.appGroups.associateBy { it.id }
        val sessionList = sessions.toList()
        val evaluations = snapshot.appRules
            .filter { it.isActive }
            .mapNotNull { rule ->
                val packages = groups[rule.appGroupId]?.selectedPackages.orEmpty().toSet()
                if (packageName !in packages) return@mapNotNull null
                evaluateRule(rule, packages, useDayId, sessionList, nowMs, zone)
            }
        return AppRulesEvaluation(
            isAllowed = evaluations.all { it.isAllowed },
            denyingRules = evaluations.filterNot { it.isAllowed },
            evaluations = evaluations
        )
    }

    fun evaluateRule(
        rule: AppRule,
        targetPackages: Set<String>,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AppRuleEvaluation {
        val activeWindow = activeWindow(rule, nowMs, zone)
        val allowanceMillis = rule.allowedMinutes
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / MILLIS_PER_MINUTE) * MILLIS_PER_MINUTE
        if (activeWindow == null) {
            return AppRuleEvaluation(
                ruleId = rule.id,
                isApplicable = true,
                isActive = false,
                usedMillis = 0L,
                allowanceMillis = allowanceMillis,
                remainingMillis = allowanceMillis,
                isAllowed = true
            )
        }

        val usageWindows = usageWindowsForUseDay(rule, useDayId, zone)
        val usedMillis = sessions.asSequence()
            .filter { it.useDayId == useDayId && it.packageName in targetPackages }
            .sumOf { session ->
                val end = minOf(session.endedAtMs ?: nowMs, nowMs)
                usageWindows.sumOf { (windowStart, windowEnd) ->
                    overlapMillis(session.startedAtMs, end, windowStart, windowEnd)
                }
            }
        val remainingMillis = allowanceMillis - usedMillis
        return AppRuleEvaluation(
            ruleId = rule.id,
            isApplicable = true,
            isActive = true,
            usedMillis = usedMillis,
            allowanceMillis = allowanceMillis,
            remainingMillis = remainingMillis,
            isAllowed = remainingMillis > 0L
        )
    }

    /** Returns the current active interval as an epoch-millisecond pair. */
    internal fun activeWindow(
        rule: AppRule,
        nowMs: Long,
        zone: ZoneId
    ): Pair<Long, Long>? {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        // An overnight interval belongs to the weekday on which it starts.  Checking today and
        // yesterday also makes equal start/end intervals represent one full 24 hour period.
        for (anchor in listOf(today, today.minusDays(1))) {
            val window = windowForAnchor(rule, anchor, zone) ?: continue
            if (nowMs >= window.first && nowMs < window.second) return window
        }
        return null
    }

    /**
     * Returns the union of this rule's active intervals that fall inside one use day. The
     * adjacent anchor dates matter because a weekday interval can cross either the 04:00 reset
     * or midnight, and overlapping weekday intervals must not charge the same session twice.
     */
    private fun usageWindowsForUseDay(
        rule: AppRule,
        useDayId: String,
        zone: ZoneId
    ): List<Pair<Long, Long>> {
        val useDay = UseDay.windowFor(useDayId, zone)
        val date = LocalDate.parse(useDayId)
        val windows = (-1..1).mapNotNull { offset ->
            val window = windowForAnchor(rule, date.plusDays(offset.toLong()), zone) ?: return@mapNotNull null
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

    private fun overlapMillis(
        leftStart: Long,
        leftEnd: Long,
        rightStart: Long,
        rightEnd: Long
    ): Long {
        if (leftEnd <= leftStart || rightEnd <= rightStart) return 0L
        return (minOf(leftEnd, rightEnd) - maxOf(leftStart, rightStart)).coerceAtLeast(0L)
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
