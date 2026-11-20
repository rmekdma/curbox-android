package neth.iecal.curbox.plugins

import android.content.Context
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianExtraTimeGrantWrite
import neth.iecal.plugin.AdditionalTimeOffer
import neth.iecal.plugin.CurboxHost
import neth.iecal.plugin.HostPlugin
import neth.iecal.plugin.PluginFactory
import neth.iecal.plugin.PluginHost
import neth.iecal.plugin.PluginHostAccess
import neth.iecal.plugin.PluginStringStore
import neth.iecal.plugin.PluginTimeHost
import neth.iecal.plugin.PluginTimeState
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.ServiceLoader
import java.util.UUID

internal class PluginMigrationException(cause: Throwable) : java.io.IOException("Plugin settings migration failed", cause)

internal object PluginCatalog {
    val factories: List<PluginFactory> by lazy {
        ServiceLoader.load(PluginFactory::class.java, PluginFactory::class.java.classLoader)
            .toList().sortedBy { it.id }.also { list ->
                require(list.map { it.id }.distinct().size == list.size)
                val names = list.flatMap { it.storeNames }
                require(names.distinct().size == names.size)
            }
    }

    fun migrateSettings(root: JsonObject, gson: Gson) {
        val states = root.getAsJsonObjectOrNull("pluginTimes") ?: JsonObject()
        for (factory in factories) {
            val fields = factory.legacySettingKeys.mapNotNull { key ->
                root.get(key)?.let { key to it.toString() }
            }.toMap()
            if (!states.has(factory.id) && fields.isNotEmpty()) {
                val migrated = try {
                    requireNotNull(factory.migrateLegacySettings(fields))
                } catch (error: Exception) {
                    throw PluginMigrationException(error)
                }
                states.add(factory.id, gson.toJsonTree(
                    StoredPluginTime(migrated.data, migrated.linkedRuleId)
                ))
            }
        }
        root.add("pluginTimes", states)
        val grants = root.getAsJsonObjectOrNull("appRuleOverrideState")?.get("grants")
        if (grants?.isJsonArray != true) return
        for (entry in grants.asJsonArray) {
            if (!entry.isJsonObject) continue
            val grant = entry.asJsonObject
            val contributions = grant.getAsJsonObjectOrNull("contributedMillisByPlugin") ?: JsonObject()
            for (factory in factories) {
                val oldKey = factory.legacyGrantSourceKey ?: continue
                val amount = grant.get(oldKey)
                if (!contributions.has(factory.id) && amount?.isJsonPrimitive == true &&
                    amount.asJsonPrimitive.isNumber && amount.asLong > 0
                ) contributions.add(factory.id, amount)
            }
            grant.add("contributedMillisByPlugin", contributions)
        }
    }

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}

internal class PluginTimeOffer(internal val id: String, internal val value: AdditionalTimeOffer) {
    val displayName get() = value.displayName
    val proposedMinutes get() = value.proposedMinutes
    val maxMinutes get() = value.maxMinutes
}

internal class PluginRuntime(context: Context) {
    private val app = context.applicationContext
    private val settings by lazy { DataStoreManager(app) }
    private val plugins: Map<String, HostPlugin> get() = synchronized(instances) {
        PluginCatalog.factories.mapNotNull { factory ->
            safely { factory.id to instances.getOrPut(factory.id) { factory.create(app, ScopedHost(app, factory)) } }
        }.toMap()
    }

    fun destinations() = plugins.values.mapNotNull { plugin -> safely { plugin.destination } }
    fun ruleSettings() = plugins.values.mapNotNull { plugin -> safely { plugin.ruleSettings } }

