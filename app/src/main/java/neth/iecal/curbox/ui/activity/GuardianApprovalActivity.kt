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
import android.util.Log
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
import androidx.lifecycle.Lifecycle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt
import neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin
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
import java.io.IOException
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
    private var guardianServiceRegisteredReceiverRegistered = false
    private var closeReason: String = REASON_INTERRUPTED
    private var screenRequestId: String = ""
    private var confirmationOperationId: String? = null
    private var confirmationCheckId: String? = null
    private var confirmationReceipt: GuardianApprovalWorkReceipt? = null
    private var confirmationChecking = false
    private var confirmationFailed = false
    private var grantInProgressRequestId: String? = null
    private var requestedServiceConnectionId: String? = null
    private var registeredServiceConnectionId: String? = null
    private var pendingApprovalCheckAction: String? = null
    private var pendingApprovalCheckId: String? = null
    private var confirmationServiceConnectionId: String? = null
    private var legacyOperationId: String? = null
    private var approvalTestReceiverRegistered = false

    private data class ApprovalWriteFailureGate(val id: String)

    private val approvalTestGateLock = Any()
    @Volatile private var approvalWriteFailureGate: ApprovalWriteFailureGate? = null
    @Volatile private var approvalTestRunId: String? = null

    private val approvalTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!BuildConfig.DEBUG) return
            val action = intent?.action ?: return
            when (action) {
                INTENT_ACTION_TEST_ARM_WRITE_FAILURE -> {
                    val gateId = intent.getStringExtra(EXTRA_TEST_GATE_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val armed = synchronized(approvalTestGateLock) {
                        if (approvalWriteFailureGate != null) {
                            false
                        } else {
                            approvalWriteFailureGate = ApprovalWriteFailureGate(gateId)
                            approvalTestRunId = gateId
                            true
                        }
                    }
                    if (armed) {
                        DataStoreManager.guardianApprovalWriteCommitObserverForTest = { settings ->
                            val consumed = synchronized(approvalTestGateLock) {
                                approvalWriteFailureGate?.takeIf { it.id == gateId }?.also {
                                    approvalWriteFailureGate = null
                                }
                            }
                            if (consumed != null) {
                                testLog(
                                    "write_committed id=$gateId " +
                                        "grant_count=${settings.appRuleOverrideState.grants.size} " +
                                        "skip_count=${settings.appRuleOverrideState.skips.size}"
                                )
                                throw IOException("Injected failure after the real DataStore commit")
                            }
                        }
                    }
                    testLog("write_failure_armed id=$gateId accepted=$armed")
                }
                INTENT_ACTION_TEST_CLEAR_WRITE_FAILURE -> {
                    synchronized(approvalTestGateLock) {
                        approvalWriteFailureGate = null
                        approvalTestRunId = null
                    }
                    DataStoreManager.guardianApprovalWriteCommitObserverForTest = null
                    testLog("write_failure_cleared")
                }
                INTENT_ACTION_TEST_SEND_STALE_CLOSED -> {
                    val targetPackage = intent.getStringExtra(EXTRA_TEST_GUARDIAN_PACKAGE)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val oldScreenRequestId = intent.getStringExtra(EXTRA_TEST_SCREEN_REQUEST_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val oldConnectionId = intent.getStringExtra(EXTRA_TEST_SERVICE_CONNECTION_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    sendBroadcast(
                        Intent(INTENT_ACTION_CLOSED)
                            .setPackage(this@GuardianApprovalActivity.packageName)
                            .putExtra(EXTRA_GUARDIAN_PACKAGE, targetPackage)
                            .putExtra(EXTRA_SCREEN_REQUEST_ID, oldScreenRequestId)
                            .putExtra(EXTRA_SERVICE_CONNECTION_ID, oldConnectionId)
                            .putExtra(EXTRA_CLOSE_REASON, REASON_INTERRUPTED)
                    )
                    testLog("test_stale_closed_sent screen=$oldScreenRequestId package=$targetPackage")
                }
                INTENT_ACTION_TEST_SEND_CONFIRMATION_RESULT -> {
                    val oldScreenRequestId = intent.getStringExtra(EXTRA_TEST_SCREEN_REQUEST_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val oldOperationId = intent.getStringExtra(EXTRA_TEST_OPERATION_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val oldCheckId = intent.getStringExtra(EXTRA_TEST_CHECK_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val oldConnectionId = intent.getStringExtra(EXTRA_TEST_SERVICE_CONNECTION_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val status = intent.getStringExtra(EXTRA_TEST_CONFIRMATION_STATUS)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    sendBroadcast(
                        Intent(INTENT_ACTION_CONFIRMATION_RESULT)
                            .setPackage(this@GuardianApprovalActivity.packageName)
                            .putExtra(EXTRA_SCREEN_REQUEST_ID, oldScreenRequestId)
                            .putExtra(EXTRA_SERVICE_CONNECTION_ID, oldConnectionId)
                            .putExtra(EXTRA_OPERATION_ID, oldOperationId)
                            .putExtra(EXTRA_CHECK_ID, oldCheckId)
                            .putExtra(EXTRA_CONFIRMATION_STATUS, status)
                    )
                    testLog(
                        "test_confirmation_result_sent screen=$oldScreenRequestId " +
                            "operation=$oldOperationId check=$oldCheckId status=$status"
                    )
                }
                INTENT_ACTION_TEST_SELECT_APPROVAL_RULE -> {
                    val ruleId = intent.getStringExtra(EXTRA_TEST_GUARDIAN_RULE_ID)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: return
                    val index = denials.indexOfFirst { it.ruleId == ruleId }
                    if (index >= 0 && !isFinishing && this@GuardianApprovalActivity::binding.isInitialized) {
                        (binding.approvalChoices.getChildAt(index) as? RadioButton)?.performClick()
                    }
                    val accepted = index >= 0 && selectedRuleId == ruleId
                    testLog(
                        "test_rule_selection instance=${System.identityHashCode(this@GuardianApprovalActivity)} " +
                            "screen=$screenRequestId rule=$ruleId " +
                            "accepted=$accepted selected=${selectedRuleId.orEmpty()}"
                    )
                }
                AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE,
                AppRuleBlocker.INTENT_ACTION_TEST_RELEASE_GUARDIAN_EVALUATION_GATE,
                AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED -> {
                    if (!intent.hasExtra(EXTRA_TEST_GATE_ACCEPTED)) return
                    testLog(
                        "service_gate action=$action " +
                            "id=${intent.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID)} " +
                            "accepted=${intent.getBooleanExtra(EXTRA_TEST_GATE_ACCEPTED, false)} " +
                            "operation=${intent.getStringExtra(AppRuleBlocker.EXTRA_TEST_OPERATION_ID)} " +
                            "check=${intent.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID)}"
                    )
                }
            }
        }
    }

    private val guardianStateRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_STATE_REQUEST ||
                guardianClosedBroadcastSent || !canHandleCallbacks() ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return
            val connectionId = intent.getStringExtra(EXTRA_SERVICE_CONNECTION_ID)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return
            if (screenRequestId.isBlank() || targetPackageName.isBlank() ||
                this@GuardianApprovalActivity.intent
                    .getStringExtra(EXTRA_SCREEN_REQUEST_ID) != screenRequestId
            ) return

            if (connectionId != requestedServiceConnectionId &&
                connectionId != registeredServiceConnectionId
            ) {
                requestedServiceConnectionId = connectionId
                pendingApprovalCheckAction = null
                pendingApprovalCheckId = null
                if (confirmationChecking && confirmationOperationId != null &&
                    confirmationReceipt != null
                ) {
                    confirmationCheckId = UUID.randomUUID().toString()
                    pendingApprovalCheckAction = INTENT_ACTION_APPROVAL_RECOVER
                    pendingApprovalCheckId = confirmationCheckId
                    renderConfirmationState()
                    testLogConfirmationRequest(
                        INTENT_ACTION_APPROVAL_RECOVER,
                        requireNotNull(confirmationOperationId),
                        confirmationCheckId!!,
                        requireNotNull(confirmationReceipt)
                    )
                }
            }
            testLog(
                "service_connection_requested id=$connectionId screen=$screenRequestId " +
                    "checking=$confirmationChecking"
            )
            sendGuardianScreenOpened(connectionId)
        }
    }

    private val guardianScreenRegisteredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_SCREEN_REGISTERED ||
                !canHandleCallbacks() ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) return
            val requestId = intent.getStringExtra(EXTRA_SCREEN_REQUEST_ID).orEmpty()
            val packageName = intent.getStringExtra(EXTRA_GUARDIAN_PACKAGE).orEmpty()
            val connectionId = intent.getStringExtra(EXTRA_SERVICE_CONNECTION_ID)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: return
            if (requestId != screenRequestId || packageName != targetPackageName ||
                (requestedServiceConnectionId != null &&
                    requestedServiceConnectionId != connectionId)
            ) return

            requestedServiceConnectionId = connectionId
            registeredServiceConnectionId = connectionId
            testLog(
                "service_connection_registered id=$connectionId screen=$screenRequestId " +
                    "pending_check=${pendingApprovalCheckId.orEmpty()}"
            )
            val action = pendingApprovalCheckAction ?: return
            val checkId = pendingApprovalCheckId ?: return
            val operationId = confirmationOperationId ?: return
            val receipt = confirmationReceipt ?: return
            if (!confirmationChecking || confirmationCheckId != checkId) return

            pendingApprovalCheckAction = null
            pendingApprovalCheckId = null
            sendApprovalConfirmationRequest(action, operationId, checkId, receipt, connectionId)
        }
    }

    private val guardianConfirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != INTENT_ACTION_CONFIRMATION_RESULT) return
            if (BuildConfig.DEBUG) {
                val resultDenials = runCatching {
                    Gson().fromJson<List<AppRuleGuardianDenial?>>(
                        intent.getStringExtra(EXTRA_CONFIRMATION_DENIALS).orEmpty(),
                        object : TypeToken<List<AppRuleGuardianDenial?>>() {}.type
                    ).orEmpty().filterNotNull().joinToString("|") { "${it.ruleId}:${it.ruleName}" }
                }.getOrDefault("")
                testLog(
                    "result test=${approvalTestRunId ?: "-"} " +
                        "status=${intent.getStringExtra(EXTRA_CONFIRMATION_STATUS)} " +
                        "operation=${intent.getStringExtra(EXTRA_OPERATION_ID)} " +
                        "check=${intent.getStringExtra(EXTRA_CHECK_ID)} denials=$resultDenials"
                )
            }
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
            ?: savedInstanceState?.getString(STATE_SCREEN_REQUEST_ID)
                ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        restoreDenialDisplayState(savedInstanceState)
        restoreConfirmationState(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            guardianStateRequestReceiver,
            IntentFilter(INTENT_ACTION_STATE_REQUEST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        guardianStateReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            guardianScreenRegisteredReceiver,
            IntentFilter(INTENT_ACTION_SCREEN_REGISTERED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        guardianServiceRegisteredReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            guardianConfirmationReceiver,
            IntentFilter(INTENT_ACTION_CONFIRMATION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                approvalTestReceiver,
                IntentFilter().apply {
                    addAction(INTENT_ACTION_TEST_ARM_WRITE_FAILURE)
                    addAction(INTENT_ACTION_TEST_CLEAR_WRITE_FAILURE)
                    addAction(INTENT_ACTION_TEST_SEND_STALE_CLOSED)
                    addAction(INTENT_ACTION_TEST_SEND_CONFIRMATION_RESULT)
                    addAction(INTENT_ACTION_TEST_SELECT_APPROVAL_RULE)
                    addAction(AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE)
                    addAction(AppRuleBlocker.INTENT_ACTION_TEST_RELEASE_GUARDIAN_EVALUATION_GATE)
                    addAction(AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            approvalTestReceiverRegistered = true
        }
        selectedRuleId = selectedRuleId?.takeIf { id -> denials.any { it.ruleId == id } }
            ?: denials.first().ruleId
        render()
        testLog(
            "activity_created instance=${System.identityHashCode(this)} screen=$screenRequestId " +
                "restored_state=${savedInstanceState != null} package=$targetPackageName " +
                "denials=${denials.joinToString("|") { it.ruleName }} " +
                "denial_ids=${denials.joinToString("|") { it.ruleId }} " +
                "selected_rule=${selectedRuleId.orEmpty()}"
        )
        if (confirmationChecking || confirmationFailed) {
            renderConfirmationState()
            testLog(
                "confirmation_restored screen=$screenRequestId " +
                    "operation=${confirmationOperationId.orEmpty()} " +
                    "check=${confirmationCheckId.orEmpty()} checking=$confirmationChecking " +
                    "failed=$confirmationFailed"
            )
        }
        sendGuardianScreenOpened()
        lifecycleScope.launch {
            hasPassword = dataStore.settings.first().guardianAuthConfig.isConfigured
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN_REQUEST_ID, screenRequestId)
        outState.putString(STATE_TARGET_PACKAGE, targetPackageName)
        outState.putString(STATE_DENIALS, Gson().toJson(denials))
        outState.putString(STATE_SELECTED_RULE_ID, selectedRuleId)
        if (BuildConfig.DEBUG) {
            outState.putString(STATE_APPROVAL_TEST_RUN_ID, approvalTestRunId)
        }
        if (confirmationChecking || confirmationFailed) {
            outState.putString(STATE_CONFIRMATION_OPERATION_ID, confirmationOperationId)
            outState.putString(STATE_CONFIRMATION_CHECK_ID, confirmationCheckId)
            outState.putBoolean(STATE_CONFIRMATION_CHECKING, confirmationChecking)
            outState.putBoolean(STATE_CONFIRMATION_FAILED, confirmationFailed)
            outState.putString(STATE_REQUESTED_CONNECTION_ID, requestedServiceConnectionId)
            outState.putString(STATE_CONFIRMATION_CONNECTION_ID, confirmationServiceConnectionId)
            outState.putString(STATE_PENDING_CHECK_ACTION, pendingApprovalCheckAction)
            outState.putString(STATE_PENDING_CHECK_ID, pendingApprovalCheckId)
            confirmationReceipt?.let { saveConfirmationReceipt(outState, it) }
        }
        super.onSaveInstanceState(outState)
    }

    private fun restoreDenialDisplayState(state: Bundle?) {
        if (state == null ||
            state.getString(STATE_TARGET_PACKAGE) != targetPackageName ||
            state.getString(STATE_SCREEN_REQUEST_ID) != screenRequestId
        ) return

        val savedDenials = readValidatedDenials(state.getString(STATE_DENIALS)) ?: return
        denials = savedDenials
        selectedRuleId = state.getString(STATE_SELECTED_RULE_ID)
            ?.takeIf { id -> savedDenials.any { it.ruleId == id } }
            ?: savedDenials.first().ruleId
    }

    private fun restoreConfirmationState(state: Bundle?) {
        if (state == null ||
            state.getString(STATE_TARGET_PACKAGE) != targetPackageName ||
            state.getString(STATE_SCREEN_REQUEST_ID) != screenRequestId
        ) return

        if (BuildConfig.DEBUG) {
            approvalTestRunId = state.getString(STATE_APPROVAL_TEST_RUN_ID)
        }

        val operationId = state.getString(STATE_CONFIRMATION_OPERATION_ID)
        val checkId = state.getString(STATE_CONFIRMATION_CHECK_ID)
        val receipt = restoreConfirmationReceipt(state)
        val checking = state.getBoolean(STATE_CONFIRMATION_CHECKING)
        val failed = state.getBoolean(STATE_CONFIRMATION_FAILED)
        if ((!checking && !failed) || operationId.isNullOrBlank() ||
            checkId.isNullOrBlank() || receipt == null
        ) return

        confirmationOperationId = operationId
        confirmationReceipt = receipt
        confirmationChecking = checking
        confirmationFailed = failed
        confirmationCheckId = if (checking) UUID.randomUUID().toString() else checkId
        requestedServiceConnectionId = state.getString(STATE_REQUESTED_CONNECTION_ID)
        confirmationServiceConnectionId = state.getString(STATE_CONFIRMATION_CONNECTION_ID)
        if (checking) {
            pendingApprovalCheckAction = INTENT_ACTION_APPROVAL_RECOVER
            pendingApprovalCheckId = confirmationCheckId
            testLogConfirmationRequest(
                INTENT_ACTION_APPROVAL_RECOVER,
                operationId,
                confirmationCheckId!!,
                receipt
            )
        } else {
            pendingApprovalCheckAction = state.getString(STATE_PENDING_CHECK_ACTION)
            pendingApprovalCheckId = state.getString(STATE_PENDING_CHECK_ID)
        }
    }

    private fun saveConfirmationReceipt(state: Bundle, receipt: GuardianApprovalWorkReceipt) {
        when (receipt) {
            is GuardianApprovalWorkReceipt.Grant -> {
                state.putString(STATE_RECEIPT_KIND, STATE_RECEIPT_GRANT)
                state.putString(STATE_RECEIPT_RULE_ID, receipt.grant.ruleId)
                state.putString(STATE_RECEIPT_USE_DAY_ID, receipt.grant.useDayId)
                state.putLong(STATE_RECEIPT_GENERATION, receipt.useDayGenerationStartedAtMs)
                state.putLong(STATE_RECEIPT_GRANTED_AT, receipt.grant.grantedAtMs)
                state.putLong(STATE_RECEIPT_GRANTED_MILLIS, receipt.grant.grantedMillis)
                state.putString(STATE_RECEIPT_GRANT_ORIGIN, receipt.origin.name)
            }
            is GuardianApprovalWorkReceipt.RuleSkip -> {
                state.putString(STATE_RECEIPT_KIND, STATE_RECEIPT_SKIP)
                state.putString(STATE_RECEIPT_RULE_ID, receipt.ruleId)
                state.putString(STATE_RECEIPT_USE_DAY_ID, receipt.useDayId)
                state.putLong(STATE_RECEIPT_GENERATION, receipt.useDayGenerationStartedAtMs)
                state.putLong(STATE_RECEIPT_SKIP_FROM, receipt.skipFromMs)
                state.putLong(STATE_RECEIPT_SKIP_UNTIL, receipt.skipUntilMs)
            }
        }
    }

    private fun restoreConfirmationReceipt(state: Bundle): GuardianApprovalWorkReceipt? =
        runCatching {
            val ruleId = state.getString(STATE_RECEIPT_RULE_ID).orEmpty()
            val useDayId = state.getString(STATE_RECEIPT_USE_DAY_ID).orEmpty()
            val generation = state.getLong(STATE_RECEIPT_GENERATION)
            when (state.getString(STATE_RECEIPT_KIND)) {
                STATE_RECEIPT_GRANT -> {
                    val origin = GuardianApprovalGrantOrigin.valueOf(
                        state.getString(STATE_RECEIPT_GRANT_ORIGIN).orEmpty()
                    )
                    GuardianApprovalWorkReceipt.Grant(
                        grant = neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt(
                            ruleId = ruleId,
                            useDayId = useDayId,
                            grantedAtMs = state.getLong(STATE_RECEIPT_GRANTED_AT),
                            grantedMillis = state.getLong(STATE_RECEIPT_GRANTED_MILLIS)
                        ),
                        origin = origin,
                        useDayGenerationStartedAtMs = generation
                    )
                }
                STATE_RECEIPT_SKIP -> GuardianApprovalWorkReceipt.RuleSkip(
                    ruleId = ruleId,
                    useDayId = useDayId,
                    skipFromMs = state.getLong(STATE_RECEIPT_SKIP_FROM),
                    skipUntilMs = state.getLong(STATE_RECEIPT_SKIP_UNTIL),
                    useDayGenerationStartedAtMs = generation
                )
                else -> null
            }
        }.getOrNull()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val payload = readValidatedPayload(intent) ?: return
        if (isFinishing) return
        val previousScreenRequestId = screenRequestId
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
        pendingApprovalCheckAction = null
        pendingApprovalCheckId = null
        confirmationServiceConnectionId = null
        grantInProgress = false
        grantInProgressRequestId = null
        grantPickerLoading = false
        closeReason = REASON_INTERRUPTED
        guardianClosedBroadcastSent = false
        legacyOperationId = null
        selectedRuleId = denials.first().ruleId
        render()
        testLog(
            "screen_reused previous=$previousScreenRequestId current=$screenRequestId " +
                "package=$targetPackageName denials=${denials.joinToString("|") { it.ruleName }}"
        )
        sendGuardianScreenOpened(replacedScreenRequestId = previousScreenRequestId)
    }

    override fun onStart() {
        super.onStart()
        // A service reconnect can happen while this Activity is stopped. Repeating the current
        // screen registration lets the service request the new connection token on resume.
        sendGuardianScreenOpened()
    }

    private fun sendGuardianScreenOpened(
        connectionId: String? = requestedServiceConnectionId ?: registeredServiceConnectionId,
        replacedScreenRequestId: String? = null
    ) {
        if (!canHandleCallbacks() || screenRequestId.isBlank() || targetPackageName.isBlank()) return
        val opened = Intent(INTENT_ACTION_OPENED)
            .setPackage(packageName)
            .putExtra(EXTRA_GUARDIAN_PACKAGE, targetPackageName)
            .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
        replacedScreenRequestId?.takeIf(String::isNotBlank)?.let {
            opened.putExtra(EXTRA_PREVIOUS_SCREEN_REQUEST_ID, it)
        }
        connectionId?.let { opened.putExtra(EXTRA_SERVICE_CONNECTION_ID, it) }
        sendBroadcast(opened)
    }

    private fun readValidatedPayload(sourceIntent: Intent): GuardianApprovalPayload? {
        val packageName = runCatching {
            sourceIntent.getStringExtra(EXTRA_PACKAGE)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.getOrNull() ?: return null
        val denials = readValidatedDenials(sourceIntent.getStringExtra(EXTRA_DENIALS))
            ?: return null

        return GuardianApprovalPayload(
            packageName = packageName,
            denials = denials
        )
    }

    private fun readValidatedDenials(encodedDenials: String?): List<AppRuleGuardianDenial>? {
        val parsedDenials = runCatching {
            Gson().fromJson<List<AppRuleGuardianDenial?>>(
                encodedDenials.orEmpty(),
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

        return validatedDenials.filterNotNull()
    }

    private fun render() {
        val choices = binding.approvalChoices
        selectedRuleId = selectedRuleId?.takeIf { id -> denials.any { it.ruleId == id } }
            ?: denials.first().ruleId
        choices.removeAllViews()
        denials.forEachIndexed { index, denial ->
            choices.addView(RadioButton(this).apply {
                id = index + 1
                text = GuardianApprovalTextFormatter.formatDenial(this@GuardianApprovalActivity, denial)
                isChecked = denial.ruleId == selectedRuleId
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
        val requestId = screenRequestId
        lifecycleScope.launch {
            val allowed = dataStore.settings.first().appRuleSnapshot.appRules.any {
                it.isActive && it.guardianExtraTimeAllowed
            }
            if (!isCurrentScreenRequest(requestId)) return@launch
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
        val requestId = screenRequestId
        lifecycleScope.launch {
            val state = resolveAccumulatedState(ruleId)
            if (!isCurrentScreenRequest(requestId)) return@launch
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

    private fun requestGrant(
        preferredRuleId: String? = null,
        requestId: String = screenRequestId
    ) {
        if (!canHandleCallbacks() || isConfirmationUiBlocking() || grantInProgress || grantPickerLoading ||
            grantDialogOpen || grantFormDialog?.isShowing == true
        ) return
        if (targetPackageName.isBlank() || requestId.isBlank()) return
        grantPickerLoading = true
        lifecycleScope.launch {
            val options = try {
                grantQuery.candidates()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentScreenRequest(requestId)) {
                    grantPickerLoading = false
                    toast(R.string.guardian_write_failed)
                }
                return@launch
            }
            if (!isCurrentScreenRequest(requestId)) return@launch
            grantPickerLoading = false
            if (options.isEmpty()) {
                toast(R.string.guardian_no_extra_time_rules)
                return@launch
            }
            val selectedIndex = options.indexOfFirst { it.rule.id == preferredRuleId }
                .takeIf { it >= 0 }
                ?: 0
            grantDialogOpen = true
            showGrantDialog(options, selectedIndex, requestId)
        }
    }

    private fun showGrantDialog(
        options: List<GuardianExtraTimeGrantCandidate>,
        selectedIndex: Int
    ) = showGrantDialog(options, selectedIndex, screenRequestId)

    private fun showGrantDialog(
        options: List<GuardianExtraTimeGrantCandidate>,
        selectedIndex: Int,
        requestId: String
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
                    testLog("grant_submit rule=${basis.ruleId} minutes=$minutes")
                    grantInProgress = true
                    grantInProgressRequestId = requestId
                    authenticateThen(
                        ruleId = basis.ruleId,
                        screenRequestId = requestId,
                        onCancelled = { clearGrantInProgress(requestId) },
                        onAuthenticated = { _, password ->
                            writeGrant(basis, password, minutes, requestId)
                        }
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

    private fun requestAccumulatedGrant(requestId: String = screenRequestId) {
        if (!canHandleCallbacks() || isConfirmationUiBlocking() || grantInProgress) return
        if (requestId.isBlank()) return
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch {
            val state = resolveAccumulatedState(ruleId)
            if (!isCurrentScreenRequest(requestId)) return@launch

            if (!state.isVisible || state.accumulatedMinutes <= 0L) {
                updateAccumulatedButton(selectedRuleId)
                return@launch
            }

            if (selectedRuleId != ruleId) {
                requestAccumulatedGrant(requestId)
                return@launch
            }
            showAccumulatedGrantDialog(ruleId, state.accumulatedMinutes, requestId)
        }
    }

    private fun showAccumulatedGrantDialog(
        ruleId: String,
        totalAccumulatedMinutes: Long,
        requestId: String = screenRequestId
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
                        grantInProgressRequestId = requestId
                        dialog.getButton(
                            android.content.DialogInterface.BUTTON_POSITIVE
                        ).isEnabled = false
                        dialog.dismiss()
                        authenticateThen(
                            ruleId = ruleId,
                            screenRequestId = requestId,
                            onCancelled = { clearGrantInProgress(requestId) },
                            onAuthenticated = { capturedRuleId, password ->
                                writeAccumulatedGrant(
                                    ruleId = capturedRuleId,
                                    password = password,
                                    minutes = submission.approvedMinutes,
                                    requestId = requestId
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
        val requestId = screenRequestId
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
                grantInProgressRequestId = requestId
                dialog.dismiss()
                authenticateThen(
                    ruleId = ruleId,
                    screenRequestId = requestId,
                    onCancelled = { clearGrantInProgress(requestId) }
                ) { capturedRuleId, password -> writeSkip(capturedRuleId, password, which, requestId) }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showOwnedDialog(dialog)
    }

    private fun authenticateThen(
        ruleId: String,
        screenRequestId: String,
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
                        if (isCurrentScreenRequest(screenRequestId)) {
                            toast(R.string.guardian_wrong_password)
                        }
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
        minutes: Long,
        requestId: String
    ) {
        val operationId = UUID.randomUUID().toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                dataStore.grantAppRuleTime(password, basis, minutes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logApprovalWriteFailure(error)
                testLog("grant_write_result status=exception type=${error.javaClass.simpleName}")
                GuardianExtraTimeGrantWrite.Result.Rejected
            }
            testLog(
                "grant_write_result status=" + when (result) {
                    is GuardianExtraTimeGrantWrite.Result.StoredWithReceipt -> "stored_with_receipt"
                    is GuardianExtraTimeGrantWrite.Result.Uncertain -> "uncertain"
                    is GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation -> "needs_reconfirmation"
                    is GuardianExtraTimeGrantWrite.Result.Unavailable -> "unavailable"
                    is GuardianExtraTimeGrantWrite.Result.Rejected -> "rejected"
                    GuardianExtraTimeGrantWrite.Result.Stored -> "stored"
                }
            )
            withContext(Dispatchers.Main) {
                if (!isCurrentScreenRequest(requestId)) return@withContext
                when (result) {
                    is GuardianExtraTimeGrantWrite.Result.StoredWithReceipt -> {
                        clearGrantInProgress(requestId)
                        beginApprovalConfirmation(
                            operationId,
                            GuardianApprovalWorkReceipt.Grant(
                                grant = result.receipt,
                                origin = GuardianApprovalGrantOrigin.DIRECT,
                                useDayGenerationStartedAtMs = basis.useDayGenerationStartedAtMs
                            ),
                            requestId
                        )
                    }
                    is GuardianExtraTimeGrantWrite.Result.Uncertain -> {
                        clearGrantInProgress(requestId)
                        beginApprovalConfirmation(
                            operationId,
                            GuardianApprovalWorkReceipt.Grant(
                                grant = result.receipt,
                                origin = GuardianApprovalGrantOrigin.DIRECT,
                                useDayGenerationStartedAtMs = basis.useDayGenerationStartedAtMs
                            ),
                            requestId
                        )
                    }
                    GuardianExtraTimeGrantWrite.Result.Stored -> {
                        clearGrantInProgress(requestId)
                        toast(R.string.guardian_write_failed)
                    }
                    is GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation -> {
                        clearGrantInProgress(requestId)
                        toast(R.string.guardian_grant_basis_changed)
                        requestGrant(preferredRuleId = basis.ruleId, requestId = requestId)
                    }
                    GuardianExtraTimeGrantWrite.Result.Unavailable -> {
                        clearGrantInProgress(requestId)
                        toast(R.string.guardian_grant_rule_changed)
                        requestGrant(requestId = requestId)
                    }
                    GuardianExtraTimeGrantWrite.Result.Rejected -> {
                        clearGrantInProgress(requestId)
                        toast(R.string.guardian_write_failed)
                    }
                }
            }
        }
    }

    private fun writeAccumulatedGrant(
        ruleId: String,
        password: String,
        minutes: Long,
        requestId: String
    ) {
        val operationId = UUID.randomUUID().toString()
        lifecycleScope.launch(Dispatchers.IO) {
            var receiptForRecovery: GuardianApprovalWorkReceipt.Grant? = null
            val receipt = try {
                val settings = dataStore.settings.first()
                val now = System.currentTimeMillis()
                val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
                val useDayId = calculator.idAt(now)
                receiptForRecovery = GuardianApprovalWorkReceipt.Grant(
                    grant = neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt(
                        ruleId = ruleId,
                        useDayId = useDayId,
                        grantedAtMs = now,
                        grantedMillis = minutes * 60_000L
                    ),
                    origin = GuardianApprovalGrantOrigin.ACCUMULATED_POOL,
                    useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
                )
                dataStore.approveAccumulatedTimeWithReceipt(
                    password = password,
                    ruleId = ruleId,
                    useDayId = useDayId,
                    approvedMinutes = minutes,
                    expectedUseDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
                    grantedAtMs = now
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logApprovalWriteFailure(error)
                receiptForRecovery
            }
            withContext(Dispatchers.Main) {
                if (!isCurrentScreenRequest(requestId)) return@withContext
                if (receipt != null) {
                    clearGrantInProgress(requestId)
                    beginApprovalConfirmation(operationId, receipt, requestId)
                } else {
                    clearGrantInProgress(requestId)
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun writeSkip(ruleId: String, password: String, option: Int, requestId: String) {
        val operationId = UUID.randomUUID().toString()
        lifecycleScope.launch(Dispatchers.IO) {
            var receiptForRecovery: GuardianApprovalWorkReceipt.RuleSkip? = null
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
                receiptForRecovery = GuardianApprovalWorkReceipt.RuleSkip(
                    ruleId = ruleId,
                    useDayId = useDayId,
                    skipFromMs = now.coerceAtLeast(0L),
                    skipUntilMs = selected.coerceIn(now, nextReset),
                    useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
                )
                dataStore.skipAppRuleUntilWithReceipt(
                    password = password,
                    ruleId = ruleId,
                    useDayId = useDayId,
                    selectedUntilMs = selected,
                    nextResetAtMs = nextReset,
                    expectedUseDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
                    nowMs = now
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logApprovalWriteFailure(error)
                receiptForRecovery
            }
            withContext(Dispatchers.Main) {
                if (!isCurrentScreenRequest(requestId)) return@withContext
                if (receipt != null) {
                    clearGrantInProgress(requestId)
                    beginApprovalConfirmation(operationId, receipt, requestId)
                } else {
                    clearGrantInProgress(requestId)
                    toast(R.string.guardian_write_failed)
                }
            }
        }
    }

    private fun beginApprovalConfirmation(
        operationId: String,
        receipt: GuardianApprovalWorkReceipt,
        requestId: String = screenRequestId
    ) {
        if (!isCurrentScreenRequest(requestId)) return
        confirmationOperationId = operationId
        confirmationReceipt = receipt
        val checkId = UUID.randomUUID().toString()
        confirmationCheckId = checkId
        confirmationChecking = true
        confirmationFailed = false
        renderConfirmationState()
        testLogConfirmationRequest(INTENT_ACTION_APPROVAL_STORED, operationId, checkId, receipt)
        queueOrSendApprovalCheck(INTENT_ACTION_APPROVAL_STORED, operationId, checkId, receipt)
    }

    private fun logApprovalWriteFailure(error: Exception) {
        CrashLogger(applicationContext).logNonFatalError(error)
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
        val action = if (confirmationServiceConnectionId != registeredServiceConnectionId) {
            INTENT_ACTION_APPROVAL_RECOVER
        } else {
            INTENT_ACTION_APPROVAL_CHECK_RETRY
        }
        testLogConfirmationRequest(action, operationId, checkId, receipt)
        queueOrSendApprovalCheck(action, operationId, checkId, receipt)
    }

    private fun queueOrSendApprovalCheck(
        action: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt
    ) {
        if (!canHandleCallbacks() || !confirmationChecking || confirmationCheckId != checkId) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingApprovalCheckAction = action
            pendingApprovalCheckId = checkId
            return
        }
        val connectionId = registeredServiceConnectionId
        if (connectionId == null || connectionId != requestedServiceConnectionId) {
            pendingApprovalCheckAction = action
            pendingApprovalCheckId = checkId
            if (connectionId == null) sendGuardianScreenOpened()
            return
        }
        pendingApprovalCheckAction = null
        pendingApprovalCheckId = null
        confirmationServiceConnectionId = connectionId
        sendApprovalConfirmationRequest(action, operationId, checkId, receipt, connectionId)
    }

    private fun sendApprovalConfirmationRequest(
        action: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt,
        connectionId: String
    ) {
        val request = Intent(action)
            .setPackage(packageName)
            .putExtra(EXTRA_GUARDIAN_PACKAGE, targetPackageName)
            .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
            .putExtra(EXTRA_SERVICE_CONNECTION_ID, connectionId)
            .putExtra(EXTRA_OPERATION_ID, operationId)
            .putExtra(EXTRA_CHECK_ID, checkId)
            .putExtra(EXTRA_RECEIPT_USE_DAY_GENERATION, receipt.useDayGenerationStartedAtMs)
        when (receipt) {
            is GuardianApprovalWorkReceipt.Grant -> request
                .putExtra(
                    EXTRA_APPROVAL_KIND,
                    when (receipt.origin) {
                        GuardianApprovalGrantOrigin.DIRECT -> APPROVAL_KIND_DIRECT
                        GuardianApprovalGrantOrigin.ACCUMULATED_POOL -> APPROVAL_KIND_ACCUMULATED
                    }
                )
                .putExtra(EXTRA_RECEIPT_RULE_ID, receipt.grant.ruleId)
                .putExtra(EXTRA_RECEIPT_USE_DAY_ID, receipt.grant.useDayId)
                .putExtra(EXTRA_RECEIPT_GRANTED_AT_MS, receipt.grant.grantedAtMs)
                .putExtra(EXTRA_RECEIPT_GRANTED_MILLIS, receipt.grant.grantedMillis)
            is GuardianApprovalWorkReceipt.RuleSkip -> request
                .putExtra(EXTRA_APPROVAL_KIND, APPROVAL_KIND_SKIP)
                .putExtra(EXTRA_RECEIPT_RULE_ID, receipt.ruleId)
                .putExtra(EXTRA_RECEIPT_USE_DAY_ID, receipt.useDayId)
                .putExtra(EXTRA_SKIP_FROM_MS, receipt.skipFromMs)
                .putExtra(EXTRA_SKIP_UNTIL_MS, receipt.skipUntilMs)
        }
        sendBroadcast(request)
    }

    private fun testLogConfirmationRequest(
        action: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt
    ) {
        if (!BuildConfig.DEBUG) return
        val details = when (receipt) {
            is GuardianApprovalWorkReceipt.Grant ->
                "kind=${if (receipt.origin == GuardianApprovalGrantOrigin.DIRECT) APPROVAL_KIND_DIRECT else APPROVAL_KIND_ACCUMULATED} " +
                    "rule=${receipt.grant.ruleId} use_day=${receipt.grant.useDayId} " +
                    "generation=${receipt.useDayGenerationStartedAtMs} granted_at=${receipt.grant.grantedAtMs} " +
                    "granted_millis=${receipt.grant.grantedMillis}"
            is GuardianApprovalWorkReceipt.RuleSkip ->
                "kind=$APPROVAL_KIND_SKIP rule=${receipt.ruleId} use_day=${receipt.useDayId} " +
                    "generation=${receipt.useDayGenerationStartedAtMs} skip_from=${receipt.skipFromMs} " +
                    "skip_until=${receipt.skipUntilMs}"
        }
        testLog(
            "check_request test=${approvalTestRunId ?: "-"} action=$action " +
                "screen=$screenRequestId connection=${requestedServiceConnectionId ?: registeredServiceConnectionId ?: "-"} " +
                "operation=$operationId check=$checkId $details"
        )
    }

    private fun testLog(message: String) {
        if (BuildConfig.DEBUG) Log.i(TEST_LOG_TAG, message)
    }

    private fun handleConfirmationResult(result: Intent) {
        val status = result.getStringExtra(EXTRA_CONFIRMATION_STATUS) ?: return
        val operationId = result.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
        val checkId = result.getStringExtra(EXTRA_CHECK_ID).orEmpty()
        if (!canHandleCallbacks() ||
            result.getStringExtra(EXTRA_SCREEN_REQUEST_ID) != screenRequestId ||
            operationId != confirmationOperationId ||
            checkId != confirmationCheckId ||
            result.getStringExtra(EXTRA_SERVICE_CONNECTION_ID) != registeredServiceConnectionId ||
            requestedServiceConnectionId != registeredServiceConnectionId
        ) {
            if (BuildConfig.DEBUG) {
                testLog(
                    "ui_result_ignored status=$status operation=$operationId check=$checkId " +
                        "current_check=${confirmationCheckId.orEmpty()} " +
                        "current_denials=${denials.joinToString("|") { it.ruleName }} " +
                        "finishing=$isFinishing"
                )
            }
            return
        }
        confirmationChecking = false
        when (status) {
            CONFIRMATION_STATUS_FAILED, CONFIRMATION_STATUS_TIMEOUT -> {
                confirmationFailed = true
                renderConfirmationState()
                logConfirmationUiState(status)
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
                    logConfirmationUiState(status)
                    return
                }
                confirmationFailed = false
                denials = latestDenials
                selectedRuleId = latestDenials.first().ruleId
                grantInProgress = false
                render()
                logConfirmationUiState(status)
            }
            CONFIRMATION_STATUS_ALLOWED -> {
                confirmationFailed = false
                closeReason = REASON_CONFIRMED
                finish()
            }
        }
    }

    private fun logConfirmationUiState(status: String) {
        if (!BuildConfig.DEBUG || !this::binding.isInitialized) return
        val retry = binding.approvalConfirmationRetry
        testLog(
            "ui_state status=$status operation=${confirmationOperationId.orEmpty()} " +
                "check=${confirmationCheckId.orEmpty()} retry_visible=${retry.visibility == View.VISIBLE} " +
                "failed=$confirmationFailed finishing=$isFinishing " +
                "denials=${denials.joinToString("|") { it.ruleName }}"
        )
        retry.post {
            val location = IntArray(2)
            retry.getLocationOnScreen(location)
            testLog(
                "ui_geometry operation=${confirmationOperationId.orEmpty()} " +
                    "check=${confirmationCheckId.orEmpty()} retry_left=${location[0]} " +
                    "retry_top=${location[1]} retry_width=${retry.width} " +
                    "retry_height=${retry.height} visible=${retry.visibility == View.VISIBLE}"
            )
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
                .putExtra(EXTRA_SERVICE_CONNECTION_ID, registeredServiceConnectionId.orEmpty())
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
                .putExtra(EXTRA_SERVICE_CONNECTION_ID, registeredServiceConnectionId.orEmpty())
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
        if (guardianServiceRegisteredReceiverRegistered) {
            runCatching { unregisterReceiver(guardianScreenRegisteredReceiver) }
            guardianServiceRegisteredReceiverRegistered = false
        }
        runCatching { unregisterReceiver(guardianConfirmationReceiver) }
        if (approvalTestReceiverRegistered) {
            DataStoreManager.guardianApprovalWriteCommitObserverForTest = null
            runCatching { unregisterReceiver(approvalTestReceiver) }
            approvalTestReceiverRegistered = false
        }
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

    private fun isCurrentScreenRequest(requestId: String): Boolean =
        canHandleCallbacks() && requestId.isNotBlank() && screenRequestId == requestId &&
            intent.getStringExtra(EXTRA_SCREEN_REQUEST_ID) == requestId

    private fun clearGrantInProgress(requestId: String) {
        if (!isCurrentScreenRequest(requestId) || grantInProgressRequestId != requestId) return
        grantInProgress = false
        grantInProgressRequestId = null
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
                .putExtra(EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                .apply {
                    (requestedServiceConnectionId ?: registeredServiceConnectionId)?.let {
                        putExtra(EXTRA_SERVICE_CONNECTION_ID, it)
                    }
                }
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
        private const val TEST_LOG_TAG = "GuardianApprovalE2E"
        private const val STATE_SCREEN_REQUEST_ID = "guardian_state_screen_request_id"
        private const val STATE_TARGET_PACKAGE = "guardian_state_target_package"
        private const val STATE_DENIALS = "guardian_state_denials"
        private const val STATE_SELECTED_RULE_ID = "guardian_state_selected_rule_id"
        private const val STATE_APPROVAL_TEST_RUN_ID = "guardian_state_approval_test_run_id"
        private const val STATE_CONFIRMATION_OPERATION_ID = "guardian_state_confirmation_operation_id"
        private const val STATE_CONFIRMATION_CHECK_ID = "guardian_state_confirmation_check_id"
        private const val STATE_CONFIRMATION_CHECKING = "guardian_state_confirmation_checking"
        private const val STATE_CONFIRMATION_FAILED = "guardian_state_confirmation_failed"
        private const val STATE_REQUESTED_CONNECTION_ID = "guardian_state_requested_connection_id"
        private const val STATE_CONFIRMATION_CONNECTION_ID = "guardian_state_confirmation_connection_id"
        private const val STATE_PENDING_CHECK_ACTION = "guardian_state_pending_check_action"
        private const val STATE_PENDING_CHECK_ID = "guardian_state_pending_check_id"
        private const val STATE_RECEIPT_KIND = "guardian_state_receipt_kind"
        private const val STATE_RECEIPT_GRANT = "grant"
        private const val STATE_RECEIPT_SKIP = "skip"
        private const val STATE_RECEIPT_RULE_ID = "guardian_state_receipt_rule_id"
        private const val STATE_RECEIPT_USE_DAY_ID = "guardian_state_receipt_use_day_id"
        private const val STATE_RECEIPT_GENERATION = "guardian_state_receipt_generation"
        private const val STATE_RECEIPT_GRANTED_AT = "guardian_state_receipt_granted_at"
        private const val STATE_RECEIPT_GRANTED_MILLIS = "guardian_state_receipt_granted_millis"
        private const val STATE_RECEIPT_GRANT_ORIGIN = "guardian_state_receipt_grant_origin"
        private const val STATE_RECEIPT_SKIP_FROM = "guardian_state_receipt_skip_from"
        private const val STATE_RECEIPT_SKIP_UNTIL = "guardian_state_receipt_skip_until"
        internal const val INTENT_ACTION_TEST_ARM_WRITE_FAILURE =
            "neth.iecal.curbox.guardian.TEST_ARM_WRITE_FAILURE"
        internal const val INTENT_ACTION_TEST_CLEAR_WRITE_FAILURE =
            "neth.iecal.curbox.guardian.TEST_CLEAR_WRITE_FAILURE"
        internal const val INTENT_ACTION_TEST_SEND_STALE_CLOSED =
            "neth.iecal.curbox.guardian.TEST_SEND_STALE_CLOSED"
        internal const val INTENT_ACTION_TEST_SEND_CONFIRMATION_RESULT =
            "neth.iecal.curbox.guardian.TEST_SEND_CONFIRMATION_RESULT"
        internal const val INTENT_ACTION_TEST_SELECT_APPROVAL_RULE =
            "neth.iecal.curbox.guardian.TEST_SELECT_APPROVAL_RULE"
        internal const val EXTRA_TEST_GATE_ID = "guardian_test_gate_id"
        internal const val EXTRA_TEST_GUARDIAN_PACKAGE = "guardian_test_package"
        internal const val EXTRA_TEST_GUARDIAN_RULE_ID = "guardian_test_rule_id"
        internal const val EXTRA_TEST_SCREEN_REQUEST_ID = "guardian_test_screen_request_id"
        internal const val EXTRA_TEST_SERVICE_CONNECTION_ID = "guardian_test_service_connection_id"
        internal const val EXTRA_TEST_OPERATION_ID = "guardian_test_operation_id"
        internal const val EXTRA_TEST_CHECK_ID = "guardian_test_check_id"
        internal const val EXTRA_TEST_CONFIRMATION_STATUS = "guardian_test_confirmation_status"
        private const val EXTRA_TEST_GATE_ACCEPTED = "guardian_test_gate_accepted"

        const val EXTRA_DENIALS = "app_rule_denials_json"
        const val EXTRA_PACKAGE = "launch_package"
        const val EXTRA_SCREEN_REQUEST_ID = "guardian_screen_request_id"
        const val EXTRA_PREVIOUS_SCREEN_REQUEST_ID = "guardian_previous_screen_request_id"
        const val EXTRA_SERVICE_CONNECTION_ID = "guardian_service_connection_id"
        const val INTENT_ACTION_CLOSED = "neth.iecal.curbox.guardian.approval.closed"
        const val INTENT_ACTION_OPENED = "neth.iecal.curbox.guardian.approval.opened"
        const val INTENT_ACTION_STATE_REQUEST = "neth.iecal.curbox.guardian.approval.state_request"
        const val INTENT_ACTION_SCREEN_REGISTERED =
            "neth.iecal.curbox.guardian.approval.screen_registered"
        const val INTENT_ACTION_DIRECT_GRANT_STORED =
            "neth.iecal.curbox.guardian.approval.direct_grant_stored"
        const val INTENT_ACTION_DIRECT_CHECK_RETRY =
            "neth.iecal.curbox.guardian.approval.direct_check_retry"
        const val INTENT_ACTION_APPROVAL_STORED =
            "neth.iecal.curbox.guardian.approval.stored"
        const val INTENT_ACTION_APPROVAL_CHECK_RETRY =
            "neth.iecal.curbox.guardian.approval.check_retry"
        const val INTENT_ACTION_APPROVAL_RECOVER =
            "neth.iecal.curbox.guardian.approval.recover"
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
