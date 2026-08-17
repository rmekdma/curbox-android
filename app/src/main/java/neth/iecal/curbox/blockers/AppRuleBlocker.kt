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
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
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
    @Volatile private var resetTime = UseDayResetTime()
    @Volatile private var useDayGenerationStartedAtMs = 0L

    fun setup(service: BaseBlockingService) {
        this.service = service
        crashLogger = CrashLogger(service)
        val database = AppDatabase.getInstance(service)
        sessionRepository = RoomCurrentUseDaySessionRepository(database.foregroundSessionDao())
        enforcement = AppRuleEnforcement(sessionRepository)
        packageScopeReader = AppRulePackageScopeReader.fromContext(service)
        refreshPackageScope()
        try {
            val initialSettings = runBlocking(Dispatchers.IO) { service.dataStoreManager.settings.first() }
            resetTime = safeResetTime(initialSettings.useDayResetHour, initialSettings.useDayResetMinute)
            useDayGenerationStartedAtMs = initialSettings.useDayGenerationStartedAtMs
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
    }

    fun setupReceivers() {
        val filter = IntentFilter(INTENT_ACTION_REFRESH_APP_RULES)
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val lifecycle = AppRuleReceiverLifecycle(
            listOf(
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
            )
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
                    essentialExcludedPackages = evaluationEssentialPackages
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
        if (nextRemaining != null) scheduleRecheck(packageName, nextRemaining)
        val denyingRule = evaluation.denyingRules.firstOrNull() ?: return
        if (now - lastShownAt < 1_000L) return
        lastShownAt = now
        showWarning(packageName, denyingRule.ruleId)
    }

    private fun showWarning(packageName: String, ruleId: String) {
        if (!service.isDelayOver(1_000)) return
        service.pressHome()
        handler.postDelayed({
            val intent = Intent(service, WarningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                putExtra("result_id", ruleId)
                putExtra("launch_package", packageName)
                putExtra("warning_config", Gson().toJson(AppBlockerWarningScreenConfig()))
            }
            try {
                service.startActivity(intent)
            } catch (error: Exception) {
                // The service remains alive when Android rejects an activity start from the
                // service process. The next accessibility event will retry the decision.
                logNonFatal(error)
            }
        }, 100L)
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
                    service.dataStoreManager.settings.first().appRuleSnapshot
                        .takeIf { it.isValid }
                        ?.let(snapshot::accept)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logNonFatal(error)
                }
            }
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
