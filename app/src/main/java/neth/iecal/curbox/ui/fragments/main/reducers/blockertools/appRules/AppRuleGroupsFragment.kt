package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
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
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
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
            render(settings.appRuleSnapshot, usage, usage != null)
        }
    }

    private fun render(
        snapshot: AppRuleSnapshot,
        evaluations: Map<String, AppRuleEvaluation>?,
        usageAvailable: Boolean
    ) {
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
            addRule(rule, snapshot, evaluations?.get(rule.id), usageAvailable)
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
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
    }

    private fun addRule(
        rule: AppRule,
        snapshot: AppRuleSnapshot,
        evaluation: AppRuleEvaluation?,
        usageAvailable: Boolean
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
        binding.rulesContainer.addView(MaterialCardView(requireContext()).apply {
            setContentPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(TextView(context).apply {
                text = status
                textSize = 15f
            })
            setOnClickListener { open(CreateAppRuleFragment.FRAGMENT_ID, rule.id) }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
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
