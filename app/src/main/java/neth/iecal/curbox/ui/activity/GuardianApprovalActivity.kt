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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt
import neth.iecal.curbox.databinding.ActivityGuardianApprovalBinding
import neth.iecal.curbox.databinding.DialogGuardianAccumulatedTimeBinding
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeFormState
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeSubmission
import neth.iecal.curbox.domain.apprules.GuardianAccumulatedTimeValidationError
import neth.iecal.curbox.domain.apprules.GuardianApprovalSelection
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantCandidate
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianOwnedDialog
import neth.iecal.curbox.utils.GuardianExtraTimeGrantWrite
import neth.iecal.curbox.utils.GuardianExtraTimeGrantQueryFactory
import java.time.Duration
import java.util.UUID
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
    private val grantQuery by lazy {
        GuardianExtraTimeGrantQueryFactory.create(applicationContext, dataStore)
    }
    private var denials: List<AppRuleGuardianDenial> = emptyList()
    private var targetPackageName: String = ""
    private var selectedRuleId: String? = null
    private var hasPassword = false
    private var grantInProgress = false
    private var grantPickerLoading = false
    private var grantDialogOpen = false
    private var grantFormDialog: AlertDialog? = null
    private val ownedDialogs = mutableSetOf<AlertDialog>()
    private var isDestroying = false
    private var guardianClosedBroadcastSent = false
    private var guardianStateReceiverRegistered = false
    private var closeReason: String = REASON_INTERRUPTED
    private var screenRequestId: String = ""
    private var confirmationOperationId: String? = null
    private var confirmationCheckId: String? = null
    private var confirmationReceipt: GuardianApprovalWorkReceipt? = null
    private var confirmationChecking = false
    private var confirmationFailed = false
    private var legacyOperationId: String? = null

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
                    .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
            )
        }
    }

    private val guardianConfirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_CONFIRMATION_RESULT) return
            handleConfirmationResult(intent)
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
        screenRequestId = intent.getStringExtra(EXTRA_SCREEN_REQUEST_ID)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        ContextCompat.registerReceiver(
            this,
            guardianStateRequestReceiver,
            IntentFilter(INTENT_ACTION_STATE_REQUEST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        guardianStateReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            guardianConfirmationReceiver,
            IntentFilter(INTENT_ACTION_CONFIRMATION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        screenRequestId = intent.getStringExtra(EXTRA_SCREEN_REQUEST_ID)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        confirmationOperationId = null
        confirmationCheckId = null
        confirmationReceipt = null
        confirmationChecking = false
        confirmationFailed = false
        legacyOperationId = null
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
        binding.approvalConfirmationRetry.setOnClickListener { retryApprovalConfirmation() }
        binding.approvalCancel.visibility = View.GONE
        renderConfirmationState()
        updateGuardianGrantButton()
        updateAccumulatedButton(selectedRuleId)
    }

    private fun updateGuardianGrantButton() {
        binding.approvalAddTime.visibility = View.GONE
        lifecycleScope.launch {
            val allowed = dataStore.settings.first().appRuleSnapshot.appRules.any {
                it.isActive && it.guardianExtraTimeAllowed
            }
            if (!canHandleCallbacks()) return@launch
            binding.approvalAddTime.visibility = if (allowed && !isConfirmationUiBlocking()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun updateAccumulatedButton(ruleId: String?) {
        binding.approvalUseAccumulatedTime.visibility = View.GONE
        if (ruleId == null) {
            return
        }
        lifecycleScope.launch {
            val state = resolveAccumulatedState(ruleId)
            if (!canHandleCallbacks()) return@launch
            if (selectedRuleId != ruleId) return@launch

            if (state.isVisible && !isConfirmationUiBlocking()) {
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
        if (!canHandleCallbacks() || isConfirmationUiBlocking() || grantInProgress || grantPickerLoading ||
            grantDialogOpen || grantFormDialog?.isShowing == true
        ) return
        if (targetPackageName.isBlank()) return
        grantPickerLoading = true
        lifecycleScope.launch {
            val options = try {
                grantQuery.candidates()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                grantPickerLoading = false
                if (canHandleCallbacks()) toast(R.string.guardian_write_failed)
                return@launch
            }
            grantPickerLoading = false
            if (!canHandleCallbacks()) return@launch
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

    private fun showGrantDialog(
        options: List<GuardianExtraTimeGrantCandidate>,
        selectedIndex: Int
    ) {
        if (!canHandleCallbacks()) {
            grantDialogOpen = false
            return
        }
        val dialog = GuardianExtraTimeGrantFormDialog(
            context = this,
            inflater = layoutInflater,
            scope = lifecycleScope,
            readCurrentBasis = grantQuery::currentBasis,
            readCurrentCandidates = grantQuery::candidates,
            onSubmit = { basis, minutes ->
                if (canHandleCallbacks() && !grantInProgress) {
                    grantInProgress = true
                    authenticateThen(
                        ruleId = basis.ruleId,
                        onCancelled = { grantInProgress = false },
                        onAuthenticated = { _, password -> writeGrant(basis, password, minutes) }
                    )
                }
            },
            onDismiss = { dismissedDialog ->
                grantDialogOpen = false
                ownedDialogs.remove(dismissedDialog)
                if (grantFormDialog === dismissedDialog) grantFormDialog = null
            }
        ).show(options, selectedIndex)
        grantFormDialog = dialog
        if (dialog != null) {
            ownedDialogs += dialog
        } else {
            grantDialogOpen = false
        }
    }

    private fun requestAccumulatedGrant() {
        if (!canHandleCallbacks() || isConfirmationUiBlocking() || grantInProgress) return
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch {
            val state = resolveAccumulatedState(ruleId)
            if (!canHandleCallbacks()) return@launch

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
        if (!canHandleCallbacks()) return
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
        showOwnedDialog(dialog)
    }

    private fun requestSkip() {
        if (!canHandleCallbacks() || isConfirmationUiBlocking() || grantInProgress) return
        val ruleId = selectedRuleId ?: return
        val labels = arrayOf(
            getString(R.string.guardian_skip_15_minutes),
            getString(R.string.guardian_skip_30_minutes),
            getString(R.string.guardian_skip_until_reset)
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_skip_rule)
            .setSingleChoiceItems(labels, 0) { dialog, which ->
                if (!canHandleCallbacks() || grantInProgress) return@setSingleChoiceItems
                grantInProgress = true
                dialog.dismiss()
                authenticateThen(
                    ruleId = ruleId,
                    onCancelled = { grantInProgress = false }
                ) { capturedRuleId, password -> writeSkip(capturedRuleId, password, which) }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showOwnedDialog(dialog)
    }

    private fun authenticateThen(
        ruleId: String,
        onCancelled: () -> Unit = {},
        onAuthenticated: (ruleId: String, password: String) -> Unit
    ) {
        if (!canHandleCallbacks()) return
        var completed = false
        fun cancelAuthentication() {
            if (completed) return
            completed = true
            onCancelled()
        }
        fun completeAuthentication(password: String) {
            if (completed || !canHandleCallbacks()) return
            completed = true
            onAuthenticated(ruleId, password)
        }

        if (!hasPassword) {
            completeAuthentication("")
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
                    if (completed || !canHandleCallbacks()) return@launch
                    val isValid = dataStore.guardianPasswordIsValid(password)
                    if (completed || !canHandleCallbacks()) return@launch
                    if (isValid) {
                        completeAuthentication(password)
                    } else {
                        cancelAuthentication()
                        toast(R.string.guardian_wrong_password)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> cancelAuthentication() }
            .create()
        showOwnedDialog(dialog, onCancel = ::cancelAuthentication)
    }

    private fun writeGrant(
        basis: GuardianExtraTimeGrantBasis,
        password: String,
        minutes: Long
    ) {
        val operationId = UUID.randomUUID().toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                dataStore.grantAppRuleTime(password, basis, minutes)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                GuardianExtraTimeGrantWrite.Result.Rejected
            }
            withContext(Dispatchers.Main) {
                if (!canHandleCallbacks()) return@withContext
                when (result) {
                    is GuardianExtraTimeGrantWrite.Result.StoredWithReceipt -> {
                        grantInProgress = false
                        beginApprovalConfirmation(
                            operationId,
                            GuardianApprovalWorkReceipt.DirectGrant(
                                grant = result.receipt,
                                useDayGenerationStartedAtMs = basis.useDayGenerationStartedAtMs
                            )
                        )
                    }
                    GuardianExtraTimeGrantWrite.Result.Stored -> {
                        grantInProgress = false
                        toast(R.string.guardian_write_failed)
                    }
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
        val operationId = UUID.randomUUID().toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val receipt = try {
                val settings = dataStore.settings.first()
                val now = System.currentTimeMillis()
                val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
                val useDayId = calculator.idAt(now)
                dataStore.approveAccumulatedTimeWithReceipt(
                    password = password,
                    ruleId = ruleId,
                    useDayId = useDayId,
                    approvedMinutes = minutes,
                    grantedAtMs = now
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (!canHandleCallbacks()) return@withContext
                if (receipt != null) {
                    grantInProgress = false
                    beginApprovalConfirmation(operationId, receipt)
                } else {
                    grantInProgress = false
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun writeSkip(ruleId: String, password: String, option: Int) {
        val operationId = UUID.randomUUID().toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val receipt = try {
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
                dataStore.skipAppRuleUntilWithReceipt(
                    password = password,
                    ruleId = ruleId,
                    useDayId = useDayId,
                    selectedUntilMs = selected,
                    nextResetAtMs = nextReset,
                    nowMs = now
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (!canHandleCallbacks()) return@withContext
                if (receipt != null) {
                    grantInProgress = false
                    beginApprovalConfirmation(operationId, receipt)
                } else {
                    grantInProgress = false
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun beginApprovalConfirmation(
        operationId: String,
        receipt: GuardianApprovalWorkReceipt
    ) {
        confirmationOperationId = operationId
        confirmationReceipt = receipt
        val checkId = UUID.randomUUID().toString()
        confirmationCheckId = checkId
        confirmationChecking = true
        confirmationFailed = false
        renderConfirmationState()
        sendApprovalConfirmationRequest(
            action = INTENT_ACTION_APPROVAL_STORED,
            operationId = operationId,
            checkId = checkId,
            receipt = receipt
        )
    }

    private fun retryApprovalConfirmation() {
        if (!canHandleCallbacks() || confirmationChecking) return
        val operationId = confirmationOperationId ?: return
        val receipt = confirmationReceipt ?: return
        val checkId = UUID.randomUUID().toString()
        confirmationCheckId = checkId
        confirmationChecking = true
        confirmationFailed = false
        renderConfirmationState()
        sendApprovalConfirmationRequest(
            action = INTENT_ACTION_APPROVAL_CHECK_RETRY,
            operationId = operationId,
            checkId = checkId,
            receipt = receipt
        )
    }

    private fun sendApprovalConfirmationRequest(
        action: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt
    ) {
        val request = Intent(action)
            .setPackage(packageName)
            .putExtra(EXTRA_GUARDIAN_PACKAGE, targetPackageName)
            .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
            .putExtra(EXTRA_OPERATION_ID, operationId)
            .putExtra(EXTRA_CHECK_ID, checkId)
            .putExtra(EXTRA_RECEIPT_USE_DAY_GENERATION, receipt.useDayGenerationStartedAtMs)
        when (receipt) {
            is GuardianApprovalWorkReceipt.DirectGrant -> request
                .putExtra(EXTRA_APPROVAL_KIND, APPROVAL_KIND_DIRECT)
                .putExtra(EXTRA_RECEIPT_RULE_ID, receipt.grant.ruleId)
                .putExtra(EXTRA_RECEIPT_USE_DAY_ID, receipt.grant.useDayId)
                .putExtra(EXTRA_RECEIPT_GRANTED_AT_MS, receipt.grant.grantedAtMs)
                .putExtra(EXTRA_RECEIPT_GRANTED_MILLIS, receipt.grant.grantedMillis)
            is GuardianApprovalWorkReceipt.AccumulatedGrant -> request
                .putExtra(EXTRA_APPROVAL_KIND, APPROVAL_KIND_ACCUMULATED)
                .putExtra(EXTRA_RECEIPT_RULE_ID, receipt.ruleId)
                .putExtra(EXTRA_RECEIPT_USE_DAY_ID, receipt.useDayId)
                .putExtra(EXTRA_RECEIPT_GRANTED_AT_MS, receipt.grantedAtMs)
                .putExtra(EXTRA_RECEIPT_GRANTED_MILLIS, receipt.grantedMillis)
            is GuardianApprovalWorkReceipt.RuleSkip -> request
                .putExtra(EXTRA_APPROVAL_KIND, APPROVAL_KIND_SKIP)
                .putExtra(EXTRA_RECEIPT_RULE_ID, receipt.ruleId)
                .putExtra(EXTRA_RECEIPT_USE_DAY_ID, receipt.useDayId)
                .putExtra(EXTRA_SKIP_FROM_MS, receipt.skipFromMs)
                .putExtra(EXTRA_SKIP_UNTIL_MS, receipt.skipUntilMs)
        }
        sendBroadcast(request)
    }

    private fun handleConfirmationResult(result: Intent) {
        if (!canHandleCallbacks() ||
            result.getStringExtra(EXTRA_SCREEN_REQUEST_ID) != screenRequestId ||
            result.getStringExtra(EXTRA_OPERATION_ID) != confirmationOperationId ||
            result.getStringExtra(EXTRA_CHECK_ID) != confirmationCheckId
        ) return
        val status = result.getStringExtra(EXTRA_CONFIRMATION_STATUS) ?: return
        confirmationChecking = false
        when (status) {
            CONFIRMATION_STATUS_FAILED, CONFIRMATION_STATUS_TIMEOUT -> {
                confirmationFailed = true
                renderConfirmationState()
            }
            CONFIRMATION_STATUS_REMAINING -> {
                val latestDenials = runCatching {
                    Gson().fromJson<List<AppRuleGuardianDenial?>>(
                        result.getStringExtra(EXTRA_CONFIRMATION_DENIALS).orEmpty(),
                        object : TypeToken<List<AppRuleGuardianDenial?>>() {}.type
                    )
                }.getOrNull()?.filterNotNull().orEmpty()
                if (latestDenials.isEmpty()) {
                    confirmationFailed = true
                    renderConfirmationState()
                    return
                }
                confirmationFailed = false
                denials = latestDenials
                selectedRuleId = latestDenials.first().ruleId
                grantInProgress = false
                render()
            }
            CONFIRMATION_STATUS_ALLOWED -> {
                confirmationFailed = false
                closeReason = REASON_CONFIRMED
                finish()
            }
        }
    }

    private fun renderConfirmationState() {
        val blocked = confirmationChecking || confirmationFailed
        binding.approvalConfirmationProgress.visibility = if (confirmationChecking) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.approvalConfirmationMessage.visibility = if (blocked) View.VISIBLE else View.GONE
        binding.approvalConfirmationMessage.setText(
            if (confirmationChecking) {
                R.string.guardian_confirmation_checking
            } else {
                R.string.guardian_confirmation_failed
            }
        )
        binding.approvalConfirmationRetry.visibility = if (confirmationFailed) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.approvalChoices.visibility = if (blocked) View.GONE else View.VISIBLE
        if (blocked) {
            binding.approvalAddTime.visibility = View.GONE
            binding.approvalUseAccumulatedTime.visibility = View.GONE
            binding.approvalSkipRule.visibility = View.GONE
        } else {
            binding.approvalSkipRule.visibility = if (grantInProgress) View.GONE else View.VISIBLE
        }
        binding.approvalCancel.visibility = View.GONE
    }

    private fun isConfirmationUiBlocking(): Boolean =
        confirmationChecking || confirmationFailed

    private fun beginLegacyOperation(): String? {
        if (!canHandleCallbacks() || isConfirmationUiBlocking()) return null
        val operationId = UUID.randomUUID().toString()
        legacyOperationId = operationId
        sendBroadcast(
            Intent(INTENT_ACTION_LEGACY_STARTED)
                .setPackage(packageName)
                .putExtra(EXTRA_GUARDIAN_PACKAGE, targetPackageName)
                .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                .putExtra(EXTRA_OPERATION_ID, operationId)
        )
        return operationId
    }

    private fun cancelLegacyOperation(operationId: String) {
        if (legacyOperationId != operationId) return
        sendBroadcast(
            Intent(INTENT_ACTION_LEGACY_CANCELLED)
                .setPackage(packageName)
                .putExtra(EXTRA_GUARDIAN_PACKAGE, targetPackageName)
                .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                .putExtra(EXTRA_OPERATION_ID, operationId)
        )
        legacyOperationId = null
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            notifyGuardianClosed()
            finish()
        }
    }

    override fun onDestroy() {
        isDestroying = true
        (ownedDialogs + listOfNotNull(grantFormDialog)).toList().forEach { dialog ->
            runCatching { dialog.dismiss() }
        }
        ownedDialogs.clear()
        grantFormDialog = null
        grantDialogOpen = false
        if (guardianStateReceiverRegistered) {
            runCatching { unregisterReceiver(guardianStateRequestReceiver) }
            guardianStateReceiverRegistered = false
        }
        runCatching { unregisterReceiver(guardianConfirmationReceiver) }
        super.onDestroy()
    }

    private fun showOwnedDialog(
        dialog: AlertDialog,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        ownedDialogs += dialog
        GuardianOwnedDialog.show(dialog, onCancel = onCancel) {
            ownedDialogs.remove(dialog)
            onDismiss?.invoke()
        }
    }

    private fun canHandleCallbacks(): Boolean =
        !isDestroying && !isFinishing && !isDestroyed

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
                .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                .putExtra(EXTRA_CLOSE_REASON, closeReason)
                .putExtra(EXTRA_OPERATION_ID, legacyOperationId)
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

    private fun finishAndLaunchLegacy(operationId: String) {
        if (!canHandleCallbacks()) return
        closeReason = REASON_GRANTED
        legacyOperationId = operationId
        targetPackageName.takeIf(String::isNotBlank)?.let { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
        finish()
    }

    private fun toast(message: Int) {
        if (canHandleCallbacks()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_DENIALS = "app_rule_denials_json"
        const val EXTRA_PACKAGE = "launch_package"
        const val EXTRA_SCREEN_REQUEST_ID = "guardian_screen_request_id"
        const val INTENT_ACTION_CLOSED = "neth.iecal.curbox.guardian.approval.closed"
        const val INTENT_ACTION_OPENED = "neth.iecal.curbox.guardian.approval.opened"
        const val INTENT_ACTION_STATE_REQUEST = "neth.iecal.curbox.guardian.approval.state_request"
        const val INTENT_ACTION_DIRECT_GRANT_STORED =
            "neth.iecal.curbox.guardian.approval.direct_grant_stored"
        const val INTENT_ACTION_DIRECT_CHECK_RETRY =
            "neth.iecal.curbox.guardian.approval.direct_check_retry"
        const val INTENT_ACTION_APPROVAL_STORED =
            "neth.iecal.curbox.guardian.approval.stored"
        const val INTENT_ACTION_APPROVAL_CHECK_RETRY =
            "neth.iecal.curbox.guardian.approval.check_retry"
        const val INTENT_ACTION_CONFIRMATION_RESULT =
            "neth.iecal.curbox.guardian.approval.confirmation_result"
        const val INTENT_ACTION_LEGACY_STARTED =
            "neth.iecal.curbox.guardian.approval.legacy_started"
        const val INTENT_ACTION_LEGACY_CANCELLED =
            "neth.iecal.curbox.guardian.approval.legacy_cancelled"
        const val EXTRA_GUARDIAN_PACKAGE = "guardian_package"
        const val EXTRA_CLOSE_REASON = "guardian_close_reason"
        const val EXTRA_OPERATION_ID = "guardian_operation_id"
        const val EXTRA_CHECK_ID = "guardian_check_id"
        const val EXTRA_RECEIPT_RULE_ID = "guardian_receipt_rule_id"
        const val EXTRA_RECEIPT_USE_DAY_ID = "guardian_receipt_use_day_id"
        const val EXTRA_RECEIPT_GRANTED_AT_MS = "guardian_receipt_granted_at_ms"
        const val EXTRA_RECEIPT_GRANTED_MILLIS = "guardian_receipt_granted_millis"
        const val EXTRA_RECEIPT_USE_DAY_GENERATION = "guardian_receipt_use_day_generation"
        const val EXTRA_APPROVAL_KIND = "guardian_approval_kind"
        const val EXTRA_SKIP_FROM_MS = "guardian_skip_from_ms"
        const val EXTRA_SKIP_UNTIL_MS = "guardian_skip_until_ms"
        const val EXTRA_CONFIRMATION_STATUS = "guardian_confirmation_status"
        const val EXTRA_CONFIRMATION_DENIALS = "guardian_confirmation_denials"
        const val EXTRA_CONFIRMATION_STATE = "guardian_confirmation_state"
        const val CONFIRMATION_STATUS_ALLOWED = "allowed"
        const val CONFIRMATION_STATUS_REMAINING = "remaining"
        const val CONFIRMATION_STATUS_FAILED = "failed"
        const val CONFIRMATION_STATUS_TIMEOUT = "timeout"
        const val APPROVAL_KIND_DIRECT = "direct_grant"
        const val APPROVAL_KIND_ACCUMULATED = "accumulated_grant"
        const val APPROVAL_KIND_SKIP = "rule_skip"
        const val REASON_GRANTED = "granted"
        const val REASON_CANCELLED = "cancelled"
        const val REASON_INTERRUPTED = "interrupted"
        const val REASON_CONFIRMED = "confirmed"
    }
}
