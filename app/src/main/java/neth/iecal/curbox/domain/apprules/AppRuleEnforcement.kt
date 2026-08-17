package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleSnapshot
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
}
