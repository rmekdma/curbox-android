package neth.iecal.curbox.blockers

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
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
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.ForegroundUsageCheckpoint
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.AppUsageTracker
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
    fun sameHostSetupDestroySetupPublishesOnlyLatestGeneration() {
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
        val evaluationGenerations = CopyOnWriteArrayList<Long>()
        val evaluationAllowed = AtomicReference<Boolean>()
        val notificationGenerations = CopyOnWriteArrayList<Long>()
        val firstEvaluation = AtomicBoolean(true)
        val firstNotification = AtomicBoolean(true)
        val oldEvaluationEntered = CountDownLatch(1)
        val oldEvaluationCompleted = CountDownLatch(1)
        val oldEvaluationRelease = CountDownLatch(1)
        val oldNotificationEntered = CountDownLatch(1)
        val oldNotificationCompleted = CountDownLatch(1)
        val oldNotificationRelease = CountDownLatch(1)
        val latestEvaluation = CountDownLatch(1)

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
            val generation = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()
            evaluationGenerations += generation
            evaluationAllowed.set(evaluation.isAllowed)
            if (firstEvaluation.compareAndSet(true, false)) {
                oldEvaluationEntered.countDown()
                try {
                    oldEvaluationRelease.await()
                } finally {
                    oldEvaluationCompleted.countDown()
                }
            } else {
                latestEvaluation.countDown()
            }
        }
        blocker.notificationPostObserver = { model ->
            val generation = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()
            if (firstNotification.compareAndSet(true, false)) {
                oldNotificationEntered.countDown()
                try {
                    oldNotificationRelease.await()
                } finally {
                    oldNotificationCompleted.countDown()
                }
            }
        }
        blocker.notificationUpdateObserver = {
            val generation = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()
            notificationGenerations += generation
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
            val firstCallback = checkNotNull(postedCallbacks.firstOrNull()) {
                "first real-host setup did not post a visible handler callback"
            }
            // Use the real accessibility event as the first-generation trigger; retain the
            // setup-posted callback so it can be delivered as stale work after reconnect.
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(oldEvaluationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "first real-host setup did not reach evaluator publication"
            }
            val readsBeforeDestroy = applicationWindowReads.get()

            blocker.onDestroy()
            check(oldEvaluationCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "destroy did not complete the cancelled old evaluator callback"
            }
            check(oldNotificationCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "destroy did not complete the cancelled old notification callback"
            }
            oldEvaluationRelease.countDown()
            oldNotificationRelease.countDown()

            blocker.setup(service)
            blocker.setupReceivers()
            installDeterministicRuntime()
            val latestGeneration = (getField(blocker, "lifecycleGeneration") as AtomicLong).get()

            // The first setup's callback is now stale. It must not perform another framework
            // window read or enqueue a decision into the latest worker.
            firstCallback.run()
            check(applicationWindowReads.get() == readsBeforeDestroy) {
                "stale first-generation handler performed a window read"
            }

            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(latestEvaluation.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "latest real-host setup did not publish an evaluator result"
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            check(evaluationGenerations.last() == latestGeneration) {
                "latest evaluator publication used a stale generation: $evaluationGenerations"
            }
            check(notificationGenerations.isNotEmpty()) {
                "latest real-host setup did not publish a notification"
            }
            check(notificationGenerations.all { it == latestGeneration }) {
                "notification publication escaped the latest generation: $notificationGenerations"
            }
            check(awaitNonEmpty(service.startedActivities)) {
                "latest real-host setup did not publish its warning; allowed=" +
                    evaluationAllowed.get()
            }
        } finally {
            blocker.onDestroy()
            runCatching {
                runBlocking {
                    service.dataStoreManager.updateAppRuleSnapshot(originalSnapshot)
                }
            }
        }
    }

    @Test
    fun ticket15MeasuresBoundedDestroyAndReconnectsOnlyTheLatestGeneration() {
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
        val decisionThread = AtomicReference<Thread?>(null)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluation -> evaluations += evaluation }
        ).apply {
            notificationPostObserver = {}
            notificationUpdateObserver = {
                notificationUpdateEntered.countDown()
                try {
                    notificationUpdateRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } finally {
                    notificationUpdateCompleted.countDown()
                }
            }
            notificationPublicationObserver = { model -> notificationPostings += model }
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
        // The candidate deadline is measured by an injected monotonic clock. Every read advances
        // one deterministic tick, so the assertion does not use a wall-clock tolerance.
        val injectedElapsedMs = AtomicLong(0L)
        blocker.elapsedRealtimeMsProvider = { injectedElapsedMs.getAndAdd(10L) }
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
        repository.releaseNotificationRead()
        check(notificationUpdateEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "notification did not reach its final publication boundary"
        }
        check(refreshStarted) { "refresh did not enter the tracked lifecycle work set" }

        val measurement = blocker.onDestroyForMeasurement(totalDrainBudgetMs = 150L)
        check(measurement.deadlineElapsedMs - measurement.requestedAtElapsedMs == 150L) {
            "candidate measurement did not use the injected absolute deadline"
        }
        check(measurement.completedAtElapsedMs >= measurement.deadlineElapsedMs) {
            "candidate timeout did not reach its injected absolute deadline"
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
        callbackRelease.countDown()
        notificationUpdateRelease.countDown()
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
        check(notificationUpdateCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "notification publication barrier did not complete after destroy"
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
            sendWindowEvent(recoveryBlocker, TARGET_PACKAGE)
            check(recoveryEvaluated.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "reconnect did not publish the new generation evaluation"
            }
            check(recoveryEvaluations.single().isAllowed.not()) {
                "reconnect did not evaluate the durable session as denied: " +
                    recoveryEvaluations.single()
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
            setField(blocker, "foregroundObservationSource", null)
            providerRelease.countDown()
            providerThread.join(WAIT_TIMEOUT_MS)
            check(!providerThread.isAlive) { "stale application-window provider remained blocked" }

            val cache = getField(blocker, "applicationWindowProvenanceCache")
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
    fun staleScheduledProviderCannotRepopulateCacheAfterReconnect() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val queuedRechecks = CopyOnWriteArrayList<Runnable>()
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
            recheckPostDelayed = { runnable, _ ->
                queuedRechecks += runnable
                true
            }
            recheckRemoveCallback = {}
            visibleApplicationCheckPostDelayed = { _, _ -> true }
            visibleApplicationCheckRemoveCallbacks = {}
        }
        val recheckFailure = AtomicReference<Throwable?>()
        val recheckThread = Thread(
            {
                try {
                    queuedRechecks.removeAt(0).run()
                } catch (error: Throwable) {
                    recheckFailure.set(error)
                }
            },
            "ticket15-stale-scheduled-provider"
        )
        try {
            invokePrivate(blocker, "scheduleRecheck", TARGET_PACKAGE, 0L, 20_000L, 0L)
            check(queuedRechecks.size == 1) {
                "scheduled recheck did not reach the deterministic queue"
            }
            recheckThread.start()
            check(providerEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "scheduled provider did not reach its reconnect barrier"
            }

            blocker.onDestroy()
            blocker.setup(service)
            setField(blocker, "foregroundObservationSource", null)
            providerRelease.countDown()
            recheckThread.join(WAIT_TIMEOUT_MS)
            check(!recheckThread.isAlive) { "stale scheduled provider remained blocked" }
            recheckFailure.get()?.let { throw it }

            val cache = getField(blocker, "applicationWindowProvenanceCache")
            check(getField(cache!!, "cached") == null) {
                "stale scheduled provider repopulated application provenance after reconnect"
            }
        } finally {
            providerRelease.countDown()
            recheckThread.join(WAIT_TIMEOUT_MS)
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
            notificationBeforeFrameworkCallObserver = { model ->
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
            check(awaitAtomicZero(getField(blocker, "inFlightNotifications") as AtomicInteger)) {
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
            check(awaitAtomicZero(getField(blocker, "inFlightNotifications") as AtomicInteger)) {
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
    fun scheduledRecheckPostCannotStartAfterDestroyAtItsFinalBarrier() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val recheckEntered = CountDownLatch(1)
        val recheckRelease = CountDownLatch(1)
        val registrations = AtomicInteger(0)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            recheckBeforeFrameworkPostObserver = {
                recheckEntered.countDown()
                recheckRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            recheckPostDelayed = { _, _ ->
                registrations.incrementAndGet()
                true
            }
            recheckRemoveCallback = {}
        }
        val recheckThread = Thread(
            {
                invokePrivate(
                    blocker,
                    "scheduleRecheck",
                    TARGET_PACKAGE,
                    10_000L,
                    20_000L,
                    0L
                )
            },
            "ticket15-recheck-final-call"
        )
        try {
            recheckThread.start()
            check(recheckEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "scheduled recheck did not reach its final framework-call barrier"
            }

            blocker.onDestroy()
            recheckRelease.countDown()
            recheckThread.join(WAIT_TIMEOUT_MS)
            check(!recheckThread.isAlive) { "scheduled recheck remained blocked after release" }
            check(registrations.get() == 0) {
                "scheduled recheck was posted after destroy: ${registrations.get()}"
            }
        } finally {
            recheckRelease.countDown()
            recheckThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
        }
    }

    @Test
    fun rearmedRecoveryHandlerPostCannotStartAfterDestroyAtItsFinalBarrier() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val rearmEntered = CountDownLatch(1)
        val rearmRelease = CountDownLatch(1)
        val recoveryPosts = AtomicInteger(0)
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            recheckPostDelayed = { _, _ -> true }
            recheckRemoveCallback = {}
        }
        try {
            invokePrivate(
                blocker,
                "scheduleRecheck",
                TARGET_PACKAGE,
                10_000L,
                20_000L,
                0L
            )
            val scheduled = getField(blocker, "scheduledRechecks") as Map<*, *>
            val registration = checkNotNull(scheduled[TARGET_PACKAGE]) {
                "primary recheck registration was not created"
            }
            val registrationToken = getField(registration, "registrationToken") as Long
            blocker.recheckRecoveryPostDelayed = { _, _ ->
                recoveryPosts.incrementAndGet()
                true
            }
            blocker.recheckBeforeFrameworkPostObserver = {
                rearmEntered.countDown()
                rearmRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            val rearmThread = Thread(
                {
                    invokePrivate(
                        blocker,
                        "rearmScheduledPost",
                        TARGET_PACKAGE,
                        0L,
                        0,
                        10_000L,
                        20_000L,
                        null,
                        null,
                        registrationToken,
                        null
                    )
                },
                "ticket15-rearm-handler-final-call"
            )
            rearmThread.start()
            check(rearmEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "rearmed recovery post did not reach its final framework-call barrier"
            }

            blocker.onDestroy()
            rearmRelease.countDown()
            rearmThread.join(WAIT_TIMEOUT_MS)
            check(!rearmThread.isAlive) { "rearmed recovery post remained blocked after release" }
            check(recoveryPosts.get() == 0) {
                "rearmed recovery handler was posted after destroy: ${recoveryPosts.get()}"
            }
        } finally {
            rearmRelease.countDown()
            blocker.onDestroy()
        }
    }

    @Test
    fun alarmPostCannotStartAfterDestroyAtItsFinalBarrier() {
        val repository = DestroyRaceRepository()
        val service = recordingService()
        val alarmEntered = CountDownLatch(1)
        val alarmRelease = CountDownLatch(1)
        val alarmPublications = CopyOnWriteArrayList<String>()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithGlobalDeny(),
            observer = {}
        ).apply {
            alarmBeforeFrameworkCallObserver = {
                alarmEntered.countDown()
                alarmRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            alarmPublicationObserver = { alarmPublications += it }
            recheckRemoveCallback = {}
        }
        val alarmThread = Thread(
            {
                invokePrivate(
                    blocker,
                    "scheduleRecheck",
                    TARGET_PACKAGE,
                    10_000L,
                    20_000L,
                    0L
                )
            },
            "ticket15-alarm-final-call"
        )
        try {
            alarmThread.start()
            check(alarmEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "alarm publication did not reach its final framework-call barrier"
            }

            blocker.onDestroy()
            alarmRelease.countDown()
            alarmThread.join(WAIT_TIMEOUT_MS)
            check(!alarmThread.isAlive) { "alarm publication remained blocked after release" }
            check(alarmPublications.isEmpty()) {
                "alarm publication escaped destroy: $alarmPublications"
            }
        } finally {
            alarmRelease.countDown()
            alarmThread.join(WAIT_TIMEOUT_MS)
            blocker.onDestroy()
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
            check(awaitAtomicZero(getField(blocker, "inFlightUsageResetCompletions") as AtomicInteger)) {
                "usage-reset completion did not drain after destroy"
            }
            check(awaitAtomicZero(getField(blocker, "inFlightCallbacks") as AtomicInteger)) {
                "usage-reset completion leaked into the aggregate callback drain"
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
            recheckPlanDeliveryObserver = {
                planEntered.countDown()
                planRelease.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            recheckPostDelayed = { _, _ ->
                registrations.incrementAndGet()
                true
            }
            recheckRemoveCallback = {}
        }
        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            repository.releaseNotificationRead()
            repository.releaseDecisionRead()
            check(planEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "worker-generated recheck plan did not reach its delivery barrier"
            }

            blocker.onDestroy()
            planRelease.countDown()
            check(awaitAtomicZero(getField(blocker, "inFlightRecheckPlans") as AtomicInteger)) {
                "recheck-plan delivery did not drain after destroy"
            }
            check(registrations.get() == 0) {
                "worker-generated recheck plan registered after destroy: ${registrations.get()}"
            }
        } finally {
            planRelease.countDown()
            blocker.onDestroy()
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
                startedAtMs = nowMs - SESSION_DURATION_MS - 1_000L
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
                try {
                    evaluatorRelease.await()
                    evaluations += evaluation
                } finally {
                    evaluatorCompleted.countDown()
                }
            }
        ).apply {
            notificationPostObserver = { model ->
                notificationPostEntered.countDown()
                try {
                    notificationPostRelease.await()
                } finally {
                    notificationPostCompleted.countDown()
                }
            }
            notificationUpdateObserver = { model -> notificationPostings += model }
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
                if (!awaitNonEmpty(recoveryEvaluations)) {
                    failures += "reconnect did not publish a fresh durable evaluation"
                } else if (recoveryEvaluations.single().isAllowed) {
                    failures += "reconnect did not evaluate the durable session as denied: " +
                        recoveryEvaluations.single()
                }
                if (!awaitNonEmpty(recoveryService.startedActivities)) {
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

            val firstEventEvaluationCount = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
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
