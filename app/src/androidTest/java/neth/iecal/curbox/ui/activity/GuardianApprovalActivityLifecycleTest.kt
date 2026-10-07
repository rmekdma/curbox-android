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
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
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
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantCandidate
import neth.iecal.curbox.domain.apprules.GuardianApprovalConfirmationState
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.GuardianSessionRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class GuardianApprovalActivityLifecycleTest {
    private val fakeServiceConnectionId = AtomicReference("test-service-connection-1")
    private lateinit var fakeServiceContext: Context
    private lateinit var fakeServiceReceiver: BroadcastReceiver

    @Before
    fun registerCurrentScreenServiceAdapter() {
        fakeServiceContext = InstrumentationRegistry.getInstrumentation().targetContext
        fakeServiceConnectionId.set("test-service-connection-1")
        fakeServiceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != GuardianApprovalActivity.INTENT_ACTION_OPENED) return
                val connectionId = fakeServiceConnectionId.get()
                val requestConnectionId = intent.getStringExtra(
                    GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID
                )
                if (requestConnectionId != null && requestConnectionId != connectionId) return
                fakeServiceContext.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_SCREEN_REGISTERED)
                        .setPackage(fakeServiceContext.packageName)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID,
                            intent.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE,
                            intent.getStringExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID, connectionId)
                )
            }
        }
        ContextCompat.registerReceiver(
            fakeServiceContext,
            fakeServiceReceiver,
            IntentFilter(GuardianApprovalActivity.INTENT_ACTION_OPENED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    @After
    fun unregisterCurrentScreenServiceAdapter() {
        fakeServiceContext.unregisterReceiver(fakeServiceReceiver)
    }

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
    fun reusedScreenRegistersTheNewRequestWithItsPreviousIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val openedRequests = LinkedBlockingQueue<Intent>()
        val firstRequestRegistered = CountDownLatch(1)
        val firstRequestId = "screen-before-${java.util.UUID.randomUUID()}"
        val replacementRequestId = "screen-after-${java.util.UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GuardianApprovalActivity.INTENT_ACTION_OPENED) {
                    openedRequests.offer(intent)
                }
            }
        }
        val registrationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GuardianApprovalActivity.INTENT_ACTION_SCREEN_REGISTERED &&
                    intent.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID) ==
                    firstRequestId
                ) {
                    firstRequestRegistered.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(GuardianApprovalActivity.INTENT_ACTION_OPENED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            registrationReceiver,
            IntentFilter(GuardianApprovalActivity.INTENT_ACTION_SCREEN_REGISTERED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(reason = "Original denial").putExtra(
                    GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID,
                    firstRequestId
                )
            ).use { scenario ->
                lateinit var original: GuardianApprovalActivity
                scenario.onActivity { original = it }
                pollOpenedRequest(openedRequests, firstRequestId)
                assertTrue(
                    "the first screen must register before the same-top replacement arrives",
                    firstRequestRegistered.await(5, TimeUnit.SECONDS)
                )
                instrumentation.waitForIdleSync()

                context.startActivity(
                    approvalIntent(reason = "Current denial after reuse").putExtra(
                        GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID,
                        replacementRequestId
                    )
                )
                instrumentation.waitForIdleSync()

                val replacementOpen = pollOpenedRequest(openedRequests, replacementRequestId)
                assertEquals(
                    firstRequestId,
                    replacementOpen.getStringExtra(
                        GuardianApprovalActivity.EXTRA_PREVIOUS_SCREEN_REQUEST_ID
                    )
                )
                scenario.onActivity { activity ->
                    assertSame(original, activity)
                    assertDenialReason(activity, "Current denial after reuse")
                }
            }
        } finally {
            context.unregisterReceiver(registrationReceiver)
            context.unregisterReceiver(receiver)
        }
    }

    private fun pollOpenedRequest(
        openedRequests: LinkedBlockingQueue<Intent>,
        screenRequestId: String
    ): Intent {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val request = openedRequests.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (request.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID) ==
                screenRequestId
            ) return request
        }
        error("No OPENED lifecycle event arrived for screen $screenRequestId")
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
        val approvalRequests = LinkedBlockingQueue<Intent>()
        val requestReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED ||
                    intent?.action == GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY ||
                    intent?.action == GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER
                ) {
                    approvalRequests.offer(intent)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            requestReceiver,
            IntentFilter().apply {
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED)
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY)
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            resetGuardianOverrides(context)
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = grantRuleId, ruleName = grantRuleName)
            ).use { scenario ->
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
                assertTrue(
                    "a stored grant from the replaced screen must not start confirmation for the new screen",
                    approvalRequests.poll(500, TimeUnit.MILLISECONDS) == null
                )
                scenario.onActivity { activity ->
                    assertDenialReason(activity, "Rule B")
                    assertFalse(
                        "the old write callback must not replace the current screen with a confirmation wait",
                        activity.findViewById<android.view.View>(R.id.approval_confirmation_progress)
                            .isShown
                    )
                }
            }
        } finally {
            try {
                resetGuardianOverrides(context)
            } finally {
                try {
                    restoreAppRuleSnapshot(context, originalSnapshot)
                } finally {
                    context.unregisterReceiver(requestReceiver)
                }
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
    fun skipUsesSharedConfirmationAndRetryDoesNotWriteAnotherSkip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = DataStoreManager(context)
        val originalSettings = runBlocking { dataStore.settings.first() }
        assumeTrue("This UI test requires an unset guardian PIN", !originalSettings.guardianAuthConfig.isConfigured)
        val ruleId = "approval-skip-${java.util.UUID.randomUUID()}"
        val originalSnapshot = seedActiveRuleWithoutRefresh(context, ruleId, "Skip test rule")
        val requests = LinkedBlockingQueue<Intent>()
        val requestReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let(requests::offer)
            }
        }
        ContextCompat.registerReceiver(
            context,
            requestReceiver,
            IntentFilter().apply {
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED)
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY)
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(
                    ruleId = ruleId,
                    ruleName = "Usage",
                    targetPackageName = "com.example.guardian.target"
                )
            ).use { scenario ->
                scenario.onActivity { activity -> chooseSkipDuration(activity, 0) }

                val persisted = awaitOverrideState(context) {
                    it.skips.any { skip -> skip.ruleId == ruleId }
                }
                val stored = requests.poll(5, TimeUnit.SECONDS)
                    ?: error("The skip operation did not start the shared confirmation check")
                assertEquals(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED, stored.action)
                assertEquals(
                    GuardianApprovalActivity.APPROVAL_KIND_SKIP,
                    stored.getStringExtra(GuardianApprovalActivity.EXTRA_APPROVAL_KIND)
                )
                assertEquals(ruleId, stored.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_RULE_ID))
                assertEquals(1, persisted.skips.count { it.ruleId == ruleId })
                assertFalse(persisted.grants.any { it.ruleId == ruleId })
                val operationId = stored.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
                val screenRequestId = stored.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
                val firstCheckId = stored.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                assertTrue(stored.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_UNTIL_MS, 0L) >
                    stored.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_FROM_MS, Long.MAX_VALUE))
                scenario.onActivity { activity ->
                    assertViewVisibility(activity, R.id.approval_confirmation_progress, android.view.View.VISIBLE)
                }

                val reconnectedId = "test-service-connection-2"
                fakeServiceConnectionId.set(reconnectedId)
                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_STATE_REQUEST)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID, reconnectedId)
                )
                val recovered = requests.poll(5, TimeUnit.SECONDS)
                    ?: error("The live screen did not rebind its approval receipt after service reconnect")
                assertEquals(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER, recovered.action)
                assertEquals(operationId, recovered.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID))
                assertEquals(screenRequestId, recovered.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID))
                assertTrue(firstCheckId != recovered.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID))
                assertEquals(
                    stored.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_RULE_ID),
                    recovered.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_RULE_ID)
                )
                assertEquals(
                    stored.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_USE_DAY_ID),
                    recovered.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_USE_DAY_ID)
                )
                assertEquals(
                    stored.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_FROM_MS, -1L),
                    recovered.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_FROM_MS, -2L)
                )
                assertEquals(
                    stored.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_UNTIL_MS, -1L),
                    recovered.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_UNTIL_MS, -2L)
                )
                assertEquals(reconnectedId, recovered.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID))

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            stored.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(GuardianApprovalActivity.EXTRA_CHECK_ID, firstCheckId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED
                        )
                )
                scenario.onActivity { activity ->
                    assertViewVisibility(activity, R.id.approval_confirmation_progress, android.view.View.VISIBLE)
                }
                scenario.onActivity { activity -> assertDenialReason(activity, "Daily limit reached") }
                assertEquals(
                    "reconnecting must not write another skip",
                    persisted,
                    awaitOverrideState(context) { it.skips.any { skip -> skip.ruleId == ruleId } }
                )

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID, reconnectedId)
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CHECK_ID,
                            recovered.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED
                        )
                )
                scenario.onActivity { activity ->
                    assertViewVisibility(activity, R.id.approval_confirmation_retry, android.view.View.VISIBLE)
                    activity.findViewById<android.view.View>(R.id.approval_confirmation_retry).performClick()
                }

                val retry = requests.poll(5, TimeUnit.SECONDS)
                    ?: error("The skip check retry was not sent")
                assertEquals(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY, retry.action)
                assertEquals(operationId, retry.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID))
                assertEquals(screenRequestId, retry.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID))
                assertTrue(firstCheckId != retry.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID))
                assertEquals(
                    stored.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_FROM_MS, -1L),
                    retry.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_FROM_MS, -2L)
                )
                assertEquals(
                    stored.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_UNTIL_MS, -1L),
                    retry.getLongExtra(GuardianApprovalActivity.EXTRA_SKIP_UNTIL_MS, -2L)
                )
                assertEquals(
                    persisted,
                    awaitOverrideState(context) { it.skips.any { skip -> skip.ruleId == ruleId } }
                )

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            retry.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CHECK_ID,
                            retry.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_REMAINING
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_DENIALS,
                            com.google.gson.Gson().toJson(
                                listOf(
                                    AppRuleGuardianDenial(
                                        ruleId = "night",
                                        ruleName = "Night rule",
                                        reason = "Night restriction"
                                    ),
                                    AppRuleGuardianDenial(
                                        ruleId = "quiet-hours",
                                        ruleName = "Quiet hours rule",
                                        reason = "Quiet hours restriction"
                                    )
                                )
                            )
                        )
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val choices = activity.findViewById<RadioGroup>(R.id.approval_choices)
                    assertEquals(2, choices.childCount)
                    assertDenialReasonAt(activity, 0, "Night rule")
                    assertDenialReasonAt(activity, 1, "Quiet hours rule")
                    (choices.getChildAt(1) as RadioButton).performClick()
                    assertEquals("quiet-hours", selectedRuleId(activity))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val choices = activity.findViewById<RadioGroup>(R.id.approval_choices)
                    assertEquals(2, choices.childCount)
                    assertDenialReasonAt(activity, 0, "Night rule")
                    assertDenialReasonAt(activity, 1, "Quiet hours rule")
                    assertTrue((choices.getChildAt(1) as RadioButton).isChecked)
                    assertEquals("quiet-hours", selectedRuleId(activity))
                }
                scenario.onActivity { activity ->
                    assertViewVisibility(activity, R.id.approval_confirmation_retry, android.view.View.GONE)
                }
                assertTrue("a remaining denial must not submit another approval check", requests.isEmpty())
            }
        } finally {
            try {
                assertTrue(
                    "The preexisting guardian override state must be restored",
                    runBlocking {
                        dataStore.writeAppRuleOverrideState("", originalSettings.appRuleOverrideState)
                    }
                )
            } finally {
                try {
                    writeAppRuleSnapshotDirectlyForTest(context, originalSnapshot)
                } finally {
                    context.unregisterReceiver(requestReceiver)
                }
            }
        }
    }

    @Test
    fun oldConfirmationResultCannotFinishAReusedCurrentScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = DataStoreManager(context)
        val originalSettings = runBlocking { dataStore.settings.first() }
        assumeTrue("This UI test requires an unset guardian PIN", !originalSettings.guardianAuthConfig.isConfigured)
        val ruleId = "approval-reuse-${java.util.UUID.randomUUID()}"
        val originalSnapshot = seedActiveRuleWithoutRefresh(context, ruleId, "Original denial")
        val requests = LinkedBlockingQueue<Intent>()
        val requestReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED) {
                    requests.offer(intent)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            requestReceiver,
            IntentFilter(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            resetGuardianOverrides(context)
            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(
                    ruleId = ruleId,
                    ruleName = "Original denial",
                    targetPackageName = "com.example.guardian.target"
                )
            ).use { scenario ->
                scenario.onActivity { activity -> chooseSkipDuration(activity, 0) }
                val originalRequest = requests.poll(5, TimeUnit.SECONDS)
                    ?: error("The original screen did not submit its skip receipt")
                val persistedBeforeReuse = awaitOverrideState(context) {
                    it.skips.any { skip -> skip.ruleId == ruleId }
                }
                assertEquals(1, persistedBeforeReuse.skips.count { it.ruleId == ruleId })

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_OPERATION_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CHECK_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED
                        )
                )
                scenario.onActivity { activity ->
                    assertViewVisibility(activity, R.id.approval_confirmation_retry, android.view.View.VISIBLE)
                }
                lateinit var originalActivity: GuardianApprovalActivity
                scenario.onActivity { originalActivity = it }

                context.startActivity(
                    approvalIntent(ruleId = "replacement", reason = "Current denial after reuse")
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(originalActivity, activity)
                    assertDenialReason(activity, "Current denial after reuse")
                }

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_OPERATION_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CHECK_ID,
                            originalRequest.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED
                        )
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse("the stale ALLOWED result must not finish the reused screen", activity.isFinishing)
                    assertDenialReason(activity, "Current denial after reuse")
                }
                assertEquals(
                    "the committed approval remains after the current screen changes",
                    persistedBeforeReuse,
                    awaitOverrideState(context) { it.skips.any { skip -> skip.ruleId == ruleId } }
                )
            }
        } finally {
            try {
                assertTrue(
                    "the preexisting guardian override state must be restored",
                    runBlocking {
                        dataStore.writeAppRuleOverrideState("", originalSettings.appRuleOverrideState)
                    }
                )
            } finally {
                try {
                    writeAppRuleSnapshotDirectlyForTest(context, originalSnapshot)
                } finally {
                    context.unregisterReceiver(requestReceiver)
                }
            }
        }
    }

    @Test
    fun accumulatedGrantUsesSharedConfirmationAndRetryDoesNotDeductThePoolAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = DataStoreManager(context)
        val initialSettings = runBlocking { dataStore.settings.first() }
        assumeTrue("This UI test requires an unset guardian PIN", !initialSettings.guardianAuthConfig.isConfigured)
        val delayConfig = initialSettings.settingsChangeDelayConfig2
        val hasPendingAppRuleEdit = delayConfig.pendingChanges.any {
            it.field == GatedSettingsField.APP_RULES.name
        }
        assumeTrue(
            "This UI test requires immediate app-rule writes",
            !hasPendingAppRuleEdit &&
                (!delayConfig.isEnabled || delayConfig.delayMinutes == 0) &&
                (!delayConfig.requireTamperProtectionOff || !initialSettings.antiUninstallConfig2.isEnabled)
        )
        val originalSnapshot = initialSettings.appRuleSnapshot
        val ruleId = "approval-accumulated-${java.util.UUID.randomUUID()}"
        val originalPool = initialSettings.appRuleRolloverState.pools[ruleId]
        val rule = AppRule(
            id = ruleId,
            name = "Accumulated test rule",
            isActive = true,
            weekdays = (0..6).toSet(),
            allowedMinutes = 0L,
            rolloverEnabled = true,
            unlockDays = (0..6).toSet(),
            guardianExtraTimeAllowed = true
        )
        val ambiguousCommitCount = AtomicInteger(0)
        val seededSnapshot = originalSnapshot.copy(
            appRules = originalSnapshot.appRules + rule
        ).normalized()
        val requests = LinkedBlockingQueue<Intent>()
        val requestReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let(requests::offer)
            }
        }
        ContextCompat.registerReceiver(
            context,
            requestReceiver,
            IntentFilter().apply {
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED)
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY)
                addAction(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            assertTrue(runBlocking { dataStore.updateAppRuleSnapshot(seededSnapshot) })
            val seededSettings = runBlocking {
                withTimeout(5_000L) {
                    dataStore.settings.first { it.appRuleSnapshot == seededSnapshot }
                }
            }
            assertTrue(
                "The accumulated grant fixture pool must be written",
                runBlocking {
                    dataStore.writeManualAppRuleRolloverPool(
                        ruleId = ruleId,
                        accumulatedMinutes = 20L,
                        basedOn = seededSettings
                    )
                }
            )
            val useDayId = ConfigurableUseDayCalculator(
                resetTime = seededSettings.useDayResetTime
            ).idAt(System.currentTimeMillis())

            ActivityScenario.launch<GuardianApprovalActivity>(
                approvalIntent(ruleId = ruleId, ruleName = "Accumulated test rule")
            ).use { scenario ->
                awaitDisplayed(R.id.approval_use_accumulated_time)
                onView(withId(R.id.approval_use_accumulated_time)).perform(click())
                awaitDisplayed(R.id.accumulated_minutes_input, inDialog = true)
                DataStoreManager.guardianApprovalWriteCommitObserverForTest = {
                    ambiguousCommitCount.incrementAndGet()
                    throw java.io.IOException("injected failure after the settings commit")
                }
                onView(withText(R.string.guardian_apply))
                    .inRoot(isDialog())
                    .perform(click())

                val storedSettings = runBlocking {
                    withTimeout(5_000L) {
                        dataStore.settings.first { settings ->
                            settings.appRuleRolloverState.pools[ruleId]?.accumulatedMinutes == 0L &&
                                settings.appRuleOverrideState.grants.any {
                                    it.ruleId == ruleId && it.isFromAccumulatedPool
                                }
                        }
                    }
                }
                val stored = requests.poll(5, TimeUnit.SECONDS)
                    ?: error("The accumulated grant did not start the shared confirmation check")
                DataStoreManager.guardianApprovalWriteCommitObserverForTest = null
                assertEquals(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_STORED, stored.action)
                assertEquals(
                    GuardianApprovalActivity.APPROVAL_KIND_ACCUMULATED,
                    stored.getStringExtra(GuardianApprovalActivity.EXTRA_APPROVAL_KIND)
                )
                assertEquals(ruleId, stored.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_RULE_ID))
                assertEquals(useDayId, stored.getStringExtra(GuardianApprovalActivity.EXTRA_RECEIPT_USE_DAY_ID))
                assertEquals(20L, storedSettings.appRuleOverrideState.grants
                    .single { it.ruleId == ruleId }
                    .grantedMillis / 60_000L)
                assertEquals("the uncertain write committed once", 1, ambiguousCommitCount.get())
                val operationId = stored.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
                val screenRequestId = stored.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
                val firstCheckId = stored.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                awaitDisplayed(R.id.approval_confirmation_progress)

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            stored.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(GuardianApprovalActivity.EXTRA_CHECK_ID, firstCheckId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATE,
                            GuardianApprovalConfirmationState.UNCONFIRMED.name.lowercase()
                        )
                )
                awaitDisplayed(R.id.approval_confirmation_retry)
                onView(withId(R.id.approval_confirmation_retry)).perform(click())

                val retry = requests.poll(5, TimeUnit.SECONDS)
                    ?: error("The accumulated check retry was not sent")
                assertEquals(GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY, retry.action)
                assertEquals(operationId, retry.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID))
                assertTrue(firstCheckId != retry.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID))
                assertEquals(
                    stored.getLongExtra(GuardianApprovalActivity.EXTRA_RECEIPT_GRANTED_AT_MS, -1L),
                    retry.getLongExtra(GuardianApprovalActivity.EXTRA_RECEIPT_GRANTED_AT_MS, -2L)
                )
                val supersedingSnapshot = seededSnapshot.copy(
                    appRules = seededSnapshot.appRules.map {
                        if (it.id == ruleId) it.copy(guardianExtraTimeAllowed = false) else it
                    }
                )
                assertTrue(runBlocking { dataStore.updateAppRuleSnapshot(supersedingSnapshot) })
                val supersedingSettings = runBlocking {
                    withTimeout(5_000L) {
                        dataStore.settings.first {
                            it.appRuleSnapshot == supersedingSnapshot &&
                                it.appRuleOverrideState.grants.none { grant -> grant.ruleId == ruleId }
                        }
                    }
                }
                assertEquals(0L, supersedingSettings.appRuleRolloverState.pools[ruleId]?.accumulatedMinutes)
                assertEquals(1, ambiguousCommitCount.get())

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            stored.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(GuardianApprovalActivity.EXTRA_CHECK_ID, firstCheckId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED
                        )
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity -> assertDenialReason(activity, "Accumulated test rule") }

                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            retry.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CHECK_ID,
                            retry.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_REMAINING
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_DENIALS,
                            com.google.gson.Gson().toJson(
                                listOf(
                                    AppRuleGuardianDenial(
                                        ruleId = ruleId,
                                        ruleName = "Updated test rule",
                                        reason = "Extra time was turned off"
                                    )
                                )
                            )
                        )
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATE,
                            GuardianApprovalConfirmationState.SUPERSEDED.name.lowercase()
                        )
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity -> assertDenialReason(activity, "Updated test rule") }
                onView(withId(R.id.approval_confirmation_retry))
                    .check(matches(withEffectiveVisibility(Visibility.GONE)))
                context.sendBroadcast(
                    Intent(GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT)
                        .setPackage(context.packageName)
                        .putExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID, screenRequestId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID,
                            stored.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
                        )
                        .putExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID, operationId)
                        .putExtra(GuardianApprovalActivity.EXTRA_CHECK_ID, firstCheckId)
                        .putExtra(
                            GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS,
                            GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED
                        )
                )
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity -> assertDenialReason(activity, "Updated test rule") }
                assertTrue("a superseded receipt must not submit another confirmation check", requests.isEmpty())
                val afterRetry = runBlocking { dataStore.settings.first() }
                assertEquals(supersedingSettings.appRuleRolloverState, afterRetry.appRuleRolloverState)
                assertEquals(supersedingSettings.appRuleOverrideState, afterRetry.appRuleOverrideState)
                assertEquals(1, ambiguousCommitCount.get())
            }
        } finally {
            DataStoreManager.guardianApprovalWriteCommitObserverForTest = null
            val currentSettings = runBlocking { dataStore.settings.first() }
            runBlocking {
                dataStore.writeManualAppRuleRolloverPool(
                    ruleId = ruleId,
                    accumulatedMinutes = 0L,
                    basedOn = currentSettings
                )
                restoreAppRuleSnapshot(context, originalSnapshot)
                dataStore.writeAppRuleOverrideState(
                    password = "",
                    state = initialSettings.appRuleOverrideState
                )
                restoreRolloverPool(context, ruleId, originalPool)
            }
            context.unregisterReceiver(requestReceiver)
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

    private fun assertDenialReasonAt(
        activity: GuardianApprovalActivity,
        index: Int,
        reason: String
    ) {
        assertTrue(
            (activity.findViewById<RadioGroup>(R.id.approval_choices)
                .getChildAt(index) as RadioButton).text.contains(reason)
        )
    }

    private fun selectedRuleId(activity: GuardianApprovalActivity): String? =
        GuardianApprovalActivity::class.java.getDeclaredField("selectedRuleId")
            .apply { isAccessible = true }
            .get(activity) as? String

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

    private fun chooseSkipDuration(activity: GuardianApprovalActivity, position: Int) {
        activity.findViewById<android.view.View>(R.id.approval_skip_rule).performClick()
        val dialogsField = GuardianApprovalActivity::class.java.getDeclaredField("ownedDialogs").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val dialogs = dialogsField.get(activity) as Set<androidx.appcompat.app.AlertDialog>
        val dialog = dialogs.lastOrNull() ?: error("The skip duration dialog was not shown")
        val listView = dialog.listView
        require(position in 0 until listView.adapter.count)
        listView.performItemClick(null, position, listView.adapter.getItemId(position))
    }

    private fun assertViewVisibility(
        activity: GuardianApprovalActivity,
        viewId: Int,
        expectedVisibility: Int
    ) {
        assertEquals(
            "Unexpected visibility for view id $viewId",
            expectedVisibility,
            activity.findViewById<android.view.View>(viewId).visibility
        )
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

    private fun seedActiveRuleWithoutRefresh(
        context: Context,
        ruleId: String,
        ruleName: String
    ): AppRuleSnapshot {
        val settings = runBlocking { DataStoreManager(context).settings.first() }
        val originalSnapshot = settings.appRuleSnapshot
        val testRule = AppRule(
            id = ruleId,
            name = ruleName,
            allowedMinutes = Long.MAX_VALUE / 60_000L
        )
        val seededSnapshot = originalSnapshot.copy(
            appRules = originalSnapshot.appRules.filterNot { it.id == ruleId } + testRule
        ).normalized()
        writeAppRuleSnapshotDirectlyForTest(context, seededSnapshot)
        return originalSnapshot
    }

    private fun writeAppRuleSnapshotDirectlyForTest(context: Context, snapshot: AppRuleSnapshot) {
        val storeField = DataStoreManager::class.java.getDeclaredField("settingsDataStore").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(DataStoreManager(context)) as androidx.datastore.core.DataStore<Settings>
        runBlocking {
            store.updateData { current -> current.copy(appRuleSnapshot = snapshot.normalized()) }
        }
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

    private suspend fun restoreRolloverPool(
        context: Context,
        ruleId: String,
        originalPool: RuleRolloverPool?
    ) {
        val manager = DataStoreManager(context)
        val storeField = DataStoreManager::class.java.getDeclaredField("settingsDataStore").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(manager) as androidx.datastore.core.DataStore<Settings>
        store.updateData { current ->
            val pools = current.appRuleRolloverState.pools.toMutableMap()
            if (originalPool == null) {
                pools.remove(ruleId)
            } else {
                pools[ruleId] = originalPool
            }
            current.copy(
                appRuleRolloverState = current.appRuleRolloverState.copy(pools = pools)
            )
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
        reason: String = "Daily limit reached",
        targetPackageName: String? = null
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
            packageName = targetPackageName ?: context.packageName,
            denials = denials
        )
    }
}
