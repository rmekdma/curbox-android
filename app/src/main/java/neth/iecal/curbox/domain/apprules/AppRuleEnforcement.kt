package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayCalculator
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Small adapter used at the service boundary. It keeps Room access behind the public session
 * repository seam while leaving the actual decision deterministic and Android free.
 */
class AppRuleEnforcement(
    private val sessionRepository: CurrentUseDaySessionRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(zone)
) {
    constructor(
        sessionRepository: CurrentUseDaySessionRepository,
        useDayCalculator: UseDayCalculator
    ) : this(sessionRepository, useDayCalculator.zone, useDayCalculator)

    suspend fun check(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        nowMs: Long,
        calculator: UseDayCalculator = useDayCalculator,
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): AppRulesEvaluation = AppRuleEvaluator.evaluate(
        snapshot = snapshot,
        packageName = packageName,
        useDayId = useDayId,
        sessions = sessionRepository.sessionsForUseDay(useDayId, useDayGenerationStartedAtMs),
        nowMs = nowMs,
        zone = zone,
        useDayCalculator = calculator,
        useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
        availablePackages = availablePackages,
        essentialExcludedPackages = essentialExcludedPackages,
        overrides = overrides
    )

    /**
     * Keeps a storage failure local to this decision. The service can continue receiving later
     * accessibility events, while cancellation still propagates through coroutine workers.
     */
    suspend fun checkSafely(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        nowMs: Long,
        calculator: UseDayCalculator = useDayCalculator,
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): AppRulesEvaluation = try {
        check(
            snapshot,
            packageName,
            useDayId,
            nowMs,
            calculator,
            useDayGenerationStartedAtMs,
            availablePackages,
            essentialExcludedPackages,
            overrides
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AppRulesEvaluation(isAllowed = true, denyingRules = emptyList(), evaluations = emptyList())
    }
}
