package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun threeUnmetContributorGroupConditionsOnlyDenyInsideTheActiveRange() = runBlocking {
        val target = AppRuleAppGroup("target", "Target", listOf("com.example.target"))
        val contributors = (1..3).map { index ->
            AppRuleAppGroup("condition-$index", "Condition $index", listOf("com.example.condition$index"))
        }
        val rule = AppRule(
            id = "rule",
            name = "Three conditions",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 16 * 60,
            scope = neth.iecal.curbox.data.models.AppRuleScope.forGroup(target.id),
            allowedMinutes = 30,
            contributorGroupIds = contributors.mapTo(linkedSetOf()) { it.id },
            usageConditionEnabled = true,
            contributorGroupConditionMinutes = contributors.associate { it.id to 10L }
        )
        val now = java.time.Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        val repository = FakeSessionRepository(emptyList())

        val result = AppRuleEnforcement(repository, ZoneId.of("UTC")).check(
            snapshot = AppRuleSnapshot(listOf(target) + contributors, listOf(rule)),
            packageName = "com.example.target",
            useDayId = "2026-08-17",
            nowMs = now
        )

        assertFalse(result.isAllowed)
        assertTrue(result.denyingRules.single().conditionEnabled)

        val afterRange = AppRuleEnforcement(repository, ZoneId.of("UTC")).check(
            snapshot = AppRuleSnapshot(listOf(target) + contributors, listOf(rule)),
            packageName = "com.example.target",
            useDayId = "2026-08-17",
            nowMs = java.time.Instant.parse("2026-08-17T16:55:00Z").toEpochMilli()
        )

        assertTrue(afterRange.isAllowed)
        assertTrue(afterRange.denyingRules.isEmpty())
    }

    @Test
    fun storageFailureDoesNotAbortTheNextEnforcementDecision() = runBlocking {
        val group = AppRuleAppGroup("group", "Reader", listOf("com.example.reader"))
        val rule = AppRule(
            id = "rule",
            name = "Reader",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            appGroupId = group.id,
            allowedMinutes = 0
        )
        val now = java.time.Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        val repository = FailingThenWorkingRepository(
            ForegroundSession(
                useDayId = "2026-08-17",
                packageName = "com.example.reader",
                startedAtMs = now,
                endedAtMs = now
            )
        )
        val enforcement = AppRuleEnforcement(repository, ZoneId.of("UTC"))
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))

        assertSame(
            SafeAppRuleEvaluationResult.RecoverableFailure,
            enforcement.checkSafely(snapshot, "com.example.reader", "2026-08-17", now)
        )
        repository.fail = false
        val success = enforcement.checkSafely(
            snapshot,
            "com.example.reader",
            "2026-08-17",
            now
        ) as SafeAppRuleEvaluationResult.Success
        assertFalse(success.evaluation.isAllowed)
    }

    @Test
    fun cancellationFailureIsRethrownWithoutUsingTheFailOpenFallback() = runBlocking {
        val group = AppRuleAppGroup("group", "Reader", listOf("com.example.reader"))
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(group),
            appRules = listOf(
                AppRule(
                    id = "rule",
                    name = "Reader",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(group.id),
                    allowedMinutes = 0
                )
            )
        )
        val repository = CancellingSessionRepository()
        val enforcement = AppRuleEnforcement(repository, ZoneId.of("UTC"))
        val thrown = try {
            enforcement.checkSafely(
                snapshot = snapshot,
                packageName = "com.example.reader",
                useDayId = "2026-08-17",
                nowMs = java.time.Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(thrown is CancellationException)
    }

    private open class FakeSessionRepository(
        private val rows: List<ForegroundSession>
    ) : CurrentUseDaySessionRepository {
        val requestedUseDayIds = mutableListOf<String>()

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L
        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit
        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit
        open override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            requestedUseDayIds += useDayId
            return rows.filter { it.useDayId == useDayId }
        }
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private class FailingThenWorkingRepository(
        private val row: ForegroundSession
    ) : FakeSessionRepository(listOf(row)) {
        var fail = true

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (fail) throw IllegalStateException("temporary storage failure")
            return super.sessionsForUseDay(useDayId)
        }
    }

    private class CancellingSessionRepository : FakeSessionRepository(emptyList()) {
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            throw CancellationException("injected evaluator cancellation")
        }
    }
}
