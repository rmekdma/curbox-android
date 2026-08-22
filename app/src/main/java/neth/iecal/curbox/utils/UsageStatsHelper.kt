package neth.iecal.curbox.utils

import android.content.Context
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.CurrentUseDayUsageAggregator
import neth.iecal.curbox.domain.apprules.CurrentUseDayUsageRepository
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

class UsageStatsHelper(context: Context) {

    private val database = AppDatabase.getInstance(context.applicationContext)
    private val dao = database.appUsageDao()
    private val currentUseDayRepository: CurrentUseDayUsageRepository =
        RoomCurrentUseDaySessionRepository(
            database.foregroundSessionDao(),
            database.foregroundLaunchDao()
        )
    private val dataStore = DataStoreManager(context.applicationContext)

    suspend fun getForegroundStatsByRelativeDay(offset: Int): List<AllAppsUsageFragment.Stat> {
        return if (offset == 0) {
            getForegroundStatsForCurrentUseDay()
        } else {
            getForegroundStatsByDay(LocalDate.now().minusDays(offset.toLong()))
        }
    }

    suspend fun getForegroundStatsByDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return if (date == LocalDate.now()) {
            getForegroundStatsForCurrentUseDay()
        } else {
            getLegacyForegroundStatsByDay(date)
        }
    }

    /** Current use-day rows are authoritative; calendar aggregates remain historical data. */
    suspend fun getForegroundStatsForCurrentUseDay(
        nowMs: Long = System.currentTimeMillis()
    ): List<AllAppsUsageFragment.Stat> {
        val current = currentUseDayData(nowMs)
        return CurrentUseDayUsageAggregator.aggregate(
            sessions = current.sessions,
            launches = current.launches,
            nowMs = nowMs,
            zone = current.zone,
            generationStartedAtMs = current.generationStartedAtMs
        ).map { aggregate ->
            AllAppsUsageFragment.Stat(
                packageName = aggregate.packageName,
                totalTime = aggregate.totalTimeMs,
                sessions = aggregate.launchCount,
                hourlyUsage = aggregate.hourlyUsage
            )
        }
    }

    private suspend fun getLegacyForegroundStatsByDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return dao.getForDate(TimeTools.dayKey(date))
            .map { it.toStat() }
            .sortedByDescending { it.totalTime }
    }

    suspend fun getForegroundStatsByTimestamps(start: Long, end: Long): List<AllAppsUsageFragment.Stat> {
        val dates = datesBetween(start, end)
        if (dates.isEmpty()) return emptyList()

        val merged = HashMap<String, AllAppsUsageFragment.Stat>()
        for (row in dao.getForDates(dates)) {
            val existing = merged[row.packageName]
            if (existing == null) {
                merged[row.packageName] = row.toStat()
            } else {
                val hourly = existing.hourlyUsage.copyOf()
                val incoming = parseHourly(row.hourlyUsage)
                for (i in 0 until 24) hourly[i] += incoming[i]
                merged[row.packageName] = AllAppsUsageFragment.Stat(
                    packageName = row.packageName,
                    totalTime = existing.totalTime + row.totalTime,
                    sessions = existing.sessions + row.launchCount,
                    hourlyUsage = hourly
                )
            }
        }
        return merged.values.sortedByDescending { it.totalTime }
    }

    suspend fun getEarliestTimestamp(): Long = dao.earliestTimestamp() ?: System.currentTimeMillis()

    /**
     * Uses the same hourly buckets shown by AppUsageBreakdown and returns only the portion that
     * overlaps [startMs, endMs]. Sessions are already split on hour boundaries by AppUsageTracker.
     */
    suspend fun getForegroundUsageBetween(
        packageNames: Set<String>,
        startMs: Long,
        endMs: Long
    ): Long {
        if (packageNames.isEmpty() || endMs <= startMs) return 0L
        val nowMs = System.currentTimeMillis()
        val effectiveEndMs = minOf(endMs, nowMs)
        if (effectiveEndMs <= startMs) return 0L
        val current = currentUseDayData(nowMs)
        val currentWindow = current.calculator.windowFor(current.useDayId)
        val currentStart = maxOf(startMs, currentWindow.first)
        val currentEnd = minOf(effectiveEndMs, currentWindow.last + 1L)
        var total = 0L
        if (currentStart < currentEnd) {
            total += CurrentUseDayUsageAggregator.usageMillisBetween(
                sessions = current.sessions,
                packageNames = packageNames,
                startMs = currentStart,
                endMs = currentEnd,
                nowMs = nowMs,
                generationStartedAtMs = current.generationStartedAtMs
            )
        }
        if (startMs < currentStart) {
            total += getLegacyForegroundUsageBetween(
                packageNames,
                startMs,
                minOf(effectiveEndMs, currentStart)
            )
        }
        if (effectiveEndMs > currentEnd) {
            total += getLegacyForegroundUsageBetween(
                packageNames,
                maxOf(startMs, currentEnd),
                effectiveEndMs
            )
        }
        return total
    }

    private suspend fun getLegacyForegroundUsageBetween(
        packageNames: Set<String>,
        startMs: Long,
        endMs: Long
    ): Long {
        if (packageNames.isEmpty() || endMs <= startMs) return 0L
        val zone = ZoneId.systemDefault()
        val dates = datesBetween(startMs, endMs)
        var total = 0.0
        dao.getForDates(dates).asSequence()
            .filter { it.packageName in packageNames }
            .forEach { row ->
                val date = runCatching {
                    LocalDate.parse(row.date, TimeTools.dayKeyFormatter())
                }.getOrNull() ?: return@forEach
                val hourly = parseHourly(row.hourlyUsage)
                for (hour in 0 until 24) {
                    val bucketStart = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
                    val bucketEnd = date.atTime(hour, 0).plusHours(1)
                        .atZone(zone).toInstant().toEpochMilli()
                    val overlap = (minOf(endMs, bucketEnd) - maxOf(startMs, bucketStart))
                        .coerceAtLeast(0L)
                    if (overlap > 0L) {
                        total += hourly[hour] * (overlap.toDouble() / (bucketEnd - bucketStart))
                    }
                }
            }
        return total.roundToLong()
    }

    private suspend fun currentUseDayData(nowMs: Long): CurrentUseDayData {
        val settings = dataStore.settings.first()
        val zone = ZoneId.systemDefault()
        val resetTime = runCatching {
            UseDayResetTime(settings.useDayResetHour, settings.useDayResetMinute)
        }.getOrDefault(UseDayResetTime())
        val calculator = ConfigurableUseDayCalculator(zone, resetTime)
        val useDayId = calculator.idAt(nowMs)
        val generation = settings.useDayGenerationStartedAtMs
        return CurrentUseDayData(
            useDayId = useDayId,
            generationStartedAtMs = generation,
            zone = zone,
            calculator = calculator,
            sessions = currentUseDayRepository.sessionsForUseDay(useDayId, generation),
            launches = currentUseDayRepository.launchesForUseDay(useDayId, generation)
        )
    }

    private data class CurrentUseDayData(
        val useDayId: String,
        val generationStartedAtMs: Long,
        val zone: ZoneId,
        val calculator: ConfigurableUseDayCalculator,
        val sessions: List<ForegroundSession>,
        val launches: List<neth.iecal.curbox.domain.apprules.ForegroundLaunch>
    )

    private fun datesBetween(start: Long, end: Long): List<String> {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        if (endDate.isBefore(startDate)) return emptyList()

        val result = ArrayList<String>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            result.add(TimeTools.dayKey(cursor))
            cursor = cursor.plusDays(1)
        }
        return result
    }

    private fun AppUsageEntity.toStat(): AllAppsUsageFragment.Stat {
        return AllAppsUsageFragment.Stat(
            packageName = packageName,
            totalTime = totalTime,
            sessions = launchCount,
            hourlyUsage = parseHourly(hourlyUsage)
        )
    }

    private fun parseHourly(serialized: String?): LongArray {
        val result = LongArray(24)
        if (serialized.isNullOrEmpty()) return result
        val parts = serialized.split(',')
        for (i in 0 until minOf(24, parts.size)) {
            result[i] = parts[i].toLongOrNull() ?: 0L
        }
        return result
    }
}
