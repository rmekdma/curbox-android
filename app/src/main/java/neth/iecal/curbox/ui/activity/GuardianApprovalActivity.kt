package neth.iecal.curbox.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.databinding.ActivityGuardianApprovalBinding
import neth.iecal.curbox.databinding.DialogGuardianAccumulatedTimeBinding
import neth.iecal.curbox.databinding.DialogGuardianExtraTimeBinding
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeFormState
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeSubmission
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeValidationError
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeFormState
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeInputSource
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
    private data class GuardianApprovalPayload(
        val packageName: String,
        val denials: List<AppRuleGuardianDenial>
    )

    private lateinit var binding: ActivityGuardianApprovalBinding
    private val dataStore by lazy { DataStoreManager(applicationContext) }
    private var denials: List<AppRuleGuardianDenial> = emptyList()
    private var selectedRuleId: String? = null
    private var hasPassword = false
    private var grantInProgress = false
    private var guardianClosedBroadcastSent = false
    private var guardianStateReceiverRegistered = false

    private val guardianStateRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_STATE_REQUEST ||
                guardianClosedBroadcastSent || isFinishing
            ) return
            val packageName = this@GuardianApprovalActivity.intent
                .getStringExtra(EXTRA_PACKAGE)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return
            sendBroadcast(
                Intent(INTENT_ACTION_OPENED)
                    .setPackage(this@GuardianApprovalActivity.packageName)
                    .putExtra(EXTRA_GUARDIAN_PACKAGE, packageName)
            )
        }
    }

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
        val payload = readValidatedPayload(intent)
        if (payload == null) {
            finish()
            return
        }
        denials = payload.denials
        ContextCompat.registerReceiver(
            this,
            guardianStateRequestReceiver,
            IntentFilter(INTENT_ACTION_STATE_REQUEST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        guardianStateReceiverRegistered = true
        selectedRuleId = denials.first().ruleId
        render()
        lifecycleScope.launch {
            hasPassword = dataStore.settings.first().guardianAuthConfig.isConfigured
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val payload = readValidatedPayload(intent) ?: return
        if (isFinishing) return
        setIntent(intent)
        denials = payload.denials
        selectedRuleId = denials.first().ruleId
        render()
    }

    private fun readValidatedPayload(sourceIntent: Intent): GuardianApprovalPayload? {
        val packageName = runCatching {
            sourceIntent.getStringExtra(EXTRA_PACKAGE)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.getOrNull() ?: return null
        val parsedDenials = runCatching {
            Gson().fromJson<List<AppRuleGuardianDenial?>>(
                sourceIntent.getStringExtra(EXTRA_DENIALS).orEmpty(),
                object : TypeToken<List<AppRuleGuardianDenial?>>() {}.type
            )
        }.getOrNull() ?: return null
        if (parsedDenials.isEmpty()) return null

        val validatedDenials = parsedDenials.map { denial ->
            runCatching {
                denial?.takeIf { it.ruleId.isNotBlank() }
            }.getOrNull()
        }
        if (validatedDenials.any { it == null }) return null

        return GuardianApprovalPayload(
            packageName = packageName,
            denials = validatedDenials.filterNotNull()
        )
    }

    private fun render() {
        val choices = binding.approvalChoices
        choices.removeAllViews()
        denials.forEachIndexed { index, denial ->
            choices.addView(RadioButton(this).apply {
                id = index + 1
                text = GuardianApprovalTextFormatter.formatDenial(this@GuardianApprovalActivity, denial)
                isChecked = index == 0
                setPadding(0, 8, 0, 8)
            })
        }
        choices.setOnCheckedChangeListener { _, checkedId ->
            selectedRuleId = GuardianApprovalSelection
                .selectedDenial(denials, checkedId - 1)
                ?.ruleId
            updateAccumulatedButton(selectedRuleId)
        }
        binding.approvalAddTime.setOnClickListener { requestGrant() }
        binding.approvalUseAccumulatedTime.setOnClickListener { requestAccumulatedGrant() }
        binding.approvalSkipRule.setOnClickListener { requestSkip() }
        binding.approvalCancel.setOnClickListener { navigateHomeAndFinish() }
        updateAccumulatedButton(selectedRuleId)
    }

    private fun updateAccumulatedButton(ruleId: String?) {
        if (ruleId == null) {
            binding.approvalUseAccumulatedTime.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val state = resolveAccumulatedState(ruleId)
            if (selectedRuleId != ruleId) return@launch

            if (state.isVisible) {
                binding.approvalUseAccumulatedTime.visibility = View.VISIBLE
                binding.approvalUseAccumulatedTime.text = getString(
                    R.string.guardian_use_accumulated_time,
                    state.accumulatedMinutes
                )
            } else {
                binding.approvalUseAccumulatedTime.visibility = View.GONE
            }
        }
    }

    private suspend fun resolveAccumulatedState(
        ruleId: String
    ): GuardianApprovalSelection.AccumulatedTimeButtonState {
        val settings = dataStore.settings.first()
        val now = System.currentTimeMillis()
        val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
        val useDayId = calculator.idAt(now)
        val rule = settings.appRuleSnapshot.appRules.find { it.id == ruleId }
        val pool = settings.appRuleRolloverState.pools[ruleId]
        return GuardianApprovalSelection.resolveAccumulatedButtonState(rule, pool, useDayId)
    }

    private fun requestGrant() {
        if (grantInProgress) return
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
            useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
            includeAccumulatedGrants = false
        ) / GuardianExtraTimeFormState.MILLIS_PER_MINUTE
    }

    private fun showGrantDialog(ruleId: String, currentTotalMinutes: Long) {
        val dialogBinding = DialogGuardianExtraTimeBinding.inflate(layoutInflater)
        dialogBinding.currentTotal.text = getString(
            R.string.guardian_current_total,
            currentTotalMinutes
        )
        dialogBinding.totalMinutesLayout.placeholderText = currentTotalMinutes.toString()
        dialogBinding.additionalMinutesInput.setSelectAllOnFocus(true)
        dialogBinding.totalMinutesInput.setSelectAllOnFocus(true)
        var formState = GuardianExtraTimeFormState.initial(currentTotalMinutes)
        var updatingDerivedValue = false
        dialogBinding.additionalMinutesInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (!updatingDerivedValue) {
                formState = formState.editAdditionalMinutes(
                    dialogBinding.additionalMinutesInput.text?.toString().orEmpty()
                )
            }
        }
        dialogBinding.totalMinutesInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (!updatingDerivedValue) {
                formState = formState.editTotalMinutes(
                    dialogBinding.totalMinutesInput.text?.toString().orEmpty()
                )
            }
        }

        dialogBinding.additionalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                formState = formState.editAdditionalMinutes(editable?.toString().orEmpty())
                dialogBinding.additionalMinutesLayout.error = null
                dialogBinding.totalMinutesLayout.error = null
                updatingDerivedValue = true
                dialogBinding.totalMinutesInput.setText(formState.totalMinutesText)
                updatingDerivedValue = false
            }
        })
        dialogBinding.totalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                formState = formState.editTotalMinutes(editable?.toString().orEmpty())
                dialogBinding.additionalMinutesLayout.error = null
                dialogBinding.totalMinutesLayout.error = null
                updatingDerivedValue = true
                dialogBinding.additionalMinutesInput.setText(formState.additionalMinutesText)
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
                        val errorLayout = if (
                            formState.activeSource == GuardianExtraTimeInputSource.TOTAL_MINUTES
                        ) {
                            dialogBinding.totalMinutesLayout
                        } else {
                            dialogBinding.additionalMinutesLayout
                        }
                        errorLayout.error = getString(
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
                        grantInProgress = true
                        dialog.getButton(
                            android.content.DialogInterface.BUTTON_POSITIVE
                        ).isEnabled = false
                        dialog.dismiss()
                        authenticateThen(
                            ruleId = ruleId,
                            onCancelled = { grantInProgress = false },
                            onAuthenticated = { capturedRuleId, password ->
                                writeGrant(capturedRuleId, password, submission.additionalMinutes)
                            }
                        )
                    }
                }
            }
        }
        GuardianOwnedDialog.show(dialog)
    }

    private fun requestAccumulatedGrant() {
        if (grantInProgress) return
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch {
            val state = resolveAccumulatedState(ruleId)

            if (!state.isVisible || state.accumulatedMinutes <= 0L) {
                updateAccumulatedButton(selectedRuleId)
                return@launch
            }

            if (selectedRuleId != ruleId) {
                requestAccumulatedGrant()
                return@launch
            }
            showAccumulatedGrantDialog(ruleId, state.accumulatedMinutes)
        }
    }

    private fun showAccumulatedGrantDialog(
        ruleId: String,
        totalAccumulatedMinutes: Long
    ) {
        val dialogBinding = DialogGuardianAccumulatedTimeBinding.inflate(layoutInflater)
        dialogBinding.accumulatedTotalDesc.text = getString(
            R.string.guardian_accumulated_available_desc,
            totalAccumulatedMinutes
        )
        val formState = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes)
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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_use_accumulated_time_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.guardian_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.accumulatedMinutesInput.requestFocus()
            dialogBinding.accumulatedMinutesInput.selectAll()

            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (grantInProgress) return@setOnClickListener
                when (val submission = currentFormState.submit()) {
                    is GuardianAccumulatedTimeSubmission.Invalid -> {
                        dialogBinding.accumulatedMinutesLayout.error = getString(
                            when (submission.error) {
                                GuardianAccumulatedTimeValidationError.INVALID_MINUTES ->
                                    R.string.guardian_invalid_minutes
                                GuardianAccumulatedTimeValidationError.EXCEEDS_ACCUMULATED ->
                                    R.string.guardian_exceeds_accumulated_minutes
                            }
                        )
                    }

                    is GuardianAccumulatedTimeSubmission.Valid -> {
                        grantInProgress = true
                        dialog.getButton(
                            android.content.DialogInterface.BUTTON_POSITIVE
                        ).isEnabled = false
                        dialog.dismiss()
                        authenticateThen(
                            ruleId = ruleId,
                            onCancelled = { grantInProgress = false },
                            onAuthenticated = { capturedRuleId, password ->
                                writeAccumulatedGrant(
                                    ruleId = capturedRuleId,
                                    password = password,
                                    minutes = submission.approvedMinutes
                                )
                            }
                        )
                    }
                }
            }
        }
        GuardianOwnedDialog.show(dialog)
    }

    private fun requestSkip() {
        val ruleId = selectedRuleId ?: return
        val labels = arrayOf(
            getString(R.string.guardian_skip_15_minutes),
            getString(R.string.guardian_skip_30_minutes),
            getString(R.string.guardian_skip_until_reset)
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_skip_rule)
            .setSingleChoiceItems(labels, 0) { dialog, which ->
                dialog.dismiss()
                authenticateThen(ruleId) { capturedRuleId, password ->
                    writeSkip(capturedRuleId, password, which)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        GuardianOwnedDialog.show(dialog)
    }

    private fun authenticateThen(
        ruleId: String,
        onCancelled: () -> Unit = {},
        onAuthenticated: (ruleId: String, password: String) -> Unit
    ) {
        if (!hasPassword) {
            onAuthenticated(ruleId, "")
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
                        onAuthenticated(ruleId, password)
                    } else {
                        onCancelled()
                        toast(R.string.guardian_wrong_password)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()
        GuardianOwnedDialog.show(dialog, onCancel = onCancelled)
    }

    private fun writeGrant(ruleId: String, password: String, minutes: Long) {
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
            withContext(Dispatchers.Main) {
                if (success) {
                    finishAndLaunch()
                } else {
                    grantInProgress = false
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun writeAccumulatedGrant(
        ruleId: String,
        password: String,
        minutes: Long
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val settings = dataStore.settings.first()
                val now = System.currentTimeMillis()
                val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
                val useDayId = calculator.idAt(now)
                dataStore.approveAccumulatedTime(
                    password = password,
                    ruleId = ruleId,
                    useDayId = useDayId,
                    approvedMinutes = minutes,
                    grantedAtMs = now
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    finishAndLaunch()
                } else {
                    grantInProgress = false
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun writeSkip(ruleId: String, password: String, option: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
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
                dataStore.skipAppRuleUntil(
                    password, ruleId, useDayId, selected, nextReset, now
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (success) finishAndLaunch() else toast(R.string.guardian_write_failed)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            notifyGuardianClosed()
            finish()
        }
    }

    override fun onDestroy() {
        if (guardianStateReceiverRegistered) {
            runCatching { unregisterReceiver(guardianStateRequestReceiver) }
            guardianStateReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun notifyGuardianClosed() {
        if (guardianClosedBroadcastSent) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        guardianClosedBroadcastSent = true
        sendBroadcast(
            Intent(INTENT_ACTION_CLOSED)
                .setPackage(this.packageName)
                .putExtra(EXTRA_GUARDIAN_PACKAGE, packageName)
        )
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
        const val INTENT_ACTION_CLOSED = "neth.iecal.curbox.guardian.approval.closed"
        const val INTENT_ACTION_OPENED = "neth.iecal.curbox.guardian.approval.opened"
        const val INTENT_ACTION_STATE_REQUEST = "neth.iecal.curbox.guardian.approval.state_request"
        const val EXTRA_GUARDIAN_PACKAGE = "guardian_package"
    }
}
