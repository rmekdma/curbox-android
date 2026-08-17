package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.databinding.FragmentCreateAppRuleBinding
import neth.iecal.curbox.utils.DataStoreManager

class CreateAppRuleFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "create_app_rule"
    }

    private var _binding: FragmentCreateAppRuleBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }
    private var editingRule: AppRule? = null
    private var groupIds = emptyList<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAppRuleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.startPicker.setIs24HourView(true)
        binding.endPicker.setIs24HourView(true)
        binding.startPicker.hour = 9
        binding.startPicker.minute = 0
        binding.endPicker.hour = 17
        binding.endPicker.minute = 0
        binding.saveRuleButton.setOnClickListener { save() }
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = dataStore.settingsForEditing.first().appRuleSnapshot
            groupIds = snapshot.appGroups.map { it.id }
            binding.groupSpinner.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                snapshot.appGroups.map { it.name }
            )
            val id = requireActivity().intent.getStringExtra("app_rule_id")
            editingRule = snapshot.appRules.find { it.id == id }
            editingRule?.let {
                binding.screenTitle.setText(R.string.app_rules_edit_rule)
                populate(it)
            }
            binding.deleteRuleButton.visibility = if (editingRule == null) View.GONE else View.VISIBLE
        }
        binding.deleteRuleButton.setOnClickListener { delete() }
    }

    private fun weekdayChecks(): List<CheckBox> = listOf(
        binding.weekdaySunday,
        binding.weekdayMonday,
        binding.weekdayTuesday,
        binding.weekdayWednesday,
        binding.weekdayThursday,
        binding.weekdayFriday,
        binding.weekdaySaturday
    )

    private fun populate(rule: AppRule) {
        binding.nameInput.setText(rule.name)
        binding.allowanceInput.setText(rule.allowedMinutes.toString())
        binding.groupSpinner.setSelection(groupIds.indexOf(rule.appGroupId).coerceAtLeast(0))
        binding.startPicker.hour = rule.startMinute / 60
        binding.startPicker.minute = rule.startMinute % 60
        binding.endPicker.hour = rule.endMinute / 60
        binding.endPicker.minute = rule.endMinute % 60
        binding.activeSwitch.isChecked = rule.isActive
        weekdayChecks().forEachIndexed { index, check -> check.isChecked = index in rule.weekdays }
    }

    private fun save() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val allowance = binding.allowanceInput.text?.toString()?.toLongOrNull()
        val groupId = groupIds.getOrNull(binding.groupSpinner.selectedItemPosition)
        val weekdays = weekdayChecks().mapIndexedNotNull { index, check ->
            index.takeIf { check.isChecked }
        }.toSet()
        if (name.isEmpty() || allowance == null || allowance < 0 || groupId == null || weekdays.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_rules_complete_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val rule = editingRule?.copy(
            name = name,
            isActive = binding.activeSwitch.isChecked,
            weekdays = weekdays,
            startMinute = binding.startPicker.hour * 60 + binding.startPicker.minute,
            endMinute = binding.endPicker.hour * 60 + binding.endPicker.minute,
            appGroupId = groupId,
            allowedMinutes = allowance
        ) ?: AppRule.create(
            name = name,
            weekdays = weekdays,
            startMinute = binding.startPicker.hour * 60 + binding.startPicker.minute,
            endMinute = binding.endPicker.hour * 60 + binding.endPicker.minute,
            appGroupId = groupId,
            allowedMinutes = allowance,
            isActive = binding.activeSwitch.isChecked
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val current = dataStore.settingsForEditing.first().appRuleSnapshot
            val rules = current.appRules.filterNot { it.id == rule.id } + rule
            if (dataStore.updateAppRuleSnapshot(current.copy(appRules = rules))) {
                requireActivity().finish()
            } else {
                Toast.makeText(requireContext(), R.string.app_rules_rule_save_failed, Toast.LENGTH_SHORT).show()
            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
