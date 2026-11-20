package neth.iecal.curbox.plugins

import com.google.gson.Gson
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.plugin.PluginRule
import neth.iecal.plugin.PluginTimeState
import java.security.MessageDigest

data class StoredPluginTime(
    val data: String? = null,
    val linkedRuleId: String? = null,
    val revision: String = ""
)

internal object PluginTimeProjection {
    private val gson = Gson()

    fun from(settings: Settings, pluginId: String, now: Long): PluginTimeState {
        val stored = settings.pluginTimes[pluginId] ?: StoredPluginTime()
        val day = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime).idAt(now)
        val basis = gson.toJson(listOf(settings.appRuleSnapshot, settings.useDayResetTime,
            settings.useDayGenerationStartedAtMs, stored.revision))
        val fingerprint = java.util.Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()))
        val rules = settings.appRuleSnapshot.appRules.map { rule ->
            PluginRule(rule.id, rule.name, rule.isActive && rule.guardianExtraTimeAllowed && rule.rolloverEnabled,
                settings.appRuleRolloverState.pools[rule.id]?.lastSettledUseDayId, rule.unlockDays)
        }
        return PluginTimeState(day, fingerprint, rules, stored.linkedRuleId, stored.data)
    }
}

/** Applies the rollover credit and plugin state in one host settings transaction. */
internal object PluginTimeSettlementWrite {
    fun next(current: Settings, id: String, expected: PluginTimeState, minutes: Long,
        data: String, revision: String, now: Long): Settings {
        val ruleId = expected.linkedRuleId ?: return current
        val state = PluginTimeProjection.from(current, id, now)
        val pool = current.appRuleRolloverState.pools[ruleId] ?: return current
        if (minutes < 0 || state != expected || state.rules.none { it.id == ruleId && it.eligibleForContributions } ||
            pool.lastSettledUseDayId != state.useDayId || state.data == data) return current
        val balance = neth.iecal.curbox.domain.apprules.AppRuleRolloverPolicy.safeAdd(pool.accumulatedMinutes.coerceAtLeast(0), minutes)
        return current.copy(appRuleRolloverState = current.appRuleRolloverState.withPool(pool.copy(accumulatedMinutes = balance)),
            pluginTimes = current.pluginTimes + (id to StoredPluginTime(data, ruleId, revision)))
    }
}
