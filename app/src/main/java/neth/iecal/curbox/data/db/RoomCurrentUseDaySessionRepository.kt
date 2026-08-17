package neth.iecal.curbox.data.db

import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.CurrentUseDayUsageRepository
import neth.iecal.curbox.domain.apprules.ForegroundLaunch

class RoomCurrentUseDaySessionRepository(
    private val dao: ForegroundSessionDao,
    private val launchDao: ForegroundLaunchDao? = null
) : CurrentUseDaySessionRepository, CurrentUseDayUsageRepository {
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

    override suspend fun startSessionAtGeneration(
        useDayId: String,
        packageName: String,
        startedAtMs: Long,
        generationStartedAtMs: Long,
        statisticsTracked: Boolean
    ): Long = dao.insert(
        ForegroundSessionEntity(
            useDayId = useDayId,
            packageName = packageName,
            startedAtMs = startedAtMs,
            useDayGenerationStartedAtMs = generationStartedAtMs,
            statisticsTracked = statisticsTracked
        )
    )

    override suspend fun recordLaunch(
        useDayId: String,
        packageName: String,
        launchedAtMs: Long,
        generationStartedAtMs: Long
    ) {
        launchDao?.insert(
            ForegroundLaunchEntity(
                useDayId = useDayId,
                packageName = packageName,
                launchedAtMs = launchedAtMs,
                useDayGenerationStartedAtMs = generationStartedAtMs
            )
        )
    }

    override suspend fun finishSession(id: Long, endedAtMs: Long) {
        dao.finish(id, endedAtMs)
    }

    override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
        dao.updateEnd(id, endedAtMs)
    }

    override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> =
        dao.getForUseDay(useDayId).map(ForegroundSessionEntity::toDomain)

    override suspend fun sessionsForUseDay(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundSession> = dao.getForUseDaySinceGeneration(
        useDayId,
        generationStartedAtMs
    ).map(ForegroundSessionEntity::toDomain)

    override suspend fun launchesForUseDay(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundLaunch> = launchDao?.getForUseDaySinceGeneration(
        useDayId,
        generationStartedAtMs
    )?.map(ForegroundLaunchEntity::toDomain).orEmpty()

    override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {
        dao.finishOpenForUseDay(useDayId, endedAtMs)
    }

    override suspend fun recoverOpenSessions(useDayId: String) {
        dao.discardOpenForUseDay(useDayId)
    }

    override suspend fun cleanupBeforeUseDay(
        currentUseDayId: String,
        generationStartedAtMs: Long
    ) {
        dao.deleteBeforeUseDayGeneration(currentUseDayId, generationStartedAtMs)
        launchDao?.deleteBeforeUseDayGeneration(currentUseDayId, generationStartedAtMs)
    }
}
