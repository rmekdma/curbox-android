package neth.iecal.curbox.blockers

import android.content.Intent
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity

internal object AppRuleWarningRoute {
    fun activityClass(): Class<*> = GuardianApprovalActivity::class.java

    fun launchFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK
}
