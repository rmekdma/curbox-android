package neth.iecal.curbox.ui.activity

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardianApprovalActivityLifecycleTest {
    @Test
    fun approvalIsFinishedAfterItLeavesTheForeground() {
        ActivityScenario.launch<GuardianApprovalActivity>(approvalIntent()).use { scenario ->
            if (scenario.state != Lifecycle.State.DESTROYED) {
                try {
                    scenario.moveToState(Lifecycle.State.CREATED)
                } catch (error: IllegalStateException) {
                    if (scenario.state != Lifecycle.State.DESTROYED) throw error
                }
            }

            if (scenario.state != Lifecycle.State.DESTROYED) {
                scenario.onActivity { approval ->
                    assertTrue(
                        "A backgrounded guardian approval can reappear after its restriction has ended",
                        approval.isFinishing
                    )
                }
            }
        }
    }

    private fun approvalIntent(): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denials = listOf(
            AppRuleGuardianDenial(
                ruleId = "test_rule",
                ruleName = "Test Rule",
                reason = "Daily limit reached"
            )
        )
        return Intent(context, GuardianApprovalActivity::class.java).apply {
            putExtra(GuardianApprovalActivity.EXTRA_PACKAGE, context.packageName)
            putExtra(GuardianApprovalActivity.EXTRA_DENIALS, Gson().toJson(denials))
        }
    }
}