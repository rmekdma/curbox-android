package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin
import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleEvaluation
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.GuardianApprovalCoordinator
import neth.iecal.curbox.domain.apprules.LifecycleGeneration
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
import org.junit.Assert.assertEquals
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val GUARDIAN_WORKER_TARGET_PACKAGE = "com.example.reader"

internal const val GUARDIAN_WORKER_TEST_TIMEOUT_MS = 10_000L

internal fun assertConfirmation(
    fixture: ConfirmationHostFixture,
    checkId: String,
    phase: GuardianApprovalCoordinator.ConfirmationPhase
) {
    val owner = checkNotNull(
        (getField(fixture.blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
            .currentOwner()
    )
    val confirmation = checkNotNull(owner.confirmation)
    assertEquals(fixture.screenRequestId, owner.screenRequestId)
    assertEquals(fixture.targetPackage, owner.packageName)
    assertEquals(
        LifecycleGeneration(
            (getField(fixture.blocker, "lifecycleGeneration") as AtomicLong).get().coerceAtLeast(1L)
        ),
        owner.lifecycleGeneration
    )
    assertEquals(fixture.operationId, confirmation.operationId)
    assertEquals(fixture.receipt, confirmation.receipt)
    assertEquals(checkId, confirmation.checkId)
    assertEquals(phase, confirmation.phase)
}

internal fun assertTimeoutIdentity(
    fixture: ConfirmationHostFixture,
    result: Intent,
    checkId: String
) {
    assertEquals(
        fixture.screenRequestId,
        result.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
    )
    assertEquals(fixture.operationId, result.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID))
    assertEquals(checkId, result.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID))
    assertEquals(
        fixture.serviceConnectionId,
        result.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
    )
    assertEquals(
        GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT,
        result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS)
    )
}

