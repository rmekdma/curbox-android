package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.databinding.FragmentCreateAppRuleBinding
import neth.iecal.curbox.databinding.ItemAppRuleTimeRangeBinding
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
    private data class RangeEditor(
        val binding: ItemAppRuleTimeRangeBinding,
        var startMinute: Int,
        var endMinute: Int
    )

    private val rangeEditors = mutableListOf<RangeEditor>()

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
        addTimeRange(AppRuleTimeRange(9 * 60, 17 * 60))
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
        clearTimeRanges()
        ranges.forEach(::addTimeRange)
    }

    private fun clearTimeRanges() {
        binding.timeRangesContainer.removeAllViews()
        rangeEditors.clear()
    }

    private fun addTimeRange(range: AppRuleTimeRange = AppRuleTimeRange(9 * 60, 17 * 60)) {
        val rowBinding = ItemAppRuleTimeRangeBinding.inflate(
            LayoutInflater.from(requireContext()),
            binding.timeRangesContainer,
            false
        )
        val editor = RangeEditor(rowBinding, range.startMinute, range.endMinute)
        rowBinding.startTimeButton.setOnClickListener { showTimePicker(editor, isStart = true) }
        rowBinding.endTimeButton.setOnClickListener { showTimePicker(editor, isStart = false) }
        rowBinding.removeTimeRangeButton.setOnClickListener {
            binding.timeRangesContainer.removeView(rowBinding.root)
            rangeEditors.remove(editor)
            renderRangeSummary()
        }
        binding.timeRangesContainer.addView(rowBinding.root)
        rangeEditors += editor
        renderEditor(editor)
    }

    private fun showTimePicker(editor: RangeEditor, isStart: Boolean) {
        val minute = if (isStart) editor.startMinute else editor.endMinute
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(
                if (DateFormat.is24HourFormat(requireContext())) {
                    TimeFormat.CLOCK_24H
                } else {
                    TimeFormat.CLOCK_12H
                }
            )
            .setHour(minute / 60)
            .setMinute(minute % 60)
            .setTitleText(
                getString(if (isStart) R.string.select_start_time else R.string.select_end_time)
            )
            .build()
        picker.addOnPositiveButtonClickListener {
            val selectedMinute = picker.hour * 60 + picker.minute
            if (isStart) editor.startMinute = selectedMinute else editor.endMinute = selectedMinute
            renderEditor(editor)
        }
        picker.show(childFragmentManager, "app_rule_time_picker")
    }

    private fun renderEditor(editor: RangeEditor) {
        editor.binding.startTimeButton.text = formatMinute(editor.startMinute)
        editor.binding.endTimeButton.text = formatMinute(editor.endMinute)
        renderRangeSummary()
    }

    private fun renderRangeSummary() {
        val summary = rangeEditors.joinToString { editor ->
            "${formatMinute(editor.startMinute)} to ${formatMinute(editor.endMinute)}"
        }
        binding.timeRangesSummary.text = getString(R.string.app_rules_time_ranges_summary, summary)
    }

    private fun formatMinute(minute: Int): String {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, (minute / 60) % 24)
            set(java.util.Calendar.MINUTE, minute % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return DateFormat.getTimeFormat(requireContext()).format(calendar.time)
    }

    private fun save() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val allowance = binding.allowanceInput.text?.toString()?.toLongOrNull()
        val weekdays = weekdayChecks().mapIndexedNotNull { index, check ->
            index.takeIf { check.isChecked }
        }.toSet()
        val ranges = rangeEditors.map { editor ->
            AppRuleTimeRange(editor.startMinute, editor.endMinute)
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
            timeRanges = ranges
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
        rangeEditors.clear()
    }
}
