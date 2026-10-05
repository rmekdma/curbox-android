package neth.iecal.curbox.ui.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingRootException
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
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
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantCandidate
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianSessionRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        val grantRuleId = "lifecycle-grant-${java.util.UUID.randomUUID()}"
        val grantRuleName = "Rule ${java.util.UUID.randomUUID()}"
        val originalSnapshot = seedEligibleGrantRule(context, grantRuleId, grantRuleName)
        try {
            resetGuardianOverrides(context)
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = grantRuleId, ruleName = grantRuleName)
            ).use {
                awaitDisplayed(R.id.approval_add_time)
                onView(withId(R.id.approval_add_time)).perform(click())
                awaitDisplayed(R.id.rule_picker, inDialog = true)
                onView(withId(R.id.rule_picker))
                    .inRoot(isDialog())
                    .perform(click())
                onView(withText(grantRuleName))
                    .inRoot(isPlatformPopup())
                    .perform(click())
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
                assertEquals(listOf(grantRuleId), state.grants.map { it.ruleId })
                assertTrue(state.skips.isEmpty())
            }
        } finally {
            try {
                resetGuardianOverrides(context)
            } finally {
                restoreAppRuleSnapshot(context, originalSnapshot)
            }
        }
    }

    @Test
    fun dismissingAddTimeFormAllowsItToBeOpenedAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val grantRuleId = "dialog-reopen-${java.util.UUID.randomUUID()}"
        val originalSnapshot = seedEligibleGrantRule(context, grantRuleId, "Dialog test rule")
        try {
            val scenario = ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = grantRuleId, ruleName = "Dialog test rule")
            )
            try {
                awaitDisplayed(R.id.approval_add_time)
                onView(withId(R.id.approval_add_time)).perform(click())
                awaitDisplayed(R.id.rule_picker, inDialog = true)
                onView(withText(R.string.cancel))
                    .inRoot(isDialog())
                    .perform(click())
                awaitDialogViewDismissed(R.id.rule_picker)

                awaitDisplayed(R.id.approval_add_time)
                onView(withId(R.id.approval_add_time)).perform(click())
                awaitDisplayed(R.id.rule_picker, inDialog = true)
                assertTrue(
                    "The open grant form should own the guardian session",
                    GuardianSessionRegistry.isOwnedDialogActive()
                )
            } finally {
                scenario.close()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse(
                "An open grant form must release its guardian session when the activity is destroyed",
                GuardianSessionRegistry.isOwnedDialogActive()
            )
        } finally {
            restoreAppRuleSnapshot(context, originalSnapshot)
        }
    }

    @Test
    fun completedGrantPickerCannotShowDialogAfterActivityIsDestroyed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val grantRuleId = "dialog-destroyed-${java.util.UUID.randomUUID()}"
        val originalSnapshot = seedEligibleGrantRule(context, grantRuleId, "Dialog test rule")
        try {
            val scenario = ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = grantRuleId, ruleName = "Dialog test rule")
            )
            lateinit var destroyedActivity: GuardianApprovalActivity
            scenario.onActivity { destroyedActivity = it }
            scenario.close()

            val settings = runBlocking { DataStoreManager(context).settings.first() }
            val rule = settings.appRuleSnapshot.appRules.first { it.id == grantRuleId }
            val basis = GuardianExtraTimeGrantBasis.capture(
                settings = settings,
                ruleId = grantRuleId,
                nowMs = System.currentTimeMillis()
            ) ?: error("The seeded guardian grant rule must produce a grant basis")
            val completion = GuardianApprovalActivity::class.java.getDeclaredMethod(
                "showGrantDialog",
                List::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            instrumentation.runOnMainSync {
                completion.invoke(
                    destroyedActivity,
                    listOf(GuardianExtraTimeGrantCandidate(rule, basis)),
                    0
                )
            }
            instrumentation.waitForIdleSync()

            assertFalse(
                "a completed picker must not show a grant form for a destroyed activity",
                GuardianSessionRegistry.isOwnedDialogActive()
            )
        } finally {
            restoreAppRuleSnapshot(context, originalSnapshot)
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

    private fun seedEligibleGrantRule(
        context: Context,
        ruleId: String,
        ruleName: String
    ): AppRuleSnapshot {
        val dataStore = DataStoreManager(context)
        val settings = runBlocking { dataStore.settings.first() }
        val delayConfig = settings.settingsChangeDelayConfig2
        val hasPendingAppRuleEdit = delayConfig.pendingChanges.any {
            it.field == GatedSettingsField.APP_RULES.name
        }
        assumeTrue(
            "Guardian lifecycle UI tests require immediate app-rule writes so their fixture can be restored",
            !hasPendingAppRuleEdit &&
                (!delayConfig.isEnabled || delayConfig.delayMinutes == 0) &&
                (!delayConfig.requireTamperProtectionOff ||
                    !settings.antiUninstallConfig2.isEnabled)
        )

        val originalSnapshot = settings.appRuleSnapshot
        val testRule = AppRule(
            id = ruleId,
            name = ruleName,
            allowedMinutes = Long.MAX_VALUE / 60_000L
        )
        val seededSnapshot = originalSnapshot.copy(
            appRules = originalSnapshot.appRules.filterNot { it.id == ruleId } + testRule
        ).normalized()
        try {
            assertTrue(runBlocking { dataStore.updateAppRuleSnapshot(seededSnapshot) })
            runBlocking {
                withTimeout(5_000L) {
                    dataStore.settings.first { it.appRuleSnapshot == seededSnapshot }
                }
            }
        } catch (error: Throwable) {
            restoreAppRuleSnapshot(context, originalSnapshot)
            throw error
        }
        return originalSnapshot
    }

    private fun restoreAppRuleSnapshot(context: Context, snapshot: AppRuleSnapshot) {
        val normalizedSnapshot = snapshot.normalized()
        val dataStore = DataStoreManager(context)
        assertTrue(runBlocking { dataStore.updateAppRuleSnapshot(normalizedSnapshot) })
        runBlocking {
            withTimeout(5_000L) {
                dataStore.settings.first { it.appRuleSnapshot == normalizedSnapshot }
            }
        }
    }

    private fun awaitDisplayed(
        viewId: Int,
        inDialog: Boolean = false,
        timeoutMs: Long = 5_000L
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastFailure: Throwable? = null
        do {
            instrumentation.waitForIdleSync()
            try {
                val interaction = onView(withId(viewId))
                if (inDialog) interaction.inRoot(isDialog())
                interaction.check(matches(isDisplayed()))
                return
            } catch (error: NoMatchingViewException) {
                lastFailure = error
            } catch (error: NoMatchingRootException) {
                lastFailure = error
            } catch (error: AssertionError) {
                lastFailure = error
            }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for view $viewId to be displayed", lastFailure)
    }

    private fun awaitDialogViewDismissed(viewId: Int, timeoutMs: Long = 5_000L) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            instrumentation.waitForIdleSync()
            try {
                onView(withId(viewId))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
            } catch (_: NoMatchingViewException) {
                return
            } catch (_: NoMatchingRootException) {
                return
            } catch (_: AssertionError) {
                return
            }
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Dialog view $viewId did not disappear")
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
