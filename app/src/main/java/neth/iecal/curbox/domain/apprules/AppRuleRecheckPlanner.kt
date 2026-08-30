package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.utils.UseDayCalculator
import java.time.ZoneId

/** A foreground check delay and the maximum delay used by its scheduler. */
data class AppRuleRecheckPlan(
    val delayMillis: Long,
    val maxDelayMillis: Long
)

/** Calculates the next foreground check needed by the service. */
object AppRuleRecheckPlanner {
    /**
     * Returns the earliest allowance, override, or schedule boundary. Usage and override checks
     * retain the service heartbeat cap, while a distant schedule boundary can be posted directly
     * instead of polling the foreground app every few seconds.
     */
    fun nextPlan(
        snapshot: AppRuleSnapshot,
        evaluation: AppRulesEvaluation,
        overrideState: AppRuleOverrideState,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayCalculator: UseDayCalculator? = null
    ): AppRuleRecheckPlan? {
        val nextAllowance = evaluation.evaluations
            .asSequence()
            .filter { it.isActive && it.remainingMillis > 0L }
            .map { it.remainingMillis }
            .minOrNull()
        val nextSkipBoundary = AppRuleGuardianOverrides.nextSkipBoundaryMs(
            overrideState,
            useDayId,
            nowMs,
            useDayGenerationStartedAtMs
        )?.let { it - nowMs }
        val evaluatedRuleIds = evaluation.evaluations.map { it.ruleId }.toSet()
        val nextScheduleBoundary = snapshot.appRules
            .asSequence()
            .filter { it.isActive && it.id in evaluatedRuleIds }
            .mapNotNull { rule -> AppRuleSchedule.nextBoundaryAfter(rule, nowMs, zone) }
            .map { it - nowMs }
            .minOrNull()
        val nextUseDayReset = useDayCalculator?.let { calculator ->
            val currentUseDayWindow = calculator.windowFor(calculator.idAt(nowMs))
            val nextBoundary = currentUseDayWindow.last
                .takeIf { it < Long.MAX_VALUE }
                ?.plus(1L)
            nextBoundary?.minus(nowMs)
        }

        return sequenceOf(
            nextAllowance?.let { AppRuleRecheckPlan(it, MAX_SHORT_RECHECK_DELAY_MS) },
            nextSkipBoundary?.let { AppRuleRecheckPlan(it, MAX_SHORT_RECHECK_DELAY_MS) },
            nextScheduleBoundary?.let { AppRuleRecheckPlan(it, Long.MAX_VALUE) },
            nextUseDayReset?.let { AppRuleRecheckPlan(it, Long.MAX_VALUE) }
        ).filterNotNull()
            .filter { it.delayMillis > 0L }
            .minWithOrNull(compareBy<AppRuleRecheckPlan> { it.delayMillis }.thenBy { it.maxDelayMillis })
    }

    private const val MAX_SHORT_RECHECK_DELAY_MS = 20_000L
}
