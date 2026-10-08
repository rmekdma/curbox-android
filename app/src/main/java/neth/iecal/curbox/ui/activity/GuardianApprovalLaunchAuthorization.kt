package neth.iecal.curbox.ui.activity

internal data class GuardianApprovalExecutionIdentity(
    val screenRequestId: String,
    val operationId: String,
    val checkId: String,
    val serviceConnectionId: String
)

internal object GuardianApprovalLaunchAuthorization {
    fun canLaunch(
        expected: GuardianApprovalExecutionIdentity,
        current: GuardianApprovalExecutionIdentity,
        activityResumed: Boolean,
        windowFocused: Boolean,
        displayUnlocked: Boolean
    ): Boolean = expected.isComplete() &&
        expected == current &&
        activityResumed &&
        windowFocused &&
        displayUnlocked

    private fun GuardianApprovalExecutionIdentity.isComplete(): Boolean =
        screenRequestId.isNotBlank() &&
            operationId.isNotBlank() &&
            checkId.isNotBlank() &&
            serviceConnectionId.isNotBlank()
}