internal fun createConfirmationHostFixture(
    includeRemainingRule: Boolean
): ConfirmationHostFixture {
    val context = InstrumentationContext.context
    val dataStore = DataStoreManager(context)
    val originalSettings = runBlocking { dataStore.settings.first() }
    val nowMs = System.currentTimeMillis()
    val useDayId = ConfigurableUseDayCalculator(
        resetTime = originalSettings.useDayResetTime
    ).idAt(nowMs)
    val ruleId = "guardian-target-allowance"
    val groupId = "guardian-target"
    val grantMillis = 60L * 60_000L
    val grant = AppRuleGuardianGrant(
        ruleId = ruleId,
        useDayId = useDayId,
        grantedAtMs = nowMs,
        grantedMillis = grantMillis
    )
    val receipt = GuardianApprovalWorkReceipt.Grant(
        grant = GuardianApprovalGrantReceipt(
            ruleId = ruleId,
            useDayId = useDayId,
            grantedAtMs = nowMs,
            grantedMillis = grantMillis
        ),
        origin = GuardianApprovalGrantOrigin.DIRECT,
        useDayGenerationStartedAtMs = originalSettings.useDayGenerationStartedAtMs
    )
    val rules = buildList {
        add(
            AppRule(
                id = ruleId,
                name = "Target allowance",
                weekdays = (0..6).toSet(),
                startMinute = 0,
                endMinute = 0,
                scope = AppRuleScope.forGroup(groupId),
                allowedMinutes = 0
            )
        )
        if (includeRemainingRule) {
            add(
                AppRule(
                    id = "guardian-remaining-denial",
                    name = "Remaining target rule",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(groupId),
                    allowedMinutes = 0
                )
            )
        }
    }
    val snapshot = AppRuleSnapshot(
        appGroups = listOf(
            AppRuleAppGroup(
                id = groupId,
                name = "Guardian target",
                selectedPackages = listOf(GUARDIAN_WORKER_TARGET_PACKAGE)
            )
        ),
        appRules = rules
    ).normalized()
    val overrideState = AppRuleOverrideState(
        useDayId = useDayId,
        useDayGenerationStartedAtMs = originalSettings.useDayGenerationStartedAtMs,
        grants = listOf(grant)
    )
    val seededSettings = updateGuardianHostTestSettings(context) { current ->
        current.copy(
            appRuleSnapshot = snapshot,
            appRuleOverrideState = overrideState
        )
    }
    val screenRequestId = "guardian-host-screen-${java.util.UUID.randomUUID()}"
    val operationId = "guardian-host-operation-${java.util.UUID.randomUUID()}"
    val checkId = "guardian-host-check-${java.util.UUID.randomUUID()}"
    val gateId = "guardian-host-gate-${java.util.UUID.randomUUID()}"
    val workerOutcome = AtomicReference<DecisionOutcome.GuardianApprovalEvaluationReady?>()
    val workerOutcomePublished = CountDownLatch(1)
    val workerOutcomesByCheckId = ConcurrentHashMap<
        String,
        DecisionOutcome.GuardianApprovalEvaluationReady
    >()
    val workerOutcomesPublishedByCheckId = ConcurrentHashMap<String, CountDownLatch>()
    val service = RecordingService().also {
        it.attach(context)
        it.lastBackPressTimeStamp = 0L
    }
    val blocker = AppRuleBlocker().apply {
        wallClockMsProvider = { nowMs }
        activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = service.packageName)
        }
        applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(service.packageName),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }
        screenInteractiveProvider = { true }
        keyguardLockedProvider = { false }
        decisionOutcomeSinkObserver = { outcome ->
            if (outcome is DecisionOutcome.GuardianApprovalEvaluationReady &&
                outcome.request.operationId == operationId
            ) {
                workerOutcomesByCheckId[outcome.request.checkId] = outcome
                workerOutcomesPublishedByCheckId
                    .computeIfAbsent(outcome.request.checkId) { CountDownLatch(1) }
                    .countDown()
                if (outcome.request.checkId == checkId) {
                    workerOutcome.set(outcome)
                    workerOutcomePublished.countDown()
                }
            }
        }
    }
    try {
        blocker.setup(service)
        blocker.setupReceivers()
        // Setup uses Android's live source. The production outcome handler below still reads
        // these same host gates; this only makes the attached instrumentation service's
        // display and foreground facts deterministic.
        setField(blocker, "foregroundObservationSource", null)
        val serviceConnectionId = getField(blocker, "serviceConnectionId") as String
        return ConfirmationHostFixture(
            context = context,
            service = service,
            blocker = blocker,
            seededSettings = seededSettings,
            receipt = receipt,
            screenRequestId = screenRequestId,
            operationId = operationId,
            checkId = checkId,
            gateId = gateId,
            serviceConnectionId = serviceConnectionId,
            targetPackage = GUARDIAN_WORKER_TARGET_PACKAGE,
            workerOutcome = workerOutcome,
            workerOutcomePublished = workerOutcomePublished,
            workerOutcomesByCheckId = workerOutcomesByCheckId,
            workerOutcomesPublishedByCheckId = workerOutcomesPublishedByCheckId
        ) {
            updateGuardianHostTestSettings(context) { current ->
                current.copy(
                    appRuleSnapshot = originalSettings.appRuleSnapshot,
                    appRuleOverrideState = originalSettings.appRuleOverrideState
                )
            }
        }
    } catch (error: Throwable) {
        try {
            blocker.onDestroy()
        } catch (cleanupError: Throwable) {
            if (cleanupError !== error) error.addSuppressed(cleanupError)
        } finally {
            try {
                updateGuardianHostTestSettings(context) { current ->
                    current.copy(
                        appRuleSnapshot = originalSettings.appRuleSnapshot,
                        appRuleOverrideState = originalSettings.appRuleOverrideState
                    )
                }
            } catch (restoreError: Throwable) {
                if (restoreError !== error) error.addSuppressed(restoreError)
            }
        }
        throw error
    }
}

internal fun updateGuardianHostTestSettings(
    context: Context,
    transform: (Settings) -> Settings
): Settings {
    val storeField = DataStoreManager::class.java.getDeclaredField("settingsDataStore").apply {
        isAccessible = true
    }
    @Suppress("UNCHECKED_CAST")
    val store = storeField.get(DataStoreManager(context)) as androidx.datastore.core.DataStore<Settings>
    return runBlocking { store.updateData { current -> transform(current) } }
}

