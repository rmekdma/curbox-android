package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.AppUsageTrackingPolicy
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.ForegroundUsageCheckpoint
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.AppUsageTracker
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Phase 0 RED contracts for callback latency and flush-before-decision ordering. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerCallbackFlushOrderingRedTest {
    @Test
    fun trackerCallbackFinishesBeforeWorkerOwnershipHandoff() {
        val repository = BlockingTrackerRepository()
        val service = recordingService()
        val tracker = configureTracker(repository, service)
        val callbackFinished = CountDownLatch(1)
        val handoffFinished = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>(null)
        val handoffFailure = AtomicReference<Throwable?>(null)
        val callbackThread = Thread {
            try {
                sendWindowEvent(tracker, TARGET_PACKAGE)
            } catch (error: Throwable) {
                callbackFailure.set(error)
            } finally {
                callbackFinished.countDown()
            }
        }
        val handoffThread = Thread {
            try {
                tracker.handoffForegroundOwnershipToDecisionWorker()
            } catch (error: Throwable) {
                handoffFailure.set(error)
            } finally {
                handoffFinished.countDown()
            }
        }

        callbackThread.start()
        try {
            assertTrue(repository.startEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            handoffThread.start()
            assertTrue(
                "handoff crossed an in-flight tracker callback",
                !handoffFinished.await(100L, TimeUnit.MILLISECONDS)
            )

            repository.releaseStart()
            assertTrue(callbackFinished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(handoffFinished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            callbackFailure.get()?.let { throw AssertionError("tracker callback failed", it) }
            handoffFailure.get()?.let { throw AssertionError("worker handoff failed", it) }

            sendWindowEvent(tracker, OTHER_PACKAGE)
            assertTrue("tracker mutated sessions after handoff", repository.startCount.get() == 1L)
        } finally {
            repository.releaseStart()
            callbackThread.join(WAIT_TIMEOUT_MS)
            handoffThread.join(WAIT_TIMEOUT_MS)
            tracker.onDestroy()
        }
    }

    @Test
    fun foregroundCallbackReturnsBeforeDelayedPersistenceCompletes() {
        val timeline = CopyOnWriteArrayList<String>()
        val repository = DelayedReadRepository(timeline)
        val service = recordingService()
        val evaluationObserved = CountDownLatch(1)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithAllowance(allowedMinutes = 0L),
            observer = {
                timeline += "evaluation"
                evaluationObserved.countDown()
            }
        )
        val callbackReturned = CountDownLatch(1)
        val callbackStartedAtMs = AtomicLong(0L)
        val callbackReturnedAtMs = AtomicLong(0L)
        val callbackFailure = AtomicReference<Throwable?>(null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val callbackThread = Thread {
            callbackStartedAtMs.set(SystemClock.elapsedRealtime())
            val event = windowEvent(TARGET_PACKAGE)
            try {
                instrumentation.runOnMainSync {
                    blocker.doAppRuleCheck(event)
                }
                timeline += "callback-return"
            } catch (error: Throwable) {
                callbackFailure.set(error)
            } finally {
                event.recycle()
                callbackReturnedAtMs.set(SystemClock.elapsedRealtime())
                callbackReturned.countDown()
            }
        }

        callbackThread.start()
        try {
            assertTrue(
                "the evaluator must reach the delayed persistence seam",
                repository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            val returnedWhilePersistenceBlocked = callbackReturned.await(
                WAIT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            val callbackObservation = CallbackObservation(
                returnedWhilePersistenceBlocked = returnedWhilePersistenceBlocked,
                elapsedMs = if (returnedWhilePersistenceBlocked) {
                    callbackReturnedAtMs.get() - callbackStartedAtMs.get()
                } else {
                    SystemClock.elapsedRealtime() - callbackStartedAtMs.get()
                },
                persistenceReleased = repository.released.get()
            )
            val failure = callbackFailure.get()
            if (failure != null) throw AssertionError("callback failed", failure)
            if (!callbackObservation.returnedWhilePersistenceBlocked ||
                callbackObservation.persistenceReleased
            ) {
                throw AssertionError(
                    "callback waited for persistence instead of returning while the gate was held: " +
                        callbackObservation
                )
            }
            repository.release()
            assertTrue(
                "evaluation did not complete after delayed persistence was released",
                evaluationObserved.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTraceOrder(
                timeline,
                "callback-return",
                "persistence-read-complete",
                "evaluation"
            )
            assertTrue("persistence read never started: $timeline", "persistence-read-start" in timeline)
        } finally {
            repository.release()
            callbackThread.join(WAIT_TIMEOUT_MS)
            if (callbackThread.isAlive) callbackThread.interrupt()
            blocker.onDestroy()
        }
    }

    @Test
    fun evaluatorRunsOnlyAfterVisibleSessionFlushIsCommitted() {
        val nowMs = System.currentTimeMillis()
        val timeline = CopyOnWriteArrayList<String>()
        val repository = DelayedFlushRepository(timeline)
        val service = recordingService()
        val observations = CopyOnWriteArrayList<DecisionObservation>()
        val firstEvaluationObserved = CountDownLatch(1)
        val postCommitEvaluations = CountDownLatch(2)
        val observingPostCommitPath = AtomicBoolean(false)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithAllowance(allowedMinutes = 1L),
            observer = { evaluation ->
                observations += DecisionObservation(
                    evaluation = evaluation,
                    persistedSessions = repository.lastRead,
                    commitCompletedAtObservation = repository.commitCompleted.get()
                )
                timeline += "evaluation-${observations.size}"
                if (observingPostCommitPath.get()) {
                    postCommitEvaluations.countDown()
                } else {
                    firstEvaluationObserved.countDown()
                }
            }
        )

        try {
            // Establish the target through the production worker handoff, then leave a minute in
            // the durable row for the next worker request to commit before its evaluator read.
            sendWindowEvent(blocker, TARGET_PACKAGE)
            assertTrue(
                "the initial target evaluation must complete",
                firstEvaluationObserved.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            repository.rewindTargetSession(nowMs - SESSION_DURATION_MS)
            observations.clear()
            timeline.clear()
            observingPostCommitPath.set(true)

            sendWindowEvent(blocker, OTHER_PACKAGE) {
                timeline += "callback-return-other"
            }
            assertTrue(
                "the foreground transition must enter the delayed flush",
                repository.commitStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )

            // A rapid switch back queues behind the same commit. Both callbacks return while the
            // single worker keeps persistence and evaluation ordered.
            sendWindowEvent(blocker, TARGET_PACKAGE) {
                timeline += "callback-return-target"
            }

            repository.releaseCommit()
            assertTrue(
                "both queued evaluations must complete after the commit",
                postCommitEvaluations.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            val committedObservation = observations.lastOrNull()
            val failures = mutableListOf<String>()
            if (!repository.commitCompleted.get()) {
                failures += "flush commit was not recorded"
            }
            if (observations.any { !it.commitCompletedAtObservation }) {
                failures += "queued evaluator ran before flush commit: $observations"
            }
            val commitCompletedIndex = timeline.indexOf("persistence-commit-complete-1")
            val callbackReturnIndexes = listOf(
                timeline.indexOf("callback-return-other"),
                timeline.indexOf("callback-return-target")
            )
            if (commitCompletedIndex < 0 || callbackReturnIndexes.any { it < 0 }) {
                failures += "ordering trace is incomplete: $timeline"
            } else if (callbackReturnIndexes.any { it >= commitCompletedIndex }) {
                failures += "callback waited for flush completion: $timeline"
            }
            if ("evaluation-1" !in timeline || "evaluation-2" !in timeline) {
                failures += "ordering trace is missing one of the queued evaluations: $timeline"
            } else {
                val firstCommitComplete = timeline.indexOf("persistence-commit-complete-1")
                val secondCommitComplete = timeline.indexOf("persistence-commit-complete-2")
                val firstEvaluation = timeline.indexOf("evaluation-1")
                val secondEvaluation = timeline.indexOf("evaluation-2")
                if (firstCommitComplete < 0 || secondCommitComplete < 0 ||
                    firstEvaluation <= firstCommitComplete || secondEvaluation <= secondCommitComplete
                ) {
                    failures += "evaluation was not after its flush completion: $timeline"
                }
            }
            if (committedObservation == null || committedObservation.evaluation.isAllowed) {
                failures += "post-commit evaluator did not deny the consumed target session"
            }
            val committedSession = committedObservation?.persistedSessions?.singleOrNull()
            if (committedSession != null && committedSession.endedAtMs == committedSession.startedAtMs) {
                failures += "post-commit evaluator did not observe the committed session end"
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "flush-before-decision RED contract failures:\n" +
                        failures.joinToString("\n") { "- $it" } +
                        "\nobservations=$observations\nordering=$timeline"
                )
            }
        } finally {
            repository.releaseCommit()
            blocker.onDestroy()
        }
    }

    private fun configureBlocker(
        repository: CurrentUseDaySessionRepository,
        service: RecordingService,
        snapshot: AppRuleSnapshot,
        observer: ((AppRulesEvaluation) -> Unit)? = null
    ): AppRuleBlocker = AppRuleBlocker().apply {
        screenInteractiveProvider = { true }
        keyguardLockedProvider = { false }
        evaluationResultObserver = observer
        setField(this, "service", service)
        setField(this, "sessionRepository", repository)
        setField(
            this,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(this, "enforcement", AppRuleEnforcement(repository))
        setField(this, "setupReady", true)
        setField(this, "launchablePackages", setOf(TARGET_PACKAGE, OTHER_PACKAGE))
        val coordinator = getField(this, "snapshot") as AppRuleSnapshotCoordinator
        check(coordinator.accept(snapshot)) { "test snapshot must be valid" }
    }

    private fun configureTracker(
        repository: CurrentUseDaySessionRepository,
        service: RecordingService
    ): AppUsageTracker = AppUsageTracker().apply {
        setField(this, "service", service)
        setField(this, "crashLogger", CrashLogger(service))
        setField(this, "sessionRepository", repository)
        setField(this, "ownPackage", service.packageName)
        setField(
            this,
            "trackingDecision",
            AppUsageTrackingPolicy.decide(
                statisticsTrackingEnabled = false,
                hasActiveTimeBasedRules = true
            )
        )
        setField(this, "screenOn", true)
        setField(this, "destroying", false)
    }

    private fun recordingService(): RecordingService = RecordingService().also {
        it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        it.lastBackPressTimeStamp = 0L
    }

    private fun sendWindowEvent(blocker: AppRuleBlocker, packageName: String) {
        sendWindowEvent(blocker, packageName) {}
    }

    private fun sendWindowEvent(
        blocker: AppRuleBlocker,
        packageName: String,
        afterCallback: () -> Unit
    ) {
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(packageName),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = packageName)
        }
        withWindowEvent(packageName) {
            event ->
            blocker.doAppRuleCheck(event)
            afterCallback()
        }
    }

    private fun sendWindowEvent(tracker: AppUsageTracker, packageName: String) {
        withWindowEvent(packageName) { event -> tracker.onEvent(event) }
    }

    private inline fun withWindowEvent(
        packageName: String,
        block: (AccessibilityEvent) -> Unit
    ) {
        val event = windowEvent(packageName)
        try {
            block(event)
        } finally {
            event.recycle()
        }
    }

    private fun windowEvent(packageName: String): AccessibilityEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            this.packageName = packageName
        }

    private fun snapshotWithAllowance(allowedMinutes: Long): AppRuleSnapshot {
        val group = AppRuleAppGroup(
            id = TARGET_GROUP_ID,
            name = "Target",
            selectedPackages = listOf(TARGET_PACKAGE)
        )
        return AppRuleSnapshot(
            appGroups = listOf(group),
            appRules = listOf(
                AppRule(
                    id = TARGET_RULE_ID,
                    name = "Target rule",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(TARGET_GROUP_ID),
                    allowedMinutes = allowedMinutes
                )
            )
        )
    }

    private data class CallbackObservation(
        val returnedWhilePersistenceBlocked: Boolean,
        val elapsedMs: Long,
        val persistenceReleased: Boolean
    )

    private data class DecisionObservation(
        val evaluation: AppRulesEvaluation,
        val persistedSessions: List<ForegroundSession>?,
        val commitCompletedAtObservation: Boolean
    )

    private class RecordingService : BaseBlockingService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }

        override fun getSystemService(name: String): Any? = null

        override fun getWindows(): MutableList<AccessibilityWindowInfo> =
            error("the tracker window provider is intentionally unavailable")
    }

    private class DelayedReadRepository(
        private val timeline: MutableList<String>
    ) : CurrentUseDaySessionRepository {
        val readStarted = CountDownLatch(1)
        val released = AtomicBoolean(false)
        private val releaseRead = CountDownLatch(1)

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            timeline += "persistence-read-start"
            readStarted.countDown()
            releaseRead.await()
            timeline += "persistence-read-complete"
            return emptyList()
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun release() {
            released.set(true)
            releaseRead.countDown()
        }
    }

    private class DelayedFlushRepository(
        private val timeline: MutableList<String>
    ) : CurrentUseDaySessionRepository {
        val commitStarted = CountDownLatch(1)
        val commitCompleted = AtomicBoolean(false)
        val readHistory = CopyOnWriteArrayList<List<ForegroundSession>>()
        private val releaseCommit = CountDownLatch(1)
        @Volatile private var persistedSessions: List<ForegroundSession> = emptyList()

        val lastRead: List<ForegroundSession>?
            get() = readHistory.lastOrNull()

        override suspend fun startSession(
            useDayId: String,
            packageName: String,
            startedAtMs: Long
        ): Long {
            val id = if (persistedSessions.isEmpty()) 1L else 2L
            persistedSessions += ForegroundSession(
                id = id,
                useDayId = useDayId,
                packageName = packageName,
                startedAtMs = startedAtMs,
                // The fake stores only the last durable checkpoint until the delayed flush runs.
                endedAtMs = startedAtMs
            )
            return id
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun commitSessionCheckpoint(
            id: Long,
            endedAtMs: Long,
            usage: List<ForegroundUsageCheckpoint>
        ): Boolean {
            val commitNumber = ++commitCount
            timeline += "persistence-commit-start-$commitNumber"
            commitStarted.countDown()
            releaseCommit.await()
            persistedSessions = persistedSessions.map { session ->
                if (session.id == id) session.copy(endedAtMs = endedAtMs) else session
            }
            commitCompleted.set(true)
            timeline += "persistence-commit-complete-$commitNumber"
            return true
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            val result = persistedSessions.filter { it.useDayId == useDayId }
            readHistory += result
            return result
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun rewindTargetSession(startedAtMs: Long) {
            persistedSessions = persistedSessions.map { session ->
                if (session.packageName == TARGET_PACKAGE) {
                    session.copy(startedAtMs = startedAtMs, endedAtMs = startedAtMs)
                } else {
                    session
                }
            }
        }

        fun releaseCommit() {
            releaseCommit.countDown()
        }

        private var commitCount = 0
    }

    private class BlockingTrackerRepository : CurrentUseDaySessionRepository {
        val startEntered = CountDownLatch(1)
        val startCount = AtomicLong(0L)
        private val startRelease = CountDownLatch(1)

        override suspend fun startSession(
            useDayId: String,
            packageName: String,
            startedAtMs: Long
        ): Long {
            startEntered.countDown()
            startRelease.await()
            return startCount.incrementAndGet()
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun releaseStart() = startRelease.countDown()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun assertTraceOrder(timeline: List<String>, vararg events: String) {
        val indexes = events.map(timeline::indexOf)
        assertTrue("ordering trace is incomplete: $timeline", indexes.all { it >= 0 })
        assertTrue(
            "ordering trace was ${timeline.joinToString()} instead of ${events.toList()}",
            indexes.zipWithNext().all { (first, second) -> first < second }
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.reader"
        const val OTHER_PACKAGE = "com.example.other"
        const val TARGET_GROUP_ID = "target-group"
        const val TARGET_RULE_ID = "target-rule"
        const val WAIT_TIMEOUT_MS = 2_000L
        const val SESSION_DURATION_MS = 2 * 60_000L
    }
}
