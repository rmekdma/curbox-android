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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.UseDay
import java.util.concurrent.atomic.AtomicReference

/** Enforces the new atomic app-rule snapshot without changing the legacy blocker. */
class AppRuleBlocker {
    companion object {
        const val INTENT_ACTION_REFRESH_APP_RULES = "neth.iecal.curbox.refresh.app_rules"
    }

    private lateinit var service: BaseBlockingService
    private lateinit var sessionRepository: CurrentUseDaySessionRepository
    private lateinit var enforcement: AppRuleEnforcement
    private val snapshot = AtomicReference(AppRuleSnapshot())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var lastShownAt = 0L

    fun setup(service: BaseBlockingService) {
        this.service = service
        val database = AppDatabase.getInstance(service)
        sessionRepository = RoomCurrentUseDaySessionRepository(database.foregroundSessionDao())
        enforcement = AppRuleEnforcement(sessionRepository)
        try {
            val initial = runBlocking(Dispatchers.IO) { service.dataStoreManager.settings.first().appRuleSnapshot }
            if (initial.isValid) snapshot.set(initial)
        } catch (_: Exception) {
        }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                val candidate = settings.appRuleSnapshot
                // A malformed value can only come from older/corrupted storage. Keep the last
                // valid runtime snapshot rather than exposing a partial reference graph.
                if (candidate.isValid) snapshot.set(candidate)
            }
        }
    }

    fun setupReceivers() {
        val filter = IntentFilter(INTENT_ACTION_REFRESH_APP_RULES)
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun doAppRuleCheck(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == service.packageName ||
            packageName == "com.android.systemui"
        ) return

        val currentSnapshot = snapshot.get()
        if (currentSnapshot.appRules.none { it.isActive }) return
        val now = System.currentTimeMillis()
        val useDayId = UseDay.idAt(now)
        val evaluation = try {
            runBlocking(Dispatchers.IO) {
                enforcement.check(
                    snapshot = currentSnapshot,
                    packageName = packageName,
                    useDayId = useDayId,
                    nowMs = now
                )
            }
        } catch (_: Exception) {
            // A failed read must not terminate the accessibility service. Failing open here is
            // limited to a storage outage; a malformed persisted snapshot fails closed above.
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
            } catch (_: Exception) {
                // The service remains alive when Android rejects an activity start from the
                // service process. The next accessibility event will retry the decision.
            }
        }, 100L)
    }

    fun onDestroy() {
        settingsJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        try {
            service.unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_REFRESH_APP_RULES) return
            // Settings flow is authoritative. This action exists for the same UI to service
            // refresh path as the legacy blocker and simply triggers a harmless re-read.
            settingsJob?.cancel()
            settingsJob = scope.launch {
                runCatching {
                    service.dataStoreManager.settings.first().appRuleSnapshot
                        .takeIf { it.isValid }
                        ?.let(snapshot::set)
                }
            }
        }
    }

    private fun scheduleRecheck(packageName: String, remainingMillis: Long) {
        handler.removeCallbacksAndMessages(null)
        val delay = remainingMillis.coerceIn(1_000L, 20_000L)
        handler.postDelayed({
            val currentPackage = runCatching {
                service.rootInActiveWindow?.packageName?.toString()
            }.getOrNull()
            if (currentPackage != null && currentPackage == packageName) {
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
}