internal fun approvalCheckIntent(
    action: String,
    screenRequestId: String,
    operationId: String,
    checkId: String,
    receipt: GuardianApprovalWorkReceipt,
    connectionId: String
): Intent = Intent(action)
    .putExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE, GUARDIAN_WORKER_TARGET_PACKAGE)
    .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
    .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
    .putExtra(GuardianApprovalActivity.EXTRA_CHECK_ID, checkId)
    .putExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID, connectionId)
    .putExtra(
        GuardianApprovalActivity.EXTRA_RECEIPT_USE_DAY_GENERATION,
        receipt.useDayGenerationStartedAtMs
    )
    .apply {
        when (receipt) {
            is GuardianApprovalWorkReceipt.Grant -> {
                putExtra(
                    GuardianApprovalActivity.EXTRA_APPROVAL_KIND,
                    when (receipt.origin) {
                        GuardianApprovalGrantOrigin.DIRECT ->
                            GuardianApprovalActivity.APPROVAL_KIND_DIRECT
                        GuardianApprovalGrantOrigin.ACCUMULATED_POOL ->
                            GuardianApprovalActivity.APPROVAL_KIND_ACCUMULATED
                    }
                )
                putExtra(GuardianApprovalActivity.EXTRA_RECEIPT_RULE_ID, receipt.grant.ruleId)
                putExtra(GuardianApprovalActivity.EXTRA_RECEIPT_USE_DAY_ID, receipt.grant.useDayId)
                putExtra(
                    GuardianApprovalActivity.EXTRA_RECEIPT_GRANTED_AT_MS,
                    receipt.grant.grantedAtMs
                )
                putExtra(
                    GuardianApprovalActivity.EXTRA_RECEIPT_GRANTED_MILLIS,
                    receipt.grant.grantedMillis
                )
            }
            is GuardianApprovalWorkReceipt.RuleSkip -> {
                putExtra(
                    GuardianApprovalActivity.EXTRA_APPROVAL_KIND,
                    GuardianApprovalActivity.APPROVAL_KIND_SKIP
                )
                putExtra(GuardianApprovalActivity.EXTRA_RECEIPT_RULE_ID, receipt.ruleId)
                putExtra(GuardianApprovalActivity.EXTRA_RECEIPT_USE_DAY_ID, receipt.useDayId)
                putExtra(GuardianApprovalActivity.EXTRA_SKIP_FROM_MS, receipt.skipFromMs)
                putExtra(GuardianApprovalActivity.EXTRA_SKIP_UNTIL_MS, receipt.skipUntilMs)
            }
        }
    }

internal fun guardianLifecycleIntent(
    action: String,
    packageName: String,
    screenRequestId: String,
    connectionId: String,
    previousScreenRequestId: String? = null
): Intent = Intent(action)
    .putExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE, packageName)
    .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
    .putExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID, connectionId)
    .apply {
        previousScreenRequestId?.let {
            putExtra(GuardianApprovalActivity.EXTRA_PREVIOUS_SCREEN_REQUEST_ID, it)
        }
    }

internal class RecordingService : BaseBlockingService() {
    val startedActivities = mutableListOf<Intent>()
    val sentBroadcasts = CopyOnWriteArrayList<Intent>()
    var startActivityObserver: ((Intent) -> Unit)? = null
    var sendBroadcastObserver: ((Intent) -> Unit)? = null
    var sendBroadcastCompletionObserver: ((Intent) -> Unit)? = null
    var windowsReads = 0
    var visibleWindows: List<AccessibilityWindowInfo> = emptyList()
    @Volatile var failNextConfirmationStatus: String? = null
    private val broadcastExpectations = CopyOnWriteArrayList<BroadcastExpectation>()

    fun attach(context: Context) {
        attachBaseContext(context)
    }

    override fun startActivity(intent: Intent) {
        startedActivities += intent
        startActivityObserver?.invoke(intent)
    }

    fun expectBroadcast(predicate: (Intent) -> Boolean): BroadcastExpectation =
        BroadcastExpectation(predicate).also(broadcastExpectations::add)

    override fun sendBroadcast(intent: Intent) {
        val recorded = Intent(intent)
        sentBroadcasts += recorded
        broadcastExpectations.forEach { expectation ->
            expectation.record(recorded)
            if (expectation.isSatisfied) broadcastExpectations.remove(expectation)
        }
        sendBroadcastObserver?.invoke(recorded)
        try {
            val shouldFail = synchronized(this) {
                if (intent.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    failNextConfirmationStatus == intent.getStringExtra(
                        GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS
                    )
                ) {
                    failNextConfirmationStatus = null
                    true
                } else {
                    false
                }
            }
            if (shouldFail) {
                throw IllegalStateException("Injected confirmation broadcast failure")
            }
            super.sendBroadcast(intent)
        } finally {
            sendBroadcastCompletionObserver?.invoke(recorded)
        }
    }

