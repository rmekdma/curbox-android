package neth.iecal.curbox.ui.activity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import neth.iecal.curbox.domain.apprules.GuardianApprovalEvaluationWindow

internal data class GuardianApprovalLaunchRequest(
    val targetPackageName: String,
    val offer: GuardianApprovalLaunchOffer
)

internal data class GuardianApprovalLaunchRequestState(
    val targetPackageName: String,
    val identity: GuardianApprovalExecutionIdentity,
    val callbacksAllowed: Boolean,
    val confirmationPending: Boolean
)

internal data class GuardianApprovalLaunchSnapshot(
    val requestState: GuardianApprovalLaunchRequestState,
    val activityResumed: Boolean,
    val windowFocused: Boolean,
    val displayUnlocked: Boolean,
    val currentZoneId: String,
    val nowWallClockMs: Long,
    val nowElapsedRealtimeMs: Long
)

internal enum class GuardianApprovalLaunchEffectResult {
    Started,
    Rejected,
    EvaluationWindowInvalid
}

internal enum class GuardianApprovalLaunchOutcome {
    LookupFailed,
    SettingsReadFailed,
    OfferInvalidated,
    DeadlineExpired,
    LaunchStarted,
    LaunchFailed
}

internal interface GuardianApprovalLaunchHost<Target> {
    fun currentRequestState(): GuardianApprovalLaunchRequestState

    fun currentSnapshot(): GuardianApprovalLaunchSnapshot

    suspend fun lookupTargetLaunchIntent(targetPackageName: String): Target?

    suspend fun readCurrentPolicyFingerprint(offer: GuardianApprovalLaunchOffer): String

    fun launchIfCurrent(
        request: GuardianApprovalLaunchRequest,
        target: Target,
        currentPolicyFingerprint: String
    ): GuardianApprovalLaunchEffectResult

    fun applyOutcome(request: GuardianApprovalLaunchRequest, outcome: GuardianApprovalLaunchOutcome)

    fun logNonFatalError(error: Exception)
}

