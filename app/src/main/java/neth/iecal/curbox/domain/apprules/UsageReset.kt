package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.TimeTools
import java.time.Instant
import java.time.ZoneId

data class UsageResetRequest(
    val useDayId: String,
    val generationStartedAtMs: Long = 0L,
    val packageNames: Set<String>,
    val resetAtMs: Long,
    /** Ephemeral process-bound response correlation; it is not part of the usage ledger. */
    val requestId: String = ""
)

data class UsageResetBucketKey(
    val date: String,
    val packageName: String,
    val hour: Int
)

data class UsageResetDelta(
    val timeByBucketMs: Map<UsageResetBucketKey, Long>,
    val launchesByPackage: Map<String, Int>,
    val launchesByDatePackage: Map<Pair<String, String>, Int> = emptyMap()
) {
    val totalTimeByPackage: Map<String, Long>
        get() = timeByBucketMs.entries
            .groupingBy { it.key.packageName }
            .fold(0L) { total, (_, value) -> safeAdd(total, value) }

    companion object {
        private fun safeAdd(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }
}

/** Pure exact subtraction calculation used before a Room reset transaction mutates its rows. */
object UsageResetDeltaCalculator {
    fun calculate(
        sessions: Iterable<ForegroundSession>,
        launches: Iterable<ForegroundLaunch>,
        packageNames: Set<String>,
        resetAtMs: Long,
        zone: ZoneId
    ): UsageResetDelta {
        val buckets = linkedMapOf<UsageResetBucketKey, Long>()
        sessions.forEach { session ->
            if (session.packageName !in packageNames || !session.statisticsTracked) return@forEach
            // An open row contains an in-memory tail that has not necessarily reached the
            // calendar aggregate yet. Only subtract the persisted closed portion; the Room
            // transaction still cuts the open row at the reset boundary.
            val persistedEnd = session.endedAtMs ?: return@forEach
            val end = minOf(persistedEnd, resetAtMs)
            if (end <= session.startedAtMs) return@forEach
            addHourlySegments(
                startMs = session.startedAtMs,
                endMs = end,
                packageName = session.packageName,
                zone = zone,
                buckets = buckets
            )
        }
        val selectedLaunches = launches.asSequence()
            .filter { it.packageName in packageNames && it.launchedAtMs <= resetAtMs }
            .toList()
        val launchCounts = selectedLaunches
            .groupingBy { it.packageName }
            .eachCount()
        val launchCountsByDate = selectedLaunches
            .groupingBy {
                TimeTools.dayKey(
                    Instant.ofEpochMilli(it.launchedAtMs).atZone(zone).toLocalDate()
                ) to it.packageName
            }
            .eachCount()
        return UsageResetDelta(buckets, launchCounts, launchCountsByDate)
    }

    private fun addHourlySegments(
        startMs: Long,
        endMs: Long,
        packageName: String,
        zone: ZoneId,
        buckets: MutableMap<UsageResetBucketKey, Long>
    ) {
        var cursor = startMs
        while (cursor < endMs) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = local
                .toLocalDate()
                .atTime(local.hour, 0)
                .plusHours(1)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            val segmentEnd = minOf(nextHour, endMs)
            val key = UsageResetBucketKey(
                date = TimeTools.dayKey(local.toLocalDate()),
                packageName = packageName,
                hour = local.hour
            )
            buckets[key] = (buckets[key] ?: 0L) + (segmentEnd - cursor)
            cursor = segmentEnd
        }
    }
}

data class UsageResetResult(
    val request: UsageResetRequest,
    val delta: UsageResetDelta,
    val restartedSessionIds: Map<String, Long> = emptyMap()
)

/**
 * A foreground row that must be recreated as part of the reset transaction.  The reset is a
 * new launch from the user's point of view, so the replacement row also gets one launch event.
 */
data class UsageResetSessionRestart(
    val useDayId: String,
    val packageName: String,
    val startedAtMs: Long,
    val generationStartedAtMs: Long,
    /** Existing Room row owned by the service, when this is a visible session restart. */
    val activeSessionId: Long = 0L,
    /** Last wall-clock point already reflected in the calendar aggregate. */
    val persistedThroughMs: Long = startedAtMs,
    val statisticsTracked: Boolean = true,
    val recordLaunch: Boolean = true
)

interface UsageResetRepository {
    suspend fun reset(request: UsageResetRequest): UsageResetResult

    /**
     * Resets the ledger and recreates any still-visible foreground rows atomically.  The
     * default keeps lightweight test repositories source compatible; the Room implementation
     * overrides it so no aggregate or session can be observed half-reset.
     */
    suspend fun resetAndStartSessions(
        request: UsageResetRequest,
        restarts: List<UsageResetSessionRestart>
    ): UsageResetResult = reset(request)
}