    override fun getWindows(): MutableList<AccessibilityWindowInfo> {
        windowsReads++
        return visibleWindows.toMutableList()
    }
}

internal class BroadcastExpectation(
    private val predicate: (Intent) -> Boolean
) {
    private val result = AtomicReference<Intent?>()
    private val received = CountDownLatch(1)
    val isSatisfied: Boolean
        get() = result.get() != null

    fun record(intent: Intent) {
        if (predicate(intent)) {
            result.compareAndSet(null, Intent(intent))
            received.countDown()
        }
    }

    fun awaitBroadcast(
        description: String,
        timeoutMs: Long = 10_000L
    ): Intent {
        check(received.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for $description"
        }
        return checkNotNull(result.get())
    }
}

internal data class ExternalEffectSnapshot(
    val pendingPermits: Set<Any>,
    val inFlightCallbackCount: Int,
    val owner: GuardianApprovalCoordinator.Owner?
) {
    val pendingPermitCount: Int
        get() = pendingPermits.size
}

internal class ConfirmationHostFixture(
val context: Context,
val service: RecordingService,
val blocker: AppRuleBlocker,
val seededSettings: Settings,
val receipt: GuardianApprovalWorkReceipt,
val screenRequestId: String,
val operationId: String,
val checkId: String,
val gateId: String,
val serviceConnectionId: String,
val targetPackage: String,
val workerOutcome: AtomicReference<DecisionOutcome.GuardianApprovalEvaluationReady?>,
val workerOutcomePublished: CountDownLatch,
private val workerOutcomesByCheckId:
    ConcurrentHashMap<String, DecisionOutcome.GuardianApprovalEvaluationReady>,
private val workerOutcomesPublishedByCheckId: ConcurrentHashMap<String, CountDownLatch>,
private val restoreSettings: () -> Unit
) {
    private val gateIdsToRelease = linkedSetOf<String>()
    private val acceptedGateIds = linkedSetOf<String>()
private val releasedGateIds = linkedSetOf<String>()
private var closed = false

fun armOutcomeGate(gateId: String = this.gateId): Boolean {
            gateIdsToRelease += gateId
    val acknowledgement = service.expectBroadcast {
        it.action == AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE &&
            it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == gateId
    }
    sendTestBroadcast(
        Intent(AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE)
            .putExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID, gateId)
            .putExtra(
                AppRuleBlocker.EXTRA_TEST_ACK_PACKAGE,
                context.packageName
            )
    )
    val accepted = acknowledgement.awaitBroadcast(
        "worker outcome gate arm",
        timeoutMs = 2_000L
    )
        .getBooleanExtra("guardian_test_gate_accepted", false)
            if (accepted) acceptedGateIds += gateId
    return accepted
}

fun armOutcomeGateWhenAvailable(baseGateId: String): String {
    repeat(10) { attempt ->
        val gateId = "$baseGateId-$attempt"
        if (armOutcomeGate(gateId)) return gateId
    }
    error("The previous worker outcome gate did not finish")
}

fun openScreenAndSubmitCheck() {
    sendTestBroadcast(
        guardianLifecycleIntent(
            action = GuardianApprovalActivity.INTENT_ACTION_OPENED,
            packageName = targetPackage,
            screenRequestId = screenRequestId,
            connectionId = serviceConnectionId
        )
    )
    submitCheck(
        action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED,
        checkId = checkId
    )
}

fun submitCheck(action: String, checkId: String) {
    sendTestBroadcast(
        approvalCheckIntent(
            action = action,
            screenRequestId = screenRequestId,
            operationId = operationId,
            checkId = checkId,
            receipt = receipt,
            connectionId = serviceConnectionId
        )
    )
}

fun receiveCheckSynchronously(action: String, checkId: String) {
    invokePrivate(
        blocker,
        "receiveGuardianApprovalCheck",
        approvalCheckIntent(
            action = action,
            screenRequestId = screenRequestId,
            operationId = operationId,
            checkId = checkId,
            receipt = receipt,
            connectionId = serviceConnectionId
        ),
        action == GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
        action == GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER
    )
}

