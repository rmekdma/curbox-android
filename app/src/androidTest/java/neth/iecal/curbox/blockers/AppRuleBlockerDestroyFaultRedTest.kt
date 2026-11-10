package neth.iecal.curbox.blockers

import android.content.Context
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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
import neth.iecal.curbox.domain.apprules.AppRuleWakeScheduler
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AtomicConnectionScopedSourceOrderSequencer
import neth.iecal.curbox.domain.apprules.AndroidForegroundObservationSource
import neth.iecal.curbox.domain.apprules.AndroidAppRuleWakeScheduler
import neth.iecal.curbox.domain.apprules.AlarmPublisher
import neth.iecal.curbox.domain.apprules.ActiveRootFact
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFact
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFreshness
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.DisplayState
import neth.iecal.curbox.domain.apprules.ForegroundFacts
import neth.iecal.curbox.domain.apprules.ForegroundReadState
import neth.iecal.curbox.domain.apprules.ObservationTrigger
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.domain.apprules.SignalFact
import neth.iecal.curbox.domain.apprules.SerializedDecisionWorker
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.FakeWakeScheduler
import neth.iecal.curbox.domain.apprules.ForegroundUsageCheckpoint
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.testing.AccessibilityFrameworkTestObjects
import neth.iecal.curbox.trackers.AppUsageTracker
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Test
import org.junit.runner.RunWith
import java.util.AbstractList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Phase 0 RED contracts for destroy drain and fault continuation. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerDestroyFaultRedTest {
    @Test
    fun sameHostReconnectRejectsStaleQueuedCallbackAndProcessesNewEvent() {
        val service = recordingService()
        val originalSnapshot = runBlocking {
            service.dataStoreManager.settings.first().appRuleSnapshot
        }
        runBlocking {
            check(service.dataStoreManager.updateAppRuleSnapshot(snapshotWithGlobalDeny())) {
                "could not install the deterministic deny snapshot"
            }
        }

        val blocker = AppRuleBlocker()
        val postedCallbacks = CopyOnWriteArrayList<Runnable>()
        val applicationWindowReads = AtomicInteger(0)
        val evaluationCount = AtomicInteger(0)
        val evaluationAllowed = AtomicReference<Boolean>()
        val publishedNotificationCount = AtomicInteger(0)
        val reconnectStarted = AtomicBoolean(false)
        val reconnectNotificationPublished = CountDownLatch(1)
        val firstEvaluation = AtomicBoolean(true)
        val firstNotification = AtomicBoolean(true)
        val oldEvaluationEntered = CountDownLatch(1)
        val oldEvaluationCompleted = CountDownLatch(1)
        val oldEvaluationRelease = CountDownLatch(1)
        val oldNotificationEntered = CountDownLatch(1)
        val oldNotificationCompleted = CountDownLatch(1)
        val oldNotificationRelease = CountDownLatch(1)

        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        blocker.applicationWindowSnapshotProvider = {
            applicationWindowReads.incrementAndGet()
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(TARGET_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false,
                applicationWindowCount = 1
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
        }
        blocker.visibleApplicationCheckPostDelayed = { runnable, _ ->
            postedCallbacks += runnable
            true
        }
        // Keep the virtual callback list intact so a callback from the first setup can be
        // delivered deliberately after the second setup.
        blocker.visibleApplicationCheckRemoveCallbacks = {}
        blocker.evaluationResultObserver = { evaluation ->
            evaluationCount.incrementAndGet()
            evaluationAllowed.set(evaluation.isAllowed)
            if (firstEvaluation.compareAndSet(true, false)) {
                oldEvaluationEntered.countDown()
                try {
                    oldEvaluationRelease.await()
                } finally {
                    oldEvaluationCompleted.countDown()
                }
            }
        }
        blocker.notificationPostObserver = { _ ->
            if (firstNotification.compareAndSet(true, false)) {
                oldNotificationEntered.countDown()
                try {
                    oldNotificationRelease.await()
                } finally {
                    oldNotificationCompleted.countDown()
                }
            }
        }
        blocker.notificationPublicationObserver = {
            publishedNotificationCount.incrementAndGet()
            if (reconnectStarted.get()) reconnectNotificationPublished.countDown()
        }
        fun installDeterministicRuntime() {
            val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
            check(coordinator.accept(snapshotWithGlobalDeny())) {
                "could not install the real-host snapshot"
            }
            // Keep setup/teardown real while making the framework read deterministic on the
            // attached test service, which is not an actual AccessibilityService connection.
            setField(blocker, "foregroundObservationSource", null)
            setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE))
            val generation = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()
            invokePrivate(blocker, "submitRuntimePublication", generation)
        }

        try {
            blocker.setup(service)
            blocker.setupReceivers()
            installDeterministicRuntime()
            check(oldNotificationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "first real-host setup did not reach notification publication"
            }
            val oldNotificationJob = getField(blocker, "liveNotificationJob") as Job
            setField(blocker, "currentForegroundPackage", TARGET_PACKAGE)
            val firstCallback = checkNotNull(postedCallbacks.firstOrNull()) {
                "first real-host setup did not post a visible handler callback"
            }
            // Use the real accessibility event as the first-generation trigger; retain the
            // setup-posted callback so it can be delivered as stale work after reconnect.
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(oldEvaluationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "first real-host setup did not reach evaluator publication"
            }
            val oldWorker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            check(oldNotificationCompleted.count == 1L && !oldNotificationJob.isCompleted) {
                "old notification must still be in flight at stop"
            }
            check(oldEvaluationCompleted.count == 1L && oldWorker.drainSnapshot().hasWork) {
                "old evaluation must still be in flight at stop"
            }

            blocker.onDestroy()
            check(oldEvaluationCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "destroy did not complete the cancelled old evaluator callback"
            }
            check(oldNotificationCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "destroy did not complete the cancelled old notification callback"
            }
            oldEvaluationRelease.countDown()
            oldNotificationRelease.countDown()
            check(awaitJobCompleted(oldNotificationJob)) { "old notification job did not finish" }
            val oldWorkerDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (oldWorker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < oldWorkerDeadline) {
                Thread.yield()
            }
            check(!oldWorker.drainSnapshot().hasWork) { "old worker did not finish" }

            reconnectStarted.set(true)
            blocker.setup(service)
            blocker.setupReceivers()
            installDeterministicRuntime()
            check(reconnectNotificationPublished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "new setup did not publish its notification"
            }
            val newNotificationJob = getField(blocker, "liveNotificationJob") as Job
            check(awaitJobCompleted(newNotificationJob)) { "new setup notification did not finish" }
            val newWorker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            val newWorkerDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (newWorker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < newWorkerDeadline) {
                Thread.yield()
            }
            check(!newWorker.drainSnapshot().hasWork) { "new setup worker did not settle" }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val readsBeforeStaleDelivery = applicationWindowReads.get()
            val evaluationsBeforeStaleDelivery = evaluationCount.get()
            val notificationsBeforeStaleDelivery = publishedNotificationCount.get()
            val warningsBeforeStaleDelivery = service.startedActivities.size
            firstCallback.run()
            val staleWorkerDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (newWorker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < staleWorkerDeadline) {
                Thread.yield()
            }
            check(!newWorker.drainSnapshot().hasWork) { "work after stale delivery did not finish" }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            check(applicationWindowReads.get() == readsBeforeStaleDelivery) { "stale callback read a window" }
            check(evaluationCount.get() == evaluationsBeforeStaleDelivery) { "stale callback published an evaluation" }
            check(publishedNotificationCount.get() == notificationsBeforeStaleDelivery) { "stale callback published a notification" }
            check(service.startedActivities.size == warningsBeforeStaleDelivery) { "stale callback requested a warning" }

            sendWindowEvent(blocker, TARGET_PACKAGE)
            val evaluationDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (evaluationCount.get() <= evaluationsBeforeStaleDelivery &&
                android.os.SystemClock.elapsedRealtime() < evaluationDeadline) {
                Thread.yield()
            }
            check(evaluationCount.get() > evaluationsBeforeStaleDelivery) {
                "new real-host event did not publish an evaluator result"
            }
            val warningDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (service.startedActivities.size <= warningsBeforeStaleDelivery &&
                android.os.SystemClock.elapsedRealtime() < warningDeadline) {
                Thread.yield()
            }
            check(service.startedActivities.size > warningsBeforeStaleDelivery) {
                "new event did not request its warning; allowed=" + evaluationAllowed.get()
            }
        } finally {
            oldEvaluationRelease.countDown()
            oldNotificationRelease.countDown()
            blocker.onDestroy()
            runCatching {
                runBlocking {
                    service.dataStoreManager.updateAppRuleSnapshot(originalSnapshot)
                }
            }
        }
    }

    @Test
    fun concurrentPendingWorkStopsAndReconnectRecoversDurableState() {
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val repository = DestroyRaceRepository()
        runBlocking {
            repository.startSession(
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = nowMs - SESSION_DURATION_MS - 1_000L
            )
        }
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
        check(repository.openSessionCount(useDayId) == 1) {
            "the bounded destroy race must begin with an open durable session"
        }

        val service = recordingService()
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val notificationPostings = CopyOnWriteArrayList<LiveRuleNotificationModel>()
        val notificationUpdateEntered = CountDownLatch(1)
        val notificationUpdateRelease = CountDownLatch(1)
        val notificationUpdateCompleted = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val blockWindowProvider = AtomicReference(false)
        val callbackThread = AtomicReference<Thread?>(null)
        val postedCallbacks = AtomicInteger(0)
        val refreshHandoffs = CopyOnWriteArrayList<Long>()
        val decisionThread = AtomicReference<Thread?>(null)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluation -> evaluations += evaluation }
        ).apply {
            notificationBeforeFrameworkCallObserver = { _ ->
                notificationUpdateEntered.countDown()
                try {
                    notificationUpdateRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } finally {
                    notificationUpdateCompleted.countDown()
                }
            }
            notificationPublicationObserver = { model -> notificationPostings += model }
            runtimePublicationBeforeWorkerHandoff = { refreshHandoffs += it.value }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                postedCallbacks.incrementAndGet()
                callbackThread.set(Thread(runnable, "ticket15-visible-callback"))
                true
            }
            visibleApplicationCheckRemoveCallbacks = {}
            activeWindowSnapshotProvider = { AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE) }
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
        setField(blocker, "sourceOrderSequencer",
            AtomicConnectionScopedSourceOrderSequencer(initialSourceOrder = 100L, initialRuntimeRevision = 100L))
        val refreshMutex = getField(blocker, "refreshMutex") as Mutex
        val scope = getField(blocker, "scope") as CoroutineScope
        val parentJob = checkNotNull(scope.coroutineContext[Job])
        try {
            runBlocking { refreshMutex.lock() }
            val childrenBeforeRefresh = parentJob.children.toSet()
            val refreshReceiver = getField(blocker, "refreshReceiver") as BroadcastReceiver
            refreshReceiver.onReceive(service, Intent(AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES))
            val refreshDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            var refreshJob: Job? = null
            while (refreshJob == null && android.os.SystemClock.elapsedRealtime() < refreshDeadline) {
                refreshJob = (parentJob.children.toSet() - childrenBeforeRefresh).singleOrNull()
                Thread.yield()
            }
            val oldRefreshJob = checkNotNull(refreshJob) { "the old refresh was not submitted" }

            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(repository.notificationReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification did not reach its deterministic read gate"
            }

            val notificationJob = getField(blocker, "liveNotificationJob") as Job
            setField(blocker, "currentForegroundPackage", TARGET_PACKAGE)
            val decision = Thread {
                sendWindowEvent(blocker, TARGET_PACKAGE)
            }
            decisionThread.set(decision)
            decision.start()
            check(repository.secondReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "decision did not reach its deterministic read gate"
            }

            val oldWorker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            check(oldWorker.drainSnapshot().hasWork) { "the old decision was not in flight" }
            blockWindowProvider.set(true)
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            val callback = checkNotNull(callbackThread.get()) {
                "visible callback was not captured"
            }
            callback.start()
            check(callbackEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "visible callback did not reach its deterministic provider gate"
            }
            repository.releaseNotificationRead()
            check(notificationUpdateEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification did not reach its final publication boundary"
            }

            val postCountBeforeDestroy = postedCallbacks.get()
            val destroy = Thread { blocker.onDestroy() }
            destroy.start()
            destroy.join(WAIT_TIMEOUT_MS)
            check(!destroy.isAlive) { "destroy did not return within the test harness timeout" }

            repository.releaseDecisionRead()
            callbackRelease.countDown()
            notificationUpdateRelease.countDown()
            refreshMutex.unlock()
            decisionThread.get()?.join(WAIT_TIMEOUT_MS)
            callback.join(WAIT_TIMEOUT_MS)
            check(!callback.isAlive) { "old provider callback did not finish" }
            check(repository.notificationReadCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            check(repository.decisionReadCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            check(notificationUpdateCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            check(awaitJobCompleted(notificationJob)) { "old notification job did not finish" }
            check(awaitJobCompleted(oldRefreshJob)) { "old refresh job did not finish" }
            val workerDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (oldWorker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < workerDeadline) {
                Thread.yield()
            }
            check(!oldWorker.drainSnapshot().hasWork) { "old decision worker did not finish" }
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger))

            check(refreshHandoffs.none { it > 100L }) { "old refresh handed off a new revision" }
            check(postedCallbacks.get() == postCountBeforeDestroy) { "old work posted a new visible check" }
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
            // Traverse the production tracker setup, which owns the reconnect recovery call. The
            // local repository seam only supplies a durable fixture with an actually open row.
            val recoveryTracker = AppUsageTracker().apply {
                sessionRepositoryOverrideForTesting = { repository }
            }
            try {
                recoveryTracker.setup(recoveryService)
                check(repository.recoverOpenSessionsCount.get() == 1) {
                    "reconnect did not use AppUsageTracker.setup recoverOpenSessions"
                }
                check(repository.openSessionCount(useDayId) == 0) {
                    "recoverOpenSessions left the durable session unfinished"
                }
            } finally {
                recoveryTracker.onDestroy()
            }
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
                val readsBeforeReconnect = repository.successfulReadCount.get()
                sendWindowEvent(recoveryBlocker, TARGET_PACKAGE)
                check(recoveryEvaluated.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "reconnect did not publish the new generation evaluation"
                }
                check(recoveryEvaluations.single().isAllowed.not()) {
                    "reconnect did not evaluate the durable session as denied: " +
                        recoveryEvaluations.single()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                check(repository.successfulReadCount.get() > readsBeforeReconnect) {
                    "reconnect did not perform a fresh durable read"
                }
                check(awaitNonEmpty(recoveryService.startedActivities)) {
                    "latest reconnect generation did not publish its warning"
                }
            } finally {
                recoveryBlocker.onDestroy()
            }
        } finally {
            repository.releaseDecisionRead()
            repository.releaseNotificationRead()
            callbackRelease.countDown()
            notificationUpdateRelease.countDown()
            if (refreshMutex.isLocked) refreshMutex.unlock()
            callbackThread.get()?.join(WAIT_TIMEOUT_MS)
            decisionThread.get()?.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun providerReadReleasedAfterDestroyCannotRecordForegroundEvidence() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val postedCallbacks = CopyOnWriteArrayList<Runnable>()
        val recordedEvidence = CopyOnWriteArrayList<String>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                postedCallbacks += runnable
                true
            }
            visibleApplicationCheckRemoveCallbacks = {}
            applicationWindowSnapshotProvider = {
                providerEntered.countDown()
                providerRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false,
                    applicationWindowCount = 1
                )
            }
            foregroundEvidenceRecordObserver = { recordedEvidence += it }
        }
        try {
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            val callback = checkNotNull(postedCallbacks.singleOrNull()) {
                "provider-backed handler callback was not posted"
            }
            val callbackThread = Thread(callback, "ticket15-provider-release")
            callbackThread.start()
            check(providerEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "provider read did not reach its deterministic barrier"
            }

            blocker.onDestroy()
            providerRelease.countDown()
            callbackThread.join(WAIT_TIMEOUT_MS)
            check(!callbackThread.isAlive) { "provider callback remained blocked after release" }
            check(recordedEvidence.isEmpty()) {
                "provider-backed handler recorded evidence after destroy: $recordedEvidence"
            }
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)) {
                "provider callback did not drain after destroy"
            }
        } finally {
            providerRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun accessibilityEventReadReleasedAfterDestroyCannotRecordForegroundEvidence() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val readCompleted = CountDownLatch(1)
        val readRelease = CountDownLatch(1)
        val recordedEvidence = CopyOnWriteArrayList<String>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            foregroundEvidenceBeforeRecordObserver = {
                readCompleted.countDown()
                readRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            foregroundEvidenceRecordObserver = { recordedEvidence += it }
        }
        val eventThread = Thread({ sendWindowEvent(blocker, TARGET_PACKAGE) }, "ticket15-event-read")
        try {
            eventThread.start()
            check(readCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "accessibility event read did not reach its deterministic barrier"
            }

            blocker.onDestroy()
            readRelease.countDown()
            eventThread.join(WAIT_TIMEOUT_MS)
            check(!eventThread.isAlive) { "accessibility event callback remained blocked" }
            check(recordedEvidence.isEmpty()) {
                "accessibility event handler recorded evidence after destroy: $recordedEvidence"
            }
            check(getField(blocker, "currentForegroundPackage") == null) {
                "accessibility event handler mutated foreground state after destroy"
            }
        } finally {
            readRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun blockedAccessibilityEventCannotBeRetaggedOrProcessedAfterReconnect() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val originalSnapshot = runBlocking {
            service.dataStoreManager.settings.first().appRuleSnapshot
        }
        runBlocking {
            check(service.dataStoreManager.updateAppRuleSnapshot(snapshotWithGlobalDeny())) {
                "could not install the deterministic reconnect snapshot"
            }
        }
        val eventObservationEntered = CountDownLatch(1)
        val eventObservationRelease = CountDownLatch(1)
        val evaluationPublished = CountDownLatch(1)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluations += it }
        ).apply {
            foregroundEvidenceBeforeRecordObserver = {
                eventObservationEntered.countDown()
                eventObservationRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false,
                    applicationWindowCount = 1
                )
            }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
            }
            // Reconnect is real, but its automatic reconciliation must remain undelivered so the
            // only candidate publication is the blocked first-generation event.
            visibleApplicationCheckPostDelayed = { _, _ -> true }
            visibleApplicationCheckRemoveCallbacks = {}
        }
        val eventThread = Thread(
            { sendWindowEvent(blocker, TARGET_PACKAGE) },
            "ticket15-old-accessibility-event"
        )
        try {
            eventThread.start()
            check(eventObservationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "old accessibility event did not reach its observation barrier"
            }

            blocker.onDestroy()
            blocker.evaluationResultObserver = null
            blocker.setup(service)
            // The first event resumes after this real setup, so make its fallback observation
            // deterministic while retaining the new connection generation and worker.
            setField(blocker, "foregroundObservationSource", null)
            blocker.evaluationResultObserver = {
                evaluations += it
                evaluationPublished.countDown()
            }
            val latestGeneration = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()
            eventObservationRelease.countDown()
            eventThread.join(WAIT_TIMEOUT_MS)
            check(!eventThread.isAlive) { "blocked accessibility event remained unreleased" }

            check(!evaluationPublished.await(500L, TimeUnit.MILLISECONDS)) {
                "old event was durably processed by the latest worker: $evaluations"
            }
            check(evaluations.isEmpty()) {
                "old event was durably processed by the latest worker: $evaluations"
            }
            check(service.startedActivities.isEmpty()) {
                "old event produced a warning after reconnect"
            }
            check(
                (getField(blocker, "lifecycleGeneration") as AtomicLong).get() ==
                    latestGeneration
            ) { "reconnect generation changed while releasing the old event" }
        } finally {
            eventObservationRelease.countDown()
            eventThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
            runCatching {
                runBlocking {
                    service.dataStoreManager.updateAppRuleSnapshot(originalSnapshot)
                }
            }
        }
    }

    @Test
    fun staleApplicationWindowProviderCannotRepopulateCacheAfterReconnect() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            applicationWindowSnapshotProvider = {
                providerEntered.countDown()
                providerRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false,
                    applicationWindowCount = 1
                )
            }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
            }
            visibleApplicationCheckPostDelayed = { _, _ -> true }
            visibleApplicationCheckRemoveCallbacks = {}
        }
        val providerThread = Thread(
            { sendWindowEvent(blocker, TARGET_PACKAGE) },
            "ticket15-stale-provenance-provider"
        )
        try {
            providerThread.start()
            check(providerEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "application-window provider did not reach its reconnect barrier"
            }

            blocker.onDestroy()
            blocker.setup(service)
            providerRelease.countDown()
            providerThread.join(WAIT_TIMEOUT_MS)
            check(!providerThread.isAlive) { "stale application-window provider remained blocked" }

            val source = getField(blocker, "foregroundObservationSource")
            val cache = getField(source!!, "windowProvenanceCache")
            check(getField(cache!!, "cached") == null) {
                "stale provider repopulated application provenance after reconnect"
            }
        } finally {
            providerRelease.countDown()
            providerThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun staleScheduledObservationCannotContaminateNewSourceOrGeneration() {
        val service = recordingService()
        val repository = FaultRepository(FaultMode.HEALTHY)
        val oldProviderEntered = CountDownLatch(1)
        val oldProviderRelease = CountDownLatch(1)
        val oldEvaluation = CountDownLatch(1)
        val newEmptyObservation = CountDownLatch(1)
        val newEvaluation = CountDownLatch(1)
        val queuedChecks = CopyOnWriteArrayList<Runnable>()
        val evaluatedPackages = CopyOnWriteArrayList<String>()
        val blockOldProvider = AtomicBoolean(false)
        val oldProbeIsEmpty = AtomicBoolean(false)
        val otherPackage = "com.example.other"
        fun facts(trigger: ObservationTrigger, packageName: String?): ForegroundFacts = ForegroundFacts(
            capturedAtWallMs = trigger.requestedAtWallMs,
            capturedAtElapsedMs = trigger.requestedAtElapsedMs,
            signal = SignalFact(
                kind = trigger.kind,
                eventPackage = trigger.eventPackage,
                eventWallMs = trigger.eventPackage?.let { trigger.requestedAtWallMs },
                eventElapsedMs = trigger.eventPackage?.let { trigger.requestedAtElapsedMs }
            ),
            activeRoot = ActiveRootFact(
                packageName = packageName,
                readState = if (packageName == null) ForegroundReadState.EMPTY else ForegroundReadState.AVAILABLE
            ),
            applicationWindows = ApplicationWindowsFact(
                packages = packageName?.let(::setOf) ?: emptySet(),
                readState = if (packageName == null) ForegroundReadState.EMPTY else ForegroundReadState.AVAILABLE
            ),
            displayState = DisplayState.UNLOCKED
        )
        val oldSource = AndroidForegroundObservationSource(
            factProvider = { trigger ->
                val observedPackage = if (oldProbeIsEmpty.get()) null else TARGET_PACKAGE
                if (blockOldProvider.get() && trigger.kind == ObservationKind.REFRESH) {
                    oldProviderEntered.countDown()
                    oldProviderRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                facts(trigger, observedPackage)
            },
            errorReporter = { throw AssertionError(it) }
        )
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { oldEvaluation.countDown() }
        ).apply {
            visibleApplicationCheckPostDelayed = { callback, _ -> queuedChecks += callback; true }
            visibleApplicationCheckRemoveCallbacks = {}
        }
        setField(blocker, "foregroundObservationSource", oldSource)
        val scheduler = checkNotNull(blocker.wakeScheduler as? FakeWakeScheduler)
        val callbackFailure = AtomicReference<Throwable?>()
        var oldCallbackThread: Thread? = null
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(oldEvaluation.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the old generation did not evaluate its initial target"
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (!scheduler.hasScheduled(TARGET_PACKAGE) && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.yield()
            }
            check(scheduler.hasScheduled(TARGET_PACKAGE)) { "the old target was not scheduled" }
            blockOldProvider.set(true)
            check(scheduler.triggerWake(TARGET_PACKAGE)) { "the old wake was not delivered" }
            val oldCallback = checkNotNull(queuedChecks.firstOrNull()) { "the wake did not post a visible check" }
            val callbackThread = Thread {
                try { oldCallback.run() } catch (error: Throwable) { callbackFailure.set(error) }
            }
            oldCallbackThread = callbackThread
            callbackThread.start()
            check(oldProviderEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the old scheduled observation did not enter its provider"
            }
            oldProbeIsEmpty.set(true)
            fun emptyProbe(source: AndroidForegroundObservationSource): ForegroundFacts = source.capture(
                ObservationTrigger(
                    sourceOrderIdentity = SourceOrderIdentity(1L),
                    kind = ObservationKind.SYNTHETIC_RECHECK,
                    requestedAtWallMs = System.currentTimeMillis(),
                    requestedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                )
            )
            check(emptyProbe(oldSource).applicationWindows.stalePackages.isEmpty()) {
                "the scheduled refresh did not clear the earlier source observation"
            }

            blocker.onDestroy()
            queuedChecks.clear()
            blocker.setup(service)
            val newSource = AndroidForegroundObservationSource(
                factProvider = { trigger ->
                    val packageName = trigger.eventPackage?.takeIf { it == otherPackage }
                    if (packageName == null && trigger.kind == ObservationKind.RECONNECT) {
                        newEmptyObservation.countDown()
                    }
                    facts(trigger, packageName)
                },
                errorReporter = { throw AssertionError(it) }
            )
            setField(blocker, "foregroundObservationSource", newSource)
            setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE, otherPackage))
            blocker.decisionOutcomeSinkObserver = { outcome ->
                if (outcome is DecisionOutcome.EvaluationReady) {
                    evaluatedPackages += outcome.packageName
                    if (outcome.packageName == otherPackage) newEvaluation.countDown()
                }
            }
            oldProviderRelease.countDown()
            callbackThread.join(WAIT_TIMEOUT_MS)
            check(!callbackThread.isAlive) { "the old provider did not complete" }
            callbackFailure.get()?.let { throw it }
            val oldAfterCompletion = emptyProbe(oldSource)
            check(TARGET_PACKAGE in oldAfterCompletion.applicationWindows.stalePackages) {
                "the completed old observation did not leave its expected provenance"
            }
            val newAfterCompletion = emptyProbe(newSource)
            check(newAfterCompletion.applicationWindows.freshness == ApplicationWindowsFreshness.FRESH &&
                newAfterCompletion.applicationWindows.stalePackages.isEmpty()
            ) { "the old observation contaminated new source provenance: ${newAfterCompletion.applicationWindows}" }
            val beforeNewObservation = service.startedActivities.size
            check(awaitNonEmpty(queuedChecks)) { "reconnect did not post its visible observation" }
            val reconnectCallback = queuedChecks.first()
            reconnectCallback.run()
            check(newEmptyObservation.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the new generation did not observe an empty window"
            }
            sendWindowEvent(blocker, otherPackage)
            check(newEvaluation.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the new generation did not process a new other-app event"
            }
            check(TARGET_PACKAGE !in evaluatedPackages) { "the stale target was evaluated after reconnect" }
            check(service.startedActivities.drop(beforeNewObservation).none {
                it.getStringExtra(GuardianApprovalActivity.EXTRA_PACKAGE) == TARGET_PACKAGE
            }) { "the stale target launched a warning after reconnect" }
        } finally {
            oldProviderRelease.countDown()
            oldCallbackThread?.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun notificationFinalFrameworkCallCannotStartAfterDestroy() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val publicationEntered = CountDownLatch(1)
        val publicationRelease = CountDownLatch(1)
        val publications = CopyOnWriteArrayList<LiveRuleNotificationModel>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            notificationBeforeFrameworkCallObserver = { _ ->
                publicationEntered.countDown()
                publicationRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            notificationPublicationObserver = { publications += it }
        }
        try {
            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(repository.notificationReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification did not reach its deterministic read gate"
            }
            repository.releaseNotificationRead()
            check(publicationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification did not reach its final framework-call barrier"
            }

            blocker.onDestroy()
            publicationRelease.countDown()
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)) {
                "notification did not drain after destroy"
            }
            check(publications.isEmpty()) {
                "notification manager publication escaped destroy: $publications"
            }
        } finally {
            publicationRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun cancelledNotificationPublicationRetriesIdenticalModel() {
        val repository = FaultRepository(FaultMode.HEALTHY)
        val service = recordingService()
        val updateEntered = CountDownLatch(1)
        val updateCount = AtomicInteger(0)
        val publications = CopyOnWriteArrayList<LiveRuleNotificationModel>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            notificationUpdateObserver = {
                if (updateCount.incrementAndGet() == 1) {
                    updateEntered.countDown()
                    throw CancellationException("injected notification publication cancellation")
                }
            }
            notificationPublicationObserver = { publications += it }
        }
        try {
            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(updateEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification did not reach the cancellable publication boundary"
            }
            check(awaitJobCompleted(getField(blocker, "liveNotificationJob") as? kotlinx.coroutines.Job)) {
                "cancelled notification worker did not finish"
            }

            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(awaitNonEmpty(publications)) {
                "identical notification model was not retried after cancellation"
            }
            check(updateCount.get() == 2) {
                "notification dedup state did not roll back for the identical retry"
            }
        } finally {
            blocker.onDestroy()
        }
    }

    @Test
    fun warningFinalFrameworkCallCannotStartAfterDestroy() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val warningEntered = CountDownLatch(1)
        val warningRelease = CountDownLatch(1)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            warningBeforeFrameworkCallObserver = {
                warningEntered.countDown()
                warningRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            repository.releaseNotificationRead()
            repository.releaseDecisionRead()
            check(warningEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "warning did not reach its final framework-call barrier"
            }

            blocker.onDestroy()
            warningRelease.countDown()
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)) {
                "warning did not drain after destroy"
            }
            check(service.startedActivities.isEmpty()) {
                "warning Activity publication escaped destroy"
            }
        } finally {
            warningRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun visibleHandlerPostCannotStartAfterDestroyAtItsFinalBarrier() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val handlerEntered = CountDownLatch(1)
        val handlerRelease = CountDownLatch(1)
        val posted = AtomicInteger(0)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            handlerBeforeFrameworkPostObserver = {
                handlerEntered.countDown()
                handlerRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            visibleApplicationCheckPostDelayed = { _, _ ->
                posted.incrementAndGet()
                true
            }
            visibleApplicationCheckRemoveCallbacks = {}
        }
        val postThread = Thread(
            { invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L) },
            "ticket15-handler-final-call"
        )
        try {
            postThread.start()
            check(handlerEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "handler post did not reach its final framework-call barrier"
            }

            blocker.onDestroy()
            handlerRelease.countDown()
            postThread.join(WAIT_TIMEOUT_MS)
            check(!postThread.isAlive) { "handler post remained blocked after release" }
            check(posted.get() == 0) {
                "handler callback was posted after destroy: ${posted.get()}"
            }
        } finally {
            handlerRelease.countDown()
            postThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun inFlightScheduledWakeIsCancelledOnDestroyAndLateDeliveryHasNoEffect() {
        val repository = FaultRepository(FaultMode.HEALTHY)
        val service = recordingService()
        val schedulerEntered = CountDownLatch(1)
        val schedulerRelease = CountDownLatch(1)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val scheduledToken = AtomicLong(-1L)
        val lateVisiblePosts = AtomicInteger(0)
        val delegate = FakeWakeScheduler()
        val scheduler = object : AppRuleWakeScheduler by delegate {
            override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
                if (key == TARGET_PACKAGE) {
                    scheduledToken.set(token)
                    schedulerEntered.countDown()
                    schedulerRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                delegate.schedule(key, dueAtWallClockMs, token)
            }
        }
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluations += it }
        ).apply {
            wakeScheduler = scheduler
            visibleApplicationCheckPostDelayed = { _, _ -> lateVisiblePosts.incrementAndGet(); true }
        }
        val destroyReturned = CountDownLatch(1)
        val destroyThread = Thread {
            try { blocker.onDestroy() } finally { destroyReturned.countDown() }
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(schedulerEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the target wake never entered scheduler registration"
            }
            val staleWake = checkNotNull(scheduler.onWake) { "the scheduler callback was not installed" }
            val worker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            val evaluationsBeforeDestroy = evaluations.size
            val warningsBeforeDestroy = service.startedActivities.size
            destroyThread.start()
            val invalidationDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (getField(blocker, "destroyed") != true &&
                android.os.SystemClock.elapsedRealtime() < invalidationDeadline
            ) Thread.yield()
            check(getField(blocker, "destroyed") == true) { "destroy did not invalidate the old generation" }
            schedulerRelease.countDown()
            check(destroyReturned.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "destroy did not return" }
            val completionDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (worker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < completionDeadline) {
                Thread.yield()
            }
            check(!worker.drainSnapshot().hasWork) { "the old scheduler worker did not complete" }
            check(delegate.scheduledCount() == 0) { "destroy left an active wake registration" }
            staleWake(TARGET_PACKAGE, scheduledToken.get())
            check(lateVisiblePosts.get() == 0) { "late wake posted a visible check" }
            check(evaluations.size == evaluationsBeforeDestroy) { "late wake produced an evaluation" }
            check(service.startedActivities.size == warningsBeforeDestroy) { "late wake launched a warning" }
        } finally {
            schedulerRelease.countDown()
            destroyThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun rearmedWakeIsCancelledWhenDestroyRacesItsRegistration() {
        val service = recordingService()
        val repository = FaultRepository(FaultMode.HEALTHY)
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val delegate = FakeWakeScheduler(initialWallClockMs = now, initialElapsedRealtimeMs = elapsed)
        val schedules = AtomicInteger(0)
        val rearmEntered = CountDownLatch(1)
        val rearmRelease = CountDownLatch(1)
        val rearmDue = AtomicLong(-1L)
        val rearmToken = AtomicLong(-1L)
        val postAttempts = AtomicInteger(0)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val scheduler = object : AppRuleWakeScheduler by delegate {
            override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
                if (key == TARGET_PACKAGE && schedules.incrementAndGet() == 2) {
                    rearmDue.set(dueAtWallClockMs)
                    rearmToken.set(token)
                    rearmEntered.countDown()
                    rearmRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                delegate.schedule(key, dueAtWallClockMs, token)
            }
        }
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluations += it }
        ).apply {
            wakeScheduler = scheduler
            wallClockMsProvider = { delegate.currentWallClockMs }
            elapsedRealtimeMsProvider = { delegate.currentElapsedRealtimeMs }
            activeWindowSnapshotProvider = { AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE) }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE), hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            visibleApplicationCheckPostDelayed = { _, _ -> postAttempts.incrementAndGet(); false }
        }
        val triggerFailure = AtomicReference<Throwable?>()
        val triggered = AtomicBoolean(false)
        val triggerThread = Thread {
            try { triggered.set(delegate.triggerWake(TARGET_PACKAGE)) }
            catch (error: Throwable) { triggerFailure.set(error) }
        }
        val destroyReturned = CountDownLatch(1)
        val destroyThread = Thread {
            try { blocker.onDestroy() } finally { destroyReturned.countDown() }
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            val initialDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (!delegate.hasScheduled(TARGET_PACKAGE) && android.os.SystemClock.elapsedRealtime() < initialDeadline) {
                Thread.yield()
            }
            val originalDue = checkNotNull(delegate.getScheduled(TARGET_PACKAGE)) {
                "the initial target wake was not registered"
            }.dueAtWallClockMs
            check(originalDue > now) { "the initial target wake must still be in the future" }
            val staleWake = checkNotNull(scheduler.onWake)
            val evaluationsBeforeDestroy = evaluations.size
            val warningsBeforeDestroy = service.startedActivities.size
            val worker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            triggerThread.start()
            check(rearmEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "failed visible posts did not reach scheduler re-registration"
            }
            check(postAttempts.get() == 3 && rearmDue.get() == originalDue) {
                "the early wake did not retain its deadline after three failed posts"
            }
            destroyThread.start()
            val invalidationDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (getField(blocker, "destroyed") != true &&
                android.os.SystemClock.elapsedRealtime() < invalidationDeadline
            ) Thread.yield()
            check(getField(blocker, "destroyed") == true) { "destroy did not invalidate the old generation" }
            rearmRelease.countDown()
            triggerThread.join(WAIT_TIMEOUT_MS)
            check(!triggerThread.isAlive && triggered.get()) { "the original wake did not complete" }
            triggerFailure.get()?.let { throw it }
            check(destroyReturned.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "destroy did not return" }
            val completionDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (worker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < completionDeadline) {
                Thread.yield()
            }
            check(!worker.drainSnapshot().hasWork) { "the old worker did not complete" }
            check(delegate.scheduledCount() == 0) { "destroy left the rearmed wake registered" }
            staleWake(TARGET_PACKAGE, rearmToken.get())
            check(postAttempts.get() == 3) { "late rearmed wake attempted another visible post" }
            check(evaluations.size == evaluationsBeforeDestroy) { "late rearmed wake evaluated a rule" }
            check(service.startedActivities.size == warningsBeforeDestroy) { "late rearmed wake requested a warning" }
        } finally {
            rearmRelease.countDown()
            triggerThread.join(WAIT_TIMEOUT_MS)
            destroyThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun inFlightAlarmIsCancelledOnDestroyAndLateDeliveryHasNoEffect() {
        val service = recordingService()
        val repository = FaultRepository(FaultMode.HEALTHY)
        val alarmEntered = CountDownLatch(1)
        val alarmRelease = CountDownLatch(1)
        val activeAlarm = AtomicBoolean(false)
        val cancelled = AtomicInteger(0)
        val scheduledToken = AtomicLong(-1L)
        val visiblePosts = AtomicInteger(0)
        val pendingIntent = AtomicReference<PendingIntent?>()
        val handlerCallbacks = CopyOnWriteArrayList<Runnable>()
        val receiverRegistered = AtomicBoolean(false)
        val receiverRegistrations = AtomicInteger(0)
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val publisher = object : AlarmPublisher {
            override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
                alarmEntered.countDown()
                alarmRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                activeAlarm.set(true)
            }
            override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
                error("unexpected inexact alarm")
            }
            override fun cancel(operation: PendingIntent) {
                activeAlarm.set(false)
                cancelled.incrementAndGet()
            }
        }
        val scheduler = AndroidAppRuleWakeScheduler(
            alarmPublisher = publisher,
            handlerPostDelayed = { runnable, _ -> handlerCallbacks += runnable; true },
            handlerRemoveCallbacks = { handlerCallbacks.remove(it) },
            wallClockMs = { System.currentTimeMillis() },
            elapsedRealtimeMs = { android.os.SystemClock.elapsedRealtime() },
            pendingIntentFactory = { key, token ->
                scheduledToken.set(token)
                PendingIntent.getBroadcast(
                    service, 76531,
                    Intent("neth.iecal.curbox.TEST_ALARM").setPackage(service.packageName).putExtra("key", key),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).also { pendingIntent.set(it) }
            },
            cancelPendingIntent = { it.cancel() },
            registerReceiver = { _, _ -> receiverRegistrations.incrementAndGet(); receiverRegistered.set(true) },
            unregisterReceiver = { receiverRegistered.set(false) },
            onNonFatalError = { throw AssertionError(it) }
        )
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluations += it }
        ).apply {
            wakeScheduler = scheduler
            visibleApplicationCheckPostDelayed = { _, _ -> visiblePosts.incrementAndGet(); true }
        }
        val destroyReturned = CountDownLatch(1)
        val destroyThread = Thread {
            try { blocker.onDestroy() } finally { destroyReturned.countDown() }
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(alarmEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the target alarm did not reach the adapter publisher"
            }
            val worker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            val oldEvaluations = evaluations.size
            val oldWarnings = service.startedActivities.size
            destroyThread.start()
            val invalidationDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (getField(blocker, "destroyed") != true &&
                android.os.SystemClock.elapsedRealtime() < invalidationDeadline
            ) Thread.yield()
            check(getField(blocker, "destroyed") == true) { "destroy did not invalidate the old generation" }
            alarmRelease.countDown()
            check(destroyReturned.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "destroy did not return" }
            val completionDeadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (worker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < completionDeadline) {
                Thread.yield()
            }
            check(!worker.drainSnapshot().hasWork) { "the old alarm worker did not complete" }
            check(cancelled.get() > 0 && !activeAlarm.get()) { "destroy left the published alarm active" }
            check(handlerCallbacks.isEmpty()) { "destroy left an adapter handler callback" }
            check(receiverRegistrations.get() > 0 && !receiverRegistered.get()) {
                "destroy did not unregister the adapter receiver"
            }
            scheduler.receiver.onReceive(
                service,
                Intent(AndroidAppRuleWakeScheduler.ACTION_APP_RULE_WAKE)
                    .putExtra(AndroidAppRuleWakeScheduler.EXTRA_KEY, TARGET_PACKAGE)
                    .putExtra(AndroidAppRuleWakeScheduler.EXTRA_TOKEN, scheduledToken.get())
            )
            check(visiblePosts.get() == 0) { "late alarm posted a visible check" }
            check(evaluations.size == oldEvaluations) { "late alarm produced an evaluation" }
            check(service.startedActivities.size == oldWarnings) { "late alarm launched a warning" }
        } finally {
            alarmRelease.countDown()
            destroyThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
            scheduler.cancelAll()
            pendingIntent.get()?.cancel()
        }
    }

    @Test
    fun usageResetCompletionBroadcastsAreIndependentlyFencedAcrossDestroy() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val completionEntered = CountDownLatch(1)
        val completionRelease = CountDownLatch(1)
        val broadcastEntered = CountDownLatch(1)
        val broadcastRelease = CountDownLatch(1)
        val broadcastCompleted = CountDownLatch(1)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            usageResetCompletionPostObserver = { _, _ ->
                completionEntered.countDown()
                completionRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            usageResetBroadcastObserver = {
                // Keep the existing recorder boundary independent from the final-call barrier.
            }
            usageResetBeforeFrameworkCallObserver = {
                broadcastEntered.countDown()
                try {
                    broadcastRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } finally {
                    broadcastCompleted.countDown()
                }
            }
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(awaitNonNullField(blocker, "decisionWorker")) {
                "foreground event did not install the serialized worker"
            }
            repository.releaseNotificationRead()
            repository.releaseDecisionRead()
            check(blocker.submitUsageReset(setOf(TARGET_PACKAGE), "ticket15-reset")) {
                "usage reset was not accepted by the serialized worker"
            }
            check(completionEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "usage-reset completion did not reach its production publication barrier"
            }
            completionRelease.countDown()
            check(broadcastEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "usage-reset completion did not reach its actual broadcast boundary"
            }

            blocker.onDestroy()
            broadcastRelease.countDown()
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)) {
                "usage-reset completion did not drain after destroy"
            }
            check(broadcastCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "usage-reset broadcast boundary did not complete after destroy"
            }
            check(service.sentBroadcasts.isEmpty()) {
                "usage-reset completion broadcast escaped destroy: ${service.sentBroadcasts}"
            }
        } finally {
            completionRelease.countDown()
            broadcastRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun workerRecheckPlanDeliveryCannotRegisterAfterDestroy() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val planEntered = CountDownLatch(1)
        val planRelease = CountDownLatch(1)
        val registrations = AtomicInteger(0)
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        runBlocking {
            val sessionId = repository.startSession(
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = nowMs - SESSION_DURATION_MS
            )
            repository.commitSessionCheckpoint(
                id = sessionId,
                endedAtMs = nowMs,
                usage = emptyList()
            )
        }
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = {}
        ).apply {
            val delegate = FakeWakeScheduler()
            wakeScheduler = object : AppRuleWakeScheduler by delegate {
                override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
                    registrations.incrementAndGet()
                    delegate.schedule(key, dueAtWallClockMs, token)
                }
            }
            recheckPlanDeliveryObserver = { update ->
                check(update.packageName == TARGET_PACKAGE && (update.plan?.dueAtWallClockMs ?: 0L) > 0L
                ) { "the intercepted update must contain a real target recheck plan" }
                planEntered.countDown()
                planRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            repository.releaseNotificationRead()
            repository.releaseDecisionRead()
            check(planEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "worker-generated recheck plan did not reach its delivery barrier"
            }

            val worker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            blocker.onDestroy()
            planRelease.countDown()
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)) {
                "recheck-plan delivery did not drain after destroy"
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (worker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.yield()
            }
            check(!worker.drainSnapshot().hasWork) { "the old worker did not finish the plan" }
            check(registrations.get() == 0) {
                "worker-generated recheck plan registered after destroy: ${registrations.get()}"
            }
        } finally {
            planRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun pendingRefreshesCannotPublishAfterDestroyAndReconnect() {
        val service = recordingService()
        val readerEntered = CountDownLatch(1)
        val readerRelease = CountDownLatch(1)
        val readerCompleted = CountDownLatch(1)
        val readerCalls = AtomicInteger(0)
        val oldPackage = "com.example.stale.refresh"
        val postedChecks = CopyOnWriteArrayList<Runnable>()
        val handoffRevisions = CopyOnWriteArrayList<Long>()
        val freshEvaluation = CountDownLatch(1)
        val blocker = configureBlocker(
            repository = FaultRepository(FaultMode.HEALTHY),
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            visibleApplicationCheckPostDelayed = { callback, _ -> postedChecks += callback; true }
            visibleApplicationCheckRemoveCallbacks = {}
            runtimePublicationBeforeWorkerHandoff = { handoffRevisions += it.value }
        }
        val reader = AppRulePackageScopeReader(
            launchableReader = {
                readerCalls.incrementAndGet()
                readerEntered.countDown()
                try { readerRelease.await() } finally { readerCompleted.countDown() }
                setOf(oldPackage)
            },
            essentialReader = { emptySet() }
        )
        setField(blocker, "packageScopeReader", reader)
        setField(
            blocker, "sourceOrderSequencer",
            AtomicConnectionScopedSourceOrderSequencer(initialSourceOrder = 100L, initialRuntimeRevision = 100L)
        )
        val receiver = getField(blocker, "refreshReceiver") as BroadcastReceiver
        val scope = getField(blocker, "scope") as CoroutineScope
        val parentJob = checkNotNull(scope.coroutineContext[Job])
        fun waitForNewChild(previous: Set<Job>): Job {
            val deadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val added = parentJob.children.toSet() - previous
                if (added.size == 1) return added.single()
                Thread.yield()
            }
            error("the refresh broadcast did not create its own pending job")
        }
        try {
            val beforeFirst = parentJob.children.toSet()
            receiver.onReceive(service, Intent(AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES))
            val firstJob = waitForNewChild(beforeFirst)
            check(readerEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the first refresh did not enter its package read under the mutex"
            }
            val beforeSecond = parentJob.children.toSet()
            receiver.onReceive(service, Intent(AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES))
            val secondJob = waitForNewChild(beforeSecond)
            check(firstJob !== secondJob) { "refresh broadcasts did not create distinct jobs" }
            blocker.onDestroy()
            blocker.setup(service)
            val newSettingsJob = getField(blocker, "settingsJob") as? Job
            newSettingsJob?.cancel()
            check(newSettingsJob == null || awaitJobCompleted(newSettingsJob)) {
                "the new settings collector did not stop for the test fixture"
            }
            val newCoordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
            val snapshotBeforeRelease = newCoordinator.snapshot()
            postedChecks.clear()
            readerRelease.countDown()
            check(readerCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the old package reader did not finish"
            }
            runBlocking { withTimeout(WAIT_TIMEOUT_MS) { listOf(firstJob, secondJob).joinAll() } }
            check(readerCalls.get() == 1) { "the second old refresh entered its package reader" }
            @Suppress("UNCHECKED_CAST")
            val launchable = getField(blocker, "launchablePackages") as Set<String>
            check(oldPackage !in launchable) { "an old refresh replaced the new package scope" }
            check(newCoordinator.snapshot() == snapshotBeforeRelease) {
                "an old refresh replaced the new settings snapshot"
            }
            check(handoffRevisions.none { it > 100L }) { "an old refresh published its runtime revision" }
            check(postedChecks.isEmpty()) { "an old refresh posted a visible reconciliation" }

            setField(blocker, "foregroundObservationSource", null)
            setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE))
            blocker.screenInteractiveProvider = { true }
            blocker.keyguardLockedProvider = { false }
            blocker.applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE), hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            blocker.activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
            }
            blocker.evaluationResultObserver = { freshEvaluation.countDown() }
            val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
            check(coordinator.accept(snapshotWithGlobalDeny()))
            val generation = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()
            invokePrivate(blocker, "submitRuntimePublication", generation)
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(freshEvaluation.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the reconnected generation did not process a new event"
            }
        } finally {
            readerRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun inFlightDecisionDestroyHasNoPostDestroySideEffectsAndReconnectRecoversDurableState() {
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val repository = DestroyRaceRepository()
        val durableSessionId = runBlocking {
            repository.startSession(useDayId, TARGET_PACKAGE, nowMs - SESSION_DURATION_MS)
        }
        runBlocking {
            repository.commitSessionCheckpoint(durableSessionId, nowMs, emptyList())
            repository.startSession(useDayId, TARGET_PACKAGE, nowMs)
        }
        check(repository.openSessionCount(useDayId) == 1) {
            "the reconnect race must start with an open durable session"
        }
        val service = recordingService()
        val visibleCallbacks = RecordingCallbacks()
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val notifications = CopyOnWriteArrayList<LiveRuleNotificationModel>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluations += it }
        ).apply {
            notificationPublicationObserver = { notifications += it }
            activeWindowSnapshotProvider = { AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE) }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, _ -> visibleCallbacks.post(runnable) }
            visibleApplicationCheckRemoveCallbacks = visibleCallbacks::removeAll
        }
        try {
            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(repository.notificationReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "notification did not reach its durable read gate"
            }
            val notificationJob = getField(blocker, "liveNotificationJob") as kotlinx.coroutines.Job
            setField(blocker, "currentForegroundPackage", TARGET_PACKAGE)
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            check(visibleCallbacks.pendingCount == 1) { "the visible callback was not queued" }
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(repository.secondReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "decision did not reach its durable read gate"
            }
            val worker = getField(blocker, "decisionWorker") as SerializedDecisionWorker
            check(worker.drainSnapshot().hasWork) { "the old decision was not in flight" }
            blocker.onDestroy()
            repository.releaseDecisionRead()
            repository.releaseNotificationRead()
            check(repository.decisionReadCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the old decision read did not finish after release"
            }
            check(repository.notificationReadCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "the old notification read did not finish after release"
            }
            check(awaitJobCompleted(notificationJob)) { "the old notification job did not finish" }
            val deadline = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
            while (worker.drainSnapshot().hasWork && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.yield()
            }
            check(!worker.drainSnapshot().hasWork) { "the old decision worker did not finish" }
            check(visibleCallbacks.pendingCount == 0 && visibleCallbacks.removedCount == 1) {
                "destroy did not remove the queued visible callback"
            }
            visibleCallbacks.deliverPending()
            check(visibleCallbacks.deliveredCount == 0) { "a removed visible callback was delivered" }
            check(evaluations.isEmpty()) { "the old decision was published after destroy" }
            check(notifications.isEmpty()) { "old notification was published after destroy" }
            check(service.startedActivities.isEmpty()) { "old warning was launched after destroy" }

            val recoveryService = recordingService()
            val recoveryTracker = AppUsageTracker().apply {
                sessionRepositoryOverrideForTesting = { repository }
            }
            try {
                recoveryTracker.setup(recoveryService)
                check(repository.recoverOpenSessionsCount.get() == 1) { "reconnect did not recover open sessions" }
                check(repository.openSessionCount(useDayId) == 0) { "reconnect left the durable session open" }
            } finally {
                recoveryTracker.onDestroy()
            }
            val recoveryEvaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
            val recoveryBlocker = configureBlocker(
                repository = repository,
                service = recoveryService,
                snapshot = snapshotWithSpentTargetAllowance(),
                observer = { recoveryEvaluations += it }
            )
            try {
                val readsBeforeReconnect = repository.successfulReadCount.get()
                sendWindowEvent(recoveryBlocker, TARGET_PACKAGE)
                check(awaitNonEmpty(recoveryEvaluations)) { "reconnect did not evaluate the durable session" }
                check(!recoveryEvaluations.single().isAllowed) { "reconnect allowed the spent session" }
                check(repository.successfulReadCount.get() > readsBeforeReconnect) { "reconnect did not read durable state" }
                check(awaitNonEmpty(recoveryService.startedActivities)) { "reconnect did not request its warning" }
            } finally {
                recoveryBlocker.onDestroy()
            }
        } finally {
            repository.releaseDecisionRead()
            repository.releaseNotificationRead()
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
        val cancellationObserved = AtomicReference<CancellationException?>()
        val cancellationBoundaryReached = CountDownLatch(1)
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = { evaluation -> evaluations += evaluation }
        ).also {
            it.decisionRequestCancellationObserver = { error ->
                cancellationObserved.set(error)
                cancellationBoundaryReached.countDown()
            }
        }

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
            check(cancellationBoundaryReached.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "CancellationException did not reach the evaluator request boundary"
            }
            check(cancellationObserved.get()?.message == "injected evaluator cancellation") {
                "CancellationException was swallowed or replaced: ${cancellationObserved.get()}"
            }
            check(thrown == null) {
                "nonblocking foreground callback unexpectedly escaped: $thrown"
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
            val schedulerFaultExecuted = CountDownLatch(1)
            if (mode == FaultMode.SCHEDULER_FAILURE) {
                val delegate = FakeWakeScheduler()
                val failOnce = AtomicBoolean(true)
                blocker.wakeScheduler = object : AppRuleWakeScheduler by delegate {
                    override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
                        if (failOnce.compareAndSet(true, false)) {
                            schedulerFaultExecuted.countDown()
                            throw IllegalStateException("injected scheduler failure")
                        }
                        delegate.schedule(key, dueAtWallClockMs, token)
                    }
                }
            }

            val firstEventEvaluationCount = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            if (mode == FaultMode.SCHEDULER_FAILURE &&
                !schedulerFaultExecuted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) {
                return "${mode.name} scheduler fault did not execute"
            }
            if (mode == FaultMode.SCHEDULER_FAILURE) {
                val firstEventFailure = healthyDenialFailure(
                    label = "${mode.name} first event",
                    evaluations = evaluations,
                    service = service,
                    evaluationCountBefore = firstEventEvaluationCount
                )
                if (firstEventFailure.isNotEmpty()) return firstEventFailure
            }
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
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (evaluations.size <= evaluationCountBefore &&
            android.os.SystemClock.elapsedRealtime() < deadlineMs
        ) {
            Thread.yield()
        }
        if (evaluations.size <= evaluationCountBefore) {
            return "$label prevented the next event from reaching the evaluator"
        }
        while (service.startedActivities.isEmpty() &&
            android.os.SystemClock.elapsedRealtime() < deadlineMs
        ) {
            Thread.yield()
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
        wakeScheduler = FakeWakeScheduler()
        screenInteractiveProvider = { true }
        keyguardLockedProvider = { false }
        evaluationResultObserver = observer
        setField(this, "service", service)
        setField(this, "servicePackageName", service.packageName)
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
        val event = AccessibilityFrameworkTestObjects.createEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        try {
            event.packageName = packageName
            blocker.doAppRuleCheck(event)
        } finally {
            AccessibilityFrameworkTestObjects.releaseEvent(event)
        }
    }

    private fun recordingService(): RecordingService = RecordingService().also {
        it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        it.lastBackPressTimeStamp = 0L
    }

    private class RecordingService : BaseBlockingService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()
        val sentBroadcasts = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }

        override fun sendBroadcast(intent: Intent) {
            sentBroadcasts += intent
        }
    }

    private class DestroyRaceRepository : CurrentUseDaySessionRepository {
        val notificationReadStarted = CountDownLatch(1)
        val notificationReadCompleted = CountDownLatch(1)
        val secondReadStarted = CountDownLatch(1)
        val decisionReadCompleted = CountDownLatch(1)
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
                endedAtMs = null
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
                    try { notificationReadGate.await() } finally { notificationReadCompleted.countDown() }
                }
                2 -> {
                    secondReadStarted.countDown()
                    try { decisionReadGate.await() } finally { decisionReadCompleted.countDown() }
                }
            }
            successfulReadCount.incrementAndGet()
            return durableSessions.filter { it.useDayId == useDayId }.toList()
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        val recoverOpenSessionsCount = AtomicInteger(0)

        override suspend fun recoverOpenSessions(useDayId: String) {
            recoverOpenSessionsCount.incrementAndGet()
            synchronized(durableSessions) {
                durableSessions.removeAll(
                    durableSessions.filter { session ->
                        session.useDayId == useDayId && session.endedAtMs == null
                    }.toSet()
                )
            }
        }

        fun openSessionCount(useDayId: String): Int = synchronized(durableSessions) {
            durableSessions.count { it.useDayId == useDayId && it.endedAtMs == null }
        }

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

        fun post(runnable: Runnable): Boolean {
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

    private fun awaitNonNullField(target: Any, name: String): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (getField(target, name) != null) return true
            Thread.yield()
        }
        return getField(target, name) != null
    }

    private fun awaitAtomicZero(counter: AtomicInteger): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (counter.get() == 0) return true
            Thread.yield()
        }
        return counter.get() == 0
    }

    private fun awaitJobCompleted(job: kotlinx.coroutines.Job?): Boolean {
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (job?.isCompleted == true) return true
            Thread.yield()
        }
        return job?.isCompleted == true
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
            (it.name == name || it.name.startsWith("$name-")) &&
                it.parameterTypes.size == args.size
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
