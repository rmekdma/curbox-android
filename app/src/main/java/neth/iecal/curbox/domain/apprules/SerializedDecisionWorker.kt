package neth.iecal.curbox.domain.apprules

import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UseDayResetTime
import java.util.concurrent.atomic.AtomicInteger

/** The complete rule inputs accepted by one serialized decision worker generation. */
data class RuleRuntimeSnapshot(
    val snapshot: AppRuleSnapshot,
    val resetTime: UseDayResetTime = UseDayResetTime(),
    val useDayGenerationStartedAtMs: Long = 0L,
    val overrideState: AppRuleOverrideState = AppRuleOverrideState(),
    val launchablePackages: Set<String> = emptySet(),
    val evidencePolicy: ForegroundEvidencePolicySnapshot = ForegroundEvidencePolicySnapshot(),
    val usageTrackingDecision: AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(
        statisticsTrackingEnabled = true,
        hasActiveTimeBasedRules = true
    )
) {
    init {
        require(useDayGenerationStartedAtMs >= 0L) {
            "use-day generation must not be negative"
        }
    }
}

/** Runtime plus the source-owned revision that made it current. */
data class AcceptedRuleRuntimeSnapshot(
    val runtime: RuleRuntimeSnapshot,
    val runtimeRevision: RuntimeRevision
)

/** A refresh publication carries its revision and complete candidate atomically. */
data class RuntimePublication(
    val runtimeRevision: RuntimeRevision,
    val candidateRuntime: RuleRuntimeSnapshot
)

/** Immutable value handed from an observation adapter to the worker queue. */
data class DecisionRequest(
    val sourceOrderIdentity: SourceOrderIdentity,
    val lifecycleGeneration: LifecycleGeneration,
    val reason: ObservationKind,
    val observation: ForegroundFacts,
    val runtimePublication: RuntimePublication? = null
)

enum class SubmissionResult {
    ACCEPTED,
    REJECTED_NOT_READY,
    REJECTED_STALE,
    REJECTED_OVERLAP
}

/** The only result collaborator exposed by the worker. */
interface DecisionOutcomeSink {
    fun publish(outcome: DecisionOutcome)
}

data class PackageDecision(
    val packageName: String,
    val isAllowed: Boolean,
    val denyingRuleIds: List<String>
) {
    init {
        require(packageName.isNotBlank()) { "package decision must name a package" }
    }
}

enum class CommitStatus {
    NOT_REQUIRED,
    COMMITTED,
    FAILED,
    RECOVERY_REQUIRED
}

enum class PublicationStatus {
    PUBLISHED,
    DEFERRED,
    DROPPED_STALE,
    RECOVERABLE_FAILURE
}

data class DecisionOutcome(
    val sourceOrderIdentity: SourceOrderIdentity,
    val lifecycleGeneration: LifecycleGeneration,
    val acceptedRuntimeRevision: RuntimeRevision,
    val packageDecisions: List<PackageDecision>,
    val commitStatus: CommitStatus,
    val followUp: FollowUpKind,
    val publicationStatus: PublicationStatus
)

/** Worker-owned boundary derivation handed to the scheduler adapter as immutable values. */
data class RecheckPlanUpdate(
    val sourceOrderIdentity: SourceOrderIdentity,
    val lifecycleGeneration: LifecycleGeneration,
    val acceptedRuntimeRevision: RuntimeRevision,
    val packageName: String,
    val plan: AppRuleRecheckPlan?,
    /** Identity of the boundary being cancelled, when this update removes an existing plan. */
    val expectedRegistrationSourceOrderIdentity: SourceOrderIdentity? = null
)

enum class StopReason {
    DESTROY,
    RECONNECT,
    REPLACEMENT
}

sealed class StopRequest {
    abstract val requestedAtElapsedMs: Long
    abstract val reason: StopReason
    abstract val lifecycleGeneration: LifecycleGeneration
}

data class RecoveryOnlyStop(
    override val requestedAtElapsedMs: Long,
    override val reason: StopReason,
    override val lifecycleGeneration: LifecycleGeneration
) : StopRequest() {
    init {
        require(requestedAtElapsedMs >= 0L) { "elapsed time must not be negative" }
    }
}

