package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession

/** One exact calendar bucket written with a foreground session checkpoint. */
data class ForegroundUsageCheckpoint(
    val date: String,
    val packageName: String,
    val hour: Int,
    val durationMs: Long,
    val lastUsedMs: Long
)

/** Public persistence seam used by the tracker and deterministic rule evaluator. */
interface CurrentUseDaySessionRepository {
    suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long

    /** Records a launch event in the current use-day ledger when statistics are enabled. */
    suspend fun recordLaunch(
        useDayId: String,
        packageName: String,
        launchedAtMs: Long,
        generationStartedAtMs: Long = 0L
    ) = Unit

    /** Records the calendar-keyed launch aggregate when statistics are enabled. */
    suspend fun recordLaunchStatistics(
        packageName: String,
        launchedAtMs: Long
    ) = Unit

    /** Starts a row in a particular use-day generation. Older fakes can use the legacy method. */
    suspend fun startSessionAtGeneration(
        useDayId: String,
        packageName: String,
        startedAtMs: Long,
        generationStartedAtMs: Long,
        statisticsTracked: Boolean = true
    ): Long = startSession(useDayId, packageName, startedAtMs)

    suspend fun finishSession(id: Long, endedAtMs: Long)

    suspend fun updateSessionEnd(id: Long, endedAtMs: Long)

    /**
     * Atomically checkpoints the session and its derived calendar buckets when supported by the
     * backing store. Lightweight repositories retain the old close-only behavior.
     */
    suspend fun commitSessionCheckpoint(
        id: Long,
        endedAtMs: Long,
        usage: List<ForegroundUsageCheckpoint>
    ): Boolean {
        updateSessionEnd(id, endedAtMs)
        return true
    }

    suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession>

    /** Generation-aware read used when a reset setting starts a fresh current use day. */
    suspend fun sessionsForUseDay(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundSession> = sessionsForUseDay(useDayId)

    suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long)

    /**
     * Closes a row left open by a process death without claiming that time continued until
     * restart. Room implementations discard only the uncheckpointed tail; lightweight fakes can
     * use the older finish operation as a conservative fallback.
     */
    suspend fun recoverOpenSessions(useDayId: String) {
        finishOpenSessions(useDayId, 0L)
    }

    suspend fun cleanupBeforeUseDay(
        currentUseDayId: String,
        generationStartedAtMs: Long = 0L
    ) = Unit
}
