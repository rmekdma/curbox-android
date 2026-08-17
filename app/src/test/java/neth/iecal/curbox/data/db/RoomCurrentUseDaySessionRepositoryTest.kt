package neth.iecal.curbox.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomCurrentUseDaySessionRepositoryTest {
    @Test
    fun restartRecoveryDiscardsOpenTailAndCleansBothCurrentDayLedgers() = runBlocking {
        val sessions = RecordingSessionDao()
        val launches = RecordingLaunchDao()
        val repository = RoomCurrentUseDaySessionRepository(sessions, launches)

        repository.recoverOpenSessions("2026-08-17")
        repository.cleanupBeforeUseDay("2026-08-17")

        assertEquals(listOf("2026-08-17"), sessions.discardedUseDays)
        assertEquals(listOf("2026-08-17"), sessions.cleanedBeforeUseDays)
        assertEquals(listOf("2026-08-17"), launches.cleanedBeforeUseDays)
    }

    private class RecordingSessionDao : ForegroundSessionDao {
        val discardedUseDays = mutableListOf<String>()
        val cleanedBeforeUseDays = mutableListOf<String>()

        override suspend fun insert(session: ForegroundSessionEntity): Long = 1L

        override suspend fun finish(id: Long, endedAtMs: Long): Int = 1

        override suspend fun updateEnd(id: Long, endedAtMs: Long): Int = 1

        override suspend fun getForUseDay(useDayId: String): List<ForegroundSessionEntity> = emptyList()

        override suspend fun finishOpenForUseDay(useDayId: String, endedAtMs: Long): Int = 1

        override suspend fun discardOpenForUseDay(useDayId: String): Int {
            discardedUseDays += useDayId
            return 1
        }

        override suspend fun deleteBeforeUseDay(currentUseDayId: String): Int {
            cleanedBeforeUseDays += currentUseDayId
            return 1
        }
    }

    private class RecordingLaunchDao : ForegroundLaunchDao {
        val cleanedBeforeUseDays = mutableListOf<String>()

        override suspend fun insert(event: ForegroundLaunchEntity): Long = 1L

        override suspend fun getForUseDay(useDayId: String): List<ForegroundLaunchEntity> = emptyList()

        override suspend fun deleteBeforeUseDay(currentUseDayId: String): Int {
            cleanedBeforeUseDays += currentUseDayId
            return 1
        }
    }
}
