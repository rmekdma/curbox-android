package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardianExtraTimeRulePickerTest {
    @Test
    fun mainAppCandidatesUseEveryEnabledAllowedRuleAndPrioritizeCurrentDenials() {
        val snapshot = AppRuleSnapshot(
            appRules = listOf(
                AppRule(id = "first-allowed"),
                AppRule(id = "inactive", isActive = false),
                AppRule(id = "inactive-window", weekdays = setOf(6)),
                AppRule(id = "denying", allowedMinutes = 0L),
                AppRule(id = "disallowed", guardianExtraTimeAllowed = false)
            )
        )
        val evaluations = listOf(
            AppRuleEvaluation("first-allowed", true, true, 0L, 10L, 10L, true),
            AppRuleEvaluation("inactive", true, false, 0L, 0L, 0L, false),
            AppRuleEvaluation("inactive-window", true, false, 0L, 0L, 0L, true),
            AppRuleEvaluation("denying", true, true, 0L, 0L, 0L, false),
            AppRuleEvaluation("disallowed", true, true, 0L, 0L, 0L, false)
        )

        val candidates = GuardianExtraTimeRulePicker.candidates(snapshot, evaluations)

        assertEquals(
            listOf("denying", "first-allowed", "inactive-window"),
            candidates.map(AppRule::id)
        )
        assertEquals("denying", candidates.first().id)
    }

    @Test
    fun candidatesShowEveryEnabledAllowedRuleWithCurrentDenialsFirstAndStableOrder() {
        val rules = listOf(
            AppRule(id = "scheduled-later", name = "Scheduled later"),
            AppRule(id = "disabled", isActive = false),
            AppRule(id = "not-allowed", guardianExtraTimeAllowed = false),
            AppRule(id = "currently-denying-a"),
            AppRule(id = "currently-denying-b"),
            AppRule(id = "unrelated", name = "Unrelated app")
        )

        val candidates = GuardianExtraTimeRulePicker.candidates(
            snapshot = AppRuleSnapshot(appRules = rules),
            currentlyDenyingRuleIds = setOf("currently-denying-a", "currently-denying-b")
        )

        assertEquals(
            listOf("currently-denying-a", "currently-denying-b", "scheduled-later", "unrelated"),
            candidates.map(AppRule::id)
        )
        assertEquals("currently-denying-a", candidates.first().id)
    }

    @Test
    fun noCandidateIsReturnedWhenEveryRuleIsDisabledOrDisallowed() {
        val snapshot = AppRuleSnapshot(
            appRules = listOf(
                AppRule(id = "off", isActive = false),
                AppRule(id = "not-allowed", guardianExtraTimeAllowed = false)
            )
        )

        assertEquals(
            emptyList<AppRule>(),
            GuardianExtraTimeRulePicker.candidates(snapshot, emptySet<String>())
        )
    }

}
