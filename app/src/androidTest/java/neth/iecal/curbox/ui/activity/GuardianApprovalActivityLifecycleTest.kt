package neth.iecal.curbox.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GuardianApprovalActivityLifecycleTest {
    @Test
    fun repeatedDenialReusesTheVisibleApprovalScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstIntent = approvalIntent()
        assertEquals(0, firstIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<GuardianApprovalActivity>(firstIntent).use { scenario ->
            lateinit var original: GuardianApprovalActivity
            scenario.onActivity { original = it }

            instrumentation.targetContext.startActivity(approvalIntent())
            instrumentation.waitForIdleSync()

            scenario.onActivity { approval ->
                assertSame(original, approval)
            }
        }
    }

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

    @Test
    fun closingApprovalBroadcastsTheGuardianPackageLifecycleEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receivedPackage = AtomicReference<String?>()
        val closed = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                receivedPackage.set(intent?.getStringExtra("guardian_package"))
                closed.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("neth.iecal.curbox.guardian.approval.closed"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(approvalIntent()).use { scenario ->
                scenario.close()
            }
            assertTrue(
                "closing the approval activity must notify the blocker",
                closed.await(2, TimeUnit.SECONDS)
            )
            assertEquals(context.packageName, receivedPackage.get())
        } finally {
            context.unregisterReceiver(receiver)
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
        return AppRuleBlocker.createGuardianApprovalIntent(
            context = context,
            packageName = context.packageName,
            denials = denials
        )
    }
}
