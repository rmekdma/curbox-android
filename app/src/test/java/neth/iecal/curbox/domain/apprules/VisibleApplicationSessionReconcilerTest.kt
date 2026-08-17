package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class VisibleApplicationSessionReconcilerTest {
    @Test
    fun disappearedPackagesFinishBeforeNewPackagesStart() = kotlinx.coroutines.runBlocking {
        val repository = RecordingSessionRepository()
        val reconciler = VisibleApplicationSessionReconciler(
            repository,
            ConfigurableUseDayCalculator(ZoneId.of("UTC"))
        )
        val current = mapOf(
            "com.example.old" to TrackedForegroundSession(
                packageName = "com.example.old",
                sessionId = 7L,
                useDayId = "2026-08-17",
                startedAtMs = 0L
            )
        )

        reconciler.reconcile(
            current = current,
            visiblePackages = setOf("com.example.new"),
            nowMs = java.time.Instant.parse("2026-08-17T10:00:00Z").toEpochMilli()
        )

        assertEquals(listOf("finish:7", "start:com.example.new"), repository.operations)
    }

    private class RecordingSessionRepository : CurrentUseDaySessionRepository {
        val operations = mutableListOf<String>()

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            operations += "start:$packageName"
            return 8L
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            operations += "finish:$id"
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }
}
