package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession
import java.time.Instant
import java.time.ZoneId

/** A launch event from the authoritative current-use-day ledger. */
data class ForegroundLaunch(
    val id: Long = 0L,
    val useDayId: String = "",
    val packageName: String = "",
    val launchedAtMs: Long = 0L,
    val useDayGenerationStartedAtMs: Long = 0L
)

/** Public read seam for current-use-day display and group usage. */
interface CurrentUseDayUsageRepository {
    suspend fun sessionsForUseDay(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundSession> = emptyList()

    suspend fun launchesForUseDay(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundLaunch> = emptyList()
}

data class ForegroundUsageAggregate(
    val packageName: String,
    val totalTimeMs: Long,
    val launchCount: Int,
    val hourlyUsage: LongArray
)

/** Merges exact session intersections for current-use-day display and group totals. */
object CurrentUseDayUsageAggregator {
    fun aggregate(
        sessions: Iterable<ForegroundSession>,
        launches: Iterable<ForegroundLaunch>,
        nowMs: Long,
        zone: ZoneId,
        generationStartedAtMs: Long = 0L
    ): List<ForegroundUsageAggregate> {
        val intervals = mergedIntervals(sessions, nowMs, generationStartedAtMs)
        val launchCounts = launches.asSequence()
            .filter {
                generationStartedAtMs <= 0L ||
                    it.useDayGenerationStartedAtMs >= generationStartedAtMs
            }
            .filter { it.launchedAtMs <= nowMs }
            .groupingBy { it.packageName }
            .eachCount()

        val packageNames = (intervals.keys + launchCounts.keys).distinct()
        return packageNames.map { packageName ->
            val packageIntervals = intervals[packageName].orEmpty()
            val hourly = LongArray(24)
            var total = 0L
            packageIntervals.forEach { (start, end) ->
                total += end - start
                addHourly(hourly, start, end, zone)
            }
            ForegroundUsageAggregate(
                packageName = packageName,
                totalTimeMs = total,
                launchCount = launchCounts[packageName] ?: 0,
                hourlyUsage = hourly
            )
        }.sortedByDescending { it.totalTimeMs }
    }

    fun usageMillisBetween(
        sessions: Iterable<ForegroundSession>,
        packageNames: Set<String>,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        generationStartedAtMs: Long = 0L
    ): Long {
        if (packageNames.isEmpty() || endMs <= startMs) return 0L
        return mergedIntervals(sessions, nowMs, generationStartedAtMs)
            .filterKeys { it in packageNames }
            .values
            .sumOf { intervals ->
                intervals.sumOf { (sessionStart, sessionEnd) ->
                    (minOf(endMs, sessionEnd) - maxOf(startMs, sessionStart))
                        .coerceAtLeast(0L)
                }
            }
    }

    private fun mergedIntervals(
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        generationStartedAtMs: Long
    ): Map<String, List<Pair<Long, Long>>> = sessions.asSequence()
        .filter {
            generationStartedAtMs <= 0L ||
                it.useDayGenerationStartedAtMs >= generationStartedAtMs
        }
        .filter { it.statisticsTracked }
        .mapNotNull { session ->
            val start = session.startedAtMs
            val end = minOf(session.endedAtMs ?: nowMs, nowMs)
            if (end > start) session.packageName to (start to end) else null
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, intervals) ->
            intervals.sortedBy { it.first }.fold(mutableListOf()) { merged, interval ->
                val previous = merged.lastOrNull()
                if (previous != null && interval.first <= previous.second) {
                    merged[merged.lastIndex] = previous.first to maxOf(previous.second, interval.second)
                } else {
                    merged += interval
                }
                merged
            }
        }

    private fun addHourly(hourly: LongArray, startMs: Long, endMs: Long, zone: ZoneId) {
        var cursor = startMs
        while (cursor < endMs) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = local.plusHours(1).toInstant().toEpochMilli()
            val segmentEnd = minOf(endMs, nextHour)
            hourly[local.hour] += segmentEnd - cursor
            cursor = segmentEnd
        }
    }
}
