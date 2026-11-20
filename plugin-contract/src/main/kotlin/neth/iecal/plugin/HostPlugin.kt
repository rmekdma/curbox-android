package neth.iecal.plugin

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow

/** Factory discovered from the modules packaged with this app. IDs own independent state. */
interface PluginFactory {
    val id: String
    val storeNames: Set<String> get() = emptySet()
    val legacySettingKeys: Set<String> get() = emptySet()
    val legacyGrantSourceKey: String? get() = null
    fun migrateLegacySettings(fields: Map<String, String>): PluginLegacyTimeState? = null
    /** Resets plugin policy for a linked rule after the host revokes the rule's extra time. */
    fun onLinkedTimeRevoked(data: String?, currentUseDayId: String): String? = data
    fun create(context: Context, host: PluginHost): HostPlugin
}

data class PluginLegacyTimeState(val data: String, val linkedRuleId: String?)

interface HostPlugin {
    val destination: PluginDestination? get() = null
    val timeProvider: AdditionalTimeProvider? get() = null
    val ruleSettings: PluginRuleSettings? get() = null
    suspend fun run(host: CurboxHost) = Unit
}

data class PluginDestination(val label: String, val route: String, val fragment: () -> Fragment)

interface PluginRuleSettings {
    val label: String
    suspend fun summary(): String?
    fun open(activity: FragmentActivity, onChanged: () -> Unit)
}

interface PluginHost {
    val time: PluginTimeHost
    fun store(name: String): PluginStringStore
    suspend fun serviceObservation(): HostObservation
    fun logFailure(error: Throwable)
}

interface PluginStringStore {
    val data: Flow<String>
    suspend fun updateData(transform: suspend (String) -> String): String
}

/** Provides the plugin its scoped API from an Android component created by the system. */
object PluginHostAccess {
    @Volatile
    var resolve: ((Context, Class<out PluginFactory>) -> PluginHost)? = null

    fun require(context: Context, factory: Class<out PluginFactory>): PluginHost =
        checkNotNull(resolve)(context.applicationContext, factory)
}
