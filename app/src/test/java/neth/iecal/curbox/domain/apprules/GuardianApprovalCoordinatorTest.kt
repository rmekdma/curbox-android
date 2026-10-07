package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianApprovalCoordinatorTest {
    private val receipt = GuardianApprovalGrantReceipt(
        ruleId = "usage",
        useDayId = "2026-10-07",
        grantedAtMs = 10L,
        grantedMillis = 15 * 60_000L
    )

    @Test
    fun timeoutFencesLateResultAndRetryReusesReceiptWithANewCheckIdentity() {
        val coordinator = GuardianApprovalCoordinator()
        assertTrue(coordinator.openScreen("screen-1", TARGET_PACKAGE, LifecycleGeneration(3L)))
        assertTrue(coordinator.beginDirectCheck("screen-1", "grant-1", "check-1", receipt))
        assertFalse(coordinator.beginDirectCheck("screen-1", "grant-1", "check-1", receipt))

        assertTrue(coordinator.timeOutDirectCheck("screen-1", "grant-1", "check-1"))
        assertFalse(coordinator.completeDirectCheck("screen-1", "grant-1", "check-1"))
        assertTrue(coordinator.retryDirectCheck("screen-1", "grant-1", "check-2"))

        val current = coordinator.currentOwner()
        assertEquals("grant-1", (current?.operation as GuardianApprovalCoordinator.Operation.Direct).operationId)
        assertEquals("check-2", (current.operation as GuardianApprovalCoordinator.Operation.Direct).checkId)
        assertEquals(receipt, (current.operation as GuardianApprovalCoordinator.Operation.Direct).receipt)
        assertFalse(coordinator.completeDirectCheck("screen-1", "grant-1", "check-1"))
        assertTrue(coordinator.completeDirectCheck("screen-1", "grant-1", "check-2"))
    }

    @Test
    fun homeCancellationPreventsLateAllowFromCompletingTheScreenRequest() {
        val coordinator = GuardianApprovalCoordinator()
        assertTrue(coordinator.openScreen("screen-2", TARGET_PACKAGE, LifecycleGeneration(4L)))
        assertTrue(coordinator.beginDirectCheck("screen-2", "grant-2", "check-1", receipt))

        assertTrue(coordinator.closeScreen("screen-2"))

        assertFalse(coordinator.completeDirectCheck("screen-2", "grant-2", "check-1"))
        assertNull(coordinator.currentOwner())
    }

    @Test
    fun lateLegacyCompletionCannotReplaceOrCloseANewerDirectOperation() {
        val coordinator = GuardianApprovalCoordinator()
        assertTrue(coordinator.openScreen("screen-3", TARGET_PACKAGE, LifecycleGeneration(5L)))
        assertTrue(coordinator.beginLegacyOperation("screen-3", "legacy-1"))
        assertTrue(coordinator.beginDirectCheck("screen-3", "grant-3", "check-1", receipt))

        assertFalse(coordinator.completeLegacyOperation("screen-3", "legacy-1"))

        val current = coordinator.currentOwner()
        assertEquals("screen-3", current?.screenRequestId)
        assertEquals("grant-3", (current?.operation as GuardianApprovalCoordinator.Operation.Direct).operationId)
    }

    @Test
    fun aLegacyApprovalCanTakeOverAfterDirectDenialAndFinishOnlyItsOwnRequest() {
        val coordinator = GuardianApprovalCoordinator()
        assertTrue(coordinator.openScreen("screen-4", TARGET_PACKAGE, LifecycleGeneration(6L)))
        assertTrue(coordinator.beginDirectCheck("screen-4", "grant-4", "check-1", receipt))
        assertTrue(coordinator.completeDirectCheck("screen-4", "grant-4", "check-1"))

        assertTrue(coordinator.beginLegacyOperation("screen-4", "legacy-2"))
        assertFalse(coordinator.completeLegacyOperation("screen-4", "legacy-old"))
        assertTrue(coordinator.completeLegacyOperation("screen-4", "legacy-2"))
        assertNull(coordinator.currentOwner())
    }

    companion object {
        private const val TARGET_PACKAGE = "neth.iecal.curbox.test.target"
    }
}
