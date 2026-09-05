package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.utils.UseDayCalculator
import java.time.ZoneId

/** A foreground check delay and the maximum delay used by its scheduler. */
data class AppRuleRecheckPlan(
    val delayMillis: Long,
    val maxDelayMillis: Long,
    /** Semantic deadline. The scheduler must derive its elapsed delay from this value each time. */
    val dueAtWallClockMs: Long = 0L
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
        )
        val evaluatedRuleIds = evaluation.evaluations.map { it.ruleId }.toSet()
        val nextScheduleBoundary = snapshot.appRules
            .asSequence()
            .filter { it.isActive && it.id in evaluatedRuleIds }
            .mapNotNull { rule -> AppRuleSchedule.nextBoundaryAfter(rule, nowMs, zone) }
            .minOrNull()
        val nextUseDayReset = useDayCalculator?.let { calculator ->
            val currentUseDayWindow = calculator.windowFor(calculator.idAt(nowMs))
            val nextBoundary = currentUseDayWindow.last
                .takeIf { it < Long.MAX_VALUE }
                ?.plus(1L)
            nextBoundary
        }

        return sequenceOf(
            nextAllowance?.let {
                AppRuleRecheckPlan(
                    delayMillis = it,
                    maxDelayMillis = MAX_SHORT_RECHECK_DELAY_MS,
                    dueAtWallClockMs = safeAdd(nowMs, it)
                )
            },
            nextSkipBoundary?.let {
                AppRuleRecheckPlan(
                    delayMillis = it - nowMs,
                    maxDelayMillis = MAX_SHORT_RECHECK_DELAY_MS,
                    dueAtWallClockMs = it
                )
            },
            nextScheduleBoundary?.let {
                AppRuleRecheckPlan(
                    delayMillis = it - nowMs,
                    maxDelayMillis = Long.MAX_VALUE,
                    dueAtWallClockMs = it
                )
            },
            nextUseDayReset?.let {
                AppRuleRecheckPlan(
                    delayMillis = it - nowMs,
                    maxDelayMillis = Long.MAX_VALUE,
                    dueAtWallClockMs = it
                )
            }
        ).filterNotNull()
            .filter { it.delayMillis > 0L }
            .minWithOrNull(compareBy<AppRuleRecheckPlan> { it.delayMillis }.thenBy { it.maxDelayMillis })
    }

    private fun safeAdd(first: Long, second: Long): Long =
        if (second > 0L && first > Long.MAX_VALUE - second) {
            Long.MAX_VALUE
        } else {
            first + second
        }

    private const val MAX_SHORT_RECHECK_DELAY_MS = 20_000L
}
