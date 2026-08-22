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
}