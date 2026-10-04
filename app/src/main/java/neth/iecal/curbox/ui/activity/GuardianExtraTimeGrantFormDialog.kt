package neth.iecal.curbox.ui.activity

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.databinding.DialogGuardianExtraTimeBinding
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantFormState
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeInputSource
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeSubmission
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeValidationError
import neth.iecal.curbox.utils.GuardianOwnedDialog

data class GuardianExtraTimeGrantOption(
    val rule: AppRule,
    val basis: GuardianExtraTimeGrantBasis
) {
    val label: String
        get() = rule.name.takeIf(String::isNotBlank) ?: rule.id
}

/** Shared form used by the lock screen and the authenticated app rule screen. */
class GuardianExtraTimeGrantFormDialog(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val scope: CoroutineScope,
    private val readCurrentBasis: suspend (String) -> GuardianExtraTimeGrantBasis?,
    private val readCurrentCandidates: suspend () -> List<GuardianExtraTimeGrantOption>,
    private val onSubmit: (GuardianExtraTimeGrantBasis, Long) -> Unit,
    private val onDismiss: (AlertDialog) -> Unit = {}
) {
    fun show(
        options: List<GuardianExtraTimeGrantOption>,
        selectedIndex: Int = 0
    ): AlertDialog? {
        if (options.isEmpty()) return null
        val initialIndex = selectedIndex.coerceIn(options.indices)
        val binding = DialogGuardianExtraTimeBinding.inflate(inflater)
        var currentOptions = options
        var state = GuardianExtraTimeGrantFormState.initial(options[initialIndex].basis)
        var positiveButton: android.widget.Button? = null
        var dialog: AlertDialog? = null
        var updatingDerivedValue = false

        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            currentOptions.map(GuardianExtraTimeGrantOption::label)
        )
        binding.rulePicker.setAdapter(adapter)
        binding.rulePicker.setText(currentOptions[initialIndex].label, false)
        binding.additionalMinutesInput.setSelectAllOnFocus(true)
        binding.totalMinutesInput.setSelectAllOnFocus(true)

        fun render() {
            val basis = state.basis
            val currentTotalMinutes = state.input.currentTotalMinutes
            binding.currentTotal.text = context.getString(
                R.string.guardian_current_total,
                currentTotalMinutes
            )
            binding.totalMinutesLayout.placeholderText = currentTotalMinutes.toString()
            binding.additionalMinutesLayout.error = null
            binding.totalMinutesLayout.error = null
            positiveButton?.isEnabled = basis != null
        }

        fun reset(basis: GuardianExtraTimeGrantBasis?) {
            if (basis == null) {
                state = state.clearInputs()
            } else {
                if (basis.ruleId != state.selectedRuleId) {
                    state = state.selectRule(basis.ruleId)
                }
                state = state.receiveBasis(state.selectionRevision, basis)
            }
            updatingDerivedValue = true
            binding.additionalMinutesInput.setText("")
            binding.totalMinutesInput.setText("")
            updatingDerivedValue = false
            render()
        }

        binding.additionalMinutesInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !updatingDerivedValue) {
                state = state.editAdditionalMinutes(
                    binding.additionalMinutesInput.text?.toString().orEmpty()
                )
            }
        }
        binding.totalMinutesInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !updatingDerivedValue) {
                state = state.editTotalMinutes(
                    binding.totalMinutesInput.text?.toString().orEmpty()
                )
            }
        }
        binding.additionalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                state = state.editAdditionalMinutes(editable?.toString().orEmpty())
                binding.additionalMinutesLayout.error = null
                binding.totalMinutesLayout.error = null
                updatingDerivedValue = true
                binding.totalMinutesInput.setText(state.input.totalMinutesText)
                updatingDerivedValue = false
            }
        })
        binding.totalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                state = state.editTotalMinutes(editable?.toString().orEmpty())
                binding.additionalMinutesLayout.error = null
                binding.totalMinutesLayout.error = null
                updatingDerivedValue = true
                binding.additionalMinutesInput.setText(state.input.additionalMinutesText)
                updatingDerivedValue = false
            }
        })

        binding.rulePicker.setOnItemClickListener { _, _, position, _ ->
            val option = currentOptions.getOrNull(position) ?: return@setOnItemClickListener
            if (option.rule.id == state.selectedRuleId) return@setOnItemClickListener

            state = state.selectRule(option.rule.id)
            val revision = state.selectionRevision
            reset(null)
            scope.launch {
                val currentBasis = readBasis(option.rule.id)
                if (revision != state.selectionRevision || dialog?.isShowing != true) return@launch
                if (currentBasis != null) {
                    state = state.receiveBasis(revision, currentBasis)
                    render()
                    return@launch
                }

                val refreshedOptions = readCandidates()
                    ?: return@launch
                if (revision != state.selectionRevision || dialog?.isShowing != true) return@launch
                if (refreshedOptions.isEmpty()) {
                    dialog?.dismiss()
                    Toast.makeText(
                        context,
                        R.string.guardian_no_extra_time_rules,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                currentOptions = refreshedOptions
                adapter.clear()
                adapter.addAll(currentOptions.map(GuardianExtraTimeGrantOption::label))
                adapter.notifyDataSetChanged()
                val refreshedIndex = currentOptions.indexOfFirst {
                    it.rule.id == option.rule.id
                }.takeIf { it >= 0 } ?: 0
                binding.rulePicker.setText(currentOptions[refreshedIndex].label, false)
                reset(currentOptions[refreshedIndex].basis)
            }
        }

        render()
        val grantDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.guardian_add_time)
            .setView(binding.root)
            .setPositiveButton(R.string.guardian_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog = grantDialog
        grantDialog.setOnShowListener {
            positiveButton = grantDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            positiveButton?.isEnabled = state.basis != null
            positiveButton?.setOnClickListener {
                val basis = state.basis ?: return@setOnClickListener
                when (val submission = state.input.submit()) {
                    is GuardianExtraTimeSubmission.Invalid -> {
                        val errorLayout = if (
                            state.input.activeSource == GuardianExtraTimeInputSource.TOTAL_MINUTES
                        ) binding.totalMinutesLayout else binding.additionalMinutesLayout
                        errorLayout.error = context.getString(
                            when (submission.error) {
                                GuardianExtraTimeValidationError.TOTAL_NOT_GREATER ->
                                    R.string.guardian_total_not_greater
                                GuardianExtraTimeValidationError.INVALID_MINUTES,
                                GuardianExtraTimeValidationError.DURATION_OVERFLOW,
                                GuardianExtraTimeValidationError.TOTAL_OVERFLOW ->
                                    R.string.guardian_invalid_minutes
                            }
                        )
                    }

                    is GuardianExtraTimeSubmission.Valid -> {
                        positiveButton?.isEnabled = false
                        grantDialog.dismiss()
                        onSubmit(basis, submission.additionalMinutes)
                    }
                }
            }
        }
        GuardianOwnedDialog.show(grantDialog, onDismiss = { onDismiss(grantDialog) })
        return grantDialog
    }

    private suspend fun readBasis(ruleId: String): GuardianExtraTimeGrantBasis? = try {
        withContext(Dispatchers.IO) { readCurrentBasis(ruleId) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun readCandidates(): List<GuardianExtraTimeGrantOption>? = try {
        withContext(Dispatchers.IO) { readCurrentCandidates() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Toast.makeText(context, R.string.guardian_write_failed, Toast.LENGTH_SHORT).show()
        null
    }
}
