package neth.iecal.todo

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import neth.iecal.curbox.learning.LearningComposition
import neth.iecal.curbox.learning.LearningNotifications
import neth.iecal.curbox.learning.LearningPermission
import neth.iecal.curbox.learning.LearningStorage
import neth.iecal.plugin.*

class TodoPluginFactory : PluginFactory {
    override val id = "todo"
    override val storeNames = setOf("learning-state", "learning-session", "learning-fake-state", "learning-fake-session")
    override val legacySettingKeys get() = TodoAwardLegacyMigration.keys
    override val legacyGrantSourceKey = "todoGrantedMillis"
    override fun onLinkedRolloverDisabled(data: String?, currentUseDayId: String): String? {
        if (data.isNullOrBlank()) return data
        val gson = com.google.gson.Gson()
        val state = gson.fromJson(data, TodoAwardState::class.java)
        return gson.toJson(state.withAutoSettlement(false, currentUseDayId))
    }

    override fun onLinkedTimeRevoked(data: String?, currentUseDayId: String): String? =
        TodoAwardLegacyMigration.onLinkedTimeRevoked(data, currentUseDayId)
    override fun migrateLegacySettings(fields: Map<String, String>): PluginLegacyTimeState? =
        TodoAwardLegacyMigration.migrate(fields)?.let { PluginLegacyTimeState(it.first, it.second) }

    override fun create(context: Context, host: PluginHost): HostPlugin {
        val app = context.applicationContext
        val storage = LearningStorage(host, BuildConfig.LEARNING_FAKE_ONLY)
        val api = LearningComposition.createApi(app)
        val log = TodoDiagnosticsFile(app.filesDir)
        val diagnostics = TodoRequestDiagnostics(log::append, enabled = { log.enabled(System.currentTimeMillis()) }, onFailure = host::logFailure)
        val automaticApi = DiagnosticTodoApi(api, diagnostics, "automatic")
        val settlementApi = DiagnosticTodoApi(api, diagnostics, "settlement")
        val award = TodoAwardRepository(host.time, storage, api, app.getString(R.string.todo_award_source_label), host::logFailure, settlementApi)
        return object : HostPlugin {
            override val destination = PluginDestination(app.getString(R.string.learning_title), "learning", R.drawable.learning_chart) { LearningFragment() }
            override val timeProvider = award
            override suspend fun run(hostConnection: CurboxHost) = coroutineScope {
                val permission = LearningPermission(app, this, host::logFailure)
                try {
                    permission.start()
                    val monitor = InitialMonitor(automaticApi, storage, permission.state, permission::isGranted,
                        { document, expected ->
                            try { LearningNotifications.publish(app, storage, document, permission.state, expected) }
                            catch (e: CancellationException) { throw e }
                            catch (t: Throwable) { host.logFailure(t) }
                        },
                        elapsed = android.os.SystemClock::elapsedRealtime,
                        subjectPackages = LearningComposition.subjectPackages(),
                        achievement = { document, expected, child, subject, stillValid ->
                            try { LearningNotifications.publish(app, storage, document, permission.state, expected, child, subject, stillValid) }
                            catch (e: CancellationException) { throw e }
                            catch (t: Throwable) { host.logFailure(t) }
                        })
                    monitor.run(hostConnection)
                } finally { permission.stop() }
            }
        }
    }
}
