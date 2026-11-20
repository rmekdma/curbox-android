package neth.iecal.todo

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.learning.LearningStorage
import neth.iecal.curbox.learning.TodoRegistrationScope
import neth.iecal.plugin.PluginRule
import neth.iecal.plugin.PluginRuleSettings
import neth.iecal.plugin.PluginTimeHost
import java.util.UUID

internal fun PluginRule.isTodoEligible() = eligibleForContributions && unlockDays == setOf(0, 6)

class TodoRuleSettings(private val context: android.content.Context, private val host: PluginTimeHost, private val storage: LearningStorage) : PluginRuleSettings {
    override val label: String get() = context.getString(R.string.todo_award_connection)
    private val gson = Gson()
    private fun decode(raw: String?) = if (raw.isNullOrBlank()) TodoAwardState() else gson.fromJson(raw, TodoAwardState::class.java)

    override suspend fun summary(): String? = decode(host.read().data).connection?.childName

    private suspend fun save(action: suspend () -> Boolean): Boolean = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override fun open(activity: FragmentActivity, onChanged: () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                val snapshot = host.read()
                val state = decode(snapshot.data)
                val children = withContext(Dispatchers.IO) {
                    val document = storage.read()
                    document.monitor.registeredChildren().keys.mapNotNull { TodoRegistrationScope.capture(document, it) }
                }
                val rules = snapshot.rules.filter { it.isTodoEligible() }
                val choices = children.map { it.childName } + if (state.connection != null) listOf(activity.getString(R.string.todo_award_disconnect)) else emptyList()
                if (choices.isEmpty() || (rules.isEmpty() && state.connection == null)) {
                    Toast.makeText(activity, R.string.todo_award_no_choices, Toast.LENGTH_LONG).show()
                    return@launch
                }
                MaterialAlertDialogBuilder(activity).setTitle(R.string.todo_award_choose_child)
                    .setItems(choices.toTypedArray()) { _, index ->
                        if (index >= children.size) {
                            activity.lifecycleScope.launch {
                                val saved = save { host.update(snapshot, gson.toJson(state.copy(connection = null, pending = emptyList())), null) }
                                Toast.makeText(activity, if (saved) R.string.todo_award_saved else R.string.todo_award_failed, Toast.LENGTH_SHORT).show()
                                if (saved) onChanged()
                            }
                        } else if (rules.isEmpty()) {
                            Toast.makeText(activity, R.string.todo_award_no_choices, Toast.LENGTH_LONG).show()
                        } else {
                            val scope = children[index]
                            MaterialAlertDialogBuilder(activity).setTitle(R.string.todo_award_choose_rule)
                                .setItems(rules.map { it.label.ifBlank { it.id } }.toTypedArray()) { _, ruleIndex ->
                                    activity.lifecycleScope.launch {
                                        val rule = rules[ruleIndex]
                                        val link = TodoAwardConnection(scope.accountId, scope.childName, rule.id, snapshot.useDayId, UUID.randomUUID().toString())
                                        val saved = save { withContext(Dispatchers.IO) {
                                            storage.withRegistration(scope) {
                                                host.update(snapshot, gson.toJson(state.copy(connection = link, pending = emptyList())), rule.id)
                                            } == true
                                        } }
                                        Toast.makeText(activity, if (saved) R.string.todo_award_saved else R.string.todo_award_failed, Toast.LENGTH_SHORT).show()
                                        if (saved) onChanged()
                                    }
                                }.setNegativeButton(android.R.string.cancel, null).show()
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.todo_award_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
