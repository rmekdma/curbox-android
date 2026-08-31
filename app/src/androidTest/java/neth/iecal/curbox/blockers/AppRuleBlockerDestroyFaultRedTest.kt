package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Test
import org.junit.runner.RunWith
import java.util.AbstractList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Phase 0 RED contracts for destroy drain and fault continuation. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerDestroyFaultRedTest {
    @Test
    fun inFlightDecisionDestroyHasNoPostDestroySideEffectsAndReconnectRecoversDurableState() {
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val durableSessions = listOf(
            ForegroundSession(
                id = 1L,
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = nowMs - SESSION_DURATION_MS,
                endedAtMs = nowMs
            )
        )
        val repository = DestroyRaceRepository(durableSessions)
        val scheduler = RecordingScheduler()
        val deferredHandlerCallbacks = CopyOnWriteArrayList<Runnable>()
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val service = recordingService()
        val blocker = configureBlocker(
            repository = repository,
            service = service,
            snapshot = snapshotWithSpentTargetAllowance(),
            observer = { evaluation -> evaluations += evaluation }
        ).apply {
            recheckPostDelayed = scheduler::post
            recheckRemoveCallback = scheduler::remove
            visibleApplicationCheckPostDelayed = { callback, _ ->
                deferredHandlerCallbacks += callback
                true
            }
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

        try {
            // Start a notification read before the foreground decision so both async publication
            // and the decision path are still in flight when destroy begins.
            blocker.updateLiveNotification(TARGET_PACKAGE)
            check(
                repository.notificationReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) { "notification worker did not reach the persistence gate" }

            invokePrivate(blocker, "scheduleRecheck", TARGET_PACKAGE, 10_000L, 20_000L, 0L)
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            check(scheduler.pendingCount == 1) { "the handler recheck was not scheduled" }
            check(deferredHandlerCallbacks.size == 1) {
                "the visible handler callback was not captured"
            }

            eventThread.start()
            check(
                repository.secondReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) { "the in-flight decision did not reach the persistence gate" }

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
            val destroyCompletedWithinBudget = destroyReturned.await(
                DESTROY_DRAIN_BUDGET_MS,
                TimeUnit.MILLISECONDS
            )
            val destroyElapsedMs =
                android.os.SystemClock.elapsedRealtime() - destroyStartedAtMs

            val failures = mutableListOf<String>()
            if (!destroyCompletedWithinBudget) {
                failures += "destroy did not return within the bounded drain budget"
            }
            if (destroyElapsedMs > DESTROY_DRAIN_BUDGET_MS) {
                failures += "destroy exceeded the bounded drain budget: ${destroyElapsedMs}ms"
            }
            if (scheduler.pendingCount != 0) {
                failures += "destroy left ${scheduler.pendingCount} handler callbacks queued"
            }

            // Release the durable read only after teardown. Any observer, warning, notification
            // publication, or handler callback after this point is a post-destroy side effect.
            repository.releaseReads()
            if (!checkReturned.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                failures += "the in-flight decision did not drain after persistence was released"
            }
            eventThread.join(WAIT_TIMEOUT_MS)
            if (eventThread.isAlive) failures += "the in-flight decision thread remained alive"

            val evaluationsBeforeManualCallbacks = evaluations.size
            val activitiesBeforeManualCallbacks = service.startedActivities.size
            scheduler.runPending()
            deferredHandlerCallbacks.forEach(Runnable::run)
            if (evaluations.size != evaluationsBeforeManualCallbacks) {
                failures += "a queued handler callback evaluated after destroy"
            }
            if (service.startedActivities.size != activitiesBeforeManualCallbacks) {
                failures += "a queued handler callback launched a warning after destroy"
            }
            if (evaluations.isNotEmpty()) {
                failures += "evaluator publication occurred after destroy: ${evaluations.size}"
            }
            if (service.startedActivities.isNotEmpty()) {
                failures += "warning activity launched after destroy"
            }
            if (getField(blocker, "lastPostedNotificationModel") != null) {
                failures += "notification model was published after destroy"
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
            repository.releaseReads()
            if (eventThread.isAlive) {
                eventThread.interrupt()
                eventThread.join(WAIT_TIMEOUT_MS)
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
    fun cancellationExceptionPropagatesWhileHealthyNextEventRemainsPossible() {
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
            check(thrown is CancellationException) {
                "CancellationException was swallowed or replaced: $thrown"
            }
            check(evaluations.isEmpty()) {
                "cancelled evaluation unexpectedly published a result"
            }

            repository.mode = FaultMode.HEALTHY
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(evaluations.singleOrNull()?.isAllowed == false) {
                "a later healthy event did not produce the expected denial"
            }
            check(service.startedActivities.isNotEmpty()) {
                "a later healthy event did not launch the expected warning"
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
                blocker.recheckPostDelayed = { _, _ ->
                    throw IllegalStateException("injected scheduler failure")
                }
                invokePrivate(blocker, "scheduleRecheck", TARGET_PACKAGE, 1_000L, 20_000L, 0L)
            }

            sendWindowEvent(blocker, TARGET_PACKAGE)
            val firstFailure = when (mode) {
                FaultMode.PERSISTENCE_FAILURE,
                FaultMode.EVALUATOR_FAILURE -> evaluations.isNotEmpty() ||
                    service.startedActivities.isNotEmpty()
                FaultMode.SCHEDULER_FAILURE -> evaluations.singleOrNull()?.isAllowed != false ||
                    service.startedActivities.isEmpty()
                else -> false
            }
            if (firstFailure) {
                return "${mode.name} did not preserve the expected first-event outcome"
            }

            repository.mode = FaultMode.HEALTHY
            val beforeHealthyEvent = evaluations.size
            sendWindowEvent(blocker, TARGET_PACKAGE)
            if (evaluations.size <= beforeHealthyEvent) {
                return "${mode.name} prevented the next event from reaching the evaluator"
            }
            if (evaluations.last().isAllowed || service.startedActivities.isEmpty()) {
                return "${mode.name} next event did not produce the expected denial warning"
            }
            ""
        } catch (error: Throwable) {
            "${mode.name} escaped the service boundary: $error"
        } finally {
            blocker.onDestroy()
        }
    }

    private fun verifyCallbackPostContinuation(): String {
        val repository = FaultRepository(FaultMode.HEALTHY)
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
                throw IllegalStateException("injected handler callback post failure")
            }
            invokePrivate(blocker, "postVisibleApplicationCheck", 0L, 0L)
            blocker.visibleApplicationCheckPostDelayed = { _, _ -> true }
            sendWindowEvent(blocker, TARGET_PACKAGE)
            if (evaluations.singleOrNull()?.isAllowed != false || service.startedActivities.isEmpty()) {
                "callback post failure prevented the next event's expected denial"
            } else {
                ""
            }
        } catch (error: Throwable) {
            "callback post failure escaped the service boundary: $error"
        } finally {
            blocker.onDestroy()
        }
    }

    private fun verifyNotificationWorkerContinuation(): String {
        val repository = FaultRepository(FaultMode.WORKER_FAILURE)
        val workerReadStarted = CountDownLatch(1)
        repository.readObserver = { workerReadStarted.countDown() }
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
            if (!workerReadStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "notification worker did not reach the injected evaluator fault"
            }
            repository.mode = FaultMode.HEALTHY
            sendWindowEvent(blocker, TARGET_PACKAGE)
            if (evaluations.singleOrNull()?.isAllowed != false || service.startedActivities.isEmpty()) {
                "notification worker failure prevented the next event's expected denial"
            } else {
                ""
            }
        } catch (error: Throwable) {
            "notification worker failure escaped the service boundary: $error"
        } finally {
            blocker.onDestroy()
        }
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

    private class DestroyRaceRepository(
        private val durableSessions: List<ForegroundSession>
    ) : CurrentUseDaySessionRepository {
        val notificationReadStarted = CountDownLatch(1)
        val secondReadStarted = CountDownLatch(1)
        val successfulReadCount = AtomicInteger(0)
        private val readCount = AtomicInteger(0)
        private val releaseGate = CountDownLatch(1)

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            when (readCount.incrementAndGet()) {
                1 -> notificationReadStarted.countDown()
                2 -> secondReadStarted.countDown()
            }
            releaseGate.await()
            successfulReadCount.incrementAndGet()
            return durableSessions.filter { it.useDayId == useDayId }
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun releaseReads() {
            releaseGate.countDown()
        }
    }

    private enum class FaultMode {
        HEALTHY,
        PERSISTENCE_FAILURE,
        EVALUATOR_FAILURE,
        SCHEDULER_FAILURE,
        WORKER_FAILURE,
        CANCELLATION
    }

    private class FaultRepository(
        @Volatile var mode: FaultMode,
        var readObserver: (() -> Unit)? = null
    ) : CurrentUseDaySessionRepository {
        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            readObserver?.invoke()
            return when (mode) {
                FaultMode.PERSISTENCE_FAILURE ->
                    throw IllegalStateException("injected persistence failure")
                FaultMode.EVALUATOR_FAILURE,
                FaultMode.WORKER_FAILURE -> ThrowingSessionList()
                FaultMode.CANCELLATION ->
                    throw CancellationException("injected cancellation")
                FaultMode.HEALTHY,
                FaultMode.SCHEDULER_FAILURE -> emptyList()
            }
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private class ThrowingSessionList : AbstractList<ForegroundSession>() {
        override val size: Int get() = 1

        override fun get(index: Int): ForegroundSession =
            error("the evaluator iterator is intentionally unavailable")

        override fun iterator(): MutableIterator<ForegroundSession> =
            error("the evaluator iterator is intentionally unavailable")
    }

    private class RecordingScheduler {
        private val pending = CopyOnWriteArrayList<Runnable>()

        val pendingCount: Int get() = pending.size

        fun post(runnable: Runnable, delayMillis: Long): Boolean {
            pending += runnable
            return true
        }

        fun remove(runnable: Runnable) {
            pending.remove(runnable)
        }

        fun runPending() {
            pending.toList().forEach { runnable ->
                if (pending.remove(runnable)) runnable.run()
            }
        }
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
        const val DESTROY_DRAIN_BUDGET_MS = 1_000L
        const val SESSION_DURATION_MS = 60_000L
    }
}
