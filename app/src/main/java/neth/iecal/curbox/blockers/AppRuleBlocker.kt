package neth.iecal.curbox.blockers

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neth.iecal.curbox.Constants
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleEvaluation
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleMembershipResolver
import neth.iecal.curbox.domain.apprules.AppRuleReevaluationGate
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleReceiverLifecycle
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.AppRuleWallClockScheduler
import neth.iecal.curbox.domain.apprules.AppRuleWakeScheduler
import neth.iecal.curbox.domain.apprules.AndroidAppRuleWakeScheduler
import neth.iecal.curbox.domain.apprules.ActiveRootFact
import neth.iecal.curbox.domain.apprules.AndroidForegroundObservationSource
import neth.iecal.curbox.domain.apprules.AppUsageTrackingDecision
import neth.iecal.curbox.domain.apprules.AppUsageTrackingPolicy
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFreshness
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFact
import neth.iecal.curbox.domain.apprules.AtomicConnectionScopedSourceOrderSequencer
import neth.iecal.curbox.domain.apprules.DisplayState
import neth.iecal.curbox.domain.apprules.ForegroundEvidenceModule
import neth.iecal.curbox.domain.apprules.ForegroundEvidenceOutcome
import neth.iecal.curbox.domain.apprules.ForegroundEvidencePolicySnapshot
import neth.iecal.curbox.domain.apprules.ForegroundFacts
import neth.iecal.curbox.domain.apprules.ForegroundReadState
import neth.iecal.curbox.domain.apprules.FollowUpKind
import neth.iecal.curbox.domain.apprules.AcceptedRuleRuntimeSnapshot
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.DecisionOutcomeSink
import neth.iecal.curbox.domain.apprules.DecisionRequest
import neth.iecal.curbox.domain.apprules.DeadlineDrainStop
import neth.iecal.curbox.domain.apprules.DrainResult
import neth.iecal.curbox.domain.apprules.LifecycleGeneration
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.ObservationTrigger
import neth.iecal.curbox.domain.apprules.RecoveryOnlyStop
import neth.iecal.curbox.domain.apprules.RecheckPlanUpdate
import neth.iecal.curbox.domain.apprules.RuleRuntimeSnapshot
import neth.iecal.curbox.domain.apprules.RuntimePublication
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SerializedDecisionWorker
import neth.iecal.curbox.domain.apprules.SignalFact
import neth.iecal.curbox.domain.apprules.SubmissionResult
import neth.iecal.curbox.domain.apprules.StopReason
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.domain.apprules.TotalDrainDeadline
import neth.iecal.curbox.domain.apprules.UsageResetCommandPolicy
import neth.iecal.curbox.domain.apprules.UsageResetRequest
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationFormatter
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationStateCalculator
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import neth.iecal.curbox.utils.UsageResetManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Adapter-local identity for one installed worker instance. */
@JvmInline
private value class AppRuleWorkerInstanceToken(val value: Long)

/** Enforces the new atomic app-rule snapshot without changing the legacy blocker. */
class AppRuleBlocker(wakeScheduler: AppRuleWakeScheduler? = null) {
    companion object {
        const val INTENT_ACTION_REFRESH_APP_RULES = "neth.iecal.curbox.refresh.app_rules"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_VISIBILITY_RETRIES = 3
        private const val MAX_SCHEDULER_POST_ATTEMPTS = 3
        private const val VISIBILITY_RETRY_DELAY_MS = 250L
        private const val UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS = 20_000L
        private const val EXTERNAL_EFFECT_RESERVED = 0
        private const val EXTERNAL_EFFECT_STARTING = 1
        private const val EXTERNAL_EFFECT_ARMED = 2
        private const val EXTERNAL_EFFECT_RUNNING = 3
        private const val EXTERNAL_EFFECT_FINISHED = 4
        private const val OBSERVATION_RECHECK_KEY = "\u0000foreground-observation"
        private const val SCHEDULER_WAKE_ACTION =
            "neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE"
        private const val EXTRA_SCHEDULER_PACKAGE =
            "neth.iecal.curbox.blockers.EXTRA_SCHEDULER_PACKAGE"
        private const val EXTRA_SCHEDULER_TOKEN =
            "neth.iecal.curbox.blockers.EXTRA_SCHEDULER_TOKEN"

        internal fun createGuardianApprovalIntent(
            context: Context,
            packageName: String,
            denials: List<AppRuleGuardianDenial>
        ): Intent = Intent(context, GuardianApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(GuardianApprovalActivity.EXTRA_PACKAGE, packageName)
            putExtra(GuardianApprovalActivity.EXTRA_DENIALS, Gson().toJson(denials))
        }
    }

    private lateinit var service: BaseBlockingService
    /** Captured outside runtimeLock; direct framework property reads never occur under the lock. */
    @Volatile private var servicePackageName: String = BuildConfig.APPLICATION_ID
    private lateinit var crashLogger: CrashLogger
    private lateinit var sessionRepository: CurrentUseDaySessionRepository
    private lateinit var usageResetRepository: RoomUsageResetRepository
    private lateinit var enforcement: AppRuleEnforcement
    private val snapshot = AppRuleSnapshotCoordinator()
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshMutex = Mutex()
    private val runtimeLock = Any()
    /** Serializes readiness, runtime capture, replacement, and installation as one handoff. */
    private val decisionWorkerLock = Any()
    @Volatile private var handlerInstance: Handler? = null
    private fun getHandler(): Handler {
        var h = handlerInstance
        if (h == null) {
            h = Handler(Looper.getMainLooper())
            handlerInstance = h
        }
        return h
    }
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var notificationTickJob: kotlinx.coroutines.Job? = null
    private var liveNotificationJob: kotlinx.coroutines.Job? = null
    private var lastShownAt = 0L
    @Volatile private var launchablePackages: Set<String> = emptySet()
    @Volatile private var essentialPackages: Set<String> = emptySet()
    private var packageScopeReader: AppRulePackageScopeReader? = null
    private var receiverLifecycle: AppRuleReceiverLifecycle? = null
    @Volatile private var setupReady = false
    @Volatile private var destroyed = false
    @Volatile private var resetTime = UseDayResetTime()
    @Volatile private var useDayGenerationStartedAtMs = 0L
    @Volatile private var overrideState = AppRuleOverrideState()
    @Volatile
    private var usageTrackingDecision: AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(
        statisticsTrackingEnabled = true,
        hasActiveTimeBasedRules = true
    )
    private val reevaluationGate = AppRuleReevaluationGate()
    @Volatile private var lastPostedNotificationModel: LiveRuleNotificationModel? = null
    private data class PendingNotificationPublication(
        val token: Long,
        val model: LiveRuleNotificationModel
    )
    private val notificationPublicationSequence = AtomicLong(0L)
    private var pendingNotificationPublication: PendingNotificationPublication? = null
    @Volatile private var activeGuardianPackage: String? = null
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var lastNonessentialForegroundPackage: String? = null
    @Volatile private var currentForegroundEvidenceAtElapsedMs = 0L
    @Volatile private var suspendedForegroundPackage: String? = null
    @Volatile private var foregroundEvidenceSuspended = false
    @Volatile private var screenOnAwaitingUserPresent = false
    private val recheckGeneration = AtomicLong(0L)
    private val lifecycleGeneration = AtomicLong(0L)
    private val schedulerTokenSequence = AtomicLong(0L)
    private var pendingSchedulerWakeGeneration: Long? = null
    private var foregroundEvidenceModule = ForegroundEvidenceModule()
    private var foregroundObservationSource: AndroidForegroundObservationSource? = null
    private var sourceOrderSequencer = AtomicConnectionScopedSourceOrderSequencer()
    private var decisionWorker: SerializedDecisionWorker? = null
    /** Distinguishes replacement workers that share one connection generation. */
    @Volatile private var currentWorkerInstanceToken: AppRuleWorkerInstanceToken? = null
    private val workerInstanceSequence = AtomicLong(0L)
    @Volatile private var latestRuntimeRevision = RuntimeRevision(0L)
    private val inFlightRefreshes = AtomicInteger(0)
    private val inFlightNotifications = AtomicInteger(0)
    private val inFlightCallbacks = AtomicInteger(0)
    private val inFlightUsageResetCompletions = AtomicInteger(0)
    private val inFlightRecheckPlans = AtomicInteger(0)
    /** External calls reserve a permit here before leaving the lifecycle lock. */
    private val pendingExternalEffects = mutableSetOf<ExternalEffectPermit>()
    private val drainMonitor = Object()

    private data class RuleRuntime(
        val snapshot: AppRuleSnapshot,
        val resetTime: UseDayResetTime,
        val useDayGenerationStartedAtMs: Long,
        val overrideState: AppRuleOverrideState,
        val launchablePackages: Set<String>,
        val generation: Long
    )

    private data class CapturedForegroundObservation(
        val sourceOrderIdentity: neth.iecal.curbox.domain.apprules.SourceOrderIdentity,
        val facts: ForegroundFacts
    )

    private data class CapturedLifecycleBoundary(
        val connectionGeneration: Long,
        val recheckGeneration: Long,
        val foregroundEvidenceSuspended: Boolean
    )

    private class ExternalEffectPermit(
        val counter: AtomicInteger
    ) {
        val state = AtomicInteger(EXTERNAL_EFFECT_RESERVED)
        /** Guarded by runtimeLock; true only while the external invocation is executing. */
        var externalInvocationInProgress = false
        /** Guarded by runtimeLock; covers a synchronous or asynchronous posted callback body. */
        var postedCallbackInProgress = false
    }

    internal data class AppRuleDrainWorkSnapshot(
        val refreshes: Int,
        val notifications: Int,
        val callbacks: Int,
        val usageResetCompletions: Int,
        val recheckPlans: Int
    ) {
        val hasWork: Boolean
            get() = refreshes > 0 || notifications > 0 || callbacks > 0 ||
                usageResetCompletions > 0 || recheckPlans > 0
    }

    internal data class DestroyDrainMeasurement(
        val requestedAtElapsedMs: Long,
        val deadlineElapsedMs: Long,
        val workAtInvalidation: AppRuleDrainWorkSnapshot,
        val workAtCompletion: AppRuleDrainWorkSnapshot,
        val workerResult: DrainResult.DeadlineDrain?,
        val schedulerCleanupCompleted: Boolean,
        val handlerCleanupCompleted: Boolean,
        val workerCleanupCompleted: Boolean,
        val scopeCleanupCompleted: Boolean,
        val receiverCleanupCompleted: Boolean,
        val effectDrainCompleted: Boolean,
        val completedAtElapsedMs: Long
    ) {
        val completed: Boolean
            get() = workerResult?.completed != false &&
                schedulerCleanupCompleted &&
                handlerCleanupCompleted &&
                workerCleanupCompleted &&
                scopeCleanupCompleted &&
                receiverCleanupCompleted &&
                effectDrainCompleted &&
                !workAtCompletion.hasWork &&
                completedAtElapsedMs <= deadlineElapsedMs

        val timedOut: Boolean
            get() = !completed
    }

    private data class ObservedForegroundOutcome(
        val outcome: ForegroundEvidenceOutcome?,
        val facts: ForegroundFacts,
        val capturedBoundary: CapturedLifecycleBoundary
    )

    /** Evidence snapshot kept internal so Android tests can exercise OEM failure combinations. */
    internal data class ApplicationWindowSnapshot(
        val packages: Set<String>,
        val stalePackages: Set<String> = emptySet(),
        val hasApplicationWindow: Boolean,
        val hasUnknownApplicationWindow: Boolean,
        val providerFailed: Boolean = false,
        /** Number of application windows, including windows whose root package is unavailable. */
        val applicationWindowCount: Int = packages.size,
        /** Exact number of application-window slots whose package could not be read. */
        val unknownSlotCount: Int = if (hasUnknownApplicationWindow) {
            (applicationWindowCount - packages.size).coerceAtLeast(1)
        } else {
            0
        },
        val freshness: ApplicationWindowsFreshness = ApplicationWindowsFreshness.FRESH,
        val capturedAtElapsedMs: Long = 0L
    )

    internal data class ActiveWindowSnapshot(
        val packageName: String?,
        val readFailed: Boolean = false
    )

    /** Test seams model framework snapshots that cannot be constructed with public setters. */
    internal var applicationWindowSnapshotProvider: (() -> ApplicationWindowSnapshot)? = null
    internal var activeWindowSnapshotProvider: (() -> ActiveWindowSnapshot)? = null

