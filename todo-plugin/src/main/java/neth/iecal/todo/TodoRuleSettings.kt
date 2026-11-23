package neth.iecal.todo

import android.content.Context
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.AdapterView
import android.view.View
import com.google.android.material.checkbox.MaterialCheckBox
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
import neth.iecal.plugin.PluginTimeHost
import java.util.UUID

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
                val rules = snapshot.rules.filter { it.eligibleForContributions }
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
                    val old = state.connection
                    val sameChild = old?.accountId == scope.accountId && old.childName == scope.childName
                    val initialIndex = rules.indexOfFirst { sameChild && it.id == old?.ruleId }.coerceAtLeast(0)
                    val content = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        val padding = (24 * resources.displayMetrics.density).toInt()
                        setPadding(padding, 0, padding, 0)
                    }
                    val selector = Spinner(activity).apply {
                        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                            rules.map { it.label.ifBlank { it.id } })
                        setSelection(initialIndex)
                    }
                    val automatic = MaterialCheckBox(activity).apply {
                        setText(R.string.todo_award_auto_settlement)
                    }
                    content.addView(selector)
                    content.addView(automatic)
                    fun selectRule(position: Int) {
                        val rule = rules[position]
                        automatic.isEnabled = rule.rolloverEnabled
                        automatic.isChecked = rule.rolloverEnabled &&
                            if (sameChild && old?.ruleId == rule.id) old.autoSettlementEnabled else true
                    }
                    selectRule(initialIndex)
                    selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            selectRule(position)
                        }
                    }
                    show(MaterialAlertDialogBuilder(activity).setTitle(R.string.todo_award_choose_rule)
                        .setView(content)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val rule = rules.getOrNull(selector.selectedItemPosition) ?: return@setPositiveButton
                            val enabled = rule.rolloverEnabled && automatic.isChecked
                            val sameLink = sameChild && old?.ruleId == rule.id
                            if (sameLink && old?.autoSettlementEnabled == enabled) return@setPositiveButton
                            val apply = {
                                owner.lifecycleScope.launch {
                                    val updated = if (sameLink) {
                                        state.withAutoSettlement(enabled, snapshot.useDayId)
                                    } else {
                                        val link = TodoAwardConnection(scope.accountId, scope.childName, rule.id,
                                            snapshot.useDayId, UUID.randomUUID().toString(), autoSettlementEnabled = enabled)
                                        state.copy(connection = link, pending = emptyList())
                                    }
                                    val saved = save { withContext(Dispatchers.IO) {
                                        storage.withRegistration(scope) {
                                            host.update(snapshot, gson.toJson(updated), rule.id)
                                        } == true
                                    } }
                                    result(saved)
                                }
                                Unit
                            }
                            if (old != null && (!sameLink || !enabled)) confirm(R.string.todo_award_change, apply) else apply()
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
