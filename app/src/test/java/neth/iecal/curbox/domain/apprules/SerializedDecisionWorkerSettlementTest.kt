package neth.iecal.curbox.domain.apprules

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.UseDayResetTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedDecisionWorkerSettlementTest {

    private class RecordingOutcomeSink : DecisionOutcomeSink {
        val values = Collections.synchronizedList(mutableListOf<DecisionOutcome>())

        val settlements: List<DecisionOutcome.SettlementFinished>
            get() = synchronized(values) {
                values.filterIsInstance<DecisionOutcome.SettlementFinished>()
            }

        override fun publish(outcome: DecisionOutcome) {
            values += outcome
        }

        fun awaitSettlementCount(expected: Int, timeoutMs: Long = 2_000L): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (settlements.size >= expected) return true
                Thread.yield()
            }
            return settlements.size >= expected
        }
    }

    private class FakeRepository : CurrentUseDaySessionRepository {
        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long = 1L
        override suspend fun finishSession(id: Long, endedAtMs: Long) {}
        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {}
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {}
    }

    private class FakeUsageResetRepository : UsageResetRepository {
        override suspend fun reset(request: UsageResetRequest): UsageResetResult =
            UsageResetResult(request, UsageResetDelta(emptyMap(), emptyMap()), emptyMap())
        override suspend fun resetAndStartSessions(
            request: UsageResetRequest,
            restarts: List<UsageResetSessionRestart>
        ): UsageResetResult = UsageResetResult(request, UsageResetDelta(emptyMap(), emptyMap()), emptyMap())
    }

    private fun acceptedRuntime(): AcceptedRuleRuntimeSnapshot = AcceptedRuleRuntimeSnapshot(
        RuleRuntimeSnapshot(
            snapshot = AppRuleSnapshot(),
            resetTime = UseDayResetTime(),
            useDayGenerationStartedAtMs = 0L
        ),
        RuntimeRevision(1L)
    )

    @Test
    fun submitSettlementEnqueuesAndPublishesSettlementFinishedOutcome() {
        val sink = RecordingOutcomeSink()
        val repository = FakeRepository()
        var reconciled = false
        val rule = AppRule(
            id = "rule1",
            name = "Rule 1",
            appGroupId = "grp1",
            rolloverEnabled = true,
            allowedMinutes = 60
        )
        val initialPool = RuleRolloverPool(ruleId = "rule1", accumulatedMinutes = 10L, lastSettledUseDayId = "2026-08-17")
        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = repository,
            zone = { java.time.ZoneId.of("UTC") },
            readSettings = {
                Settings(
                    appRuleSnapshot = AppRuleSnapshot(
                        appRules = listOf(rule),
                        appGroups = listOf(AppRuleAppGroup(id = "grp1", name = "Group 1", selectedPackages = listOf("pkg1")))
                    ),
                    appRuleRolloverState = AppRuleRolloverState(pools = mapOf(rule.id to initialPool)),
                    useDayResetHour = 4,
                    useDayResetMinute = 0
                )
            },
            writeRolloverState = {
                reconciled = true
                true
            }
        )

        val worker = SerializedDecisionWorker(
            lifecycleGeneration = LifecycleGeneration(1L),
            acceptedRuntime = acceptedRuntime(),
            repository = repository,
            outcomeSink = sink,
            usageResetRepository = FakeUsageResetRepository(),
            coordinator = coordinator
        )

        try {
            val request = SettlementRequest(wallClockMs = java.time.Instant.parse("2026-08-18T05:00:00Z").toEpochMilli())
            val submission = worker.submitSettlement(request)

            assertEquals(SubmissionResult.ACCEPTED, submission)
            assertTrue(sink.awaitSettlementCount(1))
            val outcome = sink.settlements.single()
            assertEquals(request, outcome.request)
            assertTrue(outcome.succeeded)
            assertTrue(reconciled)
        } finally {
            worker.stop(
                RecoveryOnlyStop(
                    requestedAtElapsedMs = 0L,
                    reason = StopReason.DESTROY,
                    lifecycleGeneration = LifecycleGeneration(1L)
                )
            )
        }
    }

    @Test
    fun submitSettlementWhenWorkerStoppedRejectsNotReady() {
        val sink = RecordingOutcomeSink()
        val repository = FakeRepository()
        val worker = SerializedDecisionWorker(
            lifecycleGeneration = LifecycleGeneration(1L),
            acceptedRuntime = acceptedRuntime(),
            repository = repository,
            outcomeSink = sink,
            usageResetRepository = FakeUsageResetRepository()
        )

        worker.stop(
            RecoveryOnlyStop(
                requestedAtElapsedMs = 0L,
                reason = StopReason.DESTROY,
                lifecycleGeneration = LifecycleGeneration(1L)
            )
        )

        val submission = worker.submitSettlement(SettlementRequest(wallClockMs = 10_000L))
        assertEquals(SubmissionResult.REJECTED_NOT_READY, submission)
    }

    @Test
    fun settlementFailureIsReportedNonFatalAndPublishesFailureOutcome() {
        val sink = RecordingOutcomeSink()
        val repository = FakeRepository()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = repository,
            readSettings = { throw RuntimeException("Simulated settlement crash") },
            onNonFatalError = { errors += it }
        )

        val worker = SerializedDecisionWorker(
            lifecycleGeneration = LifecycleGeneration(1L),
            acceptedRuntime = acceptedRuntime(),
            repository = repository,
            outcomeSink = sink,
            usageResetRepository = FakeUsageResetRepository(),
            coordinator = coordinator,
            onNonFatalError = { errors += it }
        )

        try {
            val request = SettlementRequest(wallClockMs = 10_000L)
            assertEquals(SubmissionResult.ACCEPTED, worker.submitSettlement(request))
            assertTrue(sink.awaitSettlementCount(1))
            val outcome = sink.settlements.single()
            assertFalse(outcome.succeeded)
            assertTrue(worker.isReadyForSubmission())
        } finally {
            worker.stop(
                RecoveryOnlyStop(
                    requestedAtElapsedMs = 0L,
                    reason = StopReason.DESTROY,
                    lifecycleGeneration = LifecycleGeneration(1L)
                )
            )
        }
    }
}
