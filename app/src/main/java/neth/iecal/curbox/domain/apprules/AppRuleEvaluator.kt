package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDay
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
    val validationErrors: List<String> = emptyList(),
    val contributorUsageMillis: Long = 0L,
    val conditionRequiredMillis: Long = 0L,
    val conditionEnabled: Boolean = false,
    val isConditionMet: Boolean = true,
    val directAllowanceMillis: Long = 0L,
    val earnedAllowanceMillis: Long = 0L
) {
    val conditionProgressMillis: Long
        get() = contributorUsageMillis

    val finalAllowanceMillis: Long
        get() = allowanceMillis

    val earnedMillis: Long
        get() = earnedAllowanceMillis

    val isConditionEnabled: Boolean
        get() = conditionEnabled
}

data class AppRulesEvaluation(
    val isAllowed: Boolean,
    val denyingRules: List<AppRuleEvaluation>,
    val evaluations: List<AppRuleEvaluation>
)

/** Pure rule decision boundary used by both the service and JVM unit tests. */
object AppRuleEvaluator {

    private data class SessionInterval(val packageName: String, val start: Long, val end: Long)

    private data class ContributorResolution(
        val packages: Set<String>,
        val missingGroupIds: Set<String>
    )

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
                val contributorResolution = resolveContributors(snapshot, rule)
                evaluateRule(
                    rule,
                    packages,
                    useDayId,
                    sessionList,
                    nowMs,
                    zone,
                    useDayCalculator,
                    useDayGenerationStartedAtMs,
                    contributorResolution.packages,
                    contributorResolution.missingGroupIds
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
        contributorPackages: Set<String> = emptySet(),
        missingContributorGroupIds: Set<String> = emptySet()
    ): AppRuleEvaluation {
        val activeWindow = AppRuleSchedule.activeWindow(rule, nowMs, zone)
        val sessionList = sessions.toList()
        val directAllowanceMillis = rule.allowedMinutes
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / MILLIS_PER_MINUTE) * MILLIS_PER_MINUTE
        val conditionRequiredMillis = rule.usageConditionMinutes
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / MILLIS_PER_MINUTE) * MILLIS_PER_MINUTE
        val contributorUsageMillis = usageMillisForPackages(
            sessions = sessionList,
            packageNames = contributorPackages,
            useDayId = useDayId,
            nowMs = nowMs,
            zone = zone,
            useDayCalculator = useDayCalculator,
            useDayGenerationStartedAtMs = useDayGenerationStartedAtMs
        )
        val isConditionMet = !rule.usageConditionEnabled ||
            contributorUsageMillis >= conditionRequiredMillis
        val hasMissingContributor = missingContributorGroupIds.isNotEmpty()
        val earnedAllowanceMillis = if (
            rule.earnedAllowanceEnabled && isConditionMet && !hasMissingContributor
        ) {
            contributorUsageMillis
        } else {
            0L
        }
        val allowanceMillis = if (hasMissingContributor || !isConditionMet) {
            0L
        } else {
            safeAdd(directAllowanceMillis, earnedAllowanceMillis)
        }
        val validationErrors = missingContributorGroupIds.map { groupId ->
            "App rule ${rule.id} references a missing contributor app group $groupId"
        }
        if (activeWindow == null) {
            return AppRuleEvaluation(
                ruleId = rule.id,
                isApplicable = true,
                isActive = false,
                usedMillis = 0L,
                allowanceMillis = allowanceMillis,
                remainingMillis = allowanceMillis,
                isAllowed = true,
                validationErrors = validationErrors,
                contributorUsageMillis = contributorUsageMillis,
                conditionRequiredMillis = conditionRequiredMillis,
                conditionEnabled = rule.usageConditionEnabled,
                isConditionMet = isConditionMet,
                directAllowanceMillis = directAllowanceMillis,
                earnedAllowanceMillis = earnedAllowanceMillis
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
        val intervalsByPackage = sessionList.asSequence()
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
            val merged = intervals.map { AppRuleInterval(it.start, it.end) }
                .sortedBy { it.startMs }
                .fold(mutableListOf<AppRuleInterval>()) { result, interval ->
                    val previous = result.lastOrNull()
                    if (previous != null && interval.startMs <= previous.endMs) {
                        result[result.lastIndex] = AppRuleInterval(
                            previous.startMs,
                            maxOf(previous.endMs, interval.endMs)
                        )
                    } else {
                        result += interval
                    }
                    result
                }
            merged.sumOf { interval ->
                usageWindows.sumOf { window ->
                    overlapMillis(interval.startMs, interval.endMs, window.startMs, window.endMs)
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
            isAllowed = remainingMillis > 0L && !hasMissingContributor,
            validationErrors = validationErrors,
            contributorUsageMillis = contributorUsageMillis,
            conditionRequiredMillis = conditionRequiredMillis,
            conditionEnabled = rule.usageConditionEnabled,
            isConditionMet = isConditionMet,
            directAllowanceMillis = directAllowanceMillis,
            earnedAllowanceMillis = earnedAllowanceMillis
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
        contributorPackages: Set<String> = emptySet(),
        missingContributorGroupIds: Set<String> = emptySet()
    ): AppRuleEvaluation = evaluateRule(
        rule = rule,
        targetPackages = targetPackages,
        useDayId = useDayId,
        sessions = sessions,
        nowMs = nowMs,
        zone = zone,
        useDayCalculator = ConfigurableUseDayCalculator(zone, resetTime),
        useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
        contributorPackages = contributorPackages,
        missingContributorGroupIds = missingContributorGroupIds
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

    private fun resolveContributors(
        snapshot: AppRuleSnapshot,
        rule: AppRule
    ): ContributorResolution {
        val groupsById = snapshot.appGroups.associateBy { it.id.trim() }
        val packages = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        rule.effectiveContributorGroupIds().forEach { groupId ->
            val group = groupsById[groupId]
            if (group == null) {
                missing += groupId
            } else {
                group.selectedPackages.map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(packages::add)
            }
        }
        return ContributorResolution(packages, missing)
    }

    private fun usageMillisForPackages(
        sessions: Iterable<ForegroundSession>,
        packageNames: Set<String>,
        useDayId: String,
        nowMs: Long,
        zone: ZoneId,
        useDayCalculator: UseDayCalculator,
        useDayGenerationStartedAtMs: Long
    ): Long {
        if (packageNames.isEmpty()) return 0L
        val useDayWindow = UseDay.windowFor(useDayId, zone, useDayCalculator.resetTime)
        val intervalsByPackage = sessions.asSequence()
            .filter {
                it.useDayId == useDayId &&
                    it.packageName in packageNames &&
                    (useDayGenerationStartedAtMs <= 0L ||
                        it.useDayGenerationStartedAtMs >= useDayGenerationStartedAtMs)
            }
            .mapNotNull { session ->
                val start = maxOf(session.startedAtMs, useDayWindow.first)
                val end = minOf(session.endedAtMs ?: nowMs, nowMs, useDayWindow.last + 1L)
                if (end <= start) null else SessionInterval(session.packageName, start, end)
            }
            .groupBy { it.packageName }
        return intervalsByPackage.values.sumOf { intervals ->
            mergeIntervals(intervals).sumOf { it.end - it.start }
        }
    }

    private fun mergeIntervals(intervals: List<SessionInterval>): List<SessionInterval> =
        intervals.sortedBy { it.start }.fold(mutableListOf()) { result, interval ->
            val previous = result.lastOrNull()
            if (previous != null && interval.start <= previous.end) {
                result[result.lastIndex] = SessionInterval(
                    previous.packageName,
                    previous.start,
                    maxOf(previous.end, interval.end)
                )
            } else {
                result += interval
            }
            result
        }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private const val MILLIS_PER_MINUTE = 60_000L
}
