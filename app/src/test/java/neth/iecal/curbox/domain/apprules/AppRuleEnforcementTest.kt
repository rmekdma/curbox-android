package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AppRuleEnforcementTest {
    @Test
    fun serviceBoundaryReadsCurrentUseDaySessionsBeforeDecision() = runBlocking {
        val group = AppRuleAppGroup("group", "Reader", listOf("com.example.reader"))
        val rule = AppRule(
            id = "rule",
            name = "Reader",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            appGroupId = group.id,
            allowedMinutes = 1
        )
        val now = java.time.Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        val repository = FakeSessionRepository(
            listOf(
                ForegroundSession(
                    useDayId = "2026-08-17",
                    packageName = "com.example.reader",
                    startedAtMs = now - 2 * 60_000L,
                    endedAtMs = now
                )
            )
        )

        val result = AppRuleEnforcement(repository, ZoneId.of("UTC")).check(
            AppRuleSnapshot(listOf(group), listOf(rule)),
            "com.example.reader",
            "2026-08-17",
            now
        )

        assertFalse(result.isAllowed)
        assertTrue(repository.requestedUseDayIds.contains("2026-08-17"))
    }

    private class FakeSessionRepository(
        private val rows: List<ForegroundSession>
    ) : CurrentUseDaySessionRepository {
        val requestedUseDayIds = mutableListOf<String>()

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L
        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit
        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            requestedUseDayIds += useDayId
            return rows.filter { it.useDayId == useDayId }
        }
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }
}