/** One absolute elapsed-time endpoint shared by every candidate drain stage. */
data class TotalDrainDeadline(
    val elapsedRealtimeMs: Long
) {
    init {
        require(elapsedRealtimeMs >= 0L) { "drain deadline must not be negative" }
    }
}

/** Candidate-only drain mode. Production remains recovery-only until D6 selects a budget. */
data class DeadlineDrainStop(
    override val requestedAtElapsedMs: Long,
    val deadline: TotalDrainDeadline,
    override val reason: StopReason,
    override val lifecycleGeneration: LifecycleGeneration
) : StopRequest() {
    init {
        require(requestedAtElapsedMs >= 0L) { "elapsed time must not be negative" }
        require(deadline.elapsedRealtimeMs >= requestedAtElapsedMs) {
            "drain deadline must not precede stop request"
        }
    }
}

sealed class DrainResult {
    data class RecoveryOnly(
        val remainingWork: Boolean,
        val durableRecoveryRequired: Boolean
    ) : DrainResult()

    data class DeadlineDrain(
        val completed: Boolean,
        val timedOut: Boolean,
        val remainingWork: Boolean,
        val durableRecoveryRequired: Boolean,
        val completedAtElapsedMs: Long
    ) : DrainResult()
}

/** Internal measurement of queued and currently executing worker work. */
internal data class WorkerDrainSnapshot(
    val queuedWork: Int,
    val inFlightWork: Int,
    val inFlightDurableWork: Int
) {
    val hasWork: Boolean
        get() = queuedWork > 0 || inFlightWork > 0
}

/**
 * Owns the complete happy-path ordering for foreground rule decisions.
 *
 * The public handoff contains values only. Persistence, the use-day clock and the session ledger
 * stay implementation details of this serialized owner.
 */
