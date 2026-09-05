package neth.iecal.curbox.domain.apprules

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.UseDayResetTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedDecisionWorkerTest {
    @Test
    fun submitReturnsWhileBackgroundPersistenceIsBlocked() {
        val repository = DelayedRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            val startedAt = System.nanoTime()
            val result = worker.submit(request(1L, 1L, TARGET_PACKAGE))
            val callbackElapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertEquals(SubmissionResult.ACCEPTED, result)
            assertTrue(repository.startStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("submit waited for persistence: ${callbackElapsedMs}ms", callbackElapsedMs < 100L)
            assertFalse(repository.released)
        } finally {
            repository.release()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun visibleSessionFlushCommitsBeforeEvaluationReadsPersistence() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue(outcomes.awaitCount(1))
            repository.operations.clear()

            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(2))

            val commitIndex = repository.operations.indexOf("commit:$TARGET_PACKAGE")
            val evaluateIndex = repository.operations.indexOf("evaluate:$OTHER_PACKAGE")
            assertTrue("missing flush commit: ${repository.operations}", commitIndex >= 0)
            assertTrue("missing evaluator read: ${repository.operations}", evaluateIndex >= 0)
            assertTrue(
                "evaluator read before flush commit: ${repository.operations}",
                commitIndex < evaluateIndex
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun rapidSwitchesAreSerializedAndPersistedRowsMatchPublishedDecisions() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 2_000L))
            worker.submit(request(3L, 1L, TARGET_PACKAGE, capturedAtMs = 3_000L))

            assertTrue(outcomes.awaitCount(3))
            assertEquals(
                listOf(TARGET_PACKAGE, OTHER_PACKAGE, TARGET_PACKAGE),
                outcomes.values.flatMap { it.packageDecisions.map(PackageDecision::packageName) }
            )

            val rows = repository.persistedSessions()
            assertEquals(3, rows.size)
            assertEquals(2_000L, rows[0].endedAtMs)
            assertEquals(3_000L, rows[1].endedAtMs)
            assertEquals(null, rows[2].endedAtMs)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun staleLifecycleAndRuntimeRevisionCannotPublishSideEffects() {
        val repository = BlockingReadRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE))
            assertTrue(repository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            worker.beginLifecycle(
                lifecycleGeneration = LifecycleGeneration(2L),
                acceptedRuntime = acceptedRuntime(RuntimeRevision(2L))
            )
            repository.releaseRead()
            assertTrue(repository.firstReadFinished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(outcomes.awaitIdle())
            assertTrue("stale request published: ${outcomes.values}", outcomes.values.isEmpty())

            val staleRevisionRequest = request(
                sourceOrder = 2L,
                lifecycle = 2L,
                packageName = TARGET_PACKAGE,
                runtimePublication = RuntimePublication(
                    runtimeRevision = RuntimeRevision(1L),
                    candidateRuntime = runtime()
                )
            )
            assertEquals(SubmissionResult.ACCEPTED, worker.submit(staleRevisionRequest))
            assertTrue(outcomes.awaitIdle())
            assertTrue("stale revision published: ${outcomes.values}", outcomes.values.isEmpty())

            worker.submit(request(3L, 2L, OTHER_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(1))
            assertEquals(RuntimeRevision(2L), outcomes.values.single().acceptedRuntimeRevision)
        } finally {
            repository.releaseRead()
            worker.stop(recoveryStop(LifecycleGeneration(2L)))
        }
    }

    private fun worker(
        repository: CurrentUseDaySessionRepository,
        sink: RecordingOutcomeSink
    ): SerializedDecisionWorker = SerializedDecisionWorker(
        lifecycleGeneration = LifecycleGeneration(1L),
        acceptedRuntime = acceptedRuntime(RuntimeRevision(1L)),
        repository = repository,
        outcomeSink = sink
    )

    private fun request(
        sourceOrder: Long,
        lifecycle: Long,
        packageName: String,
        capturedAtMs: Long = 1_000L,
        runtimePublication: RuntimePublication? = null
    ): DecisionRequest = DecisionRequest(
        sourceOrderIdentity = SourceOrderIdentity(sourceOrder),
        lifecycleGeneration = LifecycleGeneration(lifecycle),
        reason = ObservationKind.REAL_EVENT,
        observation = ForegroundFacts(
            capturedAtWallMs = capturedAtMs,
            capturedAtElapsedMs = capturedAtMs,
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = packageName,
                eventWallMs = capturedAtMs,
                eventElapsedMs = capturedAtMs
            ),
            activeRoot = ActiveRootFact(
                packageName = packageName,
                readState = ForegroundReadState.AVAILABLE
            ),
            applicationWindows = ApplicationWindowsFact(
                packages = setOf(packageName),
                readState = ForegroundReadState.AVAILABLE
            ),
            displayState = DisplayState.UNLOCKED
        ),
        runtimePublication = runtimePublication
    )

    private fun acceptedRuntime(revision: RuntimeRevision): AcceptedRuleRuntimeSnapshot =
        AcceptedRuleRuntimeSnapshot(runtime(), revision)

    private fun runtime(): RuleRuntimeSnapshot = RuleRuntimeSnapshot(
        snapshot = AppRuleSnapshot(
            appGroups = listOf(
                AppRuleAppGroup(
                    id = TARGET_GROUP_ID,
                    name = "Target",
                    selectedPackages = listOf(TARGET_PACKAGE)
                ),
                AppRuleAppGroup(
                    id = OTHER_GROUP_ID,
                    name = "Other",
                    selectedPackages = listOf(OTHER_PACKAGE)
                )
            ),
            appRules = listOf(
                AppRule(
                    id = TARGET_RULE_ID,
                    name = "Target rule",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(TARGET_GROUP_ID),
                    allowedMinutes = 0L
                )
            )
        ),
        resetTime = UseDayResetTime(),
        useDayGenerationStartedAtMs = 0L,
        launchablePackages = setOf(TARGET_PACKAGE, OTHER_PACKAGE),
        evidencePolicy = ForegroundEvidencePolicySnapshot()
    )

    private fun recoveryStop(generation: LifecycleGeneration): RecoveryOnlyStop =
        RecoveryOnlyStop(
            requestedAtElapsedMs = 1_000L,
            reason = StopReason.DESTROY,
            lifecycleGeneration = generation
        )

    private class RecordingOutcomeSink : DecisionOutcomeSink {
        val values = Collections.synchronizedList(mutableListOf<DecisionOutcome>())

        override fun publish(outcome: DecisionOutcome) {
            values += outcome
        }

        fun awaitCount(expected: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (System.nanoTime() < deadline) {
                if (values.size >= expected) return true
                Thread.yield()
            }
            return values.size >= expected
        }

        fun awaitIdle(): Boolean {
            Thread.sleep(50L)
            return true
        }
    }

    private open class RecordingRepository : CurrentUseDaySessionRepository {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        private val nextId = AtomicLong(1L)
        private val sessions = Collections.synchronizedList(mutableListOf<ForegroundSession>())

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            operations += "start:$packageName"
            val id = nextId.getAndIncrement()
            sessions += ForegroundSession(id, useDayId, packageName, startedAtMs, null)
            return id
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            operations += "finish:$id"
            updateSessionEnd(id, endedAtMs)
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            synchronized(sessions) {
                val index = sessions.indexOfFirst { it.id == id }
                if (index >= 0) sessions[index] = sessions[index].copy(endedAtMs = endedAtMs)
            }
        }

        override suspend fun commitSessionCheckpoint(
            id: Long,
            endedAtMs: Long,
            usage: List<ForegroundUsageCheckpoint>
        ): Boolean {
            val packageName = synchronized(sessions) { sessions.first { it.id == id }.packageName }
            operations += "commit:$packageName"
            updateSessionEnd(id, endedAtMs)
            return true
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            operations += "evaluate:${sessions.lastOrNull()?.packageName ?: "none"}"
            return persistedSessions()
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun persistedSessions(): List<ForegroundSession> = synchronized(sessions) { sessions.toList() }
    }

    private class DelayedRepository : RecordingRepository() {
        val startStarted = CountDownLatch(1)
        @Volatile var released = false
        private val releaseStart = CountDownLatch(1)

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            startStarted.countDown()
            releaseStart.await()
            return super.startSession(useDayId, packageName, startedAtMs)
        }

        fun release() {
            released = true
            releaseStart.countDown()
        }
    }

    private class BlockingReadRepository : RecordingRepository() {
        val readStarted = CountDownLatch(1)
        val firstReadFinished = CountDownLatch(1)
        private val releaseRead = CountDownLatch(1)
        private var reads = 0

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (reads++ == 0) {
                readStarted.countDown()
                releaseRead.await()
                firstReadFinished.countDown()
            }
            return super.sessionsForUseDay(useDayId)
        }

        fun releaseRead() = releaseRead.countDown()
    }

    companion object {
        private const val TARGET_PACKAGE = "com.example.target"
        private const val OTHER_PACKAGE = "com.example.other"
        private const val TARGET_GROUP_ID = "target-group"
        private const val OTHER_GROUP_ID = "other-group"
        private const val TARGET_RULE_ID = "target-rule"
        private const val WAIT_TIMEOUT_MS = 2_000L
    }
}
