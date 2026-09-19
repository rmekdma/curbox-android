package neth.iecal.curbox.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.services.ServiceWatchdogJob
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ServiceProtectionManager

import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRuleRolloverCoordinator
import kotlin.coroutines.cancellation.CancellationException

/**
 * After a reboot or an app update the scheduled watchdog job and the live services are gone. This
 * re schedules the watchdog and runs one immediate repair pass so protection comes back without the
 * user opening the app.
 */
import neth.iecal.curbox.blockers.AppRuleBlocker

/**
 * After a reboot or an app update the scheduled watchdog job and the live services are gone. This
 * re schedules the watchdog and runs one immediate repair pass so protection comes back without the
 * user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (BuildConfig.DEBUG && intent?.action == ACTION_APPLY_TEST_APP_RULES) {
                val rulesJson = intent.getStringExtra(EXTRA_APP_RULES_JSON)
                val snapshot = parseTestAppRules(rulesJson)
                if (snapshot != null) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val dataStore = DataStoreManager(context.applicationContext)
                            dataStore.updateAppRuleSnapshot(snapshot)
                            Log.i(TAG, "Applied test app rules: ${snapshot.appRules.size} rules")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to apply test app rules", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                    return
                }
            } else if (BuildConfig.DEBUG && intent?.action == ACTION_CLEAR_TEST_APP_RULE_OVERRIDES) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dataStore = DataStoreManager(context.applicationContext)
                        dataStore.writeAppRuleOverrideState("", AppRuleOverrideState())
                        Log.i(TAG, "Cleared test app rule overrides")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear test app rule overrides", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
                return
            }

            ServiceWatchdogJob.schedule(context)
            ServiceProtectionManager.healNow(context)
            val pendingResult = try {
                goAsync()
            } catch (_: Exception) {
                null
            }
            BootSettlementCatchupRunner.runAsync(context, pendingResult)
        } catch (e: Exception) {
            Log.e(TAG, "boot repair failed", e)
        }
    }

    companion object {
        const val TAG = "BootReceiver"
        const val ACTION_APPLY_TEST_APP_RULES = "neth.iecal.curbox.action.APPLY_TEST_APP_RULES"
        const val ACTION_CLEAR_TEST_APP_RULE_OVERRIDES = "neth.iecal.curbox.action.CLEAR_TEST_APP_RULE_OVERRIDES"
        const val EXTRA_APP_RULES_JSON = "extra_app_rules_json"

        fun parseTestAppRules(json: String?): AppRuleSnapshot? {
            if (json.isNullOrBlank()) return null
            return try {
                val snapshot = Gson().fromJson(json, AppRuleSnapshot::class.java)
                val normalized = snapshot?.normalized()
                if (normalized?.isValid == true) normalized else null
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal object BootSettlementCatchupRunner {
    var catchupAction: (suspend (Context) -> Unit)? = null
    var crashLoggerProvider: ((Context) -> ((Throwable) -> Unit))? = null

    fun runAsync(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        scope.launch {
            val logError: (Throwable) -> Unit = crashLoggerProvider?.invoke(context) ?: { error ->
                try {
                    CrashLogger(context.applicationContext).logNonFatalError(
                        if (error is Exception) error else Exception(error)
                    )
                } catch (_: Throwable) {}
            }
            try {
                if (catchupAction != null) {
                    catchupAction?.invoke(context)
                } else {
                    val appContext = context.applicationContext
                    val dataStoreManager = DataStoreManager(appContext)
                    val database = AppDatabase.getInstance(appContext)
                    val sessionRepository = RoomCurrentUseDaySessionRepository(
                        database.foregroundSessionDao(),
                        database.foregroundLaunchDao(),
                        database.appUsageDao(),
                        database
                    )
                    val coordinator = AppRuleRolloverCoordinator(
                        dataStoreManager = dataStoreManager,
                        sessionRepository = sessionRepository,
                        onNonFatalError = logError
                    )
                    coordinator.reconcileSettlement()
                }
                try {
                    val refreshIntent = Intent(AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES)
                    context.sendBroadcast(refreshIntent)
                } catch (_: Throwable) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logError(e)
                try {
                    Log.e(BootReceiver.TAG, "Failed settlement catch-up on boot", e)
                } catch (_: Throwable) {}
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
