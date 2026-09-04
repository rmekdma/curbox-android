package neth.iecal.curbox.blockers

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neth.iecal.curbox.Constants
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleEvaluation
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleEvaluator
import neth.iecal.curbox.domain.apprules.AppRuleMembershipResolver
import neth.iecal.curbox.domain.apprules.AppRuleReevaluationGate
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleReceiverLifecycle
import neth.iecal.curbox.domain.apprules.AppRuleRecheckPlanner
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.ActiveRootFact
import neth.iecal.curbox.domain.apprules.AndroidForegroundObservationSource
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
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.ObservationTrigger
import neth.iecal.curbox.domain.apprules.SignalFact
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationFormatter
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationStateCalculator
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import java.util.concurrent.atomic.AtomicLong

/** Enforces the new atomic app-rule snapshot without changing the legacy blocker. */
class AppRuleBlocker {
    companion object {
        const val INTENT_ACTION_REFRESH_APP_RULES = "neth.iecal.curbox.refresh.app_rules"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MAX_VISIBILITY_RETRIES = 3
        private const val MAX_SCHEDULER_POST_ATTEMPTS = 3
        private const val VISIBILITY_RETRY_DELAY_MS = 250L
        private const val UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS = 20_000L
        private const val OBSERVATION_RECHECK_KEY = "\u0000foreground-observation"

        internal fun createGuardianApprovalIntent(
            context: Context,
            packageName: String,
            denials: List<AppRuleGuardianDenial>
        ): Intent = Intent(context, GuardianApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(GuardianApprovalActivity.EXTRA_PACKAGE, packageName)
            putExtra(GuardianApprovalActivity.EXTRA_DENIALS, Gson().toJson(denials))
        }
    }

    private lateinit var service: BaseBlockingService
    private lateinit var crashLogger: CrashLogger
    private lateinit var sessionRepository: CurrentUseDaySessionRepository
    private lateinit var enforcement: AppRuleEnforcement
    private val snapshot = AppRuleSnapshotCoordinator()
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshMutex = Mutex()
    private val runtimeLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var notificationTickJob: kotlinx.coroutines.Job? = null
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
    private val reevaluationGate = AppRuleReevaluationGate()
    @Volatile private var lastPostedNotificationModel: LiveRuleNotificationModel? = null
    @Volatile private var activeGuardianPackage: String? = null
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var currentForegroundEvidenceAtElapsedMs = 0L
    @Volatile private var suspendedForegroundPackage: String? = null
    @Volatile private var foregroundEvidenceSuspended = false
    @Volatile private var screenOnAwaitingUserPresent = false
    private val recheckGeneration = AtomicLong(0L)
    private val lifecycleGeneration = AtomicLong(0L)
    private val scheduledRechecks = mutableMapOf<String, ScheduledRecheck>()
    private val applicationWindowProvenanceCache = ApplicationWindowProvenanceCache()
    private var foregroundEvidenceModule = ForegroundEvidenceModule()
    private var foregroundObservationSource: AndroidForegroundObservationSource? = null
    private var sourceOrderSequencer = AtomicConnectionScopedSourceOrderSequencer()

    private data class RuleRuntime(
        val snapshot: AppRuleSnapshot,
        val resetTime: UseDayResetTime,
        val useDayGenerationStartedAtMs: Long,
        val overrideState: AppRuleOverrideState,
        val launchablePackages: Set<String>,
        val generation: Long
    )

