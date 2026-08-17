package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Local guardian password settings, intentionally separate from anti-uninstall protection. */
class GuardianAuthFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "guardian_auth"
    }

    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        root.addView(TextView(requireContext()).apply {
            text = getString(R.string.guardian_settings_title)
            textSize = 24f
        })
        root.addView(TextView(requireContext()).apply {
            text = getString(R.string.guardian_settings_description)
            setPadding(0, 12, 0, 24)
        })
        val current = passwordField(R.string.guardian_current_password_hint)
        val next = passwordField(R.string.guardian_new_password_hint)
        val confirm = passwordField(R.string.guardian_confirm_password_hint)
        root.addView(current)
        root.addView(next)
        root.addView(confirm)
        val save = MaterialButton(requireContext()).apply {
            text = getString(R.string.guardian_save_password)
            setOnClickListener {
                val currentText = current.text?.toString().orEmpty()
                val nextText = next.text?.toString().orEmpty()
                val confirmText = confirm.text?.toString().orEmpty()
                if (nextText != confirmText) {
                    Toast.makeText(requireContext(), R.string.guardian_password_mismatch, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val changed = dataStore.setGuardianPasswordAuthorized(currentText, nextText)
                    if (changed) {
                        Toast.makeText(requireContext(), R.string.guardian_password_saved, Toast.LENGTH_SHORT).show()
                        current.text?.clear()
                        next.text?.clear()
                        confirm.text?.clear()
                    } else {
                        Toast.makeText(requireContext(), R.string.guardian_wrong_password, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        root.addView(save)
        val clear = MaterialButton(requireContext()).apply {
            text = getString(R.string.guardian_clear_password)
            setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.guardian_clear_password)
                    .setMessage(R.string.guardian_clear_password_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.common_continue) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val changed = dataStore.clearGuardianPassword(current.text?.toString().orEmpty())
                            Toast.makeText(
                                requireContext(),
                                if (changed) R.string.guardian_password_saved else R.string.guardian_wrong_password,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .show()
            }
        }
        root.addView(clear)
        return root
    }

    private fun passwordField(hint: Int): EditText = EditText(requireContext()).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}
