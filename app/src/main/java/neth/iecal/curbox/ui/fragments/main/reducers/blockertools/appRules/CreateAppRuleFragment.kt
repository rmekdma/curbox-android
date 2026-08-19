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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import neth.iecal.curbox.utils.GuardianOwnedDialog

class CreateAppRuleFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "create_app_rule"
    }

    private var _binding: FragmentCreateAppRuleBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }
    private var editingRule: AppRule? = null
    private var groups: List<AppRuleAppGroup> = emptyList()
    private val selectedIncludedGroupIds = mutableSetOf<String>()
    private val selectedExcludedGroupIds = mutableSetOf<String>()
    private val selectedContributorGroupIds = mutableSetOf<String>()
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

        binding.includeAllAppsSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateIncludeAllAppsState(isChecked)
        }
        binding.selectIncludedGroupsButton.setOnClickListener {
            showGroupSelectionDialog(
                titleRes = R.string.app_rules_include_groups,
                selectedIds = selectedIncludedGroupIds,
                onSelectionUpdated = { updateButtonLabels() }
            )
        }
        binding.selectExcludedGroupsButton.setOnClickListener {
            showGroupSelectionDialog(
                titleRes = R.string.app_rules_exclude_groups,
                selectedIds = selectedExcludedGroupIds,
                onSelectionUpdated = { updateButtonLabels() }
            )
        }
        binding.selectContributorGroupsButton.setOnClickListener {
            showGroupSelectionDialog(
                titleRes = R.string.app_rules_contributor_groups,
                selectedIds = selectedContributorGroupIds,
                onSelectionUpdated = { updateButtonLabels() }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = dataStore.settingsForEditing.first().appRuleSnapshot
            groups = snapshot.appGroups
            val id = requireActivity().intent.getStringExtra("app_rule_id")
            editingRule = snapshot.appRules.find { it.id == id }
            editingRule?.let {
                binding.screenTitle.setText(R.string.app_rules_edit_rule)
                populate(it)
            }
            updateIncludeAllAppsState(binding.includeAllAppsSwitch.isChecked)
            updateButtonLabels()
            binding.deleteRuleButton.visibility = if (editingRule == null) View.GONE else View.VISIBLE
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
        binding.usageConditionSwitch.isChecked = rule.usageConditionEnabled
        binding.usageConditionMinutesInput.setText(rule.usageConditionMinutes.toString())
        binding.earnedAllowanceSwitch.isChecked = rule.earnedAllowanceEnabled
        binding.activeSwitch.isChecked = rule.isActive
        weekdayChecks().forEachIndexed { index, check -> check.isChecked = index in rule.weekdays }
        val scope = rule.effectiveScope()
        binding.includeAllAppsSwitch.isChecked = scope.includeAllApps
        selectedIncludedGroupIds.clear()
        selectedIncludedGroupIds.addAll(scope.includedGroupIds)
        selectedExcludedGroupIds.clear()
        selectedExcludedGroupIds.addAll(scope.excludedGroupIds)
        selectedContributorGroupIds.clear()
        selectedContributorGroupIds.addAll(rule.effectiveContributorGroupIds())
        updateIncludeAllAppsState(scope.includeAllApps)
        updateButtonLabels()

        val ranges = rule.effectiveTimeRanges()
        clearTimeRanges()
        ranges.forEach(::addTimeRange)
    }

    private fun updateIncludeAllAppsState(includeAll: Boolean) {
        binding.includedGroupsSection.visibility = if (includeAll) View.GONE else View.VISIBLE
    }

    private fun updateButtonLabels() {
        updateButtonLabel(
            binding.selectIncludedGroupsButton,
            R.string.app_rules_include_groups,
            selectedIncludedGroupIds.size
        )
        updateButtonLabel(
            binding.selectExcludedGroupsButton,
            R.string.app_rules_exclude_groups,
            selectedExcludedGroupIds.size
        )
        updateButtonLabel(
            binding.selectContributorGroupsButton,
            R.string.app_rules_contributor_groups,
            selectedContributorGroupIds.size
        )
    }

    private fun updateButtonLabel(button: com.google.android.material.button.MaterialButton, titleRes: Int, count: Int) {
        val title = getString(titleRes)
        button.text = if (count > 0) {
            getString(R.string.app_rules_selected_count, title, count)
        } else {
            getString(R.string.app_rules_selected_none, title)
        }
    }

    private fun showGroupSelectionDialog(
        titleRes: Int,
        selectedIds: MutableSet<String>,
        onSelectionUpdated: () -> Unit
    ) {
        if (groups.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_rules_no_groups, Toast.LENGTH_SHORT).show()
            return
        }
        val names = groups.map { it.name }.toTypedArray()
        val checkedItems = groups.map { selectedIds.contains(it.id) }.toBooleanArray()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                val groupId = groups[which].id
                if (isChecked) selectedIds.add(groupId) else selectedIds.remove(groupId)
            }
            .setPositiveButton(R.string.save) { _, _ -> onSelectionUpdated() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        GuardianOwnedDialog.show(dialog)
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
        val conditionMinutes =
            binding.usageConditionMinutesInput.text?.toString()?.toLongOrNull() ?: 0L
        val weekdays = weekdayChecks().mapIndexedNotNull { index, check ->
            index.takeIf { check.isChecked }
        }.toSet()
        val ranges = rangeEditors.map { editor ->
            AppRuleTimeRange(editor.startMinute, editor.endMinute)
        }
        if (name.isEmpty() || allowance == null || allowance < 0 || conditionMinutes < 0L ||
            weekdays.isEmpty() || ranges.isEmpty()
        ) {
            Toast.makeText(requireContext(), R.string.app_rules_complete_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val scope = AppRuleScope(
            includeAllApps = binding.includeAllAppsSwitch.isChecked,
            includedGroupIds = if (binding.includeAllAppsSwitch.isChecked) emptySet() else selectedIncludedGroupIds.toSet(),
            excludedGroupIds = selectedExcludedGroupIds.toSet()
        )
        val contributorGroupIds = selectedContributorGroupIds.toSet()
        val usageConditionEnabled = binding.usageConditionSwitch.isChecked
        val earnedAllowanceEnabled = binding.earnedAllowanceSwitch.isChecked
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
            contributorGroupIds = contributorGroupIds,
            usageConditionEnabled = usageConditionEnabled,
            usageConditionMinutes = conditionMinutes,
            earnedAllowanceEnabled = earnedAllowanceEnabled
        ) ?: AppRule.create(
            name = name,
            weekdays = weekdays,
            startMinute = firstRange.startMinute,
            endMinute = firstRange.endMinute,
            appGroupId = "",
            allowedMinutes = allowance,
            isActive = binding.activeSwitch.isChecked
        ).copy(
            scope = scope,
            timeRanges = ranges,
            contributorGroupIds = contributorGroupIds,
            usageConditionEnabled = usageConditionEnabled,
            usageConditionMinutes = conditionMinutes,
            earnedAllowanceEnabled = earnedAllowanceEnabled
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
        rangeEditors.clear()
    }
}

internal fun missingContributorGroupIdsForEditor(
    rule: AppRule,
    availableGroupIds: Set<String>
): Set<String> = rule.effectiveContributorGroupIds() - availableGroupIds

internal fun contributorGroupIdsFromEditorChecks(
    checks: Map<String, Boolean>
): Set<String> = checks.filterValues { it }.keys