    private data class ScheduledRecheck(
        val generation: Long,
        val runnable: Runnable
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
    internal var recheckRemoveCallback: ((Runnable) -> Unit)? = null
    /** Temporary seam for deterministic wake-reconciliation contract tests. */
    internal var visibleApplicationCheckPostDelayed: ((Runnable, Long) -> Boolean)? = null
    /** Temporary seam for observing removal of virtual wake-reconciliation callbacks. */
    internal var visibleApplicationCheckRemoveCallbacks: (() -> Unit)? = null
    /** Temporary seams for deterministic screen and keyguard recovery contract tests. */
    internal var screenInteractiveProvider: (() -> Boolean)? = null
    internal var keyguardLockedProvider: (() -> Boolean)? = null
    internal var evaluationResultObserver: ((AppRulesEvaluation) -> Unit)? = null
    /** Temporary seam for observing the external notification publication boundary. */
    internal var notificationPostObserver: ((LiveRuleNotificationModel) -> Unit)? = null

    fun setup(service: BaseBlockingService) {
        val connectionGeneration = lifecycleGeneration.incrementAndGet()
        synchronized(runtimeLock) {
            destroyed = false
            setupReady = false
            // A reconnect must invalidate work captured by the previous service connection.
            recheckGeneration.incrementAndGet()
            applicationWindowProvenanceCache.clear()
        }
        settingsJob?.cancel()
        notificationTickJob?.cancel()
        cancelScheduledRechecks()
        handler.removeCallbacksAndMessages(null)
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = null
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        currentForegroundPackage = null
        currentForegroundEvidenceAtElapsedMs = 0L
        suspendedForegroundPackage = null
        foregroundEvidenceSuspended = false
        screenOnAwaitingUserPresent = false
        lastPostedNotificationModel = null
        activeGuardianPackage = null
        lastShownAt = 0L
        this.service = service
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
        sessionRepository = RoomCurrentUseDaySessionRepository(database.foregroundSessionDao())
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
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
        settingsJob = scope.launch {
            try {
                service.dataStoreManager.settings.collect { settings ->
                    if (!isReadyForChecks(connectionGeneration)) return@collect
                    // Serialize settings emissions with the explicit refresh receiver. A burst of
                    // DataStore writes must not let an older refresh publish after a newer one.
                    val changed = refreshMutex.withLock {
                        if (isReadyForChecks(connectionGeneration)) {
                            applySettingsSnapshot(settings)
                        } else {
                            false
                        }
                    }
                    if (!isReadyForChecks(connectionGeneration)) return@collect
                    if (changed) {
                        postVisibleApplicationCheck(
                            connectionGeneration = connectionGeneration,
                            observationKind = ObservationKind.REFRESH
                        )
                    }
                    updateLiveNotification()
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

    fun doAppRuleCheck(event: AccessibilityEvent?) {
        doAppRuleCheck(event, updateForegroundEvidence = true)
    }

    /**
     * Synthetic checks reconcile an already visible package but must not turn a stale window
     * entry into the foreground fallback used by the next boundary check.
     */
    private fun doAppRuleCheck(
        event: AccessibilityEvent?,
        updateForegroundEvidence: Boolean,
        failClosedOnEvaluationFailure: Boolean = false
    ) {
        if (!isReadyForChecks()) return
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackageName = event.packageName?.toString().orEmpty()
        if (eventPackageName.isBlank()) return

        val evaluationEssentialPackages = readEssentialPackagesForEvaluation()
        val evidencePolicy = ForegroundEvidencePolicySnapshot(
            essentialPackages = evaluationEssentialPackages +
                setOf(service.packageName, Constants.SYSTEM_UI_PACKAGE_NAME)
        )
        val foregroundEvidence = foregroundEvidenceModule.classify(
            facts = captureForegroundFacts(
                event = event,
                kind = if (updateForegroundEvidence) {
                    ObservationKind.REAL_EVENT
                } else {
                    ObservationKind.SYNTHETIC_RECHECK
                }
            ),
            policy = evidencePolicy
        )
        val packageOutcomes = linkedMapOf<String, ForegroundEvidenceOutcome>()
        foregroundEvidence.outcomes.firstOrNull { it.packageName == eventPackageName }
            ?.let { outcome -> packageOutcomes[eventPackageName] = outcome }
        if (updateForegroundEvidence) {
            foregroundEvidence.outcomes
                .filterIsInstance<ForegroundEvidenceOutcome.Visible>()
                .filter {
                    it.evidenceBasis ==
                        neth.iecal.curbox.domain.apprules.EvidenceBasis.APPLICATION_WINDOW &&
                    it.decisionPermission ==
                        neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE
                }
                .forEach { outcome ->
                    packageOutcomes.putIfAbsent(outcome.packageName, outcome)
                }
        }
        val evidenceGeneration = recheckGeneration.get()
        packageOutcomes.forEach { (packageName, packageOutcome) ->
            if (!isReadyForChecks() || recheckGeneration.get() != evidenceGeneration) return
            evaluateForegroundOutcome(
                packageName = packageName,
                packageOutcome = packageOutcome,
                updateForegroundEvidence = updateForegroundEvidence &&
                    packageName == eventPackageName,
                failClosedOnEvaluationFailure = failClosedOnEvaluationFailure,
                evaluationEssentialPackages = evaluationEssentialPackages
            )
        }
    }

    private fun evaluateForegroundOutcome(
        packageName: String,
        packageOutcome: ForegroundEvidenceOutcome,
        updateForegroundEvidence: Boolean,
        failClosedOnEvaluationFailure: Boolean,
        evaluationEssentialPackages: Set<String>
    ) {
        val shouldFailClosed = when (packageOutcome) {
            is ForegroundEvidenceOutcome.Visible ->
                packageOutcome.decisionPermission ==
                    neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE_FAIL_CLOSED
            is ForegroundEvidenceOutcome.Unknown ->
                packageOutcome.decisionPermission ==
                    neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE_FAIL_CLOSED
            is ForegroundEvidenceOutcome.NotVisible -> {
                cancelScheduledRecheck(packageName)
                false
            }
        }
        if (packageOutcome is ForegroundEvidenceOutcome.NotVisible ||
            packageOutcome.decisionPermission ==
                neth.iecal.curbox.domain.apprules.DecisionPermission.DEFER ||
            packageOutcome.decisionPermission ==
                neth.iecal.curbox.domain.apprules.DecisionPermission.DO_NOT_EVALUATE
        ) {
            return
        }

        if (updateForegroundEvidence) recordForegroundEvidence(packageName)
        updateLiveNotification(packageName.takeIf { updateForegroundEvidence })

        // Capture all rule inputs under one lock. Settings updates replace these values as one
        // generation, so an in-flight evaluation can never combine a new snapshot with an old
        // reset clock or guardian ledger.
        val runtime = captureRuleRuntime()
        val generation = runtime.generation
        val currentSnapshot = runtime.snapshot
        if (currentSnapshot.appRules.none { it.isActive }) {
            cancelScheduledRechecks()
            return
        }
        val now = wallClockMsProvider()
        val calculator = ConfigurableUseDayCalculator(resetTime = runtime.resetTime)
        val useDayId = calculator.idAt(now)
        var evaluationFailedClosed = false
        val evaluation = try {
            runBlocking(Dispatchers.IO) {
                enforcement.check(
                    snapshot = currentSnapshot,
                    packageName = packageName,
                    useDayId = useDayId,
                    nowMs = now,
                    calculator = calculator,
                    useDayGenerationStartedAtMs = runtime.useDayGenerationStartedAtMs,
                    availablePackages = runtime.launchablePackages,
                    essentialExcludedPackages = evaluationEssentialPackages,
                    overrides = runtime.overrideState
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            if (!failClosedOnEvaluationFailure && !shouldFailClosed) return
            evaluationFailedClosed = true
            failClosedEvaluation(
                snapshot = currentSnapshot,
                packageName = packageName,
                useDayId = useDayId,
                nowMs = now,
                calculator = calculator,
                runtime = runtime,
                essentialPackages = evaluationEssentialPackages
            )
        }

        evaluationResultObserver?.let { observer ->
            try {
                observer(evaluation)
            } catch (error: Throwable) {
                logNonFatal(error)
            }
        }

        if (!isReadyForChecks() || recheckGeneration.get() != generation) return

        if (evaluationFailedClosed) {
            cancelScheduledRecheck(packageName)
        } else {
            val nextPlan = AppRuleRecheckPlanner.nextPlan(
                snapshot = currentSnapshot,
                evaluation = evaluation,
                overrideState = runtime.overrideState,
                useDayId = useDayId,
                nowMs = now,
                useDayGenerationStartedAtMs = runtime.useDayGenerationStartedAtMs,
                zone = calculator.zone,
                useDayCalculator = calculator
            )
            if (nextPlan != null) {
                scheduleRecheck(
                    packageName = packageName,
                    remainingMillis = nextPlan.delayMillis,
                    maxDelayMillis = nextPlan.maxDelayMillis,
                    generation = generation
                )
            } else {
                cancelScheduledRecheck(packageName)
            }
        }
        if (evaluation.denyingRules.isEmpty()) {
            synchronized(runtimeLock) {
                if (activeGuardianPackage == packageName) activeGuardianPackage = null
            }
            return
        }
        val bypassThrottle = reevaluationGate.consumeIfApplicable(evaluation.evaluations.isNotEmpty())
        if (!bypassThrottle && now - lastShownAt < 1_000L) return
        lastShownAt = now
        showWarning(packageName, evaluation, currentSnapshot, generation)
    }

    private fun failClosedEvaluation(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        nowMs: Long,
        calculator: ConfigurableUseDayCalculator,
        runtime: RuleRuntime,
        essentialPackages: Set<String>
    ): AppRulesEvaluation {
        val eligibility = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = packageName,
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = nowMs,
            zone = calculator.zone,
            useDayCalculator = calculator,
            useDayGenerationStartedAtMs = runtime.useDayGenerationStartedAtMs,
            availablePackages = runtime.launchablePackages,
            essentialExcludedPackages = essentialPackages,
            overrides = runtime.overrideState
        )
        val applicable = eligibility.evaluations
            .filter { it.isApplicable && it.isActive && !it.isSkipped }
            .ifEmpty { eligibility.denyingRules }
        val denials = applicable.map { evaluation ->
            evaluation.copy(
                remainingMillis = 0L,
                isAllowed = false
            )
        }
        return AppRulesEvaluation(
            isAllowed = false,
            denyingRules = denials,
            evaluations = denials
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
        val notificationForeground = foregroundPackage ?: currentForegroundPackage
        scope.launch(Dispatchers.IO) {
            try {
                if (!isReadyForChecks() || recheckGeneration.get() != generation) return@launch
                val currentSnapshot = runtime.snapshot
                val now = wallClockMsProvider()
                val calculator = ConfigurableUseDayCalculator(resetTime = runtime.resetTime)
                val useDayId = calculator.idAt(now)
                val evaluationEssentialPackages = readEssentialPackagesForEvaluation()

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
                        LiveRuleNotificationFormatter.formatRuleStatus(
                            context = service,
                            ruleName = item.ruleName,
                            usedMinutes = item.usedMinutes,
                            totalAllowedMinutes = item.totalAllowedMinutes,
                            guardianExtraMinutes = item.guardianExtraMinutes
                        )
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
                    }
                )

                if (isReadyForChecks() && recheckGeneration.get() == generation &&
                    model != lastPostedNotificationModel
                ) {
                    lastPostedNotificationModel = model
                    notificationPostObserver?.invoke(model)
                    service.updateForegroundNotification(model)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
    }

    private fun showWarning(
        packageName: String,
        evaluation: neth.iecal.curbox.domain.apprules.AppRulesEvaluation,
        evaluatedSnapshot: AppRuleSnapshot,
        generation: Long
    ) {
        if (!isReadyForChecks() || recheckGeneration.get() != generation) return
        try {
            synchronized(runtimeLock) {
                // Settings can be emitted between the evaluation and this call. Keep the
                // generation check and activity launch in one short critical section so a stale
                // evaluation cannot open a guardian after a newer snapshot was published.
                if (!isReadyForChecks() || recheckGeneration.get() != generation) return
                if (activeGuardianPackage == packageName) return
                if (!service.isDelayOver(1_000)) return
                val denialRows = evaluation.denyingRules.map { denial ->
                    val rule = evaluatedSnapshot.appRules.find { it.id == denial.ruleId }
                    AppRuleGuardianDenial(
                        ruleId = denial.ruleId,
                        ruleName = rule?.name ?: denial.ruleId,
                        reason = warningStatus(denial)
                    )
                }
                service.startActivity(
                    createGuardianApprovalIntent(service, packageName, denialRows)
                )
                activeGuardianPackage = packageName
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
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
        lifecycleGeneration.incrementAndGet()
        synchronized(runtimeLock) {
            setupReady = false
            destroyed = true
            recheckGeneration.incrementAndGet()
            currentForegroundPackage = null
            activeGuardianPackage = null
            currentForegroundEvidenceAtElapsedMs = 0L
            suspendedForegroundPackage = null
            foregroundEvidenceSuspended = true
            screenOnAwaitingUserPresent = false
            applicationWindowProvenanceCache.clear()
        }
        settingsJob?.cancel()
        notificationTickJob?.cancel()
        cancelScheduledRechecks()
        try {
            visibleApplicationCheckRemoveCallbacks?.invoke()
        } catch (error: Throwable) {
            logNonFatal(error)
        }
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = null
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_REFRESH_APP_RULES) return
            if (!isReadyForChecks()) return
            val connectionGeneration = lifecycleGeneration.get()
            // Settings flow is authoritative. This action exists for the same UI to service
            // refresh path as the legacy blocker and simply triggers a harmless re-read.
            scope.launch {
                try {
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    refreshMutex.withLock {
                        if (!isReadyForChecks(connectionGeneration)) return@withLock false
                        val packageScopeChanged = refreshPackageScope()
                        val settings = service.dataStoreManager.settings.first()
                        if (!isReadyForChecks(connectionGeneration)) return@withLock false
                        packageScopeChanged || applySettingsSnapshot(settings)
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
        observationAttempt: Int
    ) {
        if (!isReadyForChecks()) return
        try {
            val configuredEssentialPackages = readEssentialPackagesForEvaluation()
            val policy = ForegroundEvidencePolicySnapshot(
                essentialPackages = configuredEssentialPackages +
                    setOf(service.packageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            )
            val facts = captureForegroundFacts(event = null, kind = observationKind)
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
                synchronized(runtimeLock) {
                    if (foregroundEvidenceSuspended) {
                        currentForegroundPackage = resumedPackage
                        currentForegroundEvidenceAtElapsedMs = facts.capturedAtElapsedMs
                        suspendedForegroundPackage = null
                        foregroundEvidenceSuspended = false
                    }
                }
            }
            val generation = recheckGeneration.get()
            val needsObservationRetry = result.outcomes.any { outcome ->
                outcome is ForegroundEvidenceOutcome.Unknown &&
                    outcome.candidatePackage == null &&
                    outcome.followUp == FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
            }
            result.outcomes.forEach { outcome ->
                if (!isReadyForChecks() || recheckGeneration.get() != generation) return
                when (outcome) {
                    is ForegroundEvidenceOutcome.NotVisible ->
                        cancelScheduledRecheck(outcome.packageName)
                    is ForegroundEvidenceOutcome.Visible -> {
                        if (outcome.decisionPermission ==
                                neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE
                        ) {
                            dispatchSyntheticCheck(outcome.packageName, generation)
                        }
                    }
                    is ForegroundEvidenceOutcome.Unknown -> Unit
                }
            }
            if (needsObservationRetry && observationAttempt < MAX_VISIBILITY_RETRIES) {
                postScheduledRecheck(
                    packageName = OBSERVATION_RECHECK_KEY,
                    generation = generation,
                    delayMillis = VISIBILITY_RETRY_DELAY_MS * (observationAttempt + 1),
                    visibilityAttempt = observationAttempt + 1
                )
            } else {
                cancelScheduledRecheck(OBSERVATION_RECHECK_KEY)
            }
        } catch (error: Throwable) {
            logNonFatal(error)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isReadyForChecks()) return
            val connectionGeneration = lifecycleGeneration.get()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> {
                    // The observation source reads the display state. SCREEN_ON is only a cheap
                    // wake trigger; USER_PRESENT supplies the follow-up observation if keyguard
                    // is still present when this one runs.
                    screenOnAwaitingUserPresent = false
                    postVisibleApplicationCheck(
                        delayMillis = 300L,
                        connectionGeneration = connectionGeneration,
                        observationKind = ObservationKind.SCREEN_WAKE
                    )
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOnAwaitingUserPresent = false
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
                if (action == GuardianApprovalActivity.INTENT_ACTION_OPENED) {
                    activeGuardianPackage = packageName
                } else if (activeGuardianPackage == packageName) {
                    activeGuardianPackage = null
                    lastShownAt = 0L
                }
            }
        }
    }

    private fun handleScreenOff() {
        synchronized(runtimeLock) {
            screenOnAwaitingUserPresent = false
            // Invalidate both keyed boundary callbacks and any reconciliation callback already
            // posted for the visible display. The screen-off broadcast is the terminal signal for
            // this foreground observation; no provider retry may run until wake.
            recheckGeneration.incrementAndGet()
        }
        cancelScheduledRechecks()
        clearForegroundEvidence()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isReadyForChecks()) return
            val connectionGeneration = lifecycleGeneration.get()
            // A new launchable app is part of an all-apps scope without requiring a rule edit.
            scope.launch {
                try {
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    refreshMutex.withLock {
                        if (isReadyForChecks(connectionGeneration)) refreshPackageScope()
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
                }
            }
        }
    }

    private fun refreshPackageScope(): Boolean {
        if (!::service.isInitialized) return false
        val reader = packageScopeReader ?: return false
        val previousLaunchable = launchablePackages
        val previousEssential = essentialPackages
        try {
            // Read both sets before publishing either one so a transient PackageManager failure
            // cannot expose a half-updated all-apps scope.
            val nextLaunchable = reader.readLaunchablePackages()
            val nextEssential = reader.readEssentialPackages()
            synchronized(runtimeLock) {
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
                essentialPackages = fallbackEssential
            }
            logNonFatal(error)
            return previousEssential != fallbackEssential
        }
    }

    private fun readEssentialPackagesForEvaluation(): Set<String> {
        val reader = packageScopeReader ?: return essentialPackages
        return try {
            reader.readEssentialPackages().also { packages ->
                synchronized(runtimeLock) {
                    essentialPackages = packages
                }
            }
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
    private fun applySettingsSnapshot(settings: Settings): Boolean {
        val candidate = try {
            settings.appRuleSnapshot.normalized().takeIf { it.isValid }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            null
        }
        val nextReset = safeResetTime(settings.useDayResetHour, settings.useDayResetMinute)
        synchronized(runtimeLock) {
            if (destroyed) return false
            val previousSnapshot = snapshot.snapshot()
            val nextSnapshot = candidate ?: previousSnapshot
            val changed = nextSnapshot != previousSnapshot ||
                nextReset != resetTime ||
                settings.useDayGenerationStartedAtMs != useDayGenerationStartedAtMs ||
                settings.appRuleOverrideState != overrideState
            if (!changed) return false

            if (settings.appRuleOverrideState != overrideState) {
                reevaluationGate.markOverrideChanged()
            }
            resetTime = nextReset
            useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
            overrideState = settings.appRuleOverrideState
            if (candidate != null) snapshot.accept(candidate)
            recheckGeneration.incrementAndGet()
            cancelScheduledRechecks()
            return true
        }
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
    ): ForegroundFacts {
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
        val factsWithProvenance = if (source != null) {
            facts.copy(
                applicationWindows = applicationWindowProvenanceCache.resolve(
                    raw = facts.applicationWindows.toApplicationWindowSnapshot(),
                    capturedAtElapsedMs = facts.capturedAtElapsedMs
                ).toForegroundFact()
            )
        } else {
            facts
        }
        return if (screenOnAwaitingUserPresent && factsWithProvenance.displayState == DisplayState.UNLOCKED) {
            factsWithProvenance.copy(displayState = DisplayState.KEYGUARD)
        } else {
            factsWithProvenance
        }
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

    private fun ApplicationWindowsFact.toApplicationWindowSnapshot(): ApplicationWindowSnapshot =
        ApplicationWindowSnapshot(
            packages = packages,
            stalePackages = stalePackages,
            hasApplicationWindow = readState != ForegroundReadState.EMPTY ||
                packages.isNotEmpty() || unknownSlotCount > 0,
            hasUnknownApplicationWindow = readState == ForegroundReadState.FAILED ||
                unknownSlotCount > 0,
            providerFailed = readState == ForegroundReadState.FAILED,
            applicationWindowCount = (packages.size + unknownSlotCount).coerceAtLeast(
                if (readState == ForegroundReadState.FAILED) 1 else 0
            ),
            unknownSlotCount = unknownSlotCount,
            freshness = freshness,
            capturedAtElapsedMs = 0L
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
            val callback = Runnable {
                runVisibleApplicationCheck(
                    connectionGeneration = connectionGeneration,
                    observationGeneration = observationGeneration,
                    observationKind = observationKind,
                    postAttempt = attemptNumber
                )
            }
            val retryDelay = if (attempt == 1) {
                delayMillis.coerceAtLeast(0L)
            } else {
                VISIBILITY_RETRY_DELAY_MS * (attempt - 1)
            }
            val posted = try {
                visibleApplicationCheckPostDelayed?.invoke(callback, retryDelay)
                    ?: handler.postDelayed(callback, retryDelay)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
                false
            }
            if (posted) return
            attempt++
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

    private fun scheduleRecheck(
        packageName: String,
        remainingMillis: Long,
        maxDelayMillis: Long = 20_000L,
        generation: Long = recheckGeneration.get()
    ) {
        if (!isReadyForChecks() || packageName.isBlank()) return
        val delay = remainingMillis.coerceIn(1_000L, maxDelayMillis.coerceAtLeast(1_000L))
        postScheduledRecheck(packageName, generation, delay, 0)
    }

    private fun postScheduledRecheck(
        packageName: String,
        generation: Long,
        delayMillis: Long,
        visibilityAttempt: Int,
        postAttempt: Int = 1
    ) {
        if (!isReadyForChecks() || recheckGeneration.get() != generation) return
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (packageName == OBSERVATION_RECHECK_KEY) {
                runObservationRecheck(generation, visibilityAttempt, runnable)
            } else {
                runScheduledRecheck(packageName, generation, visibilityAttempt, runnable)
            }
        }
        val previous = synchronized(scheduledRechecks) {
            val old = scheduledRechecks.remove(packageName)
            scheduledRechecks[packageName] = ScheduledRecheck(generation, runnable)
            old
        }
        previous?.let { removeHandlerCallback(it.runnable) }
        try {
            val posted = recheckPostDelayed?.invoke(
                runnable,
                delayMillis.coerceAtLeast(1L)
            ) ?: handler.postDelayed(runnable, delayMillis.coerceAtLeast(1L))
            if (!posted) {
                recoverScheduledPost(
                    packageName = packageName,
                    generation = generation,
                    visibilityAttempt = visibilityAttempt,
                    postAttempt = postAttempt
                )
            }
        } catch (error: Throwable) {
            logNonFatal(error)
            recoverScheduledPost(
                packageName = packageName,
                generation = generation,
                visibilityAttempt = visibilityAttempt,
                postAttempt = postAttempt
            )
        }
    }

    private fun recoverScheduledPost(
        packageName: String,
        generation: Long,
        visibilityAttempt: Int,
        postAttempt: Int
    ) {
        if (postAttempt >= MAX_SCHEDULER_POST_ATTEMPTS ||
            !isReadyForChecks() || recheckGeneration.get() != generation
        ) {
            return
        }
        postScheduledRecheck(
            packageName = packageName,
            generation = generation,
            delayMillis = VISIBILITY_RETRY_DELAY_MS * postAttempt,
            visibilityAttempt = visibilityAttempt,
            postAttempt = postAttempt + 1
        )
    }

    private fun runObservationRecheck(
        generation: Long,
        observationAttempt: Int,
        runnable: Runnable
    ) {
        try {
            synchronized(scheduledRechecks) {
                val scheduled = scheduledRechecks[OBSERVATION_RECHECK_KEY]
                if (scheduled == null || scheduled.runnable !== runnable ||
                    scheduled.generation != generation
                ) {
                    return
                }
                scheduledRechecks.remove(OBSERVATION_RECHECK_KEY)
            }
            if (!isReadyForChecks() || recheckGeneration.get() != generation) return
            checkCurrentlyVisibleApplications(
                observationKind = ObservationKind.SYNTHETIC_RECHECK,
                observationAttempt = observationAttempt
            )
        } catch (_: CancellationException) {
            return
        } catch (error: Throwable) {
            logNonFatal(error)
            if (observationAttempt < MAX_VISIBILITY_RETRIES &&
                isReadyForChecks() && recheckGeneration.get() == generation
            ) {
                postScheduledRecheck(
                    packageName = OBSERVATION_RECHECK_KEY,
                    generation = generation,
                    delayMillis = VISIBILITY_RETRY_DELAY_MS * (observationAttempt + 1),
                    visibilityAttempt = observationAttempt + 1
                )
            }
        }
    }

    private fun runScheduledRecheck(
        packageName: String,
        generation: Long,
        visibilityAttempt: Int,
        runnable: Runnable
    ) {
        try {
            synchronized(scheduledRechecks) {
                val scheduled = scheduledRechecks[packageName]
                if (scheduled == null || scheduled.runnable !== runnable || scheduled.generation != generation) return
                scheduledRechecks.remove(packageName)
            }
            if (!isReadyForChecks() || recheckGeneration.get() != generation) return
            if (foregroundEvidenceSuspended) {
                // An essential overlay owns the foreground effect. Keep this package's boundary
                // keyed and recover it after the overlay releases the suspended evidence.
                postScheduledRecheck(
                    packageName = packageName,
                    generation = generation,
                    delayMillis = UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS,
                    visibilityAttempt = 0
                )
                return
            }
            when (val outcome = observeForegroundOutcome(packageName)) {
                is ForegroundEvidenceOutcome.Visible -> {
                    if (outcome.decisionPermission ==
                        neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE
                    ) {
                        dispatchSyntheticCheck(packageName, generation)
                    }
                }
                is ForegroundEvidenceOutcome.NotVisible -> Unit
                is ForegroundEvidenceOutcome.Unknown,
                null -> {
                    val failClosedCandidate = outcome is ForegroundEvidenceOutcome.Unknown &&
                        outcome.candidatePackage == packageName &&
                        outcome.decisionPermission ==
                            neth.iecal.curbox.domain.apprules.DecisionPermission.EVALUATE_FAIL_CLOSED
                    if (visibilityAttempt < MAX_VISIBILITY_RETRIES) {
                        postScheduledRecheck(
                            packageName = packageName,
                            generation = generation,
                            delayMillis = VISIBILITY_RETRY_DELAY_MS * (visibilityAttempt + 1),
                            visibilityAttempt = visibilityAttempt + 1
                        )
                    } else if (failClosedCandidate) {
                        // R5 A is a bounded, package-checked fallback. The module grants this
                        // permission without renewing evidence validity; the evaluator remains the
                        // final allow/deny authority.
                        dispatchSyntheticCheck(
                            packageName = packageName,
                            generation = generation,
                            failClosedOnEvaluationFailure = true
                        )
                    } else {
                        // Null, partial, keyguard, and stale evidence keep this keyed boundary
                        // alive until a reliable observation or the next wake/reconnect.
                        postScheduledRecheck(
                            packageName = packageName,
                            generation = generation,
                            delayMillis = UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS,
                            visibilityAttempt = 0
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            // This is a non-coroutine Handler callback. Cancellation from callback work is an
            // ordinary feature exit here: do not log, enforce, or schedule recovery.
            return
        } catch (error: Throwable) {
            logNonFatal(error)
            try {
                if (visibilityAttempt < MAX_VISIBILITY_RETRIES &&
                    isReadyForChecks() && recheckGeneration.get() == generation
                ) {
                    postScheduledRecheck(
                        packageName = packageName,
                        generation = generation,
                        delayMillis = VISIBILITY_RETRY_DELAY_MS * (visibilityAttempt + 1),
                        visibilityAttempt = visibilityAttempt + 1
                    )
                } else if (isReadyForChecks() && recheckGeneration.get() == generation) {
                    // After the bounded ordinary-failure retries, preserve R5's fail-closed
                    // behavior for this keyed package. Cancellation exits above without logging,
                    // enforcing, or scheduling recovery.
                    dispatchSyntheticCheck(
                        packageName = packageName,
                        generation = generation,
                        failClosedOnEvaluationFailure = true
                    )
                }
            } catch (recoveryCancellation: CancellationException) {
                return
            } catch (recoveryError: Throwable) {
                // Even the bounded recovery path is part of the Handler callback's containment
                // boundary. A provider or logging failure must never escape into Accessibility.
                logNonFatal(recoveryError)
            }
        }
    }

    private fun dispatchSyntheticCheck(
        packageName: String,
        generation: Long,
        failClosedOnEvaluationFailure: Boolean = false
    ) {
        if (!isReadyForChecks() || recheckGeneration.get() != generation) return
        val event = try {
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            return
        }
        try {
            if (!isReadyForChecks() || recheckGeneration.get() != generation) return
            event.packageName = packageName
            doAppRuleCheck(
                event = event,
                updateForegroundEvidence = false,
                failClosedOnEvaluationFailure = failClosedOnEvaluationFailure
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
        packageName: String
    ): ForegroundEvidenceOutcome? {
        val configuredEssentialPackages = readEssentialPackagesForEvaluation()
        val facts = captureForegroundFacts(
            event = null,
            kind = ObservationKind.SYNTHETIC_RECHECK
        )
        return foregroundEvidenceModule.classify(
            facts = facts,
            policy = ForegroundEvidencePolicySnapshot(
                essentialPackages = configuredEssentialPackages +
                    setOf(service.packageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            )
        ).outcomes.firstOrNull { it.packageName == packageName }
    }

    private fun readApplicationWindowSnapshot(): ApplicationWindowSnapshot {
        applicationWindowSnapshotProvider?.let { provider ->
            return try {
                applyApplicationWindowProvenance(provider())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logNonFatal(error)
                applyApplicationWindowProvenance(
                    ApplicationWindowSnapshot(
                        packages = emptySet(),
                        hasApplicationWindow = false,
                        hasUnknownApplicationWindow = true,
                        providerFailed = true
                    )
                )
            }
        }
        val windows = try {
            service.windows
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            return applyApplicationWindowProvenance(
                ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = false,
                    hasUnknownApplicationWindow = true,
                    providerFailed = true
                )
            )
        }
        if (windows.isNullOrEmpty()) {
            return applyApplicationWindowProvenance(
                ApplicationWindowSnapshot(
                    packages = emptySet(),
                    hasApplicationWindow = false,
                    hasUnknownApplicationWindow = true,
                    providerFailed = false
                )
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
        return applyApplicationWindowProvenance(
            ApplicationWindowSnapshot(
                packages = packages,
                hasApplicationWindow = hasApplicationWindow || applicationWindowCount > 0,
                hasUnknownApplicationWindow = !hasApplicationWindow || hasUnknownPackage,
                providerFailed = providerFailed,
                applicationWindowCount = applicationWindowCount,
                unknownSlotCount = unknownSlotCount
            )
        )
    }

    private fun applyApplicationWindowProvenance(
        raw: ApplicationWindowSnapshot
    ): ApplicationWindowSnapshot = applicationWindowProvenanceCache.resolve(
        raw = raw,
        capturedAtElapsedMs = observationElapsedRealtimeMs()
    )

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
        synchronized(runtimeLock) {
            currentForegroundPackage = packageName
            currentForegroundEvidenceAtElapsedMs = observationElapsedRealtimeMs()
            suspendedForegroundPackage = null
            foregroundEvidenceSuspended = false
        }
    }

    private fun clearForegroundEvidence() {
        synchronized(runtimeLock) {
            suspendedForegroundPackage = currentForegroundPackage
            currentForegroundPackage = null
            currentForegroundEvidenceAtElapsedMs = 0L
            foregroundEvidenceSuspended = true
        }
    }

    private fun cancelScheduledRecheck(packageName: String) {
        synchronized(scheduledRechecks) {
            scheduledRechecks.remove(packageName)?.let { removeHandlerCallback(it.runnable) }
        }
    }

    private fun cancelScheduledRechecks() {
        synchronized(scheduledRechecks) {
            scheduledRechecks.values.forEach { removeHandlerCallback(it.runnable) }
            scheduledRechecks.clear()
        }
    }

    private fun isReadyForChecks(): Boolean =
        setupReady && !destroyed && ::service.isInitialized && scope.isActive

    private fun isReadyForChecks(connectionGeneration: Long): Boolean =
        isReadyForChecks() && lifecycleGeneration.get() == connectionGeneration

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
            handler.removeCallbacks(runnable)
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

    private fun safeResetTime(hour: Int, minute: Int): UseDayResetTime = try {
        UseDayResetTime(hour, minute)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        UseDayResetTime()
    }
}

internal class ApplicationWindowProvenanceCache {
    private data class CachedObservation(
        val packages: Set<String>,
        val capturedAtElapsedMs: Long
    )

    private var cached: CachedObservation? = null

    @Synchronized
    fun resolve(
        raw: AppRuleBlocker.ApplicationWindowSnapshot,
        capturedAtElapsedMs: Long
    ): AppRuleBlocker.ApplicationWindowSnapshot {
        val packages = raw.packages.toSet()
        val rawStalePackages = raw.stalePackages.toSet() - packages
        val resolvedNonempty = packages.isNotEmpty() &&
            raw.hasApplicationWindow &&
            !raw.hasUnknownApplicationWindow &&
            !raw.providerFailed
        if (resolvedNonempty) {
            cached = CachedObservation(packages, capturedAtElapsedMs)
            return raw.copy(
                packages = packages,
                stalePackages = emptySet(),
                freshness = ApplicationWindowsFreshness.FRESH,
                capturedAtElapsedMs = capturedAtElapsedMs
            )
        }
        val previous = cached ?: return raw.copy(
            packages = packages,
            stalePackages = rawStalePackages,
            freshness = ApplicationWindowsFreshness.FRESH,
            capturedAtElapsedMs = capturedAtElapsedMs
        )
        val carriedPackages = previous.packages - packages
        return raw.copy(
            packages = packages,
            stalePackages = (rawStalePackages + carriedPackages).toSet(),
            freshness = if (packages.isEmpty()) {
                ApplicationWindowsFreshness.STALE
            } else {
                ApplicationWindowsFreshness.FRESH
            },
            capturedAtElapsedMs = previous.capturedAtElapsedMs
        )
    }

    @Synchronized
    fun clear() {
        cached = null
    }

}