    internal var wallClockMsProvider: () -> Long = { System.currentTimeMillis() }
    internal var elapsedRealtimeMsProvider: () -> Long = { SystemClock.elapsedRealtime() }
    internal var recheckPostDelayed: ((Runnable, Long) -> Boolean)? = null
    /** Failure recovery uses an independent post seam so a dead primary adapter can re-arm. */
    internal var recheckRecoveryPostDelayed: ((Runnable, Long) -> Boolean)? = null
    internal var recheckRemoveCallback: ((Runnable) -> Unit)? = null
    /** Temporary seam for deterministic wake-reconciliation contract tests. */
    internal var visibleApplicationCheckPostDelayed: ((Runnable, Long) -> Boolean)? = null
    /** Temporary seam for observing removal of virtual wake-reconciliation callbacks. */
    internal var visibleApplicationCheckRemoveCallbacks: (() -> Unit)? = null
    /** Deterministic seam immediately before a Handler-backed callback is posted. */
    internal var handlerBeforeFrameworkPostObserver: (() -> Unit)? = null
    /** Temporary seams for deterministic screen and keyguard recovery contract tests. */
    internal var screenInteractiveProvider: (() -> Boolean)? = null
    internal var keyguardLockedProvider: (() -> Boolean)? = null
    internal var evaluationResultObserver: ((AppRulesEvaluation) -> Unit)? = null
    /** Deterministic seam for observing evaluator request cancellation at its child boundary. */
    internal var decisionRequestCancellationObserver: ((CancellationException) -> Unit)? = null
    /** Records the foreground-evidence state mutation after a provider-backed handler read. */
    internal var foregroundEvidenceRecordObserver: ((String) -> Unit)? = null
    /** Deterministic seam after an accessibility event read and before evidence mutation. */
    internal var foregroundEvidenceBeforeRecordObserver: (() -> Unit)? = null
    /** Temporary seam for forcing concurrent same-lifecycle worker recovery interleavings. */
    internal var decisionWorkerRecoveryAfterCapture: (() -> Unit)? = null
    /** Temporary seam for observing host publication before it reaches the worker handoff lock. */
    internal var runtimePublicationBeforeWorkerHandoff: ((RuntimeRevision) -> Unit)? = null
    /** Temporary seam for observing the external notification publication boundary. */
    internal var notificationPostObserver: ((LiveRuleNotificationModel) -> Unit)? = null
    /** Deterministic seam immediately before the real notification manager publication. */
    internal var notificationUpdateObserver: ((LiveRuleNotificationModel) -> Unit)? = null
    /** Deterministic seam after the final notification recheck and before manager publication. */
    internal var notificationBeforeFrameworkCallObserver:
        ((LiveRuleNotificationModel) -> Unit)? = null
    /** Test-only recorder immediately after the real notification manager publication returns. */
    internal var notificationPublicationObserver: ((LiveRuleNotificationModel) -> Unit)? = null
    /** Deterministic seam immediately before usage-reset completion broadcasts. */
    internal var usageResetCompletionPostObserver: ((UsageResetRequest, Boolean) -> Unit)? = null
    /** Records each actual completion broadcast immediately before Context.sendBroadcast. */
    internal var usageResetBroadcastObserver: ((Intent) -> Unit)? = null
    /** Deterministic seam after the final completion recheck and before Context.sendBroadcast. */
    internal var usageResetBeforeFrameworkCallObserver: ((Intent) -> Unit)? = null
    /** Deterministic seam after warning validation and before Activity.startActivity. */
    internal var warningBeforeFrameworkCallObserver: ((String) -> Unit)? = null
    /** Deterministic seam immediately before scheduler Handler/alarm publication. */
    internal var recheckBeforeFrameworkPostObserver: ((String) -> Unit)? = null
    /** Deterministic seam immediately before AlarmManager publication. */
    internal var alarmBeforeFrameworkCallObserver: ((String) -> Unit)? = null
    /** Test-only recorder after AlarmManager publication returns. */
    internal var alarmPublicationObserver: ((String) -> Unit)? = null
    /** Deterministic seam at worker-generated recheck-plan delivery. */
    internal var recheckPlanDeliveryObserver: ((RecheckPlanUpdate) -> Unit)? = null
    /** Passive seam immediately at DecisionOutcomeSink.publish entry before external effects. */
    internal var decisionOutcomeSinkObserver: ((DecisionOutcome) -> Unit)? = null

    private var isDefaultWakeScheduler = false
    internal var wakeScheduler: AppRuleWakeScheduler? = wakeScheduler
        set(value) {
            field = value
            isDefaultWakeScheduler = false
            value?.onWake = { key, token ->
                onWakeFromScheduler(key, token)
            }
        }

    init {
        this.wakeScheduler = wakeScheduler
    }

