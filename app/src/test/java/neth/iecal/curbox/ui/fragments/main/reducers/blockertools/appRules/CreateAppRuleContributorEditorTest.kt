package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import neth.iecal.curbox.data.models.AppRule
import org.junit.Assert.assertEquals
import org.junit.Test

class CreateAppRuleContributorEditorTest {
    @Test
    fun missingContributorIsShownAndAnUncheckedRowIsRemovedOnSave() {
        val rule = AppRule(contributorGroupIds = setOf("deleted", "kept"))

        assertEquals(
            setOf("deleted"),
            missingContributorGroupIdsForEditor(rule, setOf("kept"))
        )
        assertEquals(
            setOf("kept"),
            contributorGroupIdsFromEditorChecks(
                mapOf("deleted" to false, "kept" to true)
            )
        )
    }

    @Test
    fun parseGroupConditionInputsFiltersOutBlankZeroAndUnselectedGroups() {
        val inputs = mapOf(
            "group1" to "15",
            "group2" to "0",
            "group3" to "  ",
            "group4" to "30",
            "unselected" to "20"
        )
        val selected = setOf("group1", "group2", "group3", "group4")

        val parsed = parseGroupConditionInputs(inputs, selected)

        assertEquals(
            mapOf("group1" to 15L, "group4" to 30L),
            parsed
        )
    }

    @Test
    fun rolloverValidationRequiresUnlockDaysOnlyWhenRolloverIsEnabled() {
        val ranges = listOf(neth.iecal.curbox.data.models.AppRuleTimeRange(9 * 60, 17 * 60))

        // When rollover is enabled and unlockDays is empty -> error
        val errorWhenEnabledAndEmpty = validateRuleEditorInputs(
            name = "Rule",
            allowance = 30L,
            totalConditionMinutes = 0L,
            weekdays = setOf(1, 2, 3, 4, 5),
            ranges = ranges,
            rolloverEnabled = true,
            unlockDays = emptySet()
        )
        assertEquals(RuleEditorValidationError.ROLLOVER_UNLOCK_DAYS_REQUIRED, errorWhenEnabledAndEmpty)

        // When rollover is enabled and unlockDays is present -> valid (null)
        val validWhenEnabledWithDays = validateRuleEditorInputs(
            name = "Rule",
            allowance = 30L,
            totalConditionMinutes = 0L,
            weekdays = setOf(1, 2, 3, 4, 5),
            ranges = ranges,
            rolloverEnabled = true,
            unlockDays = setOf(0, 6)
        )
        org.junit.Assert.assertNull(validWhenEnabledWithDays)

        // When rollover is disabled and unlockDays is empty -> valid (null)
        val validWhenDisabledAndEmpty = validateRuleEditorInputs(
            name = "Rule",
            allowance = 30L,
            totalConditionMinutes = 0L,
            weekdays = setOf(1, 2, 3, 4, 5),
            ranges = ranges,
            rolloverEnabled = false,
            unlockDays = emptySet()
        )
        org.junit.Assert.assertNull(validWhenDisabledAndEmpty)
    }
}