package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.DataStoreManager

/** Minimal editor entry point for the first unified app-rule vertical slice. */
class AppRuleGroupsFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "app_rule_groups"
    }

    private lateinit var content: LinearLayout
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scroll = ScrollView(requireContext())
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
        }
        scroll.addView(content)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settingsForEditing.collectLatest { render(it.appRuleSnapshot) }
            }
        }
    }

    private fun render(snapshot: AppRuleSnapshot) {
        content.removeAllViews()
        content.addView(TextView(requireContext()).apply {
            text = getString(R.string.app_rules_screen_title)
            textSize = 24f
            setPadding(0, 0, 0, 16.dp())
        })
        content.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.app_rules_add_group)
            setOnClickListener { open(CreateAppRuleGroupFragment.FRAGMENT_ID) }
        })
        content.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.app_rules_add_rule)
            isEnabled = snapshot.appGroups.isNotEmpty()
            setOnClickListener { open(CreateAppRuleFragment.FRAGMENT_ID) }
        })
        addSection(getString(R.string.app_rules_groups))
        snapshot.appGroups.forEach { group -> addGroup(group) }
        addSection(getString(R.string.app_rules_rules))
        snapshot.appRules.forEach { rule -> addRule(rule, snapshot) }
    }

    private fun addSection(title: String) {
        content.addView(TextView(requireContext()).apply {
            text = title
            textSize = 16f
            setPadding(0, 20.dp(), 0, 8.dp())
        })
    }

    private fun addGroup(group: AppRuleAppGroup) {
        content.addView(MaterialCardView(requireContext()).apply {
            setContentPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(TextView(context).apply {
                text = "${group.name}\n${getString(R.string.app_rules_group_apps, group.selectedPackages.size)}"
                textSize = 15f
                setOnClickListener { open(CreateAppRuleGroupFragment.FRAGMENT_ID, group.id) }
            })
            setOnClickListener { open(CreateAppRuleGroupFragment.FRAGMENT_ID, group.id) }
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
    }

    private fun addRule(rule: AppRule, snapshot: AppRuleSnapshot) {
        val groupName = snapshot.appGroups.find { it.id == rule.appGroupId }?.name
            ?: getString(R.string.app_rules_missing_group)
        content.addView(MaterialCardView(requireContext()).apply {
            setContentPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(TextView(context).apply {
                text = "${rule.name}\n${getString(R.string.app_rules_rule_summary, groupName, rule.allowedMinutes)}"
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
        if (id != null) intent.putExtra("app_rule_id", id).putExtra("app_rule_group_id", id)
        startActivity(intent)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
