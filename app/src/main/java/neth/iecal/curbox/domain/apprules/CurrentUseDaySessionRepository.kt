package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession

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

    /** Starts a row in a particular use-day generation. Older fakes can use the legacy method. */
    suspend fun startSessionAtGeneration(
        useDayId: String,
        packageName: String,
        startedAtMs: Long,
        generationStartedAtMs: Long
    ): Long = startSession(useDayId, packageName, startedAtMs)

    suspend fun finishSession(id: Long, endedAtMs: Long)

    suspend fun updateSessionEnd(id: Long, endedAtMs: Long)

    suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession>

    suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long)

    /**
     * Closes a row left open by a process death without claiming that time continued until
     * restart. Room implementations discard only the uncheckpointed tail; lightweight fakes can
     * use the older finish operation as a conservative fallback.
     */
    suspend fun recoverOpenSessions(useDayId: String) {
        finishOpenSessions(useDayId, 0L)
    }

    suspend fun cleanupBeforeUseDay(currentUseDayId: String) = Unit
}
