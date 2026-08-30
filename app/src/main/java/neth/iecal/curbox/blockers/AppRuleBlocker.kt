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
import neth.iecal.curbox.domain.apprules.AppRuleMembershipResolver
import neth.iecal.curbox.domain.apprules.AppRuleReevaluationGate
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleReceiverLifecycle
import neth.iecal.curbox.domain.apprules.AppRuleRecheckPlanner
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
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
        private const val VISIBILITY_RETRY_DELAY_MS = 250L
        private const val UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS = 20_000L
        private const val FOREGROUND_EVIDENCE_MAX_AGE_MS = 5_000L

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
    private val reevaluationGate = AppRuleReevaluationGate()
    @Volatile private var lastPostedNotificationModel: LiveRuleNotificationModel? = null
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var currentForegroundEvidenceAtElapsedMs = 0L
    @Volatile private var suspendedForegroundPackage: String? = null
    @Volatile private var foregroundEvidenceSuspended = false
    @Volatile private var screenOnAwaitingUserPresent = false
    private val recheckGeneration = AtomicLong(0L)
    private val lifecycleGeneration = AtomicLong(0L)
    private val scheduledRechecks = mutableMapOf<String, ScheduledRecheck>()

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

    private enum class PackageVisibility {
        VISIBLE,
        NOT_VISIBLE,
        UNKNOWN
    }

    /** Evidence snapshot kept internal so Android tests can exercise OEM failure combinations. */
    internal data class ApplicationWindowSnapshot(
        val packages: Set<String>,
        val hasApplicationWindow: Boolean,
        val hasUnknownApplicationWindow: Boolean,
        val providerFailed: Boolean = false,
        /** Number of application windows, including windows whose root package is unavailable. */
        val applicationWindowCount: Int = packages.size
    )

    internal data class ActiveWindowSnapshot(
        val packageName: String?,
        val readFailed: Boolean = false
    )

    /** Test seams model framework snapshots that cannot be constructed with public setters. */
    internal var applicationWindowSnapshotProvider: (() -> ApplicationWindowSnapshot)? = null
    internal var activeWindowSnapshotProvider: (() -> ActiveWindowSnapshot)? = null

    fun setup(service: BaseBlockingService) {
        val connectionGeneration = lifecycleGeneration.incrementAndGet()
        synchronized(runtimeLock) {
            destroyed = false
            setupReady = false
            // A reconnect must invalidate work captured by the previous service connection.
            recheckGeneration.incrementAndGet()
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
        lastShownAt = 0L
        this.service = service
        crashLogger = CrashLogger(service)
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
                    if (changed) postVisibleApplicationCheck(connectionGeneration = connectionGeneration)
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
            addAction(Intent.ACTION_USER_PRESENT)
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
                )
            ),
            isReady = { setupReady }
        )
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = lifecycle
        try {
            lifecycle.register()
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
        updateForegroundEvidence: Boolean
    ) {
        if (!isReadyForChecks()) return
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return

        val evaluationEssentialPackages = readEssentialPackagesForEvaluation()
        if (isEssentialPackage(packageName, evaluationEssentialPackages)) {
            clearForegroundEvidence()
            return
        }
        // Accessibility can deliver the last app event while the display is still locked after
        // SCREEN_ON. Wait for USER_PRESENT before showing a guardian underneath the keyguard.
        if (!isScreenReadyForChecks()) return

        val packageChanged = packageName != currentForegroundPackage
        if (updateForegroundEvidence) recordForegroundEvidence(packageName)
        if (packageChanged) {
            updateLiveNotification(packageName.takeIf { updateForegroundEvidence })
        }

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
        val now = System.currentTimeMillis()
        val calculator = ConfigurableUseDayCalculator(resetTime = runtime.resetTime)
        val useDayId = calculator.idAt(now)
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
            // A failed read must not terminate the accessibility service. Failing open here is
            // limited to a storage outage; a malformed persisted snapshot fails closed above.
            logNonFatal(error)
            return
        }

        if (!isReadyForChecks() || recheckGeneration.get() != generation) return

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
        if (evaluation.denyingRules.isEmpty()) return
        val bypassThrottle = reevaluationGate.consumeIfApplicable(evaluation.evaluations.isNotEmpty())
        if (!bypassThrottle && now - lastShownAt < 1_000L) return
        lastShownAt = now
        showWarning(packageName, evaluation, currentSnapshot, generation)
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
                    if (captureRuleRuntime().snapshot.appRules.any { it.isActive } &&
                        isScreenReadyForChecks()
                    ) {
                        postVisibleApplicationCheck(connectionGeneration = connectionGeneration)
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
        liveNotificationJob?.cancel()
        liveNotificationJob = scope.launch(Dispatchers.IO) {
            try {
                if (!isReadyForChecks() || recheckGeneration.get() != generation) return@launch
                val currentSnapshot = runtime.snapshot
                val now = System.currentTimeMillis()
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

                if (isReadyForChecks() && recheckGeneration.get() == generation &&
                    model != lastPostedNotificationModel
                ) {
                    lastPostedNotificationModel = model
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
                service.startActivity(
                    createGuardianApprovalIntent(service, packageName, denialRows)
                )
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
            currentForegroundEvidenceAtElapsedMs = 0L
            suspendedForegroundPackage = null
            foregroundEvidenceSuspended = true
            screenOnAwaitingUserPresent = false
        }
        settingsJob?.cancel()
        notificationTickJob?.cancel()
        liveNotificationJob?.cancel()
        cancelScheduledRechecks()
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
                    val changed = refreshMutex.withLock {
                        if (!isReadyForChecks(connectionGeneration)) return@withLock false
                        val packageScopeChanged = refreshPackageScope()
                        val settings = service.dataStoreManager.settings.first()
                        if (!isReadyForChecks(connectionGeneration)) return@withLock false
                        packageScopeChanged || applySettingsSnapshot(settings)
                    }
                    if (!isReadyForChecks(connectionGeneration)) return@launch
                    // A refresh is also useful when the package reader returned the same set:
                    // the window/root provider may have recovered since the last event.
                    if (changed || isScreenReadyForChecks()) {
                        postVisibleApplicationCheck(connectionGeneration = connectionGeneration)
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

    private fun checkCurrentlyVisibleApplications() {
        if (!isReadyForChecks() || !isScreenReadyForChecks()) return
        try {
            val configuredEssentialPackages = readEssentialPackagesForEvaluation()
            val windows = readApplicationWindowSnapshot()
            val activePackage = readActiveWindowPackage()
            val currentPackage = currentForegroundPackage
            val evidenceSuspended = foregroundEvidenceSuspended
            val activePackageIsEssential = activePackage?.let {
                isEssentialPackage(it, configuredEssentialPackages)
            } == true
            // After the guardian/SystemUI has been foregrounded, the old event package is no
            // longer safe fallback evidence. If that overlay is still active, do not evaluate
            // stale application-window entries and accidentally launch a second guardian.
            if (evidenceSuspended && activePackageIsEssential) return
            val visiblePackages = linkedSetOf<String>()
            val activeRootIsConsistent = !evidenceSuspended && activePackage != null &&
                isReliableApplicationEvidence(
                    activePackage,
                    windows,
                    configuredEssentialPackages
                ) &&
                (activePackage in windows.packages ||
                    !windows.hasApplicationWindow) &&
                (currentPackage == null ||
                    currentPackage == activePackage ||
                    currentPackage in windows.packages)
            if (activeRootIsConsistent && activePackage != null) {
                visiblePackages += activePackage
                // Keep every package that was identified successfully. A split-screen provider
                // can return one valid root and one null root; dropping the valid package would
                // lose its keyed boundary even though it is still visible.
                if (activePackage in windows.packages || !windows.hasApplicationWindow) {
                    visiblePackages += windows.packages
                }
            } else if (evidenceSuspended) {
                // Once a nonessential active root is available again, it is safe to resume the
                // current package evidence. Require it to agree with a known application window
                // (or with an otherwise empty window snapshot) so a stale root cannot resurrect
                // the package that was visible before the overlay.
                val activeRootCanResume = activePackage != null &&
                    !activePackageIsEssential &&
                    isReliableApplicationEvidence(
                        activePackage,
                        windows,
                        configuredEssentialPackages
                    ) &&
                    (activePackage in windows.packages || !windows.hasApplicationWindow)
                if (activeRootCanResume) {
                    val packageToSuppress = suspendedForegroundPackage
                    synchronized(runtimeLock) {
                        if (foregroundEvidenceSuspended) {
                            currentForegroundPackage = activePackage
                            currentForegroundEvidenceAtElapsedMs = SystemClock.elapsedRealtime()
                            suspendedForegroundPackage = null
                            foregroundEvidenceSuspended = false
                        }
                    }
                    activePackage?.let(visiblePackages::add)
                    // Do not immediately re-evaluate the package that was underneath the
                    // essential overlay. Its window entry can be stale even when another known
                    // package is now visible; a real event or reliable root must re-establish it.
                    visiblePackages += windows.packages - setOfNotNull(packageToSuppress)
                }
            } else {
                val recentCurrentPackage = currentPackage
                    ?.takeIf { it.isNotBlank() && hasRecentForegroundEvidence(it) }
                val activeRootIsUsable = activePackage != null &&
                    !activePackageIsEssential &&
                    isReliableApplicationEvidence(
                        activePackage,
                        windows,
                        configuredEssentialPackages
                    ) &&
                    (activePackage in windows.packages || !windows.hasApplicationWindow)
                val hasMultipleApplicationWindows =
                    windows.applicationWindowCount > 1 || windows.packages.size > 1

                if (activeRootIsUsable && activePackage != null) {
                    // A reliable nonessential root is stronger than a stale previous event. It
                    // must be checked even when the old event package is absent from the window
                    // list, otherwise a switch can leave the new app unenforced.
                    visiblePackages += activePackage
                    if (recentCurrentPackage != null &&
                        recentCurrentPackage != activePackage &&
                        (recentCurrentPackage in windows.packages ||
                            windows.hasUnknownApplicationWindow)
                    ) {
                        // A partial split-screen snapshot can expose B while A's root is null.
                        // Keep the recent A event as a second candidate only when the provider
                        // explicitly reports an unknown application window.
                        visiblePackages += recentCurrentPackage
                    }
                    if (recentCurrentPackage == activePackage ||
                        recentCurrentPackage == null ||
                        hasMultipleApplicationWindows
                    ) {
                        visiblePackages += windows.packages
                    }
                } else {
                    recentCurrentPackage?.let(visiblePackages::add)
                    if (recentCurrentPackage == null ||
                        !windows.hasApplicationWindow ||
                        recentCurrentPackage in windows.packages ||
                        hasMultipleApplicationWindows
                    ) {
                        // On reconnect there is no event-derived foreground package yet. Known
                        // application roots are still safe to evaluate independently of a null
                        // active root, including partial split-screen snapshots. If there is a
                        // recent event and more than one application window, retain every known
                        // package so each split-screen boundary gets its own keyed recheck.
                        visiblePackages += windows.packages
                    }
                }
            }
            for (pkg in visiblePackages) {
                dispatchSyntheticCheck(pkg)
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
                Intent.ACTION_SCREEN_ON -> {
                    // SCREEN_ON is delivered while the keyguard can still be showing. Defer
                    // enforcement until USER_PRESENT so the guardian cannot appear underneath the
                    // lock screen or be launched twice during unlock.
                    screenOnAwaitingUserPresent = isDeviceKeyguardLocked()
                    if (!screenOnAwaitingUserPresent) {
                        postVisibleApplicationCheck(
                            delayMillis = 300L,
                            connectionGeneration = connectionGeneration
                        )
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOnAwaitingUserPresent = false
                    postVisibleApplicationCheck(
                        delayMillis = 300L,
                        connectionGeneration = connectionGeneration
                    )
                }
            }
        }
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
                    postVisibleApplicationCheck(connectionGeneration = connectionGeneration)
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

    /** Posts a guarded main-thread reconciliation used by refresh, reconnect, and wake paths. */
    private fun postVisibleApplicationCheck(
        delayMillis: Long = 0L,
        connectionGeneration: Long = lifecycleGeneration.get()
    ) {
        if (!isReadyForChecks(connectionGeneration)) return
        try {
            handler.postDelayed({
                try {
                    if (isReadyForChecks(connectionGeneration) && isScreenReadyForChecks()) {
                        checkCurrentlyVisibleApplications()
                    }
                } catch (error: Throwable) {
                    // Handler callbacks have no coroutine parent to contain a feature failure.
                    logNonFatal(error)
                }
            }, delayMillis.coerceAtLeast(0L))
        } catch (error: Throwable) {
            logNonFatal(error)
        }
    }

    private fun isScreenInteractive(): Boolean = try {
        (service.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logNonFatal(error)
        true
    }

    private fun isDeviceKeyguardLocked(): Boolean = try {
        (service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true
    } catch (error: Throwable) {
        logNonFatal(error)
        false
    }

    private fun isScreenReadyForChecks(): Boolean =
        isScreenInteractive() && !screenOnAwaitingUserPresent && !isDeviceKeyguardLocked()

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
        visibilityAttempt: Int
    ) {
        if (!isReadyForChecks() || recheckGeneration.get() != generation) return
        lateinit var runnable: Runnable
        runnable = Runnable {
            runScheduledRecheck(packageName, generation, visibilityAttempt, runnable)
        }
        val previous = synchronized(scheduledRechecks) {
            val old = scheduledRechecks.remove(packageName)
            scheduledRechecks[packageName] = ScheduledRecheck(generation, runnable)
            old
        }
        previous?.let { removeHandlerCallback(it.runnable) }
        try {
            if (!handler.postDelayed(runnable, delayMillis.coerceAtLeast(1L))) {
                synchronized(scheduledRechecks) {
                    if (scheduledRechecks[packageName]?.runnable === runnable) {
                        scheduledRechecks.remove(packageName)
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(scheduledRechecks) {
                if (scheduledRechecks[packageName]?.runnable === runnable) {
                    scheduledRechecks.remove(packageName)
                }
            }
            logNonFatal(error)
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
            if (!isScreenReadyForChecks()) return

            when (packageVisibility(packageName)) {
                PackageVisibility.VISIBLE -> dispatchSyntheticCheck(packageName, generation)
                PackageVisibility.NOT_VISIBLE -> Unit
                PackageVisibility.UNKNOWN -> {
                    handleUnresolvedVisibility(packageName, generation, visibilityAttempt)
                }
            }
        } catch (error: Throwable) {
            logNonFatal(error)
            // This is a Handler callback, not a coroutine worker. Swallow cancellation and other
            // feature exceptions here so the service's main looper remains alive.
            if (error is CancellationException) {
                if (isReadyForChecks() && recheckGeneration.get() == generation) {
                    postScheduledRecheck(
                        packageName = packageName,
                        generation = generation,
                        delayMillis = UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS,
                        visibilityAttempt = 0
                    )
                }
                return
            }
            try {
                handleUnresolvedVisibility(packageName, generation, visibilityAttempt)
            } catch (recoveryError: Throwable) {
                // Even the bounded recovery path is part of the Handler callback's containment
                // boundary. A provider or logging failure must never escape into Accessibility.
                logNonFatal(recoveryError)
            }
        }
    }

    private fun handleUnresolvedVisibility(
        packageName: String,
        generation: Long,
        visibilityAttempt: Int
    ) {
        if (!isReadyForChecks() || recheckGeneration.get() != generation) return
        if (visibilityAttempt < MAX_VISIBILITY_RETRIES) {
            postScheduledRecheck(
                packageName = packageName,
                generation = generation,
                delayMillis = VISIBILITY_RETRY_DELAY_MS * (visibilityAttempt + 1),
                visibilityAttempt = visibilityAttempt + 1
            )
        } else if (canUseForegroundFallback(packageName)) {
            // Window providers on some Android 16/OEM builds can remain empty while
            // the active app is still unchanged. The recent foreground event is a
            // bounded, package-checked fallback rather than an unbounded poll.
            dispatchSyntheticCheck(packageName, generation)
        } else {
            // Do not drop the boundary merely because an OEM returned a stale,
            // non-empty window list. Keep a low-frequency recovery until a real
            // foreground event or a reliable window snapshot resolves visibility.
            postScheduledRecheck(
                packageName = packageName,
                generation = generation,
                delayMillis = UNKNOWN_VISIBILITY_RECOVERY_DELAY_MS,
                visibilityAttempt = 0
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun dispatchSyntheticCheck(
        packageName: String,
        generation: Long? = null
    ) {
        if (!isReadyForChecks()) return
        if (generation != null && recheckGeneration.get() != generation) return
        val event = try {
            obtainWindowStateEvent()
        } catch (error: Throwable) {
            logNonFatal(error)
            return
        }
        try {
            if (!isReadyForChecks()) return
            if (generation != null && recheckGeneration.get() != generation) return
            event.packageName = packageName
            doAppRuleCheck(event, updateForegroundEvidence = false)
        } catch (error: Throwable) {
            logNonFatal(error)
        } finally {
            try {
                event.recycle()
            } catch (error: Throwable) {
                logNonFatal(error)
            }
        }
    }

    private fun packageVisibility(packageName: String): PackageVisibility {
        if (foregroundEvidenceSuspended) return PackageVisibility.NOT_VISIBLE
        val configuredEssentialPackages = readEssentialPackagesForEvaluation()
        val windows = readApplicationWindowSnapshot()
        val activePackage = readActiveWindowPackage()
        val activeIsReliable = activePackage != null &&
            isReliableApplicationEvidence(activePackage, windows, configuredEssentialPackages)
        val targetInWindows = packageName in windows.packages
        val lastForeground = currentForegroundPackage
        val foregroundSupportsActive = lastForeground == null ||
            lastForeground == activePackage ||
            lastForeground == packageName ||
            lastForeground in windows.packages
        val activeSwitchIsConfirmed = lastForeground != null &&
            lastForeground != packageName &&
            lastForeground == activePackage &&
            !windows.hasUnknownApplicationWindow

        // Only a reliable root that agrees with the latest real foreground event can prove an app
        // switch. Null, IME/SystemUI, stale, or uncorrelated roots remain UNKNOWN: the window
        // provider is known to publish stale/partial snapshots on some Android 16 devices.
        return when {
            activeIsReliable && activePackage == packageName &&
                (activePackage in windows.packages ||
                    !windows.hasApplicationWindow) &&
                foregroundSupportsActive ->
                PackageVisibility.VISIBLE
            activeIsReliable && targetInWindows &&
                (activePackage?.let { it in windows.packages } == true ||
                    !windows.hasApplicationWindow) &&
                foregroundSupportsActive ->
                PackageVisibility.VISIBLE // split-screen
            activeIsReliable && activeSwitchIsConfirmed -> PackageVisibility.NOT_VISIBLE
            else -> PackageVisibility.UNKNOWN
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
                    providerFailed = true
                    false
                }
                if (!isApplicationWindow) continue
                hasApplicationWindow = true
                applicationWindowCount++
                val packageName = packageNameForWindow(window)
                if (packageName.isNullOrBlank()) {
                    hasUnknownPackage = true
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
        }
        return ApplicationWindowSnapshot(
            packages = packages,
            hasApplicationWindow = hasApplicationWindow || applicationWindowCount > 0,
            hasUnknownApplicationWindow = !hasApplicationWindow || hasUnknownPackage,
            providerFailed = providerFailed,
            applicationWindowCount = applicationWindowCount
        )
    }

    private fun isReliableApplicationEvidence(
        packageName: String,
        windows: ApplicationWindowSnapshot,
        configuredEssentialPackages: Set<String> = essentialPackages
    ): Boolean {
        if (packageName.isBlank() || isEssentialPackage(packageName, configuredEssentialPackages)) return false
        // A root that agrees with a window, the last real accessibility event, or the current
        // launcher set is credible. With no application window at all the root itself is the only
        // evidence available, so accept it unless it is an essential package.
        return packageName in windows.packages ||
            packageName == currentForegroundPackage ||
            packageName in launchablePackages ||
            (!windows.hasApplicationWindow && !windows.providerFailed)
    }

    private fun isEssentialPackage(packageName: String, configured: Set<String>): Boolean =
        packageName == service.packageName ||
            packageName == Constants.SYSTEM_UI_PACKAGE_NAME ||
            packageName in configured

    private fun readActiveWindowPackage(): String? = readActiveWindowSnapshot().packageName

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

    private fun isCurrentForegroundPackage(packageName: String): Boolean =
        currentForegroundPackage == packageName && isReadyForChecks()

    private fun recordForegroundEvidence(packageName: String) {
        synchronized(runtimeLock) {
            currentForegroundPackage = packageName
            currentForegroundEvidenceAtElapsedMs = SystemClock.elapsedRealtime()
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

    private fun hasRecentForegroundEvidence(packageName: String): Boolean {
        if (!isCurrentForegroundPackage(packageName)) return false
        val ageMs = SystemClock.elapsedRealtime() - currentForegroundEvidenceAtElapsedMs
        return ageMs in 0..FOREGROUND_EVIDENCE_MAX_AGE_MS
    }

    private fun canUseForegroundFallback(packageName: String): Boolean {
        if (!hasRecentForegroundEvidence(packageName)) return false
        val configuredEssentialPackages = try {
            readEssentialPackagesForEvaluation()
        } catch (error: Throwable) {
            logNonFatal(error)
            return false
        }
        val activeWindow = try {
            readActiveWindowSnapshot()
        } catch (error: Throwable) {
            logNonFatal(error)
            return false
        }
        val windows = readApplicationWindowSnapshot()
        if (windows.providerFailed) {
            // A failed window provider cannot contradict a very recent foreground event unless
            // the active root independently identifies another nonessential app.
            return activeWindow.readFailed || activeWindow.packageName == null ||
                isEssentialPackage(activeWindow.packageName, configuredEssentialPackages)
        }
        if (packageName in windows.packages) return true
        if (activeWindow.packageName != null &&
            !isEssentialPackage(activeWindow.packageName, configuredEssentialPackages)
        ) {
            // A different nonessential active root is stronger switch evidence than the recent
            // event, including when the window list is stale or empty.
            return false
        }
        if (activeWindow.packageName != null &&
            isEssentialPackage(activeWindow.packageName, configuredEssentialPackages)
        ) {
            // An IME, launcher, SystemUI, or the guardian may temporarily own the active root.
            // Only a window that names the target directly is enough evidence while that overlay
            // is on top; stale or empty application windows must not resurrect the old package.
            return packageName in windows.packages
        }
        if (windows.hasApplicationWindow) {
            // Some Android 16/OEM providers return a stale list that omits the current app while
            // rootInActiveWindow is null or transiently belongs to IME/SystemUI. Honor the recent
            // real event for this short bounded interval, then fall back to periodic recovery.
            return true
        }
        if (activeWindow.readFailed) {
            // No contradictory root is available. The bounded event evidence is still safer than
            // silently dropping an allowance boundary forever.
            return true
        }
        // With no application window, a null root or a known transient overlay leaves the recent
        // real foreground event as the best available evidence.
        return activeWindow.packageName == null ||
            isEssentialPackage(activeWindow.packageName, configuredEssentialPackages)
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

    @Suppress("DEPRECATION")
    private fun obtainWindowStateEvent(): AccessibilityEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
}
