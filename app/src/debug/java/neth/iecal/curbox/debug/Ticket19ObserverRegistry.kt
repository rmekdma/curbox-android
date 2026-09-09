package neth.iecal.curbox.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AcceptedRuleRuntimeSnapshot
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.DecisionOutcomeSink
import neth.iecal.curbox.domain.apprules.DecisionRequest
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SerializedDecisionWorker
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.domain.apprules.SourceOrderReservation
import neth.iecal.curbox.services.AppBlockerService
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object Ticket19ObserverRegistry {
    private const val REFRESH_ACTION = "neth.iecal.curbox.refresh.app_rules"
    private const val SCHEDULER_ACTION =
        "neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE"
    private const val GUARDIAN_CLOSED_ACTION =
        "neth.iecal.curbox.guardian.approval.closed"
    private const val GUARDIAN_OPENED_ACTION =
        "neth.iecal.curbox.guardian.approval.opened"
    private const val MAX_EVENTS = 100
    private const val CALCULATOR_PACKAGE = "com.android.calculator2"
    private const val TEMP_GROUP_PREFIX = "ticket19-calculator-group-"
    private const val TEMP_RULE_PREFIX = "ticket19-calculator-rule-"
    private const val EXTERNAL_OUTCOME_WINDOW_MS = 10_000L
    private const val PRODUCTION_BOUNDARY_TIMEOUT_MS = 5_000L
    private const val MUTATION_TOKEN_EXTRA = "ticket19_run_token"
    private const val MUTATION_OPERATION_EXTRA = "ticket19_mutation_operation"
    private const val MUTATION_RULE_TOKEN_EXTRA = "ticket19_mutation_rule_token"
    private const val MUTATION_BROADCAST_KEY_EXTRA = "ticket19_mutation_broadcast_key"

    private val lock = Any()
    private val gson = Gson()
    private val processToken = UUID.randomUUID().toString()
    private val processStartedAtElapsedMs = SystemClock.elapsedRealtime()
    private var serviceGeneration = 0L
    private var serviceRef: WeakReference<AppBlockerService>? = null
    private var blockerRef: WeakReference<AppRuleBlocker>? = null
    private var serviceIdentity = 0
    private var runtimePublicationCount = 0L
    private var notificationPublicationCount = 0L
    private var evaluationCount = 0L
    private var allowedEvaluationCount = 0L
    private var deniedEvaluationCount = 0L
    private var warningFrameworkBoundaryCount = 0L
    private var refreshContinuationCompletionCount = 0L
    private var lastEvaluatedPackage = ""
    private var externalOutcomeToken = ""
    private var externalOutcomeArmedAtElapsedMs: Long? = null
    private var externalOutcomeWindowState = "DISARMED"
    private var externalOutcomeSourceIdentity: Long? = null
    private var externalOutcomeRequestAccepted = false
    private var externalOutcomeEvaluationDenied = false
    private var externalOutcomeDeniedPublished = false
    private var externalOutcomeWarningCalled = false
    private var causalWrappedWorkerIdentity = 0
    private var temporaryRulePresent = false
    private var temporaryRuleEffectivePresent = false
    private var temporaryRulePendingPresent = false
    private var temporaryRuleEditingPresent = false
    private var temporaryRuleOwnedToken = ""
    private var ruleMutationPreflightReady = false
    private var ruleMutationPreflightReason = "not checked"
    private var ruleCleanupCompletionCount = 0L
    private var mutationRefreshRuleToken = ""
    private var mutationRefreshToken = ""
    private var mutationRefreshOperation = ""
    private var mutationRefreshState = "DISARMED"
    private var mutationRefreshProductionReceiverDetached = false
    private var mutationRefreshBoundaryRevisionBefore: Long? = null
    private var mutationRefreshPid = 0
    private var mutationRefreshDeliveryCount = 0L
    private var mutationRefreshReservationCount = 0L
    private var mutationRefreshPublicationCount = 0L
    private var mutationRefreshCompletionCount = 0L
    private var mutationRefreshSourceIdentity: Long? = null
    private var mutationRefreshRuntimeRevision: Long? = null
    private val mutationRefreshObservedRevisions = mutableSetOf<Long>()
    private var mutationRefreshReceiver: BroadcastReceiver? = null
    private var mutationRefreshPassThroughReceiver: BroadcastReceiver? = null
    private val mutationPassThroughObservations = linkedMapOf<String, PassThroughMutationObservation>()
    private val mutationPassThroughByRevision = mutableMapOf<Long, PassThroughMutationObservation>()
    private val mutationPassThroughObservedRevisions = mutableSetOf<Long>()
    private val pendingProductionBoundaryCaptures = mutableMapOf<Long, ProductionBoundaryCapture>()
    private val productionBoundaryCapturesByRevision = mutableMapOf<Long, ProductionBoundaryCapture>()
    private var mutationRefreshUncorrelatedDeliveryCount = 0L
    private var failNextMutationReceiverUnregister = false
    private var mutationRefreshCleanupRetryCount = 0L
    private var forceImmediatePublicationBeforeReady = false
    private var failNextRuntimePublication = false
    private var runtimeBarrier: RuntimeBarrier? = null
    private val failures = mutableListOf<FailureRecord>()
    private val events = mutableListOf<EventRecord>()

    private data class FailureRecord(
        val stage: String,
        val type: String,
        val message: String,
        val elapsedMs: Long
    )

    private data class EventRecord(
        val name: String,
        val elapsedMs: Long,
        val detail: String
    )

    private data class MutationRefreshContext(
        val ruleToken: String,
        val refreshToken: String,
        val operation: String
    )

    private data class PassThroughMutationObservation(
        val key: String,
        val ruleToken: String,
        var reservationCount: Long = 0L,
        var publicationCount: Long = 0L,
        var revisionBefore: Long? = null,
        var reservation: SourceOrderReservation? = null
    )

    private data class ProductionBoundaryCapture(
        val broadcastKey: String,
        val ruleToken: String,
        val ownedCorrelation: MutationRefreshContext?,
        val revisionBefore: Long,
        val publicationWaiterEntered: CountDownLatch?,
        val reservationReady: CountDownLatch = CountDownLatch(1),
        var state: String = "PENDING",
        var reservation: SourceOrderReservation? = null,
        var failure: Throwable? = null,
        var diagnosticRecorded: Boolean = false
    )

    private data class SequencerSnapshot(
        val sourceOrder: Long,
        val runtimeRevision: Long
    )

    private class RuntimeBarrier(val timeoutMs: Long) {
        val release = CountDownLatch(1)
        var state: String = "ARMED"
        var enteredAtElapsedMs: Long? = null
        var releasedAtElapsedMs: Long? = null
    }

    private fun captureMutationRefreshReservation(
        correlation: MutationRefreshContext,
        reservation: SourceOrderReservation
    ) {
        synchronized(lock) {
            if (mutationRefreshState != "DELIVERED" ||
                mutationRefreshToken != correlation.refreshToken ||
                mutationRefreshOperation != correlation.operation ||
                mutationRefreshRuleToken != correlation.ruleToken
            ) return
            mutationRefreshReservationCount += 1L
            mutationRefreshSourceIdentity = reservation.sourceOrderIdentity.value
            mutationRefreshRuntimeRevision = reservation.runtimeRevision.value
            mutationRefreshState = "RESERVED"
            recordEventLocked(
                "mutation_refresh_reservation",
                "operation=${correlation.operation} ruleToken=${correlation.ruleToken} " +
                    "refreshToken=${correlation.refreshToken} " +
                    "sourceIdentity=${reservation.sourceOrderIdentity.value} " +
                    "runtimeRevision=${reservation.runtimeRevision.value}"
            )
            acknowledgeMutationPublicationLocked(reservation.runtimeRevision.value)
        }
    }

    private fun acknowledgeMutationPublicationLocked(revision: Long) {
        if (mutationRefreshState != "RESERVED" ||
            mutationRefreshRuntimeRevision != revision ||
            revision !in mutationRefreshObservedRevisions
        ) return
        mutationRefreshPublicationCount += 1L
        mutationRefreshState = "PUBLISHED"
        recordEventLocked(
            "mutation_refresh_exact_publication",
            "operation=$mutationRefreshOperation ruleToken=$mutationRefreshRuleToken " +
                "refreshToken=$mutationRefreshToken " +
                "sourceIdentity=$mutationRefreshSourceIdentity runtimeRevision=$revision"
        )
    }

    private fun acknowledgePassThroughPublicationLocked(revision: Long) {
        val observation = mutationPassThroughByRevision[revision] ?: return
        observation.publicationCount += 1L
        recordEventLocked(
            "mutation_pass_through_publication",
            "key=${observation.key} ruleToken=${observation.ruleToken} " +
                "sourceIdentity=${observation.reservation?.sourceOrderIdentity?.value} " +
                "runtimeRevision=$revision " +
                "count=${observation.publicationCount}"
        )
    }

    private fun sequencerSnapshot(sequencer: Any): SequencerSnapshot {
        val sourceOrder = readField(sequencer, "sourceOrder") as java.util.concurrent.atomic.AtomicLong
        val runtimeRevision =
            readField(sequencer, "runtimeRevision") as java.util.concurrent.atomic.AtomicLong
        return SequencerSnapshot(
            sourceOrder = sourceOrder.get(),
            runtimeRevision = runtimeRevision.get()
        )
    }

    private fun failProductionBoundaryCapture(
        capture: ProductionBoundaryCapture,
        stage: String,
        error: Throwable
    ) {
        var shouldRecordDiagnostic = false
        synchronized(lock) {
            if (capture.state != "FAILED") {
                capture.state = "FAILED"
                capture.failure = capture.failure ?: error
            }
            if (pendingProductionBoundaryCaptures[capture.revisionBefore + 1L] === capture) {
                pendingProductionBoundaryCaptures.remove(capture.revisionBefore + 1L)
            }
            if (productionBoundaryCapturesByRevision[capture.revisionBefore + 1L] === capture) {
                productionBoundaryCapturesByRevision.remove(capture.revisionBefore + 1L)
            }
            if (!capture.diagnosticRecorded) {
                capture.diagnosticRecorded = true
                shouldRecordDiagnostic = true
            }
        }
        capture.reservationReady.countDown()
        if (shouldRecordDiagnostic) recordFailure(stage, error)
    }

    private fun clearProductionBoundaryCapturesLocked(cause: Throwable) {
        val captures = pendingProductionBoundaryCaptures.values.toList() +
            productionBoundaryCapturesByRevision.values.toList()
        captures.forEach { capture ->
            capture.state = "FAILED"
            capture.failure = capture.failure ?: cause
            capture.reservationReady.countDown()
        }
        pendingProductionBoundaryCaptures.clear()
        productionBoundaryCapturesByRevision.clear()
    }

    private fun markProductionBoundaryCaptureReady(
        capture: ProductionBoundaryCapture,
        reservation: SourceOrderReservation
    ) {
        synchronized(lock) {
            check(capture.state == "PENDING" &&
                pendingProductionBoundaryCaptures[capture.revisionBefore + 1L] === capture &&
                productionBoundaryCapturesByRevision[capture.revisionBefore + 1L] === capture
            ) { "production boundary placeholder was no longer pending" }
            check(reservation.runtimeRevision.value == capture.revisionBefore + 1L) {
                "production boundary reservation revision mismatch: " +
                    "expected=${capture.revisionBefore + 1L} actual=${reservation.runtimeRevision.value}"
            }
            capture.reservation = reservation
            capture.state = "READY"
            pendingProductionBoundaryCaptures.remove(capture.revisionBefore + 1L)
            if (capture.ownedCorrelation != null) {
                mutationRefreshBoundaryRevisionBefore = capture.revisionBefore
                captureMutationRefreshReservation(capture.ownedCorrelation, reservation)
            } else {
                val observation = mutationPassThroughObservations.getOrPut(
                    capture.broadcastKey
                ) {
                    PassThroughMutationObservation(
                        key = capture.broadcastKey,
                        ruleToken = capture.ruleToken
                    )
                }
                observation.reservationCount += 1L
                observation.revisionBefore = capture.revisionBefore
                observation.reservation = reservation
                mutationPassThroughByRevision[reservation.runtimeRevision.value] = observation
                recordEventLocked(
                    "mutation_pass_through_reservation",
                    "key=${capture.broadcastKey} ruleToken=${capture.ruleToken} " +
                        "revisionBefore=${capture.revisionBefore} " +
                        "sourceIdentity=${reservation.sourceOrderIdentity.value} " +
                        "runtimeRevision=${reservation.runtimeRevision.value} " +
                        "count=${observation.reservationCount}"
                )
            }
        }
        capture.reservationReady.countDown()
    }

    private fun invokeProductionReceiverWithExactCapture(
        blocker: AppRuleBlocker,
        productionReceiver: BroadcastReceiver,
        context: Context?,
        intent: Intent?,
        broadcastKey: String,
        ruleToken: String,
        ownedCorrelation: MutationRefreshContext?
    ): ProductionBoundaryCapture {
        val sequencer = readField(blocker, "sourceOrderSequencer")
        val allocationLock = readField(sequencer, "allocationLock")
        var capture: ProductionBoundaryCapture? = null
        try {
            synchronized(allocationLock) {
                val before = sequencerSnapshot(sequencer)
                val expectedRevision = before.runtimeRevision + 1L
                capture = synchronized(lock) {
                    check(productionBoundaryCapturesByRevision[expectedRevision] == null) {
                        "duplicate production boundary placeholder revision=$expectedRevision"
                    }
                    val waiter = if (forceImmediatePublicationBeforeReady) {
                        forceImmediatePublicationBeforeReady = false
                        CountDownLatch(1)
                    } else {
                        null
                    }
                    ProductionBoundaryCapture(
                        broadcastKey = broadcastKey,
                        ruleToken = ruleToken,
                        ownedCorrelation = ownedCorrelation,
                        revisionBefore = before.runtimeRevision,
                        publicationWaiterEntered = waiter
                    ).also { placeholder ->
                        pendingProductionBoundaryCaptures[expectedRevision] = placeholder
                        productionBoundaryCapturesByRevision[expectedRevision] = placeholder
                    }
                }
                val placeholder = capture ?: error("production boundary placeholder was not created")
                productionReceiver.onReceive(context, intent)
                val after = sequencerSnapshot(sequencer)
                check(after.sourceOrder == before.sourceOrder + 1L &&
                    after.runtimeRevision == before.runtimeRevision + 1L
                ) {
                    "production receiver did not reserve exactly one source/revision pair: " +
                        "before=$before after=$after"
                }
                placeholder.publicationWaiterEntered?.let { waiter ->
                    check(waiter.await(PRODUCTION_BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        "publication observer did not wait for revision=$expectedRevision before READY"
                    }
                }
                val reservation = SourceOrderReservation(
                    sourceOrderIdentity = SourceOrderIdentity(after.sourceOrder),
                    runtimeRevision = RuntimeRevision(after.runtimeRevision)
                )
                markProductionBoundaryCaptureReady(placeholder, reservation)
            }
        } catch (error: Throwable) {
            val failedCapture = capture
            if (failedCapture != null) {
                failProductionBoundaryCapture(
                    failedCapture,
                    "mutation_refresh_boundary_capture",
                    error
                )
            } else {
                recordFailure("mutation_refresh_boundary_capture", error)
            }
            throw error
        }
        return capture ?: error("production boundary capture was not created")
    }

    private fun awaitProductionBoundaryCapture(capture: ProductionBoundaryCapture) {
        if (!capture.reservationReady.await(PRODUCTION_BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            val error = IllegalStateException(
                "production boundary reservation timed out for key=${capture.broadcastKey}"
            )
            failProductionBoundaryCapture(capture, "mutation_refresh_boundary_timeout", error)
            throw error
        }
        capture.failure?.let { throw IllegalStateException("production boundary capture failed", it) }
        check(capture.state == "READY" && capture.reservation != null) {
            "production boundary did not expose a READY reservation for key=${capture.broadcastKey}"
        }
    }

    private fun awaitProductionBoundaryReservation(revision: RuntimeRevision) {
        val capture = synchronized(lock) {
            productionBoundaryCapturesByRevision[revision.value]?.also { placeholder ->
                if (placeholder.state == "PENDING") {
                    placeholder.publicationWaiterEntered?.countDown()
                    recordEventLocked(
                        "mutation_refresh_boundary_placeholder_wait",
                        "revision=${revision.value} revisionBefore=${placeholder.revisionBefore}"
                    )
                }
            }
        } ?: return
        val ready = capture.reservationReady.await(PRODUCTION_BOUNDARY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val failure = capture.failure
        val reservation = capture.reservation
        synchronized(lock) {
            if (pendingProductionBoundaryCaptures[revision.value] === capture) {
                pendingProductionBoundaryCaptures.remove(revision.value)
            }
            if (productionBoundaryCapturesByRevision[revision.value] === capture) {
                productionBoundaryCapturesByRevision.remove(revision.value)
            }
        }
        if (!ready) {
            failProductionBoundaryCapture(
                capture,
                "mutation_refresh_boundary_timeout",
                IllegalStateException(
                    "production boundary reservation was not ready for revision=${revision.value}"
                )
            )
            return
        }
        if (failure != null || capture.state != "READY") {
            return
        }
        if (reservation?.runtimeRevision?.value != revision.value) {
            failProductionBoundaryCapture(
                capture,
                "mutation_refresh_boundary_publication",
                IllegalStateException(
                    "production boundary reservation revision mismatch: " +
                        "expected=${revision.value} actual=${reservation?.runtimeRevision?.value}"
                )
            )
        }
    }

    fun attach(service: AppBlockerService) {
        try {
            val blocker = readField(service, "appRuleBlocker") as AppRuleBlocker
            synchronized(lock) {
                serviceGeneration += 1L
                serviceRef = WeakReference(service)
                blockerRef = WeakReference(blocker)
                serviceIdentity = System.identityHashCode(service)
                mutationRefreshReceiver = null
                mutationRefreshPassThroughReceiver = null
                mutationRefreshProductionReceiverDetached = false
                resetObservationLocked(clearEvents = false)
                recordEventLocked(
                    name = "service_instantiated",
                    detail = "generation=$serviceGeneration identity=$serviceIdentity"
                )
            }
            installPublicationObservers(blocker)
        } catch (error: Throwable) {
            recordFailure("attach", error)
        }
    }

    fun command(name: String, argument: Long): String {
        return command(name, argument.toString())
    }

    fun command(name: String, argument: String?): String {
        try {
            when (name) {
                "snapshot" -> Unit
                "reset_observation" -> synchronized(lock) {
                    resetObservationLocked(clearEvents = true)
                    recordEventLocked("observation_reset", "")
                }
                "arm_runtime_barrier" -> armRuntimeBarrier(longArgument(name, argument))
                "release_runtime_barrier" -> releaseRuntimeBarrier()
                "await_refresh_continuation" -> awaitRefreshContinuation(longArgument(name, argument))
                "await_quiescence" -> awaitQuiescence(longArgument(name, argument))
                "fail_next_runtime_publication" -> synchronized(lock) {
                    failNextRuntimePublication = true
                    recordEventLocked("runtime_failure_armed", "")
                }
                "inject_mutation_unregister_failure" -> synchronized(lock) {
                    failNextMutationReceiverUnregister = true
                    recordEventLocked("mutation_unregister_failure_armed", "debugReceiver")
                }
                "force_mutation_publication_before_ready" -> synchronized(lock) {
                    forceImmediatePublicationBeforeReady = true
                    recordEventLocked("mutation_refresh_boundary_ready_gate_armed", "")
                }
                "retry_mutation_receiver_cleanup" -> retryMutationReceiverCleanup()
                "reapply_app_rule_receivers" -> reapplyAppRuleReceivers()
                "preflight_calculator_rule" -> preflightCalculatorRuleMutation()
                "arm_mutation_probe" -> armMutationProbe(argument)
                "install_calculator_denial_rule" -> installCalculatorDenialRule(argument)
                "remove_calculator_denial_rule" -> removeCalculatorDenialRule(argument)
                "await_calculator_rule_cleanup" -> awaitCalculatorRuleCleanup(argument)
                "await_mutation_refresh" -> awaitMutationRefresh(argument)
                "verify_calculator_rule_absent" -> verifyCalculatorRuleAbsent(argument)
                "arm_external_outcome" -> armExternalOutcome(argument)
                "terminate_process" -> terminateProcess(longArgument(name, argument))
                else -> error("unknown ticket19 observer command: $name")
            }
        } catch (error: Throwable) {
            recordFailure("command:$name", error)
        }
        return snapshot()
    }

    private fun installPublicationObservers(blocker: AppRuleBlocker) {
        val previousRuntimeObserver = blocker.runtimePublicationBeforeWorkerHandoff
        blocker.runtimePublicationBeforeWorkerHandoff = { revision ->
            awaitProductionBoundaryReservation(revision)
            previousRuntimeObserver?.invoke(revision)
            observeRuntimePublication(revision)
        }
        val previousNotificationObserver = blocker.notificationPublicationObserver
        blocker.notificationPublicationObserver = { model ->
            previousNotificationObserver?.invoke(model)
            synchronized(lock) {
                notificationPublicationCount += 1L
                recordEventLocked("notification_published", model.title)
            }
        }
        val previousForegroundObserver = blocker.foregroundEvidenceRecordObserver
        blocker.foregroundEvidenceRecordObserver = { packageName ->
            previousForegroundObserver?.invoke(packageName)
            synchronized(lock) {
                lastEvaluatedPackage = packageName
                recordEventLocked("foreground_evidence", packageName)
            }
        }
        val previousEvaluationObserver = blocker.evaluationResultObserver
        blocker.evaluationResultObserver = { evaluation ->
            previousEvaluationObserver?.invoke(evaluation)
            synchronized(lock) {
                evaluationCount += 1L
                if (evaluation.isAllowed) {
                    allowedEvaluationCount += 1L
                } else {
                    deniedEvaluationCount += 1L
                }
                recordEventLocked(
                    "evaluation_completed",
                    "package=$lastEvaluatedPackage allowed=${evaluation.isAllowed}"
                )
            }
        }
        val previousWarningObserver = blocker.warningBeforeFrameworkCallObserver
        blocker.warningBeforeFrameworkCallObserver = { packageName ->
            previousWarningObserver?.invoke(packageName)
            synchronized(lock) {
                warningFrameworkBoundaryCount += 1L
                val sourceIdentity = externalOutcomeSourceIdentity
                if (externalOutcomeWindowState == "CONSUMED" &&
                    externalOutcomeDeniedPublished &&
                    packageName == CALCULATOR_PACKAGE &&
                    sourceIdentity != null
                ) {
                    externalOutcomeWarningCalled = true
                    recordEventLocked(
                        "causal_warning_framework_call",
                        "token=$externalOutcomeToken package=$packageName " +
                            "sourceIdentity=$sourceIdentity"
                    )
                }
                recordEventLocked("warning_framework_boundary", packageName)
            }
        }
    }

    private fun preflightCalculatorRuleMutation() {
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val state = runBlocking(Dispatchers.IO) {
            inspectRuleState(service, token = null)
        }
        val reasons = buildList {
            if (state.hasAnyTemporaryRule) add("existing ticket19 temporary rule")
            if (state.hasPendingAppRules) add("pending APP_RULES change")
            if (state.timeDelayCanDefer) add("active settings change delay")
            if (state.tamperCanDefer) add("active tamper-gated settings delay")
        }
        synchronized(lock) {
            ruleMutationPreflightReady = reasons.isEmpty()
            ruleMutationPreflightReason = reasons.joinToString().ifEmpty { "ready" }
            recordEventLocked(
                "temporary_rule_preflight",
                "ready=$ruleMutationPreflightReady reason=$ruleMutationPreflightReason"
            )
        }
        check(reasons.isEmpty()) {
            "temporary Calculator rule preflight rejected: ${reasons.joinToString()}"
        }
    }

    private fun armMutationProbe(argument: String?) {
        val pieces = argument.orEmpty().split(':', limit = 2)
        require(pieces.size == 2) { "arm_mutation_probe requires operation:ruleToken" }
        val operation = pieces[0]
        val ruleToken = requireUuid(pieces[1])
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        dispatchMutationRefresh(service, blocker, ruleToken, operation)
    }

    private fun installCalculatorDenialRule(argument: String?) {
        val token = requireUuid(argument)
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val groupId = TEMP_GROUP_PREFIX + token
        val ruleId = TEMP_RULE_PREFIX + token
        synchronized(lock) {
            temporaryRuleOwnedToken = token
            recordEventLocked("temporary_rule_ownership", "token=$token")
        }
        val state = runBlocking(Dispatchers.IO) {
            val preflight = inspectRuleState(service, token = null)
            check(!preflight.hasAnyTemporaryRule) { "ticket19 temporary rule already exists" }
            check(!preflight.hasPendingAppRules) { "pending APP_RULES change exists" }
            check(!preflight.timeDelayCanDefer) { "settings change delay can defer cleanup" }
            check(!preflight.tamperCanDefer) { "tamper gate can defer cleanup" }
            val current = service.dataStoreManager.settings.first().appRuleSnapshot
            val group = AppRuleAppGroup(
                id = groupId,
                name = "Ticket19 Calculator",
                selectedPackages = listOf(CALCULATOR_PACKAGE)
            )
            val rule = AppRule(
                id = ruleId,
                name = "Ticket19 Calculator deny",
                weekdays = (0..6).toSet(),
                startMinute = 0,
                endMinute = 0,
                scope = AppRuleScope.forGroup(groupId),
                allowedMinutes = 0L
            )
            check(service.dataStoreManager.updateAppRuleSnapshot(
                current.copy(
                    appGroups = current.appGroups + group,
                    appRules = current.appRules + rule
                )
            )) { "temporary Calculator rule was rejected" }
            inspectRuleState(service, token)
        }
        check(state.effectivePresent && state.editingPresent && !state.pendingPresent) {
            "temporary Calculator rule was not consistently effective after write: $state"
        }
        synchronized(lock) {
            externalOutcomeToken = token
            updateTemporaryRuleStateLocked(state)
            recordEventLocked("temporary_rule_installed", "token=$token ruleId=$ruleId")
        }
        dispatchMutationRefresh(service, blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker"), token, "install")
    }

    private fun removeCalculatorDenialRule(argument: String?) {
        val token = requireUuid(argument)
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val state = runBlocking(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            val dataStore = readField(
                service.dataStoreManager,
                "settingsDataStore"
            ) as androidx.datastore.core.DataStore<Settings>
            dataStore.updateData { current ->
                val pendingChanges = current.settingsChangeDelayConfig2.pendingChanges.map { change ->
                    stripOwnedPendingChange(change, token)
                }
                current.copy(
                    appRuleSnapshot = stripOwnedRule(current.appRuleSnapshot, token),
                    settingsChangeDelayConfig2 = current.settingsChangeDelayConfig2.copy(
                        pendingChanges = pendingChanges
                    )
                )
            }
            inspectRuleState(service, token)
        }
        check(!state.effectivePresent && !state.pendingPresent && !state.editingPresent) {
            "temporary Calculator rule remained after transactional removal: $state"
        }
        synchronized(lock) {
            updateTemporaryRuleStateLocked(state)
            recordEventLocked(
                "temporary_rule_removal_requested",
                "token=$token effective=false pending=false editing=false"
            )
        }
        dispatchMutationRefresh(service, blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker"), token, "remove")
    }

    private fun stripOwnedRule(snapshot: AppRuleSnapshot, token: String): AppRuleSnapshot =
        snapshot.copy(
            appGroups = snapshot.appGroups.filterNot { it.id == TEMP_GROUP_PREFIX + token },
            appRules = snapshot.appRules.filterNot { it.id == TEMP_RULE_PREFIX + token }
        )

    private fun stripOwnedPendingChange(
        change: neth.iecal.curbox.data.models.PendingSettingsChange,
        token: String
    ): neth.iecal.curbox.data.models.PendingSettingsChange {
        if (change.field != GatedSettingsField.APP_RULES.name) return change
        val snapshot = gson.fromJson(change.newValueJson, AppRuleSnapshot::class.java)
            ?: error("pending APP_RULES payload was null")
        val stripped = stripOwnedRule(snapshot, token)
        if (stripped == snapshot) return change
        return change.copy(
            newValueJson = gson.toJson(stripped),
            appGroupEditModes = change.appGroupEditModes - (TEMP_GROUP_PREFIX + token)
        )
    }

    private fun unregisterMutationReceiver(
        service: AppBlockerService,
        receiver: BroadcastReceiver,
        passThrough: Boolean,
        stage: String
    ): Boolean {
        val registeredReceiver = synchronized(lock) {
            val current = if (passThrough) {
                mutationRefreshPassThroughReceiver
            } else {
                mutationRefreshReceiver
            }
            current?.takeIf { it === receiver }
        } ?: return false
        try {
            val injectFailure = synchronized(lock) {
                if (failNextMutationReceiverUnregister) {
                    failNextMutationReceiverUnregister = false
                    true
                } else {
                    false
                }
            }
            if (injectFailure) {
                error("ticket19 injected mutation receiver unregister failure")
            }
            service.unregisterReceiver(registeredReceiver)
            synchronized(lock) {
                val current = if (passThrough) {
                    mutationRefreshPassThroughReceiver
                } else {
                    mutationRefreshReceiver
                }
                if (current === registeredReceiver) {
                    if (passThrough) {
                        mutationRefreshPassThroughReceiver = null
                    } else {
                        mutationRefreshReceiver = null
                    }
                }
            }
            return true
        } catch (error: Throwable) {
            synchronized(lock) { mutationRefreshState = "FAILED" }
            recordFailure(stage, error)
            return false
        }
    }

    private fun restoreProductionRefreshReceiver(
        service: AppBlockerService,
        productionReceiver: BroadcastReceiver,
        stage: String
    ) {
        val shouldRestore = synchronized(lock) {
            mutationRefreshProductionReceiverDetached
        }
        if (!shouldRestore) return
        try {
            ContextCompat.registerReceiver(
                service,
                productionReceiver,
                IntentFilter(REFRESH_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            synchronized(lock) { mutationRefreshProductionReceiverDetached = false }
        } catch (error: Throwable) {
            synchronized(lock) {
                mutationRefreshProductionReceiverDetached = true
                mutationRefreshState = "FAILED"
            }
            recordFailure(stage, error)
        }
    }

    private fun cleanupMutationReceivers(
        service: AppBlockerService,
        productionReceiver: BroadcastReceiver,
        stage: String
    ) {
        synchronized(lock) { mutationRefreshReceiver }?.let {
            unregisterMutationReceiver(service, it, passThrough = false, stage)
        }
        synchronized(lock) { mutationRefreshPassThroughReceiver }?.let {
            unregisterMutationReceiver(service, it, passThrough = true, stage)
        }
        if (debugMutationReceiversAreClean()) {
            restoreProductionRefreshReceiver(service, productionReceiver, stage)
        }
        synchronized(lock) {
            clearProductionBoundaryCapturesLocked(
                IllegalStateException("$stage cleared pending production boundary captures")
            )
        }
    }

    private fun mutationReceiversAreClean(): Boolean = synchronized(lock) {
        mutationRefreshReceiver == null &&
            mutationRefreshPassThroughReceiver == null &&
            !mutationRefreshProductionReceiverDetached
    }

    private fun debugMutationReceiversAreClean(): Boolean = synchronized(lock) {
        mutationRefreshReceiver == null && mutationRefreshPassThroughReceiver == null
    }

    private fun retryMutationReceiverCleanup() {
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        val productionReceiver = readField(blocker, "refreshReceiver") as BroadcastReceiver
        cleanupMutationReceivers(
            service,
            productionReceiver,
            "mutation_refresh_cleanup_retry"
        )
        synchronized(lock) { mutationRefreshCleanupRetryCount += 1L }
        check(mutationReceiversAreClean()) {
            "mutation receiver cleanup retry did not remove every debug receiver or restore production"
        }
        synchronized(lock) { mutationRefreshState = "DISARMED" }
    }

    private fun dispatchMutationRefresh(
        service: AppBlockerService,
        blocker: AppRuleBlocker,
        ruleToken: String,
        operation: String
    ) {
        require(operation == "install" || operation == "remove")
        val productionReceiver = readField(blocker, "refreshReceiver") as BroadcastReceiver
        cleanupMutationReceivers(
            service,
            productionReceiver,
            "mutation_refresh_receiver_cleanup"
        )
        check(mutationReceiversAreClean()) {
            "previous mutation receiver cleanup failed; retry before arming another probe"
        }
        val refreshToken = UUID.randomUUID().toString()
        synchronized(lock) {
            mutationRefreshRuleToken = ruleToken
            mutationRefreshToken = refreshToken
            mutationRefreshOperation = operation
            mutationRefreshState = "ARMED"
            mutationRefreshPid = 0
            mutationRefreshDeliveryCount = 0L
            mutationRefreshReservationCount = 0L
            mutationRefreshPublicationCount = 0L
            mutationRefreshSourceIdentity = null
            mutationRefreshRuntimeRevision = null
            mutationRefreshBoundaryRevisionBefore = null
            mutationRefreshObservedRevisions.clear()
            mutationPassThroughObservations.clear()
            mutationPassThroughByRevision.clear()
            mutationPassThroughObservedRevisions.clear()
            clearProductionBoundaryCapturesLocked(
                IllegalStateException("mutation probe rearmed before boundary capture completed")
            )
            recordEventLocked(
                "mutation_refresh_sent",
                "operation=$operation ruleToken=$ruleToken refreshToken=$refreshToken"
            )
        }

        try {
            service.unregisterReceiver(productionReceiver)
            synchronized(lock) { mutationRefreshProductionReceiverDetached = true }
        } catch (error: Throwable) {
            synchronized(lock) { mutationRefreshState = "FAILED" }
            recordFailure("mutation_refresh_production_receiver_detach", error)
            throw error
        }

        fun readExtra(intent: Intent?, name: String): String = runCatching {
            intent?.getStringExtra(name).orEmpty()
        }.getOrDefault("")

        fun discardBoundaryCapture(capture: ProductionBoundaryCapture?) {
            capture ?: return
            synchronized(lock) {
                if (pendingProductionBoundaryCaptures[capture.revisionBefore + 1L] === capture) {
                    pendingProductionBoundaryCaptures.remove(capture.revisionBefore + 1L)
                }
                if (capture.state != "READY" &&
                    productionBoundaryCapturesByRevision[capture.revisionBefore + 1L] === capture
                ) {
                    productionBoundaryCapturesByRevision.remove(capture.revisionBefore + 1L)
                }
            }
        }

        lateinit var taggedReceiver: BroadcastReceiver
        lateinit var passThroughReceiver: BroadcastReceiver
        passThroughReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val deliveredToken = readExtra(intent, MUTATION_TOKEN_EXTRA)
                val shouldHandle = synchronized(lock) {
                    (mutationRefreshState == "ARMED" && deliveredToken != mutationRefreshToken) ||
                        mutationRefreshState == "FAILED"
                }
                if (!shouldHandle) return

                val broadcastKey = readExtra(intent, MUTATION_BROADCAST_KEY_EXTRA)
                val deliveredRuleToken = readExtra(intent, MUTATION_RULE_TOKEN_EXTRA)
                var boundaryCapture: ProductionBoundaryCapture? = null
                try {
                    if (broadcastKey.isEmpty() || deliveredRuleToken.isEmpty()) {
                        productionReceiver.onReceive(context, intent)
                    } else {
                        boundaryCapture = invokeProductionReceiverWithExactCapture(
                            blocker,
                            productionReceiver,
                            context,
                            intent,
                            broadcastKey = broadcastKey,
                            ruleToken = deliveredRuleToken,
                            ownedCorrelation = null
                        )
                    }
                    if (boundaryCapture != null) {
                        awaitProductionBoundaryCapture(boundaryCapture)
                    } else {
                        synchronized(lock) {
                            mutationRefreshUncorrelatedDeliveryCount += 1L
                            recordEventLocked(
                                "mutation_refresh_unowned_without_correlation",
                                "token=$deliveredToken"
                            )
                        }
                    }
                } catch (error: Throwable) {
                    recordFailure("mutation_refresh_pass_through_observer", error)
                } finally {
                    discardBoundaryCapture(boundaryCapture)
                    val cleanupAfterDelivery = synchronized(lock) {
                        mutationRefreshState == "FAILED"
                    }
                    if (cleanupAfterDelivery) {
                        val passThroughUnregistered = unregisterMutationReceiver(
                            service,
                            passThroughReceiver,
                            passThrough = true,
                            "mutation_refresh_pass_through_cleanup"
                        )
                        if (passThroughUnregistered && debugMutationReceiversAreClean()) {
                            restoreProductionRefreshReceiver(
                                service,
                                productionReceiver,
                                "mutation_refresh_production_receiver_restore"
                            )
                        }
                    }
                }
            }
        }
        taggedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val deliveredToken = readExtra(intent, MUTATION_TOKEN_EXTRA)
                val ownsDelivery = synchronized(lock) {
                    mutationRefreshState == "ARMED" && mutationRefreshToken == deliveredToken
                }
                if (!ownsDelivery) return

                var boundaryCapture: ProductionBoundaryCapture? = null
                try {
                    val deliveredOperation = readExtra(intent, MUTATION_OPERATION_EXTRA)
                    val deliveredRuleToken = readExtra(intent, MUTATION_RULE_TOKEN_EXTRA)
                    val correlation = synchronized(lock) {
                        check(intent?.action == REFRESH_ACTION) {
                            "owned mutation refresh action mismatch: ${intent?.action}"
                        }
                        check(mutationRefreshState == "ARMED" &&
                            mutationRefreshToken == deliveredToken
                        ) { "owned mutation refresh was no longer armed" }
                        check(mutationRefreshOperation == deliveredOperation &&
                            operation == deliveredOperation
                        ) { "owned mutation refresh operation mismatch: $deliveredOperation" }
                        check(mutationRefreshRuleToken == deliveredRuleToken &&
                            ruleToken == deliveredRuleToken
                        ) { "owned mutation refresh rule token mismatch: $deliveredRuleToken" }
                        check(context?.packageName == service.packageName &&
                            context?.applicationContext === service.applicationContext
                        ) { "owned mutation refresh context/process mismatch" }
                        mutationRefreshDeliveryCount += 1L
                        mutationRefreshPid = Process.myPid()
                        mutationRefreshState = "DELIVERED"
                        MutationRefreshContext(ruleToken, refreshToken, operation).also {
                            recordEventLocked(
                                "mutation_refresh_framework_delivery",
                                "operation=$operation ruleToken=$ruleToken " +
                                    "refreshToken=$refreshToken pid=$mutationRefreshPid"
                            )
                        }
                    }
                    boundaryCapture = invokeProductionReceiverWithExactCapture(
                        blocker,
                        productionReceiver,
                        context,
                        intent,
                        broadcastKey = readExtra(intent, MUTATION_BROADCAST_KEY_EXTRA),
                        ruleToken = deliveredRuleToken,
                        ownedCorrelation = correlation
                    )
                    awaitProductionBoundaryCapture(boundaryCapture!!)
                    val reservation = boundaryCapture?.reservation
                    synchronized(lock) {
                        check((mutationRefreshState == "RESERVED" &&
                            mutationRefreshPublicationCount == 0L) ||
                            (mutationRefreshState == "PUBLISHED" &&
                                mutationRefreshPublicationCount == 1L)
                        ) { "owned mutation refresh publication state mismatch" }
                        check(
                            mutationRefreshToken == deliveredToken &&
                            mutationRefreshOperation == deliveredOperation &&
                            mutationRefreshRuleToken == deliveredRuleToken &&
                            mutationRefreshDeliveryCount == 1L &&
                            mutationRefreshReservationCount == 1L &&
                            mutationRefreshSourceIdentity == reservation?.sourceOrderIdentity?.value &&
                            mutationRefreshRuntimeRevision == reservation?.runtimeRevision?.value
                        ) { "owned mutation refresh source/revision chain mismatch" }
                    }
                } catch (error: Throwable) {
                    synchronized(lock) {
                        mutationRefreshState = "FAILED"
                    }
                    recordFailure("mutation_refresh_owned_delivery", error)
                } finally {
                    discardBoundaryCapture(boundaryCapture)
                    try {
                        abortBroadcast()
                    } catch (error: Throwable) {
                        synchronized(lock) { mutationRefreshState = "FAILED" }
                        recordFailure("mutation_refresh_abort", error)
                    } finally {
                        unregisterMutationReceiver(
                            service,
                            taggedReceiver,
                            passThrough = false,
                            "mutation_refresh_receiver_cleanup"
                        )
                        val keepPassThroughReceiver = synchronized(lock) {
                            mutationRefreshState == "FAILED"
                        }
                        if (!keepPassThroughReceiver) {
                            val passThroughUnregistered = unregisterMutationReceiver(
                                service,
                                passThroughReceiver,
                                passThrough = true,
                                "mutation_refresh_pass_through_cleanup"
                            )
                            if (passThroughUnregistered && debugMutationReceiversAreClean()) {
                                restoreProductionRefreshReceiver(
                                    service,
                                    productionReceiver,
                                    "mutation_refresh_production_receiver_restore"
                                )
                            }
                        }
                    }
                }
            }
        }
        val passThroughFilter = IntentFilter(REFRESH_ACTION).apply { priority = -1 }
        ContextCompat.registerReceiver(
            service,
            passThroughReceiver,
            passThroughFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
        synchronized(lock) { mutationRefreshPassThroughReceiver = passThroughReceiver }
        try {
            val filter = IntentFilter(REFRESH_ACTION).apply { priority = 1_000 }
            ContextCompat.registerReceiver(
                service,
                taggedReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (error: Throwable) {
            val passThroughUnregistered = unregisterMutationReceiver(
                service,
                passThroughReceiver,
                passThrough = true,
                "mutation_refresh_pass_through_cleanup"
            )
            if (passThroughUnregistered && debugMutationReceiversAreClean()) {
                restoreProductionRefreshReceiver(
                    service,
                    productionReceiver,
                    "mutation_refresh_production_receiver_restore"
                )
            }
            throw error
        }
        synchronized(lock) {
            mutationRefreshReceiver = taggedReceiver
        }
    }

    private fun awaitMutationRefresh(argument: String?) {
        val pieces = argument.orEmpty().split(':', limit = 2)
        require(pieces.size == 2) { "await_mutation_refresh requires operation:refreshToken" }
        val operation = pieces[0]
        val refreshToken = requireUuid(pieces[1])
        synchronized(lock) {
            require(mutationRefreshOperation == operation && mutationRefreshToken == refreshToken) {
                "mutation refresh does not match current operation/token"
            }
        }
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = synchronized(lock) {
                mutationRefreshState
            }
            if (state == "FAILED") {
                return
            }
            val published = synchronized(lock) {
                mutationRefreshState == "PUBLISHED" &&
                    mutationRefreshDeliveryCount == 1L &&
                    mutationRefreshReservationCount == 1L &&
                    mutationRefreshPublicationCount == 1L
            }
            if (published) break
            Thread.sleep(10L)
        }
        synchronized(lock) {
            check(mutationRefreshState == "PUBLISHED") {
                "tagged mutation refresh did not reach its exact production publication"
            }
        }
        val completedWork = awaitRuleCleanupWork(blocker, 15_000L)
        runOnMainThread("mutation_refresh_main_queue_ack") { Unit }
        val quiescentWork = awaitRuleCleanupWork(blocker, 15_000L)
        check(completedWork.getValue("callbacks") == quiescentWork.getValue("callbacks")) {
            "mutation refresh lifecycle callback ownership changed after completion: " +
                "$completedWork -> $quiescentWork"
        }
        synchronized(lock) {
            check(mutationRefreshDeliveryCount == 1L &&
                mutationRefreshReservationCount == 1L &&
                mutationRefreshPublicationCount == 1L
            ) { "tagged mutation refresh correlation was not one-to-one" }
            mutationRefreshCompletionCount += 1L
            mutationRefreshState = "COMPLETED"
            recordEventLocked(
                "mutation_refresh_callback_completed",
                "operation=$operation ruleToken=$mutationRefreshRuleToken " +
                    "refreshToken=$refreshToken sourceIdentity=$mutationRefreshSourceIdentity " +
                    "runtimeRevision=$mutationRefreshRuntimeRevision " +
                    "pendingLifecycleCallbacks=${quiescentWork.getValue("callbacks")}"
            )
        }
    }

    private fun awaitCalculatorRuleCleanup(argument: String?) {
        val token = requireUuid(argument)
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        val completedWork = awaitRuleCleanupWork(blocker, 15_000L)
        val state = runBlocking(Dispatchers.IO) { inspectRuleState(service, token) }
        check(!state.effectivePresent && !state.pendingPresent && !state.editingPresent) {
            "temporary Calculator rule cleanup is not complete: $state"
        }
        runOnMainThread("temporary_rule_cleanup_main_queue_ack") { Unit }
        val quiescentWork = awaitRuleCleanupWork(blocker, 15_000L)
        check(completedWork.getValue("callbacks") == quiescentWork.getValue("callbacks")) {
            "temporary rule lifecycle callback ownership changed after worker completion: " +
                "$completedWork -> $quiescentWork"
        }
        synchronized(lock) {
            updateTemporaryRuleStateLocked(state)
            ruleCleanupCompletionCount += 1L
            recordEventLocked(
                "temporary_rule_cleanup_completed",
                "token=$token count=$ruleCleanupCompletionCount " +
                    "pendingLifecycleCallbacks=${quiescentWork.getValue("callbacks")}"
            )
            recordEventLocked(
                "temporary_rule_cleanup_scoped_quiescence",
                "token=$token pendingLifecycleCallbacks=${quiescentWork.getValue("callbacks")}"
            )
        }
    }

    private fun awaitRuleCleanupWork(
        blocker: AppRuleBlocker,
        timeoutMs: Long
    ): Map<String, Int> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var counts = appRuleWorkCounts(blocker)
        while (SystemClock.elapsedRealtime() < deadline) {
            counts = appRuleWorkCounts(blocker)
            if (counts.filterKeys { it != "callbacks" }.values.all { it == 0 }) return counts
            Thread.sleep(10L)
        }
        error("AppRule removal refresh/worker work did not complete within ${timeoutMs}ms: $counts")
    }

    private fun verifyCalculatorRuleAbsent(argument: String?) {
        val token = requireUuid(argument)
        val service = synchronized(lock) { serviceRef?.get() }
            ?: error("no current AppBlockerService")
        val state = runBlocking(Dispatchers.IO) { inspectRuleState(service, token) }
        synchronized(lock) { updateTemporaryRuleStateLocked(state) }
        check(!state.effectivePresent && !state.pendingPresent && !state.editingPresent) {
            "temporary Calculator rule remains in a settings view: $state"
        }
    }

    private fun armExternalOutcome(argument: String?) {
        val token = requireUuid(argument)
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        installCausalWorkerObservers(blocker)
        synchronized(lock) {
            externalOutcomeToken = token
            externalOutcomeArmedAtElapsedMs = SystemClock.elapsedRealtime()
            externalOutcomeWindowState = "ARMED"
            externalOutcomeSourceIdentity = null
            externalOutcomeRequestAccepted = false
            externalOutcomeEvaluationDenied = false
            externalOutcomeDeniedPublished = false
            externalOutcomeWarningCalled = false
            recordEventLocked("external_outcome_armed", "token=$token")
        }
    }

    private fun installCausalWorkerObservers(blocker: AppRuleBlocker) {
        val worker = readField(blocker, "decisionWorker") as SerializedDecisionWorker
        val workerIdentity = System.identityHashCode(worker)
        synchronized(lock) {
            if (causalWrappedWorkerIdentity == workerIdentity) return
        }
        val evaluationField = findField(worker, "onEvaluation")
        @Suppress("UNCHECKED_CAST")
        val previousEvaluation = evaluationField.get(worker) as? ((
            DecisionRequest,
            AcceptedRuleRuntimeSnapshot,
            String,
            AppRulesEvaluation
        ) -> Unit)
        evaluationField.set(
            worker,
            { request: DecisionRequest,
                accepted: AcceptedRuleRuntimeSnapshot,
                packageName: String,
                evaluation: AppRulesEvaluation ->
                observeCausalEvaluation(request, packageName, evaluation)
                previousEvaluation?.invoke(request, accepted, packageName, evaluation)
            }
        )
        val outcomeField = findField(worker, "outcomeSink")
        val previousOutcome = outcomeField.get(worker) as DecisionOutcomeSink
        outcomeField.set(worker, object : DecisionOutcomeSink {
            override fun publish(outcome: DecisionOutcome) {
                observeCausalOutcome(outcome)
                previousOutcome.publish(outcome)
            }
        })
        synchronized(lock) {
            causalWrappedWorkerIdentity = workerIdentity
            recordEventLocked("causal_worker_observers_installed", "worker=$workerIdentity")
        }
    }

    private fun observeCausalEvaluation(
        request: DecisionRequest,
        packageName: String,
        evaluation: AppRulesEvaluation
    ) {
        synchronized(lock) {
            if (externalOutcomeWindowState != "ARMED") return
            val armedAt = externalOutcomeArmedAtElapsedMs ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - armedAt > EXTERNAL_OUTCOME_WINDOW_MS) {
                externalOutcomeWindowState = "EXPIRED"
                recordEventLocked("external_outcome_window_expired", "token=$externalOutcomeToken")
                return
            }
            val signal = request.observation.signal
            if (request.reason != ObservationKind.REAL_EVENT ||
                signal.kind != ObservationKind.REAL_EVENT ||
                signal.eventPackage != CALCULATOR_PACKAGE ||
                packageName != CALCULATOR_PACKAGE
            ) return
            val sourceIdentity = request.sourceOrderIdentity.value
            externalOutcomeWindowState = "CONSUMED"
            externalOutcomeSourceIdentity = sourceIdentity
            externalOutcomeRequestAccepted = true
            externalOutcomeEvaluationDenied = !evaluation.isAllowed
            recordEventLocked(
                "causal_request_accepted",
                "token=$externalOutcomeToken kind=${request.reason} package=$packageName " +
                    "sourceIdentity=$sourceIdentity"
            )
            recordEventLocked(
                "causal_evaluation_completed",
                "token=$externalOutcomeToken package=$packageName allowed=${evaluation.isAllowed} " +
                    "sourceIdentity=$sourceIdentity"
            )
        }
    }

    private fun observeCausalOutcome(outcome: DecisionOutcome) {
        synchronized(lock) {
            val sourceIdentity = externalOutcomeSourceIdentity ?: return
            if (externalOutcomeWindowState != "CONSUMED" ||
                outcome.sourceOrderIdentity.value != sourceIdentity
            ) return
            val denied = outcome.packageDecisions.any {
                it.packageName == CALCULATOR_PACKAGE && !it.isAllowed
            }
            if (!denied) return
            externalOutcomeDeniedPublished = true
            recordEventLocked(
                "causal_denied_outcome_published",
                "token=$externalOutcomeToken package=$CALCULATOR_PACKAGE " +
                    "sourceIdentity=$sourceIdentity"
            )
        }
    }

    private data class TemporaryRuleState(
        val effectivePresent: Boolean,
        val pendingPresent: Boolean,
        val editingPresent: Boolean,
        val hasAnyTemporaryRule: Boolean,
        val hasPendingAppRules: Boolean,
        val timeDelayCanDefer: Boolean,
        val tamperCanDefer: Boolean
    )

    private suspend fun inspectRuleState(
        service: AppBlockerService,
        token: String?
    ): TemporaryRuleState {
        val effective = service.dataStoreManager.settings.first()
        val editing = service.dataStoreManager.settingsForEditing.first()
        val groupId = token?.let { TEMP_GROUP_PREFIX + it }
        val ruleId = token?.let { TEMP_RULE_PREFIX + it }
        fun hasOwned(snapshot: neth.iecal.curbox.data.models.AppRuleSnapshot): Boolean =
            if (token == null) {
                snapshot.appGroups.any { it.id.startsWith(TEMP_GROUP_PREFIX) } ||
                    snapshot.appRules.any { it.id.startsWith(TEMP_RULE_PREFIX) }
            } else {
                snapshot.appGroups.any { it.id == groupId } ||
                    snapshot.appRules.any { it.id == ruleId }
            }
        val pendingAppRules = effective.settingsChangeDelayConfig2.pendingChanges.filter {
            it.field == GatedSettingsField.APP_RULES.name
        }
        val pendingPresent = pendingAppRules.any { change ->
            val snapshot = gson.fromJson(change.newValueJson, AppRuleSnapshot::class.java)
                ?: error("pending APP_RULES payload was null")
            hasOwned(snapshot)
        }
        val delay = effective.settingsChangeDelayConfig2
        return TemporaryRuleState(
            effectivePresent = hasOwned(effective.appRuleSnapshot),
            pendingPresent = pendingPresent,
            editingPresent = hasOwned(editing.appRuleSnapshot),
            hasAnyTemporaryRule = hasOwned(effective.appRuleSnapshot) ||
                hasOwned(editing.appRuleSnapshot) || pendingPresent,
            hasPendingAppRules = pendingAppRules.isNotEmpty(),
            timeDelayCanDefer = delay.isEnabled && delay.delayMinutes > 0,
            tamperCanDefer = delay.requireTamperProtectionOff &&
                effective.antiUninstallConfig2.isEnabled
        )
    }

    private fun updateTemporaryRuleStateLocked(state: TemporaryRuleState) {
        temporaryRuleEffectivePresent = state.effectivePresent
        temporaryRulePendingPresent = state.pendingPresent
        temporaryRuleEditingPresent = state.editingPresent
        temporaryRulePresent = state.effectivePresent || state.pendingPresent || state.editingPresent
    }

    private fun requireUuid(argument: String?): String {
        val value = argument.orEmpty()
        UUID.fromString(value)
        return value
    }

    private fun longArgument(name: String, argument: String?): Long =
        argument?.toLongOrNull() ?: error("$name requires a long argument")

    private fun observeRuntimePublication(revision: RuntimeRevision) {
        var barrier: RuntimeBarrier? = null
        var injectedFailure: Throwable? = null
        synchronized(lock) {
            runtimePublicationCount += 1L
            recordEventLocked("runtime_publication", "count=$runtimePublicationCount")
            if (mutationRefreshState == "DELIVERED" || mutationRefreshState == "RESERVED") {
                mutationRefreshObservedRevisions += revision.value
                acknowledgeMutationPublicationLocked(revision.value)
            }
            if (mutationRefreshPassThroughReceiver != null ||
                mutationPassThroughObservations.isNotEmpty()
            ) {
                mutationPassThroughObservedRevisions += revision.value
                acknowledgePassThroughPublicationLocked(revision.value)
            }
            if (failNextRuntimePublication) {
                failNextRuntimePublication = false
                injectedFailure = IllegalStateException(
                    "ticket19 injected runtime publication failure"
                )
            } else {
                runtimeBarrier?.takeIf { it.state == "ARMED" }?.let {
                    it.state = "ENTERED"
                    it.enteredAtElapsedMs = SystemClock.elapsedRealtime()
                    recordEventLocked("runtime_barrier_entered", "timeoutMs=${it.timeoutMs}")
                    barrier = it
                }
            }
        }
        injectedFailure?.let { error ->
            recordFailure("runtime_publication_injected", error)
            throw error
        }
        barrier?.let { activeBarrier ->
            val released = try {
                activeBarrier.release.await(activeBarrier.timeoutMs, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                recordFailure("runtime_barrier_interrupted", error)
                throw IllegalStateException("ticket19 runtime barrier interrupted", error)
            }
            if (!released) {
                val error = IllegalStateException(
                    "ticket19 runtime barrier timed out after ${activeBarrier.timeoutMs}ms"
                )
                synchronized(lock) {
                    activeBarrier.state = "TIMED_OUT"
                    recordEventLocked("runtime_barrier_timed_out", "")
                }
                recordFailure("runtime_barrier_timeout", error)
                throw error
            }
        }
    }

    private fun armRuntimeBarrier(timeoutMs: Long) {
        require(timeoutMs in 1_000L..30_000L) {
            "runtime barrier timeout must be between 1000ms and 30000ms"
        }
        synchronized(lock) {
            runtimeBarrier?.release?.countDown()
            runtimeBarrier = RuntimeBarrier(timeoutMs)
            recordEventLocked("runtime_barrier_armed", "timeoutMs=$timeoutMs")
        }
    }

    private fun releaseRuntimeBarrier() {
        synchronized(lock) {
            val barrier = runtimeBarrier ?: return
            barrier.state = "RELEASED"
            barrier.releasedAtElapsedMs = SystemClock.elapsedRealtime()
            barrier.release.countDown()
            recordEventLocked("runtime_barrier_released", "")
        }
    }

    private fun awaitRefreshContinuation(timeoutMs: Long) {
        require(timeoutMs in 1_000L..30_000L) {
            "continuation timeout must be between 1000ms and 30000ms"
        }
        synchronized(lock) {
            require(runtimeBarrier?.state == "RELEASED") {
                "runtime barrier must be released before awaiting continuation"
            }
        }
        awaitQuiescenceInternal(timeoutMs)
        synchronized(lock) {
            refreshContinuationCompletionCount += 1L
            recordEventLocked("refresh_continuation_completed", "")
        }
    }

    private fun awaitQuiescence(timeoutMs: Long) {
        awaitQuiescenceInternal(timeoutMs)
        synchronized(lock) {
            recordEventLocked("app_rule_quiescence_acknowledged", "")
        }
    }

    private fun awaitQuiescenceInternal(timeoutMs: Long) {
        require(timeoutMs in 1_000L..30_000L) {
            "quiescence timeout must be between 1000ms and 30000ms"
        }
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (appRuleWorkCounts(blocker).values.all { it == 0 }) return
            Thread.sleep(10L)
        }
        error("AppRule work did not quiesce within ${timeoutMs}ms")
    }

    private fun reapplyAppRuleReceivers() {
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        runOnMainThread("reapply_app_rule_receivers") {
            blocker.setupReceivers()
        }
        synchronized(lock) {
            recordEventLocked("app_rule_receivers_reapplied", "")
        }
    }

    private fun terminateProcess(delayMs: Long) {
        val safeDelayMs = delayMs.coerceIn(100L, 2_000L)
        synchronized(lock) {
            recordEventLocked("process_termination_scheduled", "delayMs=$safeDelayMs")
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            safeDelayMs
        )
    }

    private fun snapshot(): String {
        val service = synchronized(lock) { serviceRef?.get() }
        val blocker = synchronized(lock) { blockerRef?.get() }
        val activeRegistrations = blocker?.let(::activeRegistrations).orEmpty()
        val activeReceiverCount = activeRegistrations.size
        val appRuleDestroyed = blocker?.let { readFieldOrNull(it, "destroyed") as? Boolean }
        val appRuleSetupReady = blocker?.let { readFieldOrNull(it, "setupReady") as? Boolean }
        val appRuleLifecycleGeneration = blocker
            ?.let { readFieldOrNull(it, "lifecycleGeneration") as? java.util.concurrent.atomic.AtomicLong }
            ?.get()
        val serviceScopeActive = service
            ?.let { readFieldOrNull(it, "serviceScope") as? CoroutineScope }
            ?.isActive
        val protectionScopeActive = service
            ?.let { readFieldOrNull(it, "protectionScope") as? CoroutineScope }
            ?.isActive
        val workCounts = blocker?.let(::appRuleWorkCounts).orEmpty()

        return synchronized(lock) {
            JSONObject().apply {
                put("processPid", Process.myPid())
                put("processToken", processToken)
                put("processStartedAtElapsedMs", processStartedAtElapsedMs)
                put("serviceGeneration", serviceGeneration)
                put("serviceIdentity", serviceIdentity)
                put("servicePresent", service != null)
                put("appRuleSetupReady", appRuleSetupReady ?: JSONObject.NULL)
                put("appRuleDestroyed", appRuleDestroyed ?: JSONObject.NULL)
                put("appRuleLifecycleGeneration", appRuleLifecycleGeneration ?: JSONObject.NULL)
                put("serviceScopeActive", serviceScopeActive ?: JSONObject.NULL)
                put("protectionScopeActive", protectionScopeActive ?: JSONObject.NULL)
                put("activeReceiverCount", activeReceiverCount)
                put("receiverOwnership", receiverOwnership(blocker, activeRegistrations))
                put("serviceWideReceiverOwnership", serviceWideReceiverOwnership(service, blocker))
                put("runtimePublicationCount", runtimePublicationCount)
                put("notificationPublicationCount", notificationPublicationCount)
                put("evaluationCount", evaluationCount)
                put("allowedEvaluationCount", allowedEvaluationCount)
                put("deniedEvaluationCount", deniedEvaluationCount)
                put("warningFrameworkBoundaryCount", warningFrameworkBoundaryCount)
                put("lastEvaluatedPackage", lastEvaluatedPackage)
                put("externalOutcomeToken", externalOutcomeToken)
                put(
                    "externalOutcomeArmedAtElapsedMs",
                    externalOutcomeArmedAtElapsedMs ?: JSONObject.NULL
                )
                put("externalOutcomeWindowState", externalOutcomeWindowState)
                put(
                    "externalOutcomeSourceIdentity",
                    externalOutcomeSourceIdentity ?: JSONObject.NULL
                )
                put("externalOutcomeRequestAccepted", externalOutcomeRequestAccepted)
                put("externalOutcomeEvaluationDenied", externalOutcomeEvaluationDenied)
                put("externalOutcomeDeniedPublished", externalOutcomeDeniedPublished)
                put("externalOutcomeWarningCalled", externalOutcomeWarningCalled)
                put("temporaryRulePresent", temporaryRulePresent)
                put("temporaryRuleEffectivePresent", temporaryRuleEffectivePresent)
                put("temporaryRulePendingPresent", temporaryRulePendingPresent)
                put("temporaryRuleEditingPresent", temporaryRuleEditingPresent)
                put("temporaryRuleOwnedToken", temporaryRuleOwnedToken)
                put("ruleMutationPreflightReady", ruleMutationPreflightReady)
                put("ruleMutationPreflightReason", ruleMutationPreflightReason)
                put("ruleCleanupCompletionCount", ruleCleanupCompletionCount)
                put("mutationRefreshRuleToken", mutationRefreshRuleToken)
                put("mutationRefreshToken", mutationRefreshToken)
                put("mutationRefreshOperation", mutationRefreshOperation)
                put("mutationRefreshState", mutationRefreshState)
                put("mutationRefreshPid", mutationRefreshPid)
                put("mutationRefreshDeliveryCount", mutationRefreshDeliveryCount)
                put("mutationRefreshReservationCount", mutationRefreshReservationCount)
                put("mutationRefreshPublicationCount", mutationRefreshPublicationCount)
                put("mutationRefreshCompletionCount", mutationRefreshCompletionCount)
                put(
                    "mutationRefreshSourceIdentity",
                    mutationRefreshSourceIdentity ?: JSONObject.NULL
                )
                put(
                    "mutationRefreshRuntimeRevision",
                    mutationRefreshRuntimeRevision ?: JSONObject.NULL
                )
                put(
                    "mutationRefreshBoundaryRevisionBefore",
                    mutationRefreshBoundaryRevisionBefore ?: JSONObject.NULL
                )
                put("mutationRefreshReceiverRegistered", mutationRefreshReceiver != null)
                put(
                    "mutationRefreshPassThroughReceiverRegistered",
                    mutationRefreshPassThroughReceiver != null
                )
                put(
                    "mutationRefreshProductionReceiverRegistered",
                    !mutationRefreshProductionReceiverDetached
                )
                put(
                    "mutationRefreshProductionReceiverDetached",
                    mutationRefreshProductionReceiverDetached
                )
                put("mutationRefreshCleanupRetryCount", mutationRefreshCleanupRetryCount)
                put(
                    "mutationRefreshImmediatePublicationBeforeReadyArmed",
                    forceImmediatePublicationBeforeReady
                )
                put("mutationRefreshUnregisterFailureInjectionAvailable", true)
                put(
                    "mutationRefreshUnregisterFailureInjectionArmed",
                    failNextMutationReceiverUnregister
                )
                put(
                    "mutationRefreshUncorrelatedDeliveryCount",
                    mutationRefreshUncorrelatedDeliveryCount
                )
                put("mutationPassThroughObservations", JSONArray().apply {
                    mutationPassThroughObservations.values.forEach { observation ->
                        put(JSONObject().apply {
                            put("key", observation.key)
                            put("ruleToken", observation.ruleToken)
                            put("reservationCount", observation.reservationCount)
                            put("publicationCount", observation.publicationCount)
                            put(
                                "revisionBefore",
                                observation.revisionBefore ?: JSONObject.NULL
                            )
                            val reservation = observation.reservation
                            put(
                                "sourceIdentity",
                                reservation?.sourceOrderIdentity?.value ?: JSONObject.NULL
                            )
                            put(
                                "runtimeRevision",
                                reservation?.runtimeRevision?.value ?: JSONObject.NULL
                            )
                        })
                    }
                })
                put("refreshContinuationCompletionCount", refreshContinuationCompletionCount)
                put("workCounts", JSONObject(workCounts))
                put("barrierState", runtimeBarrier?.state ?: "DISARMED")
                put(
                    "barrierEnteredAtElapsedMs",
                    runtimeBarrier?.enteredAtElapsedMs ?: JSONObject.NULL
                )
                put(
                    "barrierReleasedAtElapsedMs",
                    runtimeBarrier?.releasedAtElapsedMs ?: JSONObject.NULL
                )
                put("failures", JSONArray().apply {
                    failures.forEach { failure ->
                        put(JSONObject().apply {
                            put("stage", failure.stage)
                            put("type", failure.type)
                            put("message", failure.message)
                            put("elapsedMs", failure.elapsedMs)
                        })
                    }
                })
                put("events", JSONArray().apply {
                    events.forEach { event ->
                        put(JSONObject().apply {
                            put("name", event.name)
                            put("elapsedMs", event.elapsedMs)
                            put("detail", event.detail)
                        })
                    }
                })
            }.toString()
        }
    }

    private fun activeRegistrations(blocker: AppRuleBlocker): List<Any> {
        val lifecycle = readFieldOrNull(blocker, "receiverLifecycle") ?: return emptyList()
        val registered = readFieldOrNull(lifecycle, "registered") as? Collection<*>
        return registered?.filterNotNull().orEmpty()
    }

    private fun appRuleWorkCounts(blocker: AppRuleBlocker): Map<String, Int> {
        val counts = linkedMapOf(
            "refreshes" to atomicIntField(blocker, "inFlightRefreshes"),
            "notifications" to atomicIntField(blocker, "inFlightNotifications"),
            "callbacks" to atomicIntField(blocker, "inFlightCallbacks"),
            "usageResetCompletions" to atomicIntField(blocker, "inFlightUsageResetCompletions"),
            "recheckPlans" to atomicIntField(blocker, "inFlightRecheckPlans")
        )
        val worker = readFieldOrNull(blocker, "decisionWorker")
        counts["workerQueued"] = worker?.let { atomicIntField(it, "queuedWorkCount") } ?: 0
        counts["workerInFlight"] = worker?.let { atomicIntField(it, "inFlightWorkCount") } ?: 0
        return counts
    }

    private fun atomicIntField(instance: Any, name: String): Int =
        (readFieldOrNull(instance, name) as? AtomicInteger)?.get() ?: 0

    private fun receiverOwnership(
        blocker: AppRuleBlocker?,
        activeRegistrations: List<Any>
    ): JSONArray {
        val definitions = listOf(
            ReceiverDefinition("refreshReceiver", listOf(REFRESH_ACTION), "", true),
            ReceiverDefinition(
                "packageReceiver",
                listOf(
                    "android.intent.action.PACKAGE_ADDED",
                    "android.intent.action.PACKAGE_REMOVED",
                    "android.intent.action.PACKAGE_REPLACED"
                ),
                "package",
                true
            ),
            ReceiverDefinition(
                "screenReceiver",
                listOf(
                    "android.intent.action.SCREEN_ON",
                    "android.intent.action.SCREEN_OFF",
                    "android.intent.action.USER_PRESENT"
                ),
                "",
                true
            ),
            ReceiverDefinition("schedulerWakeReceiver", listOf(SCHEDULER_ACTION), "", false),
            ReceiverDefinition(
                "guardianReceiver",
                listOf(GUARDIAN_CLOSED_ACTION, GUARDIAN_OPENED_ACTION),
                "",
                false
            )
        )
        return JSONArray().apply {
            definitions.forEachIndexed { index, definition ->
                val receiver = blocker?.let { readFieldOrNull(it, definition.fieldName) }
                val registration = activeRegistrations.getOrNull(index)
                put(JSONObject().apply {
                    put("name", definition.fieldName)
                    put("identity", receiver?.let(System::identityHashCode) ?: 0)
                    put(
                        "registrationIdentity",
                        registration?.let(System::identityHashCode) ?: 0
                    )
                    put("ownerServiceIdentity", serviceIdentity)
                    put("active", registration != null)
                    put("actions", JSONArray(definition.actions))
                    put("dataScheme", definition.dataScheme)
                    put("exported", definition.exported)
                })
            }
        }
    }

    private fun serviceWideReceiverOwnership(
        service: AppBlockerService?,
        blocker: AppRuleBlocker?
    ): JSONArray {
        val receivers = mutableListOf<Pair<String, Any?>>()
        if (blocker != null) {
            listOf(
                "refreshReceiver",
                "packageReceiver",
                "screenReceiver",
                "schedulerWakeReceiver",
                "guardianReceiver"
            ).forEach { fieldName ->
                receivers += "AppRuleBlocker.$fieldName" to readFieldOrNull(blocker, fieldName)
            }
        }
        if (service != null) {
            listOf(
                Triple("FocusModeBlocker.refreshReceiver", "focusModeBlocker", "refreshReceiver"),
                Triple("ReelBlocker.refreshReceiver", "reelBlocker", "refreshReceiver"),
                Triple("KeywordBlocker.refreshReceiver", "keywordBlocker", "refreshReceiver"),
                Triple("GrayScaleFilter.refreshReceiver", "grayScaleFilter", "refreshReceiver"),
                Triple("UiHider.refreshReceiver", "uiHider", "refreshReceiver"),
                Triple("NodePicker.receiver", "nodePicker", "receiver"),
                Triple("ReelsCountTracker.refreshReceiver", "reelsCountTracker", "refreshReceiver"),
                Triple("MindfulMessage.intentReceiver", "mindfulMessage", "intentReceiver"),
                Triple("AppUsageTracker.screenReceiver", "appUsageTracker", "screenReceiver"),
                Triple("AppUsageTracker.usageResetReceiver", "appUsageTracker", "usageResetReceiver")
            ).forEach { (name, ownerField, receiverField) ->
                val owner = readFieldOrNull(service, ownerField)
                receivers += name to owner?.let { readFieldOrNull(it, receiverField) }
            }
        }
        return JSONArray().apply {
            receivers.forEach { (name, receiver) ->
                put(JSONObject().apply {
                    put("name", name)
                    put("identity", receiver?.let(System::identityHashCode) ?: 0)
                    put("ownerServiceIdentity", serviceIdentity)
                })
            }
        }
    }

    private data class ReceiverDefinition(
        val fieldName: String,
        val actions: List<String>,
        val dataScheme: String,
        val exported: Boolean
    )

    private fun resetObservationLocked(clearEvents: Boolean) {
        runtimeBarrier?.release?.countDown()
        runtimeBarrier = null
        runtimePublicationCount = 0L
        notificationPublicationCount = 0L
        evaluationCount = 0L
        allowedEvaluationCount = 0L
        deniedEvaluationCount = 0L
        warningFrameworkBoundaryCount = 0L
        refreshContinuationCompletionCount = 0L
        lastEvaluatedPackage = ""
        externalOutcomeToken = ""
        externalOutcomeArmedAtElapsedMs = null
        externalOutcomeWindowState = "DISARMED"
        externalOutcomeSourceIdentity = null
        externalOutcomeRequestAccepted = false
        externalOutcomeEvaluationDenied = false
        externalOutcomeDeniedPublished = false
        externalOutcomeWarningCalled = false
        failNextRuntimePublication = false
        failNextMutationReceiverUnregister = false
        forceImmediatePublicationBeforeReady = false
        mutationRefreshBoundaryRevisionBefore = null
        mutationRefreshUncorrelatedDeliveryCount = 0L
        mutationRefreshCleanupRetryCount = 0L
        clearProductionBoundaryCapturesLocked(IllegalStateException("observation reset"))
        mutationPassThroughObservations.clear()
        mutationPassThroughByRevision.clear()
        mutationPassThroughObservedRevisions.clear()
        failures.clear()
        if (clearEvents) events.clear()
    }

    private fun recordFailure(stage: String, error: Throwable) {
        synchronized(lock) {
            failures += FailureRecord(
                stage = stage,
                type = error.javaClass.name,
                message = error.message.orEmpty(),
                elapsedMs = SystemClock.elapsedRealtime()
            )
            recordEventLocked("failure", "$stage:${error.javaClass.simpleName}")
        }
    }

    private fun recordEventLocked(name: String, detail: String) {
        events += EventRecord(name, SystemClock.elapsedRealtime(), detail)
        if (events.size > MAX_EVENTS) events.removeAt(0)
    }

    private fun runOnMainThread(stage: String, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
        }
        if (!completed.await(10L, TimeUnit.SECONDS)) {
            error("$stage timed out on the service main thread")
        }
        failure?.let { throw it }
    }

    private fun readField(instance: Any, name: String): Any =
        requireNotNull(findField(instance, name).get(instance)) { "$name was null" }

    private fun readFieldOrNull(instance: Any, name: String): Any? =
        runCatching { findField(instance, name).get(instance) }.getOrNull()

    private fun findField(instance: Any, name: String): Field {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("field $name not found on ${instance.javaClass.name}")
    }
}
