package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianApprovalSelectionTest {
    private val denials = listOf(
        AppRuleGuardianDenial("first", "School", "No direct time left"),
        AppRuleGuardianDenial("second", "Night", "Condition not met"),
        AppRuleGuardianDenial("third", "Focus", "Rule is active")
    )

    @Test
    fun warningKeepsAllDenialsButResolvesExactlyOneSelectedRule() {
        assertEquals(listOf("first", "second", "third"), denials.map { it.ruleId })
        assertEquals("second", GuardianApprovalSelection.selectedDenial(denials, 1)?.ruleId)
        assertNull(GuardianApprovalSelection.selectedDenial(denials, -1))
        assertNull(GuardianApprovalSelection.selectedDenial(denials, denials.size))
    }

    @Test
    fun resolveAccumulatedButtonStateRequiresRolloverEnabledUnlockDayAndPositiveMinutes() {
        val rule = neth.iecal.curbox.data.models.AppRule(
            id = "rule1",
            rolloverEnabled = true,
            unlockDays = setOf(0, 6) // Sun=0, Sat=6
        )
        val pool = neth.iecal.curbox.data.models.RuleRolloverPool("rule1", 30L, "2026-09-20")

        // 2026-09-20 is Sunday -> Unlock day!
        val unlockState = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rule,
            pool = pool,
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertTrue(unlockState.isVisible)
        assertEquals(30L, unlockState.accumulatedMinutes)

        // 2026-09-21 is Monday -> Accrual day!
        val accrualState = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rule,
            pool = pool,
            useDayId = "2026-09-21"
        )
        org.junit.Assert.assertFalse(accrualState.isVisible)
        assertEquals(30L, accrualState.accumulatedMinutes)

        // Zero minutes -> Hidden
        val zeroMinutesState = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rule,
            pool = pool.copy(accumulatedMinutes = 0L),
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertFalse(zeroMinutesState.isVisible)
        assertEquals(0L, zeroMinutesState.accumulatedMinutes)

        // Rollover disabled -> Hidden
        val disabledState = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rule.copy(rolloverEnabled = false),
            pool = pool,
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertFalse(disabledState.isVisible)
        assertEquals(0L, disabledState.accumulatedMinutes)

        // Null rule or null pool -> Hidden
        val nullState = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = null,
            pool = null,
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertFalse(nullState.isVisible)
        assertEquals(0L, nullState.accumulatedMinutes)
    }

    @Test
    fun multiRuleSelectionDynamicallyUpdatesAccumulatedButtonState() {
        val ruleA = neth.iecal.curbox.data.models.AppRule(
            id = "ruleA",
            rolloverEnabled = true,
            unlockDays = setOf(0) // Sunday
        )
        val ruleB = neth.iecal.curbox.data.models.AppRule(
            id = "ruleB",
            rolloverEnabled = false
        )
        val ruleC = neth.iecal.curbox.data.models.AppRule(
            id = "ruleC",
            rolloverEnabled = true,
            unlockDays = setOf(0)
        )
        val pools = mapOf(
            "ruleA" to neth.iecal.curbox.data.models.RuleRolloverPool("ruleA", 45L, "2026-09-20"),
            "ruleB" to neth.iecal.curbox.data.models.RuleRolloverPool("ruleB", 100L, "2026-09-20"),
            "ruleC" to neth.iecal.curbox.data.models.RuleRolloverPool("ruleC", 0L, "2026-09-20")
        )
        val rulesMap = listOf(ruleA, ruleB, ruleC).associateBy { it.id }

        val denialList = listOf(
            AppRuleGuardianDenial("ruleA", "App A", "Reason A"),
            AppRuleGuardianDenial("ruleB", "App B", "Reason B"),
            AppRuleGuardianDenial("ruleC", "App C", "Reason C")
        )

        // Select 0 (ruleA) -> Unlock day (Sunday), 45 min available -> Visible with 45
        val selectedA = GuardianApprovalSelection.selectedDenial(denialList, 0)?.ruleId
        val stateA = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rulesMap[selectedA],
            pool = pools[selectedA],
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertTrue(stateA.isVisible)
        assertEquals(45L, stateA.accumulatedMinutes)

        // Switch selection to 1 (ruleB) -> Rollover disabled -> Hidden
        val selectedB = GuardianApprovalSelection.selectedDenial(denialList, 1)?.ruleId
        val stateB = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rulesMap[selectedB],
            pool = pools[selectedB],
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertFalse(stateB.isVisible)

        // Switch selection to 2 (ruleC) -> 0 min pool -> Hidden
        val selectedC = GuardianApprovalSelection.selectedDenial(denialList, 2)?.ruleId
        val stateC = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rulesMap[selectedC],
            pool = pools[selectedC],
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertFalse(stateC.isVisible)

        // Switch back to 0 (ruleA) -> Visible with 45
        val stateAReturn = GuardianApprovalSelection.resolveAccumulatedButtonState(
            rule = rulesMap[selectedA],
            pool = pools[selectedA],
            useDayId = "2026-09-20"
        )
        org.junit.Assert.assertTrue(stateAReturn.isVisible)
        assertEquals(45L, stateAReturn.accumulatedMinutes)
    }
}
