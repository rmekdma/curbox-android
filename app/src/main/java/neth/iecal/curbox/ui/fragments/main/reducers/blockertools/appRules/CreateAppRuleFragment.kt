package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TimePicker
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleTimeRange
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
    private var groups: List<AppRuleAppGroup> = emptyList()
    private val includedChecks = linkedMapOf<String, CheckBox>()
    private val excludedChecks = linkedMapOf<String, CheckBox>()
    private val rangePickers = mutableListOf<Pair<TimePicker, TimePicker>>()

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
        rangePickers += binding.startPicker to binding.endPicker
        binding.addTimeRangeButton.setOnClickListener { addTimeRange() }
        binding.saveRuleButton.setOnClickListener { save() }
        binding.deleteRuleButton.setOnClickListener { delete() }

        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = dataStore.settingsForEditing.first().appRuleSnapshot
            groups = snapshot.appGroups
            renderGroupChecks()
            val id = requireActivity().intent.getStringExtra("app_rule_id")
            editingRule = snapshot.appRules.find { it.id == id }
            editingRule?.let {
                binding.screenTitle.setText(R.string.app_rules_edit_rule)
                populate(it)
            }
            binding.deleteRuleButton.visibility = if (editingRule == null) View.GONE else View.VISIBLE
        }
    }

    private fun renderGroupChecks() {
        includedChecks.clear()
        excludedChecks.clear()
        binding.includedGroupsContainer.removeAllViews()
        binding.excludedGroupsContainer.removeAllViews()
        groups.forEach { group ->
            val included = MaterialCheckBox(requireContext()).apply {
                text = group.name
                tag = group.id
            }
            val excluded = MaterialCheckBox(requireContext()).apply {
                text = group.name
                tag = group.id
            }
            includedChecks[group.id] = included
            excludedChecks[group.id] = excluded
            binding.includedGroupsContainer.addView(included)
            binding.excludedGroupsContainer.addView(excluded)
        }
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
        binding.activeSwitch.isChecked = rule.isActive
        weekdayChecks().forEachIndexed { index, check -> check.isChecked = index in rule.weekdays }
        val scope = rule.effectiveScope()
        binding.includeAllAppsSwitch.isChecked = scope.includeAllApps
        scope.includedGroupIds.forEach { includedChecks[it]?.isChecked = true }
        scope.excludedGroupIds.forEach { excludedChecks[it]?.isChecked = true }

        val ranges = rule.effectiveTimeRanges()
        setPicker(binding.startPicker, ranges.first())
        setPicker(binding.endPicker, ranges.first(), end = true)
        ranges.drop(1).forEach(::addTimeRange)
    }

    private fun setPicker(
        picker: TimePicker,
        range: AppRuleTimeRange,
        end: Boolean = false
    ) {
        val minute = if (end) range.endMinute else range.startMinute
        picker.hour = minute / 60
        picker.minute = minute % 60
    }

    private fun addTimeRange(range: AppRuleTimeRange? = null) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val start = TimePicker(requireContext()).apply {
            setIs24HourView(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            range?.let { setPicker(this, it) }
        }
        val end = TimePicker(requireContext()).apply {
            setIs24HourView(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            range?.let { setPicker(this, it, end = true) }
        }
        val remove = MaterialButton(requireContext()).apply {
            text = getString(R.string.remove)
            setOnClickListener {
                binding.timeRangesContainer.removeView(row)
                rangePickers.remove(start to end)
            }
        }
        row.addView(start)
        row.addView(end)
        row.addView(remove)
        binding.timeRangesContainer.addView(row)
        rangePickers += start to end
    }

    private fun save() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val allowance = binding.allowanceInput.text?.toString()?.toLongOrNull()
        val weekdays = weekdayChecks().mapIndexedNotNull { index, check ->
            index.takeIf { check.isChecked }
        }.toSet()
        val ranges = rangePickers.map { (start, end) ->
            AppRuleTimeRange(start.hour * 60 + start.minute, end.hour * 60 + end.minute)
        }
        if (name.isEmpty() || allowance == null || allowance < 0 || weekdays.isEmpty() || ranges.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_rules_complete_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val scope = AppRuleScope(
            includeAllApps = binding.includeAllAppsSwitch.isChecked,
            includedGroupIds = includedChecks.filterValues { it.isChecked }.keys,
            excludedGroupIds = excludedChecks.filterValues { it.isChecked }.keys
        )
        val firstRange = ranges.first()
        val rule = editingRule?.copy(
            name = name,
            isActive = binding.activeSwitch.isChecked,
            weekdays = weekdays,
            startMinute = firstRange.startMinute,
            endMinute = firstRange.endMinute,
            appGroupId = "",
            allowedMinutes = allowance,
            scope = scope,
            timeRanges = ranges,
            ranges = emptyList(),
            includeAllApps = false,
            includedGroupIds = emptySet(),
            excludedGroupIds = emptySet()
        ) ?: AppRule.create(
            name = name,
            weekdays = weekdays,
            startMinute = firstRange.startMinute,
            endMinute = firstRange.endMinute,
            appGroupId = "",
            allowedMinutes = allowance,
            isActive = binding.activeSwitch.isChecked
        ).copy(scope = scope, timeRanges = ranges)
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
        rangePickers.clear()
    }
}
