package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
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

/** Pure rule decision boundary used by both the service and JVM unit tests. */
object AppRuleEvaluator {

    private data class SessionInterval(val packageName: String, val start: Long, val end: Long)

    fun evaluate(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(zone),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet()
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

        val sessionList = sessions.toList()
        val evaluations = snapshot.appRules
            .filter { it.isActive }
            .mapNotNull { rule ->
                val packages = rule.effectiveScope().resolve(
                    groups = snapshot.appGroups,
                    // A caller that does not have a launcher listing is still able to evaluate
                    // the event package. The service supplies the complete dynamic listing.
                    launchablePackages = availablePackages.ifEmpty { setOf(packageName) },
                    essentialExcludedPackages = essentialExcludedPackages
                )
                if (packageName !in packages) return@mapNotNull null
                evaluateRule(
                    rule,
                    packages,
                    useDayId,
                    sessionList,
                    nowMs,
                    zone,
                    useDayCalculator,
                    useDayGenerationStartedAtMs
                )
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
        zone: ZoneId = ZoneId.systemDefault(),
        useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(zone),
        useDayGenerationStartedAtMs: Long = 0L,
        @Suppress("UNUSED_PARAMETER") availablePackages: Set<String> = emptySet(),
        @Suppress("UNUSED_PARAMETER") essentialExcludedPackages: Set<String> = emptySet()
    ): AppRuleEvaluation {
        val activeWindow = AppRuleSchedule.activeWindow(rule, nowMs, zone)
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

        val usageWindows = AppRuleSchedule.usageWindowsForUseDay(
            rule,
            useDayId,
            zone,
            useDayCalculator.resetTime
        )
        // A package may be represented by more than one persisted row after a process restart or
        // a visibility reconciliation. Merge its intervals first so duplicate rows cannot charge
        // the same visible package twice.
        val intervalsByPackage = sessions.asSequence()
            .filter {
                it.useDayId == useDayId &&
                    it.packageName in targetPackages &&
                    (useDayGenerationStartedAtMs <= 0L ||
                        it.useDayGenerationStartedAtMs >= useDayGenerationStartedAtMs)
            }
            .mapNotNull { session ->
                val end = minOf(session.endedAtMs ?: nowMs, nowMs)
                if (end <= session.startedAtMs) {
                    null
                } else {
                    SessionInterval(session.packageName, session.startedAtMs, end)
                }
            }
            .groupBy { it.packageName }
        val usedMillis = intervalsByPackage.values.sumOf { intervals ->
            val merged = intervals.map { it.start to it.end }.sortedBy { it.first }
                .fold(mutableListOf<Pair<Long, Long>>()) { result, interval ->
                    val previous = result.lastOrNull()
                    if (previous != null && interval.first <= previous.second) {
                        result[result.lastIndex] = previous.first to maxOf(previous.second, interval.second)
                    } else {
                        result += interval
                    }
                    result
                }
            merged.sumOf { (sessionStart, sessionEnd) ->
                usageWindows.sumOf { (windowStart, windowEnd) ->
                    overlapMillis(sessionStart, sessionEnd, windowStart, windowEnd)
                }
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

    fun evaluateRule(
        rule: AppRule,
        targetPackages: Set<String>,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        resetTime: UseDayResetTime,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet()
    ): AppRuleEvaluation = evaluateRule(
        rule = rule,
        targetPackages = targetPackages,
        useDayId = useDayId,
        sessions = sessions,
        nowMs = nowMs,
        zone = zone,
        useDayCalculator = ConfigurableUseDayCalculator(zone, resetTime),
        useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
        availablePackages = availablePackages,
        essentialExcludedPackages = essentialExcludedPackages
    )

    fun evaluateWithResetTime(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        resetTime: UseDayResetTime,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet()
    ): AppRulesEvaluation = evaluate(
        snapshot = snapshot,
        packageName = packageName,
        useDayId = useDayId,
        sessions = sessions,
        nowMs = nowMs,
        zone = zone,
        useDayCalculator = ConfigurableUseDayCalculator(zone, resetTime),
        useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
        availablePackages = availablePackages,
        essentialExcludedPackages = essentialExcludedPackages
    )

    fun evaluate(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        resetTime: UseDayResetTime,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet()
    ): AppRulesEvaluation = evaluateWithResetTime(
        snapshot = snapshot,
        packageName = packageName,
        useDayId = useDayId,
        sessions = sessions,
        nowMs = nowMs,
        resetTime = resetTime,
        zone = zone,
        useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
        availablePackages = availablePackages,
        essentialExcludedPackages = essentialExcludedPackages
    )

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
