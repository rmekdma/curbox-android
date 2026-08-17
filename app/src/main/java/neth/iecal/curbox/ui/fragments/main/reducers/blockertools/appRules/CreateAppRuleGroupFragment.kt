package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.databinding.FragmentCreateAppRuleGroupBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianSessionRegistry

class CreateAppRuleGroupFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "create_app_rule_group"
    }

    private var _binding: FragmentCreateAppRuleGroupBinding? = null
    private val binding get() = _binding!!
    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }
    private var selectedPackages = arrayListOf<String>()
    private var editingGroup: AppRuleAppGroup? = null

    private val selectApps = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        result.data?.getStringArrayListExtra("SELECTED_APPS")?.let {
            selectedPackages = it
            updateSelectionLabel()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAppRuleGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.selectAppsButton.setOnClickListener {
            selectApps.launch(GuardianSessionRegistry.attachInternalNavigationToken(
                Intent(requireContext(), SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", selectedPackages)
                putExtra("ALLOW_CUSTOM_APPS", false)
                putExtra(SelectAppsActivity.EXTRA_STRICT_LAUNCHABLE_APPS, true)
                putExtra(SelectAppsActivity.EXTRA_FILTER_APP_RULE_ESSENTIALS, true)
                }
            ))
        }
        binding.saveGroupButton.setOnClickListener { save() }
        val id = requireActivity().intent.getStringExtra("app_rule_group_id")
        if (id != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val group = dataStore.settingsForEditing.first().appRuleSnapshot.appGroups
                    .find { it.id == id } ?: return@launch
                editingGroup = group
                binding.screenTitle.setText(R.string.app_rules_edit_group)
                binding.nameInput.setText(group.name)
                selectedPackages = ArrayList(group.selectedPackages)
                binding.deleteGroupButton.visibility = View.VISIBLE
                updateSelectionLabel()
            }
            binding.deleteGroupButton.setOnClickListener { delete() }
        }
    }

    private fun save() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || selectedPackages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.app_rules_enter_group, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val current = dataStore.settingsForEditing.first().appRuleSnapshot
            val group = editingGroup?.copy(name = name, selectedPackages = selectedPackages.distinct())
                ?: AppRuleAppGroup.create(name, selectedPackages)
            val groups = current.appGroups.filterNot { it.id == group.id } + group
            if (!dataStore.updateAppRuleSnapshot(current.copy(appGroups = groups))) {
                Toast.makeText(requireContext(), R.string.app_rules_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            requireActivity().finish()
        }
    }

    private fun delete() {
        val group = editingGroup ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val current = dataStore.settingsForEditing.first().appRuleSnapshot
            val references = current.targetRuleIdsReferencing(group.id)
            if (references.isEmpty()) {
                dataStore.updateAppRuleSnapshot(
                    current.copy(appGroups = current.appGroups.filterNot { it.id == group.id })
                )
                requireActivity().finish()
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_rules_group_delete_referenced_title)
                .setMessage(getString(R.string.app_rules_group_delete_referenced_message, references.size))
                .setItems(
                    arrayOf(
                        getString(R.string.app_rules_remove_group_references),
                        getString(R.string.app_rules_delete_dependent_rules)
                    )
                ) { _, which ->
                    viewLifecycleOwner.lifecycleScope.launch deleteGroup@{
                        val latest = dataStore.settingsForEditing.first().appRuleSnapshot
                        val updated = latest.deleteTargetGroup(
                            group.id,
                            removeReferences = which == 0,
                            deleteDependentRules = which == 1
                        ) ?: return@deleteGroup
                        if (dataStore.updateAppRuleSnapshot(updated)) requireActivity().finish()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateSelectionLabel() {
        binding.selectAppsButton.text = getString(R.string.app_rules_select_apps_count, selectedPackages.size)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
