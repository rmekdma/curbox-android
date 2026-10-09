package neth.iecal.curbox.blockers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.GuardianApprovalCoordinator
import neth.iecal.curbox.domain.apprules.GuardianApprovalEvaluationStatus
import neth.iecal.curbox.domain.apprules.GuardianApprovalPolicyFingerprint
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.DataStoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** End-to-end host coverage for the guardian evaluation worker and result handler. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerGuardianWorkerIntegrationTest {
    @Test
    fun fixtureRestoresSettingsWhenSetupFailsAfterSeeding() {
        val dataStore = DataStoreManager(InstrumentationContext.context)
        val originalSettings = runBlocking { dataStore.settings.first() }
        val injectedFailure = IllegalStateException("Injected guardian fixture setup failure")
        val observedSeededSettings = AtomicBoolean(false)

        val setupFailure = runCatching {
            createConfirmationHostFixture(includeRemainingRule = false) { _ ->
                val currentSettings = runBlocking { dataStore.settings.first() }
                assertEquals(
                    listOf(GUARDIAN_WORKER_TARGET_PACKAGE),
                    currentSettings.appRuleSnapshot.appGroups.single().selectedPackages
                )
                assertEquals(
                    "guardian-target-allowance",
                    currentSettings.appRuleOverrideState.grants.single().ruleId
                )
                observedSeededSettings.set(true)
                throw injectedFailure
            }
        }.exceptionOrNull()

        assertSame(injectedFailure, setupFailure)
        assertTrue(
            "the fixture must seed settings before the injected setup failure",
            observedSeededSettings.get()
        )
        val restoredSettings = runBlocking { dataStore.settings.first() }
        assertEquals(originalSettings.appRuleSnapshot, restoredSettings.appRuleSnapshot)
        assertEquals(originalSettings.appRuleOverrideState, restoredSettings.appRuleOverrideState)
    }

    @Test
    fun actualGuardianWorkerSendsAllowedOfferForAcceptedRequest() {
        val fixture = createConfirmationHostFixture(includeRemainingRule = false)
        try {
            val gateArmed = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            val gateReached = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            val resultSent = fixture.service.expectBroadcast {
                it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == fixture.checkId &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                    GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED
            }

            fixture.armOutcomeGate()
            val armed = gateArmed.awaitBroadcast("worker outcome gate arm")
            assertTrue(armed.getBooleanExtra("guardian_test_gate_accepted", false))
            fixture.openScreenAndSubmitCheck()

            val reached = gateReached.awaitBroadcast("worker outcome gate reach")
            assertTrue(reached.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(
                fixture.operationId,
                reached.getStringExtra(AppRuleBlocker.EXTRA_TEST_OPERATION_ID)
            )
            assertEquals(
                fixture.checkId,
                reached.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID)
            )
            assertTrue(
                "the actual serialized worker did not publish this guardian evaluation",
                fixture.workerOutcomePublished.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )

            val outcome = checkNotNull(fixture.workerOutcome.get())
            val acceptedDeadlineElapsedRealtimeMs = getField(
                fixture.blocker,
                "guardianConfirmationDeadlineElapsedRealtimeMs"
            ) as Long
            assertEquals(fixture.targetPackage, outcome.request.packageName)
            assertEquals(fixture.screenRequestId, outcome.request.screenRequestId)
            assertEquals(fixture.operationId, outcome.request.operationId)
            assertEquals(fixture.checkId, outcome.request.checkId)
            assertEquals(GuardianApprovalEvaluationStatus.COMPLETED, outcome.status)
            assertEquals(
                neth.iecal.curbox.domain.apprules.GuardianApprovalConfirmationState.REFLECTED,
                outcome.confirmationState
            )
            assertTrue(
                "the current target evaluation must allow the app",
                outcome.evaluation?.isAllowed == true
            )

            fixture.releaseOutcomeGate()
            val result = resultSent.awaitBroadcast("ALLOWED confirmation result")
            assertEquals(
                fixture.screenRequestId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
            )
            assertEquals(
                fixture.operationId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
            )
            assertEquals(fixture.checkId, result.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID))
            assertEquals(
                fixture.serviceConnectionId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
            )
            assertEquals(fixture.context.packageName, result.`package`)
            assertEquals(
                outcome.acceptedRuntimeRevision.value,
                result.getLongExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_RUNTIME_REVISION, -1L)
            )
            assertEquals(
                outcome.request.capturedAtWallMs,
                result.getLongExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_CAPTURED_AT_WALL_CLOCK_MS, -1L)
            )
            assertEquals(
                outcome.request.capturedAtElapsedMs,
                result.getLongExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_CAPTURED_AT_ELAPSED_REALTIME_MS, -1L)
            )
            assertEquals(
                outcome.evaluationZoneId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_EVALUATION_ZONE_ID)
            )
            assertEquals(
                outcome.validUntilWallClockMs,
                result.getLongExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_VALID_UNTIL_WALL_CLOCK_MS, -1L)
            )
            assertEquals(
                GuardianApprovalPolicyFingerprint.forSettings(fixture.seededSettings),
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_POLICY_FINGERPRINT)
            )
            assertEquals(
                "allowed result must carry the active check deadline",
                acceptedDeadlineElapsedRealtimeMs,
                result.getLongExtra(
                    GuardianApprovalActivity.EXTRA_CONFIRMATION_DEADLINE_ELAPSED_REALTIME_MS,
                    -1L
                )
            )
            assertTrue(
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_OFFER_ID)
                    .orEmpty().isNotBlank()
            )
            assertEquals(
                "[]",
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_DENIALS)
            )
            assertEquals(
                "reflected",
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATE)
            )
            assertEquals(
                "the result is addressed to Curbox while the accepted worker request identifies the target",
                fixture.context.packageName,
                result.`package`
            )
            assertEquals(
                "the result schema does not add a target-package extra",
                null,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_GUARDIAN_PACKAGE)
            )
            val coordinator = getField(
                fixture.blocker,
                "guardianApprovalCoordinator"
            ) as GuardianApprovalCoordinator
            val offeredConfirmation = checkNotNull(coordinator.currentOwner()?.confirmation)
            assertEquals(GuardianApprovalCoordinator.ConfirmationPhase.OFFERED, offeredConfirmation.phase)
            assertEquals(
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_OFFER_ID),
                offeredConfirmation.offerId
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun actualGuardianWorkerSendsRemainingRowsFromItsEvaluation() {
        val fixture = createConfirmationHostFixture(includeRemainingRule = true)
        try {
            val gateArmed = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            val gateReached = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            val resultSent = fixture.service.expectBroadcast {
                it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == fixture.checkId &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                    GuardianApprovalActivity.CONFIRMATION_STATUS_REMAINING
            }

            fixture.armOutcomeGate()
            assertTrue(
                gateArmed.awaitBroadcast("worker outcome gate arm")
                    .getBooleanExtra("guardian_test_gate_accepted", false)
            )
            fixture.openScreenAndSubmitCheck()
            val reached = gateReached.awaitBroadcast("worker outcome gate reach")
            assertTrue(reached.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(
                fixture.operationId,
                reached.getStringExtra(AppRuleBlocker.EXTRA_TEST_OPERATION_ID)
            )
            assertEquals(
                fixture.checkId,
                reached.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID)
            )
            assertTrue(
                "the actual serialized worker did not publish this guardian evaluation",
                fixture.workerOutcomePublished.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )

            val outcome = checkNotNull(fixture.workerOutcome.get())
            val evaluation = checkNotNull(outcome.evaluation)
            assertEquals(fixture.targetPackage, outcome.request.packageName)
            assertEquals(fixture.screenRequestId, outcome.request.screenRequestId)
            assertEquals(fixture.operationId, outcome.request.operationId)
            assertEquals(fixture.checkId, outcome.request.checkId)
            assertEquals(GuardianApprovalEvaluationStatus.COMPLETED, outcome.status)
            assertEquals(
                neth.iecal.curbox.domain.apprules.GuardianApprovalConfirmationState.REFLECTED,
                outcome.confirmationState
            )
            assertTrue("the second target rule must keep the target restricted", !evaluation.isAllowed)
            assertEquals(listOf("guardian-remaining-denial"), evaluation.denyingRules.map { it.ruleId })
            assertEquals(
                mapOf("guardian-remaining-denial" to "Remaining target rule"),
                outcome.denyingRuleNames
            )

            fixture.releaseOutcomeGate()
            val result = resultSent.awaitBroadcast("REMAINING confirmation result")
            assertEquals(
                fixture.screenRequestId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
            )
            assertEquals(
                fixture.operationId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
            )
            assertEquals(fixture.checkId, result.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID))
            assertEquals(
                fixture.serviceConnectionId,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
            )
            assertEquals(fixture.context.packageName, result.`package`)
            assertEquals(
                GuardianApprovalActivity.CONFIRMATION_STATUS_REMAINING,
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS)
            )
            assertEquals(
                "reflected",
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATE)
            )

            val rows = Gson().fromJson(
                result.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_DENIALS),
                Array<neth.iecal.curbox.data.models.AppRuleGuardianDenial>::class.java
            ).toList()
            assertEquals(evaluation.denyingRules.map { it.ruleId }, rows.map { it.ruleId })
            assertEquals(
                evaluation.denyingRules.map { outcome.denyingRuleNames.getValue(it.ruleId) },
                rows.map { it.ruleName }
            )
            assertEquals(
                evaluation.denyingRules.map { it.conditionProgresses },
                rows.map { it.conditionProgresses }
            )
            assertEquals(
                evaluation.denyingRules.map { it.isAllowanceExhausted },
                rows.map { it.isAllowanceExhausted }
            )
            assertEquals(
                evaluation.denyingRules.map { (it.usedMillis / 60_000L).coerceAtLeast(0L) },
                rows.map { it.usedMinutes }
            )
            assertEquals(
                evaluation.denyingRules.map {
                    (it.effectiveAllowanceMillis / 60_000L).coerceAtLeast(0L)
                },
                rows.map { it.totalAllowedMinutes }
            )
            assertEquals(
                evaluation.denyingRules.map { fixture.blocker.warningStatusForTest(it) },
                rows.map { it.reason }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedAllowedSendCleansPermitBeforeFailedAndAcceptsNextCheck() {
        val fixture = createConfirmationHostFixture(includeRemainingRule = false)
        try {
            val gateArmed = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            val gateReached = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            val failedResultSent = fixture.service.expectBroadcast {
                it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == fixture.checkId &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                    GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED
            }
            val failedCheckCleanupObserved = CountDownLatch(1)
            val failedResultSendReturned = CountDownLatch(1)
            val permitBaseline = AtomicReference<ExternalEffectSnapshot?>()
            val allowedAttempt = AtomicReference<ExternalEffectSnapshot?>()
            val cleanupAtFailure = AtomicReference<ExternalEffectSnapshot?>()
            val retryCheckId = "guardian-host-retry-${java.util.UUID.randomUUID()}"
            val retryAllowedResult = fixture.service.expectBroadcast {
                it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == retryCheckId &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                    GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED
            }
            fixture.service.sendBroadcastObserver = { intent ->
                if (intent.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    intent.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == fixture.checkId
                ) {
                    when (intent.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS)) {
                        GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED ->
                            allowedAttempt.set(fixture.externalEffectSnapshot())
                        GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED -> {
                            cleanupAtFailure.set(fixture.externalEffectSnapshot())
                            failedCheckCleanupObserved.countDown()
                        }
                    }
                }
            }
            fixture.service.sendBroadcastCompletionObserver = { intent ->
                if (intent.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                    intent.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == fixture.checkId &&
                    intent.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                    GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED
                ) {
                    failedResultSendReturned.countDown()
                }
            }
            fixture.service.failNextConfirmationStatus =
                GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED

            fixture.armOutcomeGate()
            assertTrue(
                gateArmed.awaitBroadcast("worker outcome gate arm")
                    .getBooleanExtra("guardian_test_gate_accepted", false)
            )
            fixture.openScreenAndSubmitCheck()
            val reached = gateReached.awaitBroadcast("worker outcome gate reach")
            assertTrue(reached.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(fixture.operationId, reached.getStringExtra(AppRuleBlocker.EXTRA_TEST_OPERATION_ID))
            assertEquals(fixture.checkId, reached.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID))
            assertTrue(
                "the actual serialized worker did not publish this guardian evaluation",
                fixture.workerOutcomePublished.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            permitBaseline.set(fixture.externalEffectSnapshot())
            val outcome = checkNotNull(fixture.workerOutcome.get())
            assertEquals(fixture.targetPackage, outcome.request.packageName)
            assertEquals(fixture.operationId, outcome.request.operationId)
            assertEquals(fixture.checkId, outcome.request.checkId)
            assertTrue(
                "the current target evaluation must allow the app",
                outcome.evaluation?.isAllowed == true
            )

            fixture.releaseOutcomeGate()
            val failedResult = failedResultSent.awaitBroadcast("FAILED after ALLOWED send failure")
            assertTrue(
                "the cleanup state was not observed at the FAILED send",
                failedCheckCleanupObserved.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "the FAILED broadcast did not return before the retry",
                failedResultSendReturned.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertEquals(
                fixture.screenRequestId,
                failedResult.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
            )
            assertEquals(
                fixture.serviceConnectionId,
                failedResult.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
            )
            assertEquals(
                fixture.operationId,
                failedResult.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
            )
            assertEquals(
                fixture.checkId,
                failedResult.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
            )
            assertEquals(fixture.context.packageName, failedResult.`package`)
            assertEquals(
                GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED,
                failedResult.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS)
            )
            assertEquals(null, fixture.service.failNextConfirmationStatus)
            assertEquals(
                listOf(
                    GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED,
                    GuardianApprovalActivity.CONFIRMATION_STATUS_FAILED
                ),
                fixture.service.sentBroadcasts.filter {
                    it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                        it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == fixture.checkId
                }.map { it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) }
            )
            val baseline = checkNotNull(permitBaseline.get())
            val allowedSend = checkNotNull(allowedAttempt.get())
            val failureSnapshot = checkNotNull(cleanupAtFailure.get())
            val allowedPermit = allowedSend.pendingPermits - baseline.pendingPermits
            assertEquals("the ALLOWED handler should reserve one fresh permit", 1, allowedPermit.size)
            assertTrue(
                "the failed permit remained pending when FAILED was sent",
                allowedPermit.none { it in failureSnapshot.pendingPermits }
            )
            assertTrue(
                "FAILED must not inherit a permit created by the failed ALLOWED send",
                failureSnapshot.pendingPermits.all { it in baseline.pendingPermits }
            )
            assertTrue(
                "the failed ALLOWED call's in-flight count was not drained before FAILED",
                failureSnapshot.inFlightCallbackCount <= allowedSend.inFlightCallbackCount - 1
            )
            val failedOwner = checkNotNull(failureSnapshot.owner)
            assertEquals(fixture.screenRequestId, failedOwner.screenRequestId)
            assertEquals(fixture.targetPackage, failedOwner.packageName)
            assertEquals(fixture.operationId, failedOwner.confirmation?.operationId)
            assertEquals(fixture.checkId, failedOwner.confirmation?.checkId)
            assertEquals(
                GuardianApprovalCoordinator.ConfirmationPhase.FINISHED,
                failedOwner.confirmation?.phase
            )

            fixture.submitCheck(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
                checkId = retryCheckId
            )
            val retryResult = retryAllowedResult.awaitBroadcast("follow-up ALLOWED result")
            assertEquals(
                fixture.screenRequestId,
                retryResult.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
            )
            assertEquals(
                fixture.operationId,
                retryResult.getStringExtra(GuardianApprovalActivity.EXTRA_OPERATION_ID)
            )
            assertEquals(
                retryCheckId,
                retryResult.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID)
            )
            assertEquals(
                fixture.serviceConnectionId,
                retryResult.getStringExtra(GuardianApprovalActivity.EXTRA_SERVICE_CONNECTION_ID)
            )
            assertEquals(
                GuardianApprovalActivity.CONFIRMATION_STATUS_ALLOWED,
                retryResult.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS)
            )
            val currentConfirmation = checkNotNull(
                (getField(fixture.blocker, "guardianApprovalCoordinator") as GuardianApprovalCoordinator)
                    .currentOwner()?.confirmation
            )
            assertEquals(GuardianApprovalCoordinator.ConfirmationPhase.OFFERED, currentConfirmation.phase)
            assertEquals(retryCheckId, currentConfirmation.checkId)
            assertTrue(currentConfirmation.offerId.orEmpty().isNotBlank())
            assertEquals(
                retryResult.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_OFFER_ID),
                currentConfirmation.offerId
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun recoveryAcceptedBeforeTimeoutLockRejectsStaleATimeoutAndKeepsBDeadline() {
        val fixture = createConfirmationHostFixture(includeRemainingRule = false)
        val checkA = fixture.checkId
        val checkB = "guardian-host-recovery-${java.util.UUID.randomUUID()}"
        val timeoutAReachedBeforeLock = CountDownLatch(1)
        val timeoutBReachedBeforeLock = CountDownLatch(1)
        val handledByCheck = ConcurrentHashMap<String, CountDownLatch>()
        val timeoutObserver: (GuardianApprovalCoordinator.CheckIdentity) -> Unit = { identity ->
            when (identity.checkId) {
                checkA -> timeoutAReachedBeforeLock.countDown()
                checkB -> timeoutBReachedBeforeLock.countDown()
            }
        }
        val outcomeHandledObserver: (GuardianApprovalCoordinator.CheckIdentity) -> Unit = { identity ->
            handledByCheck.computeIfAbsent(identity.checkId) { CountDownLatch(1) }.countDown()
        }
        val timeoutBResult = fixture.service.expectBroadcast {
            it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == checkB &&
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
        }
        val gateAReached = fixture.service.expectBroadcast {
            it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
        }
        val gateBReached = fixture.service.expectBroadcast {
            it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                it.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID) == checkB
        }
        try {
            setField(fixture.blocker, "guardianTimeoutPostDelayObserverForTest", timeoutObserver)
            setField(
                fixture.blocker,
                "guardianOutcomeHandlerFinishedObserverForTest",
                outcomeHandledObserver
            )
            val gateAArmed = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            fixture.armOutcomeGate()
            assertTrue(
                gateAArmed.awaitBroadcast("A worker gate arm")
                    .getBooleanExtra("guardian_test_gate_accepted", false)
            )
            fixture.openScreenAndSubmitCheck()
            val reachedA = gateAReached.awaitBroadcast("A worker gate reach")
            assertTrue(reachedA.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(checkA, reachedA.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID))
            val outcomeA = fixture.awaitWorkerOutcome(checkA)
            assertTrue("A must remain gated before its timeout", outcomeA.evaluation?.isAllowed == true)

            val runtimeLock = getField(fixture.blocker, "runtimeLock") as Any
            val gateBId = synchronized(runtimeLock) {
                assertTrue(
                    "A's production delay did not reach the pre-lock boundary",
                    timeoutAReachedBeforeLock.await(30_000L, TimeUnit.MILLISECONDS)
                )
                assertConfirmation(
                    fixture,
                    checkA,
                    GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
                )

                fixture.receiveCheckSynchronously(
                    action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
                    checkId = checkB
                )
                assertConfirmation(
                    fixture,
                    checkA,
                    GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
                )

                fixture.receiveCheckSynchronously(
                    action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_RECOVER,
                    checkId = checkB
                )
                assertConfirmation(
                    fixture,
                    checkB,
                    GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
                )

                fixture.releaseOutcomeGate(fixture.gateId)
                val acceptedGateId = fixture.armOutcomeGateWhenAvailable("guardian-host-gate-b")
                val replacementTimer = getField(fixture.blocker, "guardianConfirmationTimeoutJob")
                    as kotlinx.coroutines.Job
                assertTrue("B's replacement timeout job must still be active", replacementTimer.isActive)
                acceptedGateId
            }

            val reachedB = gateBReached.awaitBroadcast("B worker gate reach")
            assertTrue(reachedB.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(checkB, reachedB.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID))
            assertTrue(
                "A's stale handler did not finish after its gate was released",
                handledByCheck.computeIfAbsent(checkA) { CountDownLatch(1) }
                    .await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            val outcomeB = fixture.awaitWorkerOutcome(checkB)
            assertEquals(fixture.targetPackage, outcomeB.request.packageName)
            assertEquals(fixture.operationId, outcomeB.request.operationId)
            assertTrue(outcomeB.evaluation?.isAllowed == true)
            assertConfirmation(
                fixture,
                checkB,
                GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            )

            val timeoutResult = timeoutBResult.awaitBroadcast(
                "B timeout at its own deadline",
                timeoutMs = 25_000L
            )
            assertTimeoutIdentity(fixture, timeoutResult, checkB)
            assertTrue(
                "B's timeout delay did not reach its pre-lock signal",
                timeoutBReachedBeforeLock.await(5_000L, TimeUnit.MILLISECONDS)
            )
            fixture.releaseOutcomeGate(gateBId)
            assertTrue(
                "B's stale handler did not finish after timeout released its gate",
                handledByCheck.computeIfAbsent(checkB) { CountDownLatch(1) }
                    .await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertConfirmation(
                fixture,
                checkB,
                GuardianApprovalCoordinator.ConfirmationPhase.TIMED_OUT
            )
            assertEquals(
                listOf(checkB to GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT),
                fixture.confirmationResults()
                    .map { it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) to
                        it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) }
            )
        } finally {
            try {
                setField(fixture.blocker, "guardianTimeoutPostDelayObserverForTest", null)
                setField(fixture.blocker, "guardianOutcomeHandlerFinishedObserverForTest", null)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun timedOutASendMayArriveAfterRetryBWithoutChangingItsDeadline() {
        val fixture = createConfirmationHostFixture(includeRemainingRule = false)
        val checkA = fixture.checkId
        val checkB = "guardian-host-retry-after-timeout-${java.util.UUID.randomUUID()}"
        val timeoutSendEntered = CountDownLatch(1)
        val releaseTimeoutSend = CountDownLatch(1)
        val timeoutASendCompleted = CountDownLatch(1)
        val bOutcomeAtSink = CountDownLatch(1)
        val releaseBOutcomeAtSink = CountDownLatch(1)
        val bOutcomeSinkTimedOut = AtomicBoolean(false)
        val handledByCheck = ConcurrentHashMap<String, CountDownLatch>()
        val originalOutcomeSinkObserver = fixture.blocker.decisionOutcomeSinkObserver
        val outcomeHandledObserver: (GuardianApprovalCoordinator.CheckIdentity) -> Unit = { identity ->
            handledByCheck.computeIfAbsent(identity.checkId) { CountDownLatch(1) }.countDown()
        }
        val timeoutAResult = fixture.service.expectBroadcast {
            it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == checkA &&
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
        }
        val timeoutBResult = fixture.service.expectBroadcast {
            it.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == checkB &&
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
        }
        val gateAReached = fixture.service.expectBroadcast {
            it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
        }
        val gateBReached = fixture.service.expectBroadcast {
            it.action == AppRuleBlocker.INTENT_ACTION_TEST_GUARDIAN_EVALUATION_GATE_REACHED &&
                it.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID) == checkB
        }
        fixture.service.sendBroadcastObserver = { intent ->
            if (intent.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                intent.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == checkA &&
                intent.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
            ) {
                timeoutSendEntered.countDown()
                releaseTimeoutSend.await(30_000L, TimeUnit.MILLISECONDS)
            }
        }
        fixture.service.sendBroadcastCompletionObserver = { intent ->
            if (intent.action == GuardianApprovalActivity.INTENT_ACTION_CONFIRMATION_RESULT &&
                intent.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == checkA &&
                intent.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
            ) {
                timeoutASendCompleted.countDown()
            }
        }
        try {
            setField(
                fixture.blocker,
                "guardianOutcomeHandlerFinishedObserverForTest",
                outcomeHandledObserver
            )
            fixture.blocker.decisionOutcomeSinkObserver = { outcome ->
                originalOutcomeSinkObserver?.invoke(outcome)
                if (outcome is DecisionOutcome.GuardianApprovalEvaluationReady &&
                    outcome.request.checkId == checkB
                ) {
                    bOutcomeAtSink.countDown()
                    if (!releaseBOutcomeAtSink.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        bOutcomeSinkTimedOut.set(true)
                    }
                }
            }
            val gateAArmed = fixture.service.expectBroadcast {
                it.action == AppRuleBlocker.INTENT_ACTION_TEST_ARM_GUARDIAN_EVALUATION_GATE &&
                    it.getStringExtra(AppRuleBlocker.EXTRA_TEST_GATE_ID) == fixture.gateId
            }
            fixture.armOutcomeGate()
            assertTrue(
                gateAArmed.awaitBroadcast("A worker gate arm")
                    .getBooleanExtra("guardian_test_gate_accepted", false)
            )
            fixture.openScreenAndSubmitCheck()
            val reachedA = gateAReached.awaitBroadcast("A worker gate reach")
            assertTrue(reachedA.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(checkA, reachedA.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID))
            assertTrue(fixture.awaitWorkerOutcome(checkA).evaluation?.isAllowed == true)

            assertTrue(
                "A's timeout did not reach the recording service outside the lock",
                timeoutSendEntered.await(30_000L, TimeUnit.MILLISECONDS)
            )
            assertTimeoutIdentity(
                fixture,
                timeoutAResult.awaitBroadcast("A timeout send entered"),
                checkA
            )
            assertConfirmation(
                fixture,
                checkA,
                GuardianApprovalCoordinator.ConfirmationPhase.TIMED_OUT
            )

            fixture.receiveCheckSynchronously(
                action = GuardianApprovalActivity.INTENT_ACTION_APPROVAL_CHECK_RETRY,
                checkId = checkB
            )
            assertConfirmation(
                fixture,
                checkB,
                GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            )
            val replacementTimer = getField(fixture.blocker, "guardianConfirmationTimeoutJob")
                as kotlinx.coroutines.Job
            assertTrue("B's retry timeout job must be active", replacementTimer.isActive)

            // A and B use the single-slot production gate. Hold B at the real outcome sink after
            // A's gate is released, then install B's gate before allowing handler dispatch.
            fixture.releaseOutcomeGate(fixture.gateId)
            assertTrue(
                "B's actual worker did not reach the outcome sink before production handling",
                bOutcomeAtSink.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "A's stale worker result did not finish after B became current",
                handledByCheck.computeIfAbsent(checkA) { CountDownLatch(1) }
                    .await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertEquals(
                GuardianApprovalEvaluationStatus.COMPLETED,
                fixture.awaitWorkerOutcome(checkB).status
            )
            assertTrue(fixture.awaitWorkerOutcome(checkB).evaluation?.isAllowed == true)
            assertConfirmation(
                fixture,
                checkB,
                GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            )

            val gateBId = fixture.armOutcomeGateWhenAvailable("guardian-host-gate-after-timeout")
            releaseBOutcomeAtSink.countDown()
            assertFalse("B outcome sink synchronization must release promptly", bOutcomeSinkTimedOut.get())

            val reachedB = gateBReached.awaitBroadcast("B worker gate reach")
            assertTrue(reachedB.getBooleanExtra("guardian_test_gate_accepted", false))
            assertEquals(checkB, reachedB.getStringExtra(AppRuleBlocker.EXTRA_TEST_CHECK_ID))

            releaseTimeoutSend.countDown()
            assertTrue(
                "A's delayed timeout send did not complete after B became current",
                timeoutASendCompleted.await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            val lateTimeoutA = fixture.confirmationResults().single {
                it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) == checkA &&
                    it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) ==
                    GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
            }
            assertTimeoutIdentity(fixture, lateTimeoutA, checkA)
            assertConfirmation(
                fixture,
                checkB,
                GuardianApprovalCoordinator.ConfirmationPhase.CHECKING
            )

            assertEquals(fixture.operationId, fixture.awaitWorkerOutcome(checkB).request.operationId)
            val timeoutResult = timeoutBResult.awaitBroadcast(
                "B timeout after late A delivery",
                timeoutMs = 25_000L
            )
            assertTimeoutIdentity(fixture, timeoutResult, checkB)
            fixture.releaseOutcomeGate(gateBId)
            assertTrue(
                "B's held worker result did not finish behind stale fencing",
                handledByCheck.computeIfAbsent(checkB) { CountDownLatch(1) }
                    .await(GUARDIAN_WORKER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertConfirmation(
                fixture,
                checkB,
                GuardianApprovalCoordinator.ConfirmationPhase.TIMED_OUT
            )
            assertEquals(
                listOf(
                    checkA to GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT,
                    checkB to GuardianApprovalActivity.CONFIRMATION_STATUS_TIMEOUT
                ),
                fixture.confirmationResults()
                    .map { it.getStringExtra(GuardianApprovalActivity.EXTRA_CHECK_ID) to
                        it.getStringExtra(GuardianApprovalActivity.EXTRA_CONFIRMATION_STATUS) }
            )
        } finally {
            releaseTimeoutSend.countDown()
            releaseBOutcomeAtSink.countDown()
            try {
                fixture.blocker.decisionOutcomeSinkObserver = originalOutcomeSinkObserver
                setField(fixture.blocker, "guardianOutcomeHandlerFinishedObserverForTest", null)
            } finally {
                fixture.service.sendBroadcastObserver = null
                fixture.service.sendBroadcastCompletionObserver = null
                fixture.close()
            }
        }
    }
}
