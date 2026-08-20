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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
import neth.iecal.curbox.domain.apprules.AppRuleReevaluationGate
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.AppRuleReceiverLifecycle
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime

/** Enforces the new atomic app-rule snapshot without changing the legacy blocker. */
class AppRuleBlocker {
    companion object {
        const val INTENT_ACTION_REFRESH_APP_RULES = "neth.iecal.curbox.refresh.app_rules"
        private const val MILLIS_PER_MINUTE = 60_000L
    }

    private lateinit var service: BaseBlockingService
    private lateinit var crashLogger: CrashLogger
    private lateinit var sessionRepository: CurrentUseDaySessionRepository
    private lateinit var enforcement: AppRuleEnforcement
    private val snapshot = AppRuleSnapshotCoordinator()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var settingsJob: kotlinx.coroutines.Job? = null
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
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
        setupReady = true
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

    private fun showWarning(packageName: String, evaluation: neth.iecal.curbox.domain.apprules.AppRulesEvaluation) {
        if (!service.isDelayOver(1_000)) return
        service.pressHome()
        handler.postDelayed({
            try {
                val denialRows = evaluation.denyingRules.map { denial ->
                    val rule = snapshot.snapshot().appRules.find { it.id == denial.ruleId }
                    AppRuleGuardianDenial(
                        ruleId = denial.ruleId,
                        ruleName = rule?.name ?: denial.ruleId,
                        reason = warningStatus(denial)
                    )
                }
                val intent = Intent(service, WarningActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                    putExtra("launch_package", packageName)
                    putExtra("app_rule_guardian", true)
                    putExtra("app_rule_denials_json", Gson().toJson(denialRows))
                }
                service.startActivity(intent)
            } catch (error: Exception) {
                // Keep the delayed callback contained too: formatting the breakdown and starting
                // the activity are both optional presentation work for the service process.
                logNonFatal(error)
            }
        }, 100L)
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
            val visiblePackages = service.windows
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
