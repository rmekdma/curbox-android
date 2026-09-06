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
import neth.iecal.curbox.domain.apprules.ActiveRootFact
import neth.iecal.curbox.domain.apprules.AcceptedRuleRuntimeSnapshot
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFact
import neth.iecal.curbox.domain.apprules.AtomicConnectionScopedSourceOrderSequencer
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.DecisionRequest
import neth.iecal.curbox.domain.apprules.DisplayState
import neth.iecal.curbox.domain.apprules.ForegroundFacts
import neth.iecal.curbox.domain.apprules.ForegroundReadState
import neth.iecal.curbox.domain.apprules.LifecycleGeneration
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.RecoveryOnlyStop
import neth.iecal.curbox.domain.apprules.RuntimePublication
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SerializedDecisionWorker
import neth.iecal.curbox.domain.apprules.SignalFact
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.domain.apprules.StopReason
import neth.iecal.curbox.domain.apprules.SubmissionResult
import neth.iecal.curbox.services.BaseBlockingService
import org.junit.Assert.assertFalse
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

    @Test
    fun replacementWorkerInheritsPublishedRuntimeAndPublishesItsEvaluation() = runBlocking {
        val initialSnapshot = snapshot(allowedMinutes = 0L, ruleName = "initial")
        val publishedSnapshot = snapshot(allowedMinutes = 10L, ruleName = "published")
        val fixture = createProductionHandoffFixture(initialSnapshot)
        try {
            val reservation = fixture.sourceOrderSequencer.reserveRuntimePublication()
            stopWorker(fixture.blocker)

            val accepted = publishSharedPublicationPath(
                blocker = fixture.blocker,
                refreshMutex = fixture.refreshMutex,
                source = "publication N after worker rejection",
                settings = Settings(appRuleSnapshot = publishedSnapshot),
                sourceOrderIdentity = reservation.sourceOrderIdentity,
                runtimeRevision = reservation.runtimeRevision,
                timeline = fixture.timeline
            )
            assertTrue("publication N was not accepted", accepted)
            // The production refresh receiver posts this reconciliation after publication. Run
            // the same check synchronously so the worker handoff and visible result are observed
            // without relying on a Handler timing window.
            invokePrivate(fixture.blocker, "checkCurrentlyVisibleApplications")
            val replacementPublished = awaitCondition { fixture.visibleChecks.size == 1 }
            assertTrue(
                "replacement did not publish the accepted evaluation: " +
                    "host=${hostRuntimeRevision(fixture.blocker)}, " +
                    "worker=${acceptedRuntime(fixture.blocker).runtimeRevision}, " +
                    "visible=${fixture.visibleChecks.size}, timeline=${fixture.timeline}",
                replacementPublished
            )

            val replacementWorker = getField(fixture.blocker, "decisionWorker")
                as SerializedDecisionWorker
            val acceptedRuntime = acceptedRuntime(fixture.blocker)
            val hostRevision = hostRuntimeRevision(fixture.blocker)
            val hostSnapshot = fixture.snapshotCoordinator.snapshot()
            val hostGeneration = (getField(fixture.blocker, "recheckGeneration") as AtomicLong).get()
            val evaluation = fixture.visibleChecks.singleOrNull()
            val failures = mutableListOf<String>()
            if (hostRevision != reservation.runtimeRevision) {
                failures += "host revision regressed to ${hostRevision.value}"
            }
            if (acceptedRuntime.runtimeRevision != reservation.runtimeRevision) {
                failures += "worker accepted ${acceptedRuntime.runtimeRevision.value}"
            }
            if (acceptedRuntime.runtime.snapshot != publishedSnapshot.normalized()) {
                failures += "worker snapshot is ${snapshotLabel(acceptedRuntime.runtime.snapshot)}"
            }
            if (hostSnapshot != publishedSnapshot.normalized()) {
                failures += "host snapshot is ${snapshotLabel(hostSnapshot)}"
            }
            if (hostGeneration != 1L) {
                failures += "host recheck generation was $hostGeneration"
            }
            if ((getField(replacementWorker, "currentLifecycleGeneration") as Number).toLong() != 1L) {
                failures += "replacement lifecycle generation was not 1"
            }
            if (evaluation?.isAllowed != true) {
                failures += "visible evaluation was ${evaluation?.isAllowed}, expected allow"
            }
            if (fixture.service.startedActivities.isNotEmpty()) {
                failures += "allow result launched a warning activity"
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "production handoff A failed:\n" +
                        failures.joinToString(separator = "\n") { "- $it" } +
                        "\ntimeline:\n" +
                        fixture.timeline.joinToString(separator = "\n") { "- $it" }
                )
            }
        } finally {
            fixture.blocker.onDestroy()
        }
    }

    @Test
    fun delayedOlderPublicationCannotRollbackSameGenerationReplacement() = runBlocking {
        val initialSnapshot = snapshot(allowedMinutes = 1L, ruleName = "initial")
        val staleSnapshot = snapshot(allowedMinutes = 0L, ruleName = "revision1")
        val latestSnapshot = snapshot(allowedMinutes = 10L, ruleName = "revision2")
        val fixture = createProductionHandoffFixture(initialSnapshot)
        try {
            // Reserve revision 1, then deliberately hold its publication while revision 2 wins.
            val revision1 = fixture.sourceOrderSequencer.reserveRuntimePublication()
            val revision2 = fixture.sourceOrderSequencer.reserveRuntimePublication()
            assertTrue(
                publishSharedPublicationPath(
                    blocker = fixture.blocker,
                    refreshMutex = fixture.refreshMutex,
                    source = "revision 2",
                    settings = Settings(appRuleSnapshot = latestSnapshot),
                    sourceOrderIdentity = revision2.sourceOrderIdentity,
                    runtimeRevision = revision2.runtimeRevision,
                    timeline = fixture.timeline
                )
            )
            invokePrivate(fixture.blocker, "checkCurrentlyVisibleApplications")
            val revision2Published = awaitCondition {
                acceptedRuntime(fixture.blocker).runtimeRevision == revision2.runtimeRevision &&
                    fixture.visibleChecks.size == 1
            }
            assertTrue(
                "revision 2 did not publish its allow evaluation: " +
                    "host=${hostRuntimeRevision(fixture.blocker)}, " +
                    "worker=${acceptedRuntime(fixture.blocker).runtimeRevision}, " +
                    "visible=${fixture.visibleChecks.size}, timeline=${fixture.timeline}",
                revision2Published
            )
            assertTrue(fixture.visibleChecks.single().isAllowed)

            // Force same-generation recovery through the real foreground handoff path.
            stopWorker(fixture.blocker)
            val replacementRequest = foregroundRequest(
                sourceOrderIdentity = fixture.sourceOrderSequencer.nextSourceOrderIdentity(),
                lifecycleGeneration = LifecycleGeneration(1L)
            )
            val replacementSubmission = invokePrivateResult(
                fixture.blocker,
                "submitDecisionRequest",
                replacementRequest,
                1L,
                "foreground decision"
            ) as SubmissionResult
            assertEquals(SubmissionResult.ACCEPTED, replacementSubmission)
            assertTrue(
                "replacement did not retain revision 2",
                awaitCondition {
                    acceptedRuntime(fixture.blocker).runtimeRevision == revision2.runtimeRevision
                }
            )
            assertTrue(
                "replacement changed the visible allow result",
                awaitCondition { fixture.visibleChecks.size == 2 }
            )

            // The delayed source is stale at the host and must be rejected before it can alter
            // the snapshot or generation.
            val staleAcceptedAtBlocker = publishSharedPublicationPath(
                blocker = fixture.blocker,
                refreshMutex = fixture.refreshMutex,
                source = "delayed revision 1 at blocker",
                settings = Settings(appRuleSnapshot = staleSnapshot),
                sourceOrderIdentity = revision1.sourceOrderIdentity,
                runtimeRevision = revision1.runtimeRevision,
                timeline = fixture.timeline
            )
            assertTrue("revision 1 was accepted by the blocker", !staleAcceptedAtBlocker)

            // Also send the delayed revision through the production decision handoff after
            // replacement. The worker must retain revision 2 and drop revision 1 before the
            // following fresh request is allowed to evaluate.
            val staleRuntime = acceptedRuntime(fixture.blocker).runtime.copy(
                snapshot = staleSnapshot.normalized()
            )
            val staleWorkerSubmission = invokePrivateResult(
                fixture.blocker,
                "submitDecisionRequest",
                foregroundRequest(
                    sourceOrderIdentity = revision1.sourceOrderIdentity,
                    lifecycleGeneration = LifecycleGeneration(1L),
                    runtimePublication = RuntimePublication(
                        runtimeRevision = revision1.runtimeRevision,
                        candidateRuntime = staleRuntime
                    )
                ),
                1L,
                "delayed revision 1 worker handoff"
            ) as SubmissionResult
            assertEquals(SubmissionResult.ACCEPTED, staleWorkerSubmission)
            val freshWorkerSubmission = invokePrivateResult(
                fixture.blocker,
                "submitDecisionRequest",
                foregroundRequest(
                    sourceOrderIdentity = fixture.sourceOrderSequencer.nextSourceOrderIdentity(),
                    lifecycleGeneration = LifecycleGeneration(1L)
                ),
                1L,
                "post-stale foreground decision"
            ) as SubmissionResult
            assertEquals(SubmissionResult.ACCEPTED, freshWorkerSubmission)
            assertTrue(
                "the worker did not drain the delayed revision before the fresh request",
                awaitCondition { fixture.visibleChecks.size == 3 }
            )

            val acceptedRuntime = acceptedRuntime(fixture.blocker)
            val finalSnapshot = (getField(fixture.blocker, "snapshot") as AppRuleSnapshotCoordinator)
                .snapshot()
            val finalGeneration = (getField(fixture.blocker, "recheckGeneration") as AtomicLong).get()
            val hostRevision = hostRuntimeRevision(fixture.blocker)
            val failures = mutableListOf<String>()
            if (hostRevision != revision2.runtimeRevision) {
                failures += "host accepted stale revision ${hostRevision.value}"
            }
            if (acceptedRuntime.runtimeRevision != revision2.runtimeRevision) {
                failures += "worker rolled back to ${acceptedRuntime.runtimeRevision.value}"
            }
            if (acceptedRuntime.runtime.snapshot != latestSnapshot.normalized()) {
                failures += "worker snapshot rolled back to ${snapshotLabel(acceptedRuntime.runtime.snapshot)}"
            }
            if (finalSnapshot != latestSnapshot.normalized()) {
                failures += "host snapshot rolled back to ${snapshotLabel(finalSnapshot)}"
            }
            if (finalGeneration != 1L) {
                failures += "host recheck generation was $finalGeneration"
            }
            val replacementWorker = getField(fixture.blocker, "decisionWorker") as SerializedDecisionWorker
            if ((getField(replacementWorker, "currentLifecycleGeneration") as Number).toLong() != 1L) {
                failures += "worker lifecycle generation was not 1"
            }
            if (fixture.visibleChecks.size != 3 || fixture.visibleChecks.any { !it.isAllowed }) {
                failures += "visible results were ${fixture.visibleChecks.map { it.isAllowed }}"
            }
            if (fixture.service.startedActivities.isNotEmpty()) {
                failures += "stale revision launched ${fixture.service.startedActivities.size} warning activities"
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "production handoff B failed:\n" +
                        failures.joinToString(separator = "\n") { "- $it" } +
                        "\ntimeline:\n" +
                        fixture.timeline.joinToString(separator = "\n") { "- $it" }
                )
            }
        } finally {
            fixture.blocker.onDestroy()
        }
    }

    @Test
    fun concurrentReplacementCannotInstallCapturedOlderRuntimeAfterNewerPublication() =
        runBlocking {
            val oldSnapshot = snapshot(allowedMinutes = 0L, ruleName = "old")
            val latestSnapshot = snapshot(allowedMinutes = 10L, ruleName = "latest")
            val fixture = createProductionHandoffFixture(oldSnapshot)
            val releaseOldCapture = CompletableDeferred<Unit>()
            val oldCaptured = CompletableDeferred<Unit>()
            val newerHandoffReached =
                CompletableDeferred<Pair<RuntimeRevision, AppRuleSnapshot>>()
            val newerPublicationCompleted = CompletableDeferred<Unit>()
            val firstRecovery = AtomicBoolean(true)
            var oldSubmission: kotlinx.coroutines.Deferred<SubmissionResult>? = null
            var newerPublication: kotlinx.coroutines.Deferred<Boolean>? = null
            fixture.blocker.decisionWorkerRecoveryAfterCapture = {
                if (firstRecovery.compareAndSet(true, false)) {
                    oldCaptured.complete(Unit)
                    runBlocking { releaseOldCapture.await() }
                }
            }
            try {
                val oldReservation = fixture.sourceOrderSequencer.reserveRuntimePublication()
                assertTrue(
                    publishSharedPublicationPath(
                        blocker = fixture.blocker,
                        refreshMutex = fixture.refreshMutex,
                        source = "old runtime M",
                        settings = Settings(appRuleSnapshot = oldSnapshot),
                        sourceOrderIdentity = oldReservation.sourceOrderIdentity,
                        runtimeRevision = oldReservation.runtimeRevision,
                        timeline = fixture.timeline
                    )
                )
                stopWorker(fixture.blocker)

                val newerReservation = fixture.sourceOrderSequencer.reserveRuntimePublication()
                fixture.blocker.runtimePublicationBeforeWorkerHandoff = { revision ->
                    if (revision == newerReservation.runtimeRevision) {
                        newerHandoffReached.complete(
                            hostRuntimeRevision(fixture.blocker) to
                                fixture.snapshotCoordinator.snapshot()
                        )
                    }
                }
                oldSubmission = async(Dispatchers.Default) {
                    invokePrivateResult(
                        fixture.blocker,
                        "submitDecisionRequest",
                        foregroundRequest(
                            sourceOrderIdentity =
                                fixture.sourceOrderSequencer.nextSourceOrderIdentity(),
                            lifecycleGeneration = LifecycleGeneration(1L)
                        ),
                        1L,
                        "old replacement foreground"
                    ) as SubmissionResult
                }
                oldCaptured.await()

                newerPublication = async(Dispatchers.Default) {
                    try {
                        publishSharedPublicationPath(
                            blocker = fixture.blocker,
                            refreshMutex = fixture.refreshMutex,
                            source = "newer runtime N",
                            settings = Settings(appRuleSnapshot = latestSnapshot),
                            sourceOrderIdentity = newerReservation.sourceOrderIdentity,
                            runtimeRevision = newerReservation.runtimeRevision,
                            timeline = fixture.timeline
                        )
                    } finally {
                        newerPublicationCompleted.complete(Unit)
                    }
                }

                val observedNewerHandoff = newerHandoffReached.await()
                assertEquals(newerReservation.runtimeRevision, observedNewerHandoff.first)
                assertEquals(latestSnapshot.normalized(), observedNewerHandoff.second)
                assertFalse(
                    "newer replacement completed while older M owned the handoff lock",
                    newerPublicationCompleted.isCompleted
                )

                releaseOldCapture.complete(Unit)
                assertEquals(SubmissionResult.ACCEPTED, oldSubmission.await())
                assertTrue("newer publication was not accepted", newerPublication.await())
                assertTrue("newer publication did not finish", newerPublicationCompleted.isCompleted)

                fixture.visibleChecks.clear()
                invokePrivate(fixture.blocker, "checkCurrentlyVisibleApplications")
                assertTrue(
                    "final replacement did not publish a visible evaluation",
                    awaitCondition { fixture.visibleChecks.isNotEmpty() }
                )

                val acceptedRuntime = acceptedRuntime(fixture.blocker)
                val finalWorker = getField(fixture.blocker, "decisionWorker")
                    as SerializedDecisionWorker
                val failures = mutableListOf<String>()
                if (hostRuntimeRevision(fixture.blocker) != newerReservation.runtimeRevision) {
                    failures += "host revision was ${hostRuntimeRevision(fixture.blocker).value}"
                }
                if (acceptedRuntime.runtimeRevision != newerReservation.runtimeRevision) {
                    failures += "worker revision was ${acceptedRuntime.runtimeRevision.value}"
                }
                if (acceptedRuntime.runtime.snapshot != latestSnapshot.normalized()) {
                    failures += "worker snapshot was ${snapshotLabel(acceptedRuntime.runtime.snapshot)}"
                }
                if (fixture.snapshotCoordinator.snapshot() != latestSnapshot.normalized()) {
                    failures += "host snapshot was ${snapshotLabel(fixture.snapshotCoordinator.snapshot())}"
                }
                if ((getField(finalWorker, "currentLifecycleGeneration") as Number).toLong() != 1L) {
                    failures += "worker lifecycle generation was not 1"
                }
                if (fixture.visibleChecks.lastOrNull()?.isAllowed != true) {
                    failures += "final visible result was ${fixture.visibleChecks.lastOrNull()?.isAllowed}"
                }
                if (fixture.service.startedActivities.isNotEmpty()) {
                    failures += "final allow result launched ${fixture.service.startedActivities.size} warning activities"
                }
                if (failures.isNotEmpty()) {
                    throw AssertionError(
                        "concurrent production handoff failed:\n" +
                            failures.joinToString(separator = "\n") { "- $it" } +
                            "\ntimeline:\n" +
                            fixture.timeline.joinToString(separator = "\n") { "- $it" }
                    )
                }
            } finally {
                releaseOldCapture.complete(Unit)
                oldSubmission?.join()
                newerPublication?.join()
                fixture.blocker.decisionWorkerRecoveryAfterCapture = null
                fixture.blocker.runtimePublicationBeforeWorkerHandoff = null
                fixture.blocker.onDestroy()
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

    private fun hostRuntimeRevision(blocker: AppRuleBlocker): RuntimeRevision =
        RuntimeRevision((getField(blocker, "latestRuntimeRevision") as Number).toLong())

    private data class ProductionHandoffFixture(
        val blocker: AppRuleBlocker,
        val service: RecordingService,
        val snapshotCoordinator: AppRuleSnapshotCoordinator,
        val refreshMutex: Mutex,
        val sourceOrderSequencer: AtomicConnectionScopedSourceOrderSequencer,
        val visibleChecks: CopyOnWriteArrayList<AppRulesEvaluation>,
        val timeline: CopyOnWriteArrayList<String>
    )

    private fun createProductionHandoffFixture(
        initialSnapshot: AppRuleSnapshot
    ): ProductionHandoffFixture {
        val blocker = AppRuleBlocker()
        val service = RecordingService().also {
            it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
            it.lastBackPressTimeStamp = 0L
        }
        val repository = EmptySessionRepository()
        val snapshotCoordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        val refreshMutex = getField(blocker, "refreshMutex") as Mutex
        val sourceOrderSequencer = AtomicConnectionScopedSourceOrderSequencer()
        val visibleChecks = CopyOnWriteArrayList<AppRulesEvaluation>()
        val timeline = CopyOnWriteArrayList<String>()

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        (getField(blocker, "lifecycleGeneration") as AtomicLong).set(1L)
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

        return ProductionHandoffFixture(
            blocker = blocker,
            service = service,
            snapshotCoordinator = snapshotCoordinator,
            refreshMutex = refreshMutex,
            sourceOrderSequencer = sourceOrderSequencer,
            visibleChecks = visibleChecks,
            timeline = timeline
        )
    }

    private fun stopWorker(blocker: AppRuleBlocker) {
        (getField(blocker, "decisionWorker") as SerializedDecisionWorker).stop(
            RecoveryOnlyStop(
                requestedAtElapsedMs = NOW_MS,
                reason = StopReason.REPLACEMENT,
                lifecycleGeneration = LifecycleGeneration(1L)
            )
        )
    }

    private fun foregroundRequest(
        sourceOrderIdentity: SourceOrderIdentity,
        lifecycleGeneration: LifecycleGeneration,
        runtimePublication: RuntimePublication? = null
    ): DecisionRequest = DecisionRequest(
        sourceOrderIdentity = sourceOrderIdentity,
        lifecycleGeneration = lifecycleGeneration,
        reason = ObservationKind.REAL_EVENT,
        observation = ForegroundFacts(
            capturedAtWallMs = NOW_MS,
            capturedAtElapsedMs = NOW_MS,
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = TARGET_PACKAGE,
                eventWallMs = NOW_MS,
                eventElapsedMs = NOW_MS
            ),
            activeRoot = ActiveRootFact(
                packageName = TARGET_PACKAGE,
                readState = ForegroundReadState.AVAILABLE
            ),
            applicationWindows = ApplicationWindowsFact(
                packages = setOf(TARGET_PACKAGE),
                readState = ForegroundReadState.AVAILABLE
            ),
            displayState = DisplayState.UNLOCKED
        ),
        runtimePublication = runtimePublication
    )

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
