package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules

import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRule
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRuleGroupsSummaryTest {

    private val fakeStringResolver: (Int, Array<out Any>) -> String = { resId, args ->
        when (resId) {
            R.string.app_rules_sunday_short -> "Sun"
            R.string.app_rules_monday_short -> "Mon"
            R.string.app_rules_tuesday_short -> "Tue"
            R.string.app_rules_wednesday_short -> "Wed"
            R.string.app_rules_thursday_short -> "Thu"
            R.string.app_rules_friday_short -> "Fri"
            R.string.app_rules_saturday_short -> "Sat"
            R.string.app_rules_rollover_summary_on -> "Rollover on (${args[0]})"
            R.string.app_rules_rollover_summary_off -> "Rollover off"
            else -> ""
        }
    }

    @Test
    fun whenRolloverIsOff_showsRolloverOff() {
        val rule = AppRule(rolloverEnabled = false)
        val summary = formatAppRuleRolloverSummary(rule, fakeStringResolver)
        assertEquals("Rollover off", summary)
    }

    @Test
    fun whenRolloverIsOn_showsRolloverOnWithSortedDays() {
        val rule = AppRule(
            rolloverEnabled = true,
            unlockDays = setOf(6, 0) // Sat, Sun -> sorted as 0, 6 -> Sun, Sat
        )
        val summary = formatAppRuleRolloverSummary(rule, fakeStringResolver)
        assertEquals("Rollover on (Sun, Sat)", summary)
    }
}
