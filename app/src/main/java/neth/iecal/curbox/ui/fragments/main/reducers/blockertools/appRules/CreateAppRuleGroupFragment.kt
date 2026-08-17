package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.utils.DataStoreManager

class CreateAppRuleGroupFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "create_app_rule_group"
    }

    private lateinit var nameInput: EditText
    private lateinit var selectAppsButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
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
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
        }
        nameInput = EditText(requireContext()).apply {
            hint = getString(R.string.app_rules_group_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        selectAppsButton = MaterialButton(requireContext()).apply { text = getString(R.string.app_rules_select_apps) }
        deleteButton = MaterialButton(requireContext()).apply { text = getString(R.string.app_rules_delete_group); visibility = View.GONE }
        root.addView(nameInput)
        root.addView(selectAppsButton)
        root.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.app_rules_save_group)
            setOnClickListener { save() }
        })
        root.addView(deleteButton)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectAppsButton.setOnClickListener {
            selectApps.launch(Intent(requireContext(), SelectAppsActivity::class.java).apply {
                putStringArrayListExtra("PRE_SELECTED_APPS", selectedPackages)
                putExtra("ALLOW_CUSTOM_APPS", false)
                putExtra("STRICT_LAUNCHABLE_APPS", true)
            })
        }
        val id = requireActivity().intent.getStringExtra("app_rule_group_id")
        if (id != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val group = dataStore.settingsForEditing.first().appRuleSnapshot.appGroups
                    .find { it.id == id } ?: return@launch
                editingGroup = group
                nameInput.setText(group.name)
                selectedPackages = ArrayList(group.selectedPackages)
                deleteButton.visibility = View.VISIBLE
                updateSelectionLabel()
            }
            deleteButton.setOnClickListener { delete() }
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
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
            if (current.appRules.any { it.appGroupId == group.id }) {
                Toast.makeText(requireContext(), R.string.app_rules_remove_group_first, Toast.LENGTH_SHORT).show()
                return@launch
            }
            dataStore.updateAppRuleSnapshot(current.copy(appGroups = current.appGroups.filterNot { it.id == group.id }))
            requireActivity().finish()
        }
    }

    private fun updateSelectionLabel() {
        selectAppsButton.text = getString(R.string.app_rules_select_apps_count, selectedPackages.size)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