    suspend fun run(host: CurboxHost) = supervisorScope {
        for (plugin in plugins.values) {
            launch {
                try { plugin.run(host) }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) { log(app, error) }
            }
        }
    }

    suspend fun offer(basis: GuardianExtraTimeGrantBasis): PluginTimeOffer? {
        for ((id, plugin) in plugins) {
            val candidate = try { plugin.timeProvider?.offer(basis.ruleId) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { log(app, error); null }
            if (candidate == null || candidate.displayName.isBlank() || candidate.maxMinutes <= 0 ||
                candidate.proposedMinutes !in 0L..candidate.maxMinutes ||
                candidate.expected.linkedRuleId != basis.ruleId || candidate.expected.useDayId != basis.useDayId
            ) continue
            return PluginTimeOffer(id, candidate)
        }
        return null
    }

    suspend fun isCurrent(offer: PluginTimeOffer, basis: GuardianExtraTimeGrantBasis): Boolean {
        if (offer.value.expected.useDayId != basis.useDayId ||
            offer.value.expected.linkedRuleId != basis.ruleId ||
            settings.readPluginTime(offer.id) != offer.value.expected) return false
        return try { offer.value.isCurrent() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { log(app, error); false }
    }

    suspend fun grant(
        password: String, offer: PluginTimeOffer, basis: GuardianExtraTimeGrantBasis,
        manual: Long, contributed: Long
    ): GuardianExtraTimeGrantWrite.Result {
        if (contributed !in 1L..offer.maxMinutes || manual < 0L) {
            return GuardianExtraTimeGrantWrite.Result.Rejected
        }
        if (!isCurrent(offer, basis)) return GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation(basis)
        var result: GuardianExtraTimeGrantWrite.Result = GuardianExtraTimeGrantWrite.Result.Unavailable
        var attempted = false
        offer.value.prepared.withCurrentState(contributed) { data ->
            if (attempted) return@withCurrentState false
            attempted = true
            result = settings.grantPluginTime(offer.id, password, basis, offer.value.expected, manual, contributed, data)
            result is GuardianExtraTimeGrantWrite.Result.StoredWithReceipt
        }
        return result
    }

    fun recordRollover(current: Settings, rollover: AppRuleRolloverState): Settings {
        var states = current.pluginTimes
        for ((id, state) in current.pluginTimes) {
            val rule = state.linkedRuleId ?: continue
            val previous = current.appRuleRolloverState.pools[rule]?.lastSettledUseDayId
            val settled = rollover.pools[rule]?.lastSettledUseDayId ?: continue
            if (previous != null && settled <= previous) continue
            val plugin = plugins[id]
            if (plugin == null) {
                check(PluginCatalog.factories.none { it.id == id }) { "Plugin unavailable during rollover" }
                continue
            }
            val data = plugin.timeProvider?.recordRollover(
                PluginTimeProjection.from(current, id, System.currentTimeMillis()), previous, settled
            ) ?: continue
            states = states + (id to state.copy(data = data, revision = UUID.randomUUID().toString()))
        }
        return current.copy(appRuleRolloverState = rollover, pluginTimes = states)
    }

    suspend fun settleEnded(): Boolean {
        var pending = false
        for (plugin in plugins.values) {
            try {
                if (plugin.timeProvider?.settleEnded() == true) pending = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log(app, error)
                pending = true
            }
        }
        return pending
    }

    private fun <T> safely(block: () -> T): T? = try { block() }
    catch (error: Exception) { log(app, error); null }

    companion object {
        private val instances = mutableMapOf<String, HostPlugin>()

        fun installHostAccess() {
            PluginHostAccess.resolve = { context, factory ->
                ScopedHost(context, PluginCatalog.factories.single { it.javaClass == factory })
            }
        }

        private fun log(context: Context, error: Throwable) {
            CrashLogger(context).logNonFatalError(Exception(error))
        }
    }

    private class ScopedHost(private val app: Context, private val factory: PluginFactory) : PluginHost {
        private val settings by lazy { DataStoreManager(app) }
        override val time = object : PluginTimeHost {
            override suspend fun read() = settings.readPluginTime(factory.id)
            override suspend fun update(expected: PluginTimeState, data: String?, linkedRuleId: String?) =
                settings.updatePluginTime(factory.id, expected, data, linkedRuleId)
            override suspend fun settle(expected: PluginTimeState, endedUseDayId: String, minutes: Long, data: String) =
                settings.settlePluginTime(factory.id, expected, endedUseDayId, minutes, data)
        }
        override fun store(name: String): PluginStringStore {
            require(name in factory.storeNames)
            return PluginStores.open(app, name)
        }
        override suspend fun serviceObservation() = PluginServiceStatus.read(app)
        override fun logFailure(error: Throwable) = log(app, error)
    }
}

private object PluginStores {
    private val stores = mutableMapOf<String, PluginStringStore>()
    private val serializer = object : Serializer<String> {
        override val defaultValue = ""
        override suspend fun readFrom(input: InputStream) = input.readBytes().decodeToString()
        override suspend fun writeTo(t: String, output: OutputStream) { output.write(t.encodeToByteArray()) }
    }

    fun open(context: Context, name: String): PluginStringStore {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")) && name != "settings")
        return synchronized(stores) {
            stores.getOrPut(name) {
                val store = MultiProcessDataStoreFactory.create(serializer = serializer) {
                    File(context.filesDir, "datastore/$name.json")
                }
                object : PluginStringStore {
                    override val data = store.data
                    override suspend fun updateData(transform: suspend (String) -> String) = store.updateData(transform)
                }
            }
        }
    }
}
