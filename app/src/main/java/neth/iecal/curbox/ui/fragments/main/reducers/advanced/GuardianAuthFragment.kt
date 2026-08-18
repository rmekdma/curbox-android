package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentGuardianAuthBinding
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianOwnedDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Local guardian password settings, intentionally separate from anti-uninstall protection. */
class GuardianAuthFragment : Fragment() {
    companion object {
        const val FRAGMENT_ID = "guardian_auth"
    }

    private val dataStore by lazy { DataStoreManager(requireContext().applicationContext) }
    private var _binding: FragmentGuardianAuthBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        _binding = FragmentGuardianAuthBinding.inflate(inflater, container, false)
        binding.guardianSavePassword.setOnClickListener {
            val currentText = binding.guardianCurrentPassword.text?.toString().orEmpty()
            val nextText = binding.guardianNewPassword.text?.toString().orEmpty()
            val confirmText = binding.guardianConfirmPassword.text?.toString().orEmpty()
            if (nextText != confirmText) {
                Toast.makeText(requireContext(), R.string.guardian_password_mismatch, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val changed = dataStore.setGuardianPasswordAuthorized(currentText, nextText)
                if (changed) {
                    requireActivity().window.addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                    Toast.makeText(requireContext(), R.string.guardian_password_saved, Toast.LENGTH_SHORT).show()
                    binding.guardianCurrentPassword.text?.clear()
                    binding.guardianNewPassword.text?.clear()
                    binding.guardianConfirmPassword.text?.clear()
                } else {
                    Toast.makeText(requireContext(), R.string.guardian_wrong_password, Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.guardianClearPassword.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.guardian_clear_password)
                .setMessage(R.string.guardian_clear_password_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.common_continue) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val changed = dataStore.clearGuardianPassword(
                            binding.guardianCurrentPassword.text?.toString().orEmpty()
                        )
                        Toast.makeText(
                            requireContext(),
                            if (changed) R.string.guardian_password_saved else R.string.guardian_wrong_password,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .create()
            GuardianOwnedDialog.show(dialog)
        }
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
