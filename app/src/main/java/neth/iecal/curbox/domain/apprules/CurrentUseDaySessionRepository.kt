package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession

/** Public persistence seam used by the tracker and deterministic rule evaluator. */
interface CurrentUseDaySessionRepository {
    suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long

    suspend fun finishSession(id: Long, endedAtMs: Long)

    suspend fun updateSessionEnd(id: Long, endedAtMs: Long)

    suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession>

    suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long)

    suspend fun sessions(useDayId: String): List<ForegroundSession> =
        sessionsForUseDay(useDayId)
}
