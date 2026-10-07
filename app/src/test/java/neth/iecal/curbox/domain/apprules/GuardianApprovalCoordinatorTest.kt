package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt
import neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt
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

        val expiredCheck = GuardianApprovalCoordinator.DirectCheckIdentity(
            screenRequestId = "screen-1",
            operationId = "grant-1",
            checkId = "check-1",
            lifecycleGeneration = LifecycleGeneration(3L)
        )
        assertFalse(
            coordinator.timeOutDirectCheck(
                expiredCheck.copy(lifecycleGeneration = LifecycleGeneration(2L))
            )
        )
        assertTrue(coordinator.timeOutDirectCheck(expiredCheck))
        assertFalse(coordinator.completeDirectCheck(expiredCheck))
        assertTrue(coordinator.retryDirectCheck("screen-1", "grant-1", "check-2"))

        val current = coordinator.currentOwner()
        assertEquals("grant-1", (current?.operation as GuardianApprovalCoordinator.Operation.Confirmation).operationId)
        assertEquals("check-2", (current.operation as GuardianApprovalCoordinator.Operation.Confirmation).checkId)
        assertEquals(
            GuardianApprovalWorkReceipt.Grant(
                receipt,
                GuardianApprovalGrantOrigin.DIRECT,
                0L
            ),
            (current.operation as GuardianApprovalCoordinator.Operation.Confirmation).receipt
        )
        assertFalse(coordinator.completeDirectCheck(expiredCheck))
        assertEquals(
            GuardianApprovalCoordinator.ConfirmationPhase.CHECKING,
            (coordinator.currentOwner()?.operation as GuardianApprovalCoordinator.Operation.Confirmation).phase
        )
        assertTrue(
            coordinator.completeDirectCheck(
                GuardianApprovalCoordinator.DirectCheckIdentity(
                    screenRequestId = "screen-1",
                    operationId = "grant-1",
                    checkId = "check-2",
                    lifecycleGeneration = LifecycleGeneration(3L)
                )
            )
        )
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
    fun rebindRestartsOnlyTheCurrentReceiptAndFencesThePreviousGenerationCheck() {
        val coordinator = GuardianApprovalCoordinator()
        val originalGeneration = LifecycleGeneration(12L)
        val reconnectedGeneration = LifecycleGeneration(13L)
        val approval = GuardianApprovalWorkReceipt.Grant(
            grant = receipt,
            origin = GuardianApprovalGrantOrigin.DIRECT,
            useDayGenerationStartedAtMs = 7L
        )
        assertTrue(coordinator.openScreen("screen-live", TARGET_PACKAGE, originalGeneration))
        assertTrue(coordinator.beginConfirmation("screen-live", "grant-live", "check-old", approval))

        assertTrue(coordinator.closeScreen("screen-live"))
        assertTrue(coordinator.openScreen("screen-live", TARGET_PACKAGE, reconnectedGeneration))
        assertFalse(
            "a receipt from a previous screen must not be rebound under another request identity",
            coordinator.rebindConfirmation(
                screenRequestId = "screen-old",
                operationId = "grant-live",
                checkId = "check-current",
                receipt = approval,
                lifecycleGeneration = reconnectedGeneration
            )
        )
        assertFalse(
            "an older service generation must not bind a current screen",
            coordinator.rebindConfirmation(
                screenRequestId = "screen-live",
                operationId = "grant-live",
                checkId = "check-current",
                receipt = approval,
                lifecycleGeneration = originalGeneration
            )
        )
        assertTrue(
            coordinator.rebindConfirmation(
                screenRequestId = "screen-live",
                operationId = "grant-live",
                checkId = "check-current",
                receipt = approval,
                lifecycleGeneration = reconnectedGeneration
            )
        )
        assertFalse(
            "the previous check cannot complete after its request is rebound",
            coordinator.completeConfirmation(
                GuardianApprovalCoordinator.CheckIdentity(
                    "screen-live",
                    "grant-live",
                    "check-old",
                    originalGeneration
                )
            )
        )
        assertEquals(
            GuardianApprovalCoordinator.Operation.Confirmation(
                operationId = "grant-live",
                checkId = "check-current",
                receipt = approval,
                phase = GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            ),
            coordinator.currentOwner()?.operation
        )
        assertTrue(
            coordinator.completeConfirmation(
                GuardianApprovalCoordinator.CheckIdentity(
                    "screen-live",
                    "grant-live",
                    "check-current",
                    reconnectedGeneration
                )
            )
        )
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
        assertEquals("grant-3", (current?.operation as GuardianApprovalCoordinator.Operation.Confirmation).operationId)
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

    @Test
    fun accumulatedConfirmationRetryKeepsItsEffectAndFencesDuplicateAndLateResults() {
        val coordinator = GuardianApprovalCoordinator()
        val receipt = GuardianApprovalWorkReceipt.Grant(
            grant = GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = "2026-10-07",
                grantedAtMs = 100L,
                grantedMillis = 15 * 60_000L
            ),
            origin = GuardianApprovalGrantOrigin.ACCUMULATED_POOL,
            useDayGenerationStartedAtMs = 40L
        )
        assertTrue(coordinator.openScreen("screen-5", TARGET_PACKAGE, LifecycleGeneration(7L)))
        assertTrue(coordinator.beginConfirmation("screen-5", "acc-1", "check-1", receipt))
        assertFalse(coordinator.beginConfirmation("screen-5", "acc-1", "check-1", receipt))
        assertFalse(
            coordinator.beginConfirmation(
                "screen-5",
                "acc-1",
                "check-2",
                receipt.copy(
                    grant = receipt.grant.copy(grantedMillis = 30 * 60_000L)
                )
            )
        )

        val firstCheck = GuardianApprovalCoordinator.CheckIdentity(
            screenRequestId = "screen-5",
            operationId = "acc-1",
            checkId = "check-1",
            lifecycleGeneration = LifecycleGeneration(7L)
        )
        assertTrue(coordinator.timeOutConfirmation(firstCheck))
        assertFalse(coordinator.completeConfirmation(firstCheck))
        assertTrue(coordinator.retryConfirmation("screen-5", "acc-1", "check-2"))
        assertFalse(coordinator.completeConfirmation(firstCheck))
        val current = coordinator.currentOwner()?.operation as GuardianApprovalCoordinator.Operation.Confirmation
        assertEquals(receipt, current.receipt)
        assertEquals("check-2", current.checkId)
        val retry = firstCheck.copy(checkId = "check-2")
        assertTrue(coordinator.completeConfirmation(retry))
        assertFalse(coordinator.completeConfirmation(retry))
    }

    @Test
    fun skipConfirmationRetainsSkipIdentityInsteadOfBecomingGenericGranted() {
        val coordinator = GuardianApprovalCoordinator()
        val receipt = GuardianApprovalWorkReceipt.RuleSkip(
            ruleId = "night",
            useDayId = "2026-10-07",
            skipFromMs = 100L,
            skipUntilMs = 200L,
            useDayGenerationStartedAtMs = 40L
        )
        assertTrue(coordinator.openScreen("screen-6", TARGET_PACKAGE, LifecycleGeneration(8L)))
        assertTrue(coordinator.beginConfirmation("screen-6", "skip-1", "check-1", receipt))

        val current = coordinator.currentOwner()?.operation as GuardianApprovalCoordinator.Operation.Confirmation
        assertEquals(receipt, current.receipt)
        assertFalse(coordinator.completeLegacyOperation("screen-6", "skip-1"))
        val check = GuardianApprovalCoordinator.CheckIdentity(
            screenRequestId = "screen-6",
            operationId = "skip-1",
            checkId = "check-1",
            lifecycleGeneration = LifecycleGeneration(8L)
        )
        assertTrue(coordinator.completeConfirmation(check))
        assertFalse(coordinator.completeConfirmation(check))
    }

    companion object {
        private const val TARGET_PACKAGE = "neth.iecal.curbox.test.target"
    }
}