fun awaitWorkerOutcome(
    checkId: String,
    timeoutMs: Long = GUARDIAN_WORKER_TEST_TIMEOUT_MS
): DecisionOutcome.GuardianApprovalEvaluationReady {
    val published = workerOutcomesPublishedByCheckId.computeIfAbsent(checkId) {
        CountDownLatch(1)
    }
    check(published.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        "The actual serialized worker did not publish check $checkId"
    }
    return checkNotNull(workerOutcomesByCheckId[checkId])
}

fun confirmationResults(): List<Intent> = service.sentBroadcasts.filter {
    it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT
}

fun externalEffectSnapshot(): ExternalEffectSnapshot = synchronized(
    getField(blocker, "runtimeLock") as Any
) {
    ExternalEffectSnapshot(
        pendingPermits = (getField(blocker, "pendingExternalEffects") as Set<*>)
            .filterNotNull()
            .toSet(),
        inFlightCallbackCount =
            (getField(blocker, "inFlightCallbacks") as java.util.concurrent.atomic.AtomicInteger)
                .get(),
        owner = (getField(blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
            .currentOwner()
    )
}

        fun releaseOutcomeGate(
            gateId: String = this.gateId,
            requireAccepted: Boolean = gateId in acceptedGateIds
        ) {
            if (gateId !in gateIdsToRelease || gateId in releasedGateIds) return
    val released = service.expectBroadcast {
        it.action == AppRuleBlocker.INTENT_ACTION_TEST_RELEASE_GUARDIAN_EVALUATION_GATE &&
            it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == gateId
    }
    sendTestBroadcast(
        Intent(AppRuleBlocker.INTENT_ACTION_TEST_RELEASE_GUARDIAN_EVALUATION_GATE)
            .putExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID, gateId)
    )
    val acknowledgement = released.awaitBroadcast("worker outcome gate release")
            check(
                !requireAccepted ||
                    acknowledgement.getBooleanExtra("guardian_test_gate_accepted", false)
            ) {
        "the worker outcome gate release was rejected"
    }
    releasedGateIds += gateId
}

fun close() {
    if (closed) return
    closed = true
            var cleanupError: Throwable? = null
            fun recordCleanupError(error: Throwable) {
                val previousError = cleanupError
                if (previousError == null) {
                    cleanupError = error
                } else if (previousError !== error) {
                    previousError.addSuppressed(error)
                }
            }

            gateIdsToRelease.toList().forEach { gateId ->
                try {
                    releaseOutcomeGate(
                        gateId = gateId,
                        requireAccepted = gateId in acceptedGateIds
                    )
                } catch (error: Throwable) {
                    recordCleanupError(error)
                }
            }
            try {
                blocker.onDestroy()
            } catch (error: Throwable) {
                recordCleanupError(error)
            }
            try {
                restoreSettings()
            } catch (error: Throwable) {
                recordCleanupError(error)
    }
            cleanupError?.let { throw it }
}

private fun sendTestBroadcast(intent: Intent) {
    context.sendBroadcast(intent.setPackage(context.packageName))
}
}

internal object InstrumentationContext {
    val context: Context
        get() = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
}

internal fun setField(target: Any, name: String, value: Any?) {
    target.javaClass.getDeclaredField(name).apply {
        isAccessible = true
        set(target, value)
    }
    if (target is AppRuleBlocker && name == "sessionRepository") {
        val service = target.javaClass.getDeclaredField("service").apply {
            isAccessible = true
        }.get(target) as BaseBlockingService
        target.javaClass.getDeclaredField("usageResetRepository").apply {
            isAccessible = true
            set(target, RoomUsageResetRepository(AppDatabase.getInstance(service)))
        }
    }
}

internal fun getField(target: Any, name: String): Any? =
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

internal fun AppRuleBlocker.warningStatusForTest(evaluation: AppRuleEvaluation): String =
    javaClass.getDeclaredMethod("warningStatus", AppRuleEvaluation::class.java).apply {
        isAccessible = true
    }.invoke(this, evaluation) as String

internal fun invokePrivate(target: Any, name: String, vararg args: Any?) {
    val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
    method.isAccessible = true
    method.invoke(target, *args)
}

internal fun invokePrivateResult(target: Any, name: String, vararg args: Any?): Any? {
    val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
    method.isAccessible = true
    return method.invoke(target, *args)
}
