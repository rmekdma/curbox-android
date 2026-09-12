package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.domain.apprules.AppRuleEvaluation
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.AppRuleMembershipResolver
import neth.iecal.curbox.domain.apprules.AppRuleReevaluationGate
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleReceiverLifecycle
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationFormatter
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationModel
import neth.iecal.curbox.domain.apprules.LiveRuleNotificationStateCalculator
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime

/** Enforces the new atomic app-rule snapshot without changing the legacy blocker. */
class AppRuleBlocker {
    companion object {
        const val INTENT_ACTION_REFRESH_APP_RULES = "neth.iecal.curbox.refresh.app_rules"
        private const val MILLIS_PER_MINUTE = 60_000L

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var notificationTickJob: kotlinx.coroutines.Job? = null
    private var lastShownAt = 0L
    @Volatile private var launchablePackages: Set<String> = emptySet()
    @Volatile private var essentialPackages: Set<String> = emptySet()
    private var packageScopeReader: AppRulePackageScopeReader? = null
    private var receiverLifecycle: AppRuleReceiverLifecycle? = null
    private var setupReady = false
    @Volatile private var resetTime = UseDayResetTime()
    @Volatile private var useDayGenerationStartedAtMs = 0L
    @Volatile private var overrideState = neth.iecal.curbox.data.models.AppRuleOverrideState()
    private val reevaluationGate = AppRuleReevaluationGate()
    @Volatile private var lastPostedNotificationModel: LiveRuleNotificationModel? = null
    @Volatile private var currentForegroundPackage: String? = null

    fun setup(service: BaseBlockingService) {
        setupReady = false
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
            resetTime = safeResetTime(initialSettings.useDayResetHour, initialSettings.useDayResetMinute)
            useDayGenerationStartedAtMs = initialSettings.useDayGenerationStartedAtMs
            overrideState = initialSettings.appRuleOverrideState
            val initial = initialSettings.appRuleSnapshot
            snapshot.accept(initial)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            try {
                service.dataStoreManager.settings.collectLatest { settings ->
                    val candidate = settings.appRuleSnapshot
                    resetTime = safeResetTime(settings.useDayResetHour, settings.useDayResetMinute)
                    useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
                    if (overrideState != settings.appRuleOverrideState) {
                        reevaluationGate.markOverrideChanged()
                    }
                    overrideState = settings.appRuleOverrideState
                    // A malformed value can only come from older/corrupted storage. Keep the last
                    // valid runtime snapshot rather than exposing a partial reference graph.
                    snapshot.accept(candidate)
                    updateLiveNotification()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
        setupReady = true
        startNotificationTicker()
        updateLiveNotification()
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
                )
            ),
            isReady = { setupReady }
        )
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = lifecycle
        try {
            lifecycle.register()
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    fun doAppRuleCheck(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return
        currentForegroundPackage = packageName
        updateLiveNotification(packageName)

        val evaluationEssentialPackages = readEssentialPackagesForEvaluation()
        if (packageName in evaluationEssentialPackages) return

        val currentSnapshot = snapshot.snapshot()
        if (currentSnapshot.appRules.none { it.isActive }) return
        val now = System.currentTimeMillis()
        val calculator = ConfigurableUseDayCalculator(resetTime = resetTime)
        val useDayId = calculator.idAt(now)
        val evaluation = try {
            runBlocking(Dispatchers.IO) {
                enforcement.check(
                    snapshot = currentSnapshot,
                    packageName = packageName,
                    useDayId = useDayId,
                    nowMs = now,
                    calculator = calculator,
                    useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                    availablePackages = launchablePackages,
                    essentialExcludedPackages = evaluationEssentialPackages,
                    overrides = overrideState
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

        val nextRemaining = evaluation.evaluations
            .filter { it.isActive && it.remainingMillis > 0L }
            .minOfOrNull { it.remainingMillis }
        val nextSkipBoundary = AppRuleGuardianOverrides.nextSkipBoundaryMs(
            overrideState,
            useDayId,
            now,
            useDayGenerationStartedAtMs
        )
        listOfNotNull(nextRemaining, nextSkipBoundary?.minus(now)).minOrNull()?.let {
            scheduleRecheck(packageName, it)
        }
        if (evaluation.denyingRules.isEmpty()) return
        val bypassThrottle = reevaluationGate.consumeIfApplicable(evaluation.evaluations.isNotEmpty())
        if (!bypassThrottle && now - lastShownAt < 1_000L) return
        lastShownAt = now
        showWarning(packageName, evaluation)
    }

    private fun startNotificationTicker() {
        notificationTickJob?.cancel()
        notificationTickJob = scope.launch {
            while (isActive) {
                delay(MILLIS_PER_MINUTE)
                try {
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
        if (!setupReady || !::service.isInitialized) return
        scope.launch(Dispatchers.IO) {
            try {
                val currentSnapshot = snapshot.snapshot()
                val now = System.currentTimeMillis()
                val calculator = ConfigurableUseDayCalculator(resetTime = resetTime)
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
                    useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                    availablePackages = launchablePackages,
                    essentialExcludedPackages = evaluationEssentialPackages,
                    overrides = overrideState
                )
                val membershipResolver = AppRuleMembershipResolver(currentSnapshot)
                val model = LiveRuleNotificationStateCalculator.buildNotificationModel(
                    items = items,
                    defaultTitle = defaultTitle,
                    defaultText = defaultText,
                    formatter = { item ->
                        LiveRuleNotificationFormatter.formatNotificationItem(service, item)
                    },
                    foregroundPackage = foregroundPackage ?: currentForegroundPackage,
                    rulePackageResolver = { ruleId ->
                        val rule = currentSnapshot.appRules.find { it.id == ruleId }
                        if (rule != null) {
                            membershipResolver.targetPackagesAt(
                                rule = rule,
                                atMs = now,
                                launchablePackages = launchablePackages,
                                essentialExcludedPackages = evaluationEssentialPackages
                            )
                        } else {
                            emptySet()
                        }
                    }
                )

                if (model != lastPostedNotificationModel) {
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

    private fun showWarning(packageName: String, evaluation: neth.iecal.curbox.domain.apprules.AppRulesEvaluation) {
        if (!service.isDelayOver(1_000)) return
        try {
            val denialRows = evaluation.denyingRules.map { denial ->
                val rule = snapshot.snapshot().appRules.find { it.id == denial.ruleId }
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
        settingsJob?.cancel()
        notificationTickJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        receiverLifecycle?.unregister()?.forEach(::logNonFatal)
        receiverLifecycle = null
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_REFRESH_APP_RULES) return
            // Settings flow is authoritative. This action exists for the same UI to service
            // refresh path as the legacy blocker and simply triggers a harmless re-read.
            scope.launch {
                try {
                    refreshPackageScope()
                    val settings = service.dataStoreManager.settings.first()
                    resetTime = safeResetTime(settings.useDayResetHour, settings.useDayResetMinute)
                    useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
                    if (overrideState != settings.appRuleOverrideState) {
                        reevaluationGate.markOverrideChanged()
                    }
                    overrideState = settings.appRuleOverrideState
                    settings.appRuleSnapshot.takeIf { it.isValid }?.let(snapshot::accept)
                    handler.post { checkCurrentlyVisibleApplications() }
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
        if (!::service.isInitialized) return
        try {
            val visiblePackages = (service.windows ?: emptyList())
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { packageNameForWindow(it) }
                .filter { it.isNotBlank() }
                .distinct()
            for (pkg in visiblePackages) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                event.packageName = pkg
                try {
                    doAppRuleCheck(event)
                } finally {
                    event.recycle()
                }
            }
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A new launchable app is part of an all-apps scope without requiring a rule edit.
            refreshPackageScope()
            updateLiveNotification()
        }
    }

    private fun refreshPackageScope() {
        if (!::service.isInitialized) return
        val reader = packageScopeReader ?: return
        try {
            launchablePackages = reader.readLaunchablePackages()
            essentialPackages = reader.readEssentialPackages()
        } catch (error: Exception) {
            essentialPackages = setOf(service.packageName, Constants.SYSTEM_UI_PACKAGE_NAME)
            logNonFatal(error)
        }
    }

    private fun readEssentialPackagesForEvaluation(): Set<String> {
        val reader = packageScopeReader ?: return essentialPackages
        return try {
            reader.readEssentialPackages().also { essentialPackages = it }
        } catch (error: Exception) {
            logNonFatal(error)
            essentialPackages
        }
    }

    private fun scheduleRecheck(packageName: String, remainingMillis: Long) {
        handler.removeCallbacksAndMessages(null)
        val delay = remainingMillis.coerceIn(1_000L, 20_000L)
        handler.postDelayed({
            val packageStillVisible = try {
                service.windows.any { window ->
                    window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                        packageNameForWindow(window) == packageName
                }
            } catch (error: Exception) {
                logNonFatal(error)
                false
            }
            if (packageStillVisible) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                event.packageName = packageName
                try {
                    doAppRuleCheck(event)
                } finally {
                    event.recycle()
                }
            }
        }, delay)
    }

    private fun packageNameForWindow(
        window: android.view.accessibility.AccessibilityWindowInfo
    ): String {
        val root = window.root ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } finally {
            root.recycle()
        }
    }

    private fun logNonFatal(error: Exception) {
        if (::crashLogger.isInitialized) crashLogger.logNonFatalError(error)
    }

    private fun safeResetTime(hour: Int, minute: Int): UseDayResetTime =
        runCatching { UseDayResetTime(hour, minute) }.getOrDefault(UseDayResetTime())
}
