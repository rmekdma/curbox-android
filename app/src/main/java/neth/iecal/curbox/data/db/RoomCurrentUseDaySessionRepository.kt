package neth.iecal.curbox.data.db

import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.CurrentUseDayUsageRepository
import neth.iecal.curbox.domain.apprules.ForegroundLaunch
import neth.iecal.curbox.domain.apprules.ForegroundUsageCheckpoint
import androidx.room.withTransaction

class RoomCurrentUseDaySessionRepository(
    private val dao: ForegroundSessionDao,
    private val launchDao: ForegroundLaunchDao? = null,
    private val appUsageDao: AppUsageDao? = null,
    private val database: AppDatabase? = null
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

    override suspend fun commitSessionCheckpoint(
        id: Long,
        endedAtMs: Long,
        usage: List<ForegroundUsageCheckpoint>
    ): Boolean {
        val usageDao = appUsageDao
        val db = database
        if (usageDao == null || db == null) {
            check(dao.updateEnd(id, endedAtMs) == 1) {
                "foreground session checkpoint row disappeared: $id"
            }
            return true
        }
        db.withTransaction {
            usage.forEach { checkpoint ->
                if (checkpoint.durationMs <= 0L) return@forEach
                val existing = usageDao.get(checkpoint.date, checkpoint.packageName)
                val hourly = parseHourly(existing?.hourlyUsage)
                hourly[checkpoint.hour] += checkpoint.durationMs
                usageDao.upsert(
                    AppUsageEntity(
                        date = checkpoint.date,
                        packageName = checkpoint.packageName,
                        totalTime = (existing?.totalTime ?: 0L) + checkpoint.durationMs,
                        hourlyUsage = hourly.joinToString(","),
                        launchCount = existing?.launchCount ?: 0,
                        lastUsed = maxOf(existing?.lastUsed ?: 0L, checkpoint.lastUsedMs)
                    )
                )
            }
            check(dao.updateEnd(id, endedAtMs) == 1) {
                "foreground session checkpoint row disappeared: $id"
            }
        }
        return true
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

    private fun parseHourly(value: String?): LongArray {
        val result = LongArray(24)
        if (value.isNullOrEmpty()) return result
        value.split(',').take(24).forEachIndexed { index, token ->
            result[index] = token.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        }
        return result
    }
}
