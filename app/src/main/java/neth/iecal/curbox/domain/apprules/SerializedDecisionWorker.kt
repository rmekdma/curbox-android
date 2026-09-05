package neth.iecal.curbox.domain.apprules

import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.ZoneId
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
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UseDayResetTime

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
    val plan: AppRuleRecheckPlan?
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
    private val usageResetRepository: UsageResetRepository,
    private val workerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val onNonFatalError: (Throwable) -> Unit = {},
    private val onEvaluation: ((
        DecisionRequest,
        AcceptedRuleRuntimeSnapshot,
        String,
        AppRulesEvaluation
    ) -> Unit)? = null,
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
    private val stateLock = Any()
    private val pendingUsageResetPackages = mutableSetOf<String>()
    private val wallClockBoundaries = mutableMapOf<String, Long>()
    private var currentLifecycleGeneration = lifecycleGeneration
    private var currentAcceptedRuntime = acceptedRuntime
    private var evidenceModule = ForegroundEvidenceModule()
    private val sessionPersistence = SerializedForegroundSessionPersistence(repository)
    private val workerJob: Job = workerScope.launch {
        for (work in requests) {
            try {
                when (work) {
                    is Work.Decision -> process(work.request)
                    is Work.UsageReset -> processUsageReset(work)
                }
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
            return if (requests.trySend(Work.Decision(request)).isSuccess) {
                SubmissionResult.ACCEPTED
            } else {
                SubmissionResult.REJECTED_NOT_READY
            }
        }
    }

    /** Enqueues a usage reset behind all earlier foreground work owned by this worker. */
    internal fun submitUsageReset(
        request: UsageResetRequest,
        resetAtElapsedMs: Long
    ): SubmissionResult = synchronized(stateLock) {
        if (!accepting.get()) return@synchronized SubmissionResult.REJECTED_NOT_READY
        if (UsageResetCommandPolicy.hasPendingOverlap(
                request.packageNames,
                pendingUsageResetPackages
            )
        ) {
            return@synchronized SubmissionResult.REJECTED_OVERLAP
        }
        pendingUsageResetPackages += request.packageNames
        if (requests.trySend(Work.UsageReset(request, resetAtElapsedMs)).isSuccess) {
            SubmissionResult.ACCEPTED
        } else {
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
            accepting.set(true)
        }
    }

    /** Production currently uses recovery-only stop; it invalidates publication immediately. */
    fun stop(request: RecoveryOnlyStop): DrainResult.RecoveryOnly {
        accepting.set(false)
        synchronized(stateLock) {
            currentLifecycleGeneration = request.lifecycleGeneration
            wallClockBoundaries.clear()
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
            evaluatedPackages += packageName to evaluation
            PackageDecision(
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

    private fun publishRecheckPlan(
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
        synchronized(stateLock) {
            if (!accepting.get() ||
                request.lifecycleGeneration != currentLifecycleGeneration ||
                accepted.runtimeRevision != currentAcceptedRuntime.runtimeRevision
            ) return
            if (plan == null) {
                wallClockBoundaries.remove(packageName)
            } else {
                wallClockBoundaries[packageName] = plan.dueAtWallClockMs
            }
        }
        try {
            onRecheckPlan?.invoke(
                RecheckPlanUpdate(
                    sourceOrderIdentity = request.sourceOrderIdentity,
                    lifecycleGeneration = request.lifecycleGeneration,
                    acceptedRuntimeRevision = accepted.runtimeRevision,
                    packageName = packageName,
                    plan = plan
                )
            )
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

    private fun publishUsageResetComplete(request: UsageResetRequest, succeeded: Boolean) {
        try {
            onUsageResetComplete(request, succeeded)
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
