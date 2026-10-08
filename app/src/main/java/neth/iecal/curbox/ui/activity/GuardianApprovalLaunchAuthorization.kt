package neth.iecal.curbox.ui.activity

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
    val deadlineElapsedRealtimeMs: Long
)

internal object GuardianApprovalLaunchAuthorization {
    fun canLaunch(
        offer: GuardianApprovalLaunchOffer,
        current: GuardianApprovalExecutionIdentity,
        confirmationPending: Boolean,
        activityResumed: Boolean,
        windowFocused: Boolean,
        displayUnlocked: Boolean,
        nowElapsedRealtimeMs: Long,
        currentPolicyFingerprint: String
    ): Boolean = offer.identity.isComplete() &&
        offer.offerId.isNotBlank() &&
        offer.policyFingerprint.isNotBlank() &&
        offer.policyFingerprint == currentPolicyFingerprint &&
        offer.runtimeRevision >= 0L &&
        offer.deadlineElapsedRealtimeMs > nowElapsedRealtimeMs &&
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
