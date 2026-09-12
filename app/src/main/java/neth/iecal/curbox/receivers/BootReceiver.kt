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
