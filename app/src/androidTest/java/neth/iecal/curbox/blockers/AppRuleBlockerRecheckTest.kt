package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleRecheckPlan
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.LifecycleGeneration
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.RecheckPlanUpdate
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SignalFact
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = PACKAGE
        blocker.doAppRuleCheck(event)
        event.recycle()

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
    fun screenOffImmediatelyEndsForegroundEvidenceAndInvalidatesPendingRecheck() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val queued = ArrayDeque<Runnable>()
        var evaluations = 0
        var posts = 0
        var removals = 0
        val blocker = AppRuleBlocker().apply {
            recheckPostDelayed = { runnable, _ ->
                posts++
                queued.addLast(runnable)
                true
            }
            recheckRemoveCallback = { removals++ }
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
        val screenReceiver = getField(blocker, "screenReceiver") as android.content.BroadcastReceiver
        screenReceiver.onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))
        queued.removeFirst().run()

        assertEquals(null, getField(blocker, "currentForegroundPackage"))
        assertEquals(true, getField(blocker, "foregroundEvidenceSuspended"))
        assertTrue(
            "screen-off must cancel every pending boundary without evaluating",
            (getField(blocker, "scheduledRechecks") as Map<*, *>).isEmpty()
        )
        assertEquals(0, evaluations)
        assertEquals(1, posts)
        assertTrue(removals > 0)
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
        val blocker = AppRuleBlocker()
        val schedulerHandler = android.os.Handler(android.os.Looper.getMainLooper())
        blocker.recheckPostDelayed = { runnable, delayMillis ->
            schedulerHandler.postDelayed(runnable, delayMillis)
            true
        }
        blocker.recheckRemoveCallback = schedulerHandler::removeCallbacks
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
        val now = System.currentTimeMillis()
        val useDayId = ConfigurableUseDayCalculator().idAt(now)
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
            // Keep a short but real guardian remainder so the initial target rule is allowed and
            // the scheduled callback later reevaluates the newly active global denial.
            grantedMillis = 2_000L,
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
        // app open. A global rule is added while the same app remains visible and no new window
        // event is delivered. The handler callback must perform the real re-evaluation.
        sendWindowEvent(blocker)
        assertTrue("the target rule's guardian remainder must keep the app open", service.startedActivities.isEmpty())
        assertTrue(
            "initial guardian remainder must schedule a callback " +
                "(scheduled=${(getField(blocker, "scheduledRechecks") as Map<*, *>).keys})",
            awaitCondition {
                (getField(blocker, "scheduledRechecks") as Map<*, *>).isNotEmpty()
            }
        )
        coordinator.accept(snapshotWithTargetAndGlobalDeny())
        setField(blocker, "lifecycleGeneration", java.util.concurrent.atomic.AtomicLong(1L))
        invokePrivate(blocker, "submitRuntimePublication", 0L)
        SystemClock.sleep(5_000L)

        assertTrue(
            "the scheduled recheck must open approval for the newly active global denial " +
                "(windowsReads=${service.windowsReads}, " +
                "current=${getField(blocker, "currentForegroundPackage")}, " +
                "scheduled=${(getField(blocker, "scheduledRechecks") as Map<*, *>).keys})",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        val intent = service.startedActivities.last()
        val denials = intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS).orEmpty()
        assertTrue("unexpected denial payload: $denials", denials.contains("global"))
        assertTrue(windowSnapshotReads >= 2)
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
        val blocker = AppRuleBlocker().apply {
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
            PACKAGE !in (getField(blocker, "scheduledRechecks") as Map<*, *>).keys
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
        val receiver = getField(blocker, "guardianReceiver") as android.content.BroadcastReceiver

        receiver.onReceive(
            service,
            Intent(GuardianApprovalActivity.INTENT_ACTION_OPENED)
                .putExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE, PACKAGE)
        )
        assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))

        receiver.onReceive(
            service,
            Intent(GuardianApprovalActivity.INTENT_ACTION_CLOSED)
                .putExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE, OTHER_PACKAGE)
        )
        assertEquals(PACKAGE, getField(blocker, "activeGuardianPackage"))

        receiver.onReceive(
            service,
            Intent(GuardianApprovalActivity.INTENT_ACTION_CLOSED)
                .putExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE, PACKAGE)
        )
        assertEquals(null, getField(blocker, "activeGuardianPackage"))
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
        val blocker = AppRuleBlocker()
        val schedulerHandler = android.os.Handler(android.os.Looper.getMainLooper())
        blocker.recheckPostDelayed = { runnable, delayMillis ->
            schedulerHandler.postDelayed(runnable, delayMillis)
            true
        }
        blocker.recheckRemoveCallback = schedulerHandler::removeCallbacks
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
                "(scheduled=${(getField(blocker, "scheduledRechecks") as Map<*, *>).keys})",
            awaitCondition {
                (getField(blocker, "scheduledRechecks") as Map<*, *>).isNotEmpty()
            }
        )
        SystemClock.sleep(4_500L)

        assertTrue(
            "a recent target event must not lose its expiration check to a stale other-app window " +
                "(activities=${service.startedActivities.size}, windowsReads=${service.windowsReads}, " +
                "current=${getField(blocker, "currentForegroundPackage")}, " +
                "evidenceAt=${getField(blocker, "currentForegroundEvidenceAtElapsedMs")}, " +
                "nowElapsed=${SystemClock.elapsedRealtime()}, " +
                "suspended=${getField(blocker, "foregroundEvidenceSuspended")}, " +
                "scheduled=${(getField(blocker, "scheduledRechecks") as Map<*, *>).values})",
            awaitCondition { service.startedActivities.isNotEmpty() }
        )
        blocker.onDestroy()
    }

    @Test
    fun recentForegroundEvidenceSurvivesWindowProviderException() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        service.lastBackPressTimeStamp = 0L
        val blocker = AppRuleBlocker()
        val schedulerHandler = android.os.Handler(android.os.Looper.getMainLooper())
        blocker.recheckPostDelayed = { runnable, delayMillis ->
            schedulerHandler.postDelayed(runnable, delayMillis)
            true
        }
        blocker.recheckRemoveCallback = schedulerHandler::removeCallbacks
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
                (getField(blocker, "scheduledRechecks") as Map<*, *>).isNotEmpty()
            }
        )
        SystemClock.sleep(4_500L)

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
        val blocker = AppRuleBlocker()
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
                val scheduled = (getField(blocker, "scheduledRechecks") as Map<*, *>).keys
                PACKAGE in scheduled && OTHER_PACKAGE in scheduled
            }
        )
        val scheduledPackages = (getField(blocker, "scheduledRechecks") as Map<*, *>).keys
            .map { it.toString() }
            .toSet()
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
        val blocker = AppRuleBlocker()
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
        blocker.recheckPostDelayed = { _, _ -> true }
        blocker.recheckRemoveCallback = {}
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

            wallClockMs.set(dueWallClockMs)
            elapsedRealtimeMs.set(baseElapsedRealtimeMs + 60_000L)
            phase.set(DeadlineEvidencePhase.DUE_TICK)

            // Two independently due alarms reach the same production wake path. The second
            // wake is intentionally delivered before the first visible reconciliation callback.
            blocker.onSchedulerWake()
            blocker.onSchedulerWake()
            assertEquals(
                "independent due alarms must coalesce into one visible tick",
                1,
                visibleCallbacks.size
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
        val blocker = AppRuleBlocker().apply {
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
            recheckPostDelayed = { runnable, delayMillis ->
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
                "(scheduled=${(getField(blocker, "scheduledRechecks") as Map<*, *>).keys}, " +
                "delays=$delays)",
            (getField(blocker, "scheduledRechecks") as Map<*, *>).keys.none {
                it.toString().contains("foreground-observation")
            }
        )
        blocker.onDestroy()
    }

    @Test
    fun schedulerPostFailureRetriesThreeTimesAndRetainsBoundaryState() {
        listOf(false, true).forEach { throws ->
            val service = RecordingService().also { it.attach(InstrumentationContext.context) }
            var postAttempts = 0
            val blocker = AppRuleBlocker().apply {
                recheckPostDelayed = { _, _ ->
                    postAttempts++
                    if (throws) error("scheduler post failed")
                    false
                }
            }
            setField(blocker, "service", service)
            setField(blocker, "setupReady", true)

            invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)

            assertEquals(3, postAttempts)
            assertTrue(
                "failed scheduler posts must retain the package boundary",
                PACKAGE in (getField(blocker, "scheduledRechecks") as Map<*, *>).keys
            )
            blocker.onDestroy()
        }
    }

    @Test
    fun schedulerPostFailureRearmsAndEventuallyExecutesWithoutRunningBeforeWallDeadline() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val primaryQueue = ArrayDeque<Runnable>()
        val recoveryQueue = ArrayDeque<Runnable>()
        val primaryDelays = mutableListOf<Long>()
        var primaryAttempts = 0
        var evaluations = 0
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
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
            recheckPostDelayed = { runnable, delayMillis ->
                primaryAttempts++
                primaryDelays += delayMillis
                if (primaryAttempts <= 3) {
                    false
                } else {
                    primaryQueue.addLast(runnable)
                    true
                }
            }
            recheckRecoveryPostDelayed = { runnable, _ ->
                recoveryQueue.addLast(runnable)
                true
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

        assertEquals(3, primaryAttempts)
        assertTrue("exhausted posts must schedule independent recovery", recoveryQueue.isNotEmpty())
        assertTrue(
            "every failed retry must wait for the original wall deadline",
            primaryDelays.all { it >= 1_000L }
        )

        wallClockMs += 750L
        elapsedRealtimeMs += 750L
        recoveryQueue.removeFirst().run()
        assertEquals(4, primaryAttempts)
        assertTrue("re-arm must post the callback again", primaryQueue.isNotEmpty())
        assertEquals(
            "re-arm must use only the wall-clock remainder, not wait the worker delay twice",
            250L,
            primaryDelays[3]
        )

        // A callback that happens to be delivered early must be retained, not executed early.
        primaryQueue.removeFirst().run()
        assertEquals(0, evaluations)
        assertTrue("early delivery must re-arm the same boundary", primaryQueue.isNotEmpty())

        wallClockMs += 250L
        elapsedRealtimeMs += 250L
        primaryQueue.removeFirst().run()
        assertTrue(
            "the recovered boundary must eventually execute after its wall deadline",
            awaitCondition { evaluations > 0 }
        )
        blocker.onDestroy()
    }

    @Test
    fun schedulerPostFailureRefreshesExpiredRelativeRecoveryDue() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val primaryQueue = ArrayDeque<Runnable>()
        val recoveryQueue = ArrayDeque<Runnable>()
        val primaryDelays = mutableListOf<Long>()
        var primaryAttempts = 0
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            recheckPostDelayed = { runnable, delayMillis ->
                primaryAttempts++
                primaryDelays += delayMillis
                if (primaryAttempts <= 3) {
                    false
                } else {
                    primaryQueue.addLast(runnable)
                    true
                }
            }
            recheckRecoveryPostDelayed = { runnable, _ ->
                recoveryQueue.addLast(runnable)
                true
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)

        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        assertTrue("failed primary posts must install recovery", recoveryQueue.isNotEmpty())

        wallClockMs += 1_500L
        elapsedRealtimeMs += 1_500L
        recoveryQueue.removeFirst().run()

        assertEquals(
            "an expired recovery boundary must become a fresh relative deadline",
            1_000L,
            primaryDelays[3]
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
        val blocker = AppRuleBlocker().apply {
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
        var primaryAttempts = 0
        var evaluations = 0
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            recheckPostDelayed = { _, _ ->
                primaryAttempts++
                false
            }
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

        // Force two production AlarmManager recovery registrations so the first token becomes
        // stale and the second token is the only one allowed to wake the receiver.
        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val firstToken = scheduledAlarmToken(blocker)
        invokePrivate(blocker, "scheduleRecheck", PACKAGE, 1_000L, 20_000L, 0L)
        val secondToken = scheduledAlarmToken(blocker)
        assertTrue("replacement must receive a distinct alarm token", firstToken != secondToken)
        assertTrue("primary post failures must reach recovery alarm registration", primaryAttempts >= 6)

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
        var primaryAttempts = 0
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            recheckPostDelayed = { _, _ ->
                primaryAttempts++
                false
            }
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
        assertEquals(3, primaryAttempts)
        val recoveryToken = scheduledAlarmToken(blocker)
        assertTrue(
            "the failed primary post must leave a production recovery callback",
            (getField(blocker, "scheduledRecoveryCallbacks") as Map<*, *>).containsKey(PACKAGE)
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
            "plan removal must remove the paired recovery callback",
            (getField(blocker, "scheduledRecoveryCallbacks") as Map<*, *>).isEmpty()
        )
        assertTrue(
            "plan removal must remove the paired alarm token",
            (getField(blocker, "scheduledAlarms") as Map<*, *>).isEmpty()
        )
        blocker.onDestroy()
    }

    @Test
    fun stalePlanCancellationCannotRemoveReplacementRegistration() {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        var wallClockMs = 1_000_000L
        var elapsedRealtimeMs = 5_000L
        var primaryAttempts = 0
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClockMs }
            elapsedRealtimeMsProvider = { elapsedRealtimeMs }
            recheckPostDelayed = { _, _ ->
                primaryAttempts++
                false
            }
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
        assertEquals(6, primaryAttempts)

        // This is the old worker update arriving after a newer plan has installed its recovery.
        applyPlan(1L, null)

        val alarmsAfterStaleCancel = getField(blocker, "scheduledAlarms") as Map<*, *>
        assertEquals(
            "a stale plan cancellation must not remove the replacement alarm",
            replacementToken,
            getField(alarmsAfterStaleCancel[PACKAGE]!!, "token")
        )
        assertTrue(
            "a stale plan cancellation must retain the replacement recovery callback",
            (getField(blocker, "scheduledRecoveryCallbacks") as Map<*, *>).containsKey(PACKAGE)
        )

        applyPlan(2L, null)
        assertTrue((getField(blocker, "scheduledAlarms") as Map<*, *>).isEmpty())
        assertTrue((getField(blocker, "scheduledRecoveryCallbacks") as Map<*, *>).isEmpty())
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
        blocker = AppRuleBlocker().apply {
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
            recheckPostDelayed = { _, _ -> false }
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
            0,
            null
        )

        assertTrue("the observation must install a replacement registration", replacementToken != oldToken)
        val alarmsAfterStaleCancel = getField(blocker, "scheduledAlarms") as Map<*, *>
        assertTrue(
            "the replacement alarm must still be registered: ${alarmsAfterStaleCancel.keys}",
            alarmsAfterStaleCancel.containsKey(PACKAGE)
        )
        assertEquals(
            "a stale NotVisible cancellation must not remove the replacement alarm",
            replacementToken,
            getField(alarmsAfterStaleCancel[PACKAGE]!!, "token")
        )
        assertTrue(
            "a stale NotVisible cancellation must retain the replacement recovery callback",
            (getField(blocker, "scheduledRecoveryCallbacks") as Map<*, *>).containsKey(PACKAGE)
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
            val blocker = AppRuleBlocker().apply {
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
                recheckPostDelayed = { runnable, delayMillis ->
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
            wallClockMs += 1_000L
            elapsedRealtimeMs += 1_000L
            val recoveryAttempts = if (suspended) 1 else 4
            repeat(recoveryAttempts) { attempt ->
                queued.removeFirst().run()
                if (attempt < recoveryAttempts - 1 && queued.isNotEmpty()) {
                    val nextDelay = delays.last()
                    wallClockMs += nextDelay
                    elapsedRealtimeMs += nextDelay
                }
            }

            assertTrue(
                "${if (suspended) "suspended" else "unknown"} recovery must not clamp to 1ms",
                delays.last() >= 20_000L
            )
            val scheduled = (getField(blocker, "scheduledRechecks") as Map<*, *>)
                .get(PACKAGE) ?: error("relative recovery must retain a scheduled package")
            val dueAtWallClockMs = getField(scheduled, "dueAtWallClockMs") as Long?
            assertTrue(
                "relative recovery must own a fresh future absolute due time",
                dueAtWallClockMs != null && dueAtWallClockMs > wallClockMs
            )
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
                0L,
                neth.iecal.curbox.domain.apprules.ObservationKind.RECONNECT
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
            0L,
            neth.iecal.curbox.domain.apprules.ObservationKind.RECONNECT
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
        val blocker = AppRuleBlocker()
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
        val before = (getField(blocker, "scheduledRechecks") as Map<*, *>).size
        val changed = invokePrivateResult(
            blocker,
            "applySettingsSnapshot",
            Settings(appRuleSnapshot = snapshot, isReelCounterOn = false)
        ) as Boolean

        assertTrue("an unrelated DataStore emission must not invalidate a boundary job", !changed)
        assertTrue(
            "the existing app boundary must remain scheduled",
            (getField(blocker, "scheduledRechecks") as Map<*, *>).size == before
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

    private class RecordingService : BaseBlockingService() {
        val startedActivities = mutableListOf<Intent>()
        var startActivityObserver: ((Intent) -> Unit)? = null
        var windowsReads = 0
        var visibleWindows: List<AccessibilityWindowInfo> = emptyList()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
            startActivityObserver?.invoke(intent)
        }

        override fun getWindows(): MutableList<AccessibilityWindowInfo> {
            windowsReads++
            return visibleWindows.toMutableList()
        }
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

    private object InstrumentationContext {
        val context: Context
            get() = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
        if (target is AppRuleBlocker && name == "sessionRepository") {
            val service = target.javaClass.getDeclaredField("service").apply {
                isAccessible = true
            }.get(target) as BaseBlockingService
            target.javaClass.getDeclaredField("usageResetRepository").apply {
                isAccessible = true
                set(target, RoomUsageResetRepository(AppDatabase.getInstance(service)))
            }
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun scheduledAlarmToken(blocker: AppRuleBlocker): Long {
        val alarms = getField(blocker, "scheduledAlarms") as Map<*, *>
        val registration = alarms[PACKAGE] ?: error("scheduled alarm registration is missing")
        return getField(registration, "token") as Long
    }

    private fun invokePrivate(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun invokePrivateResult(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun sendWindowEvent(blocker: AppRuleBlocker, packageName: String = PACKAGE) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = packageName
        blocker.doAppRuleCheck(event)
        event.recycle()
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
        const val WAIT_TIMEOUT_MS = 2_000L
    }
}
