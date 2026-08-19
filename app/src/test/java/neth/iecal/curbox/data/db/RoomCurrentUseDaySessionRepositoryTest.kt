package neth.iecal.curbox.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RoomCurrentUseDaySessionRepositoryTest {
    @Test
    fun currentUseDayReadsUseDayAndGenerationAwareSessionAndLaunchLedgers() = runBlocking {
        val sessions = RecordingSessionDao().apply {
            rows = listOf(
                ForegroundSessionEntity(
                    id = 1L,
                    useDayId = "2026-08-17",
                    packageName = "com.example.current",
                    startedAtMs = 100L,
                    useDayGenerationStartedAtMs = 200L
                ),
                ForegroundSessionEntity(
                    id = 2L,
                    useDayId = "2026-08-17",
                    packageName = "com.example.old",
                    startedAtMs = 100L,
                    useDayGenerationStartedAtMs = 100L
                )
            )
        }
        val launches = RecordingLaunchDao().apply {
            rows = listOf(
                ForegroundLaunchEntity(
                    id = 3L,
                    useDayId = "2026-08-17",
                    packageName = "com.example.current",
                    launchedAtMs = 100L,
                    useDayGenerationStartedAtMs = 200L
                ),
                ForegroundLaunchEntity(
                    id = 4L,
                    useDayId = "2026-08-17",
                    packageName = "com.example.old",
                    launchedAtMs = 100L,
                    useDayGenerationStartedAtMs = 100L
                )
            )
        }
        val repository = RoomCurrentUseDaySessionRepository(sessions, launches)

        assertEquals(
            listOf("com.example.current"),
            repository.sessionsForUseDay("2026-08-17", 200L).map { it.packageName }
        )
        assertEquals(
            listOf("com.example.current"),
            repository.launchesForUseDay("2026-08-17", 200L).map { it.packageName }
        )
        assertEquals(listOf(200L), sessions.readGenerations)
        assertEquals(listOf(200L), launches.readGenerations)
    }

    @Test
    fun sessionStartPersistsStatisticsPolicyForTheInterval() = runBlocking {
        val sessions = RecordingSessionDao()
        val repository = RoomCurrentUseDaySessionRepository(sessions)

        repository.startSessionAtGeneration(
            useDayId = "2026-08-17",
            packageName = "com.example.enforced",
            startedAtMs = 1_000L,
            generationStartedAtMs = 10L,
            statisticsTracked = false
        )

        assertEquals(false, sessions.inserted.single().statisticsTracked)
    }

    @Test
    fun restartRecoveryDiscardsOpenTailAndCleansBothCurrentDayLedgers() = runBlocking {
        val sessions = RecordingSessionDao()
        val launches = RecordingLaunchDao()
        val repository = RoomCurrentUseDaySessionRepository(sessions, launches)

        repository.recoverOpenSessions("2026-08-17")
        repository.cleanupBeforeUseDay("2026-08-17", 123L)

        assertEquals(listOf("2026-08-17"), sessions.discardedUseDays)
        assertEquals(listOf("2026-08-17"), sessions.cleanedBeforeUseDays)
        assertEquals(listOf("2026-08-17"), launches.cleanedBeforeUseDays)
        assertEquals(listOf(123L), sessions.cleanedGenerations)
        assertEquals(listOf(123L), launches.cleanedGenerations)
    }

    @Test
    fun checkpointRequiresAnAffectedSessionRow() = runBlocking {
        val sessions = RecordingSessionDao().apply { updateEndResult = 0 }
        val repository = RoomCurrentUseDaySessionRepository(sessions)

        try {
            repository.commitSessionCheckpoint(
                id = 99L,
                endedAtMs = 2_000L,
                usage = emptyList()
            )
            fail("missing checkpoint row must fail")
        } catch (_: IllegalStateException) {
            // Expected. The aggregate transaction must roll back instead of silently advancing.
        }
    }

    private class RecordingSessionDao : ForegroundSessionDao {
        val inserted = mutableListOf<ForegroundSessionEntity>()
        var rows = emptyList<ForegroundSessionEntity>()
        val readGenerations = mutableListOf<Long>()
        val discardedUseDays = mutableListOf<String>()
        val cleanedBeforeUseDays = mutableListOf<String>()
        val cleanedGenerations = mutableListOf<Long>()
        var updateEndResult = 1

        override suspend fun insert(session: ForegroundSessionEntity): Long {
            inserted += session
            return 1L
        }

        override suspend fun finish(id: Long, endedAtMs: Long): Int = 1

        override suspend fun updateEnd(id: Long, endedAtMs: Long): Int = updateEndResult

        override suspend fun deleteByIds(ids: List<Long>): Int = ids.size

        override suspend fun getById(id: Long): ForegroundSessionEntity? =
            rows.find { it.id == id }

        override suspend fun getForUseDay(useDayId: String): List<ForegroundSessionEntity> = emptyList()

        override suspend fun getForUseDaySinceGeneration(
            useDayId: String,
            generationStartedAtMs: Long
        ): List<ForegroundSessionEntity> {
            readGenerations += generationStartedAtMs
            return rows.filter {
                it.useDayId == useDayId &&
                    it.useDayGenerationStartedAtMs >= generationStartedAtMs
            }
        }

        override suspend fun cutAt(id: Long, endedAtMs: Long): Int = 1

        override suspend fun finishOpenForUseDay(useDayId: String, endedAtMs: Long): Int = 1

        override suspend fun discardOpenForUseDay(useDayId: String): Int {
            discardedUseDays += useDayId
            return 1
        }

        override suspend fun deleteBeforeUseDay(currentUseDayId: String): Int {
            cleanedBeforeUseDays += currentUseDayId
            return 1
        }

        override suspend fun deleteBeforeUseDayGeneration(
            currentUseDayId: String,
            generationStartedAtMs: Long
        ): Int {
            cleanedBeforeUseDays += currentUseDayId
            cleanedGenerations += generationStartedAtMs
            return 1
        }

    }

    private class RecordingLaunchDao : ForegroundLaunchDao {
        var rows = emptyList<ForegroundLaunchEntity>()
        val readGenerations = mutableListOf<Long>()
        val cleanedBeforeUseDays = mutableListOf<String>()
        val cleanedGenerations = mutableListOf<Long>()

        override suspend fun insert(event: ForegroundLaunchEntity): Long = 1L

        override suspend fun getForUseDay(useDayId: String): List<ForegroundLaunchEntity> = emptyList()

        override suspend fun deleteBeforeUseDay(currentUseDayId: String): Int {
            cleanedBeforeUseDays += currentUseDayId
            return 1
        }

        override suspend fun getForUseDaySinceGeneration(
            useDayId: String,
            generationStartedAtMs: Long
        ): List<ForegroundLaunchEntity> {
            readGenerations += generationStartedAtMs
            return rows.filter {
                it.useDayId == useDayId &&
                    it.useDayGenerationStartedAtMs >= generationStartedAtMs
            }
        }

        override suspend fun deleteByIds(ids: List<Long>): Int = ids.size

        override suspend fun deleteBeforeUseDayGeneration(
            currentUseDayId: String,
            generationStartedAtMs: Long
        ): Int {
            cleanedBeforeUseDays += currentUseDayId
            cleanedGenerations += generationStartedAtMs
            return 1
        }
    }
}
