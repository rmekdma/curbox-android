package neth.iecal.curbox.domain.apprules

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime

/** The complete rule inputs accepted by one serialized decision worker generation. */
data class RuleRuntimeSnapshot(
    val snapshot: AppRuleSnapshot,
    val resetTime: UseDayResetTime = UseDayResetTime(),
    val useDayGenerationStartedAtMs: Long = 0L,
    val overrideState: AppRuleOverrideState = AppRuleOverrideState(),
    val launchablePackages: Set<String> = emptySet(),
    val evidencePolicy: ForegroundEvidencePolicySnapshot = ForegroundEvidencePolicySnapshot()
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
    REJECTED_STALE
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

sealed class DrainResult {
    data class RecoveryOnly(
        val remainingWork: Boolean,
        val durableRecoveryRequired: Boolean
    ) : DrainResult()
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
    private val workerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val onNonFatalError: (Throwable) -> Unit = {},
    private val onEvaluation: ((
        DecisionRequest,
        AcceptedRuleRuntimeSnapshot,
        String,
        AppRulesEvaluation
    ) -> Unit)? = null,
    private val enforcement: AppRuleEnforcement = AppRuleEnforcement(repository),
    private val visibleSessionReconciler: (suspend (
        Set<String>,
        Long,
        Long
    ) -> Boolean)? = null
) {
    private val accepting = AtomicBoolean(true)
    private val requests = Channel<DecisionRequest>(Channel.UNLIMITED)
    private val stateLock = Any()
    private var currentLifecycleGeneration = lifecycleGeneration
    private var currentAcceptedRuntime = acceptedRuntime
    private var evidenceModule = ForegroundEvidenceModule()
    private val sessionLedger = WorkerSessionLedger(repository)
    private val workerJob: Job = workerScope.launch {
        for (request in requests) {
            try {
                process(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportNonFatal(error)
            }
        }
    }

    /** Nonblocking value handoff from the accessibility callback. */
    fun submit(request: DecisionRequest): SubmissionResult {
        if (!accepting.get()) return SubmissionResult.REJECTED_NOT_READY
        synchronized(stateLock) {
            if (!accepting.get()) return SubmissionResult.REJECTED_NOT_READY
            if (request.lifecycleGeneration != currentLifecycleGeneration) {
                return SubmissionResult.REJECTED_STALE
            }
            return if (requests.trySend(request).isSuccess) {
                SubmissionResult.ACCEPTED
            } else {
                SubmissionResult.REJECTED_NOT_READY
            }
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
            sessionLedger.clear()
            accepting.set(true)
        }
    }

    /** Production currently uses recovery-only stop; it invalidates publication immediately. */
    fun stop(request: RecoveryOnlyStop): DrainResult.RecoveryOnly {
        accepting.set(false)
        synchronized(stateLock) {
            currentLifecycleGeneration = request.lifecycleGeneration
        }
        requests.close()
        workerJob.cancel()
        workerScope.cancel()
        return DrainResult.RecoveryOnly(
            remainingWork = true,
            durableRecoveryRequired = true
        )
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
            val reconciled = visibleSessionReconciler?.invoke(
                visiblePackages,
                request.observation.capturedAtWallMs,
                request.observation.capturedAtElapsedMs
            ) ?: sessionLedger.reconcile(
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
        val decisions = evaluable.mapNotNull { outcome ->
            val packageName = outcome.packageName ?: return@mapNotNull null
            val evaluation = enforcement.checkSafely(
                snapshot = accepted.runtime.snapshot,
                packageName = packageName,
                useDayId = useDayId,
                nowMs = request.observation.capturedAtWallMs,
                calculator = calculator,
                useDayGenerationStartedAtMs = accepted.runtime.useDayGenerationStartedAtMs,
                availablePackages = accepted.runtime.launchablePackages,
                essentialExcludedPackages = accepted.runtime.evidencePolicy.essentialPackages,
                overrides = accepted.runtime.overrideState
            )
            try {
                onEvaluation?.invoke(request, accepted, packageName, evaluation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportNonFatal(error)
            }
            PackageDecision(
                packageName = packageName,
                isAllowed = evaluation.isAllowed,
                denyingRuleIds = evaluation.denyingRules.map { it.ruleId }
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
private class WorkerSessionLedger(
    private val repository: CurrentUseDaySessionRepository
) {
    private data class ActiveSession(
        val packageName: String,
        val useDayId: String,
        val sessionId: Long,
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
        val existingPackages = active.keys.toList()
        for (packageName in existingPackages) {
            val session = active[packageName] ?: continue
            val remainsVisible = packageName in visiblePackages && session.useDayId == currentUseDayId
            if (!flush(session, nowWallMs, nowElapsedMs)) return false
            if (!remainsVisible) {
                repository.finishSession(session.sessionId, nowWallMs)
                active.remove(packageName)
            }
        }
        visiblePackages.forEach { packageName ->
            if (packageName in active) return@forEach
            val id = repository.startSessionAtGeneration(
                useDayId = currentUseDayId,
                packageName = packageName,
                startedAtMs = nowWallMs,
                generationStartedAtMs = runtime.useDayGenerationStartedAtMs,
                statisticsTracked = true
            )
            repository.recordLaunch(
                useDayId = currentUseDayId,
                packageName = packageName,
                launchedAtMs = nowWallMs,
                generationStartedAtMs = runtime.useDayGenerationStartedAtMs
            )
            active[packageName] = ActiveSession(
                packageName = packageName,
                useDayId = currentUseDayId,
                sessionId = id,
                lastCommittedWallMs = nowWallMs,
                lastCommittedElapsedMs = nowElapsedMs
            )
        }
        return true
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
            val usage = emptyList<ForegroundUsageCheckpoint>()
            if (!repository.commitSessionCheckpoint(session.sessionId, endWallMs, usage)) {
                return false
            }
        }
        session.lastCommittedWallMs = endWallMs
        session.lastCommittedElapsedMs = maxOf(nowElapsedMs, session.lastCommittedElapsedMs)
        return true
    }
}
