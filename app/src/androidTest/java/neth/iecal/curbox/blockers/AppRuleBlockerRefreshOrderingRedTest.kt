package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.AcceptedRuleRuntimeSnapshot
import neth.iecal.curbox.domain.apprules.AtomicConnectionScopedSourceOrderSequencer
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.services.BaseBlockingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Deterministic AR010 regression for source-time runtime publication ordering. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerRefreshOrderingRedTest {
    @Test
    fun latestRefreshCannotBeFollowedByDelayedStaleSettingsPublication() = runBlocking {
        val blocker = AppRuleBlocker()
        val service = RecordingService().also {
            it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
            it.lastBackPressTimeStamp = 0L
        }
        val repository = EmptySessionRepository()
        val snapshotCoordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        val refreshMutex = getField(blocker, "refreshMutex") as Mutex
        val lifecycleGeneration = getField(blocker, "lifecycleGeneration") as AtomicLong
        val recheckGeneration = getField(blocker, "recheckGeneration") as AtomicLong
        val timeline = CopyOnWriteArrayList<String>()
        val visibleChecks = CopyOnWriteArrayList<AppRulesEvaluation>()
        val initialSnapshot = snapshot(allowedMinutes = 1L, ruleName = "initial")
        val staleSnapshot = snapshot(allowedMinutes = 0L, ruleName = "stale")
        val latestSnapshot = snapshot(allowedMinutes = 10L, ruleName = "latest")
        val sourceOrderSequencer = AtomicConnectionScopedSourceOrderSequencer()

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        lifecycleGeneration.set(1L)
        setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE))
        setField(blocker, "currentForegroundPackage", TARGET_PACKAGE)
        setField(blocker, "sourceOrderSequencer", sourceOrderSequencer)
        blocker.wallClockMsProvider = { NOW_MS }
        blocker.applicationWindowSnapshotProvider = {
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
        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        blocker.evaluationResultObserver = { evaluation -> visibleChecks += evaluation }
        snapshotCoordinator.accept(initialSnapshot)
        invokePrivate(blocker, "createDecisionWorker", 1L)

        val staleEmissionReceived = CompletableDeferred<Unit>()
        val releaseStaleEmission = CompletableDeferred<Unit>()
        val staleReservation = sourceOrderSequencer.reserveRuntimePublication()
        val staleEmission = launch(Dispatchers.Default) {
            timeline += "stale settings emission received"
            staleEmissionReceived.complete(Unit)
            releaseStaleEmission.await()
            publishSharedPublicationPath(
                blocker = blocker,
                refreshMutex = refreshMutex,
                source = "stale settings emission",
                settings = Settings(appRuleSnapshot = staleSnapshot),
                sourceOrderIdentity = staleReservation.sourceOrderIdentity,
                runtimeRevision = staleReservation.runtimeRevision,
                timeline = timeline
            )
        }

        try {
            staleEmissionReceived.await()
            timeline += "latest refresh starts after stale emission is delayed"
            val latestReservation = sourceOrderSequencer.reserveRuntimePublication()
            async(Dispatchers.Default) {
                publishSharedPublicationPath(
                    blocker = blocker,
                    refreshMutex = refreshMutex,
                    source = "latest refresh",
                    settings = Settings(appRuleSnapshot = latestSnapshot),
                    sourceOrderIdentity = latestReservation.sourceOrderIdentity,
                    runtimeRevision = latestReservation.runtimeRevision,
                    timeline = timeline
                )
            }.await()

            val snapshotAfterLatestRefresh = snapshotCoordinator.snapshot()
            val generationAfterLatestRefresh = recheckGeneration.get()
            if (snapshotAfterLatestRefresh != latestSnapshot.normalized()) {
                throw AssertionError(
                    "latest refresh did not publish before stale emission; timeline=$timeline"
                )
            }
            if (generationAfterLatestRefresh <= 0L) {
                throw AssertionError(
                    "latest refresh did not advance the runtime generation; timeline=$timeline"
                )
            }
            timeline += "latest refresh published generation=$generationAfterLatestRefresh"

            releaseStaleEmission.complete(Unit)
            staleEmission.join()
            val finalSnapshot = snapshotCoordinator.snapshot()
            val finalGeneration = recheckGeneration.get()
            timeline += "stale emission published final generation=$finalGeneration"

            // The production receiver and settings collector both post this same reconciliation
            // after their shared publication critical section. Invoke the real reconciliation
            // method synchronously so the result is deterministic and observable here.
            invokePrivate(blocker, "checkCurrentlyVisibleApplications")
            assertTrue(
                "visible reconciliation did not reach the worker outcome",
                awaitCondition { visibleChecks.size == 1 }
            )
            val acceptedRuntime = acceptedRuntime(blocker)
            assertEquals(latestReservation.runtimeRevision, acceptedRuntime.runtimeRevision)
            assertEquals(
                1L,
                getField(getField(blocker, "decisionWorker")!!, "currentLifecycleGeneration")
            )
            timeline += "visible reconciliation allowed=" +
                visibleChecks.singleOrNull()?.isAllowed +
                " warningActivities=${service.startedActivities.size}"

            val failures = mutableListOf<String>()
            if (finalSnapshot != latestSnapshot.normalized()) {
                failures += "final snapshot regressed to ${snapshotLabel(finalSnapshot)}"
            }
            if (finalGeneration != generationAfterLatestRefresh) {
                failures += "final generation $finalGeneration differs from latest generation " +
                    generationAfterLatestRefresh
            }
            if (lifecycleGeneration.get() != 1L) {
                failures += "lifecycle generation changed to ${lifecycleGeneration.get()}"
            }
            if (visibleChecks.size != 1) {
                failures += "expected one visible check, observed ${visibleChecks.size}"
            }
            if (visibleChecks.singleOrNull()?.isAllowed != true) {
                failures += "visible reconciliation used stale denial instead of latest allow"
            }
            if (service.startedActivities.isNotEmpty()) {
                failures += "visible user outcome launched ${service.startedActivities.size} " +
                    "warning activity instead of allowing the latest snapshot"
            }

            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "AR010 deterministic interleaving reproduced:\n" +
                        failures.joinToString(separator = "\n") { "- $it" } +
                        "\nforced timeline:\n" +
                        timeline.joinToString(separator = "\n") { "- $it" }
                )
            }

            visibleChecks.clear()
            val oldEvaluationStarted = CompletableDeferred<Unit>()
            val releaseOldEvaluation = CompletableDeferred<Unit>()
            val blockFirstEvaluation = AtomicBoolean(true)
            val acceptReplacementEvaluation = AtomicBoolean(false)
            val visibleReconciliations = CopyOnWriteArrayList<Runnable>()
            blocker.visibleApplicationCheckPostDelayed = { runnable, _ ->
                visibleReconciliations += runnable
                true
            }
            blocker.evaluationResultObserver = { evaluation ->
                timeline += "visible evaluation callback allowed=${evaluation.isAllowed} " +
                    "latest=${getField(blocker, "latestRuntimeRevision")} " +
                    "worker=${acceptedRuntime(blocker).runtimeRevision}"
                if (blockFirstEvaluation.compareAndSet(true, false)) {
                    oldEvaluationStarted.complete(Unit)
                    runBlocking { releaseOldEvaluation.await() }
                }
                if (acceptReplacementEvaluation.get()) {
                    visibleChecks += evaluation
                }
            }
            invokePrivate(blocker, "checkCurrentlyVisibleApplications")
            assertTrue(
                "old visible evaluation did not enter the deterministic interleaving",
                awaitCondition { oldEvaluationStarted.isCompleted }
            )

            val noOpReservation = sourceOrderSequencer.reserveRuntimePublication()
            val noOpAccepted = publishSharedPublicationPath(
                blocker = blocker,
                refreshMutex = refreshMutex,
                source = "unrelated settings emission",
                settings = Settings(appRuleSnapshot = latestSnapshot),
                sourceOrderIdentity = noOpReservation.sourceOrderIdentity,
                runtimeRevision = noOpReservation.runtimeRevision,
                timeline = timeline
            )
            assertTrue("the no-op settings emission was not accepted", noOpAccepted)
            invokePrivate(
                blocker,
                "postVisibleApplicationCheck",
                0L,
                1L,
                ObservationKind.REFRESH
            )
            assertEquals(1, visibleReconciliations.size)
            timeline += "replacement visible reconciliation posted"
            releaseOldEvaluation.complete(Unit)
            timeline += "old visible evaluation released"
            assertTrue(
                "the no-op runtime publication did not reach the serialized worker: " +
                    "timeline=$timeline",
                awaitCondition {
                    acceptedRuntime(blocker).runtimeRevision == noOpReservation.runtimeRevision
                }
            )
            acceptReplacementEvaluation.set(true)
            visibleReconciliations.single().run()

            assertTrue(
                "accepted no-op revision lost the visible replacement evaluation: " +
                    "observed=${visibleChecks.size}, activities=${service.startedActivities.size}, " +
                    "timeline=$timeline",
                awaitCondition { visibleChecks.size == 1 }
            )
            assertEquals(noOpReservation.runtimeRevision, acceptedRuntime(blocker).runtimeRevision)
            assertEquals(
                1L,
                getField(getField(blocker, "decisionWorker")!!, "currentLifecycleGeneration")
            )
            assertTrue(
                "the replacement visible evaluation must use the unchanged allow result",
                visibleChecks.single().isAllowed
            )
            assertEquals(latestSnapshot.normalized(), snapshotCoordinator.snapshot())
            assertEquals(generationAfterLatestRefresh, recheckGeneration.get())
            assertEquals(
                latestSnapshot.normalized(),
                acceptedRuntime(blocker).runtime.snapshot
            )
            assertTrue(
                "a no-op superseding revision must not show a warning",
                service.startedActivities.isEmpty()
            )
        } finally {
            releaseStaleEmission.complete(Unit)
            staleEmission.join()
            blocker.onDestroy()
        }
    }

    /**
     * Both production publication endpoints converge on this exact critical section:
     * `refreshMutex.withLock { applySettingsSnapshot(...); submitRuntimePublication(...) }`.
     * Each source allocates its reservation before entering this section, and the visible
     * reconciliation is posted after the lock.
     */
    private suspend fun publishSharedPublicationPath(
        blocker: AppRuleBlocker,
        refreshMutex: Mutex,
        source: String,
        settings: Settings,
        sourceOrderIdentity: SourceOrderIdentity,
        runtimeRevision: RuntimeRevision,
        timeline: MutableList<String>
    ): Boolean = refreshMutex.withLock {
        timeline += "$source observed source=${sourceOrderIdentity.value} " +
            "revision=${runtimeRevision.value}"
        timeline += "$source acquired publication lock revision=${runtimeRevision.value}"
        val accepted = invokePrivateResult(
            blocker,
            "applyAndSubmitRuntimePublication",
            1L,
            settings,
            sourceOrderIdentity.value,
            runtimeRevision.value
        ) as Boolean
        timeline += "$source applied and submitted accepted=$accepted"
        accepted
    }

    private fun acceptedRuntime(blocker: AppRuleBlocker): AcceptedRuleRuntimeSnapshot {
        val worker = getField(blocker, "decisionWorker")
            ?: error("runtime publication did not create a decision worker")
        return getField(worker, "currentAcceptedRuntime") as AcceptedRuleRuntimeSnapshot
    }

    private fun snapshot(allowedMinutes: Long, ruleName: String): AppRuleSnapshot {
        val group = AppRuleAppGroup(
            id = GROUP_ID,
            name = "Target",
            selectedPackages = listOf(TARGET_PACKAGE)
        )
        return AppRuleSnapshot(
            appGroups = listOf(group),
            appRules = listOf(
                AppRule(
                    id = RULE_ID,
                    name = ruleName,
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(GROUP_ID),
                    allowedMinutes = allowedMinutes
                )
            )
        )
    }

    private fun snapshotLabel(snapshot: AppRuleSnapshot): String =
        snapshot.appRules.singleOrNull()?.name ?: "<empty>"

    private class RecordingService : BaseBlockingService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }

        override fun getSystemService(name: String): Any? = null

        override fun getWindows(): MutableList<AccessibilityWindowInfo> = mutableListOf()
    }

    private class EmptySessionRepository : CurrentUseDaySessionRepository {
        override suspend fun startSession(
            useDayId: String,
            packageName: String,
            startedAtMs: Long
        ): Long = 1L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
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

    private fun invokePrivateResult(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first {
            (it.name == name || it.name.startsWith("$name-")) &&
                it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline && !condition()) {
            Thread.yield()
        }
        return condition()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.reader"
        const val GROUP_ID = "target-group"
        const val RULE_ID = "target-rule"
        const val NOW_MS = 1_756_642_800_000L
        const val WAIT_TIMEOUT_MS = 2_000L
    }
}
