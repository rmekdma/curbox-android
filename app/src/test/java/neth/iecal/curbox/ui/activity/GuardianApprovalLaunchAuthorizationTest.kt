package neth.iecal.curbox.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianApprovalLaunchAuthorizationTest {
    @Test
    fun anotherCurboxActivityInTheForegroundCannotUseTheApprovalRequestToLaunch() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                expected = current,
                current = current,
                activityResumed = false,
                windowFocused = false,
                displayUnlocked = true
            )
        )
    }

    @Test
    fun currentGuardianApprovalCanLaunchWhenItOwnsTheForeground() {
        val current = executionIdentity()

        assertTrue(
            GuardianApprovalLaunchAuthorization.canLaunch(
                expected = current,
                current = current,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true
            )
        )
    }

    @Test
    fun lateResultCannotLaunchAfterTheApprovalRequestChanges() {
        val current = executionIdentity(checkId = "current-check")
        val old = executionIdentity(checkId = "old-check")

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                expected = old,
                current = current,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true
            )
        )
    }

    private fun executionIdentity(
        screenRequestId: String = "guardian-screen-current",
        operationId: String = "guardian-operation-current",
        checkId: String = "guardian-check-current",
        serviceConnectionId: String = "guardian-connection-current"
    ) = GuardianApprovalExecutionIdentity(
        screenRequestId = screenRequestId,
        operationId = operationId,
        checkId = checkId,
        serviceConnectionId = serviceConnectionId
    )
}
