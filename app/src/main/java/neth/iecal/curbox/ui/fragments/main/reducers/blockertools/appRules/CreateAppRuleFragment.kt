package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TimePicker
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DataStoreManager

class CreateAppRuleFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "create_app_rule"
    }

    private lateinit var nameInput: EditText
    private lateinit var allowanceInput: EditText
    private lateinit var groupSpinner: Spinner
    private lateinit var startPicker: TimePicker
    private lateinit var endPicker: TimePicker
    private lateinit var activeSwitch: Switch
    private val weekdayChecks = mutableListOf<CheckBox>()
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }
    private var editingRule: AppRule? = null
    private var groupIds = emptyList<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
        }
        nameInput = EditText(requireContext()).apply { hint = getString(R.string.app_rules_rule_name_hint) }
        allowanceInput = EditText(requireContext()).apply {
            hint = getString(R.string.app_rules_allowed_minutes_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        groupSpinner = Spinner(requireContext())
        startPicker = TimePicker(requireContext()).apply { setIs24HourView(true); hour = 9; minute = 0 }
        endPicker = TimePicker(requireContext()).apply { setIs24HourView(true); hour = 17; minute = 0 }
        activeSwitch = Switch(requireContext()).apply { text = getString(R.string.app_rules_active); isChecked = true }
        root.addView(nameInput)
        root.addView(allowanceInput)
        root.addView(groupSpinner)
        root.addView(startPicker)
        root.addView(endPicker)
        root.addView(activeSwitch)
        val days = listOf(
            R.string.app_rules_sunday,
            R.string.app_rules_monday,
            R.string.app_rules_tuesday,
            R.string.app_rules_wednesday,
            R.string.app_rules_thursday,
            R.string.app_rules_friday,
            R.string.app_rules_saturday
        )
        days.forEach { day ->
            weekdayChecks += CheckBox(requireContext()).apply { text = getString(day); isChecked = true }.also(root::addView)
        }
        root.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.app_rules_save_rule)
            setOnClickListener { save() }
        })
        root.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = getString(R.string.app_rules_delete_rule)
            visibility = View.GONE
            tag = "delete"
        })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = dataStore.settingsForEditing.first().appRuleSnapshot
            groupIds = snapshot.appGroups.map { it.id }
            groupSpinner.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item,
                snapshot.appGroups.map { it.name }
            )
            val id = requireActivity().intent.getStringExtra("app_rule_id")
            editingRule = snapshot.appRules.find { it.id == id }
            editingRule?.let(::populate)
            view.findViewWithTag<View>("delete")?.apply {
                visibility = if (editingRule == null) View.GONE else View.VISIBLE
                setOnClickListener { delete() }
            }
        }
    }

    private fun populate(rule: AppRule) {
        nameInput.setText(rule.name)
        allowanceInput.setText(rule.allowedMinutes.toString())
        groupSpinner.setSelection(groupIds.indexOf(rule.appGroupId).coerceAtLeast(0))
        startPicker.hour = rule.startMinute / 60
        startPicker.minute = rule.startMinute % 60
        endPicker.hour = rule.endMinute / 60
        endPicker.minute = rule.endMinute % 60
        activeSwitch.isChecked = rule.isActive
        weekdayChecks.forEachIndexed { index, check -> check.isChecked = index in rule.weekdays }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        val allowance = allowanceInput.text.toString().toLongOrNull()
        val groupId = groupIds.getOrNull(groupSpinner.selectedItemPosition)
        val weekdays = weekdayChecks.mapIndexedNotNull { index, check -> index.takeIf { check.isChecked } }.toSet()
        if (name.isEmpty() || allowance == null || allowance < 0 || groupId == null || weekdays.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_rules_complete_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val rule = editingRule?.copy(
            name = name,
            isActive = activeSwitch.isChecked,
            weekdays = weekdays,
            startMinute = startPicker.hour * 60 + startPicker.minute,
            endMinute = endPicker.hour * 60 + endPicker.minute,
            appGroupId = groupId,
            allowedMinutes = allowance
        ) ?: AppRule.create(
            name = name,
            weekdays = weekdays,
            startMinute = startPicker.hour * 60 + startPicker.minute,
            endMinute = endPicker.hour * 60 + endPicker.minute,
            appGroupId = groupId,
            allowedMinutes = allowance,
            isActive = activeSwitch.isChecked
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val current = dataStore.settingsForEditing.first().appRuleSnapshot
            val rules = current.appRules.filterNot { it.id == rule.id } + rule
            if (dataStore.updateAppRuleSnapshot(current.copy(appRules = rules))) requireActivity().finish()
            else Toast.makeText(requireContext(), R.string.app_rules_rule_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun delete() {
        val id = editingRule?.id ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val current = dataStore.settingsForEditing.first().appRuleSnapshot
            dataStore.updateAppRuleSnapshot(current.copy(appRules = current.appRules.filterNot { it.id == id }))
            requireActivity().finish()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
