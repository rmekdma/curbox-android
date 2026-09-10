package neth.iecal.curbox.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.utils.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val firstIntent = approvalIntent(reason = "Initial denial")
        assertEquals(0, firstIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertTrue(firstIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        ActivityScenario.launch<GuardianApprovalActivity>(firstIntent).use { scenario ->
            lateinit var original: GuardianApprovalActivity
            scenario.onActivity { approval ->
                original = approval
                assertTrue(
                    (approval.findViewById<RadioGroup>(R.id.approval_choices)
                        .getChildAt(0) as RadioButton).text.contains("Initial denial")
                )
            }

            instrumentation.targetContext.startActivity(approvalIntent(reason = "Updated denial"))
            instrumentation.waitForIdleSync()

            scenario.onActivity { approval ->
                assertSame(original, approval)
                assertEquals(1, approval.findViewById<RadioGroup>(R.id.approval_choices).childCount)
                assertTrue(
                    (approval.findViewById<RadioGroup>(R.id.approval_choices)
                        .getChildAt(0) as RadioButton).text.contains("Updated denial")
                )
            }
        }
    }

    @Test
    fun malformedDenialReplacementRetainsTheCurrentApprovalPayload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val receivedPackage = AtomicReference<String?>()
        val closed = CountDownLatch(1)
        val receiver = guardianClosedReceiver(receivedPackage, closed)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(GuardianApprovalActivity.INTENT_ACTION_CLOSED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(reason = "Current denial")
            ).use { scenario ->
                lateinit var original: GuardianApprovalActivity
                scenario.onActivity { approval ->
                    original = approval
                    assertDenialReason(approval, "Current denial")
                }

                instrumentation.targetContext.startActivity(
                    malformedApprovalIntent("[null]")
                )
                instrumentation.waitForIdleSync()

                scenario.onActivity { approval ->
                    assertSame(original, approval)
                    assertDenialReason(approval, "Current denial")
                }

                instrumentation.targetContext.startActivity(
                    malformedApprovalIntent(
                        "[{\"ruleName\":\"Malformed\",\"reason\":\"Bad payload\"}]"
                    )
                )
                instrumentation.waitForIdleSync()

                scenario.onActivity { approval ->
                    assertSame(original, approval)
                    assertDenialReason(approval, "Current denial")
                }
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

    @Test
    fun missingPackageReplacementRetainsTheCurrentPackageForCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val receivedPackage = AtomicReference<String?>()
        val closed = CountDownLatch(1)
        val receiver = guardianClosedReceiver(receivedPackage, closed)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(GuardianApprovalActivity.INTENT_ACTION_CLOSED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(reason = "Current package")
            ).use { scenario ->
                lateinit var original: GuardianApprovalActivity
                scenario.onActivity { approval ->
                    original = approval
                    assertDenialReason(approval, "Current package")
                }

                instrumentation.targetContext.startActivity(
                    approvalIntentWithoutPackage(reason = "Replacement denial")
                )
                instrumentation.waitForIdleSync()

                scenario.onActivity { approval ->
                    assertSame(original, approval)
                    assertDenialReason(approval, "Current package")
                }
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

    @Test
    fun malformedInitialPayloadFinishesWithoutCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<GuardianApprovalActivity>(
            malformedApprovalIntent("[null]")
        ).use { scenario ->
            instrumentation.waitForIdleSync()
            if (scenario.state != Lifecycle.State.DESTROYED) {
                scenario.onActivity { approval ->
                    assertTrue(
                        "a malformed initial payload must finish the activity",
                        approval.isFinishing
                    )
                }
            }
        }
    }

    @Test
    fun addTimeFlowKeepsTheOriginalRuleAfterValidReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetGuardianOverrides(context)
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = "rule_a", ruleName = "Rule A")
            ).use {
                onView(withId(R.id.approval_add_time)).perform(click())
                onView(isAssignableFrom(EditText::class.java))
                    .inRoot(isDialog())
                    .perform(replaceText("5"))
                    .check(matches(withText("5")))

                InstrumentationRegistry.getInstrumentation().targetContext.startActivity(
                    approvalIntent(ruleId = "rule_b", ruleName = "Rule B")
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                onView(withText(R.string.common_continue))
                    .inRoot(isDialog())
                    .perform(click())

                val state = awaitOverrideState(context) { it.grants.isNotEmpty() }
                assertEquals(listOf("rule_a"), state.grants.map { it.ruleId })
                assertTrue(state.skips.isEmpty())
            }
        } finally {
            resetGuardianOverrides(context)
        }
    }

    @Test
    fun skipFlowKeepsTheOriginalRuleAfterValidReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetGuardianOverrides(context)
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = "rule_a", ruleName = "Rule A")
            ).use {
                onView(withId(R.id.approval_skip_rule)).perform(click())

                InstrumentationRegistry.getInstrumentation().targetContext.startActivity(
                    approvalIntent(ruleId = "rule_b", ruleName = "Rule B")
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                onView(withText(R.string.guardian_skip_15_minutes))
                    .inRoot(isDialog())
                    .perform(click())

                val state = awaitOverrideState(context) { it.skips.isNotEmpty() }
                assertEquals(listOf("rule_a"), state.skips.map { it.ruleId })
                assertTrue(state.grants.isEmpty())
            }
        } finally {
            resetGuardianOverrides(context)
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

    private fun assertDenialReason(activity: GuardianApprovalActivity, reason: String) {
        assertTrue(
            (activity.findViewById<RadioGroup>(R.id.approval_choices)
                .getChildAt(0) as RadioButton).text.contains(reason)
        )
    }

    private fun guardianClosedReceiver(
        receivedPackage: AtomicReference<String?>,
        closed: CountDownLatch
    ): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            receivedPackage.set(
                intent?.getStringExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE)
            )
            closed.countDown()
        }
    }

    private fun malformedApprovalIntent(denialsJson: String): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, GuardianApprovalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(GuardianApprovalActivity.EXTRA_PACKAGE, context.packageName)
            putExtra(GuardianApprovalActivity.EXTRA_DENIALS, denialsJson)
        }
    }

    private fun approvalIntentWithoutPackage(reason: String): Intent =
        approvalIntent(reason).apply {
            removeExtra(GuardianApprovalActivity.EXTRA_PACKAGE)
        }

    private fun resetGuardianOverrides(context: Context) {
        assertTrue(
            "Guardian lifecycle tests require an unset guardian password",
            runBlocking { DataStoreManager(context).clearGuardianPassword() }
        )
        assertTrue(
            runBlocking {
                DataStoreManager(context).writeAppRuleOverrideState(
                    password = "",
                    state = AppRuleOverrideState()
                )
            }
        )
    }

    private fun awaitOverrideState(
        context: Context,
        predicate: (AppRuleOverrideState) -> Boolean
    ): AppRuleOverrideState = runBlocking {
        withTimeout(5_000L) {
            DataStoreManager(context).settings
                .first { predicate(it.appRuleOverrideState) }
                .appRuleOverrideState
        }
    }

    private fun approvalIntent(
        ruleId: String = "test_rule",
        ruleName: String = "Test Rule",
        reason: String = "Daily limit reached"
    ): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denials = listOf(
            AppRuleGuardianDenial(
                ruleId = ruleId,
                ruleName = ruleName,
                reason = reason
            )
        )
        return AppRuleBlocker.createGuardianApprovalIntent(
            context = context,
            packageName = context.packageName,
            denials = denials
        )
    }
}
