package neth.iecal.curbox.blockers

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.ForegroundUsageCheckpoint
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Test
import org.junit.runner.RunWith
import java.util.AbstractList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Phase 0 RED contracts for destroy drain and fault continuation. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerDestroyFaultRedTest {
    @Test
    fun ticket15MeasuresBoundedDestroyAndReconnectsOnlyTheLatestGeneration() {
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val repository = DestroyRaceRepository()
        val durableSessionId = runBlocking {
            repository.startSession(
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = nowMs - SESSION_DURATION_MS
            )
        }
        runBlocking {
            repository.commitSessionCheckpoint(
                id = durableSessionId,
                endedAtMs = nowMs,
                usage = emptyList<ForegroundUsageCheckpoint>()
            )
        }

        val service = recordingService()
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val notificationPostings = CopyOnWriteArrayList<LiveRuleNotificationModel>()
        val notificationPostEntered = CountDownLatch(1)
        val notificationPostRelease = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val blockWindowProvider = AtomicReference(false)
        val callbackThread = AtomicReference<Thread?>(null)
        val decisionThread = AtomicReference<Thread?>(null)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluation -> evaluations += evaluation }
        ).apply {
            notificationPostObserver = { model ->
                notificationPostEntered.countDown()
                notificationPostRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                notificationPostings += model
            }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                callbackThread.set(Thread(runnable, "ticket15-visible-callback"))
                true
            }
            visibleApplicationCheckRemoveCallbacks = {}
            applicationWindowSnapshotProvider = {
                if (blockWindowProvider.get()) {
                    callbackEntered.countDown()
                    callbackRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false,
                    applicationWindowCount = 1
                )
            }
        }
        val refreshMutex = getField(blocker, "refreshMutex") as Mutex

        // Hold the publication mutex so a refresh is definitely in-flight when invalidation
        // begins. The worker decision and notification use separate deterministic repository gates.
        runBlocking { refreshMutex.lock() }
        val refreshReceiver = getField(blocker, "refreshReceiver") as BroadcastReceiver
        refreshReceiver.onReceive(
            service,
            Intent(AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES)
        )
        val refreshStarted = awaitAtomicPositive(
            getField(blocker, "inFlightRefreshes") as AtomicInteger
        )

        blocker.updateLiveNotification(TARGET_PACKAGE)
        check(repository.notificationReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "notification did not reach its deterministic read gate"
        }

        val decision = Thread {
            sendWindowEvent(blocker, TARGET_PACKAGE)
        }
        decisionThread.set(decision)
        decision.start()
        check(repository.secondReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "decision did not reach its deterministic read gate"
        }

        blockWindowProvider.set(true)
        invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
        val callback = checkNotNull(callbackThread.get()) {
            "visible callback was not captured"
        }
        callback.start()
        check(callbackEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "visible callback did not reach its deterministic provider gate"
        }
        check(refreshStarted) { "refresh did not enter the tracked lifecycle work set" }

        val destroyStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val measurement = blocker.onDestroyForMeasurement(totalDrainBudgetMs = 150L)
        val destroyElapsedMs =
            android.os.SystemClock.elapsedRealtime() - destroyStartedAtMs

        check(destroyElapsedMs <= 1_000L) {
            "candidate destroy measurement was not bounded: ${destroyElapsedMs}ms"
        }
        check(measurement.timedOut) { "blocked candidate drain unexpectedly completed" }
        check(measurement.workAtInvalidation.refreshes > 0) {
            "refresh was not included in the destroy measurement"
        }
        check(measurement.workAtInvalidation.notifications > 0) {
            "notification was not included in the destroy measurement"
        }
        check(measurement.workAtInvalidation.callbacks > 0) {
            "handler callback was not included in the destroy measurement"
        }
        check(measurement.workerResult?.timedOut == true) {
            "final in-flight decision did not report the absolute deadline timeout"
        }
        check(measurement.workerResult?.durableRecoveryRequired == true) {
            "timed-out durable decision was not marked for recovery"
        }

        // Release every barrier only after destroy. Generation invalidation must suppress the
        // evaluator observer, warning, notification publication, and the callback's decision.
        repository.releaseDecisionRead()
        repository.releaseNotificationRead()
        callbackRelease.countDown()
        refreshMutex.unlock()
        decisionThread.get()?.join(WAIT_TIMEOUT_MS)
        callback.join(WAIT_TIMEOUT_MS)
        check(
            awaitAtomicZero(getField(blocker, "inFlightRefreshes") as AtomicInteger) &&
                awaitAtomicZero(getField(blocker, "inFlightNotifications") as AtomicInteger) &&
                awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)
        ) {
            "tracked async work did not finish after its post-destroy barriers were released"
        }

        check(evaluations.isEmpty()) {
            "evaluator side effect occurred after destroy: ${evaluations.size}"
        }
        check(service.startedActivities.isEmpty()) {
            "warning activity occurred after destroy"
        }
        check(notificationPostings.isEmpty()) {
            "notification side effect occurred after destroy: ${notificationPostings.size}"
        }

        val recoveryEvaluated = CountDownLatch(1)
        val recoveryEvaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val recoveryService = recordingService()
        val recoveryBlocker = configureBlocker(
            repository = repository,
            service = recoveryService,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluation ->
                recoveryEvaluations += evaluation
                recoveryEvaluated.countDown()
            }
        )
        try {
            sendWindowEvent(recoveryBlocker, TARGET_PACKAGE)
            check(recoveryEvaluated.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "reconnect did not publish the new generation evaluation"
            }
            check(recoveryEvaluations.single().isAllowed.not()) {
                "reconnect did not evaluate the durable session as denied"
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            check(repository.successfulReadCount.get() >= 3) {
                "reconnect did not perform a fresh durable read"
            }
            check(awaitNonEmpty(recoveryService.startedActivities)) {
                "latest reconnect generation did not publish its warning"
            }
        } finally {
            recoveryBlocker.onDestroy()
        }
    }

    @Test
    fun inFlightDecisionDestroyHasNoPostDestroySideEffectsAndReconnectRecoversDurableState() {
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val repository = DestroyRaceRepository()
        val durableSessionId = runBlocking {
            repository.startSession(
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = nowMs - SESSION_DURATION_MS
            )
        }
        runBlocking {
            repository.commitSessionCheckpoint(
                id = durableSessionId,
                endedAtMs = nowMs,
                usage = emptyList<ForegroundUsageCheckpoint>()
            )
        }
        check(repository.durableWriteCount.get() == 2) {
            "the race must start from a session written through the durable fake repository"
        }

        val scheduler = RecordingCallbacks()
        val visibleCallbacks = RecordingCallbacks()
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val notificationPostings = CopyOnWriteArrayList<LiveRuleNotificationModel>()
        val notificationPostEntered = CountDownLatch(1)
        val notificationPostRelease = CountDownLatch(1)
        val notificationPostCompleted = CountDownLatch(1)
        val evaluatorEntered = CountDownLatch(1)
        val evaluatorRelease = CountDownLatch(1)
        val evaluatorCompleted = CountDownLatch(1)
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluation ->
                evaluatorEntered.countDown()
                evaluatorRelease.await()
                evaluations += evaluation
                evaluatorCompleted.countDown()
            }
        ).apply {
            notificationPostObserver = { model ->
                notificationPostEntered.countDown()
                notificationPostRelease.await()
                notificationPostings += model
                notificationPostCompleted.countDown()
            }
            recheckPostDelayed = scheduler::post
            recheckRemoveCallback = scheduler::remove
            visibleApplicationCheckPostDelayed = visibleCallbacks::post
            visibleApplicationCheckRemoveCallbacks = visibleCallbacks::removeAll
        }

        val checkFailure = AtomicReference<Throwable?>(null)
        val checkReturned = CountDownLatch(1)
        val eventThread = Thread {
            try {
                sendWindowEvent(blocker, TARGET_PACKAGE)
            } catch (error: Throwable) {
                checkFailure.set(error)
            } finally {
                checkReturned.countDown()
            }
        }

        val drainDeadlineMs =
            android.os.SystemClock.elapsedRealtime() + TOTAL_DRAIN_BUDGET_MS

        try {
            // Start a notification read before the foreground decision so both async publication
            // and the decision path are still in flight when destroy begins.
            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(
                awaitBeforeDeadline(repository.notificationReadStarted, drainDeadlineMs)
            ) { "notification worker did not reach the persistence gate" }

            invokePrivate(blocker, "scheduleRecheck", TARGET_PACKAGE, 10_000L, 20_000L, 0L)
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            check(scheduler.pendingCount == 1) { "the handler recheck was not scheduled" }
            check(visibleCallbacks.pendingCount == 1) {
                "the visible handler callback was not captured"
            }

            eventThread.start()
            check(
                awaitBeforeDeadline(repository.secondReadStarted, drainDeadlineMs)
            ) { "the in-flight decision did not reach the persistence gate" }
            repository.releaseDecisionRead()
            check(awaitBeforeDeadline(evaluatorEntered, drainDeadlineMs)) {
                "the in-flight evaluator did not reach its distinct gate"
            }

            val destroyReturned = CountDownLatch(1)
            val destroyStartedAtMs = android.os.SystemClock.elapsedRealtime()
            val destroyThread = Thread {
                try {
                    blocker.onDestroy()
                } finally {
                    destroyReturned.countDown()
                }
            }
            destroyThread.start()
            val destroyCompletedWithinBudget =
                awaitBeforeDeadline(destroyReturned, drainDeadlineMs)
            val destroyElapsedMs =
                android.os.SystemClock.elapsedRealtime() - destroyStartedAtMs

            val failures = mutableListOf<String>()
            if (!destroyCompletedWithinBudget) {
                failures += "destroy did not return within the bounded drain budget"
            }
            if (destroyElapsedMs > TOTAL_DRAIN_BUDGET_MS) {
                failures += "destroy exceeded the bounded drain budget: ${destroyElapsedMs}ms"
            }
            if (scheduler.pendingCount != 0) {
                failures += "destroy left ${scheduler.pendingCount} handler callbacks queued"
            }
            if (scheduler.removedCount != 1) {
                failures += "destroy did not remove the queued scheduler callback"
            }
            if (scheduler.deliveredCount != 0) {
                failures += "destroy delivered a scheduler callback"
            }
            if (visibleCallbacks.pendingCount != 0) {
                failures += "destroy left ${visibleCallbacks.pendingCount} handler callbacks queued"
            }
            if (visibleCallbacks.removedCount != 1) {
                failures += "destroy did not remove the queued handler callback"
            }
            // Probe both virtual queues after destroy. A callback that survived removal would be
            // delivered here and counted, so this verifies delivery rather than relying on a
            // post-destroy no-op guard inside the callback.
            scheduler.deliverPending()
            visibleCallbacks.deliverPending()
            if (visibleCallbacks.deliveredCount != 0) {
                failures += "destroy delivered a handler callback"
            }
            if (scheduler.deliveredCount != 0) {
                failures += "destroy delivered a scheduler callback"
            }

            // Release the evaluator only after teardown. The observer records the external
            // evaluator publication after its distinct gate, so it cannot be confused with the
            // persistence read that got the decision in flight.
            evaluatorRelease.countDown()
            if (!awaitBeforeDeadline(evaluatorCompleted, drainDeadlineMs)) {
                failures += "the in-flight evaluator did not complete after its gate was released"
            }
            if (!awaitBeforeDeadline(checkReturned, drainDeadlineMs)) {
                failures += "the in-flight decision did not drain after the evaluator was released"
            }
            joinBeforeDeadline(eventThread, drainDeadlineMs)
            if (eventThread.isAlive) failures += "the in-flight decision thread remained alive"

            // Release the notification persistence read only after teardown. Any notification
            // post observed from this worker is therefore a separate post-destroy side effect.
            repository.releaseNotificationRead()
            val notificationPostedAfterDestroy =
                awaitBeforeDeadline(notificationPostEntered, drainDeadlineMs)
            notificationPostRelease.countDown()
            if (notificationPostedAfterDestroy &&
                !awaitBeforeDeadline(notificationPostCompleted, drainDeadlineMs)
            ) {
                failures += "the post-destroy notification observer did not complete"
            }

            if (evaluations.isNotEmpty()) {
                failures += "evaluator publication occurred after destroy: ${evaluations.size}"
            }
            if (service.startedActivities.isNotEmpty()) {
                failures += "warning activity launched after destroy"
            }
            if (notificationPostings.isNotEmpty()) {
                failures += "notification was posted after destroy: ${notificationPostings.size}"
            }
            checkFailure.get()?.let { failures += "in-flight decision escaped: $it" }

            val recoveryEvaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
            val recoveryService = recordingService()
            val recoveryBlocker = configureBlocker(
                repository = repository,
                service = recoveryService,
                snapshot = snapshotWithSpentTargetAllowance(),
                observer = { evaluation -> recoveryEvaluations += evaluation }
            )
            try {
                sendWindowEvent(recoveryBlocker, TARGET_PACKAGE)
                if (recoveryEvaluations.singleOrNull()?.isAllowed != false) {
                    failures += "reconnect did not evaluate the durable session as denied"
                }
                if (recoveryService.startedActivities.isEmpty()) {
                    failures += "reconnect did not produce the durable denial warning"
                }
                if (repository.successfulReadCount.get() == 0) {
                    failures += "reconnect did not read the durable session state"
                }
                if (repository.readCount.get() < 3) {
                    failures += "reconnect did not perform a new durable read"
                }
            } finally {
                recoveryBlocker.onDestroy()
            }

            if (checkFailure.get() is CancellationException) {
                failures += "ordinary destroy race unexpectedly propagated cancellation"
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "destroy drain and recovery RED contract failures:\n" +
                        failures.joinToString(separator = "\n") { "- $it" }
                )
            }
        } finally {
            evaluatorRelease.countDown()
            notificationPostRelease.countDown()
            repository.releaseDecisionRead()
            repository.releaseNotificationRead()
            if (eventThread.isAlive) {
                eventThread.interrupt()
                joinBeforeDeadline(eventThread, drainDeadlineMs)
            }
            // The first blocker normally destroys inside the race. This is idempotent for the
            // test fixture and ensures no coroutine survives a setup failure before that point.
            blocker.onDestroy()
        }
    }

    @Test
    fun ordinaryPersistenceEvaluatorSchedulerCallbackAndWorkerFaultsDoNotLoseNextEvent() {
        val failures = mutableListOf<String>()
        failures += verifyFaultContinuation(FaultMode.PERSISTENCE_FAILURE)
        failures += verifyFaultContinuation(FaultMode.EVALUATOR_FAILURE)
        failures += verifyFaultContinuation(FaultMode.SCHEDULER_FAILURE)
        failures += verifyCallbackPostContinuation()
        failures += verifyNotificationWorkerContinuation()

        val actualFailures = failures.filter(String::isNotEmpty)
        if (actualFailures.isNotEmpty()) {
            throw AssertionError(
                "fault continuation RED contract failures:\n" +
                    actualFailures.joinToString(separator = "\n") { "- $it" }
            )
        }
    }

    @Test
    fun evaluatorCancellationExceptionPropagatesWhileHealthyNextEventRemainsPossible() {
        val repository = FaultRepository(FaultMode.CANCELLATION)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        )

        try {
            val thrown = try {
                sendWindowEvent(blocker, TARGET_PACKAGE)
                null
            } catch (error: Throwable) {
                error
            }
            check(repository.faultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "evaluator cancellation fault did not execute"
            }
            check(thrown is CancellationException) {
                "CancellationException was swallowed or replaced: $thrown"
            }
            check(evaluations.isEmpty()) {
                "cancelled evaluation unexpectedly published a result"
            }

            repository.mode = FaultMode.HEALTHY
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            val failure = healthyDenialFailure(
                label = "evaluator cancellation",
                evaluations = evaluations,
                service = service,
                evaluationCountBefore = beforeHealthyEvent
            )
            check(failure.isEmpty()) {
                failure
            }
        } finally {
            blocker.onDestroy()
        }
    }

    @Test
    fun workerCancellationExceptionIsContainedToWorkerAndNextEventRemainsPossible() {
        val repository = FaultRepository(FaultMode.WORKER_CANCELLATION)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        )

        try {
            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(repository.faultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification worker cancellation fault did not execute"
            }
            repository.mode = FaultMode.HEALTHY
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            val failure = healthyDenialFailure(
                label = "worker cancellation",
                evaluations = evaluations,
                service = service,
                evaluationCountBefore = beforeHealthyEvent
            )
            check(failure.isEmpty()) {
                failure
            }
        } finally {
            blocker.onDestroy()
        }
    }

    @Test
    fun handlerCancellationExceptionIsContainedAndNextEventRemainsPossible() {
        val repository = FaultRepository(FaultMode.HEALTHY)
        val callbackFaultExecuted = CountDownLatch(1)
        val capturedCallback = AtomicReference<Runnable?>(null)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        )

        try {
            blocker.applicationWindowSnapshotProvider = {
                callbackFaultExecuted.countDown()
                throw CancellationException("injected handler callback cancellation")
            }
            blocker.visibleApplicationCheckPostDelayed = { callback, _ ->
                capturedCallback.set(callback)
                true
            }
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            val callback = capturedCallback.get()
            check(callback != null) {
                "handler callback was not captured"
            }
            val escaped = try {
                callback.run()
                null
            } catch (error: Throwable) {
                error
            }
            check(callbackFaultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "handler cancellation fault did not execute"
            }
            check(escaped == null) {
                "handler CancellationException escaped its callback boundary: $escaped"
            }

            blocker.applicationWindowSnapshotProvider = null
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            val failure = healthyDenialFailure(
                label = "handler cancellation",
                evaluations = evaluations,
                service = service,
                evaluationCountBefore = beforeHealthyEvent
            )
            check(failure.isEmpty()) {
                failure
            }
        } finally {
            blocker.onDestroy()
        }
    }

    private fun verifyFaultContinuation(mode: FaultMode): String {
        val repository = FaultRepository(mode)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        )

        return try {
            if (mode == FaultMode.SCHEDULER_FAILURE) {
                val schedulerFaultExecuted = CountDownLatch(1)
                blocker.recheckPostDelayed = { _, _ ->
                    schedulerFaultExecuted.countDown()
                    throw IllegalStateException("injected scheduler failure")
                }
                invokePrivate(blocker, "scheduleRecheck", TARGET_PACKAGE, 1_000L, 20_000L, 0L)
                if (!schedulerFaultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return "${mode.name} scheduler fault did not execute"
                }
            }

            sendWindowEvent(blocker, TARGET_PACKAGE)
            if (mode != FaultMode.SCHEDULER_FAILURE &&
                !repository.faultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) {
                return "${mode.name} fault did not execute"
            }
            val firstEventOutcomeFailed = when (mode) {
                FaultMode.PERSISTENCE_FAILURE,
                FaultMode.EVALUATOR_FAILURE -> evaluations.isNotEmpty() ||
                    service.startedActivities.isNotEmpty()
                FaultMode.SCHEDULER_FAILURE -> evaluations.singleOrNull()?.isAllowed != false ||
                    service.startedActivities.isEmpty()
                else -> false
            }
            if (firstEventOutcomeFailed) {
                return "${mode.name} did not preserve the expected first-event outcome"
            }

            repository.mode = FaultMode.HEALTHY
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            healthyDenialFailure(
                label = mode.name,
                evaluations = evaluations,
                service = service,
                evaluationCountBefore = beforeHealthyEvent
            )
        } catch (error: Throwable) {
            "${mode.name} escaped the service boundary: $error"
        } finally {
            blocker.onDestroy()
        }
    }

    private fun verifyCallbackPostContinuation(): String {
        val repository = FaultRepository(FaultMode.HEALTHY)
        val callbackFaultExecuted = CountDownLatch(1)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        )

        return try {
            blocker.visibleApplicationCheckPostDelayed = { _, _ ->
                callbackFaultExecuted.countDown()
                throw IllegalStateException("injected handler callback post failure")
            }
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            if (!callbackFaultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "callback post fault did not execute"
            }
            blocker.visibleApplicationCheckPostDelayed = { _, _ -> true }
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            healthyDenialFailure(
                label = "callback post failure",
                evaluations = evaluations,
                service = service,
                evaluationCountBefore = beforeHealthyEvent
            )
        } catch (error: Throwable) {
            "callback post failure escaped the service boundary: $error"
        } finally {
            blocker.onDestroy()
        }
    }

    private fun verifyNotificationWorkerContinuation(): String {
        val repository = FaultRepository(FaultMode.WORKER_FAILURE)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        )

        return try {
            blocker.updateLiveNotification(TARGET_PACKAGE)
            if (!repository.faultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "notification worker fault did not execute"
            }
            repository.mode = FaultMode.HEALTHY
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            healthyDenialFailure(
                label = "notification worker failure",
                evaluations = evaluations,
                service = service,
                evaluationCountBefore = beforeHealthyEvent
            )
        } catch (error: Throwable) {
            "notification worker failure escaped the service boundary: $error"
        } finally {
            blocker.onDestroy()
        }
    }

    private fun healthyDenialFailure(
        label: String,
        evaluations: List<AppRulesEvaluation>,
        service: RecordingService,
        evaluationCountBefore: Int
    ): String {
        if (evaluations.size <= evaluationCountBefore) {
            return "$label prevented the next event from reaching the evaluator"
        }
        if (evaluations.last().isAllowed || service.startedActivities.isEmpty()) {
            return "$label next event did not produce the expected denial warning"
        }
        return ""
    }

    private fun configureBlocker(
        repository: CurrentUseDaySessionRepository,
        service: RecordingService,
        snapshot: AppRuleSnapshot,
        observer: (AppRulesEvaluation) -> Unit
    ): AppRuleBlocker = AppRuleBlocker().apply {
        screenInteractiveProvider = { true }
        keyguardLockedProvider = { false }
        evaluationResultObserver = observer
        setField(this, "service", service)
        setField(this, "sessionRepository", repository)
        setField(this, "usageResetRepository", RoomUsageResetRepository(AppDatabase.getInstance(service)))
        setField(this, "enforcement", AppRuleEnforcement(repository))
        setField(this, "setupReady", true)
        setField(this, "launchablePackages", setOf(TARGET_PACKAGE))
        val coordinator = getField(this, "snapshot") as AppRuleSnapshotCoordinator
        check(coordinator.accept(snapshot)) { "test snapshot must be valid" }
    }

    private fun snapshotWithGlobalDeny(): AppRuleSnapshot {
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
                    allowedMinutes = 0L
                )
            )
        )
    }

    private fun snapshotWithSpentTargetAllowance(): AppRuleSnapshot =
        snapshotWithGlobalDeny().copy(
            appRules = listOf(
                snapshotWithGlobalDeny().appRules.single().copy(allowedMinutes = 1L)
            )
        )

    private fun sendWindowEvent(blocker: AppRuleBlocker, packageName: String) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try {
            event.packageName = packageName
            blocker.doAppRuleCheck(event)
        } finally {
            event.recycle()
        }
    }

    private fun recordingService(): RecordingService = RecordingService().also {
        it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        it.lastBackPressTimeStamp = 0L
    }

    private class RecordingService : BaseBlockingService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }
    }

    private class DestroyRaceRepository : CurrentUseDaySessionRepository {
        val notificationReadStarted = CountDownLatch(1)
        val secondReadStarted = CountDownLatch(1)
        val durableWriteCount = AtomicInteger(0)
        val successfulReadCount = AtomicInteger(0)
        val readCount = AtomicInteger(0)
        private val nextId = AtomicLong(1L)
        private val durableSessions = CopyOnWriteArrayList<ForegroundSession>()
        private val notificationReadGate = CountDownLatch(1)
        private val decisionReadGate = CountDownLatch(1)

        override suspend fun startSession(
            useDayId: String,
            packageName: String,
            startedAtMs: Long
        ): Long {
            val id = nextId.getAndIncrement()
            durableSessions += ForegroundSession(
                id = id,
                useDayId = useDayId,
                packageName = packageName,
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs
            )
            durableWriteCount.incrementAndGet()
            return id
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            updateSessionEnd(id, endedAtMs)
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            synchronized(durableSessions) {
                val index = durableSessions.indexOfFirst { it.id == id }
                if (index >= 0) {
                    durableSessions[index] = durableSessions[index].copy(endedAtMs = endedAtMs)
                }
            }
            durableWriteCount.incrementAndGet()
        }

        override suspend fun commitSessionCheckpoint(
            id: Long,
            endedAtMs: Long,
            usage: List<ForegroundUsageCheckpoint>
        ): Boolean {
            updateSessionEnd(id, endedAtMs)
            return true
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            when (readCount.incrementAndGet()) {
                1 -> {
                    notificationReadStarted.countDown()
                    notificationReadGate.await()
                }
                2 -> {
                    secondReadStarted.countDown()
                    decisionReadGate.await()
                }
            }
            successfulReadCount.incrementAndGet()
            return durableSessions.filter { it.useDayId == useDayId }.toList()
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun releaseNotificationRead() {
            notificationReadGate.countDown()
        }

        fun releaseDecisionRead() {
            decisionReadGate.countDown()
        }
    }

    private enum class FaultMode {
        HEALTHY,
        PERSISTENCE_FAILURE,
        EVALUATOR_FAILURE,
        SCHEDULER_FAILURE,
        WORKER_FAILURE,
        CANCELLATION,
        WORKER_CANCELLATION
    }

    private class FaultRepository(
        @Volatile var mode: FaultMode
    ) : CurrentUseDaySessionRepository {
        val faultExecuted = CountDownLatch(1)

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            return when (mode) {
                FaultMode.PERSISTENCE_FAILURE -> {
                    faultExecuted.countDown()
                    throw IllegalStateException("injected persistence failure")
                }
                FaultMode.EVALUATOR_FAILURE,
                FaultMode.WORKER_FAILURE -> ThrowingSessionList(faultExecuted::countDown)
                FaultMode.CANCELLATION -> {
                    faultExecuted.countDown()
                    throw CancellationException("injected evaluator cancellation")
                }
                FaultMode.WORKER_CANCELLATION -> {
                    faultExecuted.countDown()
                    throw CancellationException("injected worker cancellation")
                }
                FaultMode.HEALTHY,
                FaultMode.SCHEDULER_FAILURE -> emptyList()
            }
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private class ThrowingSessionList(
        private val faultExecuted: () -> Unit
    ) : AbstractList<ForegroundSession>() {
        override val size: Int get() = 1

        override fun get(index: Int): ForegroundSession {
            faultExecuted()
            error("the evaluator iterator is intentionally unavailable")
        }

        override fun iterator(): MutableIterator<ForegroundSession> {
            faultExecuted()
            error("the evaluator iterator is intentionally unavailable")
        }
    }

    private class RecordingCallbacks {
        private val pending = CopyOnWriteArrayList<Runnable>()
        private val removedCallbacks = AtomicInteger(0)
        private val deliveredCallbacks = AtomicInteger(0)

        val pendingCount: Int get() = pending.size
        val removedCount: Int get() = removedCallbacks.get()
        val deliveredCount: Int get() = deliveredCallbacks.get()

        fun post(runnable: Runnable, _delayMillis: Long): Boolean {
            pending += runnable
            return true
        }

        fun remove(runnable: Runnable) {
            if (pending.remove(runnable)) removedCallbacks.incrementAndGet()
        }

        fun removeAll() {
            removedCallbacks.addAndGet(pending.size)
            pending.clear()
        }

        fun deliverPending() {
            pending.toList().forEach { runnable ->
                if (pending.remove(runnable)) {
                    deliveredCallbacks.incrementAndGet()
                    runnable.run()
                }
            }
        }
    }

    private fun awaitBeforeDeadline(latch: CountDownLatch, deadlineMs: Long): Boolean {
        val remainingMs = (deadlineMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return latch.await(remainingMs, TimeUnit.MILLISECONDS)
    }

    private fun awaitAtomicPositive(counter: AtomicInteger): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (counter.get() > 0) return true
            Thread.yield()
        }
        return counter.get() > 0
    }

    private fun awaitAtomicZero(counter: AtomicInteger): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (counter.get() == 0) return true
            Thread.yield()
        }
        return counter.get() == 0
    }

    private fun awaitNonEmpty(values: List<*>): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (values.isNotEmpty()) return true
            Thread.yield()
        }
        return values.isNotEmpty()
    }

    private fun joinBeforeDeadline(thread: Thread, deadlineMs: Long) {
        val remainingMs = (deadlineMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remainingMs > 0L) thread.join(remainingMs)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun invokePrivate(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first {
            it.name == name && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.reader"
        const val TARGET_GROUP_ID = "target-group"
        const val TARGET_RULE_ID = "target-rule"
        const val WAIT_TIMEOUT_MS = 2_000L
        const val TOTAL_DRAIN_BUDGET_MS = 5_000L
        const val SESSION_DURATION_MS = 60_000L
    }
}
