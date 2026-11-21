package neth.iecal.todo

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.learning.LearningStorage
import neth.iecal.curbox.learning.TodoRegistrationScope
import neth.iecal.plugin.PluginRule
import neth.iecal.plugin.PluginTimeHost
import java.util.UUID

internal fun PluginRule.isTodoEligible() = eligibleForContributions && unlockDays == setOf(0, 6)

class TodoRuleSettings(private val context: Context, private val host: PluginTimeHost, private val storage: LearningStorage) {
    private val gson = Gson()
    private fun decode(raw: String?) = if (raw.isNullOrBlank()) TodoAwardState() else gson.fromJson(raw, TodoAwardState::class.java)

    suspend fun summary(): String {
        val snapshot = host.read()
        val link = decode(snapshot.data).connection ?: return context.getString(R.string.todo_award_not_connected)
        val rule = snapshot.rules.firstOrNull { it.id == link.ruleId }
        return context.getString(R.string.todo_award_current, link.childName, rule?.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.todo_award_rule_unavailable))
    }

    private suspend fun save(action: suspend () -> Boolean): Boolean = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    fun open(activity: FragmentActivity, owner: LifecycleOwner, onChanged: () -> Unit) {
        fun show(builder: MaterialAlertDialogBuilder) {
            val dialog = builder.create()
            val observer = object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
            }
            owner.lifecycle.addObserver(observer)
            dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
            dialog.show()
        }
        owner.lifecycleScope.launch {
            try {
                val snapshot = host.read()
                val state = decode(snapshot.data)
                val children = withContext(Dispatchers.IO) {
                    val document = storage.read()
                    document.monitor.registeredChildren().keys.mapNotNull { TodoRegistrationScope.capture(document, it) }
                }
                val rules = snapshot.rules.filter { it.isTodoEligible() }
                fun result(saved: Boolean) {
                    Toast.makeText(activity, if (saved) R.string.todo_award_saved else R.string.todo_award_failed, Toast.LENGTH_SHORT).show()
                    if (saved) onChanged()
                }
                fun confirm(button: Int, action: () -> Unit) {
                    show(MaterialAlertDialogBuilder(activity).setMessage(R.string.todo_award_change_warning)
                        .setPositiveButton(button) { _, _ -> action() }
                        .setNegativeButton(android.R.string.cancel, null))
                }
                fun chooseRule(scope: TodoRegistrationScope) {
                    if (rules.isEmpty()) {
                        Toast.makeText(activity, R.string.todo_award_no_choices, Toast.LENGTH_LONG).show()
                        return
                    }
                    show(MaterialAlertDialogBuilder(activity).setTitle(R.string.todo_award_choose_rule)
                        .setItems(rules.map { it.label.ifBlank { it.id } }.toTypedArray()) { _, index ->
                            val rule = rules[index]
                            val old = state.connection
                            if (old?.accountId == scope.accountId && old.childName == scope.childName && old.ruleId == rule.id) return@setItems
                            val apply = {
                                owner.lifecycleScope.launch {
                                    val link = TodoAwardConnection(scope.accountId, scope.childName, rule.id, snapshot.useDayId, UUID.randomUUID().toString())
                                    val saved = save { withContext(Dispatchers.IO) {
                                        storage.withRegistration(scope) {
                                            host.update(snapshot, gson.toJson(state.copy(connection = link, pending = emptyList())), rule.id)
                                        } == true
                                    } }
                                    result(saved)
                                }
                                Unit
                            }
                            if (old == null) apply() else confirm(R.string.todo_award_change, apply)
                        }.setNegativeButton(android.R.string.cancel, null))
                }
                fun chooseChild() {
                    when (children.size) {
                        0 -> Toast.makeText(activity, R.string.todo_award_no_choices, Toast.LENGTH_LONG).show()
                        1 -> chooseRule(children.single())
                        else -> show(MaterialAlertDialogBuilder(activity).setTitle(R.string.todo_award_choose_child)
                            .setItems(children.map { it.childName }.toTypedArray()) { _, index -> chooseRule(children[index]) }
                            .setNegativeButton(android.R.string.cancel, null))
                    }
                }
                if (state.connection == null) chooseChild() else {
                    show(MaterialAlertDialogBuilder(activity).setTitle(R.string.todo_award_connection)
                        .setItems(arrayOf(activity.getString(R.string.todo_award_change), activity.getString(R.string.todo_award_disconnect))) { _, index ->
                            if (index == 0) chooseChild() else confirm(R.string.todo_award_disconnect) {
                                owner.lifecycleScope.launch {
                                    result(save { host.update(snapshot, gson.toJson(state.copy(connection = null, pending = emptyList())), null) })
                                }
                            }
                        }.setNegativeButton(android.R.string.cancel, null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.todo_award_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
