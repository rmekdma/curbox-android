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
}
