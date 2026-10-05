package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import java.time.ZoneId

/** Shared selection rules for the direct guardian extra-time grant flows. */
object GuardianExtraTimeRulePicker {
    /** Evaluates every rule in its own scope, as the main rule screen does. */
    fun evaluateRules(
        snapshot: AppRuleSnapshot,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        resetTime: UseDayResetTime = UseDayResetTime(),
        zone: ZoneId = ZoneId.systemDefault(),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): Map<String, AppRuleEvaluation> {
        val normalizedSnapshot = snapshot.normalized()
        val calculator = ConfigurableUseDayCalculator(zone, resetTime)
        val sessionList = sessions.toList()
        return normalizedSnapshot.appRules.associate { rule ->
            rule.id to AppRuleEvaluator.evaluateRuleForSnapshot(
                snapshot = normalizedSnapshot,
                rule = rule,
                useDayId = useDayId,
                sessions = sessionList,
                nowMs = nowMs,
                zone = zone,
                useDayCalculator = calculator,
                useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                availablePackages = availablePackages,
                essentialExcludedPackages = essentialExcludedPackages,
                overrides = overrides
            )
        }
    }

    fun candidates(
        snapshot: AppRuleSnapshot,
        currentlyDenyingRuleIds: Set<String>
    ): List<AppRule> {
        val eligible = snapshot.appRules.filter {
            it.isActive && it.guardianExtraTimeAllowed
        }
        return eligible.filter { it.id in currentlyDenyingRuleIds } +
            eligible.filterNot { it.id in currentlyDenyingRuleIds }
    }

    /** Orders the full rule list using the screen's existing per-rule evaluations. */
    fun candidates(
        snapshot: AppRuleSnapshot,
        evaluations: Iterable<AppRuleEvaluation>
    ): List<AppRule> {
        val currentlyDenyingRuleIds = evaluations
            .filter { it.isApplicable && it.isActive && !it.isAllowed }
            .mapTo(mutableSetOf()) { it.ruleId }
        return candidates(snapshot, currentlyDenyingRuleIds)
    }
}
