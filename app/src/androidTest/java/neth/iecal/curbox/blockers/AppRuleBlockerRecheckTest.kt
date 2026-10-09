package neth.iecal.curbox.blockers

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import neth.iecal.curbox.testing.AccessibilityFrameworkTestObjects
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt
import neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleEvaluator
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleRecheckPlan
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.FakeWakeScheduler
import neth.iecal.curbox.domain.apprules.GuardianApprovalCoordinator
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.LifecycleGeneration
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.RecheckPlanUpdate
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SignalFact
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Regression coverage for foreground app-rule checks and their recheck boundaries. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerRecheckTest {
    @Test
    fun activeZeroAllowanceRuleStartsGuardianApprovalForCurrentPackage() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        val event = AccessibilityFrameworkTestObjects.createEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        event.packageName = PACKAGE
        blocker.doAppRuleCheck(event)
        AccessibilityFrameworkTestObjects.releaseEvent(event)

        assertTrue(
            "active zero allowance rule must open approval",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun screenOnDefersGuardianUntilUserPresent() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
        }
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        // SCREEN_ON may arrive before the keyguard has delivered USER_PRESENT. No guardian may
        // be launched in that interval, even if the last app event is delivered again.
        setField(blocker, "screenOnAwaitingUserPresent", true)
        sendWindowEvent(blocker)
        assertTrue("screen-on must wait for USER_PRESENT", service.startedActivities.isEmpty())

        setField(blocker, "screenOnAwaitingUserPresent", false)
        sendWindowEvent(blocker)
        assertTrue(
            "the app must be checked after USER_PRESENT",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun closeGuardianForInstrumentationResetsActivePackageAndLastShownAtOnlyWhenPackageMatches() {
        val blocker = AppRuleBlocker()
        setField(blocker, "setupReady", true)
        setField(blocker, "activeGuardianPackage", PACKAGE)
        setField(blocker, "lastShownAt", 12345L)

        // Different package should return false and not mutate state
        org.junit.Assert.assertFalse(blocker.closeGuardianForInstrumentation(OTHER_PACKAGE))
        org.junit.Assert.assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))
        org.junit.Assert.assertEquals(12345L, getField(blocker, "lastShownAt"))

        // Matching package should reset and return true
        org.junit.Assert.assertTrue(blocker.closeGuardianForInstrumentation(PACKAGE))
        org.junit.Assert.assertNull(getField(blocker, "activeGuardianPackage"))
        org.junit.Assert.assertEquals(0L, getField(blocker, "lastShownAt"))
        blocker.onDestroy()
    }

    @Test
    fun decisionOutcomeSinkObserverReceivesDecisionOutcomeAtPublishEntry() {
        val observedOutcomes = java.util.concurrent.CopyOnWriteArrayList<neth.iecal.curbox.domain.apprules.DecisionOutcome>()
        val blocker = AppRuleBlocker().apply {
            decisionOutcomeSinkObserver = { outcome ->
                observedOutcomes.add(outcome)
            }
        }
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        sendWindowEvent(blocker, PACKAGE)
        org.junit.Assert.assertTrue(
            "decisionOutcomeSinkObserver must observe an enforcement outcome at publish entry",
            awaitCondition {
                observedOutcomes.any { it is DecisionOutcome.EnforcementOutcome }
            }
        )
        val outcome = observedOutcomes.filterIsInstance<DecisionOutcome.EnforcementOutcome>().first()
        org.junit.Assert.assertEquals(1, outcome.packageDecisions.size)
        org.junit.Assert.assertEquals(PACKAGE, outcome.packageDecisions[0].packageName)
        blocker.onDestroy()
    }

    @Test
    fun screenOffImmediatelyEndsForegroundEvidenceAndInvalidatesPendingRecheck() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val queued = ArrayDeque<Runnable>()
        var evaluations = 0
        var posts = 0
        val fakeScheduler = FakeWakeScheduler()
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                posts++
                queued.addLast(runnable)
                true
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        setField(blocker, "currentForegroundPackage", PACKAGE)
        setField(blocker, "foregroundEvidenceSuspended", false)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        assertTrue("the boundary must be registered before the screen turns off", fakeScheduler.hasScheduled(PACKAGE))
        assertTrue("the due wake must enqueue guarded visibility work", fakeScheduler.triggerWake(PACKAGE))
        val screenReceiver = getField(blocker, "screenReceiver") as android.content.BroadcastReceiver
        screenReceiver.onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))
        queued.removeFirst().run()

        assertEquals(null, getField(blocker, "currentForegroundPackage"))
        assertEquals(true, getField(blocker, "foregroundEvidenceSuspended"))
        assertTrue(
            "screen-off must cancel package boundaries while retaining settlement reconciliation",
            PACKAGE !in scheduledKeys(blocker) &&
                AppRuleBlocker.SETTLEMENT_WAKE_KEY in scheduledKeys(blocker)
        )
        assertEquals(0, evaluations)
        assertEquals(1, posts)
        blocker.onDestroy()
    }

    @Test
    fun screenOffClosesLastClassifiedTargetAfterEssentialEvent() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var activePackage = PACKAGE
        val repository = SessionRecordingRepository()
        val blocker = AppRuleBlocker().apply {
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = activePackage)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(activePackage),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
        }
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithTargetAllowance())

        sendWindowEvent(blocker, PACKAGE)
        assertTrue("target session must start", awaitCondition { repository.startedPackages.contains(PACKAGE) })

        activePackage = service.packageName
        sendWindowEvent(blocker, service.packageName)
        val screenReceiver = getField(blocker, "screenReceiver") as android.content.BroadcastReceiver
        screenReceiver.onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))

        assertTrue(
            "SCREEN_OFF must close the last classified target after an essential event",
            awaitCondition { repository.finishedPackages.contains(PACKAGE) }
        )
        assertTrue(service.packageName !in repository.startedPackages)
        blocker.onDestroy()
    }

    @Test
    fun staleEvaluationCannotRegisterBoundaryAfterScreenOffInvalidation() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var screenInteractive = true
        val blocker = AppRuleBlocker().apply {
            screenInteractiveProvider = { screenInteractive }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
        }
        val repository = EmptySessionRepository()
        val posts = mutableListOf<Long>()
        val queued = ArrayDeque<Runnable>()
        val invalidated = AtomicBoolean(false)
        val callbackFinished = AtomicBoolean(false)
        blocker.recheckPostDelayed = { runnable, delayMillis ->
            posts += delayMillis
            queued.addLast(runnable)
            true
        }
        blocker.evaluationResultObserver = {
            if (invalidated.compareAndSet(false, true)) {
                screenInteractive = false
                try {
                    invokePrivate(blocker, "handleScreenOff")
                } finally {
                    callbackFinished.set(true)
                }
            }
        }
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val now = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(now)
        setField(
            blocker,
            "overrideState",
            AppRuleGuardianOverrides.grant(
                AppRuleOverrideState(useDayId),
                "target",
                useDayId,
                grantedMillis = 3_000L,
                grantedAtMs = now
            )
        )
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithTargetAllowance())

        sendWindowEvent(blocker)

        assertTrue(
            "the evaluation must reach the invalidation seam",
            awaitCondition { invalidated.get() }
        )
        assertTrue(
            "the stale worker callback must complete before checking the scheduler",
            awaitCondition { callbackFinished.get() }
        )
        SystemClock.sleep(250L)
        assertEquals(
            "screen-off invalidation must discard an in-flight boundary plan",
            0,
            posts.size
        )
        blocker.onDestroy()
    }

    @Test
    fun displayReadFailureDefersWithoutEvaluatingOrTreatingTheScreenAsUnlocked() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var evaluations = 0
        val blocker = AppRuleBlocker().apply {
            screenInteractiveProvider = { error("display interactivity read failed") }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        sendWindowEvent(blocker)

        assertEquals(0, evaluations)
        assertTrue(service.startedActivities.isEmpty())
        blocker.onDestroy()
    }

    @Test
    fun positiveGuardianRemainderSchedulesARecheckWithoutAnotherWindowEvent() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val repository = EmptySessionRepository()
        val now = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(now)
        val overrideState = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState(useDayId),
            "target",
            useDayId,
            grantedMillis = 3_000L,
            grantedAtMs = now
        )
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        setField(blocker, "overrideState", overrideState)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithTargetAllowance())

        sendWindowEvent(blocker)
        coordinator.accept(snapshotWithGlobalDeny())
        // The production scheduler keeps a one second minimum delay, so allow the scheduled
        // callback and its bounded visibility retries to run before asserting the read.
        SystemClock.sleep(4_000L)

        assertTrue(
            "a positive guardian remainder must schedule a foreground recheck",
            service.windowsReads > 0
        )
        blocker.onDestroy()
    }

    @Test
    fun allAppsRuleEvaluatesCurrentPackageWhenLauncherListingOmitsIt() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val repository = EmptySessionRepository()
        val launchablePackages = setOf("com.example.other")
        val packageReader = AppRulePackageScopeReader(
            launchableReader = { launchablePackages },
            essentialReader = { emptySet() }
        )
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "packageScopeReader", packageReader)
        invokePrivate(blocker, "refreshPackageScope")
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        sendWindowEvent(blocker)
        assertTrue(
            "an all-apps rule must include the current package even when the launcher listing omits it",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun scheduledRecheckReevaluatesGlobalDenialAfterTargetUsageAndGuardianExtra() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val zone = ZoneId.systemDefault()
        val activeBoundary = ZonedDateTime.now(zone)
            .plusDays(1)
            .withHour(11)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
        val now = activeBoundary.minusMinutes(1).toInstant().toEpochMilli()
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = now,
            initialElapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler)
        blocker.wallClockMsProvider = { fakeScheduler.currentWallClockMs }
        blocker.elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        val observedOutcomes = CopyOnWriteArrayList<DecisionOutcome>()
        blocker.decisionOutcomeSinkObserver = { observedOutcomes += it }
        var windowSnapshotReads = 0
        blocker.applicationWindowSnapshotProvider = {
            windowSnapshotReads++
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        }
        val useDayId = ConfigurableUseDayCalculator(zone = zone).idAt(now)
        val repository = EmptySessionRepository(
            sessions = listOf(
                ForegroundSession(
                    useDayId = useDayId,
                    packageName = PACKAGE,
                    // Consume the full direct allowance so the guardian remainder is what keeps
                    // this package open during the first check.
                    startedAtMs = now - 60_000L,
                    endedAtMs = now
                )
            )
        )
        val overrideState = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState(useDayId),
            "target",
            useDayId,
            // Keep a guardian remainder that expires at the test boundary. The fake clock makes
            // this full minute deterministic without waiting in real time.
            grantedMillis = 60_000L,
            grantedAtMs = now
        )
        val packageReader = AppRulePackageScopeReader(
            launchableReader = { setOf("com.example.other") },
            essentialReader = { emptySet() }
        )
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "packageScopeReader", packageReader)
        setField(blocker, "overrideState", overrideState)
        invokePrivate(blocker, "refreshPackageScope")
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSpentTargetAllowance())

        // The target rule has consumed its direct allowance, but its guardian remainder keeps the
        // app open. A global rule becomes active at the grant's scheduled expiry, while the app
        // remains visible and no new window event is delivered.
        sendWindowEvent(blocker)
        assertTrue("the target rule's guardian remainder must keep the app open", service.startedActivities.isEmpty())
        assertTrue(
            "initial guardian remainder must schedule a callback " +
                "(scheduled=${scheduledKeys(blocker)})",
            awaitCondition {
                scheduledKeys(blocker).isNotEmpty()
            }
        )
        val globalStartMinute = activeBoundary.hour * 60 + activeBoundary.minute
        val snapshotWithFutureGlobalDeny = snapshotWithTargetAndGlobalDeny().let { snapshot ->
            snapshot.copy(
                appRules = snapshot.appRules.map { rule ->
                    if (rule.id == "global") {
                        rule.copy(
                            timeRanges = listOf(
                                AppRuleTimeRange(
                                    startMinute = globalStartMinute,
                                    endMinute = globalStartMinute + 60
                                )
                            )
                        )
                    } else {
                        rule
                    }
                }
            )
        }
        coordinator.accept(snapshotWithFutureGlobalDeny)
        setField(blocker, "lifecycleGeneration", java.util.concurrent.atomic.AtomicLong(1L))
        invokePrivate(blocker, "submitRuntimePublication", 0L)
        val publishedRuntimeRevision = RuntimeRevision(
            (getField(blocker, "latestRuntimeRevision") as Number).toLong()
        )
        assertTrue(
            "runtime publication should finish before the scheduled boundary",
            awaitCondition {
                observedOutcomes.filterIsInstance<DecisionOutcome.EnforcementOutcome>().any {
                    it.acceptedRuntimeRevision == publishedRuntimeRevision
                }
            }
        )
        val publicationOutcomes = observedOutcomes
            .filterIsInstance<DecisionOutcome.EnforcementOutcome>()
            .filter { it.acceptedRuntimeRevision == publishedRuntimeRevision }
        assertTrue(
            "the future global rule must remain inactive during runtime publication: $publicationOutcomes",
            publicationOutcomes.none { outcome ->
                outcome.packageDecisions.any { decision ->
                    !decision.isAllowed && "global" in decision.denyingRuleIds
                }
            }
        )
        assertTrue("runtime publication alone must not open approval", service.startedActivities.isEmpty())

        val scheduledWake = checkNotNull(fakeScheduler.getScheduled(PACKAGE)) {
            "the guardian expiry must remain scheduled after runtime publication"
        }
        assertTrue(
            "the scheduled wake must land on or after the future global rule boundary " +
                "(wake=${scheduledWake.dueAtWallClockMs}, boundary=${activeBoundary.toInstant().toEpochMilli()})",
            scheduledWake.dueAtWallClockMs >= activeBoundary.toInstant().toEpochMilli()
        )
        fakeScheduler.advanceTimeTo(
            wallClockMs = scheduledWake.dueAtWallClockMs,
            elapsedRealtimeMs = scheduledWake.effectiveDueElapsedMs
        )

        assertTrue(
            "a scheduler-driven synthetic recheck must deny through the now-active global rule",
            awaitCondition {
                observedOutcomes.filterIsInstance<DecisionOutcome.EvaluationReady>().any { outcome ->
                    outcome.request.reason == ObservationKind.SYNTHETIC_RECHECK &&
                        !outcome.evaluation.isAllowed &&
                        outcome.evaluation.denyingRules.any { it.ruleId == "global" }
                }
            }
        )

        assertTrue(
            "the scheduled recheck must open approval for the newly active global denial " +
                "(windowsReads=${service.windowsReads}, " +
                "current=${getField(blocker, "currentForegroundPackage")}, " +
                "scheduled=${scheduledKeys(blocker)})",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        val intent = service.startedActivities.last()
        val denials = intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS).orEmpty()
        assertTrue("unexpected denial payload: $denials", denials.contains("global"))
        assertTrue(
            "scheduled visibility recheck should reread windows (reads=$windowSnapshotReads)",
            windowSnapshotReads >= 2
        )
        blocker.onDestroy()
    }

    @Test
    fun scheduledRecheckDoesNotLockAfterARealForegroundSwitch() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val now = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(now)
        val repository = EmptySessionRepository(
            sessions = listOf(
                ForegroundSession(
                    useDayId = useDayId,
                    packageName = PACKAGE,
                    startedAtMs = now - 60_000L,
                    endedAtMs = now
                )
            )
        )
        val overrideState = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState(useDayId),
            "target",
            useDayId,
            grantedMillis = 2_000L,
            grantedAtMs = now
        )
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        setField(blocker, "overrideState", overrideState)
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = OTHER_PACKAGE)
        }
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSpentTargetAllowance())

        // A remains allowed only by the short guardian remainder, so its boundary is scheduled.
        sendWindowEvent(blocker, PACKAGE)
        // The real event for B is stronger than the last A event, even if the window provider is
        // empty during the transition. The old A callback must not show a stale lock screen.
        sendWindowEvent(blocker, OTHER_PACKAGE)
        SystemClock.sleep(4_000L)

        assertTrue(
            "a scheduled callback for an old foreground app must not lock after switching apps",
            service.startedActivities.isEmpty()
        )
        blocker.onDestroy()
    }

    @Test
    fun essentialOverlayClearsForegroundEvidenceAndDoesNotStartGuardianTwice() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        // The first real denial opens the guardian. Its own package is an essential overlay and
        // must suspend the old target foreground evidence before any queued callback can run.
        sendWindowEvent(blocker, PACKAGE)
        assertTrue(awaitCondition { service.startedActivities.size == 1 })
        sendWindowEvent(blocker, service.packageName)

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        SystemClock.sleep(2_000L)

        assertTrue(
            "an essential overlay must not trigger a second guardian from stale target evidence",
            service.startedActivities.size == 1
        )
        blocker.onDestroy()
    }

    @Test
    fun essentialEventEvaluatesTargetIdentifiedByApplicationWindow() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker().apply {
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = service.packageName)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        sendWindowEvent(blocker, service.packageName)

        assertTrue(
            "the target window must be evaluated even when an essential package emitted the event",
            awaitCondition { service.startedActivities.size == 1 }
        )
        blocker.onDestroy()
    }

    @Test
    fun essentialEventEvaluatesEveryDedupedApplicationWindowPackage() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        var evaluations = 0
        val blocker = AppRuleBlocker().apply {
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = service.packageName)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = linkedSetOf(PACKAGE, OTHER_PACKAGE, PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        sendWindowEvent(blocker, service.packageName)

        assertTrue(
            "an essential event must evaluate every distinct direct application window",
            awaitCondition { evaluations >= 2 }
        )
        assertEquals(2, evaluations)
        assertTrue(
            "one guardian must cover the current denial while it is open",
            awaitCondition { service.startedActivities.size == 1 }
        )
        assertEquals(1, service.startedActivities.size)
        blocker.onDestroy()
    }

    @Test
    fun overlayEvaluationUsesEveryPackageFromTheOriginalWindowSnapshot() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        var windowSnapshotReads = 0
        val evaluatedRuleIds = mutableListOf<String>()
        val blocker = AppRuleBlocker().apply {
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = service.packageName)
            }
            applicationWindowSnapshotProvider = {
                windowSnapshotReads++
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = if (windowSnapshotReads == 1) {
                        linkedSetOf(PACKAGE, OTHER_PACKAGE)
                    } else {
                        emptySet()
                    },
                    hasApplicationWindow = windowSnapshotReads == 1,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluation ->
                evaluatedRuleIds += evaluation.evaluations.single().ruleId
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSplitAllowances())

        sendWindowEvent(blocker, service.packageName)

        assertTrue(
            "both packages from the original overlay observation must be evaluated once",
            awaitCondition { evaluatedRuleIds.size == 2 }
        )
        assertEquals(listOf("target", "other"), evaluatedRuleIds)
        assertEquals(
            "one overlay observation must not recapture foreground windows per package",
            1,
            windowSnapshotReads
        )
        blocker.onDestroy()
    }

    @Test
    fun differentReliableActiveRootReplacesTheEventPackageWithoutRecapture() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        var activeRootReads = 0
        var applicationWindowReads = 0
        val evaluatedRuleIds = mutableListOf<String>()
        val fakeScheduler = FakeWakeScheduler()
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            activeWindowSnapshotProvider = {
                activeRootReads++
                AppRuleBlocker.ActiveWindowSnapshot(
                    packageName = if (activeRootReads == 1) OTHER_PACKAGE else PACKAGE
                )
            }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            applicationWindowSnapshotProvider = {
                applicationWindowReads++
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = false,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluation ->
                evaluatedRuleIds += evaluation.evaluations.single().ruleId
            }
            recheckPostDelayed = { _, _ -> true }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSplitAllowances())
        invokePrivate(
            blocker,
            "applyRecheckPlan",
            RecheckPlanUpdate(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                lifecycleGeneration = LifecycleGeneration(1L),
                acceptedRuntimeRevision = RuntimeRevision(0L),
                packageName = PACKAGE,
                plan = AppRuleRecheckPlan(
                    delayMillis = 60_000L,
                    maxDelayMillis = 60_000L,
                    dueAtWallClockMs = System.currentTimeMillis() + 60_000L
                )
            )
        )

        sendWindowEvent(blocker, PACKAGE)

        assertTrue(
            "the event package must end without evaluation and the active root must evaluate once",
            awaitCondition { evaluatedRuleIds.size == 1 }
        )
        assertEquals(listOf("other"), evaluatedRuleIds)
        assertTrue(
            "the not-visible event outcome must cancel its existing boundary",
            PACKAGE !in scheduledKeys(blocker)
        )
        assertEquals(1, activeRootReads)
        assertEquals(1, applicationWindowReads)
        blocker.onDestroy()
    }

    @Test
    fun essentialRootAndReconnectProcessVisibleTargetWithoutDuplicateGuardian() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        var nowMs = 10_000L
        var activePackage = PACKAGE
        var evaluations = 0
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { nowMs }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = activePackage)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        sendWindowEvent(blocker, PACKAGE)
        activePackage = service.packageName
        nowMs += 2_000L
        sendWindowEvent(blocker, service.packageName)
        nowMs += 2_000L
        invokePrivate(blocker, "checkCurrentlyVisibleApplications")

        assertTrue(awaitCondition { evaluations >= 3 })
        assertEquals(3, evaluations)
        assertEquals(
            "essential and reconnect observations must reuse the existing guardian",
            1,
            service.startedActivities.size
        )
        blocker.onDestroy()
    }

    @Test
    fun guardianLifecycleSignalsOpenAndCloseOnlyAffectTheMatchingPackage() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val blocker = AppRuleBlocker()
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)
        val connectionId = "guardian-connection-current"
        val currentRequestId = "guardian-screen-current"
        openGuardianOwnerForTest(blocker, PACKAGE, currentRequestId, connectionId)
        val receiver = getField(blocker, "guardianReceiver") as android.content.BroadcastReceiver

        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_OPENED,
                PACKAGE,
                "guardian-screen-old",
                connectionId
            )
        )
        assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))
        assertEquals(
            currentRequestId,
            (getField(blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
                .currentOwner()?.screenRequestId
        )

        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_CLOSED,
                OTHER_PACKAGE,
                currentRequestId,
                connectionId
            )
        )
        assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))

        val replacementRequestId = "guardian-screen-replacement"
        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_OPENED,
                PACKAGE,
                replacementRequestId,
                connectionId,
                previousScreenRequestId = currentRequestId
            )
        )
        assertEquals(
            replacementRequestId,
            (getField(blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
                .currentOwner()?.screenRequestId
        )

        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_CLOSED,
                PACKAGE,
                currentRequestId,
                connectionId
            )
        )
        assertEquals(
            replacementRequestId,
            (getField(blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
                .currentOwner()?.screenRequestId
        )

        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_CLOSED,
                PACKAGE,
                replacementRequestId,
                connectionId
            )
        )
        assertEquals(null, getField(blocker, "activeGuardianPackage"))
        assertEquals(
            null,
            (getField(blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
                .currentOwner()
        )
        blocker.onDestroy()
    }

    @Test
    fun accumulatedApprovalReceiverKeepsTheReceiptAcrossConfirmationRetry() =
        assertApprovalReceiverKeepsReceiptAcrossRetry(
            GuardianApprovalWorkReceipt.Grant(
                grant = GuardianApprovalGrantReceipt(
                    ruleId = "usage",
                    useDayId = "2026-10-07",
                    grantedAtMs = 1_791_360_000_000L,
                    grantedMillis = 15 * 60_000L
                ),
                origin = GuardianApprovalGrantOrigin.ACCUMULATED_POOL,
                useDayGenerationStartedAtMs = 1_791_360_000_000L
            )
        )

    @Test
    fun skipApprovalReceiverKeepsTheReceiptAcrossConfirmationRetry() =
        assertApprovalReceiverKeepsReceiptAcrossRetry(
            GuardianApprovalWorkReceipt.RuleSkip(
                ruleId = "usage",
                useDayId = "2026-10-07",
                skipFromMs = 1_791_360_000_000L,
                skipUntilMs = 1_791_360_900_000L,
                useDayGenerationStartedAtMs = 1_791_360_000_000L
            )
        )

    @Test
    fun currentScreenRebindsAfterReconnectAndRejectsOldConnectionCommands() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val blocker = AppRuleBlocker()
        val queuedDispatcher = QueuedDispatcher()
        val connectionId = "guardian-connection-reconnected"
        val oldConnectionId = "guardian-connection-old"
        val screenRequestId = "guardian-screen-live"
        val operationId = "guardian-operation-live"
        val receipt = GuardianApprovalWorkReceipt.RuleSkip(
            ruleId = "usage",
            useDayId = "2026-10-07",
            skipFromMs = 1_791_360_000_000L,
            skipUntilMs = 1_791_360_900_000L,
            useDayGenerationStartedAtMs = 1_791_360_000_000L
        )
        val currentCheckId = "check-after-reconnect"
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)
        setField(blocker, "scope", CoroutineScope(SupervisorJob() + queuedDispatcher))
        setField(blocker, "serviceConnectionId", connectionId)
        (getField(blocker, "lifecycleGeneration") as AtomicLong).set(2L)
        val receiver = getField(blocker, "guardianReceiver") as android.content.BroadcastReceiver
        val coordinator = getField(blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator

        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_OPENED,
                PACKAGE,
                screenRequestId,
                connectionId
            )
        )
        assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))
        assertEquals(
            LifecycleGeneration(2L),
            coordinator.currentOwner()?.lifecycleGeneration
        )

        receiver.onReceive(
            service,
            approvalCheckIntent(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER,
                screenRequestId = screenRequestId,
                operationId = operationId,
                checkId = currentCheckId,
                receipt = receipt,
                connectionId = connectionId
            )
        )
        assertEquals(
            GuardianApprovalCoordinator.Confirmation(
                operationId = operationId,
                checkId = currentCheckId,
                receipt = receipt,
                phase = GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            ),
            coordinator.currentOwner()?.confirmation
        )

        receiver.onReceive(
            service,
            approvalCheckIntent(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
                screenRequestId = screenRequestId,
                operationId = operationId,
                checkId = "old-check",
                receipt = receipt,
                connectionId = oldConnectionId
            )
        )
        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_CLOSED,
                PACKAGE,
                "old-screen",
                connectionId
            )
        )
        assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))
        assertEquals(
            GuardianApprovalCoordinator.Confirmation(
                operationId = operationId,
                checkId = currentCheckId,
                receipt = receipt,
                phase = GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            ),
            coordinator.currentOwner()?.confirmation
        )
        blocker.onDestroy()
    }

    private fun assertApprovalReceiverKeepsReceiptAcrossRetry(
        receipt: GuardianApprovalWorkReceipt
    ) {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val blocker = AppRuleBlocker()
        val queuedDispatcher = QueuedDispatcher()
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)
        setField(blocker, "activeGuardianPackage", PACKAGE)
        setField(blocker, "scope", CoroutineScope(SupervisorJob() + queuedDispatcher))
        (getField(blocker, "lifecycleGeneration") as AtomicLong).set(1L)

        val screenRequestId = "screen-$PACKAGE"
        val operationId = "operation-${receipt::class.simpleName}"
        val firstCheckId = "check-1"
        val retryCheckId = "check-2"
        val generation = LifecycleGeneration(1L)
        setField(blocker, "serviceConnectionId", TEST_GUARDIAN_CONNECTION_ID)
        val coordinator = getField(blocker, "guardianApprovalCoordinator")
            as GuardianApprovalCoordinator
        assertTrue(coordinator.openScreen(screenRequestId, PACKAGE, generation))
        val receiver = getField(blocker, "guardianReceiver") as android.content.BroadcastReceiver

        receiver.onReceive(
            service,
            approvalCheckIntent(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED,
                screenRequestId = screenRequestId,
                operationId = operationId,
                checkId = firstCheckId,
                receipt = receipt,
                connectionId = TEST_GUARDIAN_CONNECTION_ID
            )
        )

        assertEquals(
            GuardianApprovalCoordinator.Confirmation(
                operationId = operationId,
                checkId = firstCheckId,
                receipt = receipt,
                phase = GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            ),
            coordinator.currentOwner()?.confirmation
        )
        assertTrue(
            coordinator.timeOutConfirmation(
                GuardianApprovalCoordinator.CheckIdentity(
                    screenRequestId = screenRequestId,
                    operationId = operationId,
                    checkId = firstCheckId,
                    lifecycleGeneration = generation
                )
            )
        )

        receiver.onReceive(
            service,
            approvalCheckIntent(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
                screenRequestId = screenRequestId,
                operationId = operationId,
                checkId = retryCheckId,
                receipt = receipt,
                connectionId = TEST_GUARDIAN_CONNECTION_ID
            )
        )
        assertEquals(
            GuardianApprovalCoordinator.Confirmation(
                operationId = operationId,
                checkId = retryCheckId,
                receipt = receipt,
                phase = GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            ),
            coordinator.currentOwner()?.confirmation
        )

        receiver.onReceive(
            service,
            approvalCheckIntent(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
                screenRequestId = screenRequestId,
                operationId = operationId,
                checkId = retryCheckId,
                receipt = receipt,
                connectionId = TEST_GUARDIAN_CONNECTION_ID
            )
        )
        assertEquals(
            "a duplicate retry must not replace or complete the current request",
            retryCheckId,
            coordinator.currentOwner()?.confirmation?.checkId
        )
        blocker.onDestroy()
    }

    private fun openGuardianOwnerForTest(
        blocker: AppRuleBlocker,
        packageName: String,
        screenRequestId: String,
        connectionId: String
    ) {
        setField(blocker, "serviceConnectionId", connectionId)
        val generation = (getField(blocker, "lifecycleGeneration") as AtomicLong)
            .get().coerceAtLeast(1L)
        val coordinator = getField(blocker, "guardianApprovalCoordinator")
            as GuardianApprovalCoordinator
        assertTrue(
            coordinator.openScreen(
                screenRequestId,
                packageName,
                LifecycleGeneration(generation)
            )
        )
        setField(blocker, "activeGuardianPackage", packageName)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }
    }

    @Test
    fun guardianClosedWithInterruptedReasonResetsThrottleAndPostsRecheck() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val postedDelays = mutableListOf<Long>()
        val blocker = AppRuleBlocker().apply {
            visibleApplicationCheckPostDelayed = { _, delayMs ->
                postedDelays.add(delayMs)
                true
            }
        }
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)
        val requestId = "guardian-interrupted-close"
        openGuardianOwnerForTest(blocker, PACKAGE, requestId, TEST_GUARDIAN_CONNECTION_ID)
        setField(blocker, "lastShownAt", 5000L)

        val receiver = getField(blocker, "guardianReceiver") as android.content.BroadcastReceiver
        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_CLOSED,
                PACKAGE,
                requestId,
                TEST_GUARDIAN_CONNECTION_ID
            )
                .putExtra(
                    GuardianApprovalActivity.EXTRA_CLOSE_REASON,
                    GuardianApprovalActivity.REASON_INTERRUPTED
                )
        )

        assertEquals(null, getField(blocker, "activeGuardianPackage"))
        assertEquals(0L, getField(blocker, "lastShownAt"))
        assertEquals(listOf(50L), postedDelays)
        blocker.onDestroy()
    }

    @Test
    fun directGrantKeepsUnrelatedNightDenialOnTheCurrentGuardianScreen() {
        val nowMs = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val targetGroup = AppRuleAppGroup("target", "Target", listOf(PACKAGE))
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(targetGroup),
            appRules = listOf(
                AppRule(
                    id = "usage",
                    name = "Usage limit",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(targetGroup.id),
                    allowedMinutes = 0
                ),
                AppRule(
                    id = "night",
                    name = "Night restriction",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope(includeAllApps = true),
                    allowedMinutes = 0,
                    guardianExtraTimeAllowed = false
                )
            )
        ).normalized()
        val grantedOverrides = AppRuleGuardianOverrides.grant(
            state = AppRuleOverrideState(useDayId = useDayId),
            ruleId = "usage",
            useDayId = useDayId,
            grantedMillis = 10 * 60_000L,
            grantedAtMs = nowMs
        )
        val actualEvaluation = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = PACKAGE,
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = nowMs,
            resetTime = UseDayResetTime(),
            overrides = grantedOverrides,
            availablePackages = setOf(PACKAGE)
        )

        assertEquals(listOf("night"), actualEvaluation.denyingRules.map { it.ruleId })
        assertTrue(actualEvaluation.evaluations.single { it.ruleId == "usage" }.isAllowed)

        val service = RecordingService().also {
            it.attach(InstrumentationContext.context)
            it.lastBackPressTimeStamp = 0L
        }
        val guardianActivities = CopyOnWriteArrayList<Intent>()
        val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
        val enforcementOutcomes = CopyOnWriteArrayList<DecisionOutcome.EnforcementOutcome>()
        service.startActivityObserver = { intent ->
            if (intent.component?.className == GuardianApprovalActivity::class.java.name) {
                guardianActivities += intent
            }
        }
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { nowMs }
            elapsedRealtimeMsProvider = { SystemClock.elapsedRealtime() }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations += it }
            decisionOutcomeSinkObserver = { outcome ->
                if (outcome is DecisionOutcome.EnforcementOutcome &&
                    outcome.packageDecisions.any { it.packageName == PACKAGE }
                ) {
                    enforcementOutcomes += outcome
                }
            }
        }
        val repository = EmptySessionRepository()

        try {
            setField(blocker, "service", service)
            setField(blocker, "sessionRepository", repository)
            setField(blocker, "enforcement", AppRuleEnforcement(repository))
            setField(blocker, "setupReady", true)
            setField(blocker, "serviceConnectionId", TEST_GUARDIAN_CONNECTION_ID)
            setField(blocker, "launchablePackages", setOf(PACKAGE))
            setField(blocker, "overrideState", grantedOverrides)
            val snapshotCoordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
            snapshotCoordinator.accept(snapshot)

            fun checkPackage() {
                val event = AccessibilityFrameworkTestObjects.createEvent(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                )
                try {
                    event.packageName = PACKAGE
                    blocker.doAppRuleCheck(event)
                } finally {
                    AccessibilityFrameworkTestObjects.releaseEvent(event)
                }
            }

            checkPackage()
            assertTrue(
                "the foreground event must reach rule evaluation; evaluations=${evaluations.map { evaluation -> evaluation.denyingRules.map { it.ruleId } }}, outcomes=${enforcementOutcomes.map { it.packageDecisions }}",
                awaitCondition { evaluations.isNotEmpty() }
            )
            assertEquals(listOf("night"), evaluations.first().denyingRules.map { it.ruleId })
            assertTrue(
                "the denied foreground decision must publish; outcomes=${enforcementOutcomes.map { it.packageDecisions }}",
                awaitCondition { enforcementOutcomes.isNotEmpty() }
            )
            assertEquals(
                listOf("night"),
                enforcementOutcomes.first().packageDecisions
                    .firstOrNull { it.packageName == PACKAGE }?.denyingRuleIds
            )
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .runOnMainSync { }
            assertTrue(
                "the existing night denial must show the guardian before the direct grant is confirmed; started=${service.startedActivities.map { it.component?.className }}, guardian=${guardianActivities.size}",
                awaitCondition { guardianActivities.isNotEmpty() }
            )
            val baselineGuardianCount = guardianActivities.size
            val screenRequestId = guardianActivities.last()
                .getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
                .orEmpty()
            val coordinator = getField(
                blocker,
                "guardianApprovalCoordinator"
            ) as GuardianApprovalCoordinator
            val grantReceipt = GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = useDayId,
                grantedAtMs = nowMs,
                grantedMillis = 10 * 60_000L
            )
            val workReceipt = GuardianApprovalWorkReceipt.Grant(
                grant = grantReceipt,
                origin = neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin.DIRECT,
                useDayGenerationStartedAtMs = 0L
            )
            val operationId = "direct-grant-$screenRequestId"
            val checkId = "direct-check-$screenRequestId"
            assertTrue(
                coordinator.beginConfirmation(
                    screenRequestId,
                    operationId,
                    checkId,
                    workReceipt
                )
            )
            val owner = coordinator.currentOwner() ?: error("The approval screen should own the request.")
            assertTrue(
                coordinator.completeConfirmation(
                    GuardianApprovalCoordinator.CheckIdentity(
                        screenRequestId,
                        operationId,
                        checkId,
                        owner.lifecycleGeneration
                    )
                )
            )
            checkPackage()

            assertTrue(
                "the post grant package evaluation must complete",
                awaitCondition { evaluations.size >= 2 }
            )
            assertEquals(
                listOf("night"),
                evaluations.last().denyingRules.map { it.ruleId }
            )
            assertTrue(
                "the post grant enforcement decision must complete",
                awaitCondition { enforcementOutcomes.size >= 2 }
            )
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .runOnMainSync { }

            assertEquals(
                "the current guardian screen remains the sole owner after a direct grant",
                baselineGuardianCount,
                guardianActivities.size
            )
            assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))
            assertEquals(screenRequestId, coordinator.currentOwner()?.screenRequestId)
        } finally {
            blocker.onDestroy()
        }
    }

    @Test
    fun cancelledGuardianClosePostsANearTermForegroundRecheck() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val postedDelays = mutableListOf<Long>()
        val blocker = AppRuleBlocker().apply {
            visibleApplicationCheckPostDelayed = { _, delayMs ->
                postedDelays.add(delayMs)
                true
            }
        }
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)
        val requestId = "guardian-cancelled-close"
        openGuardianOwnerForTest(blocker, PACKAGE, requestId, TEST_GUARDIAN_CONNECTION_ID)
        setField(blocker, "lastShownAt", 5000L)

        val receiver = getField(blocker, "guardianReceiver") as android.content.BroadcastReceiver
        receiver.onReceive(
            service,
            guardianLifecycleIntent(
                GuardianApprovalActivity.INTENT_ACTION_CLOSED,
                PACKAGE,
                requestId,
                TEST_GUARDIAN_CONNECTION_ID
            )
                .putExtra(
                    GuardianApprovalActivity.EXTRA_CLOSE_REASON,
                    GuardianApprovalActivity.REASON_CANCELLED
                )
        )

        assertEquals(null, getField(blocker, "activeGuardianPackage"))
        assertEquals(0L, getField(blocker, "lastShownAt"))
        assertEquals(listOf(300L), postedDelays)
        blocker.onDestroy()
    }

    @Test
    fun suspendedEvidenceResumesFromModuleVisibleOutcomeUnderEssentialRoot() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var evaluations = 0
        val blocker = AppRuleBlocker().apply {
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = service.packageName)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        setField(blocker, "suspendedForegroundPackage", PACKAGE)
        setField(blocker, "foregroundEvidenceSuspended", true)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(blocker, "checkCurrentlyVisibleApplications")

        assertTrue(
            "a visible package recovered from suspended evidence must be evaluated",
            awaitCondition { evaluations == 1 }
        )
        assertEquals(false, getField(blocker, "foregroundEvidenceSuspended"))
        blocker.onDestroy()
    }

    @Test
    fun suspendedEvidenceResumesOnlyFromTheMatchingModulePackageOutcome() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val blocker = AppRuleBlocker().apply {
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = OTHER_PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = linkedSetOf(OTHER_PACKAGE, PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        setField(blocker, "suspendedForegroundPackage", PACKAGE)
        setField(blocker, "foregroundEvidenceSuspended", true)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(blocker, "checkCurrentlyVisibleApplications")

        assertEquals(
            "suspended recovery must match the suspended package in module outcomes",
            PACKAGE,
            getField(blocker, "currentForegroundPackage")
        )
        assertEquals(false, getField(blocker, "foregroundEvidenceSuspended"))
        blocker.onDestroy()
    }

    @Test
    fun recentForegroundEvidenceSurvivesAStaleOtherApplicationWindow() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = System.currentTimeMillis(),
            initialElapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
        }
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        }
        val now = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(now)
        val repository = EmptySessionRepository(
            sessions = listOf(
                ForegroundSession(
                    useDayId = useDayId,
                    packageName = PACKAGE,
                    startedAtMs = now - 60_000L,
                    // Keep the session open so the delayed check consumes the guardian remainder
                    // just as it would while the user remains in the app.
                    endedAtMs = null
                )
            )
        )
        val overrideState = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState(useDayId),
            "target",
            useDayId,
            grantedMillis = 2_000L,
            grantedAtMs = now
        )
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(OTHER_PACKAGE))
        setField(blocker, "overrideState", overrideState)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSpentTargetAllowance())

        // The target was the last real foreground event, but the OEM window list now omits it.
        // A null active root must use the short recent-evidence window to run the real denial.
        sendWindowEvent(blocker, PACKAGE)
        assertTrue(
            "the target allowance boundary must be scheduled before visibility recovery " +
                "(scheduled=${scheduledKeys(blocker)})",
            awaitCondition {
                scheduledKeys(blocker).isNotEmpty()
            }
        )
        fakeScheduler.advanceTimeBy(4_500L)

        assertTrue(
            "a recent target event must not lose its expiration check to a stale other-app window " +
                "(activities=${service.startedActivities.size}, windowsReads=${service.windowsReads}, " +
                "current=${getField(blocker, "currentForegroundPackage")}, " +
                "evidenceAt=${getField(blocker, "currentForegroundEvidenceAtElapsedMs")}, " +
                "nowElapsed=${SystemClock.elapsedRealtime()}, " +
                "suspended=${getField(blocker, "foregroundEvidenceSuspended")}, " +
                "scheduled=${scheduledKeys(blocker)})",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun recentForegroundEvidenceSurvivesWindowProviderException() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = System.currentTimeMillis(),
            initialElapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
        }
        blocker.applicationWindowSnapshotProvider = {
            error("transient OEM window provider failure")
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        }
        val now = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(now)
        val repository = EmptySessionRepository(
            sessions = listOf(
                ForegroundSession(
                    useDayId = useDayId,
                    packageName = PACKAGE,
                    startedAtMs = now - 60_000L,
                    // The open session models the app remaining in the foreground while the
                    // guardian remainder expires.
                    endedAtMs = null
                )
            )
        )
        val overrideState = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState(useDayId),
            "target",
            useDayId,
            grantedMillis = 2_000L,
            grantedAtMs = now
        )
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(OTHER_PACKAGE))
        setField(blocker, "overrideState", overrideState)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSpentTargetAllowance())

        // The application window provider fails for every bounded read and the active root is
        // unavailable. Recent real foreground evidence must still complete this boundary check.
        sendWindowEvent(blocker, PACKAGE)
        assertTrue(
            "the target allowance boundary must survive the provider failure",
            awaitCondition {
                scheduledKeys(blocker).isNotEmpty()
            }
        )
        fakeScheduler.advanceTimeBy(4_500L)

        assertTrue(
            "a provider exception must not lose the target expiration check",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun reconnectEvaluatesKnownApplicationWindowsWithoutForegroundOrActiveRoot() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        // Model a fresh service connection: no event-derived package and no root, only known
        // application windows returned by AccessibilityService.getWindows().
        setField(blocker, "currentForegroundPackage", null)
        invokePrivate(blocker, "checkCurrentlyVisibleApplications")

        assertTrue(
            "known application windows must be checked after reconnect",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun splitScreenKnownWindowsKeepIndependentBoundaryJobsWhenOneRootIsUnknown() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val fakeScheduler = FakeWakeScheduler()
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler)
        blocker.applicationWindowSnapshotProvider = {
            // Model a split-screen snapshot where B has a readable root and A's root is null.
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 2
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithSplitAllowances())

        // A delivered event establishes the recent foreground evidence. The reconciliation must
        // still evaluate B from the partial split-screen window list and retain both keyed jobs.
        sendWindowEvent(blocker, PACKAGE)
        invokePrivate(blocker, "checkCurrentlyVisibleApplications")

        assertTrue(
            "split-screen reconciliation must publish both boundary jobs",
            awaitCondition {
                val scheduled = scheduledKeys(blocker)
                PACKAGE in scheduled && OTHER_PACKAGE in scheduled
            }
        )
        val scheduledPackages = scheduledKeys(blocker)
        assertTrue(
            "the current split-screen package must retain its boundary",
            PACKAGE in scheduledPackages
        )
        assertTrue(
            "the known second split-screen package must get its own boundary",
            OTHER_PACKAGE in scheduledPackages
        )
        blocker.onDestroy()
    }

    @Test
    fun oneCoalescedWakeProcessesIndependentDueDeadlinesWithExternalOutcomes() {
        val zone = ZoneId.systemDefault()
        val baseWallClockMs = ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val baseElapsedRealtimeMs = 10_000L
        val dueWallClockMs = baseWallClockMs + 60_000L
        val baseUseDayId = ConfigurableUseDayCalculator(zone = zone).idAt(baseWallClockMs)
        val targetRuleId = "independent-target-deadline"
        val otherRuleId = "independent-other-deadline"
        val baseLocalDateTime = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(baseWallClockMs),
            zone
        )
        val weekday = baseLocalDateTime.dayOfWeek.value % 7
        val otherStartMinute = baseLocalDateTime.hour * 60 + baseLocalDateTime.minute + 1
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(
                AppRuleAppGroup("target", "Target", listOf(PACKAGE)),
                AppRuleAppGroup("other", "Other", listOf(OTHER_PACKAGE))
            ),
            appRules = listOf(
                AppRule(
                    id = targetRuleId,
                    name = "Target allowance boundary",
                    weekdays = setOf(weekday),
                    scope = AppRuleScope.forGroup("target"),
                    allowedMinutes = 1,
                    timeRanges = listOf(AppRuleTimeRange(0, 0))
                ),
                AppRule(
                    id = otherRuleId,
                    name = "Other schedule boundary",
                    weekdays = setOf(weekday),
                    scope = AppRuleScope.forGroup("other"),
                    allowedMinutes = 2,
                    timeRanges = listOf(
                        AppRuleTimeRange(otherStartMinute, otherStartMinute + 1)
                    )
                )
            )
        )
        val repository = EmptySessionRepository(
            sessions = listOf(
                ForegroundSession(
                    id = 1L,
                    useDayId = baseUseDayId,
                    packageName = PACKAGE,
                    startedAtMs = baseWallClockMs
                ),
                ForegroundSession(
                    id = 2L,
                    useDayId = baseUseDayId,
                    packageName = OTHER_PACKAGE,
                    startedAtMs = baseWallClockMs
                )
            )
        )
        val service = RecordingService().also {
            it.attach(InstrumentationContext.context)
            it.lastBackPressTimeStamp = 0L
        }
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = baseWallClockMs,
            initialElapsedRealtimeMs = baseElapsedRealtimeMs
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler)
        val phase = AtomicReference(DeadlineEvidencePhase.INITIAL)
        val wallClockMs = AtomicLong(baseWallClockMs)
        val elapsedRealtimeMs = AtomicLong(baseElapsedRealtimeMs)
        val requiredPackages = setOf(PACKAGE, OTHER_PACKAGE)
        val planLock = Any()
        val initialDueAt = mutableMapOf<String, Long>()
        val tickPlanPackages = mutableSetOf<String>()
        val tickDueAt = mutableMapOf<String, Long>()
        val initialPlansReady = CountDownLatch(1)
        val tickPlansReady = CountDownLatch(1)
        val targetDenied = CountDownLatch(1)
        val otherAllowed = CountDownLatch(1)
        val warningPublished = CountDownLatch(1)
        val denialActivityStarted = CountDownLatch(1)
        val warningPackages = CopyOnWriteArrayList<String>()
        val visibleCallbacks = CopyOnWriteArrayList<Runnable>()
        val tickEvaluations = mutableMapOf<String, Boolean>()
        val evaluationLock = Any()
        service.startActivityObserver = { intent ->
            if (intent.getStringExtra(GuardianApprovalActivity.EXTRA_PACKAGE) == PACKAGE) {
                denialActivityStarted.countDown()
            }
        }

        blocker.wallClockMsProvider = { wallClockMs.get() }
        blocker.elapsedRealtimeMsProvider = { elapsedRealtimeMs.get() }
        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
        }
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = requiredPackages,
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false,
                applicationWindowCount = requiredPackages.size
            )
        }
        blocker.visibleApplicationCheckPostDelayed = { runnable, _ ->
            visibleCallbacks += runnable
            true
        }
        blocker.visibleApplicationCheckRemoveCallbacks = {}
        blocker.evaluationResultObserver = { evaluation ->
            val decisions = evaluation.evaluations.associate { it.ruleId to it.isAllowed }
            if (phase.get() == DeadlineEvidencePhase.DUE_TICK) {
                synchronized(evaluationLock) {
                    decisions.forEach { (ruleId, isAllowed) ->
                        tickEvaluations[ruleId] = isAllowed
                    }
                }
                if (decisions[targetRuleId] == false) targetDenied.countDown()
                if (decisions[otherRuleId] == true) otherAllowed.countDown()
            }
        }
        blocker.warningBeforeFrameworkCallObserver = { packageName ->
            warningPackages += packageName
            if (packageName == PACKAGE) warningPublished.countDown()
        }
        blocker.recheckPlanDeliveryObserver = { update ->
            update.plan?.let { plan ->
                synchronized(planLock) {
                    when (phase.get() ?: DeadlineEvidencePhase.INITIAL) {
                        DeadlineEvidencePhase.INITIAL -> {
                            initialDueAt[update.packageName] = plan.dueAtWallClockMs
                            if (initialDueAt.keys.containsAll(requiredPackages)) {
                                initialPlansReady.countDown()
                            }
                        }
                        DeadlineEvidencePhase.DUE_TICK -> {
                            tickPlanPackages += update.packageName
                            tickDueAt[update.packageName] = plan.dueAtWallClockMs
                            if (tickPlanPackages.containsAll(requiredPackages)) {
                                tickPlansReady.countDown()
                            }
                        }
                    }
                }
            }
        }

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", requiredPackages)
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        check(coordinator.accept(snapshot)) { "deterministic split-screen snapshot was rejected" }

        try {
            // One real event carries both visible application windows. The worker derives one
            // independent plan per package before any synthetic scheduler wake is delivered.
            sendWindowEvent(blocker, PACKAGE)
            assertTrue(
                "initial worker did not publish both independent boundary plans",
                initialPlansReady.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            val firstPlans = synchronized(planLock) { initialDueAt.toMap() }
            assertEquals(requiredPackages, firstPlans.keys)
            assertEquals(dueWallClockMs, firstPlans[PACKAGE])
            assertEquals(dueWallClockMs, firstPlans[OTHER_PACKAGE])
            assertTrue(
                "initial plans must register one keyed wall-clock wake per package",
                awaitCondition {
                    fakeScheduler.scheduledKeys().containsAll(requiredPackages)
                }
            )
            assertEquals(requiredPackages, fakeScheduler.scheduledKeys())
            val initialTokens = requiredPackages.sorted().associateWith { packageName ->
                scheduledAlarmToken(blocker, packageName)
            }
            assertEquals(
                "independent packages must have distinct keyed scheduler tokens",
                requiredPackages.size,
                initialTokens.values.toSet().size
            )

            wallClockMs.set(dueWallClockMs)
            elapsedRealtimeMs.set(baseElapsedRealtimeMs + 60_000L)
            phase.set(DeadlineEvidencePhase.DUE_TICK)

            // Deliver both actual keyed alarm tokens through the production receiver before the
            // first visible reconciliation callback runs. The receiver removes each keyed
            // registration and coalesces both wakes into one guarded observation.
            val schedulerWakeReceiver =
                getField(blocker, "schedulerWakeReceiver") as android.content.BroadcastReceiver
            val wakeOrder = initialTokens.keys.sorted()
            val firstPackage = wakeOrder.first()
            val secondPackage = wakeOrder.last()
            fun deliverSchedulerWake(packageName: String) {
                schedulerWakeReceiver.onReceive(
                    service,
                    Intent("neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE")
                        .putExtra(
                            "neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE",
                            packageName
                        )
                        .putExtra(
                            "neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN",
                            initialTokens.getValue(packageName)
                        )
                )
            }
            deliverSchedulerWake(firstPackage)
            assertTrue(
                "first receiver delivery must remove only its keyed registration",
                !scheduledKeys(blocker).contains(firstPackage) &&
                    scheduledKeys(blocker).contains(secondPackage)
            )
            assertEquals(
                "the first receiver delivery must already coalesce one visible callback",
                1,
                visibleCallbacks.size
            )
            deliverSchedulerWake(secondPackage)
            assertTrue(
                "both receiver deliveries must remove both keyed registrations",
                requiredPackages.none { packageName ->
                    scheduledKeys(blocker).contains(packageName)
                }
            )
            assertEquals(
                "independent due alarms must coalesce into one visible tick",
                1,
                visibleCallbacks.size
            )
            assertTrue(
                "scheduler receiver must preserve the visible callback barrier",
                synchronized(evaluationLock) { tickEvaluations.isEmpty() }
            )
            visibleCallbacks.single().run()

            assertTrue(
                "target allowance deadline did not produce an external denial",
                targetDenied.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "other schedule deadline did not produce an external allow",
                otherAllowed.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "coalesced tick did not publish both independent next plans",
                tickPlansReady.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "target denial did not reach the guardian framework boundary",
                warningPublished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "target denial did not start the externally visible guardian activity",
                denialActivityStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertEquals(
                "only target denial should launch a guardian activity",
                listOf(PACKAGE),
                warningPackages.toList()
            )
            synchronized(evaluationLock) {
                assertEquals(false, tickEvaluations[targetRuleId])
                assertEquals(true, tickEvaluations[otherRuleId])
            }
            assertEquals(
                "the other package's next allowance boundary was lost",
                baseWallClockMs + 120_000L,
                synchronized(planLock) { tickDueAt[OTHER_PACKAGE] }
            )
            assertEquals(PACKAGE, service.startedActivities.single().getStringExtra(
                GuardianApprovalActivity.EXTRA_PACKAGE
            ))
        } finally {
            blocker.onDestroy()
        }
    }

    @Test
    fun packageLessUnknownSlotRetriesObservationThreeTimesThenStops() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val queued = ArrayDeque<Runnable>()
        val delays = mutableListOf<Long>()
        val fakeScheduler = FakeWakeScheduler()
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(OTHER_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = true,
                    applicationWindowCount = 2
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, delayMillis ->
                if (delayMillis <= 1_000L) {
                    delays += delayMillis
                    queued.addLast(runnable)
                }
                true
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(OTHER_PACKAGE))

        invokePrivate(blocker, "checkCurrentlyVisibleApplications")
        while (queued.isNotEmpty() && delays.size < 3) queued.removeFirst().run()
        if (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(listOf(250L, 500L, 750L), delays.take(3))
        assertTrue(
            "package-less observation retry must terminate after the third attempt " +
                "(scheduled=${scheduledKeys(blocker)}, " +
                "delays=$delays)",
            scheduledKeys(blocker).none {
                it.contains("foreground-observation")
            }
        )
        blocker.onDestroy()
    }

    @Test
    fun schedulerPostFailureRetriesThreeTimesAndRetainsBoundaryState() {
        listOf(false, true).forEach { throws ->
            val service = RecordingService().also { it.attach(InstrumentationContext.context) }
            var postAttempts = 0
            var wallClockMs = 1_000_000L
            var elapsedRealtimeMs = 5_000L
            val fakeScheduler = FakeWakeScheduler(
                initialWallClockMs = wallClockMs,
                initialElapsedRealtimeMs = elapsedRealtimeMs
            )
            val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
                wallClockMsProvider = { wallClockMs }
                elapsedRealtimeMsProvider = { elapsedRealtimeMs }
                visibleApplicationCheckPostDelayed = { _, _ ->
                    postAttempts++
                    if (throws) error("scheduler post failed")
                    false
                }
            }
            setField(blocker, "service", service)
            setField(blocker, "setupReady", true)

            invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
            invokePrivate(
                blocker,
                "postVisibleApplicationCheck",
                0L,
                0L,
                ObservationKind.REFRESH,
                0
            )

            assertEquals(3, postAttempts)
            assertTrue(
                "failed scheduler posts must retain the package boundary",
                PACKAGE in scheduledKeys(blocker)
            )
            assertEquals(
                wallClockMs + 1_000L,
                fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
            )
            blocker.onDestroy()
        }
    }

    @Test
    fun schedulerPostFailureRearmsAndEventuallyExecutesWithoutRunningBeforeWallDeadline() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val wallClockMs = 1_000_000L
        val elapsedRealtimeMs = 5_000L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val visibleCallbacks = ArrayDeque<Runnable>()
        val postDelays = mutableListOf<Long>()
        var postAttempts = 0
        var evaluations = 0
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, delayMillis ->
                postAttempts++
                postDelays += delayMillis
                if (postAttempts <= 3) {
                    false
                } else {
                    visibleCallbacks.addLast(runnable)
                    true
                }
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val originalDueAt = fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
            ?: error("the package boundary must be registered")
        val firstToken = scheduledAlarmToken(blocker)

        // An alarm delivered early must retain its absolute due time after all handler posts fail.
        fakeScheduler.currentWallClockMs += 750L
        fakeScheduler.currentElapsedRealtimeMs += 750L
        val receiver = getField(blocker, "schedulerWakeReceiver") as android.content.BroadcastReceiver
        receiver.onReceive(
            service,
            Intent("neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE")
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE", PACKAGE)
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN", firstToken)
        )

        assertEquals(3, postAttempts)
        assertTrue(postDelays.all { it >= 0L })
        assertTrue(visibleCallbacks.isEmpty())
        assertEquals(0, evaluations)
        assertEquals(
            "failed posts must re-arm the original wall-clock deadline",
            originalDueAt,
            fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
        )

        fakeScheduler.advanceTimeBy(249L)
        assertTrue("the retained boundary must not run before its wall deadline", visibleCallbacks.isEmpty())
        assertEquals(3, postAttempts)
        fakeScheduler.advanceTimeBy(1L)
        assertEquals(4, postAttempts)
        assertEquals(1, visibleCallbacks.size)
        assertTrue(fakeScheduler.currentWallClockMs >= originalDueAt)

        visibleCallbacks.removeFirst().run()
        assertTrue(
            "the recovered boundary must eventually evaluate after its wall deadline",
            awaitCondition { evaluations > 0 }
        )
        blocker.onDestroy()
    }

    @Test
    fun schedulerPostFailureRefreshesExpiredRelativeRecoveryDue() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val wallClockMs = 1_000_000L
        val elapsedRealtimeMs = 5_000L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val visibleCallbacks = ArrayDeque<Runnable>()
        var postAttempts = 0
        var evaluations = 0
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                postAttempts++
                if (postAttempts <= 3) false else {
                    visibleCallbacks.addLast(runnable)
                    true
                }
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val token = scheduledAlarmToken(blocker)
        fakeScheduler.currentWallClockMs += 1_500L
        fakeScheduler.currentElapsedRealtimeMs += 1_500L
        val receiver = getField(blocker, "schedulerWakeReceiver") as android.content.BroadcastReceiver
        receiver.onReceive(
            service,
            Intent("neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE")
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE", PACKAGE)
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN", token)
        )

        assertEquals(3, postAttempts)
        assertTrue(visibleCallbacks.isEmpty())
        val recoveryDueAt = fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
            ?: error("an expired boundary must be scheduled again")
        assertEquals(
            "an expired wake must become a fresh relative recovery boundary",
            fakeScheduler.currentWallClockMs + 20_000L,
            recoveryDueAt
        )

        fakeScheduler.advanceTimeBy(19_999L)
        assertEquals(3, postAttempts)
        assertTrue(visibleCallbacks.isEmpty())
        fakeScheduler.advanceTimeBy(1L)
        assertEquals(4, postAttempts)
        assertEquals(1, visibleCallbacks.size)
        visibleCallbacks.removeFirst().run()
        assertTrue(
            "the refreshed boundary must eventually evaluate",
            awaitCondition { evaluations > 0 }
        )
        blocker.onDestroy()
    }

    @Test
    fun productionWakeAlarmReceiverRecomputesFromCurrentWallClock() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val wakeQueue = ArrayDeque<Runnable>()
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        var evaluations = 0
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                wakeQueue.addLast(runnable)
                true
            }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val token = scheduledAlarmToken(blocker)

        wallClockMs += 2_000L
        elapsedRealtimeMs += 2_000L
        val receiver = getField(blocker, "schedulerWakeReceiver") as android.content.BroadcastReceiver
        receiver.onReceive(
            service,
            Intent("neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE")
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE", PACKAGE)
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN", token)
        )

        assertTrue(
            "alarm onReceive must not execute the keyed decision path synchronously",
            !awaitCondition { evaluations > 0 }
        )
        assertEquals(
            "one alarm must coalesce into one guarded wake observation",
            1,
            wakeQueue.size
        )
        while (wakeQueue.isNotEmpty()) wakeQueue.removeFirst().run()
        assertTrue(
            "wake recovery must evaluate using the current wall clock",
            awaitCondition { evaluations > 0 }
        )
        SystemClock.sleep(100L)
        assertEquals("one alarm must produce one decision", 1, evaluations)
        blocker.onDestroy()
    }

    @Test
    fun productionRecoveryWakeAlarmReceiverAcceptsOnlyCurrentTokenAndWakesOnce() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val wakeQueue = ArrayDeque<Runnable>()
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        var evaluations = 0
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                wakeQueue.addLast(runnable)
                true
            }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        // Replace the keyed wake so the first token becomes stale and only the second may wake.
        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val firstToken = scheduledAlarmToken(blocker)
        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val secondToken = scheduledAlarmToken(blocker)
        assertTrue("replacement must receive a distinct alarm token", firstToken != secondToken)
        val receiver = getField(blocker, "schedulerWakeReceiver") as android.content.BroadcastReceiver
        fun deliver(token: Long) {
            receiver.onReceive(
                service,
                Intent("neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE")
                    .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE", PACKAGE)
                    .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN", token)
            )
        }

        deliver(firstToken)
        assertTrue("a replaced recovery token must be rejected", wakeQueue.isEmpty())

        wallClockMs += 1_000L
        elapsedRealtimeMs += 1_000L
        deliver(secondToken)
        deliver(secondToken)
        assertEquals("the current recovery token must wake exactly once", 1, wakeQueue.size)

        wakeQueue.removeFirst().run()
        assertTrue(
            "the accepted recovery alarm must reach the decision path",
            awaitCondition { evaluations > 0 }
        )
        blocker.onDestroy()
    }

    @Test
    fun removingPlanCancelsPendingProductionRecoveryWake() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val wakeQueue = ArrayDeque<Runnable>()
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                wakeQueue.addLast(runnable)
                true
            }
        }
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)

        invokePrivate(
            blocker,
            "applyRecheckPlan",
            RecheckPlanUpdate(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                lifecycleGeneration = LifecycleGeneration(1L),
                acceptedRuntimeRevision = RuntimeRevision(0L),
                packageName = PACKAGE,
                plan = AppRuleRecheckPlan(
                    delayMillis = 1_000L,
                    maxDelayMillis = 20_000L,
                    dueAtWallClockMs = wallClockMs + 1_000L
                )
            )
        )
        val recoveryToken = scheduledAlarmToken(blocker)
        assertTrue(
            "a plan must leave a registered wake",
            scheduledKeys(blocker).contains(PACKAGE)
        )

        invokePrivate(
            blocker,
            "applyRecheckPlan",
            RecheckPlanUpdate(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                lifecycleGeneration = LifecycleGeneration(1L),
                acceptedRuntimeRevision = RuntimeRevision(0L),
                packageName = PACKAGE,
                plan = null
            )
        )

        val receiver = getField(blocker, "schedulerWakeReceiver") as android.content.BroadcastReceiver
        receiver.onReceive(
            service,
            Intent("neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE")
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE", PACKAGE)
                .putExtra("neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN", recoveryToken)
        )

        assertTrue(
            "removing a plan must invalidate its pending recovery alarm before wake delivery",
            wakeQueue.isEmpty()
        )
        assertTrue(
            "plan removal must remove the paired scheduled wake",
            scheduledKeys(blocker).isEmpty()
        )
        blocker.onDestroy()
    }

    @Test
    fun stalePlanCancellationCannotRemoveReplacementRegistration() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
        }
        setField(blocker, "service", service)
        setField(blocker, "setupReady", true)
        val plan = AppRuleRecheckPlan(
            delayMillis = 1_000L,
            maxDelayMillis = 20_000L,
            dueAtWallClockMs = wallClockMs + 1_000L
        )
        fun applyPlan(sourceOrder: Long, nextPlan: AppRuleRecheckPlan?) {
            invokePrivate(
                blocker,
                "applyRecheckPlan",
                RecheckPlanUpdate(
                    sourceOrderIdentity = SourceOrderIdentity(sourceOrder),
                    lifecycleGeneration = LifecycleGeneration(1L),
                    acceptedRuntimeRevision = RuntimeRevision(0L),
                    packageName = PACKAGE,
                    plan = nextPlan
                )
            )
        }

        applyPlan(1L, plan)
        val oldToken = scheduledAlarmToken(blocker)
        applyPlan(2L, plan)
        val replacementToken = scheduledAlarmToken(blocker)
        assertTrue("replacement must use a new registration token", oldToken != replacementToken)
        assertTrue("replacement must remain a scheduler registration", scheduledKeys(blocker).contains(PACKAGE))

        // This is the old worker update arriving after a newer plan has installed its recovery.
        applyPlan(1L, null)

        assertEquals(
            "a stale plan cancellation must not remove the replacement alarm",
            replacementToken,
            scheduledAlarmToken(blocker)
        )
        assertTrue(
            "a stale plan cancellation must retain the replacement scheduled wake",
            scheduledKeys(blocker).contains(PACKAGE)
        )

        applyPlan(2L, null)
        assertTrue(scheduledKeys(blocker).isEmpty())
        blocker.onDestroy()
    }

    @Test
    fun olderWakeCleanupRetainsNewerSameDueWakeForTheSamePackage() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val wallClockMs = 1_000_000L
        val elapsedRealtimeMs = 5_000L
        val dueAtWallClockMs = wallClockMs + 1_000L
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        val queued = ArrayDeque<Runnable>()
        var replacementToken = Long.MIN_VALUE
        var replacementDueAtWallClockMs = Long.MIN_VALUE
        lateinit var blocker: AppRuleBlocker
        blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                if (replacementToken == Long.MIN_VALUE) {
                    invokePrivate(
                        blocker,
                        "scheduleRecheckAtWallClock",
                        PACKAGE,
                        dueAtWallClockMs
                    )
                    replacementToken = scheduledAlarmToken(blocker)
                    replacementDueAtWallClockMs =
                        fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
                            ?: error("the replacement alarm must remain scheduled before delivery")
                    blocker.onWakeFromScheduler(PACKAGE, replacementToken)
                }
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                queued.addLast(runnable)
                true
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))

        invokePrivate(blocker, "scheduleRecheckAtWallClock", PACKAGE, dueAtWallClockMs)
        val originalToken = scheduledAlarmToken(blocker)
        val originalDueAtWallClockMs = fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
        assertTrue(fakeScheduler.triggerWake(PACKAGE))
        assertEquals(1, queued.size)

        queued.removeFirst().run()

        assertTrue("the test must publish a replacement alarm", replacementToken != originalToken)
        assertEquals(
            "the replacement has the same deadline as the consumed wake",
            originalDueAtWallClockMs,
            replacementDueAtWallClockMs
        )
        assertTrue(
            "cleanup for the older wake must retain a newer wake with the same package and due time",
            pendingSchedulerWakePackages(blocker).contains(PACKAGE)
        )
        assertEquals("the newer wake also queued its own observation", 1, queued.size)
        blocker.onDestroy()
    }

    @Test
    fun notVisiblePackageIsNotRearmedForAnotherUnknownApplicationSlot() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000L,
            initialElapsedRealtimeMs = 5_000L
        )
        val queued = ArrayDeque<Runnable>()
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = OTHER_PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = true,
                    applicationWindowCount = 1
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                queued.addLast(runnable)
                true
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        setField(blocker, "currentForegroundPackage", PACKAGE)
        val evidenceModule = getField(blocker, "foregroundEvidenceModule")!!
        evidenceModule.javaClass.getDeclaredField("lastRealSignal").apply {
            isAccessible = true
            set(
                evidenceModule,
                SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = PACKAGE,
                    eventWallMs = fakeScheduler.currentWallClockMs,
                    eventElapsedMs = fakeScheduler.currentElapsedRealtimeMs
                )
            )
        }

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        assertTrue(fakeScheduler.triggerWake(PACKAGE))
        repeat(4) { queued.removeFirst().run() }

        assertFalse(
            "NotVisible evidence for the pending package must cancel its wake even when another window slot is unknown",
            fakeScheduler.hasScheduled(PACKAGE)
        )
        blocker.onDestroy()
    }

    @Test
    fun visiblePackagePlanIsNotOverwrittenForAnotherUnknownApplicationSlot() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000L,
            initialElapsedRealtimeMs = 5_000L
        )
        val queued = ArrayDeque<Runnable>()
        var replacementToken = Long.MIN_VALUE
        var otherReplacementToken = Long.MIN_VALUE
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { fakeScheduler.currentWallClockMs }
            elapsedRealtimeMsProvider = { fakeScheduler.currentElapsedRealtimeMs }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                if (replacementToken == Long.MIN_VALUE) {
                    invokePrivate(
                        this,
                        "scheduleRecheckAtWallClock",
                        PACKAGE,
                        fakeScheduler.currentWallClockMs + 7_000L
                    )
                    replacementToken = scheduledAlarmToken(this)
                    invokePrivate(
                        this,
                        "scheduleRecheckAtWallClock",
                        OTHER_PACKAGE,
                        fakeScheduler.currentWallClockMs + 9_000L
                    )
                    otherReplacementToken = scheduledAlarmToken(this, OTHER_PACKAGE)
                }
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = true,
                    applicationWindowCount = 1
                )
            }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                queued.addLast(runnable)
                true
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))
        setField(blocker, "currentForegroundPackage", PACKAGE)
        val evidenceModule = getField(blocker, "foregroundEvidenceModule")!!
        evidenceModule.javaClass.getDeclaredField("lastRealSignal").apply {
            isAccessible = true
            set(
                evidenceModule,
                SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = PACKAGE,
                    eventWallMs = fakeScheduler.currentWallClockMs,
                    eventElapsedMs = fakeScheduler.currentElapsedRealtimeMs
                )
            )
        }

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        invokePrivate(blocker, "scheduleRecheck", OTHER_PACKAGE, 1_000L, 20_000L, 0L)
        assertTrue(fakeScheduler.triggerWake(PACKAGE))
        assertTrue(fakeScheduler.triggerWake(OTHER_PACKAGE))
        repeat(4) { queued.removeFirst().run() }

        assertTrue("the observation must install a replacement worker plan", replacementToken != Long.MIN_VALUE)
        assertEquals(
            "a Visible result must not let an unrelated unknown window overwrite the new plan",
            replacementToken,
            scheduledAlarmToken(blocker)
        )
        assertTrue("an unresolved pending package must keep its new worker plan", otherReplacementToken != Long.MIN_VALUE)
        assertEquals(
            "fallback recovery must not replace a plan installed during observation",
            otherReplacementToken,
            scheduledAlarmToken(blocker, OTHER_PACKAGE)
        )
        assertEquals(
            fakeScheduler.currentWallClockMs + 7_000L,
            fakeScheduler.getScheduled(PACKAGE)?.dueAtWallClockMs
        )
        assertEquals(
            fakeScheduler.currentWallClockMs + 9_000L,
            fakeScheduler.getScheduled(OTHER_PACKAGE)?.dueAtWallClockMs
        )
        blocker.onDestroy()
    }

    @Test
    fun staleNotVisibleCancellationCannotRemoveReplacementRegistration() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        var useReplacementObservation = false
        var replacementToken = Long.MIN_VALUE
        lateinit var blocker: AppRuleBlocker
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = wallClockMs,
            initialElapsedRealtimeMs = elapsedRealtimeMs
        )
        blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                if (useReplacementObservation) {
                    if (replacementToken == Long.MIN_VALUE) {
                        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 60_000L, 60_000L, 0L)
                        replacementToken = scheduledAlarmToken(blocker)
                    }
                    AppRuleBlocker.ActiveWindowSnapshot(packageName = OTHER_PACKAGE)
                } else {
                    AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
                }
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = false,
                    hasUnknownApplicationWindow = false
                )
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE, OTHER_PACKAGE))

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 60_000L, 60_000L, 0L)
        val oldToken = scheduledAlarmToken(blocker)
        val evidenceModule = getField(blocker, "foregroundEvidenceModule")!!
        evidenceModule.javaClass.getDeclaredField("lastRealSignal").apply {
            isAccessible = true
            set(
                evidenceModule,
                SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = PACKAGE,
                    eventWallMs = wallClockMs,
                    eventElapsedMs = elapsedRealtimeMs
                )
            )
        }

        useReplacementObservation = true
        invokePrivate(
            blocker,
            "checkCurrentlyVisibleApplications",
            ObservationKind.REAL_EVENT,
            0
        )

        assertTrue("the observation must install a replacement registration", replacementToken != oldToken)
        assertTrue(
            "the replacement wake must still be registered: ${scheduledKeys(blocker)}",
            scheduledKeys(blocker).contains(PACKAGE)
        )
        assertEquals(
            "a stale NotVisible cancellation must not remove the replacement alarm",
            replacementToken,
            scheduledAlarmToken(blocker)
        )
        blocker.onDestroy()
    }

    @Test
    fun relativeVisibilityAndSuspendedRecoveryRefreshesExpiredBoundary() {
        listOf(false, true).forEach { suspended ->
            val service = RecordingService().also { it.attach(InstrumentationContext.context) }
            val queued = ArrayDeque<Runnable>()
            val delays = mutableListOf<Long>()
            var wallClockMs = 1_000_000L
            var elapsedRealtimeMs = 5_000L
            val fakeScheduler = FakeWakeScheduler(
                initialWallClockMs = wallClockMs,
                initialElapsedRealtimeMs = elapsedRealtimeMs
            )
            val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler).apply {
                wallClockMsProvider = { wallClockMs }
                elapsedRealtimeMsProvider = { elapsedRealtimeMs }
                screenInteractiveProvider = { true }
                keyguardLockedProvider = { false }
                activeWindowSnapshotProvider = {
                    AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
                }
                applicationWindowSnapshotProvider = {
                    AppRuleBlocker.ApplicationWindowSnapshot(
                        packages = emptySet(),
                        hasApplicationWindow = true,
                        hasUnknownApplicationWindow = true,
                        applicationWindowCount = 1
                    )
                }
                visibleApplicationCheckPostDelayed = { runnable, delayMillis ->
                    delays += delayMillis
                    queued.addLast(runnable)
                    true
                }
            }
            val repository = EmptySessionRepository()
            setField(blocker, "service", service)
            setField(blocker, "sessionRepository", repository)
            setField(blocker, "enforcement", AppRuleEnforcement(repository))
            setField(blocker, "setupReady", true)
            setField(blocker, "launchablePackages", setOf(PACKAGE))
            setField(blocker, "foregroundEvidenceSuspended", suspended)
            if (suspended) setField(blocker, "suspendedForegroundPackage", PACKAGE)

            invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
            assertTrue(fakeScheduler.hasScheduled(PACKAGE))
            wallClockMs += 1_000L
            elapsedRealtimeMs += 1_000L
            fakeScheduler.currentWallClockMs = wallClockMs
            fakeScheduler.currentElapsedRealtimeMs = elapsedRealtimeMs
            assertTrue("the due package wake must enqueue a visible observation", fakeScheduler.triggerWake(PACKAGE))
            // With no package identity, another immediate read cannot add evidence. The keyed
            // wall-clock wake preserves the expired boundary for the next useful observation.
            val recoveryAttempts = 1
            repeat(recoveryAttempts) { attempt ->
                queued.removeFirst().run()
                if (attempt < recoveryAttempts - 1 && queued.isNotEmpty()) {
                    val nextDelay = delays.last()
                    wallClockMs += nextDelay
                    elapsedRealtimeMs += nextDelay
                    fakeScheduler.currentWallClockMs = wallClockMs
                    fakeScheduler.currentElapsedRealtimeMs = elapsedRealtimeMs
                }
            }

            val scheduled = fakeScheduler.getScheduled(PACKAGE)
                ?: error("relative recovery must retain a scheduled package")
            assertEquals(
                "${if (suspended) "suspended" else "unknown"} recovery must use a fresh relative boundary",
                wallClockMs + 20_000L,
                scheduled.dueAtWallClockMs
            )
            assertEquals(listOf(0L), delays)
            blocker.onDestroy()
        }
    }

    @Test
    fun visibleReconciliationPostFailureRecoversWithTheSameGeneration() {
        listOf(false, true).forEach { throws ->
            val service = RecordingService().also { it.attach(InstrumentationContext.context) }
            service.lastBackPressTimeStamp = 0L
            val queued = ArrayDeque<Runnable>()
            var posts = 0
            var evaluations = 0
            val blocker = AppRuleBlocker().apply {
                screenInteractiveProvider = { true }
                keyguardLockedProvider = { false }
                activeWindowSnapshotProvider = {
                    AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
                }
                applicationWindowSnapshotProvider = {
                    AppRuleBlocker.ApplicationWindowSnapshot(
                        packages = setOf(PACKAGE),
                        hasApplicationWindow = true,
                        hasUnknownApplicationWindow = false
                    )
                }
                evaluationResultObserver = { evaluations++ }
                visibleApplicationCheckPostDelayed = { runnable, _ ->
                    posts++
                    if (posts == 1) {
                        if (throws) error("visible reconciliation post failed") else false
                    } else {
                        queued.addLast(runnable)
                        true
                    }
                }
            }
            val repository = EmptySessionRepository()
            setField(blocker, "service", service)
            setField(blocker, "sessionRepository", repository)
            setField(blocker, "enforcement", AppRuleEnforcement(repository))
            setField(blocker, "setupReady", true)
            setField(blocker, "launchablePackages", setOf(PACKAGE))
            val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
            coordinator.accept(snapshotWithGlobalDeny())

            invokePrivate(
                blocker,
                "postVisibleApplicationCheck",
                0L,
                0L
            )
            assertTrue("failed visible post must enqueue bounded recovery", queued.isNotEmpty())
            queued.removeFirst().run()

            assertTrue(
                "visible post $throws recovery must evaluate the module's visible package",
                awaitCondition { evaluations > 0 }
            )
            assertEquals(2, posts)
            blocker.onDestroy()
        }
    }

    @Test
    fun visibleReconciliationCallbackIsIgnoredAfterItsGenerationChanges() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val queued = ArrayDeque<Runnable>()
        var evaluations = 0
        val blocker = AppRuleBlocker().apply {
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            evaluationResultObserver = { evaluations++ }
            visibleApplicationCheckPostDelayed = { runnable, _ ->
                queued.addLast(runnable)
                true
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshotWithGlobalDeny())

        invokePrivate(
            blocker,
            "postVisibleApplicationCheck",
            0L,
            0L
        )
        (getField(blocker, "recheckGeneration") as java.util.concurrent.atomic.AtomicLong)
            .incrementAndGet()
        queued.removeFirst().run()

        assertEquals(
            "a stale visible reconciliation callback must not evaluate after generation change",
            0,
            evaluations
        )
        blocker.onDestroy()
    }

    @Test
    fun unrelatedSettingsEmissionKeepsExistingBoundaryJob() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val fakeScheduler = FakeWakeScheduler()
        val blocker = AppRuleBlocker(wakeScheduler = fakeScheduler)
        val snapshot = snapshotWithTargetAllowance()
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshot)

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 10_000L, 20_000L, 0L)
        val before = scheduledKeys(blocker).size
        val changed = invokePrivateResult(
            blocker,
            "applySettingsSnapshot",
            Settings(appRuleSnapshot = snapshot, isReelCounterOn = false)
        ) as Boolean

        assertTrue("an unrelated DataStore emission must not invalidate a boundary job", !changed)
        assertTrue(
            "the existing app boundary must remain scheduled",
            scheduledKeys(blocker).size == before
        )
        blocker.onDestroy()
    }

    private fun snapshotWithGlobalDeny(): AppRuleSnapshot {
        val target = AppRuleAppGroup("target", "Target", listOf(PACKAGE))
        return AppRuleSnapshot(
            appGroups = listOf(target),
            appRules = listOf(
                AppRule(
                    id = "global",
                    name = "Global lockdown",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope(includeAllApps = true),
                    allowedMinutes = 0
                )
            )
        )
    }

    private fun snapshotWithTargetAllowance(): AppRuleSnapshot {
        val target = AppRuleAppGroup("target", "Target", listOf(PACKAGE))
        return AppRuleSnapshot(
            appGroups = listOf(target),
            appRules = listOf(
                AppRule(
                    id = "target",
                    name = "Target allowance",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(target.id),
                    allowedMinutes = 0
                )
            )
        )
    }

    private fun snapshotWithTargetAndGlobalDeny(): AppRuleSnapshot {
        val target = AppRuleAppGroup("target", "Target", listOf(PACKAGE))
        return AppRuleSnapshot(
            appGroups = listOf(target),
            appRules = listOf(
                AppRule(
                    id = "target",
                    name = "Target allowance",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(target.id),
                    allowedMinutes = 1
                ),
                AppRule(
                    id = "global",
                    name = "Global lockdown",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope(includeAllApps = true),
                    allowedMinutes = 0
                )
            )
        )
    }

    private fun snapshotWithSpentTargetAllowance(): AppRuleSnapshot {
        val target = AppRuleAppGroup("target", "Target", listOf(PACKAGE))
        return AppRuleSnapshot(
            appGroups = listOf(target),
            appRules = listOf(
                AppRule(
                    id = "target",
                    name = "Target allowance",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(target.id),
                    allowedMinutes = 1
                )
            )
        )
    }

    private fun snapshotWithSplitAllowances(): AppRuleSnapshot {
        val target = AppRuleAppGroup("target", "Target", listOf(PACKAGE))
        val other = AppRuleAppGroup("other", "Other", listOf(OTHER_PACKAGE))
        return AppRuleSnapshot(
            appGroups = listOf(target, other),
            appRules = listOf(
                AppRule(
                    id = "target",
                    name = "Target allowance",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(target.id),
                    allowedMinutes = 1
                ),
                AppRule(
                    id = "other",
                    name = "Other allowance",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(other.id),
                    allowedMinutes = 1
                )
            )
        )
    }

    private class EmptySessionRepository(
        private val sessions: List<ForegroundSession> = emptyList()
    ) : CurrentUseDaySessionRepository {
        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L
        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit
        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = sessions
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private class SessionRecordingRepository : CurrentUseDaySessionRepository {
        private var nextId = 0L
        private val packagesById = mutableMapOf<Long, String>()
        val startedPackages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finishedPackages = java.util.Collections.synchronizedList(mutableListOf<String>())

        override suspend fun startSession(
            useDayId: String,
            packageName: String,
            startedAtMs: Long
        ): Long = synchronized(this) {
            val id = ++nextId
            packagesById[id] = packageName
            startedPackages += packageName
            id
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            synchronized(this) {
                packagesById[id]?.let(finishedPackages::add)
            }
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private fun scheduledKeys(blocker: AppRuleBlocker): Set<String> {
        val scheduler = blocker.wakeScheduler as? FakeWakeScheduler ?: return emptySet()
        return scheduler.scheduledKeys()
    }

    private fun pendingSchedulerWakePackages(blocker: AppRuleBlocker): Set<String> =
        (getField(blocker, "pendingSchedulerWakeByPackage") as Map<*, *>).keys
            .filterIsInstance<String>()
            .toSet()

    private fun scheduledAlarmToken(
        blocker: AppRuleBlocker,
        packageName: String = PACKAGE
    ): Long {
        val scheduler = checkNotNull(blocker.wakeScheduler as? FakeWakeScheduler) {
            "FakeWakeScheduler is required on blocker"
        }
        val entry = scheduler.getScheduled(packageName)
            ?: error("scheduled alarm registration is missing for $packageName")
        return entry.token
    }

    private fun sendWindowEvent(blocker: AppRuleBlocker, packageName: String = PACKAGE) {
        val event = AccessibilityFrameworkTestObjects.createEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        event.packageName = packageName
        blocker.doAppRuleCheck(event)
        AccessibilityFrameworkTestObjects.releaseEvent(event)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(10L)
        }
        return condition()
    }

    private enum class DeadlineEvidencePhase {
        INITIAL,
        DUE_TICK
    }

    private companion object {
        const val PACKAGE = "com.example.reader"
        const val OTHER_PACKAGE = "com.example.other"
        const val TEST_GUARDIAN_CONNECTION_ID = "guardian-test-connection"
        const val WAIT_TIMEOUT_MS = 2_000L
    }
}
