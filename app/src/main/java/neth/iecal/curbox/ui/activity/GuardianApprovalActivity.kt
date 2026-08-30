package neth.iecal.curbox.ui.activity

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.databinding.ActivityGuardianApprovalBinding
import neth.iecal.curbox.databinding.DialogGuardianExtraTimeBinding
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeFormState
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeSubmission
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeValidationError
import neth.iecal.curbox.domain.apprules.GuardianApprovalSelection
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianOwnedDialog
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Internal approval surface. It has no exported intent or broadcast write path. */
class GuardianApprovalActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGuardianApprovalBinding
    private val dataStore by lazy { DataStoreManager(applicationContext) }
    private var denials: List<AppRuleGuardianDenial> = emptyList()
    private var selectedRuleId: String? = null
    private var hasPassword = false
    private var grantInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityGuardianApprovalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateHomeAndFinish()
            }
        })
        denials = runCatching {
            Gson().fromJson<List<AppRuleGuardianDenial>>(
                intent.getStringExtra(EXTRA_DENIALS).orEmpty(),
                object : TypeToken<List<AppRuleGuardianDenial>>() {}.type
            )
        }.getOrNull().orEmpty()
        if (denials.isEmpty()) {
            finish()
            return
        }
        selectedRuleId = denials.first().ruleId
        render()
        lifecycleScope.launch {
            hasPassword = dataStore.settings.first().guardianAuthConfig.isConfigured
        }
    }

    private fun render() {
        val choices = binding.approvalChoices
        denials.forEachIndexed { index, denial ->
            choices.addView(RadioButton(this).apply {
                id = index + 1
                text = getString(R.string.guardian_denial_row, denial.ruleName, denial.reason)
                isChecked = index == 0
                setPadding(0, 8, 0, 8)
            })
        }
        choices.setOnCheckedChangeListener { _, checkedId ->
            selectedRuleId = GuardianApprovalSelection
                .selectedDenial(denials, checkedId - 1)
                ?.ruleId
        }
        binding.approvalAddTime.setOnClickListener { requestGrant() }
        binding.approvalSkipRule.setOnClickListener { requestSkip() }
        binding.approvalCancel.setOnClickListener { navigateHomeAndFinish() }
    }

    private fun requestGrant() {
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch {
            val currentTotalMinutes = try {
                withContext(Dispatchers.IO) { readCurrentGrantTotalMinutes(ruleId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }

            if (selectedRuleId != ruleId) {
                requestGrant()
                return@launch
            }
            showGrantDialog(ruleId, currentTotalMinutes)
        }
    }

    private suspend fun readCurrentGrantTotalMinutes(ruleId: String): Long {
        val settings = dataStore.settings.first()
        val now = System.currentTimeMillis()
        val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
        val useDayId = calculator.idAt(now)
        return AppRuleGuardianOverrides.grantMillisForRule(
            state = settings.appRuleOverrideState,
            ruleId = ruleId,
            useDayId = useDayId,
            nowMs = now,
            useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
        ) / GuardianExtraTimeFormState.MILLIS_PER_MINUTE
    }

    private fun showGrantDialog(ruleId: String, currentTotalMinutes: Long) {
        grantInProgress = false
        val dialogBinding = DialogGuardianExtraTimeBinding.inflate(layoutInflater)
        dialogBinding.currentTotal.text = getString(
            R.string.guardian_current_total,
            currentTotalMinutes
        )
        dialogBinding.totalMinutesInput.hint = currentTotalMinutes.toString()

        var formState = GuardianExtraTimeFormState.initial(currentTotalMinutes)
        var updatingDerivedValue = false
        dialogBinding.additionalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                formState = formState.editAdditionalMinutes(editable?.toString().orEmpty())
                dialogBinding.additionalMinutesLayout.error = null
                updatingDerivedValue = true
                dialogBinding.totalMinutesInput.setText(formState.totalMinutesText)
                updatingDerivedValue = false
            }
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_add_time)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.guardian_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (grantInProgress) return@setOnClickListener
                when (val submission = formState.submit()) {
                    is GuardianExtraTimeSubmission.Invalid -> {
                        dialogBinding.additionalMinutesLayout.error = getString(
                            when (submission.error) {
                                GuardianExtraTimeValidationError.INVALID_MINUTES,
                                GuardianExtraTimeValidationError.DURATION_OVERFLOW,
                                GuardianExtraTimeValidationError.TOTAL_OVERFLOW ->
                                    R.string.guardian_invalid_minutes
                            }
                        )
                    }

                    is GuardianExtraTimeSubmission.Valid -> {
                        grantInProgress = true
                        dialog.getButton(
                            android.content.DialogInterface.BUTTON_POSITIVE
                        ).isEnabled = false
                        dialog.dismiss()
                        authenticateThen { password ->
                            writeGrant(password, ruleId, submission.additionalMinutes)
                        }
                    }
                }
            }
        }
        GuardianOwnedDialog.show(dialog)
    }

    private fun requestSkip() {
        val labels = arrayOf(
            getString(R.string.guardian_skip_15_minutes),
            getString(R.string.guardian_skip_30_minutes),
            getString(R.string.guardian_skip_until_reset)
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_skip_rule)
            .setSingleChoiceItems(labels, 0) { dialog, which ->
                dialog.dismiss()
                authenticateThen { password -> writeSkip(password, which) }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        GuardianOwnedDialog.show(dialog)
    }

    private fun authenticateThen(onAuthenticated: (String) -> Unit) {
        if (!hasPassword) {
            onAuthenticated("")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.guardian_password_hint)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_enter_password)
            .setView(input)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                lifecycleScope.launch {
                    val password = input.text.toString()
                    if (dataStore.guardianPasswordIsValid(password)) {
                        onAuthenticated(password)
                    } else {
                        toast(R.string.guardian_wrong_password)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        GuardianOwnedDialog.show(dialog)
    }

    private fun writeGrant(password: String, ruleId: String, minutes: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val settings = dataStore.settings.first()
                val now = System.currentTimeMillis()
                val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
                val useDayId = calculator.idAt(now)
                dataStore.grantAppRuleTime(password, ruleId, useDayId, minutes, now)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (success) {
                    finishAndLaunch()
                } else {
                    grantInProgress = false
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun writeSkip(password: String, option: Int) {
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = dataStore.settings.first()
            val now = System.currentTimeMillis()
            val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
            val useDayId = calculator.idAt(now)
            val nextReset = calculator.windowFor(useDayId).last + 1L
            val selected = when (option) {
                0 -> now + Duration.ofMinutes(15).toMillis()
                1 -> now + Duration.ofMinutes(30).toMillis()
                else -> nextReset
            }
            val success = dataStore.skipAppRuleUntil(
                password, ruleId, useDayId, selected, nextReset, now
            )
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (success) finishAndLaunch() else toast(R.string.guardian_write_failed)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            finish()
        }
    }

    private fun navigateHomeAndFinish() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finishAffinity()
    }

    private fun finishAndLaunch() {
        intent.getStringExtra(EXTRA_PACKAGE)?.let { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
        finish()
    }

    private fun toast(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_DENIALS = "app_rule_denials_json"
        const val EXTRA_PACKAGE = "launch_package"
    }
}
