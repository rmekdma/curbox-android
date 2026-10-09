package neth.iecal.curbox.ui.activity

import neth.iecal.curbox.domain.apprules.GuardianApprovalEvaluationWindow

internal data class GuardianApprovalExecutionIdentity(
    val screenRequestId: String,
    val operationId: String,
    val checkId: String,
    val serviceConnectionId: String
)

internal data class GuardianApprovalLaunchOffer(
    val identity: GuardianApprovalExecutionIdentity,
    val offerId: String,
    val policyFingerprint: String,
    val runtimeRevision: Long,
    val deadlineElapsedRealtimeMs: Long,
    val evaluationZoneId: String,
    val capturedAtWallClockMs: Long,
    val capturedAtElapsedRealtimeMs: Long,
    val validUntilWallClockMs: Long
)

internal object GuardianApprovalLaunchAuthorization {
    fun canLaunch(
        offer: GuardianApprovalLaunchOffer,
        current: GuardianApprovalExecutionIdentity,
        confirmationPending: Boolean,
        activityResumed: Boolean,
        windowFocused: Boolean,
        displayUnlocked: Boolean,
        currentZoneId: String,
        nowWallClockMs: Long,
        nowElapsedRealtimeMs: Long,
        currentPolicyFingerprint: String
    ): Boolean = offer.identity.isComplete() &&
        offer.offerId.isNotBlank() &&
        offer.policyFingerprint.isNotBlank() &&
        offer.policyFingerprint == currentPolicyFingerprint &&
        offer.runtimeRevision >= 0L &&
        offer.deadlineElapsedRealtimeMs > nowElapsedRealtimeMs &&
        GuardianApprovalEvaluationWindow.isCurrent(
            evaluationZoneId = offer.evaluationZoneId,
            currentZoneId = currentZoneId,
            capturedAtWallClockMs = offer.capturedAtWallClockMs,
            capturedAtElapsedRealtimeMs = offer.capturedAtElapsedRealtimeMs,
            validUntilWallClockMs = offer.validUntilWallClockMs,
            nowWallClockMs = nowWallClockMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs
        ) &&
        offer.identity == current &&
        confirmationPending &&
        activityResumed &&
        windowFocused &&
        displayUnlocked

    private fun GuardianApprovalExecutionIdentity.isComplete(): Boolean =
        screenRequestId.isNotBlank() &&
            operationId.isNotBlank() &&
            checkId.isNotBlank() &&
            serviceConnectionId.isNotBlank()
}