    fun setup(service: BaseBlockingService) {
        servicePackageName = service.packageName
        val connectionGeneration = lifecycleGeneration.incrementAndGet()
        val cancelledExternalEffects = synchronized(runtimeLock) {
            destroyed = false
            setupReady = false
            currentWorkerInstanceToken = null
            // A reconnect must invalidate work captured by the previous service connection.
            recheckGeneration.incrementAndGet()
            pendingWorkerEvaluations.clear()
            pendingNotificationPublication = null
            cancelPendingExternalEffectsLocked()
        }
        cancelledExternalEffects.forEach(::finishDrainWork)
        synchronized(decisionWorkerLock) {
            decisionWorker?.stop(
                RecoveryOnlyStop(
                    requestedAtElapsedMs = observationElapsedRealtimeMs(),
                    reason = StopReason.RECONNECT,
                    lifecycleGeneration = LifecycleGeneration(connectionGeneration)
                )
            )
            decisionWorker = null
        }
        settingsJob?.cancel()
        notificationTickJob?.cancel()
        cancelScheduledRechecks()
        handlerInstance?.removeCallbacksAndMessages(null)
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = null
        // A reconnect owns a fresh scope. This cancels untracked refresh/notification jobs from
        // the previous connection instead of relying only on their generation checks.
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        currentForegroundPackage = null
        lastNonessentialForegroundPackage = null
        currentForegroundEvidenceAtElapsedMs = 0L
        suspendedForegroundPackage = null
        foregroundEvidenceSuspended = false
        screenOnAwaitingUserPresent = false
        lastPostedNotificationModel = null
        activeGuardianPackage = null
        pendingSchedulerWakeGeneration = null
        lastShownAt = 0L
        this.service = service
        if (wakeScheduler == null || isDefaultWakeScheduler) {
            isDefaultWakeScheduler = true
            wakeScheduler = AndroidAppRuleWakeScheduler(
                context = service,
                wallClockMs = { wallClockMsProvider() },
                elapsedRealtimeMs = { elapsedRealtimeMsProvider() },
                onNonFatalError = ::logNonFatal
            )
        }
        crashLogger = CrashLogger(service)
        foregroundEvidenceModule = ForegroundEvidenceModule()
        sourceOrderSequencer = AtomicConnectionScopedSourceOrderSequencer()
        foregroundObservationSource = AndroidForegroundObservationSource(
            service = service,
            wallClockMs = { wallClockMsProvider() },
            elapsedRealtimeMs = { elapsedRealtimeMsProvider() },
            onNonFatalError = ::logNonFatal
        )
        val database = AppDatabase.getInstance(service)
        sessionRepository = RoomCurrentUseDaySessionRepository(
            database.foregroundSessionDao(),
            database.foregroundLaunchDao(),
            database.appUsageDao(),
            database
        )
        usageResetRepository = RoomUsageResetRepository(database)
        enforcement = AppRuleEnforcement(sessionRepository)
        packageScopeReader = AppRulePackageScopeReader.fromContext(service)
        refreshPackageScope()
        try {
            val initialSettings = runBlocking(Dispatchers.IO) {
                service.dataStoreManager.compactAppRuleOverrides()
                service.dataStoreManager.settings.first()
            }
            synchronized(runtimeLock) {
                resetTime = safeResetTime(initialSettings.useDayResetHour, initialSettings.useDayResetMinute)
                useDayGenerationStartedAtMs = initialSettings.useDayGenerationStartedAtMs
                overrideState = initialSettings.appRuleOverrideState
                val initial = initialSettings.appRuleSnapshot
                snapshot.accept(initial)
                usageTrackingDecision = AppUsageTrackingPolicy.decide(
                    statisticsTrackingEnabled = initialSettings.isAppUsageTrackingEnabled,
                    hasActiveTimeBasedRules = initial.appRules.any { it.isActive }
                )
            }
            createDecisionWorker(connectionGeneration)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
        settingsJob = scope.launch {
            try {
                service.dataStoreManager.settings.collect { settings ->
                    val finishRefresh = beginDrainWork(inFlightRefreshes)
                    try {
                        if (!isReadyForChecks(connectionGeneration)) return@collect
                        // Allocate ordering at the source observation, before this emission can
                        // wait for the shared publication path.
                        val reservation = sourceOrderSequencer.reserveRuntimePublication()
                        // Serialize settings emissions with the explicit refresh receiver. A
                        // burst of DataStore writes must not let an older refresh publish after a
                        // newer one.
                        val accepted = refreshMutex.withLock {
                            applyAndSubmitRuntimePublication(
                                connectionGeneration = connectionGeneration,
                                settings = settings,
                                sourceOrderIdentity = reservation.sourceOrderIdentity,
                                runtimeRevision = reservation.runtimeRevision
                            )
                        }
                        if (!isReadyForChecks(connectionGeneration)) return@collect
                        if (accepted) {
                            postVisibleApplicationCheck(
                                connectionGeneration = connectionGeneration,
                                observationKind = ObservationKind.REFRESH
                            )
                        }
                        updateLiveNotification()
                    } finally {
                        finishRefresh()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
        setupReady = true
        startNotificationTicker(connectionGeneration)
        updateLiveNotification()
        // Reconnection does not guarantee a new WINDOW_STATE_CHANGED event. Reconcile what is
        // already visible while the service is alive.
        postVisibleApplicationCheck(connectionGeneration = connectionGeneration)
    }

    fun setupReceivers() {
        if (!setupReady) return
        val filter = IntentFilter(INTENT_ACTION_REFRESH_APP_RULES)
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val guardianFilter = IntentFilter().apply {
            addAction(GuardianApprovalActivity.INTENT_ACTION_CLOSED)
            addAction(GuardianApprovalActivity.INTENT_ACTION_OPENED)
        }
        val lifecycle = AppRuleReceiverLifecycle(
            registrations = listOf(
                AppRuleReceiverLifecycle.Registration(
                    register = {
                        ContextCompat.registerReceiver(
                            service,
                            refreshReceiver,
                            filter,
                            ContextCompat.RECEIVER_EXPORTED
                        )
                    },
                    unregister = { service.unregisterReceiver(refreshReceiver) }
                ),
                AppRuleReceiverLifecycle.Registration(
                    register = {
                        ContextCompat.registerReceiver(
                            service,
                            packageReceiver,
                            packageFilter,
                            ContextCompat.RECEIVER_EXPORTED
                        )
                    },
                    unregister = { service.unregisterReceiver(packageReceiver) }
                ),
                AppRuleReceiverLifecycle.Registration(
                    register = {
                        ContextCompat.registerReceiver(
                            service,
                            screenReceiver,
                            screenFilter,
                            ContextCompat.RECEIVER_EXPORTED
                        )
                    },
                    unregister = { service.unregisterReceiver(screenReceiver) }
                ),
                AppRuleReceiverLifecycle.Registration(
                    register = {
                        ContextCompat.registerReceiver(
                            service,
                            schedulerWakeReceiver,
                            IntentFilter(SCHEDULER_WAKE_ACTION),
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    },
                    unregister = { service.unregisterReceiver(schedulerWakeReceiver) }
                ),
                AppRuleReceiverLifecycle.Registration(
                    register = {
                        ContextCompat.registerReceiver(
                            service,
                            guardianReceiver,
                            guardianFilter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    },
                    unregister = { service.unregisterReceiver(guardianReceiver) }
                )
            ),
            isReady = { setupReady }
        )
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = lifecycle
        try {
            lifecycle.register()
            service.sendBroadcast(
                Intent(GuardianApprovalActivity.INTENT_ACTION_STATE_REQUEST)
                    .setPackage(service.packageName)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    /** Fresh connection initialization only; setup has just reset its connection-scoped sequencer. */
    private fun createDecisionWorker(connectionGeneration: Long): SerializedDecisionWorker =
        synchronized(decisionWorkerLock) {
            val acceptedRuntime = synchronized(runtimeLock) {
                val initialRevision = RuntimeRevision(0L)
                latestRuntimeRevision = initialRevision
                AcceptedRuleRuntimeSnapshot(
                    runtime = ruleRuntimeSnapshotLocked(),
                    runtimeRevision = initialRevision
                )
            }
            checkNotNull(installDecisionWorkerLocked(connectionGeneration, acceptedRuntime))
        }

    /** Same-lifecycle recovery must inherit host freshness instead of reinitializing it. */
    private fun installDecisionWorkerLocked(
        connectionGeneration: Long,
        acceptedRuntime: AcceptedRuleRuntimeSnapshot,
        requireReadyForSubmission: Boolean = false
    ): SerializedDecisionWorker? {
        synchronized(runtimeLock) {
            if (requireReadyForSubmission && !isReadyForChecks(connectionGeneration)) {
                return null
            }
            // Invalidate the old instance before stopping it. A replacement can share this
            // connection generation, so lifecycle and runtime revisions are not sufficient.
            currentWorkerInstanceToken = null
        }
        decisionWorker?.stop(
            RecoveryOnlyStop(
                requestedAtElapsedMs = observationElapsedRealtimeMs(),
                reason = StopReason.RECONNECT,
                lifecycleGeneration = LifecycleGeneration(connectionGeneration)
            )
        )
        val worker = synchronized(runtimeLock) {
            if (requireReadyForSubmission && !isReadyForChecks(connectionGeneration)) {
                return null
            }
            val workerInstanceToken =
                AppRuleWorkerInstanceToken(workerInstanceSequence.incrementAndGet())
            SerializedDecisionWorker(
                lifecycleGeneration = LifecycleGeneration(connectionGeneration.coerceAtLeast(1L)),
                acceptedRuntime = acceptedRuntime,
                repository = sessionRepository,
                outcomeSink = object : DecisionOutcomeSink {
                    override fun publish(outcome: DecisionOutcome) {
                        decisionOutcomeSinkObserver?.invoke(outcome)
                        publishDecisionOutcome(outcome, workerInstanceToken)
                    }
                },
                workerScope = scope,
                onNonFatalError = ::logNonFatal,
                onEvaluation = { request, accepted, packageName, evaluation ->
                    observeWorkerEvaluation(
                        workerInstanceToken,
                        request,
                        accepted,
                        packageName,
                        evaluation
                    )
                },
                onRequestCancellation = { error ->
                    decisionRequestCancellationObserver?.invoke(error)
                },
                onRecheckPlan = { update ->
                    applyRecheckPlan(workerInstanceToken, update)
                },
                usageResetRepository = usageResetRepository,
                onUsageResetComplete = { request, succeeded ->
                    publishUsageResetComplete(workerInstanceToken, request, succeeded)
                },
                enforcement = enforcement,
                elapsedRealtimeMs = { observationElapsedRealtimeMs() }
            ).also {
                currentWorkerInstanceToken = workerInstanceToken
            }
        }
        decisionWorker = worker
        return worker
    }

    private fun ensureDecisionWorker(connectionGeneration: Long): SerializedDecisionWorker? =
        synchronized(decisionWorkerLock) {
            if (!isReadyForChecks(connectionGeneration)) return@synchronized null
            decisionWorker?.let { worker ->
                if (worker.isReadyForSubmission()) return@synchronized worker
                worker.stop(
                    RecoveryOnlyStop(
                        requestedAtElapsedMs = observationElapsedRealtimeMs(),
                        reason = StopReason.REPLACEMENT,
                        lifecycleGeneration = LifecycleGeneration(connectionGeneration)
                    )
                )
                decisionWorker = null
            }
            check(::sessionRepository.isInitialized) { "app-rule session repository is not ready" }
            val acceptedRuntime = synchronized(runtimeLock) {
                AcceptedRuleRuntimeSnapshot(
                    runtime = ruleRuntimeSnapshotLocked(),
                    runtimeRevision = latestRuntimeRevision
                )
            }
            decisionWorkerRecoveryAfterCapture?.invoke()
            installDecisionWorkerLocked(
                connectionGeneration = connectionGeneration,
                acceptedRuntime = acceptedRuntime,
                requireReadyForSubmission = true
            )
        }

    private fun ruleRuntimeSnapshot(): RuleRuntimeSnapshot = synchronized(runtimeLock) {
        ruleRuntimeSnapshotLocked()
    }

    private fun ruleRuntimeSnapshotLocked(): RuleRuntimeSnapshot =
        RuleRuntimeSnapshot(
            snapshot = snapshot.snapshot(),
            resetTime = resetTime,
            useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
            overrideState = overrideState,
            launchablePackages = launchablePackages,
            evidencePolicy = ForegroundEvidencePolicySnapshot(
                essentialPackages = essentialPackages +
                    setOf(servicePackageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            ),
            usageTrackingDecision = usageTrackingDecision
        )

    private fun applyAndSubmitRuntimePublication(
        connectionGeneration: Long,
        settings: Settings,
        sourceOrderIdentity: SourceOrderIdentity,
        runtimeRevision: RuntimeRevision
    ): Boolean {
        if (!isReadyForChecks(connectionGeneration)) return false
        applySettingsSnapshot(
            settings = settings,
            runtimeRevision = runtimeRevision,
            connectionGeneration = connectionGeneration
        )
        val accepted = isAcceptedRuntimeRevision(runtimeRevision)
        if (accepted) {
            runtimePublicationBeforeWorkerHandoff?.invoke(runtimeRevision)
            submitRuntimePublication(
                connectionGeneration = connectionGeneration,
                sourceOrderIdentity = sourceOrderIdentity,
                runtimeRevision = runtimeRevision
            )
        }
        return accepted
    }

    /**
     * Compatibility path for the existing private scheduler test seam. Production refresh paths
     * allocate both identities before entering their serialized publication section below.
     */
    private fun submitRuntimePublication(connectionGeneration: Long) {
        val reservation = sourceOrderSequencer.reserveRuntimePublication()
        synchronized(runtimeLock) {
            latestRuntimeRevision = reservation.runtimeRevision
        }
        submitRuntimePublication(
            connectionGeneration = connectionGeneration,
            sourceOrderIdentity = reservation.sourceOrderIdentity,
            runtimeRevision = reservation.runtimeRevision
        )
    }

    private fun submitRuntimePublication(
        connectionGeneration: Long,
        sourceOrderIdentity: SourceOrderIdentity,
        runtimeRevision: RuntimeRevision
    ) {
        val normalizedConnectionGeneration = connectionGeneration.coerceAtLeast(1L)
        if (!isReadyForChecks(normalizedConnectionGeneration)) return
        val nowWallMs = observationWallClockMs()
        val nowElapsedMs = observationElapsedRealtimeMs()
        val request = DecisionRequest(
            sourceOrderIdentity = sourceOrderIdentity,
            lifecycleGeneration = LifecycleGeneration(normalizedConnectionGeneration),
            reason = ObservationKind.REFRESH,
            observation = ForegroundFacts(
                capturedAtWallMs = nowWallMs,
                capturedAtElapsedMs = nowElapsedMs,
                signal = SignalFact(kind = ObservationKind.REFRESH),
                displayState = DisplayState.UNLOCKED
            ),
            runtimePublication = RuntimePublication(
                runtimeRevision = runtimeRevision,
                candidateRuntime = ruleRuntimeSnapshot()
            )
        )
        submitDecisionRequest(
            request = request,
            connectionGeneration = normalizedConnectionGeneration,
            operation = "runtime publication"
        )
    }

    private fun publishDecisionOutcome(
        outcome: DecisionOutcome,
        workerInstanceToken: AppRuleWorkerInstanceToken
    ) {
        val permit = synchronized(runtimeLock) {
            reserveExternalEffectLocked(inFlightCallbacks) {
                isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
            }
        } ?: return
        if (!startExternalEffect(permit) {
            isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
        }) return
        val posted = try {
            handlerBeforeFrameworkPostObserver?.invoke()
            if (!beginExternalEffectCall(permit) {
                    isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
                }
            ) return
            getHandler().post {
                if (!enterPostedEffect(permit)) return@post
                try {
                    if (!isCurrentWorkerOutcome(outcome, workerInstanceToken)) return@post
                    val denied = outcome.packageDecisions.firstOrNull { !it.isAllowed }
                    if (denied == null) {
                        synchronized(runtimeLock) {
                            outcome.packageDecisions.firstOrNull()?.packageName?.let { packageName ->
                                if (activeGuardianPackage == packageName) {
                                    activeGuardianPackage = null
                                }
                            }
                        }
                        return@post
                    }
                    val evaluated = synchronized(runtimeLock) {
                        pendingWorkerEvaluations.remove(outcome.sourceOrderIdentity)
                            ?.get(denied.packageName)
                    }
                    if (evaluated != null) {
                        val now = observationWallClockMs()
                        val bypassThrottle = reevaluationGate.consumeIfApplicable(
                            evaluated.evaluations.isNotEmpty()
                        )
                        if (bypassThrottle || now - lastShownAt >= 1_000L) {
                            lastShownAt = now
                            showWarning(
                                packageName = denied.packageName,
                                evaluation = evaluated,
                                evaluatedSnapshot = captureRuleRuntime().snapshot,
                                generation = recheckGeneration.get(),
                                expectedLifecycleGeneration = outcome.lifecycleGeneration,
                                workerInstanceToken = workerInstanceToken
                            )
                        }
                    } else {
                        showWarningFromDecision(outcome, denied, workerInstanceToken)
                    }
                } finally {
                    finishExternalEffect(permit)
                }
            }
        } catch (error: CancellationException) {
            finishExternalEffect(permit)
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            finishExternalEffect(permit)
            return
        }
        armPostedEffect(permit, posted) {
            isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
        }
    }

    private fun showWarningFromDecision(
        outcome: DecisionOutcome,
        decision: neth.iecal.curbox.domain.apprules.PackageDecision,
        workerInstanceToken: AppRuleWorkerInstanceToken
    ) {
        if (!isCurrentWorkerOutcome(outcome, workerInstanceToken)) return
        if (!service.isDelayOver(1_000)) return
        val evaluatedSnapshot = synchronized(runtimeLock) {
            if (!isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken) ||
                activeGuardianPackage == decision.packageName
            ) return
            snapshot.snapshot()
        }
        val denials = decision.denyingRuleIds.map { ruleId ->
            val rule = evaluatedSnapshot.appRules.find { it.id == ruleId }
            AppRuleGuardianDenial(
                ruleId = ruleId,
                ruleName = rule?.name ?: ruleId,
                reason = service.getString(R.string.app_rules_warning_status_no_condition, 0L, 0L, 0L)
            )
        }
        val permit = synchronized(runtimeLock) {
            if (activeGuardianPackage == decision.packageName) {
                null
            } else {
                reserveExternalEffectLocked(inFlightCallbacks) {
                    isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken) &&
                        activeGuardianPackage == null
                }?.also {
                    activeGuardianPackage = decision.packageName
                }
            }
        } ?: return
        if (!startExternalEffect(permit) {
            isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
        }) {
            synchronized(runtimeLock) {
                if (activeGuardianPackage == decision.packageName) activeGuardianPackage = null
            }
            return
        }
        try {
            warningBeforeFrameworkCallObserver?.invoke(decision.packageName)
            if (!beginExternalEffectCall(permit) {
                    isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
                }
            ) {
                synchronized(runtimeLock) {
                    if (activeGuardianPackage == decision.packageName) {
                        activeGuardianPackage = null
                    }
                }
                return
            }
            service.startActivity(createGuardianApprovalIntent(service, decision.packageName, denials))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
        } finally {
            completeExternalEffectCall(permit)
            finishExternalEffect(permit)
        }
    }

    private fun isCurrentWorkerOutcome(
        outcome: DecisionOutcome,
        workerInstanceToken: AppRuleWorkerInstanceToken
    ): Boolean = synchronized(runtimeLock) {
        isCurrentWorkerOutcomeLocked(outcome, workerInstanceToken)
    }

    private fun isCurrentWorkerOutcomeLocked(
        outcome: DecisionOutcome,
        workerInstanceToken: AppRuleWorkerInstanceToken
    ): Boolean =
        isReadyForChecks() &&
            outcome.lifecycleGeneration == currentLifecycleGeneration() &&
            workerInstanceMatchesLocked(workerInstanceToken) &&
            outcome.acceptedRuntimeRevision == latestRuntimeRevision &&
            outcome.publicationStatus == neth.iecal.curbox.domain.apprules.PublicationStatus.PUBLISHED

    private val pendingWorkerEvaluations =
        mutableMapOf<neth.iecal.curbox.domain.apprules.SourceOrderIdentity, MutableMap<String, AppRulesEvaluation>>()

    private fun observeWorkerEvaluation(
        workerInstanceToken: AppRuleWorkerInstanceToken,
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot,
        packageName: String,
        evaluation: AppRulesEvaluation
    ) {
        if (!isCurrentWorkerRequest(workerInstanceToken, request, accepted)) return
        val evidenceAtElapsedMs = observationElapsedRealtimeMs()
        synchronized(runtimeLock) {
            if (!isCurrentWorkerRequestLocked(workerInstanceToken, request, accepted) ||
                foregroundEvidenceSuspended
            ) return
            if (request.reason != ObservationKind.SYNTHETIC_RECHECK) {
                recordForegroundEvidence(
                    packageName = packageName,
                    evidenceAtElapsedMs = evidenceAtElapsedMs
                )
            }
            pendingWorkerEvaluations
                .getOrPut(request.sourceOrderIdentity) { mutableMapOf() }[packageName] = evaluation
        }
        if (!isCurrentWorkerRequest(workerInstanceToken, request, accepted)) return
        val observer = evaluationResultObserver ?: return
        val permit = synchronized(runtimeLock) {
            reserveExternalEffectLocked(inFlightCallbacks) {
                isCurrentWorkerRequestLocked(workerInstanceToken, request, accepted)
            }
        } ?: return
        try {
            if (!startExternalEffect(permit) {
                    isCurrentWorkerRequestLocked(workerInstanceToken, request, accepted)
                }
            ) return
            if (!isCurrentWorkerRequest(workerInstanceToken, request, accepted)) return
            if (!beginExternalEffectCall(permit) {
                    isCurrentWorkerRequestLocked(workerInstanceToken, request, accepted)
                }
            ) return
            observer(evaluation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
        } finally {
            completeExternalEffectCall(permit)
            finishExternalEffect(permit)
        }
    }

    private fun isCurrentWorkerRequest(
        workerInstanceToken: AppRuleWorkerInstanceToken,
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot
    ): Boolean = synchronized(runtimeLock) {
        isCurrentWorkerRequestLocked(workerInstanceToken, request, accepted)
    }

    private fun isCurrentWorkerRequestLocked(
        workerInstanceToken: AppRuleWorkerInstanceToken,
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot
    ): Boolean =
        isReadyForChecks() &&
            request.lifecycleGeneration == currentLifecycleGeneration() &&
            workerInstanceMatchesLocked(workerInstanceToken) &&
            accepted.runtimeRevision == latestRuntimeRevision

    /** Private compatibility seam for the direct scheduler tests; production supplies the token. */
    private fun applyRecheckPlan(update: RecheckPlanUpdate) {
        applyRecheckPlan(workerInstanceToken = null, update = update)
    }

    private fun applyRecheckPlan(
        workerInstanceToken: AppRuleWorkerInstanceToken?,
        update: RecheckPlanUpdate
    ) {
        val permit = synchronized(runtimeLock) {
            reserveExternalEffectLocked(inFlightRecheckPlans) {
                isCurrentWorkerRecheckLocked(workerInstanceToken, update)
            }
        } ?: return
        try {
            recheckPlanDeliveryObserver?.let { observer ->
                observer(update)
            }
            if (!startExternalEffect(permit) {
                    isCurrentWorkerRecheckLocked(workerInstanceToken, update)
                }
            ) return
            // Revalidate immediately after the delivery barrier and before scheduler state or
            // registration changes. Destroy can cancel the reserved permit while the observer is
            // blocked.
            synchronized(runtimeLock) {
                if (!isCurrentWorkerRecheckLocked(workerInstanceToken, update)) return
            }
            val plan = update.plan
            if (plan == null) {
                cancelScheduledRecheck(update.packageName)
            } else {
                scheduleRecheckAtWallClock(
                    packageName = update.packageName,
                    dueAtWallClockMs = plan.dueAtWallClockMs
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
        } finally {
            finishExternalEffect(permit)
        }
    }

    private fun isCurrentWorkerRecheck(
        workerInstanceToken: AppRuleWorkerInstanceToken?,
        update: RecheckPlanUpdate
    ): Boolean = synchronized(runtimeLock) {
        isCurrentWorkerRecheckLocked(workerInstanceToken, update)
    }

    private fun isCurrentWorkerRecheckLocked(
        workerInstanceToken: AppRuleWorkerInstanceToken?,
        update: RecheckPlanUpdate
    ): Boolean =
        isReadyForChecks() &&
            !foregroundEvidenceSuspended &&
            update.lifecycleGeneration == currentLifecycleGeneration() &&
            (workerInstanceToken == null || workerInstanceMatchesLocked(workerInstanceToken)) &&
            latestRuntimeRevision == update.acceptedRuntimeRevision

    fun doAppRuleCheck(event: AccessibilityEvent?) {
        if (event == null) return
        val capturedBoundary = captureLifecycleBoundary() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackageName = event.packageName?.toString()?.trim().orEmpty()
        if (eventPackageName.isBlank()) return

        val packageChanged = eventPackageName != currentForegroundPackage
        if (isNonessentialPackage(eventPackageName)) {
            // Keep the last concrete target available for a concurrent SCREEN_OFF callback. The
            // worker still owns the authoritative classification; this is only raw evidence.
            foregroundEvidenceBeforeRecordObserver?.invoke()
            recordForegroundEvidenceIfCurrent(
                packageName = eventPackageName,
                evidenceAtElapsedMs = observationElapsedRealtimeMs(),
                expectedConnectionGeneration = capturedBoundary.connectionGeneration,
                expectedRecheckGeneration = capturedBoundary.recheckGeneration,
                requireSuspended = capturedBoundary.foregroundEvidenceSuspended
            )
        }
        if (packageChanged) {
            updateLiveNotification(eventPackageName)
        }
        submitForegroundDecision(
            event = event,
            kind = ObservationKind.REAL_EVENT,
            capturedBoundary = capturedBoundary
        )
    }

    /** Called by the scheduler adapter when a delayed wall-clock plan becomes runnable. */
    internal fun onSchedulerWake() {
        onSchedulerWake(recheckGeneration.get())
    }

    private fun onSchedulerWake(expectedGeneration: Long) {
        val connectionGeneration = synchronized(runtimeLock) {
            if (!isReadyForChecks() || recheckGeneration.get() != expectedGeneration) return
            if (pendingSchedulerWakeGeneration == expectedGeneration) return
            pendingSchedulerWakeGeneration = expectedGeneration
            lifecycleGeneration.get()
        }
        postVisibleApplicationCheck(
            connectionGeneration = connectionGeneration,
            observationKind = ObservationKind.REFRESH
        )
    }

    /** Enqueues a reset behind the worker's already accepted foreground observations. */
    internal fun submitUsageReset(packages: Set<String>, requestId: String): Boolean {
        val normalizedPackages = packages.map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (normalizedPackages.isEmpty() || !isReadyForChecks()) return false
        val runtime = captureRuleRuntime()
        val resetAtMs = UsageResetCommandPolicy.acceptedAt(observationWallClockMs())
        val request = UsageResetRequest(
            useDayId = ConfigurableUseDayCalculator(resetTime = runtime.resetTime).idAt(resetAtMs),
            generationStartedAtMs = runtime.useDayGenerationStartedAtMs,
            packageNames = normalizedPackages,
            resetAtMs = resetAtMs,
            requestId = requestId
        )
        val workerAndToken = synchronized(decisionWorkerLock) {
            decisionWorker to synchronized(runtimeLock) { currentWorkerInstanceToken }
        }
        val worker = workerAndToken.first
        val result = worker?.submitUsageReset(
            request = request,
            resetAtElapsedMs = observationElapsedRealtimeMs()
        )
        if (result == SubmissionResult.ACCEPTED) return true
        workerAndToken.second?.let { token ->
            publishUsageResetComplete(token, request, succeeded = false)
        }
        return true
    }

    private fun publishUsageResetComplete(
        workerInstanceToken: AppRuleWorkerInstanceToken,
        request: UsageResetRequest,
        succeeded: Boolean
    ) {
        val permit = synchronized(runtimeLock) {
            reserveExternalEffectLocked(inFlightUsageResetCompletions) {
                isReadyForChecks() && workerInstanceMatchesLocked(workerInstanceToken)
            }
        } ?: return
        try {
            usageResetCompletionPostObserver?.let { observer ->
                observer(request, succeeded)
            }
            if (!startExternalEffect(permit) {
                    isReadyForChecks() && workerInstanceMatchesLocked(workerInstanceToken)
                }
            ) return
            fun publishBroadcast(intent: Intent): Boolean {
                synchronized(runtimeLock) {
                    if (!isReadyForChecks() || !workerInstanceMatchesLocked(workerInstanceToken)) {
                        return false
                    }
                }
                usageResetBroadcastObserver?.invoke(intent)
                usageResetBeforeFrameworkCallObserver?.invoke(intent)
                if (!beginExternalEffectCall(permit) {
                        isReadyForChecks() && workerInstanceMatchesLocked(workerInstanceToken)
                    }
                ) return false
                try {
                    service.sendBroadcast(intent)
                    return true
                } finally {
                    completeExternalEffectCall(permit)
                }
            }
            val completionIntent = Intent(UsageResetManager.ACTION_USAGE_RESET)
                .setPackage(service.packageName)
                .putStringArrayListExtra(
                    UsageResetManager.EXTRA_PACKAGES,
                    ArrayList(request.packageNames)
                )
                .putExtra(UsageResetManager.EXTRA_RESET_AT_MS, request.resetAtMs)
                .putExtra(UsageResetManager.EXTRA_REQUEST_ID, request.requestId)
                .putExtra(UsageResetManager.EXTRA_RESULT_OK, succeeded)
            if (!publishBroadcast(completionIntent)) return
            if (succeeded) {
                val refreshIntent = Intent(INTENT_ACTION_REFRESH_APP_RULES)
                    .setPackage(service.packageName)
                if (!rearmExternalEffect(permit) {
                        isReadyForChecks() && workerInstanceMatchesLocked(workerInstanceToken)
                    }
                ) return
                publishBroadcast(refreshIntent)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
        } finally {
            finishExternalEffect(permit)
        }
    }

    private fun submitForegroundDecision(
        event: AccessibilityEvent?,
        kind: ObservationKind,
        screenOffPackage: String? = null,
        capturedBoundary: CapturedLifecycleBoundary? = null
    ) {
        try {
            val connectionGeneration = capturedBoundary?.connectionGeneration
                ?: lifecycleGeneration.get().coerceAtLeast(1L)
            val captured = if (kind == ObservationKind.SCREEN_OFF) {
                captureScreenOffObservation(screenOffPackage)
            } else {
                captureForegroundObservation(
                    event = event,
                    kind = kind
                )
            }
            val request = DecisionRequest(
                sourceOrderIdentity = captured.sourceOrderIdentity,
                lifecycleGeneration = LifecycleGeneration(connectionGeneration),
                reason = kind,
                observation = captured.facts
            )
            if (kind == ObservationKind.REAL_EVENT) {
                // The serialized worker remains the decision authority. Keep the host-side
                // scheduler classifier's raw signal history aligned with the same captured
                // facts so a later synthetic recheck can reach the approved R5 candidate after
                // the existing evidence age expires.
                foregroundEvidenceModule.classify(
                    facts = captured.facts,
                    policy = synchronized(runtimeLock) {
                        ForegroundEvidencePolicySnapshot(
                            essentialPackages = essentialPackages +
                                setOf(servicePackageName, Constants.SYSTEM_UI_PACKAGE_NAME)
                        )
                    }
                )
            }
            submitDecisionRequest(
                request = request,
                connectionGeneration = connectionGeneration,
                operation = "foreground decision"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
        }
    }

    private fun submitDecisionRequest(
        request: DecisionRequest,
        connectionGeneration: Long,
        operation: String
    ): SubmissionResult {
        val initialWorker = ensureDecisionWorker(connectionGeneration)
            ?: return SubmissionResult.REJECTED_NOT_READY
        val initialResult = initialWorker.submit(request)
        var retryWorker: SerializedDecisionWorker? = null
        return retryRejectedWorkerSubmission(
            initialResult = initialResult,
            canRetry = { isReadyForChecks(connectionGeneration) },
            retry = {
                retryWorker = ensureDecisionWorker(connectionGeneration)
                retryWorker?.submit(request) ?: SubmissionResult.REJECTED_NOT_READY
            },
            onRepeatedRejection = {
                val failedWorker = retryWorker
                if (failedWorker != null) {
                    synchronized(decisionWorkerLock) {
                        if (decisionWorker === failedWorker) {
                            failedWorker.stop(
                                RecoveryOnlyStop(
                                    requestedAtElapsedMs = observationElapsedRealtimeMs(),
                                    reason = StopReason.REPLACEMENT,
                                    lifecycleGeneration = LifecycleGeneration(connectionGeneration)
                                )
                            )
                            decisionWorker = null
                        }
                    }
                }
                logNonFatal(
                    IllegalStateException(
                        "$operation submission was rejected twice while the app-rule " +
                            "lifecycle remained ready"
                    )
                )
            }
        )
    }

    private fun startNotificationTicker(connectionGeneration: Long) {
        notificationTickJob?.cancel()
        notificationTickJob = scope.launch {
            while (isActive) {
                delay(MILLIS_PER_MINUTE)
                try {
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    // Handler delays use uptime and may be held during doze. A light, once per
                    // minute reconciliation repairs a missed wall-clock boundary after wake;
                    // screen-on/user-present also trigger this path immediately.
                    if (captureRuleRuntime().snapshot.appRules.any { it.isActive }) {
                        postVisibleApplicationCheck(
                            connectionGeneration = connectionGeneration,
                            observationKind = ObservationKind.REFRESH
                        )
                    }
                    updateLiveNotification()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logNonFatal(error)
                }
            }
        }
    }

    fun updateLiveNotification(foregroundPackage: String? = null) {
        if (!isReadyForChecks()) return
        val runtime = captureRuleRuntime()
        val generation = runtime.generation
        val connectionGeneration = lifecycleGeneration.get()
        val notificationForeground = foregroundPackage ?: currentForegroundPackage
        liveNotificationJob?.cancel()
        liveNotificationJob = scope.launch(Dispatchers.IO) {
            val finishNotification = beginDrainWork(inFlightNotifications)
            try {
                if (!isReadyForChecks(connectionGeneration) || recheckGeneration.get() != generation) {
                    return@launch
                }
                val currentSnapshot = runtime.snapshot
                val now = wallClockMsProvider()
                val calculator = ConfigurableUseDayCalculator(resetTime = runtime.resetTime)
                val useDayId = calculator.idAt(now)
                val evaluationEssentialPackages =
                    readEssentialPackagesForEvaluation(connectionGeneration)

                val defaultTitle = service.getString(
                    R.string.blocking_service_notification_title,
                    service::class.simpleName
                )
                val defaultText = service.getString(R.string.blocking_service_notification_text)

                val sessions = sessionRepository.sessionsForUseDay(useDayId)
                val items = LiveRuleNotificationStateCalculator.computeNotificationItems(
                    snapshot = currentSnapshot,
                    sessions = sessions,
                    useDayId = useDayId,
                    nowMs = now,
                    zone = java.time.ZoneId.systemDefault(),
                    useDayCalculator = calculator,
                    useDayGenerationStartedAtMs = runtime.useDayGenerationStartedAtMs,
                    availablePackages = runtime.launchablePackages,
                    essentialExcludedPackages = evaluationEssentialPackages,
                    overrides = runtime.overrideState
                )
                val membershipResolver = AppRuleMembershipResolver(currentSnapshot)
                val model = LiveRuleNotificationStateCalculator.buildNotificationModel(
                    items = items,
                    defaultTitle = defaultTitle,
                    defaultText = defaultText,
                    formatter = { item ->
                        LiveRuleNotificationFormatter.formatNotificationItem(service, item)
                    },
                    foregroundPackage = notificationForeground,
                    rulePackageResolver = { ruleId ->
                        val rule = currentSnapshot.appRules.find { it.id == ruleId }
                        if (rule != null) {
                            membershipResolver.targetPackagesAt(
                                rule = rule,
                                atMs = now,
                                launchablePackages = runtime.launchablePackages,
                                essentialExcludedPackages = evaluationEssentialPackages,
                                foregroundPackageFallback = notificationForeground
                            )
                        } else {
                            emptySet()
                        }
                    },
                    titleFormatter = { ruleName ->
                        LiveRuleNotificationFormatter.formatNotificationTitle(service, ruleName)
                    }
                )

                val publicationCandidate = synchronized(runtimeLock) {
                    if (isReadyForChecks(connectionGeneration) &&
                        recheckGeneration.get() == generation &&
                        model != lastPostedNotificationModel &&
                        model != pendingNotificationPublication?.model
                    ) {
                        val candidate = PendingNotificationPublication(
                            token = notificationPublicationSequence.incrementAndGet(),
                            model = model
                        )
                        pendingNotificationPublication = candidate
                        val permit = reserveExternalEffectLocked(inFlightNotifications) {
                            isReadyForChecks(connectionGeneration) &&
                                recheckGeneration.get() == generation &&
                                pendingNotificationPublication == candidate
                        }
                        if (permit == null) {
                            pendingNotificationPublication = null
                            null
                        } else {
                            candidate to permit
                        }
                    } else {
                        null
                    }
                }
                if (publicationCandidate != null) {
                    val (candidate, publication) = publicationCandidate
                    try {
                        if (!startExternalEffect(publication) {
                                isReadyForChecks(connectionGeneration) &&
                                    recheckGeneration.get() == generation &&
                                    pendingNotificationPublication == candidate
                            }
                        ) return@launch
                        notificationPostObserver?.let { postObserver ->
                            runInterruptible { postObserver(model) }
                        }
                        // The post observer is deliberately before the final publication check;
                        // destroy can invalidate the reserved publication while it is blocked.
                        synchronized(runtimeLock) {
                            if (!isReadyForChecks(connectionGeneration) ||
                                recheckGeneration.get() != generation
                            ) return@launch
                        }
                        notificationUpdateObserver?.let { updateObserver ->
                            runInterruptible { updateObserver(model) }
                        }
                        synchronized(runtimeLock) {
                            if (!isReadyForChecks(connectionGeneration) ||
                                recheckGeneration.get() != generation
                            ) return@launch
                        }
                        notificationBeforeFrameworkCallObserver?.invoke(model)
                        if (!beginExternalEffectCall(publication) {
                                isReadyForChecks(connectionGeneration) &&
                                    recheckGeneration.get() == generation &&
                                    pendingNotificationPublication == candidate
                            }
                        ) return@launch
                        service.updateForegroundNotification(model)
                        synchronized(runtimeLock) {
                            if (isReadyForChecks(connectionGeneration) &&
                                recheckGeneration.get() == generation &&
                                pendingNotificationPublication == candidate
                            ) {
                                lastPostedNotificationModel = model
                                pendingNotificationPublication = null
                            }
                        }
                        notificationPublicationObserver?.invoke(model)
                    } finally {
                        completeExternalEffectCall(publication)
                        finishExternalEffect(publication)
                        synchronized(runtimeLock) {
                            if (pendingNotificationPublication == candidate) {
                                pendingNotificationPublication = null
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            } finally {
                finishNotification()
            }
        }
    }

    private fun showWarning(
        packageName: String,
        evaluation: neth.iecal.curbox.domain.apprules.AppRulesEvaluation,
        evaluatedSnapshot: AppRuleSnapshot,
        generation: Long,
        expectedLifecycleGeneration: LifecycleGeneration,
        workerInstanceToken: AppRuleWorkerInstanceToken? = null
    ) {
        if (!isCurrentLifecycle(expectedLifecycleGeneration) ||
            recheckGeneration.get() != generation
        ) return
        try {
            if (!service.isDelayOver(1_000)) return
            val denialRows = evaluation.denyingRules.map { denial ->
                val rule = evaluatedSnapshot.appRules.find { it.id == denial.ruleId }
                val effectiveAllowanceMillis = denial.effectiveAllowanceMillis
                AppRuleGuardianDenial(
                    ruleId = denial.ruleId,
                    ruleName = rule?.name ?: denial.ruleId,
                    reason = warningStatus(denial),
                    conditionProgresses = denial.conditionProgresses,
                    isAllowanceExhausted = denial.isAllowanceExhausted,
                    usedMinutes = (denial.usedMillis / MILLIS_PER_MINUTE).coerceAtLeast(0L),
                    totalAllowedMinutes = (effectiveAllowanceMillis / MILLIS_PER_MINUTE).coerceAtLeast(0L),
                    earnedAllowanceEnabled = denial.earnedAllowanceEnabled
                )
            }
            val permit = synchronized(runtimeLock) {
                if (activeGuardianPackage == packageName) {
                    null
                } else {
                    reserveExternalEffectLocked(inFlightCallbacks) {
                        isReadyForChecks() &&
                            expectedLifecycleGeneration == currentLifecycleGeneration() &&
                            recheckGeneration.get() == generation &&
                            (workerInstanceToken == null ||
                                workerInstanceMatchesLocked(workerInstanceToken)) &&
                            activeGuardianPackage == null
                    }?.also {
                        activeGuardianPackage = packageName
                    }
                }
            } ?: return
            if (!startExternalEffect(permit) {
                    isReadyForChecks() &&
                        expectedLifecycleGeneration == currentLifecycleGeneration() &&
                        recheckGeneration.get() == generation &&
                        (workerInstanceToken == null ||
                            workerInstanceMatchesLocked(workerInstanceToken))
                }
            ) {
                synchronized(runtimeLock) {
                    if (activeGuardianPackage == packageName &&
                        expectedLifecycleGeneration == currentLifecycleGeneration() &&
                        recheckGeneration.get() == generation
                    ) {
                        activeGuardianPackage = null
                    }
                }
                return
            }
            try {
                warningBeforeFrameworkCallObserver?.invoke(packageName)
                if (!beginExternalEffectCall(permit) {
                        isReadyForChecks() &&
                            expectedLifecycleGeneration == currentLifecycleGeneration() &&
                            recheckGeneration.get() == generation &&
                            (workerInstanceToken == null ||
                                workerInstanceMatchesLocked(workerInstanceToken))
                    }
                ) {
                    synchronized(runtimeLock) {
                        if (activeGuardianPackage == packageName) {
                            activeGuardianPackage = null
                        }
                    }
                    return
                }
                service.startActivity(
                    createGuardianApprovalIntent(service, packageName, denialRows)
                )
            } finally {
                completeExternalEffectCall(permit)
                finishExternalEffect(permit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private fun isCurrentLifecycle(expected: LifecycleGeneration): Boolean =
        isReadyForChecks() &&
            expected == LifecycleGeneration(lifecycleGeneration.get().coerceAtLeast(1L))

    private fun currentLifecycleGeneration(): LifecycleGeneration =
        LifecycleGeneration(lifecycleGeneration.get().coerceAtLeast(1L))

    private fun workerInstanceMatchesLocked(token: AppRuleWorkerInstanceToken): Boolean {
        return currentWorkerInstanceToken?.value == token.value
    }

    private fun warningStatus(evaluation: AppRuleEvaluation): String = if (evaluation.conditionEnabled) {
        service.getString(
            R.string.app_rules_warning_status,
            evaluation.contributorUsageMillis / MILLIS_PER_MINUTE,
            evaluation.conditionRequiredMillis / MILLIS_PER_MINUTE,
            evaluation.earnedAllowanceMillis / MILLIS_PER_MINUTE,
            evaluation.directAllowanceMillis / MILLIS_PER_MINUTE,
            (evaluation.remainingMillis / MILLIS_PER_MINUTE).coerceAtLeast(0L)
        )
    } else {
        service.getString(
            R.string.app_rules_warning_status_no_condition,
            evaluation.earnedAllowanceMillis / MILLIS_PER_MINUTE,
            evaluation.directAllowanceMillis / MILLIS_PER_MINUTE,
            (evaluation.remainingMillis / MILLIS_PER_MINUTE).coerceAtLeast(0L)
        )
    }

    fun onDestroy() {
        destroyInternal(totalDrainBudgetMs = null)
    }

    /** Candidate-only measurement seam; production teardown continues to use recovery-only stop. */
    internal fun onDestroyForMeasurement(totalDrainBudgetMs: Long): DestroyDrainMeasurement {
        require(totalDrainBudgetMs >= 0L) { "drain budget must not be negative" }
        return requireNotNull(destroyInternal(totalDrainBudgetMs))
    }

    private fun destroyInternal(totalDrainBudgetMs: Long?): DestroyDrainMeasurement? {
        val requestedAtElapsedMs = observationElapsedRealtimeMs()
        val deadline = totalDrainBudgetMs?.let {
            TotalDrainDeadline(safeElapsedRealtimeAdd(requestedAtElapsedMs, it))
        }
        val nextLifecycleGeneration = lifecycleGeneration.incrementAndGet()
        val cancelledExternalEffects = synchronized(runtimeLock) {
            setupReady = false
            destroyed = true
            currentWorkerInstanceToken = null
            recheckGeneration.incrementAndGet()
            currentForegroundPackage = null
            lastNonessentialForegroundPackage = null
            activeGuardianPackage = null
            currentForegroundEvidenceAtElapsedMs = 0L
            suspendedForegroundPackage = null
            foregroundEvidenceSuspended = true
            screenOnAwaitingUserPresent = false
            pendingSchedulerWakeGeneration = null
            pendingWorkerEvaluations.clear()
            pendingNotificationPublication = null
            cancelPendingExternalEffectsLocked()
        }
        cancelledExternalEffects.forEach(::finishDrainWork)
        val workAtInvalidation = drainWorkSnapshot()
        if (deadline == null) {
            settingsJob?.cancel()
            notificationTickJob?.cancel()
            liveNotificationJob?.cancel()
            cancelScheduledRechecks()
            try {
                visibleApplicationCheckRemoveCallbacks?.invoke()
            } catch (error: Throwable) {
                logNonFatal(error)
            }
            handlerInstance?.removeCallbacksAndMessages(null)
            synchronized(decisionWorkerLock) {
                val worker = decisionWorker
                decisionWorker = null
                worker?.stop(
                    RecoveryOnlyStop(
                        requestedAtElapsedMs = requestedAtElapsedMs,
                        reason = StopReason.DESTROY,
                        lifecycleGeneration = LifecycleGeneration(nextLifecycleGeneration)
                    )
                )
            }
            scope.cancel()
            receiverLifecycle?.unregister()?.forEach(::logNonFatal)
            receiverLifecycle = null
            if (isDefaultWakeScheduler) {
                wakeScheduler = null
                isDefaultWakeScheduler = false
            } else {
                wakeScheduler?.onWake = null
            }
            return null
        }

        // Candidate measurement uses one absolute endpoint. Each stage records whether it
        // completed within that endpoint; no stage receives a restarted timeout.
        val schedulerCleanupCompleted = runCandidateCleanupStage(deadline) {
            settingsJob?.cancel()
            notificationTickJob?.cancel()
            liveNotificationJob?.cancel()
            cancelScheduledRechecks()
        }
        val handlerCleanupCompleted = runCandidateCleanupStage(deadline) {
            try {
                visibleApplicationCheckRemoveCallbacks?.invoke()
            } catch (error: Throwable) {
                logNonFatal(error)
            }
            handlerInstance?.removeCallbacksAndMessages(null)
        }
        val workerStartedAtElapsedMs = observationElapsedRealtimeMs()
        val workerResult = synchronized(decisionWorkerLock) {
            val worker = decisionWorker
            decisionWorker = null
            worker?.stop(
                DeadlineDrainStop(
                    requestedAtElapsedMs = requestedAtElapsedMs,
                    deadline = deadline,
                    reason = StopReason.DESTROY,
                    lifecycleGeneration = LifecycleGeneration(nextLifecycleGeneration)
                )
            )
        }
        val workerFinishedAtElapsedMs = observationElapsedRealtimeMs()
        val workerCleanupCompleted = workerStartedAtElapsedMs <= deadline.elapsedRealtimeMs &&
            workerFinishedAtElapsedMs <= deadline.elapsedRealtimeMs &&
            workerResult?.completed != false
        val scopeStartedAtElapsedMs = observationElapsedRealtimeMs()
        // Scope cancellation is nonblocking and is still required after a worker timeout so
        // evaluator and notification observers receive coroutine cancellation.
        scope.cancel()
        val scopeCleanupCompleted = scopeStartedAtElapsedMs <= deadline.elapsedRealtimeMs &&
            observationElapsedRealtimeMs() <= deadline.elapsedRealtimeMs
        val receiverCleanupCompleted = runCandidateCleanupStage(deadline) {
            receiverLifecycle?.unregister()?.forEach(::logNonFatal)
            receiverLifecycle = null
            if (isDefaultWakeScheduler) {
                wakeScheduler = null
                isDefaultWakeScheduler = false
            } else {
                wakeScheduler?.onWake = null
            }
        }
        val effectDrainCompleted = awaitDrainWorkUntil(deadline.elapsedRealtimeMs)
        val completedAtElapsedMs = observationElapsedRealtimeMs()
        return DestroyDrainMeasurement(
            requestedAtElapsedMs = requestedAtElapsedMs,
            deadlineElapsedMs = deadline.elapsedRealtimeMs,
            workAtInvalidation = workAtInvalidation,
            workAtCompletion = drainWorkSnapshot(),
            workerResult = workerResult,
            schedulerCleanupCompleted = schedulerCleanupCompleted,
            handlerCleanupCompleted = handlerCleanupCompleted,
            workerCleanupCompleted = workerCleanupCompleted,
            scopeCleanupCompleted = scopeCleanupCompleted,
            receiverCleanupCompleted = receiverCleanupCompleted,
            effectDrainCompleted = effectDrainCompleted,
            completedAtElapsedMs = completedAtElapsedMs
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_REFRESH_APP_RULES) return
            if (!isReadyForChecks()) return
            val connectionGeneration = lifecycleGeneration.get()
            // The broadcast is the source observation. Keep its revision with the coroutine even
            // if the coroutine later waits for the shared publication path.
            val reservation = sourceOrderSequencer.reserveRuntimePublication()
            // Settings flow is authoritative. This action exists for the same UI to service
            // refresh path as the legacy blocker and simply triggers a harmless re-read.
            scope.launch {
                val finishRefresh = beginDrainWork(inFlightRefreshes)
                try {
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    refreshMutex.withLock {
                        if (!isReadyForChecks(connectionGeneration)) return@withLock false
                        val packageScopeChanged = refreshPackageScope(
                            runtimeRevision = reservation.runtimeRevision,
                            connectionGeneration = connectionGeneration
                        )
                        val settings = service.dataStoreManager.settings.first()
                        if (!isReadyForChecks(connectionGeneration)) return@withLock false
                        val accepted = applyAndSubmitRuntimePublication(
                            connectionGeneration = connectionGeneration,
                            settings = settings,
                            sourceOrderIdentity = reservation.sourceOrderIdentity,
                            runtimeRevision = reservation.runtimeRevision
                        )
                        packageScopeChanged || accepted
                    }
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    // A refresh is also useful when the package reader returned the same set:
                    // the window/root provider may have recovered since the last event.
                    postVisibleApplicationCheck(
                        connectionGeneration = connectionGeneration,
                        observationKind = ObservationKind.REFRESH
                    )
                    updateLiveNotification()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logNonFatal(error)
                } finally {
                    finishRefresh()
                }
            }
        }
    }

    private fun checkCurrentlyVisibleApplications() {
        checkCurrentlyVisibleApplications(ObservationKind.RECONNECT)
    }

    private fun checkCurrentlyVisibleApplications(observationKind: ObservationKind) {
        checkCurrentlyVisibleApplications(observationKind, observationAttempt = 0)
    }

    private fun checkCurrentlyVisibleApplications(
        observationKind: ObservationKind,
        observationAttempt: Int = 0
    ) {
        val observationBoundary = captureLifecycleBoundary() ?: return
        val connectionGeneration = observationBoundary.connectionGeneration
        val observationGeneration = observationBoundary.recheckGeneration
        try {
            val configuredEssentialPackages =
                readEssentialPackagesForEvaluation(connectionGeneration)
            val policy = ForegroundEvidencePolicySnapshot(
                essentialPackages = configuredEssentialPackages +
                    setOf(service.packageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            )
            val facts = captureForegroundFacts(
                event = null,
                kind = observationKind
            )
            val result = foregroundEvidenceModule.classify(facts, policy)
            val moduleVisiblePackages = result.outcomes
                .filterIsInstance<ForegroundEvidenceOutcome.Visible>()
                .mapNotNull { it.packageName }
                .distinct()
            val suspendedPackage = synchronized(runtimeLock) {
                suspendedForegroundPackage.takeIf { foregroundEvidenceSuspended }
            }
            // A suspended package is only resumed when the module reports that exact package.
            // If it disappeared while the display was off, a new package may be adopted only
            // from the module's own direct visible outcomes, never from caller state.
            val resumedPackage = moduleVisiblePackages.firstOrNull { it == suspendedPackage }
                ?: moduleVisiblePackages.firstOrNull()
            if (foregroundEvidenceSuspended && resumedPackage != null) {
                recordForegroundEvidenceIfCurrent(
                    packageName = resumedPackage,
                    evidenceAtElapsedMs = facts.capturedAtElapsedMs,
                    expectedConnectionGeneration = connectionGeneration,
                    expectedRecheckGeneration = observationGeneration,
                    requireSuspended = true
                )
            } else if (!foregroundEvidenceSuspended) {
                moduleVisiblePackages.firstOrNull { it == facts.activeRoot.packageName }
                    ?.let { packageName ->
                        recordForegroundEvidenceIfCurrent(
                            packageName = packageName,
                            evidenceAtElapsedMs = facts.capturedAtElapsedMs,
                            expectedConnectionGeneration = connectionGeneration,
                            expectedRecheckGeneration = observationGeneration,
                            requireSuspended = false
                        )
                    }
                    ?: moduleVisiblePackages.firstOrNull()?.let { packageName ->
                        recordForegroundEvidenceIfCurrent(
                            packageName = packageName,
                            evidenceAtElapsedMs = facts.capturedAtElapsedMs,
                            expectedConnectionGeneration = connectionGeneration,
                            expectedRecheckGeneration = observationGeneration,
                            requireSuspended = false
                        )
                    }
            }
            val generation = observationGeneration
            val needsObservationRetry = result.outcomes.any { outcome ->
                outcome is ForegroundEvidenceOutcome.Unknown &&
                    outcome.candidatePackage == null &&
                    outcome.followUp == FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
            }
            result.outcomes.forEach { outcome ->
                if (!isReadyForChecks() || recheckGeneration.get() != generation) return
                when (outcome) {
                    is ForegroundEvidenceOutcome.NotVisible ->
                        cancelScheduledRecheck(packageName = outcome.packageName)
                    is ForegroundEvidenceOutcome.Visible -> {
                        if (outcome.decisionPermission ==
                                neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE
                        ) {
                            dispatchSyntheticCheck(outcome.packageName, observationBoundary)
                        }
                    }
                    is ForegroundEvidenceOutcome.Unknown -> Unit
                }
            }
            if (needsObservationRetry && observationAttempt < MAX_VISIBILITY_RETRIES) {
                postVisibleApplicationCheck(
                    delayMillis = VISIBILITY_RETRY_DELAY_MS * (observationAttempt + 1),
                    connectionGeneration = connectionGeneration,
                    observationKind = ObservationKind.SYNTHETIC_RECHECK
                )
            }
        } catch (error: CancellationException) {
            return
        } catch (error: Throwable) {
            logNonFatal(error)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun onWakeFromScheduler(packageName: String, token: Long) {
        if (!isReadyForChecks()) return
        onSchedulerWake(recheckGeneration.get())
    }

    private val schedulerWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isReadyForChecks()) return
            val packageName = intent?.getStringExtra(EXTRA_SCHEDULER_PACKAGE)
                ?.takeIf(String::isNotBlank)
                ?: return
            val token = intent.getLongExtra(EXTRA_SCHEDULER_TOKEN, -1L)
            onWakeFromScheduler(packageName, token)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isReadyForChecks()) return
            val connectionGeneration = lifecycleGeneration.get()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> {
                    // SCREEN_ON can precede USER_PRESENT. Latch the observation until the real
                    // keyguard state says the device is unlocked, and let USER_PRESENT release
                    // an unknown or locked state.
                    val keyguardLocked = isDeviceKeyguardLocked()
                    synchronized(runtimeLock) {
                        if (!isReadyForChecks() || lifecycleGeneration.get() != connectionGeneration) {
                            return
                        }
                        screenOnAwaitingUserPresent = keyguardLocked != false
                    }
                    postVisibleApplicationCheck(
                        delayMillis = 300L,
                        connectionGeneration = connectionGeneration,
                        observationKind = ObservationKind.SCREEN_WAKE
                    )
                }
                Intent.ACTION_USER_PRESENT -> {
                    synchronized(runtimeLock) {
                        if (!isReadyForChecks() || lifecycleGeneration.get() != connectionGeneration) {
                            return
                        }
                        screenOnAwaitingUserPresent = false
                    }
                    postVisibleApplicationCheck(
                        delayMillis = 300L,
                        connectionGeneration = connectionGeneration,
                        observationKind = ObservationKind.USER_PRESENT
                    )
                }
            }
        }
    }

    private val guardianReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != GuardianApprovalActivity.INTENT_ACTION_CLOSED &&
                action != GuardianApprovalActivity.INTENT_ACTION_OPENED
            ) return
            val packageName = intent.getStringExtra(
                GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE
            )?.trim().orEmpty()
            if (packageName.isBlank()) return
            synchronized(runtimeLock) {
                if (!isReadyForChecks()) return
                applyGuardianLifecycleTransition(action, packageName)
            }
        }
    }

    private fun applyGuardianLifecycleTransition(action: String, packageName: String) {
        if (action == GuardianApprovalActivity.INTENT_ACTION_OPENED) {
            activeGuardianPackage = packageName
        } else if (activeGuardianPackage == packageName) {
            activeGuardianPackage = null
            lastShownAt = 0L
        }
    }

    @VisibleForTesting
    internal fun closeGuardianForInstrumentation(packageName: String): Boolean =
        synchronized(runtimeLock) {
            applyGuardianLifecycleTransition(GuardianApprovalActivity.INTENT_ACTION_CLOSED, packageName)
            activeGuardianPackage == null && lastShownAt == 0L
        }

    private fun handleScreenOff() {
        val screenOffPackage = synchronized(runtimeLock) {
            val packageName = currentForegroundPackage
                ?: lastNonessentialForegroundPackage
                ?: suspendedForegroundPackage
            screenOnAwaitingUserPresent = false
            // Invalidate both keyed boundary callbacks and any reconciliation callback already
            // posted for the visible display. The screen-off broadcast is the terminal signal for
            // this foreground observation; no provider retry may run until wake.
            recheckGeneration.incrementAndGet()
            suspendedForegroundPackage = packageName
            currentForegroundPackage = null
            currentForegroundEvidenceAtElapsedMs = 0L
            foregroundEvidenceSuspended = true
            packageName
        }
        cancelScheduledRechecks()
        submitForegroundDecision(
            event = null,
            kind = ObservationKind.SCREEN_OFF,
            screenOffPackage = screenOffPackage
        )
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isReadyForChecks()) return
            val connectionGeneration = lifecycleGeneration.get()
            // A new launchable app is part of an all-apps scope without requiring a rule edit.
            scope.launch {
                val finishRefresh = beginDrainWork(inFlightRefreshes)
                try {
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    refreshMutex.withLock {
                        if (isReadyForChecks(connectionGeneration)) {
                            refreshPackageScope(connectionGeneration = connectionGeneration)
                        }
                    }
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    // Package broadcasts must not erase unrelated boundary jobs. Visible apps are
                    // reconciled and will replace only their own keyed job when needed.
                    postVisibleApplicationCheck(
                        connectionGeneration = connectionGeneration,
                        observationKind = ObservationKind.REFRESH
                    )
                    updateLiveNotification()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logNonFatal(error)
                } finally {
                    finishRefresh()
                }
            }
        }
    }

    /** Keep the existing private no-argument test seam for the unversioned setup refresh. */
    private fun refreshPackageScope(): Boolean = refreshPackageScope(
        runtimeRevision = null,
        connectionGeneration = null
    )

    private fun refreshPackageScope(
        runtimeRevision: RuntimeRevision? = null,
        connectionGeneration: Long? = null
    ): Boolean {
        if (!::service.isInitialized) return false
        if (connectionGeneration != null && !isReadyForChecks(connectionGeneration)) return false
        val reader = packageScopeReader ?: return false
        val previousLaunchable = launchablePackages
        val previousEssential = essentialPackages
        try {
            // Read both sets before publishing either one so a transient PackageManager failure
            // cannot expose a half-updated all-apps scope.
            val nextLaunchable = reader.readLaunchablePackages()
            val nextEssential = reader.readEssentialPackages()
            synchronized(runtimeLock) {
                if (connectionGeneration != null &&
                    lifecycleGeneration.get() != connectionGeneration
                ) return false
                if (runtimeRevision != null &&
                    runtimeRevision.value <= latestRuntimeRevision.value
                ) return false
                launchablePackages = nextLaunchable
                essentialPackages = nextEssential
            }
            return previousLaunchable != nextLaunchable || previousEssential != nextEssential
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Keep the last successful launcher listing. Only the always-safe exclusions need a
            // conservative fallback when the package provider is unavailable.
            val fallbackEssential = setOf(service.packageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            synchronized(runtimeLock) {
                if (connectionGeneration != null &&
                    lifecycleGeneration.get() != connectionGeneration
                ) return false
                if (runtimeRevision != null &&
                    runtimeRevision.value <= latestRuntimeRevision.value
                ) return false
                essentialPackages = fallbackEssential
            }
            logNonFatal(error)
            return previousEssential != fallbackEssential
        }
    }

    private fun readEssentialPackagesForEvaluation(
        expectedConnectionGeneration: Long? = null
    ): Set<String> {
        val reader = packageScopeReader ?: return essentialPackages
        return try {
            val packages = reader.readEssentialPackages()
            synchronized(runtimeLock) {
                if (expectedConnectionGeneration != null &&
                    lifecycleGeneration.get() != expectedConnectionGeneration
                ) return essentialPackages
                essentialPackages = packages
            }
            packages
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            essentialPackages
        }
    }

    /**
     * Publishes the settings inputs as one generation. DataStore also emits for unrelated
     * settings, so only a real rule, override, or use-day clock change invalidates boundary jobs.
     */
    /** Keep the existing private one-argument test seam for direct snapshot checks. */
    private fun applySettingsSnapshot(settings: Settings): Boolean =
        applySettingsSnapshot(
            settings = settings,
            runtimeRevision = null,
            connectionGeneration = null
        )

    private fun applySettingsSnapshot(
        settings: Settings,
        runtimeRevision: RuntimeRevision?,
        connectionGeneration: Long? = null
    ): Boolean {
        if (connectionGeneration != null && !isReadyForChecks(connectionGeneration)) return false
        val candidate = try {
            settings.appRuleSnapshot.normalized().takeIf { it.isValid }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            null
        }
        val nextReset = safeResetTime(settings.useDayResetHour, settings.useDayResetMinute)
        val nextUsageTrackingDecision = AppUsageTrackingPolicy.decide(
            statisticsTrackingEnabled = settings.isAppUsageTrackingEnabled,
            hasActiveTimeBasedRules = settings.appRuleSnapshot.appRules.any { it.isActive }
        )
        val changed = synchronized(runtimeLock) {
            if (destroyed) return@synchronized false
            if (connectionGeneration != null &&
                lifecycleGeneration.get() != connectionGeneration
            ) return@synchronized false
            if (runtimeRevision != null &&
                runtimeRevision.value <= latestRuntimeRevision.value
            ) return@synchronized false
            runtimeRevision?.let { latestRuntimeRevision = it }
            val previousSnapshot = snapshot.snapshot()
            val nextSnapshot = candidate ?: previousSnapshot
            val changed = nextSnapshot != previousSnapshot ||
                nextReset != resetTime ||
                settings.useDayGenerationStartedAtMs != useDayGenerationStartedAtMs ||
                settings.appRuleOverrideState != overrideState ||
                nextUsageTrackingDecision != usageTrackingDecision
            if (!changed) return@synchronized false

            if (settings.appRuleOverrideState != overrideState) {
                reevaluationGate.markOverrideChanged()
            }
            resetTime = nextReset
            useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
            overrideState = settings.appRuleOverrideState
            usageTrackingDecision = nextUsageTrackingDecision
            if (candidate != null) snapshot.accept(candidate)
            recheckGeneration.incrementAndGet()
            true
        }
        if (changed) cancelScheduledRechecks()
        return changed
    }

    private fun isAcceptedRuntimeRevision(runtimeRevision: RuntimeRevision): Boolean =
        synchronized(runtimeLock) {
            latestRuntimeRevision == runtimeRevision
        }

    private fun captureRuleRuntime(): RuleRuntime = synchronized(runtimeLock) {
        RuleRuntime(
            snapshot = snapshot.snapshot(),
            resetTime = resetTime,
            useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
            overrideState = overrideState,
            launchablePackages = launchablePackages,
            generation = recheckGeneration.get()
        )
    }

    private fun captureForegroundFacts(
        event: AccessibilityEvent?,
        kind: ObservationKind
    ): ForegroundFacts = captureForegroundObservation(event, kind).facts

    private fun captureForegroundObservation(
        event: AccessibilityEvent?,
        kind: ObservationKind
    ): CapturedForegroundObservation {
        val trigger = ObservationTrigger(
            sourceOrderIdentity = sourceOrderSequencer.nextSourceOrderIdentity(),
            kind = kind,
            eventPackage = event?.packageName
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            requestedAtWallMs = observationWallClockMs(),
            requestedAtElapsedMs = observationElapsedRealtimeMs()
        )
        val source = foregroundObservationSource
        val facts = if (source != null) {
            if (event != null) {
                source.captureEvent(event, trigger)
            } else {
                source.capture(trigger)
            }
        } else {
            captureLegacyForegroundFacts(event, trigger)
        }
        val normalizedFacts = if (screenOnAwaitingUserPresent &&
            facts.displayState == DisplayState.UNLOCKED
        ) {
            facts.copy(displayState = DisplayState.KEYGUARD)
        } else {
            facts
        }
        return CapturedForegroundObservation(trigger.sourceOrderIdentity, normalizedFacts)
    }

    /**
     * SCREEN_OFF is a terminal framework signal. Its evidence must not be downgraded by a
     * concurrent display/keyguard read returning UNKNOWN or UNLOCKED.
     */
    private fun captureScreenOffObservation(
        packageName: String?
    ): CapturedForegroundObservation {
        val trigger = ObservationTrigger(
            sourceOrderIdentity = sourceOrderSequencer.nextSourceOrderIdentity(),
            kind = ObservationKind.SCREEN_OFF,
            eventPackage = packageName?.trim()?.takeIf(String::isNotEmpty),
            requestedAtWallMs = observationWallClockMs(),
            requestedAtElapsedMs = observationElapsedRealtimeMs()
        )
        val normalizedPackage = trigger.eventPackage
        return CapturedForegroundObservation(
            sourceOrderIdentity = trigger.sourceOrderIdentity,
            facts = ForegroundFacts(
                capturedAtWallMs = trigger.requestedAtWallMs,
                capturedAtElapsedMs = trigger.requestedAtElapsedMs,
                signal = SignalFact(
                    kind = ObservationKind.SCREEN_OFF,
                    eventPackage = normalizedPackage
                ),
                activeRoot = ActiveRootFact(
                    packageName = normalizedPackage,
                    readState = if (normalizedPackage == null) {
                        ForegroundReadState.EMPTY
                    } else {
                        ForegroundReadState.AVAILABLE
                    }
                ),
                applicationWindows = ApplicationWindowsFact(),
                displayState = DisplayState.SCREEN_OFF
            )
        )
    }

    private fun captureLegacyForegroundFacts(
        event: AccessibilityEvent?,
        trigger: ObservationTrigger
    ): ForegroundFacts {
        val eventPackage = event?.packageName
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val eventElapsedMs = event?.eventTime?.takeIf { it > 0L }
        val activeWindow = readActiveWindowSnapshot()
        val applicationWindows = readApplicationWindowSnapshot()
        return ForegroundFacts(
            capturedAtWallMs = trigger.requestedAtWallMs,
            capturedAtElapsedMs = trigger.requestedAtElapsedMs,
            signal = SignalFact(
                kind = trigger.kind,
                eventPackage = eventPackage,
                eventWallMs = eventPackage?.let { trigger.requestedAtWallMs },
                eventElapsedMs = eventPackage?.let {
                    eventElapsedMs ?: trigger.requestedAtElapsedMs
                }
            ),
            activeRoot = ActiveRootFact(
                packageName = activeWindow.packageName,
                readState = when {
                    activeWindow.readFailed -> ForegroundReadState.FAILED
                    activeWindow.packageName == null -> ForegroundReadState.EMPTY
                    else -> ForegroundReadState.AVAILABLE
                }
            ),
            applicationWindows = applicationWindows.toForegroundFact(),
            displayState = legacyDisplayState()
        )
    }

    private fun ApplicationWindowSnapshot.toForegroundFact(): ApplicationWindowsFact =
        ApplicationWindowsFact(
            packages = packages,
            unknownSlotCount = unknownSlotCount,
            readState = when {
                providerFailed -> ForegroundReadState.FAILED
                !hasApplicationWindow -> ForegroundReadState.EMPTY
                else -> ForegroundReadState.AVAILABLE
            },
            freshness = freshness,
            stalePackages = stalePackages
        )

    private fun legacyDisplayState(): DisplayState {
        val interactive = isScreenInteractive() ?: return DisplayState.UNKNOWN
        if (!interactive) return DisplayState.SCREEN_OFF
        if (screenOnAwaitingUserPresent) return DisplayState.KEYGUARD
        return when (isDeviceKeyguardLocked()) {
            null -> DisplayState.UNKNOWN
            true -> DisplayState.KEYGUARD
            false -> DisplayState.UNLOCKED
        }
    }

    private fun observationWallClockMs(): Long = try {
        wallClockMsProvider().coerceAtLeast(0L)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logNonFatal(error)
        System.currentTimeMillis().coerceAtLeast(0L)
    }

    private fun observationElapsedRealtimeMs(): Long = try {
        elapsedRealtimeMsProvider().coerceAtLeast(0L)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logNonFatal(error)
        SystemClock.elapsedRealtime().coerceAtLeast(0L)
    }

    /** Compatibility overload for the existing private scheduler test seam. */
    private fun postVisibleApplicationCheck(
        delayMillis: Long,
        connectionGeneration: Long
    ) {
        postVisibleApplicationCheck(
            delayMillis = delayMillis,
            connectionGeneration = connectionGeneration,
            observationKind = ObservationKind.RECONNECT
        )
    }

    /** Posts a guarded main-thread reconciliation used by refresh, reconnect, and wake paths. */
    private fun postVisibleApplicationCheck(
        delayMillis: Long = 0L,
        connectionGeneration: Long = lifecycleGeneration.get(),
        observationKind: ObservationKind = ObservationKind.RECONNECT
    ) {
        if (!isReadyForChecks(connectionGeneration)) return
        val observationGeneration = recheckGeneration.get()
        postVisibleApplicationAttempt(
            delayMillis = delayMillis,
            connectionGeneration = connectionGeneration,
            observationGeneration = observationGeneration,
            observationKind = observationKind,
            postAttempt = 1
        )
    }

    /** Retries failed Handler posts without recursive calls or stale-generation callbacks. */
    private fun postVisibleApplicationAttempt(
        delayMillis: Long,
        connectionGeneration: Long,
        observationGeneration: Long,
        observationKind: ObservationKind,
        postAttempt: Int
    ) {
        var attempt = postAttempt
        while (attempt <= MAX_SCHEDULER_POST_ATTEMPTS &&
            isReadyForChecks(connectionGeneration) &&
            recheckGeneration.get() == observationGeneration
        ) {
            val attemptNumber = attempt
            lateinit var postPermit: ExternalEffectPermit
            val callback = Runnable {
                if (!enterPostedEffect(postPermit)) return@Runnable
                try {
                    if (!isReadyForChecks(connectionGeneration) ||
                        recheckGeneration.get() != observationGeneration
                    ) return@Runnable
                    runVisibleApplicationCheck(
                        connectionGeneration = connectionGeneration,
                        observationGeneration = observationGeneration,
                        observationKind = observationKind,
                        postAttempt = attemptNumber
                    )
                } finally {
                    finishExternalEffect(postPermit)
                }
            }
            val retryDelay = if (attempt == 1) {
                delayMillis.coerceAtLeast(0L)
            } else {
                VISIBILITY_RETRY_DELAY_MS * (attempt - 1)
            }
            val permit = synchronized(runtimeLock) {
                reserveExternalEffectLocked(inFlightCallbacks) {
                    isReadyForChecks(connectionGeneration) &&
                        recheckGeneration.get() == observationGeneration
                }
            } ?: break
            postPermit = permit
            if (!startExternalEffect(permit) {
                    isReadyForChecks(connectionGeneration) &&
                        recheckGeneration.get() == observationGeneration
                }
            ) {
                attempt++
                continue
            }
            try {
                handlerBeforeFrameworkPostObserver?.invoke()
            } catch (error: CancellationException) {
                finishExternalEffect(permit)
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
                finishExternalEffect(permit)
                attempt++
                continue
            }
            if (!beginExternalEffectCall(permit) {
                    isReadyForChecks(connectionGeneration) &&
                        recheckGeneration.get() == observationGeneration
                }
            ) {
                attempt++
                continue
            }
            val posted = try {
                visibleApplicationCheckPostDelayed?.invoke(callback, retryDelay)
                    ?: getHandler().postDelayed(callback, retryDelay)
            } catch (error: CancellationException) {
                finishExternalEffect(permit)
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
                false
            }
            val armed = armPostedEffect(permit, posted) {
                isReadyForChecks(connectionGeneration) &&
                    recheckGeneration.get() == observationGeneration
            }
            if (armed) return
            attempt++
        }
        synchronized(runtimeLock) {
            if (pendingSchedulerWakeGeneration == observationGeneration) {
                pendingSchedulerWakeGeneration = null
            }
        }
    }

    private fun runVisibleApplicationCheck(
        connectionGeneration: Long,
        observationGeneration: Long,
        observationKind: ObservationKind,
        postAttempt: Int
    ) {
        if (!isReadyForChecks(connectionGeneration) ||
            recheckGeneration.get() != observationGeneration
        ) return
        synchronized(runtimeLock) {
            if (pendingSchedulerWakeGeneration == observationGeneration) {
                pendingSchedulerWakeGeneration = null
            }
        }
        try {
            checkCurrentlyVisibleApplications(observationKind)
        } catch (_: CancellationException) {
            return
        } catch (error: Throwable) {
            // Handler callbacks have no coroutine parent to contain a feature failure. Keep the
            // recovery bounded and tied to the generation that posted this callback.
            logNonFatal(error)
            postVisibleApplicationAttempt(
                delayMillis = VISIBILITY_RETRY_DELAY_MS * postAttempt,
                connectionGeneration = connectionGeneration,
                observationGeneration = observationGeneration,
                observationKind = observationKind,
                postAttempt = postAttempt + 1
            )
        }
    }

    private fun isScreenInteractive(): Boolean? = try {
        screenInteractiveProvider?.invoke() ?: run {
            val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@run null
            powerManager.isInteractive
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logNonFatal(error)
        null
    }

    private fun isDeviceKeyguardLocked(): Boolean? = try {
        keyguardLockedProvider?.invoke() ?: run {
            val keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                ?: return@run null
            keyguardManager.isKeyguardLocked
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logNonFatal(error)
        null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scheduleRecheck(
        packageName: String,
        remainingMillis: Long,
        maxDelayMillis: Long = 20_000L,
        generation: Long = recheckGeneration.get()
    ) {
        if (!isReadyForChecks() || packageName.isBlank()) return
        scheduleRecheckAtWallClock(
            packageName = packageName,
            dueAtWallClockMs = safeWallClockAdd(
                observationWallClockMs(),
                remainingMillis
            )
        )
    }

    private fun scheduleRecheckAtWallClock(
        packageName: String,
        dueAtWallClockMs: Long
    ) {
        if (!isReadyForChecks() || packageName.isBlank()) return
        val token = schedulerTokenSequence.incrementAndGet()
        wakeScheduler?.schedule(packageName, dueAtWallClockMs, token)
    }

    @Suppress("DEPRECATION")
    private fun dispatchSyntheticCheck(
        packageName: String,
        generation: Long
    ) {
        val capturedBoundary = captureLifecycleBoundary(generation) ?: return
        dispatchSyntheticCheck(packageName, capturedBoundary)
    }

    private fun dispatchSyntheticCheck(
        packageName: String,
        capturedBoundary: CapturedLifecycleBoundary
    ) {
        if (!isCurrentLifecycleBoundary(capturedBoundary)) return
        val event = try {
            obtainWindowStateEvent()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            return
        }
        try {
            if (!isCurrentLifecycleBoundary(capturedBoundary)) return
            event.packageName = packageName
            submitForegroundDecision(
                event = event,
                kind = ObservationKind.SYNTHETIC_RECHECK,
                capturedBoundary = capturedBoundary
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
        } finally {
            try {
                event.recycle()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
            }
        }
    }

    private fun observeForegroundOutcome(
        packageName: String,
        expectedRecheckGeneration: Long
    ): ObservedForegroundOutcome? {
        val capturedBoundary = captureLifecycleBoundary(expectedRecheckGeneration) ?: return null
        val configuredEssentialPackages = readEssentialPackagesForEvaluation(
            capturedBoundary.connectionGeneration
        )
        val facts = captureForegroundFacts(
            event = null,
            kind = ObservationKind.SYNTHETIC_RECHECK
        )
        val result = foregroundEvidenceModule.classify(
            facts = facts,
            policy = ForegroundEvidencePolicySnapshot(
                essentialPackages = configuredEssentialPackages +
                    setOf(servicePackageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            )
        )
        return ObservedForegroundOutcome(
            outcome = result.outcomes.firstOrNull { it.packageName == packageName },
            facts = facts,
            capturedBoundary = capturedBoundary
        )
    }

    private fun shouldUseRecentForegroundEvidence(
        packageName: String,
        facts: ForegroundFacts
    ): Boolean {
        if (facts.displayState != DisplayState.UNLOCKED) return false
        val activeRoot = facts.activeRoot
        if (activeRoot.readState == ForegroundReadState.AVAILABLE &&
            !activeRoot.packageName.isNullOrBlank() &&
            activeRoot.packageName != packageName
        ) return false
        val nowElapsedMs = observationElapsedRealtimeMs()
        return synchronized(runtimeLock) {
            if (currentForegroundPackage != packageName) return@synchronized false
            val ageMs = nowElapsedMs - currentForegroundEvidenceAtElapsedMs
            ageMs in 0L..ForegroundEvidenceModule.RECENT_EVENT_MAX_AGE_MS
        }
    }

    private fun readApplicationWindowSnapshot(): ApplicationWindowSnapshot {
        applicationWindowSnapshotProvider?.let { provider ->
            return try {
                provider()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
                ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = false,
                    hasUnknownApplicationWindow = true,
                    providerFailed = true
                )
            }
        }
        val windows = try {
            service.windows
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            return ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true,
                providerFailed = true
            )
        }
        if (windows.isNullOrEmpty()) {
            return ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true,
                providerFailed = false
            )
        }

        val packages = linkedSetOf<String>()
        var hasApplicationWindow = false
        var hasUnknownPackage = false
        var unknownSlotCount = 0
        var applicationWindowCount = 0
        var providerFailed = false
        try {
            for (window in windows) {
                val isApplicationWindow = try {
                    window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logNonFatal(error)
                    hasUnknownPackage = true
                    unknownSlotCount++
                    providerFailed = true
                    false
                }
                if (!isApplicationWindow) continue
                hasApplicationWindow = true
                applicationWindowCount++
                val packageName = packageNameForWindow(window)
                if (packageName.isNullOrBlank()) {
                    hasUnknownPackage = true
                    unknownSlotCount++
                } else {
                    packages += packageName
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            providerFailed = true
            hasUnknownPackage = true
            unknownSlotCount++
        }
        return ApplicationWindowSnapshot(
            packages = packages,
            hasApplicationWindow = hasApplicationWindow || applicationWindowCount > 0,
            hasUnknownApplicationWindow = !hasApplicationWindow || hasUnknownPackage,
            providerFailed = providerFailed,
            applicationWindowCount = applicationWindowCount,
            unknownSlotCount = unknownSlotCount
        )
    }

    @Suppress("DEPRECATION")
    private fun readActiveWindowSnapshot(): ActiveWindowSnapshot {
        activeWindowSnapshotProvider?.let { provider ->
            return try {
                provider()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
                ActiveWindowSnapshot(packageName = null, readFailed = true)
            }
        }
        val root = try {
            service.rootInActiveWindow
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            return ActiveWindowSnapshot(packageName = null, readFailed = true)
        } ?: return ActiveWindowSnapshot(packageName = null)
        var readFailed = false
        val packageName = try {
            root.packageName?.toString()?.trim()?.takeIf(String::isNotEmpty)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            readFailed = true
            null
        } finally {
            try {
                root.recycle()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
                readFailed = true
            }
        }
        return ActiveWindowSnapshot(packageName = packageName, readFailed = readFailed)
    }

    private fun recordForegroundEvidence(packageName: String) {
        recordForegroundEvidence(
            packageName = packageName,
            evidenceAtElapsedMs = observationElapsedRealtimeMs()
        )
    }

    private fun recordForegroundEvidence(
        packageName: String,
        evidenceAtElapsedMs: Long
    ) {
        if (!isNonessentialPackage(packageName)) return
        synchronized(runtimeLock) {
            currentForegroundPackage = packageName
            lastNonessentialForegroundPackage = packageName
            currentForegroundEvidenceAtElapsedMs = evidenceAtElapsedMs
            suspendedForegroundPackage = null
            foregroundEvidenceSuspended = false
        }
    }

    /** Revalidates every lifecycle input captured before a framework/provider read. */
    private fun recordForegroundEvidenceIfCurrent(
        packageName: String,
        evidenceAtElapsedMs: Long,
        expectedConnectionGeneration: Long,
        expectedRecheckGeneration: Long,
        requireSuspended: Boolean
    ): Boolean {
        if (!isNonessentialPackage(packageName)) return false
        val recorded = synchronized(runtimeLock) {
            if (!isReadyForChecks() ||
                lifecycleGeneration.get().coerceAtLeast(1L) != expectedConnectionGeneration ||
                recheckGeneration.get() != expectedRecheckGeneration ||
                foregroundEvidenceSuspended != requireSuspended
            ) return false
            currentForegroundPackage = packageName
            lastNonessentialForegroundPackage = packageName
            currentForegroundEvidenceAtElapsedMs = evidenceAtElapsedMs
            suspendedForegroundPackage = null
            foregroundEvidenceSuspended = false
            true
        }
        if (recorded) foregroundEvidenceRecordObserver?.invoke(packageName)
        return recorded
    }

    private fun isNonessentialPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val configuredEssential = essentialPackages
        val servicePackage = servicePackageName
        return packageName !in configuredEssential &&
            packageName != servicePackage &&
            packageName != Constants.SYSTEM_UI_PACKAGE_NAME
    }

    private fun cancelScheduledRecheck(packageName: String) {
        wakeScheduler?.cancel(packageName)
    }

    private fun cancelScheduledRechecks() {
        wakeScheduler?.cancelAll()
    }

    private fun captureLifecycleBoundary(
        expectedRecheckGeneration: Long? = null
    ): CapturedLifecycleBoundary? = synchronized(runtimeLock) {
        if (!isReadyForChecks()) return@synchronized null
        val connectionGeneration = lifecycleGeneration.get().coerceAtLeast(1L)
        val capturedRecheckGeneration = recheckGeneration.get()
        if (expectedRecheckGeneration != null &&
            capturedRecheckGeneration != expectedRecheckGeneration
        ) {
            return@synchronized null
        }
        CapturedLifecycleBoundary(
            connectionGeneration = connectionGeneration,
            recheckGeneration = capturedRecheckGeneration,
            foregroundEvidenceSuspended = foregroundEvidenceSuspended
        )
    }

    private fun isCurrentLifecycleBoundary(boundary: CapturedLifecycleBoundary): Boolean =
        synchronized(runtimeLock) {
            isReadyForChecks() &&
                lifecycleGeneration.get().coerceAtLeast(1L) == boundary.connectionGeneration &&
                recheckGeneration.get() == boundary.recheckGeneration
        }

    private fun isReadyForChecks(): Boolean =
        setupReady && !destroyed && (::service.isInitialized || wakeScheduler != null) && scope.isActive

    private fun isReadyForChecks(connectionGeneration: Long): Boolean =
        isReadyForChecks() &&
            lifecycleGeneration.get().coerceAtLeast(1L) == connectionGeneration.coerceAtLeast(1L)

    private fun beginDrainWork(counter: AtomicInteger): () -> Unit {
        counter.incrementAndGet()
        return { finishDrainWork(counter) }
    }

    /**
     * Reserves an external effect while holding the lifecycle lock. The reservation contains only
     * local state; framework calls happen after the lock has been released.
     */
    private fun reserveExternalEffectLocked(
        counter: AtomicInteger,
        isCurrentLocked: () -> Boolean
    ): ExternalEffectPermit? {
        if (!isCurrentLocked()) return null
        return ExternalEffectPermit(counter).also { permit ->
            counter.incrementAndGet()
            pendingExternalEffects += permit
        }
    }

    private fun finishExternalEffectLocked(permit: ExternalEffectPermit): Boolean {
        if (permit.state.getAndSet(EXTERNAL_EFFECT_FINISHED) == EXTERNAL_EFFECT_FINISHED) {
            return false
        }
        permit.externalInvocationInProgress = false
        permit.postedCallbackInProgress = false
        pendingExternalEffects.remove(permit)
        return true
    }

    private fun finishExternalEffect(permit: ExternalEffectPermit) {
        val counter = synchronized(runtimeLock) {
            if (finishExternalEffectLocked(permit)) permit.counter else null
        }
        counter?.let(::finishDrainWork)
    }

    /** Starts a one-shot effect only after revalidating its captured lifecycle under the lock. */
    private fun startExternalEffect(
        permit: ExternalEffectPermit,
        isCurrentLocked: () -> Boolean
    ): Boolean {
        var finishedByThisCall = false
        val started = synchronized(runtimeLock) {
            if (permit.state.get() != EXTERNAL_EFFECT_RESERVED || !isCurrentLocked()) {
                finishedByThisCall = finishExternalEffectLocked(permit)
                false
            } else {
                permit.state.compareAndSet(
                    EXTERNAL_EFFECT_RESERVED,
                    EXTERNAL_EFFECT_STARTING
                )
                true
            }
        }
        if (finishedByThisCall) {
            finishDrainWork(permit.counter)
        }
        return started
    }

    /**
     * Linearizes the actual framework call immediately before it leaves this adapter. A destroy
     * can cancel STARTING, but once this point is crossed the permit is counted as an in-flight
     * call and destroy drains it instead of racing the call from outside the lock.
     */
    private fun beginExternalEffectCall(
        permit: ExternalEffectPermit,
        isCurrentLocked: () -> Boolean
    ): Boolean {
        var finishedByThisCall = false
        val started = synchronized(runtimeLock) {
            if (permit.state.get() != EXTERNAL_EFFECT_STARTING || !isCurrentLocked()) {
                finishedByThisCall = finishExternalEffectLocked(permit)
                false
            } else {
                permit.state.set(EXTERNAL_EFFECT_RUNNING)
                permit.externalInvocationInProgress = true
                true
            }
        }
        if (finishedByThisCall) finishDrainWork(permit.counter)
        return started
    }

    /** Marks a direct framework call complete while retaining the permit for later work. */
    private fun completeExternalEffectCall(permit: ExternalEffectPermit) {
        synchronized(runtimeLock) {
            permit.externalInvocationInProgress = false
        }
    }

    /** Rearms one multi-call permit only after the previous direct framework call has returned. */
    private fun rearmExternalEffect(
        permit: ExternalEffectPermit,
        isCurrentLocked: () -> Boolean
    ): Boolean {
        var finishedByThisCall = false
        val rearmed = synchronized(runtimeLock) {
            if (permit.state.get() != EXTERNAL_EFFECT_RUNNING ||
                permit.externalInvocationInProgress ||
                !isCurrentLocked()
            ) {
                finishedByThisCall = finishExternalEffectLocked(permit)
                false
            } else {
                permit.state.set(EXTERNAL_EFFECT_STARTING)
                true
            }
        }
        if (finishedByThisCall) finishDrainWork(permit.counter)
        return rearmed
    }

    /** Completes the external post handshake without invoking a framework callback under lock. */
    private fun armPostedEffect(
        permit: ExternalEffectPermit,
        posted: Boolean,
        isCurrentLocked: () -> Boolean
    ): Boolean {
        var finishedByThisCall = false
        synchronized(runtimeLock) {
            permit.externalInvocationInProgress = false
            when {
                !posted -> finishedByThisCall = finishExternalEffectLocked(permit)
                permit.state.get() == EXTERNAL_EFFECT_FINISHED -> Unit
                permit.state.get() == EXTERNAL_EFFECT_RUNNING &&
                    permit.postedCallbackInProgress -> Unit
                permit.state.get() != EXTERNAL_EFFECT_RUNNING -> Unit
                !isCurrentLocked() -> finishedByThisCall = finishExternalEffectLocked(permit)
                else -> {
                    permit.state.compareAndSet(
                        EXTERNAL_EFFECT_RUNNING,
                        EXTERNAL_EFFECT_ARMED
                    )
                }
            }
        }
        if (finishedByThisCall) {
            finishDrainWork(permit.counter)
        }
        if (!posted) return false
        return permit.state.get() == EXTERNAL_EFFECT_ARMED ||
            permit.state.get() == EXTERNAL_EFFECT_RUNNING
    }

    private fun enterPostedEffect(permit: ExternalEffectPermit): Boolean = synchronized(runtimeLock) {
        when (permit.state.get()) {
            EXTERNAL_EFFECT_ARMED -> {
                permit.postedCallbackInProgress = true
                permit.state.compareAndSet(
                    EXTERNAL_EFFECT_ARMED,
                    EXTERNAL_EFFECT_RUNNING
                )
            }
            // Test adapters may deliver a callback synchronously from the external post. The
            // post is already linearized, so keep the same permit counted for its callback body.
            EXTERNAL_EFFECT_RUNNING -> {
                if (!permit.externalInvocationInProgress) {
                    false
                } else {
                    permit.postedCallbackInProgress = true
                    true
                }
            }
            else -> false
        }
    }

    /** Cancels effects that have not crossed the final framework-call linearization point. */
    private fun cancelPendingExternalEffectsLocked(): List<AtomicInteger> {
        val counters = mutableListOf<AtomicInteger>()
        pendingExternalEffects.toList().forEach { permit ->
            val state = permit.state.get()
            if (state == EXTERNAL_EFFECT_RESERVED ||
                state == EXTERNAL_EFFECT_STARTING ||
                state == EXTERNAL_EFFECT_ARMED
            ) {
                if (finishExternalEffectLocked(permit)) counters += permit.counter
            }
        }
        return counters
    }

    private fun finishDrainWork(counter: AtomicInteger) {
        counter.decrementAndGet()
        synchronized(drainMonitor) {
            drainMonitor.notifyAll()
        }
    }

    private fun drainWorkSnapshot(): AppRuleDrainWorkSnapshot = AppRuleDrainWorkSnapshot(
        refreshes = inFlightRefreshes.get(),
        notifications = inFlightNotifications.get(),
        callbacks = inFlightCallbacks.get(),
        usageResetCompletions = inFlightUsageResetCompletions.get(),
        recheckPlans = inFlightRecheckPlans.get()
    )

    private fun awaitDrainWorkUntil(deadlineElapsedMs: Long): Boolean {
        synchronized(drainMonitor) {
            while (drainWorkSnapshot().hasWork) {
                val remainingMs = deadlineElapsedMs - observationElapsedRealtimeMs()
                if (remainingMs <= 0L) return false
                try {
                    drainMonitor.wait(remainingMs.coerceAtMost(50L))
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return observationElapsedRealtimeMs() <= deadlineElapsedMs
    }

    private fun runCandidateCleanupStage(
        deadline: TotalDrainDeadline,
        cleanup: () -> Unit
    ): Boolean {
        val startedAtElapsedMs = observationElapsedRealtimeMs()
        return try {
            cleanup()
            startedAtElapsedMs <= deadline.elapsedRealtimeMs &&
                observationElapsedRealtimeMs() <= deadline.elapsedRealtimeMs
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun packageNameForWindow(
        window: android.view.accessibility.AccessibilityWindowInfo
    ): String? {
        val root = try {
            window.root
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            return null
        } ?: return null
        return try {
            root.packageName?.toString()?.trim()?.takeIf(String::isNotEmpty)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            null
        } finally {
            try {
                root.recycle()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
    }

    private fun removeHandlerCallback(runnable: Runnable) {
        try {
            recheckRemoveCallback?.let {
                it(runnable)
                return
            }
            handlerInstance?.removeCallbacks(runnable)
        } catch (error: Throwable) {
            logNonFatal(error)
        }
    }

    private fun logNonFatal(error: Throwable) {
        if (::crashLogger.isInitialized) {
            runCatching {
                crashLogger.logNonFatalError(
                    if (error is Exception) error else Exception(error)
                )
            }
        }
    }

    private fun safeElapsedRealtimeAdd(baseMs: Long, deltaMs: Long): Long =
        if (deltaMs > 0L && baseMs > Long.MAX_VALUE - deltaMs) {
            Long.MAX_VALUE
        } else {
            (baseMs + deltaMs).coerceAtLeast(0L)
        }

    private fun safeWallClockAdd(baseMs: Long, deltaMs: Long): Long =
        if (deltaMs > 0L && baseMs > Long.MAX_VALUE - deltaMs) {
            Long.MAX_VALUE
        } else {
            baseMs + deltaMs
        }

    private fun safeResetTime(hour: Int, minute: Int): UseDayResetTime = try {
        UseDayResetTime(hour, minute)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        UseDayResetTime()
    }

    @Suppress("DEPRECATION")
    private fun obtainWindowStateEvent(): AccessibilityEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
}

internal fun retryRejectedWorkerSubmission(
    initialResult: SubmissionResult,
    canRetry: () -> Boolean,
    retry: () -> SubmissionResult,
    onRepeatedRejection: () -> Unit
): SubmissionResult {
    if (initialResult != SubmissionResult.REJECTED_NOT_READY || !canRetry()) {
        return initialResult
    }
    val retryResult = retry()
    if (retryResult == SubmissionResult.REJECTED_NOT_READY) {
        onRepeatedRejection()
    }
    return retryResult
}
