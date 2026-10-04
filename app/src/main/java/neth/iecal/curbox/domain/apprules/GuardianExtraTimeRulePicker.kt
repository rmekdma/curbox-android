package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.UseDayResetTime
import java.time.ZoneId

/** Shared selection rules for the direct guardian extra-time grant flows. */
object GuardianExtraTimeRulePicker {
    /** Evaluates the supplied target package and orders eligible rules from that result. */
    fun candidates(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        resetTime: UseDayResetTime = UseDayResetTime(),
        zone: ZoneId = ZoneId.systemDefault(),
        useDayGenerationStartedAtMs: Long = 0L,
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): List<AppRule> {
        val normalizedSnapshot = snapshot.normalized()
        val evaluation = AppRuleEvaluator.evaluate(
            snapshot = normalizedSnapshot,
            packageName = packageName,
            useDayId = useDayId,
            sessions = sessions,
            nowMs = nowMs,
            resetTime = resetTime,
            zone = zone,
            useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
            overrides = overrides
        )
        // The evaluator also reports some inactive-window failures; those do not block now.
        return candidates(normalizedSnapshot, evaluation.denyingRules)
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
