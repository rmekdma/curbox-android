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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.databinding.FragmentAppRuleGroupsBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.DataStoreManager

/** Entry point for the first unified app-rule vertical slice. */
class AppRuleGroupsFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "app_rule_groups"
    }

    private var _binding: FragmentAppRuleGroupsBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }

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
                dataStore.settingsForEditing.collectLatest { render(it.appRuleSnapshot) }
            }
        }
    }

    private fun render(snapshot: AppRuleSnapshot) {
        binding.groupsContainer.removeAllViews()
        binding.rulesContainer.removeAllViews()
        val errors = snapshot.validate()
        binding.configurationStatus.visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
        binding.configurationStatus.text = if (errors.isEmpty()) "" else {
            getString(R.string.app_rules_configuration_error)
        }
        binding.addRuleButton.isEnabled = errors.isEmpty()
        snapshot.appGroups.forEach(::addGroup)
        snapshot.appRules.forEach { rule -> addRule(rule, snapshot) }
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

    private fun addRule(rule: AppRule, snapshot: AppRuleSnapshot) {
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
        binding.rulesContainer.addView(MaterialCardView(requireContext()).apply {
            setContentPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(TextView(context).apply {
                text = getString(
                    R.string.app_rules_rule_summary,
                    rule.name,
                    groupName,
                    rule.allowedMinutes
                )
                textSize = 15f
            })
            setOnClickListener { open(CreateAppRuleFragment.FRAGMENT_ID, rule.id) }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
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
}
