package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleSnapshot
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Small adapter used at the service boundary. It keeps Room access behind the public session
 * repository seam while leaving the actual decision deterministic and Android free.
 */
class AppRuleEnforcement(
    private val sessionRepository: CurrentUseDaySessionRepository,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    suspend fun check(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        nowMs: Long
    ): AppRulesEvaluation = AppRuleEvaluator.evaluate(
        snapshot = snapshot,
        packageName = packageName,
        useDayId = useDayId,
        sessions = sessionRepository.sessionsForUseDay(useDayId),
        nowMs = nowMs,
        zone = zone
    )

    /**
     * Keeps a storage failure local to this decision. The service can continue receiving later
     * accessibility events, while cancellation still propagates through coroutine workers.
     */
    suspend fun checkSafely(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        nowMs: Long
    ): AppRulesEvaluation = try {
        check(snapshot, packageName, useDayId, nowMs)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AppRulesEvaluation(isAllowed = true, denyingRules = emptyList(), evaluations = emptyList())
    }
}
