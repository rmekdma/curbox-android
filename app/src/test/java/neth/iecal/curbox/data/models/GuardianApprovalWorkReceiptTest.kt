package neth.iecal.curbox.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianApprovalWorkReceiptTest {
    @Test
    fun directAndAccumulatedGrantsKeepTheirDifferentMeaningAndGeneration() {
        val identity = GuardianApprovalGrantReceipt(
            ruleId = "usage",
            useDayId = "2026-10-07",
            grantedAtMs = 10L,
            grantedMillis = 15 * 60_000L
        )
        val receipt = GuardianApprovalWorkReceipt.Grant(
            grant = identity,
            origin = GuardianApprovalGrantOrigin.ACCUMULATED_POOL,
            useDayGenerationStartedAtMs = 100L
        )
        val directReceipt = receipt.copy(origin = GuardianApprovalGrantOrigin.DIRECT)
        val directGrant = AppRuleGuardianGrant(
            ruleId = "usage",
            useDayId = "2026-10-07",
            grantedAtMs = 10L,
            grantedMillis = 15 * 60_000L
        )
        val accumulatedGrant = directGrant.copy(isFromAccumulatedPool = true)

        assertTrue(receipt.isPresentIn(stateWith(accumulatedGrant, generation = 100L)))
        assertFalse(receipt.isPresentIn(stateWith(directGrant, generation = 100L)))
        assertFalse(receipt.isPresentIn(stateWith(accumulatedGrant, generation = 101L)))
        assertTrue(directReceipt.isPresentIn(stateWith(directGrant, generation = 100L)))
        assertFalse(directReceipt.isPresentIn(stateWith(accumulatedGrant, generation = 100L)))
        assertEquals(identity, receipt.grant)
        assertEquals(identity, directReceipt.grant)
        assertFalse(receipt.origin == directReceipt.origin)
    }

    @Test
    fun skippedRuleReceiptSurvivesIntervalCompactionButNotAChangedUseDay() {
        val receipt = GuardianApprovalWorkReceipt.RuleSkip(
            ruleId = "usage",
            useDayId = "2026-10-07",
            skipFromMs = 20L,
            skipUntilMs = 50L,
            useDayGenerationStartedAtMs = 100L
        )
        val mergedSkip = AppRuleGuardianSkip(
            ruleId = "usage",
            useDayId = "2026-10-07",
            skipFromMs = 10L,
            skipUntilMs = 60L
        )

        assertTrue(receipt.isPresentIn(AppRuleOverrideState(
            useDayId = "2026-10-07",
            useDayGenerationStartedAtMs = 100L,
            skips = listOf(mergedSkip)
        )))
        assertFalse(receipt.isPresentIn(AppRuleOverrideState(
            useDayId = "2026-10-08",
            useDayGenerationStartedAtMs = 100L,
            skips = listOf(mergedSkip)
        )))
    }

    private fun stateWith(
        grant: AppRuleGuardianGrant,
        generation: Long
    ) = AppRuleOverrideState(
        useDayId = "2026-10-07",
        useDayGenerationStartedAtMs = generation,
        grants = listOf(grant)
    )
}
