package neth.iecal.curbox.data.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianApprovalWorkReceiptTest {
    @Test
    fun directAndAccumulatedGrantsKeepTheirDifferentMeaningAndGeneration() {
        val receipt = GuardianApprovalWorkReceipt.AccumulatedGrant(
            ruleId = "usage",
            useDayId = "2026-10-07",
            grantedAtMs = 10L,
            grantedMillis = 15 * 60_000L,
            useDayGenerationStartedAtMs = 100L
        )
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

    @Test
    fun directGrantReceiptRequiresTheExactNonAccumulatedWrite() {
        val receipt = GuardianApprovalWorkReceipt.DirectGrant(
            grant = GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = "2026-10-07",
                grantedAtMs = 10L,
                grantedMillis = 15 * 60_000L
            ),
            useDayGenerationStartedAtMs = 100L
        )

        assertTrue(receipt.isPresentIn(stateWith(
            AppRuleGuardianGrant(
                ruleId = "usage",
                useDayId = "2026-10-07",
                grantedAtMs = 10L,
                grantedMillis = 15 * 60_000L
            ),
            generation = 100L
        )))
        assertFalse(receipt.isPresentIn(stateWith(
            AppRuleGuardianGrant(
                ruleId = "usage",
                useDayId = "2026-10-07",
                grantedAtMs = 10L,
                grantedMillis = 15 * 60_000L,
                isFromAccumulatedPool = true
            ),
            generation = 100L
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
