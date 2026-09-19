package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import android.text.Editable
import android.text.TextWatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.databinding.DialogGuardianAccumulatedTimeBinding
import neth.iecal.curbox.domain.apprules.InAppAccumulatedTimeFormState
import neth.iecal.curbox.domain.apprules.InAppAccumulatedTimeSubmission
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.databinding.FragmentAppRuleGroupsBinding
import neth.iecal.curbox.domain.apprules.AppRuleEvaluation
import neth.iecal.curbox.domain.apprules.AppRuleEvaluator
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.UsageResetUiPolicy
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianOwnedDialog
import neth.iecal.curbox.utils.UsageResetManager
import neth.iecal.curbox.utils.UsageResetStatus
import java.time.ZoneId

/** Entry point for the first unified app-rule vertical slice. */
class AppRuleGroupsFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "app_rule_groups"
        private const val MILLIS_PER_MINUTE = 60_000L
    }

    private var _binding: FragmentAppRuleGroupsBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }
    private val sessionRepository by lazy {
        RoomCurrentUseDaySessionRepository(
            AppDatabase.getInstance(requireContext().applicationContext).foregroundSessionDao()
        )
    }
    private val packageScopeReader by lazy {
        AppRulePackageScopeReader.fromContext(requireContext().applicationContext)
    }
    private var latestSettings: Settings? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppRuleGroupsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.addGroupButton.setOnClickListener { open(CreateAppRuleGroupFragment.FRAGMENT_ID) }
        binding.addRuleButton.setOnClickListener { open(CreateAppRuleFragment.FRAGMENT_ID) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dataStore.settingsForEditing.collectLatest { settings ->
                        latestSettings = settings
                        refresh(settings)
                    }
                }
                launch {
                    while (isActive) {
                        latestSettings?.let { refresh(it) }
                        delay(5_000L)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            usageResetReceiver,
            android.content.IntentFilter(UsageResetManager.ACTION_USAGE_RESET),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(usageResetReceiver) }
        super.onStop()
    }

    private val usageResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != UsageResetManager.ACTION_USAGE_RESET) return
            if (!isAdded) return
            val packages = intent.getStringArrayListExtra(UsageResetManager.EXTRA_PACKAGES).orEmpty().toSet()
            val affectsGroupTotals = UsageResetUiPolicy.affectsGroupTotals(
                groupPackages = latestSettings?.appRuleSnapshot?.appGroups
                    ?.map { group -> group.selectedPackages }
                    .orEmpty(),
                completedPackages = packages
            )
            if (affectsGroupTotals) {
                val message = if (intent.getBooleanExtra(UsageResetManager.EXTRA_RESULT_OK, false)) {
                    R.string.usage_reset_done
                } else {
                    R.string.usage_reset_failed
                }
                Toast.makeText(
                    requireContext(),
                    message,
                    if (message == R.string.usage_reset_done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
            latestSettings?.let { settings ->
                viewLifecycleOwner.lifecycleScope.launch { refresh(settings) }
            }
        }
    }

    private suspend fun refresh(settings: Settings) {
        val usage = try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val calculator = ConfigurableUseDayCalculator(zone, settings.useDayResetTime)
                val useDayId = calculator.idAt(now)
                val sessions = sessionRepository.sessionsForUseDay(
                    useDayId,
                    settings.useDayGenerationStartedAtMs
                )
                val availablePackages = packageScopeReader.readLaunchablePackages()
                val essentialPackages = packageScopeReader.readEssentialPackages()
                settings.appRuleSnapshot.appRules.associate { rule ->
                    rule.id to AppRuleEvaluator.evaluateRuleForSnapshot(
                        snapshot = settings.appRuleSnapshot,
                        rule = rule,
                        useDayId = useDayId,
                        sessions = sessions,
                        nowMs = now,
                        zone = zone,
                        useDayCalculator = calculator,
                        useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
                        availablePackages = availablePackages,
                        essentialExcludedPackages = essentialPackages,
                        overrides = settings.appRuleOverrideState
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (isAdded) {
            render(settings, usage, usage != null)
        }
    }

    private fun render(
        settings: Settings,
        evaluations: Map<String, AppRuleEvaluation>?,
        usageAvailable: Boolean
    ) {
        val snapshot = settings.appRuleSnapshot
        binding.groupsContainer.removeAllViews()
        binding.rulesContainer.removeAllViews()
        val errors = snapshot.validate()
        val hasMissingContributors = snapshot.appRules.any {
            snapshot.missingContributorGroupIds(it).isNotEmpty()
        }
        binding.configurationStatus.visibility =
            if (errors.isEmpty() && !hasMissingContributors) View.GONE else View.VISIBLE
        binding.configurationStatus.text = if (errors.isEmpty() && !hasMissingContributors) "" else {
            getString(R.string.app_rules_configuration_error)
        }
        binding.addRuleButton.isEnabled = errors.isEmpty()
        snapshot.appGroups.forEach(::addGroup)
        snapshot.appRules.forEach { rule ->
            addRule(
                rule = rule,
                snapshot = snapshot,
                evaluation = evaluations?.get(rule.id),
                usageAvailable = usageAvailable,
                rolloverPool = settings.appRuleRolloverState.pools[rule.id]
            )
        }
    }

    private fun addGroup(group: AppRuleAppGroup) {
        binding.groupsContainer.addView(MaterialCardView(requireContext()).apply {
            setContentPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(TextView(context).apply {
                text = getString(
                    R.string.app_rules_group_summary,
                    group.name,
                    group.selectedPackages.size
                )
                textSize = 15f
            })
            setOnClickListener { open(CreateAppRuleGroupFragment.FRAGMENT_ID, group.id) }
            setOnLongClickListener {
                confirmGroupReset(group)
                true
            }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
    }

    private fun confirmGroupReset(group: AppRuleAppGroup) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.usage_reset_group)
            .setMessage(getString(R.string.usage_reset_group_message, group.name, group.selectedPackages.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.usage_reset_confirm) { _, _ -> resetGroupUsage(group.id) }
            .create()
        GuardianOwnedDialog.show(dialog)
    }

    private fun resetGroupUsage(groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val outcome = UsageResetManager(requireContext()).resetGroup(groupId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (outcome.status) {
                    // Service completion is handled by the screen-wide receiver. Do not
                    // refresh or toast a second time when the manager observes that broadcast.
                    UsageResetStatus.SUCCESS -> Unit
                    UsageResetStatus.FAILED -> {
                        if (outcome.request == null) {
                            Toast.makeText(requireContext(), R.string.usage_reset_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    UsageResetStatus.PENDING -> {
                        Toast.makeText(requireContext(), R.string.usage_reset_pending, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun addRule(
        rule: AppRule,
        snapshot: AppRuleSnapshot,
        evaluation: AppRuleEvaluation?,
        usageAvailable: Boolean,
        rolloverPool: RuleRolloverPool?
    ) {
        val scope = rule.effectiveScope()
        val groupNames = scope.includedGroupIds.mapNotNull { id ->
            snapshot.appGroups.find { it.id == id }?.name
        }
        val groupName = when {
            scope.includeAllApps && groupNames.isNotEmpty() ->
                getString(R.string.app_rules_all_apps_and_groups, groupNames.joinToString())
            scope.includeAllApps -> getString(R.string.app_rules_all_apps)
            groupNames.isNotEmpty() -> groupNames.joinToString()
            else -> getString(R.string.app_rules_empty_scope)
        }
        val contributorNames = scopeContributorNames(rule, snapshot)
        val missingContributorIds = snapshot.missingContributorGroupIds(rule)
        val poolMinutes = rolloverPool?.accumulatedMinutes ?: 0L
        val hasRolloverInfo = rule.rolloverEnabled || poolMinutes > 0L
        val status = buildString {
            append(
                getString(
                    R.string.app_rules_rule_summary,
                    rule.name,
                    groupName,
                    rule.allowedMinutes
                )
            )
            if (!usageAvailable || evaluation == null) {
                append("\n")
                append(getString(R.string.app_rules_direct_summary, rule.allowedMinutes))
            }
            append("\n")
            append(
                getString(
                    R.string.app_rules_contributor_summary,
                    if (contributorNames.isEmpty()) {
                        getString(R.string.app_rules_contributor_none)
                    } else {
                        contributorNames.joinToString()
                    }
                )
            )
            if (rule.usageConditionEnabled) {
                append("\n")
                append(
                    getString(
                        R.string.app_rules_condition_summary,
                        rule.usageConditionMinutes
                    )
                )
            }
            append("\n")
            append(
                getString(
                    R.string.app_rules_earning_summary,
                    getString(
                        if (rule.earnedAllowanceEnabled) {
                            R.string.app_rules_on
                        } else {
                            R.string.app_rules_off
                        }
                    )
                )
            )
            append("\n")
            append(formatAppRuleRolloverSummary(rule) { resId, args ->
                if (args.isEmpty()) getString(resId) else getString(resId, *args)
            })
            if (hasRolloverInfo) {
                append("\n")
                append(formatAppRuleAccumulatedSummary(poolMinutes) { resId, args ->
                    if (args.isEmpty()) getString(resId) else getString(resId, *args)
                })
            }
            if (missingContributorIds.isNotEmpty()) {
                append("\n")
                append(getString(R.string.app_rules_missing_contributor))
            }
            append("\n")
            if (!usageAvailable || evaluation == null) {
                append(getString(R.string.app_rules_current_usage_unavailable))
            } else {
                if (evaluation.conditionEnabled) {
                    append(
                        getString(
                            R.string.app_rules_condition_progress_summary,
                            evaluation.contributorUsageMillis.toMinutesForDisplay(),
                            evaluation.conditionRequiredMillis.toMinutesForDisplay()
                        )
                    )
                } else {
                    append(getString(R.string.app_rules_condition_off_summary))
                }
                append("\n")
                append(
                    getString(
                        R.string.app_rules_earned_actual_summary,
                        evaluation.earnedAllowanceMillis.toMinutesForDisplay()
                    )
                )
                append("\n")
                append(
                    getString(
                        R.string.app_rules_direct_summary,
                        evaluation.directAllowanceMillis.toMinutesForDisplay()
                    )
                )
                append("\n")
                append(
                    getString(
                        R.string.app_rules_remaining_summary,
                        evaluation.remainingMillis.coerceAtLeast(0L).toMinutesForDisplay()
                    )
                )
                if (evaluation.guardianAllowanceMillis > 0L) {
                    append("\n")
                    append(
                        getString(
                            R.string.app_rules_guardian_time_summary,
                            evaluation.guardianRemainingMillis.coerceAtLeast(0L)
                                .toMinutesForDisplay()
                        )
                    )
                }
                if (evaluation.isSkipped) {
                    append("\n")
                    append(getString(R.string.app_rules_guardian_skipped))
                }
            }
        }
        val cardLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(context).apply {
                text = status
                textSize = 15f
            })
            if (hasRolloverInfo) {
                addView(MaterialButton(
                    context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = getString(R.string.app_rules_change_accumulated_time)
                    setOnClickListener {
                        showChangeAccumulatedTimeDialog(rule, poolMinutes)
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8.dp()
                })
            }
        }
        binding.rulesContainer.addView(MaterialCardView(requireContext()).apply {
            setContentPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(cardLayout)
            setOnClickListener { open(CreateAppRuleFragment.FRAGMENT_ID, rule.id) }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
    }

    private fun showChangeAccumulatedTimeDialog(rule: AppRule, currentMinutes: Long) {
        val dialogBinding = DialogGuardianAccumulatedTimeBinding.inflate(layoutInflater)
        dialogBinding.accumulatedTotalDesc.text = getString(
            R.string.app_rules_accumulated_time_summary,
            currentMinutes
        )
        val formState = InAppAccumulatedTimeFormState.initial(currentMinutes)
        dialogBinding.accumulatedMinutesInput.setText(formState.accumulatedMinutesText)
        dialogBinding.accumulatedMinutesInput.setSelectAllOnFocus(true)
        var currentFormState = formState

        dialogBinding.accumulatedMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                currentFormState = currentFormState.editMinutes(editable?.toString().orEmpty())
                dialogBinding.accumulatedMinutesLayout.error = null
            }
        })

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_rules_change_accumulated_time)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.accumulatedMinutesInput.requestFocus()
            dialogBinding.accumulatedMinutesInput.selectAll()

            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                when (val submission = currentFormState.submit()) {
                    is InAppAccumulatedTimeSubmission.Invalid -> {
                        dialogBinding.accumulatedMinutesLayout.error = getString(
                            R.string.app_rules_invalid_accumulated_minutes
                        )
                    }
                    is InAppAccumulatedTimeSubmission.Valid -> {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val currentSettings = dataStore.settings.first()
                            val existingPool = currentSettings.appRuleRolloverState.pools[rule.id]
                            val updatedPool = (existingPool ?: RuleRolloverPool(ruleId = rule.id))
                                .copy(accumulatedMinutes = submission.minutes)
                            val newState = currentSettings.appRuleRolloverState.withPool(updatedPool)
                            val success = dataStore.writeAppRuleRolloverState(newState)
                            withContext(Dispatchers.Main) {
                                if (isAdded && success) {
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                }
            }
        }

        GuardianOwnedDialog.show(dialog)
    }

    private fun scopeContributorNames(
        rule: AppRule,
        snapshot: AppRuleSnapshot
    ): List<String> = rule.effectiveContributorGroupIds().map { id ->
        snapshot.appGroups.find { it.id == id }?.name ?: getString(R.string.app_rules_missing_group)
    }

    private fun open(fragment: String, id: String? = null) {
        val intent = Intent(requireContext(), FragmentActivity::class.java)
            .putExtra("fragment", fragment)
        if (id != null) {
            val key = if (fragment == CreateAppRuleGroupFragment.FRAGMENT_ID) {
                "app_rule_group_id"
            } else {
                "app_rule_id"
            }
            intent.putExtra(key, id)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun Long.toMinutesForDisplay(): Long = this / MILLIS_PER_MINUTE
}

internal fun formatAppRuleRolloverSummary(
    rule: AppRule,
    stringResolver: (Int, Array<out Any>) -> String
): String {
    return if (rule.rolloverEnabled) {
        val dayResIds = listOf(
            R.string.app_rules_sunday_short,
            R.string.app_rules_monday_short,
            R.string.app_rules_tuesday_short,
            R.string.app_rules_wednesday_short,
            R.string.app_rules_thursday_short,
            R.string.app_rules_friday_short,
            R.string.app_rules_saturday_short
        )
        val unlockDaysText = rule.unlockDays.sorted().mapNotNull { dayIndex ->
            dayResIds.getOrNull(dayIndex)?.let { stringResolver(it, emptyArray()) }
        }.joinToString(", ")
        stringResolver(R.string.app_rules_rollover_summary_on, arrayOf(unlockDaysText))
    } else {
        stringResolver(R.string.app_rules_rollover_summary_off, emptyArray())
    }
}

internal fun formatAppRuleAccumulatedSummary(
    accumulatedMinutes: Long,
    stringResolver: (Int, Array<out Any>) -> String
): String {
    return stringResolver(
        R.string.app_rules_accumulated_time_summary,
        arrayOf(accumulatedMinutes)
    )
}

