package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
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
    val earnedAllowanceMillis: Long = 0L,
    val guardianAllowanceMillis: Long = 0L,
    val guardianUsedMillis: Long = 0L,
    val guardianRemainingMillis: Long = 0L,
    val isSkipped: Boolean = false
)

data class AppRulesEvaluation(
    val isAllowed: Boolean,
    val denyingRules: List<AppRuleEvaluation>,
    val evaluations: List<AppRuleEvaluation>
)

/** Pure rule decision boundary used by both the service and JVM unit tests. */
object AppRuleEvaluator {

    private data class SessionInterval(val packageName: String, val start: Long, val end: Long)

    private data class AllowanceAllocation(
        val baseRemainingMillis: Long,
        val guardianUsedMillis: Long,
        val guardianRemainingMillis: Long
    )

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
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
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
                    contributorResolution.missingGroupIds,
                    overrides
                )
            }
        return AppRulesEvaluation(
            isAllowed = evaluations.all { it.isAllowed },
            denyingRules = evaluations.filterNot { it.isAllowed },
            evaluations = evaluations
        )
    }

    /** Evaluates one persisted rule for display or other callers that need its full breakdown. */
    fun evaluateRuleForSnapshot(
        snapshot: AppRuleSnapshot,
        rule: AppRule,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(zone),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): AppRuleEvaluation {
        val sessionList = sessions.toList()
        val targetPackages = rule.effectiveScope().resolve(
            groups = snapshot.appGroups,
            launchablePackages = availablePackages,
            essentialExcludedPackages = essentialExcludedPackages
        )
        val contributorResolution = resolveContributors(snapshot, rule)
        return evaluateRule(
            rule = rule,
            targetPackages = targetPackages,
            useDayId = useDayId,
            sessions = sessionList,
            nowMs = nowMs,
            zone = zone,
            useDayCalculator = useDayCalculator,
            useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
            contributorPackages = contributorResolution.packages,
            missingContributorGroupIds = contributorResolution.missingGroupIds,
            overrides = overrides
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
        missingContributorGroupIds: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
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
        val activeOverrides = AppRuleGuardianOverrides.normalize(
            overrides,
            useDayId,
            nowMs,
            useDayGenerationStartedAtMs
        )
        val isSkipped = AppRuleGuardianOverrides.isSkipped(
            activeOverrides,
            rule.id,
            useDayId,
            nowMs,
            useDayGenerationStartedAtMs
        )
        val grants = AppRuleGuardianOverrides.grantsForRule(
            activeOverrides,
            rule.id,
            useDayId,
            nowMs,
            useDayGenerationStartedAtMs
        )
        val skipIntervals = AppRuleGuardianOverrides.skipsForRule(
            activeOverrides,
            rule.id,
            useDayId,
            nowMs,
            useDayGenerationStartedAtMs
        )
        val guardianAllowanceMillis = grants.fold(0L) { total, grant ->
            safeAdd(total, grant.grantedMillis)
        }
        if (activeWindow == null) {
            return AppRuleEvaluation(
                ruleId = rule.id,
                isApplicable = true,
                isActive = false,
                usedMillis = 0L,
                allowanceMillis = safeAdd(allowanceMillis, guardianAllowanceMillis),
                remainingMillis = safeAdd(allowanceMillis, guardianAllowanceMillis),
                isAllowed = !hasMissingContributor,
                validationErrors = validationErrors,
                contributorUsageMillis = contributorUsageMillis,
                conditionRequiredMillis = conditionRequiredMillis,
                conditionEnabled = rule.usageConditionEnabled,
                isConditionMet = isConditionMet,
                directAllowanceMillis = directAllowanceMillis,
                earnedAllowanceMillis = earnedAllowanceMillis,
                guardianAllowanceMillis = guardianAllowanceMillis,
                guardianRemainingMillis = guardianAllowanceMillis,
                isSkipped = isSkipped
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
        val usageIntervals = intervalsByPackage.values.flatMap { intervals ->
            mergeIntervals(intervals).flatMap { interval ->
                usageWindows.mapNotNull { window ->
                    val start = maxOf(interval.start, window.startMs)
                    val end = minOf(interval.end, window.endMs)
                    if (start < end) SessionInterval(interval.packageName, start, end) else null
                }
            }
        }.sortedBy { it.start }
        val effectiveUsageIntervals = usageIntervals.flatMap { interval ->
            subtractIntervals(interval, skipIntervals.map { skip ->
                SessionInterval("guardian-skip", skip.skipFromMs, skip.skipUntilMs)
            })
        }
        val usedMillis = effectiveUsageIntervals.sumOf { it.end - it.start }
        val allocation = allocateAllowance(
            usageIntervals = effectiveUsageIntervals,
            baseAllowanceMillis = allowanceMillis,
            grants = grants
        )
        val remainingMillis = safeAdd(allocation.baseRemainingMillis, allocation.guardianRemainingMillis)
        return AppRuleEvaluation(
            ruleId = rule.id,
            isApplicable = true,
            isActive = true,
            usedMillis = usedMillis,
            allowanceMillis = safeAdd(allowanceMillis, guardianAllowanceMillis),
            remainingMillis = remainingMillis,
            isAllowed = !hasMissingContributor && (isSkipped || remainingMillis > 0L),
            validationErrors = validationErrors,
            contributorUsageMillis = contributorUsageMillis,
            conditionRequiredMillis = conditionRequiredMillis,
            conditionEnabled = rule.usageConditionEnabled,
            isConditionMet = isConditionMet,
            directAllowanceMillis = directAllowanceMillis,
            earnedAllowanceMillis = earnedAllowanceMillis,
            guardianAllowanceMillis = guardianAllowanceMillis,
            guardianUsedMillis = allocation.guardianUsedMillis,
            guardianRemainingMillis = allocation.guardianRemainingMillis,
            isSkipped = isSkipped
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
        missingContributorGroupIds: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
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
        missingContributorGroupIds = missingContributorGroupIds,
        overrides = overrides
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
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
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
        essentialExcludedPackages = essentialExcludedPackages,
        overrides = overrides
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
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
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
        essentialExcludedPackages = essentialExcludedPackages,
        overrides = overrides
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

    private fun subtractIntervals(
        interval: SessionInterval,
        exclusions: List<SessionInterval>
    ): List<SessionInterval> {
        var remaining = listOf(interval)
        exclusions.sortedBy { it.start }.forEach { exclusion ->
            remaining = remaining.flatMap { candidate ->
                if (exclusion.end <= candidate.start || exclusion.start >= candidate.end) {
                    listOf(candidate)
                } else {
                    buildList {
                        if (candidate.start < exclusion.start) {
                            add(candidate.copy(end = exclusion.start.coerceAtMost(candidate.end)))
                        }
                        if (exclusion.end < candidate.end) {
                            add(candidate.copy(start = exclusion.end.coerceAtLeast(candidate.start)))
                        }
                    }.filter { it.start < it.end }
                }
            }
        }
        return remaining
    }

    /**
     * Allocates visible foreground time in chronological order. Direct and earned allowance is
     * consumed first; only time after a grant was issued can consume that grant. This is what
     * makes a grant issued during an inactive rule window usable in the next window, while old
     * usage cannot retroactively spend it.
     */
    private fun allocateAllowance(
        usageIntervals: List<SessionInterval>,
        baseAllowanceMillis: Long,
        grants: List<AppRuleGuardianGrant>
    ): AllowanceAllocation {
        var baseRemaining = baseAllowanceMillis
        val grantRemaining = grants
            .sortedBy { it.grantedAtMs }
            .map { it to it.grantedMillis.coerceAtLeast(0L) }
            .toMutableList()
        var guardianUsed = 0L

        usageIntervals.forEach { interval ->
            val cutPoints = buildList {
                add(interval.start)
                grants.forEach { grant ->
                    if (grant.grantedAtMs > interval.start && grant.grantedAtMs < interval.end) {
                        add(grant.grantedAtMs)
                    }
                }
                add(interval.end)
            }.distinct().sorted()
            cutPoints.zipWithNext().forEach segment@{ (start, end) ->
                var remaining = end - start
                if (remaining <= 0L) return@segment
                if (baseRemaining > 0L) {
                    val consumed = minOf(baseRemaining, remaining)
                    baseRemaining -= consumed
                    remaining -= consumed
                }
                if (remaining <= 0L) return@segment
                grantRemaining.indices.forEach grant@{ index ->
                    if (remaining <= 0L) return@grant
                    val (grant, available) = grantRemaining[index]
                    if (grant.grantedAtMs > start || available <= 0L) return@grant
                    val consumed = minOf(available, remaining)
                    grantRemaining[index] = grant to (available - consumed)
                    guardianUsed = safeAdd(guardianUsed, consumed)
                    remaining -= consumed
                }
            }
        }
        val guardianRemaining = grantRemaining.fold(0L) { total, (_, remaining) ->
            safeAdd(total, remaining)
        }
        return AllowanceAllocation(baseRemaining, guardianUsed, guardianRemaining)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private const val MILLIS_PER_MINUTE = 60_000L
}