/** Owns offer de-duplication and the suspend ordering for an Activity's current approval screen. */
internal class GuardianApprovalOfferLaunchWorkflow<Target>(
    private val scope: CoroutineScope,
    private val host: GuardianApprovalLaunchHost<Target>
) {
    private data class AttemptKey(
        val offerId: String,
        val identity: GuardianApprovalExecutionIdentity
    )

    private data class Attempt(
        val generation: Long,
        val key: AttemptKey
    )

    private val lock = Any()
    private var generation = 0L
    private var activeAttempt: Attempt? = null
    private var activeJob: Job? = null

    fun accept(request: GuardianApprovalLaunchRequest) {
        if (request.targetPackageName.isBlank() || request.offer.offerId.isBlank()) return
        if (!isCurrentRequest(request, host.currentRequestState())) return

        val key = AttemptKey(request.offer.offerId, request.offer.identity)
        val (attempt, previousJob) = synchronized(lock) {
            if (activeAttempt?.key == key) return
            generation += 1L
            val newAttempt = Attempt(generation, key)
            val oldJob = activeJob
            activeAttempt = newAttempt
            activeJob = null
            newAttempt to oldJob
        }
        previousJob?.cancel()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            runAttempt(attempt, request)
        }
        val shouldStart = synchronized(lock) {
            if (activeAttempt == attempt) {
                activeJob = job
                true
            } else {
                false
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    private suspend fun runAttempt(attempt: Attempt, request: GuardianApprovalLaunchRequest) {
        try {
            val launchTarget = try {
                host.lookupTargetLaunchIntent(request.targetPackageName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                host.logNonFatalError(error)
                null
            }
            if (currentRequestState(attempt, request) == null) return
            if (launchTarget == null) {
                host.applyOutcome(request, GuardianApprovalLaunchOutcome.LookupFailed)
                return
            }

            val currentPolicyFingerprint = try {
                host.readCurrentPolicyFingerprint(request.offer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                host.logNonFatalError(error)
                if (currentRequestState(attempt, request) != null) {
                    release(attempt)
                    host.applyOutcome(request, GuardianApprovalLaunchOutcome.SettingsReadFailed)
                }
                return
            }
            val authorizationSnapshot = currentAuthorizationSnapshot(attempt, request) ?: return
            if (!canLaunch(request.offer, authorizationSnapshot, currentPolicyFingerprint)) {
                release(attempt)
                when {
                    !isEvaluationWindowCurrent(request.offer, authorizationSnapshot) ||
                        request.offer.policyFingerprint != currentPolicyFingerprint -> {
                        host.applyOutcome(request, GuardianApprovalLaunchOutcome.OfferInvalidated)
                    }
                    authorizationSnapshot.requestState.confirmationPending &&
                        authorizationSnapshot.nowElapsedRealtimeMs >=
                        request.offer.deadlineElapsedRealtimeMs -> {
                        host.applyOutcome(request, GuardianApprovalLaunchOutcome.DeadlineExpired)
                    }
                }
                return
            }

            when (host.launchIfCurrent(request, launchTarget, currentPolicyFingerprint)) {
                GuardianApprovalLaunchEffectResult.Started -> {
                    if (isOwner(attempt)) {
                        release(attempt)
                        host.applyOutcome(request, GuardianApprovalLaunchOutcome.LaunchStarted)
                    }
                }
                GuardianApprovalLaunchEffectResult.EvaluationWindowInvalid -> {
                    if (currentRequestState(attempt, request) != null) {
                        release(attempt)
                        host.applyOutcome(request, GuardianApprovalLaunchOutcome.OfferInvalidated)
                    }
                }
                GuardianApprovalLaunchEffectResult.Rejected -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            host.logNonFatalError(error)
            if (currentRequestState(attempt, request) != null) {
                host.applyOutcome(request, GuardianApprovalLaunchOutcome.LaunchFailed)
            }
        } finally {
            synchronized(lock) {
                if (activeAttempt == attempt) activeJob = null
            }
        }
    }

    private fun currentRequestState(
        attempt: Attempt,
        request: GuardianApprovalLaunchRequest
    ): GuardianApprovalLaunchRequestState? {
        if (!isOwner(attempt)) return null
        return host.currentRequestState().takeIf { isCurrentRequest(request, it) }
    }

    private fun currentAuthorizationSnapshot(
        attempt: Attempt,
        request: GuardianApprovalLaunchRequest
    ): GuardianApprovalLaunchSnapshot? {
        if (!isOwner(attempt)) return null
        return host.currentSnapshot().takeIf { isCurrentRequest(request, it.requestState) }
    }

    private fun isOwner(attempt: Attempt): Boolean = synchronized(lock) {
        activeAttempt == attempt
    }

    private fun release(attempt: Attempt) {
        synchronized(lock) {
            if (activeAttempt == attempt) {
                activeAttempt = null
                activeJob = null
            }
        }
    }

    private fun isCurrentRequest(
        request: GuardianApprovalLaunchRequest,
        state: GuardianApprovalLaunchRequestState
    ): Boolean = state.callbacksAllowed &&
        state.confirmationPending &&
        state.identity == request.offer.identity &&
        state.targetPackageName == request.targetPackageName

    private fun canLaunch(
        offer: GuardianApprovalLaunchOffer,
        snapshot: GuardianApprovalLaunchSnapshot,
        currentPolicyFingerprint: String
    ): Boolean = GuardianApprovalLaunchAuthorization.canLaunch(
        offer = offer,
        current = snapshot.requestState.identity,
        confirmationPending = snapshot.requestState.confirmationPending,
        activityResumed = snapshot.activityResumed,
        windowFocused = snapshot.windowFocused,
        displayUnlocked = snapshot.displayUnlocked,
        currentZoneId = snapshot.currentZoneId,
        nowWallClockMs = snapshot.nowWallClockMs,
        nowElapsedRealtimeMs = snapshot.nowElapsedRealtimeMs,
        currentPolicyFingerprint = currentPolicyFingerprint
    )

    private fun isEvaluationWindowCurrent(
        offer: GuardianApprovalLaunchOffer,
        snapshot: GuardianApprovalLaunchSnapshot
    ): Boolean = GuardianApprovalEvaluationWindow.isCurrent(
        evaluationZoneId = offer.evaluationZoneId,
        currentZoneId = snapshot.currentZoneId,
        capturedAtWallClockMs = offer.capturedAtWallClockMs,
        capturedAtElapsedRealtimeMs = offer.capturedAtElapsedRealtimeMs,
        validUntilWallClockMs = offer.validUntilWallClockMs,
        nowWallClockMs = snapshot.nowWallClockMs,
        nowElapsedRealtimeMs = snapshot.nowElapsedRealtimeMs
    )
}
