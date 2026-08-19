package neth.iecal.curbox.data.db

import androidx.room.withTransaction
import neth.iecal.curbox.domain.apprules.UsageResetDeltaCalculator
import neth.iecal.curbox.domain.apprules.UsageResetRequest
import neth.iecal.curbox.domain.apprules.UsageResetRepository
import neth.iecal.curbox.domain.apprules.UsageResetResult
import neth.iecal.curbox.domain.apprules.UsageResetSessionRestart
import neth.iecal.curbox.utils.TimeTools
import java.time.Instant
import java.time.ZoneId

/**
 * Atomically removes one app's current-use-day ledger and adjusts every affected calendar
 * aggregate. Previous-use-day portions that happen to share a calendar date are untouched
 * because the delta is calculated from the selected use-day sessions only.
 */
class RoomUsageResetRepository(
    private val database: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault()
) : UsageResetRepository {
    override suspend fun reset(request: UsageResetRequest): UsageResetResult =
        resetAndStartSessions(request, emptyList())

    override suspend fun resetAndStartSessions(
        request: UsageResetRequest,
        restarts: List<UsageResetSessionRestart>
    ): UsageResetResult {
        require(request.useDayId.isNotBlank()) { "useDayId must not be blank" }
        require(request.packageNames.isNotEmpty()) { "packageNames must not be empty" }
        require(restarts.all { it.packageName in request.packageNames }) {
            "every restart must belong to the reset request"
        }
        return database.withTransaction {
            val sessionDao = database.foregroundSessionDao()
            val launchDao = database.foregroundLaunchDao()
            // A heartbeat writes the calendar aggregate before closing its Room row.  If the
            // second write failed, the row can still be open even though its checkpoint is
            // already reflected in app_usage_stats.  Close each service-owned row at that
            // checkpoint inside this same transaction before calculating the subtraction.  That
            // makes the session ledger authoritative for the exact persisted interval and leaves
            // any uncheckpointed tail discarded by the reset.
            restarts.forEach { restart ->
                if (restart.activeSessionId == 0L) return@forEach
                val active = sessionDao.getById(restart.activeSessionId) ?: return@forEach
                if (active.endedAtMs != null) return@forEach
                val checkpoint = maxOf(
                    active.startedAtMs,
                    minOf(restart.persistedThroughMs, request.resetAtMs)
                )
                sessionDao.updateEnd(active.id, checkpoint)
            }
            val sessions = sessionDao.getForUseDaySinceGeneration(
                request.useDayId,
                request.generationStartedAtMs
            )
            val launches = launchDao.getForUseDaySinceGeneration(
                request.useDayId,
                request.generationStartedAtMs
            )
            val selectedSessions = sessions.filter { it.packageName in request.packageNames }
            val selectedLaunches = launches.filter { it.packageName in request.packageNames }
            val delta = UsageResetDeltaCalculator.calculate(
                sessions = selectedSessions.map(ForegroundSessionEntity::toDomain),
                launches = selectedLaunches.map(ForegroundLaunchEntity::toDomain),
                packageNames = request.packageNames,
                resetAtMs = request.resetAtMs,
                zone = zone
            )

            val deleteSessionIds = selectedSessions.filter {
                it.startedAtMs <= request.resetAtMs &&
                    (it.startedAtMs == request.resetAtMs ||
                        (it.endedAtMs ?: request.resetAtMs) <= request.resetAtMs)
            }.map { it.id }
            if (deleteSessionIds.isNotEmpty()) sessionDao.deleteByIds(deleteSessionIds)
            selectedSessions.filter {
                it.id !in deleteSessionIds &&
                    it.startedAtMs < request.resetAtMs &&
                    (it.endedAtMs == null || it.endedAtMs > request.resetAtMs)
            }.forEach { sessionDao.cutAt(it.id, request.resetAtMs) }

            val deleteLaunchIds = selectedLaunches.filter {
                it.launchedAtMs <= request.resetAtMs
            }.map { it.id }
            if (deleteLaunchIds.isNotEmpty()) launchDao.deleteByIds(deleteLaunchIds)

            val byRow = delta.timeByBucketMs.keys
                .map { it.date to it.packageName }
                .toSet() + delta.launchesByDatePackage.keys
            byRow.forEach { (date, packageName) ->
                val current = database.appUsageDao().get(date, packageName) ?: return@forEach
                val hourly = parseHourly(current.hourlyUsage)
                for (hour in 0 until 24) {
                    val key = neth.iecal.curbox.domain.apprules.UsageResetBucketKey(
                        date,
                        packageName,
                        hour
                    )
                    hourly[hour] = (hourly[hour] - (delta.timeByBucketMs[key] ?: 0L))
                        .coerceAtLeast(0L)
                }
                val removedTime = delta.timeByBucketMs
                    .filterKeys { it.date == date && it.packageName == packageName }
                    .values
                    .sum()
                val launchDelta = delta.launchesByDatePackage[date to packageName] ?: 0
                database.appUsageDao().updateDerivedCounters(
                    date = date,
                    packageName = packageName,
                    totalTime = (current.totalTime - removedTime).coerceAtLeast(0L),
                    hourlyUsage = hourly.joinToString(","),
                    launchCount = (current.launchCount - launchDelta).coerceAtLeast(0)
                )
            }

            // Recreate visible rows only after the old ledger and its calendar aggregates have
            // been cut.  Both the row and its first launch are part of this transaction, so a
            // crash cannot leave a foreground session without the corresponding count.
            val restartedSessionIds = linkedMapOf<String, Long>()
            restarts.forEach { restart ->
                val newSessionId = sessionDao.insert(
                    ForegroundSessionEntity(
                        useDayId = restart.useDayId,
                        packageName = restart.packageName,
                        startedAtMs = restart.startedAtMs,
                        useDayGenerationStartedAtMs = restart.generationStartedAtMs,
                        statisticsTracked = restart.statisticsTracked
                    )
                )
                restartedSessionIds[restart.packageName] = newSessionId
                if (restart.recordLaunch && restart.statisticsTracked) {
                    recordLaunchAggregate(
                        packageName = restart.packageName,
                        launchedAtMs = restart.startedAtMs
                    )
                    launchDao.insert(
                        ForegroundLaunchEntity(
                            useDayId = restart.useDayId,
                            packageName = restart.packageName,
                            launchedAtMs = restart.startedAtMs,
                            useDayGenerationStartedAtMs = restart.generationStartedAtMs
                        )
                    )
                }
            }
            UsageResetResult(request, delta, restartedSessionIds)
        }
    }

    private suspend fun recordLaunchAggregate(packageName: String, launchedAtMs: Long) {
        val date = TimeTools.dayKey(
            Instant.ofEpochMilli(launchedAtMs).atZone(zone).toLocalDate()
        )
        val dao = database.appUsageDao()
        val existing = dao.get(date, packageName)
        dao.upsert(
            existing?.copy(
                launchCount = existing.launchCount + 1,
                lastUsed = maxOf(existing.lastUsed, launchedAtMs)
            ) ?: AppUsageEntity(
                date = date,
                packageName = packageName,
                launchCount = 1,
                lastUsed = launchedAtMs
            )
        )
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
