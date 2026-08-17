package neth.iecal.curbox.data.db

import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository

class RoomCurrentUseDaySessionRepository(
    private val dao: ForegroundSessionDao
) : CurrentUseDaySessionRepository {
    override suspend fun startSession(
        useDayId: String,
        packageName: String,
        startedAtMs: Long
    ): Long = dao.insert(
        ForegroundSessionEntity(
            useDayId = useDayId,
            packageName = packageName,
            startedAtMs = startedAtMs
        )
    )

    override suspend fun finishSession(id: Long, endedAtMs: Long) {
        dao.finish(id, endedAtMs)
    }

    override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
        dao.updateEnd(id, endedAtMs)
    }

    override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> =
        dao.getForUseDay(useDayId).map(ForegroundSessionEntity::toDomain)

    override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {
        dao.finishOpenForUseDay(useDayId, endedAtMs)
    }
}
