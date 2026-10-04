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
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.databinding.ActivityGuardianApprovalBinding
import neth.iecal.curbox.databinding.DialogGuardianAccumulatedTimeBinding
import neth.iecal.curbox.databinding.DialogGuardianExtraTimeBinding
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeFormState
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeSubmission
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeValidationError
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeInputSource
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeSubmission
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeValidationError
import neth.iecal.curbox.domain.apprules.GuardianApprovalSelection
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantFormState
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeRulePicker
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianOwnedDialog
import neth.iecal.curbox.utils.GuardianExtraTimeGrantWrite
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

    private data class GuardianGrantRuleOption(
        val rule: AppRule,
        val basis: GuardianExtraTimeGrantBasis
    ) {
        val label: String
            get() = rule.name.takeIf(String::isNotBlank) ?: rule.id
    }

    private lateinit var binding: ActivityGuardianApprovalBinding
    private val dataStore by lazy { DataStoreManager(applicationContext) }
    private var denials: List<AppRuleGuardianDenial> = emptyList()
    private var targetPackageName: String = ""
    private var selectedRuleId: String? = null
    private var hasPassword = false
    private var grantInProgress = false
    private var grantPickerLoading = false
    private var grantDialogOpen = false
    private var guardianClosedBroadcastSent = false
    private var guardianStateReceiverRegistered = false
    private var closeReason: String = REASON_INTERRUPTED

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
        targetPackageName = payload.packageName
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
        targetPackageName = payload.packageName
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
        updateGuardianGrantButton()
        updateAccumulatedButton(selectedRuleId)
    }

    private fun updateGuardianGrantButton() {
        binding.approvalAddTime.visibility = View.GONE
        lifecycleScope.launch {
            val allowed = dataStore.settings.first().appRuleSnapshot.appRules.any {
                it.isActive && it.guardianExtraTimeAllowed
            }
            binding.approvalAddTime.visibility = if (allowed) View.VISIBLE else View.GONE
        }
    }

    private fun updateAccumulatedButton(ruleId: String?) {
        binding.approvalUseAccumulatedTime.visibility = View.GONE
        if (ruleId == null) {
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

    private fun requestGrant(preferredRuleId: String? = null) {
        if (grantInProgress || grantPickerLoading || grantDialogOpen) return
        val packageName = targetPackageName.takeIf(String::isNotBlank) ?: return
        grantPickerLoading = true
        lifecycleScope.launch {
            val options = try {
                withContext(Dispatchers.IO) { readCurrentGrantCandidates(packageName) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                grantPickerLoading = false
                toast(R.string.guardian_write_failed)
                return@launch
            }
            grantPickerLoading = false
            if (options.isEmpty()) {
                toast(R.string.guardian_no_extra_time_rules)
                return@launch
            }
            val selectedIndex = options.indexOfFirst { it.rule.id == preferredRuleId }
                .takeIf { it >= 0 }
                ?: 0
            grantDialogOpen = true
            showGrantDialog(options, selectedIndex)
        }
    }

    private suspend fun readCurrentGrantCandidates(
        packageName: String
    ): List<GuardianGrantRuleOption> {
        val settings = dataStore.settings.first()
        val now = System.currentTimeMillis()
        val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
        val useDayId = calculator.idAt(now)
        val database = AppDatabase.getInstance(applicationContext)
        val sessions = RoomCurrentUseDaySessionRepository(database.foregroundSessionDao())
            .sessionsForUseDay(useDayId, settings.useDayGenerationStartedAtMs)
        val ruleSnapshot = settings.appRuleSnapshot.normalized()
        val candidates = GuardianExtraTimeRulePicker.candidates(
            snapshot = ruleSnapshot,
            packageName = packageName,
            useDayId = useDayId,
            sessions = sessions,
            nowMs = now,
            resetTime = settings.useDayResetTime,
            useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
            overrides = settings.appRuleOverrideState
        )
        return candidates.mapNotNull { rule ->
            GuardianExtraTimeGrantBasis.capture(settings, rule.id, now)?.let { basis ->
                GuardianGrantRuleOption(rule, basis)
            }
        }
    }

    private suspend fun readGrantBasis(ruleId: String): GuardianExtraTimeGrantBasis? {
        val settings = dataStore.settings.first()
        return GuardianExtraTimeGrantBasis.capture(
            settings = settings,
            ruleId = ruleId,
            nowMs = System.currentTimeMillis()
        )
    }

    private fun showGrantDialog(
        options: List<GuardianGrantRuleOption>,
        selectedIndex: Int
    ) {
        val dialogBinding = DialogGuardianExtraTimeBinding.inflate(layoutInflater)
        var currentOptions = options
        var grantFormState = GuardianExtraTimeGrantFormState.initial(options[selectedIndex].basis)
        var positiveButton: android.widget.Button? = null
        var grantDialog: AlertDialog? = null
        val ruleAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            currentOptions.map(GuardianGrantRuleOption::label)
        )
        dialogBinding.rulePicker.setAdapter(ruleAdapter)
        dialogBinding.rulePicker.setText(currentOptions[selectedIndex].label, false)
        dialogBinding.currentTotal.text = getString(
            R.string.guardian_current_total,
            grantFormState.input.currentTotalMinutes
        )
        dialogBinding.totalMinutesLayout.placeholderText =
            grantFormState.input.currentTotalMinutes.toString()
        dialogBinding.additionalMinutesInput.setSelectAllOnFocus(true)
        dialogBinding.totalMinutesInput.setSelectAllOnFocus(true)
        var updatingDerivedValue = false

        fun renderGrantForm() {
            val basis = grantFormState.basis
            val currentTotalMinutes = grantFormState.input.currentTotalMinutes
            dialogBinding.currentTotal.text = getString(
                R.string.guardian_current_total,
                currentTotalMinutes
            )
            dialogBinding.totalMinutesLayout.placeholderText =
                currentTotalMinutes.toString()
            dialogBinding.additionalMinutesLayout.error = null
            dialogBinding.totalMinutesLayout.error = null
            positiveButton?.isEnabled = basis != null
        }

        fun resetGrantForm(basis: GuardianExtraTimeGrantBasis?) {
            if (basis == null) {
                grantFormState = grantFormState.clearInputs()
            } else {
                if (basis.ruleId != grantFormState.selectedRuleId) {
                    grantFormState = grantFormState.selectRule(basis.ruleId)
                }
                grantFormState = grantFormState.receiveBasis(
                    grantFormState.selectionRevision,
                    basis
                )
            }
            updatingDerivedValue = true
            dialogBinding.additionalMinutesInput.setText("")
            dialogBinding.totalMinutesInput.setText("")
            updatingDerivedValue = false
            renderGrantForm()
        }

        dialogBinding.additionalMinutesInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (!updatingDerivedValue) {
                grantFormState = grantFormState.editAdditionalMinutes(
                    dialogBinding.additionalMinutesInput.text?.toString().orEmpty()
                )
            }
        }
        dialogBinding.totalMinutesInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (!updatingDerivedValue) {
                grantFormState = grantFormState.editTotalMinutes(
                    dialogBinding.totalMinutesInput.text?.toString().orEmpty()
                )
            }
        }

        dialogBinding.additionalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                grantFormState = grantFormState.editAdditionalMinutes(editable?.toString().orEmpty())
                dialogBinding.additionalMinutesLayout.error = null
                dialogBinding.totalMinutesLayout.error = null
                updatingDerivedValue = true
                dialogBinding.totalMinutesInput.setText(grantFormState.input.totalMinutesText)
                updatingDerivedValue = false
            }
        })
        dialogBinding.totalMinutesInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updatingDerivedValue) return
                grantFormState = grantFormState.editTotalMinutes(editable?.toString().orEmpty())
                dialogBinding.additionalMinutesLayout.error = null
                dialogBinding.totalMinutesLayout.error = null
                updatingDerivedValue = true
                dialogBinding.additionalMinutesInput.setText(
                    grantFormState.input.additionalMinutesText
                )
                updatingDerivedValue = false
            }
        })

        dialogBinding.rulePicker.setOnItemClickListener { _, _, position, _ ->
            val option = currentOptions.getOrNull(position) ?: return@setOnItemClickListener
            if (option.rule.id == grantFormState.selectedRuleId) return@setOnItemClickListener

            grantFormState = grantFormState.selectRule(option.rule.id)
            val revision = grantFormState.selectionRevision
            resetGrantForm(null)
            lifecycleScope.launch {
                val currentBasis = try {
                    withContext(Dispatchers.IO) { readGrantBasis(option.rule.id) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (revision != grantFormState.selectionRevision) return@launch
                if (currentBasis != null) {
                    grantFormState = grantFormState.receiveBasis(revision, currentBasis)
                    renderGrantForm()
                    return@launch
                }

                val refreshedOptions = try {
                    withContext(Dispatchers.IO) {
                        readCurrentGrantCandidates(targetPackageName)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
                if (revision != grantFormState.selectionRevision) return@launch
                if (refreshedOptions.isEmpty()) {
                    grantDialog?.dismiss()
                    toast(R.string.guardian_no_extra_time_rules)
                    return@launch
                }

                currentOptions = refreshedOptions
                ruleAdapter.clear()
                ruleAdapter.addAll(currentOptions.map(GuardianGrantRuleOption::label))
                ruleAdapter.notifyDataSetChanged()
                val refreshedIndex = currentOptions.indexOfFirst {
                    it.rule.id == option.rule.id
                }.takeIf { it >= 0 } ?: 0
                dialogBinding.rulePicker.setText(currentOptions[refreshedIndex].label, false)
                resetGrantForm(currentOptions[refreshedIndex].basis)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_add_time)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.guardian_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        grantDialog = dialog
        dialog.setOnDismissListener { grantDialogOpen = false }
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            positiveButton?.isEnabled = grantFormState.basis != null
            positiveButton?.setOnClickListener {
                if (grantInProgress) return@setOnClickListener
                val basis = grantFormState.basis ?: return@setOnClickListener
                when (val submission = grantFormState.input.submit()) {
                    is GuardianExtraTimeSubmission.Invalid -> {
                        val errorLayout = if (
                            grantFormState.input.activeSource == GuardianExtraTimeInputSource.TOTAL_MINUTES
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
                            ruleId = basis.ruleId,
                            onCancelled = { grantInProgress = false },
                            onAuthenticated = { _, password ->
                                writeGrant(basis, password, submission.additionalMinutes)
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

    private fun writeGrant(
        basis: GuardianExtraTimeGrantBasis,
        password: String,
        minutes: Long
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                dataStore.grantAppRuleTime(password, basis, minutes)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                GuardianExtraTimeGrantWrite.Result.Rejected
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    GuardianExtraTimeGrantWrite.Result.Stored -> finishAndLaunch()
                    is GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation -> {
                        grantInProgress = false
                        toast(R.string.guardian_grant_basis_changed)
                        requestGrant(preferredRuleId = basis.ruleId)
                    }
                    GuardianExtraTimeGrantWrite.Result.Unavailable -> {
                        grantInProgress = false
                        toast(R.string.guardian_grant_rule_changed)
                        requestGrant()
                    }
                    GuardianExtraTimeGrantWrite.Result.Rejected -> {
                        grantInProgress = false
                        toast(R.string.guardian_write_failed)
                    }
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
                .putExtra(EXTRA_CLOSE_REASON, closeReason)
        )
    }

    private fun navigateHomeAndFinish() {
        closeReason = REASON_CANCELLED
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finishAffinity()
    }

    private fun finishAndLaunch() {
        closeReason = REASON_GRANTED
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
        const val EXTRA_CLOSE_REASON = "guardian_close_reason"
        const val REASON_GRANTED = "granted"
        const val REASON_CANCELLED = "cancelled"
        const val REASON_INTERRUPTED = "interrupted"
    }
}
