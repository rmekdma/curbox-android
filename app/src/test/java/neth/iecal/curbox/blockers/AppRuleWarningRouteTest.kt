package neth.iecal.curbox.blockers

import android.content.Intent
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRuleWarningRouteTest {
    @Test
    fun launchesOnlyTheGuardianApprovalScreen() {
        assertEquals(
            GuardianApprovalActivity::class.java,
            AppRuleWarningRoute.activityClass()
        )
        assertEquals(
            0,
            AppRuleWarningRoute.launchFlags() and Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
    }
}