class SerializedDecisionWorker internal constructor(
    lifecycleGeneration: LifecycleGeneration,
    acceptedRuntime: AcceptedRuleRuntimeSnapshot,
    private val repository: CurrentUseDaySessionRepository,
    private val outcomeSink: DecisionOutcomeSink,
    private val usageResetRepository: UsageResetRepository,
    private val workerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val elapsedRealtimeMs: () -> Long = {
        System.nanoTime() / 1_000_000L
    },
    private val onNonFatalError: (Throwable) -> Unit = {},
    private val onEvaluation: ((
        DecisionRequest,
        AcceptedRuleRuntimeSnapshot,
        String,
        AppRulesEvaluation
    ) -> Unit)? = null,
    private val onRequestCancellation: ((CancellationException) -> Unit)? = null,
    private val onUsageResetComplete: (UsageResetRequest, Boolean) -> Unit = { _, _ -> },
    private val enforcement: AppRuleEnforcement = AppRuleEnforcement(repository),
    private val onRecheckPlan: ((RecheckPlanUpdate) -> Unit)? = null
) {
    private sealed interface Work {
        data class Decision(val request: DecisionRequest) : Work

        data class UsageReset(
            val request: UsageResetRequest,
            val resetAtElapsedMs: Long
        ) : Work
    }

    private val accepting = AtomicBoolean(true)
    private val requests = Channel<Work>(Channel.UNLIMITED)
    private val queuedWorkCount = AtomicInteger(0)
    private val inFlightWorkCount = AtomicInteger(0)
    private val inFlightDurableWorkCount = AtomicInteger(0)
    private val drainMonitor = Object()
    private val stateLock = Any()
    private val pendingUsageResetPackages = mutableSetOf<String>()
    private val wallClockBoundaries = mutableMapOf<String, Long>()
    private val boundarySourceOrderIdentities = mutableMapOf<String, SourceOrderIdentity>()
    private var currentLifecycleGeneration = lifecycleGeneration
    private var currentAcceptedRuntime = acceptedRuntime
    private var evidenceModule = ForegroundEvidenceModule()
    private val sessionPersistence = SerializedForegroundSessionPersistence(repository)
    private val workerJob: Job = workerScope.launch {
        try {
            for (work in requests) {
                inFlightWorkCount.incrementAndGet()
                inFlightDurableWorkCount.incrementAndGet()
                queuedWorkCount.decrementAndGet()
                try {
                    when (work) {
                        is Work.Decision -> process(work.request)
                        is Work.UsageReset -> processUsageReset(work)
                    }
                } catch (error: CancellationException) {
                    accepting.set(false)
                    requests.close()
                    throw error
                } catch (error: Throwable) {
                    reportNonFatal(error)
                } finally {
                    inFlightDurableWorkCount.decrementAndGet()
                    inFlightWorkCount.decrementAndGet()
                    synchronized(drainMonitor) {
                        drainMonitor.notifyAll()
                    }
                }
            }
        } finally {
            discardQueuedWork()
            accepting.set(false)
            synchronized(drainMonitor) {
                drainMonitor.notifyAll()
            }
        }
    }

    /** Nonblocking value handoff from the accessibility callback. */
    fun submit(request: DecisionRequest): SubmissionResult {
        if (!accepting.get()) return SubmissionResult.REJECTED_NOT_READY
        synchronized(stateLock) {
            if (!accepting.get() || !workerJob.isActive) {
                accepting.set(false)
                return SubmissionResult.REJECTED_NOT_READY
            }
            if (request.lifecycleGeneration != currentLifecycleGeneration) {
                return SubmissionResult.REJECTED_STALE
            }
            queuedWorkCount.incrementAndGet()
            return if (requests.trySend(Work.Decision(request)).isSuccess) {
                SubmissionResult.ACCEPTED
            } else {
                queuedWorkCount.decrementAndGet()
                SubmissionResult.REJECTED_NOT_READY
            }
        }
    }

    /** Enqueues a usage reset behind all earlier foreground work owned by this worker. */
    internal fun submitUsageReset(
        request: UsageResetRequest,
        resetAtElapsedMs: Long
    ): SubmissionResult = synchronized(stateLock) {
        if (!accepting.get() || !workerJob.isActive) {
            accepting.set(false)
            return@synchronized SubmissionResult.REJECTED_NOT_READY
        }
        if (UsageResetCommandPolicy.hasPendingOverlap(
                request.packageNames,
                pendingUsageResetPackages
            )
        ) {
            return@synchronized SubmissionResult.REJECTED_OVERLAP
        }
        pendingUsageResetPackages += request.packageNames
        queuedWorkCount.incrementAndGet()
        if (requests.trySend(Work.UsageReset(request, resetAtElapsedMs)).isSuccess) {
            SubmissionResult.ACCEPTED
        } else {
            queuedWorkCount.decrementAndGet()
            pendingUsageResetPackages.removeAll(request.packageNames)
            SubmissionResult.REJECTED_NOT_READY
        }
    }

    /**
     * Installs a new connection generation. The lifecycle owner supplies both typed values; the
     * worker never allocates or numerically compares either ordering domain.
     */
    internal fun beginLifecycle(
        lifecycleGeneration: LifecycleGeneration,
        acceptedRuntime: AcceptedRuleRuntimeSnapshot
    ) {
        synchronized(stateLock) {
            currentLifecycleGeneration = lifecycleGeneration
            currentAcceptedRuntime = acceptedRuntime
            evidenceModule = ForegroundEvidenceModule()
            sessionPersistence.clear()
            wallClockBoundaries.clear()
            boundarySourceOrderIdentities.clear()
            accepting.set(workerJob.isActive)
        }
    }

    /** True only while this worker can still consume queued foreground work. */
    internal fun isReadyForSubmission(): Boolean =
        accepting.get() && workerJob.isActive

    internal fun drainSnapshot(): WorkerDrainSnapshot = WorkerDrainSnapshot(
        queuedWork = queuedWorkCount.get(),
        inFlightWork = inFlightWorkCount.get(),
        inFlightDurableWork = inFlightDurableWorkCount.get()
    )

    /** Production currently uses recovery-only stop; it invalidates publication immediately. */
    fun stop(request: RecoveryOnlyStop): DrainResult.RecoveryOnly {
        invalidateForStop(request)
        val snapshot = drainSnapshot()
        workerJob.cancel()
        return DrainResult.RecoveryOnly(
            remainingWork = snapshot.hasWork,
            durableRecoveryRequired = snapshot.hasWork
        )
    }

    /**
     * Candidate measurement path. It waits against one absolute endpoint, then cancels any
     * remaining worker work. It is deliberately not used by the production lifecycle yet.
     */
    fun stop(request: DeadlineDrainStop): DrainResult.DeadlineDrain {
        invalidateForStop(request)
        val completed = awaitWorkUntil(request.deadline)
        val completedAt = elapsedRealtimeMs().coerceAtLeast(request.requestedAtElapsedMs)
        // Capture durable recovery before cancellation cleanup can decrement the counters or
        // close the channel. The timeout result is the handoff record for the next process.
        val snapshotAtDeadline = drainSnapshot()
        if (!completed) {
            workerJob.cancel()
        }
        return DrainResult.DeadlineDrain(
            completed = completed,
            timedOut = !completed,
            remainingWork = snapshotAtDeadline.hasWork,
            durableRecoveryRequired = snapshotAtDeadline.hasWork,
            completedAtElapsedMs = completedAt
        )
    }

    private fun invalidateForStop(request: StopRequest) {
        accepting.set(false)
        synchronized(stateLock) {
            currentLifecycleGeneration = request.lifecycleGeneration
            wallClockBoundaries.clear()
            boundarySourceOrderIdentities.clear()
        }
        requests.close()
    }

    private fun discardQueuedWork() {
        while (requests.tryReceive().isSuccess) {
            queuedWorkCount.decrementAndGet()
        }
        synchronized(stateLock) {
            pendingUsageResetPackages.clear()
        }
    }

    private fun awaitWorkUntil(deadline: TotalDrainDeadline): Boolean {
        synchronized(drainMonitor) {
            while (drainSnapshot().hasWork) {
                val remainingMs = deadline.elapsedRealtimeMs - elapsedRealtimeMs()
                if (remainingMs <= 0L) return false
                try {
                    drainMonitor.wait(remainingMs.coerceAtMost(50L))
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return elapsedRealtimeMs() <= deadline.elapsedRealtimeMs
    }

    private suspend fun process(request: DecisionRequest) {
        val accepted = acceptRuntimePublication(request) ?: return
        if (!isCurrent(request, accepted)) return

        val evidence = evidenceModule.classify(
            facts = request.observation,
            policy = accepted.runtime.evidencePolicy
        )
        if (!isCurrent(request, accepted)) return

        val evaluable = evidence.outcomes.filter { outcome ->
            outcome.packageName != null && (
                outcome.decisionPermission == DecisionPermission.EVALUATE ||
                    outcome.decisionPermission == DecisionPermission.EVALUATE_FAIL_CLOSED
                )
        }
        val visiblePackages = evidence.outcomes
            .filterIsInstance<ForegroundEvidenceOutcome.Visible>()
            .filter { it.sessionEffect == SessionEvidenceEffect.RENEW }
            .mapTo(LinkedHashSet()) { it.packageName }
        val endsVisibility = evidence.outcomes.any {
            it is ForegroundEvidenceOutcome.NotVisible &&
                it.sessionEffect == SessionEvidenceEffect.END_WITHOUT_RENEWAL
        }
        val shouldReconcile = visiblePackages.isNotEmpty() || endsVisibility
        val commitStatus = if (shouldReconcile) {
            val reconciled = sessionPersistence.reconcile(
                visiblePackages = visiblePackages,
                nowWallMs = request.observation.capturedAtWallMs,
                nowElapsedMs = request.observation.capturedAtElapsedMs,
                runtime = accepted.runtime
            )
            if (reconciled) {
                CommitStatus.COMMITTED
            } else {
                CommitStatus.FAILED
            }
        } else {
            CommitStatus.NOT_REQUIRED
        }
        if (commitStatus != CommitStatus.NOT_REQUIRED && commitStatus != CommitStatus.COMMITTED) {
            return
        }
        if (!isCurrent(request, accepted)) return

        evidence.outcomes
            .filterIsInstance<ForegroundEvidenceOutcome.NotVisible>()
            .forEach { outcome ->
                if (!isCurrent(request, accepted)) return
                publishRecheckCancellation(
                    request = request,
                    accepted = accepted,
                    packageName = outcome.packageName
                )
            }

        if (evaluable.isEmpty()) {
            if (isCurrent(request, accepted)) {
                publish(
                    request = request,
                    accepted = accepted,
                    packageDecisions = emptyList(),
                    commitStatus = commitStatus,
                    followUp = evidence.outcomes.firstOrNull()?.followUp
                        ?: FollowUpKind.NONE,
                    publicationStatus = if (evidence.outcomes.isEmpty()) {
                        PublicationStatus.PUBLISHED
                    } else {
                        PublicationStatus.DEFERRED
                    }
                )
            }
            return
        }

        val calculator = ConfigurableUseDayCalculator(resetTime = accepted.runtime.resetTime)
        val useDayId = calculator.idAt(request.observation.capturedAtWallMs)
        val evaluatedPackages = mutableListOf<Pair<String, AppRulesEvaluation>>()
        val decisions = mutableListOf<PackageDecision>()
        for (outcome in evaluable) {
            val packageName = outcome.packageName ?: continue
            if (!isCurrent(request, accepted)) return
            val evaluation = when (val result = evaluateRequestSafely(
                    snapshot = accepted.runtime.snapshot,
                    packageName = packageName,
                    useDayId = useDayId,
                    nowMs = request.observation.capturedAtWallMs,
                    calculator = calculator,
                    useDayGenerationStartedAtMs = accepted.runtime.useDayGenerationStartedAtMs,
                    availablePackages = accepted.runtime.launchablePackages,
                    essentialExcludedPackages = accepted.runtime.evidencePolicy.essentialPackages,
                    overrides = accepted.runtime.overrideState
                )) {
                null -> return
                is SafeAppRuleEvaluationResult.Success -> result.evaluation
                SafeAppRuleEvaluationResult.RecoverableFailure -> return
            }
            if (!isCurrent(request, accepted)) return
            try {
                runInterruptible {
                    onEvaluation?.invoke(
                        request,
                        accepted,
                        packageName,
                        evaluation
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportNonFatal(error)
            }
            if (!isCurrent(request, accepted)) return
            evaluatedPackages += packageName to evaluation
            decisions += PackageDecision(
                packageName = packageName,
                isAllowed = evaluation.isAllowed,
                denyingRuleIds = evaluation.denyingRules.map { it.ruleId }
            )
        }
        if (!isCurrent(request, accepted)) return
        evaluatedPackages.forEach { (packageName, evaluation) ->
            if (!isCurrent(request, accepted)) return
            publishRecheckPlan(
                request = request,
                accepted = accepted,
                packageName = packageName,
                evaluation = evaluation,
                useDayId = useDayId,
                calculator = calculator
            )
        }
        if (!isCurrent(request, accepted)) return
        publish(
            request = request,
            accepted = accepted,
            packageDecisions = decisions,
            commitStatus = commitStatus,
            followUp = evaluable.firstOrNull()?.followUp ?: FollowUpKind.NONE,
            publicationStatus = PublicationStatus.PUBLISHED
        )
    }

    /**
     * Evaluator cancellation belongs to one request child. Cancellation of the worker itself
     * still reaches the serialized owner and terminates this generation.
     */
    private suspend fun evaluateRequestSafely(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        nowMs: Long,
        calculator: ConfigurableUseDayCalculator,
        useDayGenerationStartedAtMs: Long,
        availablePackages: Set<String>,
        essentialExcludedPackages: Set<String>,
        overrides: AppRuleOverrideState
    ): SafeAppRuleEvaluationResult? {
        val workerContext = currentCoroutineContext()
        val owningWorkerJob = workerContext[Job]
        val requestJob = SupervisorJob(owningWorkerJob)
        val requestScope = CoroutineScope(workerContext + requestJob)
        return try {
            requestScope.async {
                enforcement.checkSafely(
                    snapshot = snapshot,
                    packageName = packageName,
                    useDayId = useDayId,
                    nowMs = nowMs,
                    calculator = calculator,
                    useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                    availablePackages = availablePackages,
                    essentialExcludedPackages = essentialExcludedPackages,
                    overrides = overrides,
                    onNonFatalError = ::reportNonFatal
                )
            }.await()
        } catch (error: CancellationException) {
            if (owningWorkerJob?.isActive != true) throw error
            try {
                onRequestCancellation?.invoke(error)
            } catch (observerCancellation: CancellationException) {
                throw observerCancellation
            } catch (observerError: Throwable) {
                reportNonFatal(observerError)
            }
            null
        } finally {
            requestJob.cancel()
        }
    }

    private suspend fun publishRecheckPlan(
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot,
        packageName: String,
        evaluation: AppRulesEvaluation,
        useDayId: String,
        calculator: ConfigurableUseDayCalculator
    ) {
        val semanticPlan = AppRuleRecheckPlanner.nextPlan(
            snapshot = accepted.runtime.snapshot,
            evaluation = evaluation,
            overrideState = accepted.runtime.overrideState,
            useDayId = useDayId,
            nowMs = request.observation.capturedAtWallMs,
            useDayGenerationStartedAtMs = accepted.runtime.useDayGenerationStartedAtMs,
            zone = calculator.zone,
            useDayCalculator = calculator
        )
        val plan = semanticPlan?.copy(
            delayMillis = AppRuleWallClockScheduler.delayUntil(
                dueAtWallClockMs = semanticPlan.dueAtWallClockMs,
                nowWallClockMs = request.observation.capturedAtWallMs,
                nowElapsedRealtimeMs = request.observation.capturedAtElapsedMs
            )
        )
        var expectedRegistrationSourceOrderIdentity: SourceOrderIdentity? = null
        synchronized(stateLock) {
            if (!accepting.get() ||
                request.lifecycleGeneration != currentLifecycleGeneration ||
                accepted.runtimeRevision != currentAcceptedRuntime.runtimeRevision
            ) return
            if (plan == null) {
                wallClockBoundaries.remove(packageName)
                expectedRegistrationSourceOrderIdentity =
                    boundarySourceOrderIdentities.remove(packageName)
            } else {
                wallClockBoundaries[packageName] = plan.dueAtWallClockMs
                boundarySourceOrderIdentities[packageName] = request.sourceOrderIdentity
            }
        }
        if (!isCurrent(request, accepted)) return
        try {
            runInterruptible {
                onRecheckPlan?.invoke(
                    RecheckPlanUpdate(
                        sourceOrderIdentity = request.sourceOrderIdentity,
                        lifecycleGeneration = request.lifecycleGeneration,
                        acceptedRuntimeRevision = accepted.runtimeRevision,
                        packageName = packageName,
                        plan = plan,
                        expectedRegistrationSourceOrderIdentity =
                            expectedRegistrationSourceOrderIdentity
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportNonFatal(error)
        }
    }

    private suspend fun publishRecheckCancellation(
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot,
        packageName: String
    ) {
        val expectedRegistrationSourceOrderIdentity = synchronized(stateLock) {
            if (!accepting.get() ||
                request.lifecycleGeneration != currentLifecycleGeneration ||
                accepted.runtimeRevision != currentAcceptedRuntime.runtimeRevision
            ) return
            wallClockBoundaries.remove(packageName)
            boundarySourceOrderIdentities.remove(packageName)
        }
        if (!isCurrent(request, accepted)) return
        try {
            runInterruptible {
                onRecheckPlan?.invoke(
                    RecheckPlanUpdate(
                        sourceOrderIdentity = request.sourceOrderIdentity,
                        lifecycleGeneration = request.lifecycleGeneration,
                        acceptedRuntimeRevision = accepted.runtimeRevision,
                        packageName = packageName,
                        plan = null,
                        expectedRegistrationSourceOrderIdentity =
                            expectedRegistrationSourceOrderIdentity
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportNonFatal(error)
        }
    }

    private suspend fun processUsageReset(work: Work.UsageReset) {
        val restarts = sessionPersistence.usageResetRestarts(work.request)
        try {
            val result = usageResetRepository.resetAndStartSessions(
                request = work.request,
                restarts = restarts
            )
            sessionPersistence.applyUsageReset(
                request = work.request,
                result = result,
                resetAtElapsedMs = work.resetAtElapsedMs,
                restarts = restarts
            )
            publishUsageResetComplete(work.request, succeeded = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportNonFatal(error)
            publishUsageResetComplete(work.request, succeeded = false)
        } finally {
            synchronized(stateLock) {
                pendingUsageResetPackages.removeAll(work.request.packageNames)
            }
        }
    }

    private suspend fun publishUsageResetComplete(
        request: UsageResetRequest,
        succeeded: Boolean
    ) {
        try {
            runInterruptible {
                onUsageResetComplete(request, succeeded)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportNonFatal(error)
        }
    }

    private fun acceptRuntimePublication(request: DecisionRequest): AcceptedRuleRuntimeSnapshot? =
        synchronized(stateLock) {
            if (!accepting.get() || request.lifecycleGeneration != currentLifecycleGeneration) {
                return null
            }
            val publication = request.runtimePublication
            if (publication != null) {
                if (publication.runtimeRevision.value <= currentAcceptedRuntime.runtimeRevision.value) {
                    return null
                }
                currentAcceptedRuntime = AcceptedRuleRuntimeSnapshot(
                    runtime = publication.candidateRuntime,
                    runtimeRevision = publication.runtimeRevision
                )
            }
            currentAcceptedRuntime
        }

    private fun isCurrent(
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot
    ): Boolean = synchronized(stateLock) {
        accepting.get() &&
            request.lifecycleGeneration == currentLifecycleGeneration &&
            accepted.runtimeRevision == currentAcceptedRuntime.runtimeRevision
    }

    private fun publish(
        request: DecisionRequest,
        accepted: AcceptedRuleRuntimeSnapshot,
        packageDecisions: List<PackageDecision>,
        commitStatus: CommitStatus,
        followUp: FollowUpKind,
        publicationStatus: PublicationStatus
    ) {
        if (!isCurrent(request, accepted)) return
        try {
            outcomeSink.publish(
                DecisionOutcome(
                    sourceOrderIdentity = request.sourceOrderIdentity,
                    lifecycleGeneration = request.lifecycleGeneration,
                    acceptedRuntimeRevision = accepted.runtimeRevision,
                    packageDecisions = packageDecisions,
                    commitStatus = commitStatus,
                    followUp = followUp,
                    publicationStatus = publicationStatus
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportNonFatal(error)
        }
    }

    private fun reportNonFatal(error: Throwable) {
        try {
            onNonFatalError(error)
        } catch (_: Throwable) {
            // A logging failure must not terminate the serialized owner.
        }
    }
}

/** Private worker-owned session persistence. */
private class SerializedForegroundSessionPersistence(
    private val repository: CurrentUseDaySessionRepository
) {
    private data class ActiveSession(
        val packageName: String,
        val useDayId: String,
        val sessionId: Long,
        val recordingEnabled: Boolean,
        val statisticsTracked: Boolean,
        var lastCommittedWallMs: Long,
        var lastCommittedElapsedMs: Long
    )

    private val active = LinkedHashMap<String, ActiveSession>()

    suspend fun reconcile(
        visiblePackages: Set<String>,
        nowWallMs: Long,
        nowElapsedMs: Long,
        runtime: RuleRuntimeSnapshot
    ): Boolean {
        val calculator = ConfigurableUseDayCalculator(resetTime = runtime.resetTime)
        val currentUseDayId = calculator.idAt(nowWallMs)
        val usageTrackingDecision = runtime.usageTrackingDecision
        val recordingEnabled = usageTrackingDecision.shouldRecordSessions
        val existingPackages = active.keys.toList()
        val suppressLaunchForRotation = mutableSetOf<String>()
        for (packageName in existingPackages) {
            val session = active[packageName] ?: continue
            val policyRotated = session.recordingEnabled != recordingEnabled ||
                session.statisticsTracked != usageTrackingDecision.recordStatistics
            val remainsVisible = packageName in visiblePackages &&
                session.useDayId == currentUseDayId &&
                !policyRotated
            if (!flush(session, nowWallMs, nowElapsedMs)) return false
            if (!remainsVisible) {
                if (packageName in visiblePackages) {
                    suppressLaunchForRotation += packageName
                }
                repository.finishSession(session.sessionId, nowWallMs)
                active.remove(packageName)
            }
        }
        visiblePackages.forEach { packageName ->
            if (packageName in active || !recordingEnabled) return@forEach
            val id = repository.startSessionAtGeneration(
                useDayId = currentUseDayId,
                packageName = packageName,
                startedAtMs = nowWallMs,
                generationStartedAtMs = runtime.useDayGenerationStartedAtMs,
                statisticsTracked = usageTrackingDecision.recordStatistics
            )
            if (usageTrackingDecision.recordStatistics &&
                packageName !in suppressLaunchForRotation
            ) {
                repository.recordLaunch(
                    useDayId = currentUseDayId,
                    packageName = packageName,
                    launchedAtMs = nowWallMs,
                    generationStartedAtMs = runtime.useDayGenerationStartedAtMs
                )
                repository.recordLaunchStatistics(packageName, nowWallMs)
            }
            active[packageName] = ActiveSession(
                packageName = packageName,
                useDayId = currentUseDayId,
                sessionId = id,
                recordingEnabled = recordingEnabled,
                statisticsTracked = usageTrackingDecision.recordStatistics,
                lastCommittedWallMs = nowWallMs,
                lastCommittedElapsedMs = nowElapsedMs
            )
        }
        return true
    }

    fun usageResetRestarts(request: UsageResetRequest): List<UsageResetSessionRestart> =
        active.values
            .filter { it.packageName in request.packageNames }
            .map { session ->
                UsageResetSessionRestart(
                    useDayId = request.useDayId,
                    packageName = session.packageName,
                    startedAtMs = request.resetAtMs,
                    generationStartedAtMs = request.generationStartedAtMs,
                    activeSessionId = session.sessionId,
                    persistedThroughMs = session.lastCommittedWallMs.coerceAtMost(
                        request.resetAtMs
                    ),
                    statisticsTracked = session.statisticsTracked,
                    recordLaunch = true
                )
            }

    fun applyUsageReset(
        request: UsageResetRequest,
        result: UsageResetResult,
        resetAtElapsedMs: Long,
        restarts: List<UsageResetSessionRestart>
    ) {
        restarts.forEach { restart ->
            val restartedSessionId = checkNotNull(
                result.restartedSessionIds[restart.packageName]
            ) {
                "usage reset did not return a replacement session for ${restart.packageName}"
            }
            check(restartedSessionId != 0L) {
                "usage reset returned an invalid replacement session for ${restart.packageName}"
            }
            val session = active[restart.packageName] ?: return@forEach
            active[restart.packageName] = session.copy(
                useDayId = request.useDayId,
                sessionId = restartedSessionId,
                lastCommittedWallMs = request.resetAtMs,
                lastCommittedElapsedMs = maxOf(
                    resetAtElapsedMs,
                    session.lastCommittedElapsedMs
                )
            )
        }
    }

    fun clear() {
        active.clear()
    }

    private suspend fun flush(
        session: ActiveSession,
        nowWallMs: Long,
        nowElapsedMs: Long
    ): Boolean {
        val endWallMs = maxOf(nowWallMs, session.lastCommittedWallMs)
        if (session.sessionId == 0L) return false
        if (endWallMs > session.lastCommittedWallMs) {
            val usage = if (session.statisticsTracked) {
                splitIntoHourlyCheckpoints(
                    packageName = session.packageName,
                    startWallMs = session.lastCommittedWallMs,
                    endWallMs = endWallMs
                )
            } else {
                emptyList()
            }
            if (!repository.commitSessionCheckpoint(session.sessionId, endWallMs, usage)) {
                return false
            }
        }
        session.lastCommittedWallMs = endWallMs
        session.lastCommittedElapsedMs = maxOf(nowElapsedMs, session.lastCommittedElapsedMs)
        return true
    }

    private fun splitIntoHourlyCheckpoints(
        packageName: String,
        startWallMs: Long,
        endWallMs: Long
    ): List<ForegroundUsageCheckpoint> {
        if (endWallMs <= startWallMs) return emptyList()
        val zone = ZoneId.systemDefault()
        val checkpoints = ArrayList<ForegroundUsageCheckpoint>()
        var cursor = startWallMs
        while (cursor < endWallMs) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = local
                .plusHours(1)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toInstant()
                .toEpochMilli()
            val segmentEnd = minOf(endWallMs, nextHour)
            checkpoints += ForegroundUsageCheckpoint(
                date = TimeTools.dayKey(local.toLocalDate()),
                packageName = packageName,
                hour = local.hour,
                durationMs = segmentEnd - cursor,
                lastUsedMs = segmentEnd
            )
            cursor = segmentEnd
        }
        return checkpoints
    }
}
