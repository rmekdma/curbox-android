package neth.iecal.curbox.domain.apprules

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt
import neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedDecisionWorkerTest {
    @Test
    fun guardianApprovalCheckSettlesRealSessionAndReturnsCurrentTargetDecision() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val wallNow = 2_000L
        val useDayId = ConfigurableUseDayCalculator().idAt(wallNow)
        val receipt = GuardianApprovalGrantReceipt(
            ruleId = "usage",
            useDayId = useDayId,
            grantedAtMs = 1_500L,
            grantedMillis = 15 * 60_000L
        )
        val worker = worker(
            repository = repository,
            sink = outcomes,
            acceptedRuntime = approvalRuntime(
                revision = RuntimeRevision(5L),
                useDayId = useDayId,
                receipt = GuardianApprovalWorkReceipt.Grant(
                    receipt,
                    GuardianApprovalGrantOrigin.DIRECT,
                    0L
                )
            )
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue("the initial foreground decision did not finish", outcomes.awaitCount(1))
            assertEquals(1, repository.persistedSessions().size)
            assertEquals(1, repository.launchEvents.size)

            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    GuardianApprovalEvaluationRequest(
                        sourceOrderIdentity = SourceOrderIdentity(2L),
                        lifecycleGeneration = LifecycleGeneration(1L),
                        screenRequestId = "screen-1",
                        operationId = "grant-1",
                        checkId = "check-1",
                        packageName = TARGET_PACKAGE,
                        approvalReceipt = GuardianApprovalWorkReceipt.Grant(
                            receipt,
                            GuardianApprovalGrantOrigin.DIRECT,
                            0L
                        ),
                        capturedAtWallMs = wallNow,
                        capturedAtElapsedMs = wallNow
                    )
                )
            )
            assertTrue(
                "the explicit guardian check did not finish",
                outcomes.awaitGuardianApprovalCount(1)
            )

            val result = outcomes.guardianApprovalEvaluations.single()
            assertEquals(GuardianApprovalEvaluationStatus.COMPLETED, result.status)
            assertEquals(GuardianApprovalConfirmationState.REFLECTED, result.confirmationState)
            assertEquals(listOf("night"), result.evaluation?.denyingRules?.map { it.ruleId })
            assertEquals(RuntimeRevision(5L), result.acceptedRuntimeRevision)
            assertEquals(wallNow, repository.persistedSessions().single().endedAtMs)
            assertEquals("an explicit check must not renew visibility or record another launch", 1, repository.launchEvents.size)
            assertEquals("an explicit check must not masquerade as a foreground event", 1, outcomes.evaluations.size)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalPersistenceFailurePublishesNoEvaluationFreshnessEvidence() {
        val repository = CheckpointFailingRepository()
        val outcomes = RecordingOutcomeSink()
        val wallNow = 2_000L
        val useDayId = ConfigurableUseDayCalculator().idAt(wallNow)
        val receipt = GuardianApprovalWorkReceipt.Grant(
            GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = useDayId,
                grantedAtMs = 1_500L,
                grantedMillis = 15 * 60_000L
            ),
            GuardianApprovalGrantOrigin.DIRECT,
            0L
        )
        val worker = worker(
            repository = repository,
            sink = outcomes,
            acceptedRuntime = approvalRuntime(
                revision = RuntimeRevision(7L),
                useDayId = useDayId,
                receipt = receipt
            )
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = wallNow))
            )
            assertTrue("the foreground session did not start", outcomes.awaitCount(1))
            repository.failNextCheckpoint = true

            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    guardianApprovalRequest(
                        sourceOrder = 2L,
                        operationId = "failed-reconcile",
                        checkId = "failed-reconcile-check",
                        receipt = receipt,
                        capturedAtMs = wallNow + 1_000L
                    )
                )
            )
            assertTrue("the failed guardian evaluation did not finish", outcomes.awaitGuardianApprovalCount(1))

            val result = outcomes.guardianApprovalEvaluations.single()
            assertEquals(GuardianApprovalEvaluationStatus.FAILED, result.status)
            assertEquals(GuardianApprovalConfirmationState.UNCONFIRMED, result.confirmationState)
            assertTrue("a failed result must not carry an evaluation", result.evaluation == null)
            assertEquals("", result.evaluationZoneId)
            assertEquals(Long.MAX_VALUE, result.validUntilWallClockMs)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalAllowedResultExpiresAtTheNextScheduledRuleBoundary() {
        val zone = ZoneId.systemDefault()
        val capturedAt = ZonedDateTime.now(zone)
            .withHour(10)
            .withMinute(30)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
        val calculator = ConfigurableUseDayCalculator(zone = zone)
        val useDayId = calculator.idAt(capturedAt)
        val receipt = GuardianApprovalWorkReceipt.Grant(
            GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = useDayId,
                grantedAtMs = capturedAt - 1_000L,
                grantedMillis = 2 * 60 * 60_000L
            ),
            GuardianApprovalGrantOrigin.DIRECT,
            0L
        )
        val acceptedRuntime = approvalRuntime(
            revision = RuntimeRevision(81L),
            useDayId = useDayId,
            receipt = receipt,
            nightAllowedMinutes = 1_440L
        ).let { accepted ->
            val scheduledUsageRule = accepted.runtime.snapshot.appRules
                .first { it.id == "usage" }
                .copy(timeRanges = listOf(AppRuleTimeRange(11 * 60, 12 * 60)))
            accepted.copy(
                runtime = accepted.runtime.copy(
                    snapshot = accepted.runtime.snapshot.copy(
                        appRules = accepted.runtime.snapshot.appRules.map { rule ->
                            if (rule.id == scheduledUsageRule.id) scheduledUsageRule else rule
                        }
                    ),
                    resetTime = UseDayResetTime(hour = 4, minute = 0)
                )
            )
        }
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            acceptedRuntime = acceptedRuntime
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    guardianApprovalRequest(
                        sourceOrder = 81L,
                        operationId = "scheduled-boundary",
                        checkId = "scheduled-boundary-check",
                        receipt = receipt,
                        capturedAtMs = capturedAt
                    )
                )
            )
            assertTrue(outcomes.awaitGuardianApprovalCount(1))
            val result = outcomes.guardianApprovalEvaluations.single()
            assertTrue("the grant keeps the current policy allowed", result.evaluation?.isAllowed == true)
            assertEquals("the offer carries the zone used by the evaluation", zone.id, result.evaluationZoneId)
            assertEquals(
                "an all-allow result must expire when its scheduled restriction starts",
                capturedAt + 30 * 60_000L,
                result.validUntilWallClockMs
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalDeniedResultExpiresAtTheNextScheduledRuleBoundary() {
        val zone = ZoneId.systemDefault()
        val capturedAt = ZonedDateTime.now(zone)
            .withHour(10)
            .withMinute(30)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
        val calculator = ConfigurableUseDayCalculator(zone)
        val useDayId = calculator.idAt(capturedAt)
        val receipt = GuardianApprovalWorkReceipt.Grant(
            GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = useDayId,
                grantedAtMs = capturedAt - 1_000L,
                grantedMillis = 2 * 60 * 60_000L
            ),
            GuardianApprovalGrantOrigin.DIRECT,
            0L
        )
        val accepted = approvalRuntime(
            revision = RuntimeRevision(84L),
            useDayId = useDayId,
            receipt = receipt
        )
        val scheduledDenial = accepted.runtime.snapshot.appRules
            .first { it.id == "night" }
            .copy(timeRanges = listOf(AppRuleTimeRange(10 * 60, 11 * 60)))
        val acceptedRuntime = accepted.copy(
            runtime = accepted.runtime.copy(
                snapshot = accepted.runtime.snapshot.copy(
                    appRules = accepted.runtime.snapshot.appRules.map { rule ->
                        if (rule.id == scheduledDenial.id) scheduledDenial else rule
                    }
                )
            )
        )
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            acceptedRuntime = acceptedRuntime
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    guardianApprovalRequest(
                        sourceOrder = 84L,
                        operationId = "scheduled-denial-boundary",
                        checkId = "scheduled-denial-boundary-check",
                        receipt = receipt,
                        capturedAtMs = capturedAt
                    )
                )
            )
            assertTrue(outcomes.awaitGuardianApprovalCount(1))
            val result = outcomes.guardianApprovalEvaluations.single()
            assertFalse("the scheduled rule must deny during its active window", result.evaluation?.isAllowed ?: true)
            assertEquals(listOf("night"), result.evaluation?.denyingRules?.map { it.ruleId })
            assertEquals(zone.id, result.evaluationZoneId)

            val boundary = capturedAt + 30 * 60_000L
            assertEquals(
                "a denied result must expire when its scheduled restriction ends",
                boundary,
                result.validUntilWallClockMs
            )
            assertTrue(
                "the denied result stays current before the boundary",
                GuardianApprovalEvaluationWindow.isCurrent(
                    evaluationZoneId = result.evaluationZoneId,
                    currentZoneId = zone.id,
                    capturedAtWallClockMs = capturedAt,
                    capturedAtElapsedRealtimeMs = capturedAt,
                    validUntilWallClockMs = result.validUntilWallClockMs,
                    nowWallClockMs = boundary - 1L,
                    nowElapsedRealtimeMs = capturedAt + (boundary - capturedAt) - 1L
                )
            )
            assertFalse(
                "the denied result must be rejected once that boundary passes",
                GuardianApprovalEvaluationWindow.isCurrent(
                    evaluationZoneId = result.evaluationZoneId,
                    currentZoneId = zone.id,
                    capturedAtWallClockMs = capturedAt,
                    capturedAtElapsedRealtimeMs = capturedAt,
                    validUntilWallClockMs = result.validUntilWallClockMs,
                    nowWallClockMs = boundary,
                    nowElapsedRealtimeMs = capturedAt + (boundary - capturedAt)
                )
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalAllowedResultExpiresAtTheNextUseDayReset() {
        val zone = ZoneId.systemDefault()
        val capturedAt = ZonedDateTime.now(zone)
            .withHour(10)
            .withMinute(30)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
        val resetTime = UseDayResetTime(hour = 11, minute = 0)
        val calculator = ConfigurableUseDayCalculator(zone, resetTime)
        val useDayId = calculator.idAt(capturedAt)
        val receipt = GuardianApprovalWorkReceipt.Grant(
            GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = useDayId,
                grantedAtMs = capturedAt - 1_000L,
                grantedMillis = 2 * 60 * 60_000L
            ),
            GuardianApprovalGrantOrigin.DIRECT,
            0L
        )
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            acceptedRuntime = approvalRuntime(
                revision = RuntimeRevision(82L),
                useDayId = useDayId,
                receipt = receipt,
                nightAllowedMinutes = 1_440L,
                resetTime = resetTime
            )
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    guardianApprovalRequest(
                        sourceOrder = 82L,
                        operationId = "reset-boundary",
                        checkId = "reset-boundary-check",
                        receipt = receipt,
                        capturedAtMs = capturedAt
                    )
                )
            )
            assertTrue(outcomes.awaitGuardianApprovalCount(1))
            val result = outcomes.guardianApprovalEvaluations.single()
            assertTrue("the current grant keeps evaluation allowed", result.evaluation?.isAllowed == true)
            assertEquals("the offer carries the zone used by the evaluation", zone.id, result.evaluationZoneId)
            assertEquals(
                "an allowed result must expire before a use-day reset supersedes its receipt",
                capturedAt + 30 * 60_000L,
                result.validUntilWallClockMs
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalSkipResultExpiresAtItsExistingSkipBoundary() {
        val zone = ZoneId.systemDefault()
        val capturedAt = ZonedDateTime.now(zone)
            .withHour(10)
            .withMinute(30)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
        val useDayId = ConfigurableUseDayCalculator(zone).idAt(capturedAt)
        val skipUntilMs = capturedAt + 15 * 60_000L
        val receipt = GuardianApprovalWorkReceipt.RuleSkip(
            ruleId = "usage",
            useDayId = useDayId,
            skipFromMs = capturedAt - 1_000L,
            skipUntilMs = skipUntilMs,
            useDayGenerationStartedAtMs = 0L
        )
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            acceptedRuntime = approvalRuntime(
                revision = RuntimeRevision(83L),
                useDayId = useDayId,
                receipt = receipt,
                nightAllowedMinutes = 1_440L
            )
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    guardianApprovalRequest(
                        sourceOrder = 83L,
                        operationId = "skip-boundary",
                        checkId = "skip-boundary-check",
                        receipt = receipt,
                        capturedAtMs = capturedAt
                    )
                )
            )
            assertTrue(outcomes.awaitGuardianApprovalCount(1))
            val result = outcomes.guardianApprovalEvaluations.single()
            assertTrue("the current skip keeps evaluation allowed", result.evaluation?.isAllowed == true)
            assertEquals("the offer carries the zone used by the evaluation", zone.id, result.evaluationZoneId)
            assertEquals(
                "an allowed result must expire when the stored skip ends",
                skipUntilMs,
                result.validUntilWallClockMs
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalCheckKeepsMissingReceiptUnconfirmedWhileRuleIsStillValid() {
        val outcomes = RecordingOutcomeSink()
        val wallNow = 2_000L
        val useDayId = ConfigurableUseDayCalculator().idAt(wallNow)
        val receipt = GuardianApprovalGrantReceipt(
            ruleId = "usage",
            useDayId = useDayId,
            grantedAtMs = 1_500L,
            grantedMillis = 15 * 60_000L
        )
        val worker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            acceptedRuntime = approvalRuntime(
                revision = RuntimeRevision(6L),
                useDayId = useDayId,
                receipt = null
            )
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    GuardianApprovalEvaluationRequest(
                        sourceOrderIdentity = SourceOrderIdentity(1L),
                        lifecycleGeneration = LifecycleGeneration(1L),
                        screenRequestId = "screen-2",
                        operationId = "grant-2",
                        checkId = "check-2",
                        packageName = TARGET_PACKAGE,
                        approvalReceipt = GuardianApprovalWorkReceipt.Grant(
                            receipt,
                            GuardianApprovalGrantOrigin.DIRECT,
                            0L
                        ),
                        capturedAtWallMs = wallNow,
                        capturedAtElapsedMs = wallNow
                    )
                )
            )
            assertTrue(outcomes.awaitGuardianApprovalCount(1))
            val result = outcomes.guardianApprovalEvaluations.single()
            assertEquals(GuardianApprovalEvaluationStatus.COMPLETED, result.status)
            assertEquals(GuardianApprovalConfirmationState.UNCONFIRMED, result.confirmationState)
            assertEquals(listOf("usage", "night"), result.evaluation?.denyingRules?.map { it.ruleId })
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun guardianApprovalCheckTreatsAChangedUseDayOrRevokedRuleAsSuperseded() {
        val wallNow = 2_000_000_000L
        val originalUseDayId = ConfigurableUseDayCalculator().idAt(wallNow)
        val receipt = GuardianApprovalWorkReceipt.Grant(
            GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = originalUseDayId,
                grantedAtMs = wallNow - 100L,
                grantedMillis = 15 * 60_000L
            ),
            GuardianApprovalGrantOrigin.DIRECT,
            7L
        )
        val base = approvalRuntime(
            revision = RuntimeRevision(40L),
            useDayId = originalUseDayId,
            receipt = null,
            useDayGenerationStartedAtMs = 7L
        )
        val usageRule = base.runtime.snapshot.appRules.first { it.id == "usage" }
        val nightRule = base.runtime.snapshot.appRules.first { it.id == "night" }
        val changedUseDayId = ConfigurableUseDayCalculator().idAt(wallNow + 86_400_000L)
        val supersedingRuntimes = listOf(
            base.copy(
                runtime = base.runtime.copy(
                    useDayGenerationStartedAtMs = 8L,
                    overrideState = base.runtime.overrideState.copy(
                        useDayId = changedUseDayId,
                        useDayGenerationStartedAtMs = 8L
                    )
                )
            ),
            base.copy(
                runtime = base.runtime.copy(
                    snapshot = base.runtime.snapshot.copy(appRules = listOf(nightRule))
                )
            ),
            base.copy(
                runtime = base.runtime.copy(
                    snapshot = base.runtime.snapshot.copy(
                        appRules = listOf(usageRule.copy(guardianExtraTimeAllowed = false), nightRule)
                    )
                )
            ),
            base.copy(
                runtime = base.runtime.copy(
                    snapshot = base.runtime.snapshot.copy(appRules = emptyList())
                )
            )
        )

        supersedingRuntimes.forEachIndexed { index, acceptedRuntime ->
            val outcomes = RecordingOutcomeSink()
            val worker = worker(
                repository = RecordingRepository(),
                sink = outcomes,
                acceptedRuntime = acceptedRuntime.copy(
                    runtimeRevision = RuntimeRevision(41L + index)
                )
            )
            try {
                assertEquals(
                    SubmissionResult.ACCEPTED,
                    worker.submitGuardianApprovalEvaluation(
                        guardianApprovalRequest(
                            sourceOrder = 41L + index,
                            operationId = "superseded-$index",
                            checkId = "check-$index",
                            receipt = receipt,
                            capturedAtMs = wallNow + if (index == 0) 86_400_000L else 0L
                        )
                    )
                )
                assertTrue(outcomes.awaitGuardianApprovalCount(1))
                val result = outcomes.guardianApprovalEvaluations.single()
                assertEquals(GuardianApprovalEvaluationStatus.COMPLETED, result.status)
                assertEquals(GuardianApprovalConfirmationState.SUPERSEDED, result.confirmationState)
                if (index == 1) {
                    assertEquals(listOf("night"), result.evaluation?.denyingRules?.map { it.ruleId })
                }
                if (index == 3) {
                    assertTrue("current rules all allow after the approved rule was removed", result.evaluation?.isAllowed == true)
                }
            } finally {
                worker.stop(recoveryStop(LifecycleGeneration(1L)))
            }
        }
    }

    @Test
    fun guardianApprovalCheckUsesLatestSettingsAfterDelayedOlderPublication() {
        val outcomes = RecordingOutcomeSink()
        val wallNow = 2_000_000_000L
        val useDayId = ConfigurableUseDayCalculator().idAt(wallNow)
        val receipt = GuardianApprovalWorkReceipt.Grant(
            grant = GuardianApprovalGrantReceipt(
                ruleId = "usage",
                useDayId = useDayId,
                grantedAtMs = wallNow - 100L,
                grantedMillis = 15 * 60_000L
            ),
            origin = GuardianApprovalGrantOrigin.DIRECT,
            useDayGenerationStartedAtMs = 7L
        )
        val current = approvalRuntime(
            revision = RuntimeRevision(42L),
            useDayId = useDayId,
            receipt = null,
            useDayGenerationStartedAtMs = 7L
        ).let { accepted ->
            val currentRules = accepted.runtime.snapshot.appRules.map {
                if (it.id == "usage") it.copy(guardianExtraTimeAllowed = false) else it
            }
            accepted.copy(
                runtime = accepted.runtime.copy(
                    snapshot = accepted.runtime.snapshot.copy(appRules = currentRules)
                )
            )
        }
        val worker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            acceptedRuntime = current
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    request(
                        sourceOrder = 41L,
                        lifecycle = 1L,
                        packageName = TARGET_PACKAGE,
                        runtimePublication = RuntimePublication(
                            runtimeRevision = RuntimeRevision(41L),
                            candidateRuntime = approvalRuntime(
                                revision = RuntimeRevision(41L),
                                useDayId = useDayId,
                                receipt = receipt,
                                useDayGenerationStartedAtMs = 7L
                            ).runtime
                        )
                    )
                )
            )
            assertTrue("delayed old settings publication did not drain", outcomes.awaitIdle())

            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitGuardianApprovalEvaluation(
                    guardianApprovalRequest(
                        sourceOrder = 43L,
                        operationId = "latest-settings",
                        checkId = "check-latest-settings",
                        receipt = receipt,
                        capturedAtMs = wallNow
                    )
                )
            )
            assertTrue(outcomes.awaitGuardianApprovalCount(1))
            val result = outcomes.guardianApprovalEvaluations.single()
            assertEquals(RuntimeRevision(42L), result.acceptedRuntimeRevision)
            assertEquals(GuardianApprovalConfirmationState.SUPERSEDED, result.confirmationState)
            assertEquals(listOf("usage", "night"), result.evaluation?.denyingRules?.map { it.ruleId })
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun accumulatedGrantAndSkipConfirmTheirOwnEffectsAgainstTheLatestTargetEvaluation() {
        val wallNow = 2_000L
        val useDayId = ConfigurableUseDayCalculator().idAt(wallNow)
        val receipts = listOf(
            GuardianApprovalWorkReceipt.Grant(
                grant = GuardianApprovalGrantReceipt(
                    ruleId = "usage",
                    useDayId = useDayId,
                    grantedAtMs = 1_500L,
                    grantedMillis = 15 * 60_000L
                ),
                origin = GuardianApprovalGrantOrigin.ACCUMULATED_POOL,
                useDayGenerationStartedAtMs = 7L
            ),
            GuardianApprovalWorkReceipt.RuleSkip(
                ruleId = "usage",
                useDayId = useDayId,
                skipFromMs = 1_500L,
                skipUntilMs = 5_000L,
                useDayGenerationStartedAtMs = 7L
            )
        )

        receipts.forEachIndexed { index, receipt ->
            val deniedOutcomes = RecordingOutcomeSink()
            val deniedWorker = worker(
                repository = RecordingRepository(),
                sink = deniedOutcomes,
                acceptedRuntime = approvalRuntime(
                    revision = RuntimeRevision(20L + index),
                    useDayId = useDayId,
                    receipt = receipt,
                    useDayGenerationStartedAtMs = 7L
                )
            )
            try {
                assertEquals(
                    SubmissionResult.ACCEPTED,
                    deniedWorker.submitGuardianApprovalEvaluation(
                        guardianApprovalRequest(
                            sourceOrder = 20L + index,
                            operationId = "approval-$index",
                            checkId = "check-denied-$index",
                            receipt = receipt,
                            capturedAtMs = wallNow
                        )
                    )
                )
                assertTrue(deniedOutcomes.awaitGuardianApprovalCount(1))
                val result = deniedOutcomes.guardianApprovalEvaluations.single()
                assertEquals(GuardianApprovalEvaluationStatus.COMPLETED, result.status)
                assertEquals(GuardianApprovalConfirmationState.REFLECTED, result.confirmationState)
                assertEquals(listOf("night"), result.evaluation?.denyingRules?.map { it.ruleId })
                assertTrue(
                    "the target rule's accumulated grant or skip must take effect",
                    result.evaluation?.evaluations?.single { it.ruleId == "usage" }?.isAllowed == true
                )
            } finally {
                deniedWorker.stop(recoveryStop(LifecycleGeneration(1L)))
            }

            val allowedOutcomes = RecordingOutcomeSink()
            val allowedWorker = worker(
                repository = RecordingRepository(),
                sink = allowedOutcomes,
                acceptedRuntime = approvalRuntime(
                    revision = RuntimeRevision(30L + index),
                    useDayId = useDayId,
                    receipt = receipt,
                    useDayGenerationStartedAtMs = 7L,
                    nightAllowedMinutes = 1_440L
                )
            )
            try {
                assertEquals(
                    SubmissionResult.ACCEPTED,
                    allowedWorker.submitGuardianApprovalEvaluation(
                        guardianApprovalRequest(
                            sourceOrder = 30L + index,
                            operationId = "approval-$index",
                            checkId = "check-allowed-$index",
                            receipt = receipt,
                            capturedAtMs = wallNow
                        )
                    )
                )
                assertTrue(allowedOutcomes.awaitGuardianApprovalCount(1))
                val result = allowedOutcomes.guardianApprovalEvaluations.single()
                assertEquals(GuardianApprovalConfirmationState.REFLECTED, result.confirmationState)
                assertTrue("every applicable rule is allowed", result.evaluation?.isAllowed == true)
            } finally {
                allowedWorker.stop(recoveryStop(LifecycleGeneration(1L)))
            }
        }
    }

    @Test
    fun deadlineStopTimesOutFinalDecisionAndQueuedRefreshForRecoveryOnly() {
        val repository = BlockingReadRepository()
        val outcomes = RecordingOutcomeSink()
        val clock = AtomicLong(0L)
        val worker = worker(
            repository = repository,
            sink = outcomes,
            elapsedRealtimeMs = { clock.get() }
        )
        val stopper = AtomicReference<DrainResult.DeadlineDrain>()
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE))
            )
            assertTrue(repository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(refreshRequest(2L, 1L))
            )

            val stopThread = Thread {
                stopper.set(
                    worker.stop(
                        DeadlineDrainStop(
                            requestedAtElapsedMs = 0L,
                            deadline = TotalDrainDeadline(100L),
                            reason = StopReason.DESTROY,
                            lifecycleGeneration = LifecycleGeneration(1L)
                        )
                    )
                )
            }
            stopThread.start()
            clock.set(100L)
            stopThread.join(WAIT_TIMEOUT_MS)

            val result = stopper.get()
            assertTrue("deadline stop did not return", result != null)
            assertFalse(result.completed)
            assertTrue(result.timedOut)
            assertTrue(result.remainingWork)
            assertTrue(result.durableRecoveryRequired)
            assertTrue("stale work published an outcome", outcomes.values.isEmpty())

            // Cancellation cleanup is allowed to finish after the stop result, but it must not
            // rewrite the durable timeout decision captured at the deadline.
            repository.releaseRead()
            val cleanupDeadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (worker.drainSnapshot().hasWork && System.nanoTime() < cleanupDeadline) {
                Thread.yield()
            }
            assertFalse("cancellation cleanup did not finish", worker.drainSnapshot().hasWork)
            assertTrue(result.remainingWork)
            assertTrue(result.durableRecoveryRequired)
        } finally {
            repository.releaseRead()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun deadlineStopDrainsFinalDecisionAndQueuedRefreshBeforeAbsoluteDeadline() {
        val repository = BlockingReadRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L }
        )
        val stopper = AtomicReference<DrainResult.DeadlineDrain>()
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE))
            )
            assertTrue(repository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(refreshRequest(2L, 1L))
            )

            val stopThread = Thread {
                val requestedAt = System.nanoTime() / 1_000_000L
                stopper.set(
                    worker.stop(
                        DeadlineDrainStop(
                            requestedAtElapsedMs = requestedAt,
                            deadline = TotalDrainDeadline(requestedAt + WAIT_TIMEOUT_MS),
                            reason = StopReason.DESTROY,
                            lifecycleGeneration = LifecycleGeneration(1L)
                        )
                    )
                )
            }
            stopThread.start()
            val invalidationDeadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (worker.isReadyForSubmission() && System.nanoTime() < invalidationDeadline) {
                Thread.yield()
            }
            assertFalse("deadline stop did not invalidate the worker", worker.isReadyForSubmission())
            repository.releaseRead()
            stopThread.join(WAIT_TIMEOUT_MS)

            val result = stopper.get()
            assertTrue("deadline stop did not return", result != null)
            assertTrue(result.completed)
            assertFalse(result.timedOut)
            assertFalse(result.remainingWork)
            assertFalse(result.durableRecoveryRequired)
            assertTrue("stale refresh published an outcome", outcomes.values.isEmpty())
        } finally {
            repository.releaseRead()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun repeatedGenerationReplacementPublishesOnlyTheLatestWorkerGeneration() {
        val outcomes = RecordingOutcomeSink()
        val oldRepository = BlockingReadRepository()
        val oldWorker = worker(
            repository = oldRepository,
            sink = outcomes,
            lifecycleGeneration = LifecycleGeneration(1L)
        )
        val currentWorker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            lifecycleGeneration = LifecycleGeneration(2L)
        )
        val newestWorker = worker(
            repository = RecordingRepository(),
            sink = outcomes,
            lifecycleGeneration = LifecycleGeneration(3L)
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                oldWorker.submit(request(1L, 1L, TARGET_PACKAGE))
            )
            assertTrue(oldRepository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            oldWorker.stop(
                RecoveryOnlyStop(
                    requestedAtElapsedMs = 1_000L,
                    reason = StopReason.RECONNECT,
                    lifecycleGeneration = LifecycleGeneration(2L)
                )
            )
            oldRepository.releaseRead()

            assertEquals(
                SubmissionResult.ACCEPTED,
                currentWorker.submit(request(2L, 2L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            )
            assertTrue(outcomes.awaitCount(1))
            currentWorker.stop(
                RecoveryOnlyStop(
                    requestedAtElapsedMs = 2_000L,
                    reason = StopReason.RECONNECT,
                    lifecycleGeneration = LifecycleGeneration(3L)
                )
            )

            assertEquals(
                SubmissionResult.ACCEPTED,
                newestWorker.submit(request(3L, 3L, TARGET_PACKAGE, capturedAtMs = 3_000L))
            )
            assertTrue(outcomes.awaitCount(2))
            assertEquals(
                listOf(LifecycleGeneration(2L), LifecycleGeneration(3L)),
                outcomes.enforcementOutcomes.map { it.lifecycleGeneration }
            )
        } finally {
            oldRepository.releaseRead()
            oldWorker.stop(recoveryStop(LifecycleGeneration(2L)))
            currentWorker.stop(recoveryStop(LifecycleGeneration(3L)))
            newestWorker.stop(recoveryStop(LifecycleGeneration(3L)))
        }
    }

    @Test
    fun sameLifecycleReplacementDropsOldWorkerAfterItsFinalCheck() {
        val oldRepository = RecordingRepository()
        val published = Collections.synchronizedList(mutableListOf<Long>())
        val activeToken = AtomicLong(11L)
        val oldPublicationEntered = CountDownLatch(1)
        val releaseOldPublication = CountDownLatch(1)
        fun taggedSink(token: Long) = object : DecisionOutcomeSink {
            override fun publish(outcome: DecisionOutcome) {
                if (outcome !is DecisionOutcome.EnforcementOutcome) return
                if (token == 11L) {
                    // The worker has passed its final freshness check and entered the host
                    // publication boundary. Replace it before allowing the old publication to
                    // continue, which is the same-lifecycle race a generation cannot identify.
                    oldPublicationEntered.countDown()
                    releaseOldPublication.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                if (token == activeToken.get()) {
                    published += token
                }
            }
        }
        val oldWorker = worker(
            repository = oldRepository,
            sink = taggedSink(11L),
            lifecycleGeneration = LifecycleGeneration(1L)
        )
        val replacementWorker = worker(
            repository = RecordingRepository(),
            sink = taggedSink(12L),
            lifecycleGeneration = LifecycleGeneration(1L)
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                oldWorker.submit(request(1L, 1L, TARGET_PACKAGE))
            )
            assertTrue(oldPublicationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            activeToken.set(12L)
            oldWorker.stop(
                RecoveryOnlyStop(
                    requestedAtElapsedMs = 1_000L,
                    reason = StopReason.REPLACEMENT,
                    lifecycleGeneration = LifecycleGeneration(1L)
                )
            )
            assertEquals(
                SubmissionResult.ACCEPTED,
                replacementWorker.submit(request(2L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            )
            val replacementDeadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (published.none { it == 12L } &&
                System.nanoTime() < replacementDeadline
            ) {
                Thread.yield()
            }
            assertTrue(
                "replacement did not publish its outcome",
                published.any { it == 12L }
            )

            releaseOldPublication.countDown()
            val oldDrainDeadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (oldWorker.drainSnapshot().hasWork && System.nanoTime() < oldDrainDeadline) {
                Thread.yield()
            }
            assertTrue(
                "old worker publication did not finish",
                oldWorker.drainSnapshot().hasWork.not()
            )
            assertEquals(
                listOf(12L),
                published
            )
        } finally {
            releaseOldPublication.countDown()
            oldWorker.stop(recoveryStop(LifecycleGeneration(1L)))
            replacementWorker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun submitReturnsWhileBackgroundPersistenceIsBlocked() {
        val repository = DelayedRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            val startedAt = System.nanoTime()
            val result = worker.submit(request(1L, 1L, TARGET_PACKAGE))
            val callbackElapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertEquals(SubmissionResult.ACCEPTED, result)
            assertTrue(repository.startStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue("submit waited for persistence: ${callbackElapsedMs}ms", callbackElapsedMs < 100L)
            assertFalse(repository.released)
        } finally {
            repository.release()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun evaluatorFailureIsLoggedContainedAndNextForegroundDecisionIsProcessed() {
        val repository = RecordingRepository()
        repository.evaluatorFailure = IllegalStateException("injected evaluator failure")
        val outcomes = RecordingOutcomeSink()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = worker(
            repository = repository,
            sink = outcomes,
            onNonFatalError = { errors += it }
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitIdle())
            assertTrue(outcomes.values.isEmpty())
            assertEquals(1, errors.size)

            repository.evaluatorFailure = null
            worker.submit(request(2L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(1))
            assertFalse(outcomes.enforcementOutcomes.single().packageDecisions.single().isAllowed)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun recoverableFailureWithoutApplicableRulePreservesEligibilityWithoutDenial() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, OTHER_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue(outcomes.awaitCount(1))

            repository.evaluatorFailure = IllegalStateException("injected evaluator failure")
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    syntheticStaleRequest(
                        sourceOrder = 2L,
                        lifecycle = 1L,
                        capturedAtMs = 7_000L
                    )
                )
            )
            assertTrue(outcomes.awaitCount(2))
            assertTrue(outcomes.awaitEvaluationCount(2))

            val evaluation = outcomes.evaluations.last().evaluation
            assertTrue("an inapplicable package must remain allowed", evaluation.isAllowed)
            assertTrue(evaluation.denyingRules.isEmpty())
            assertTrue(evaluation.evaluations.isEmpty())
            val decision = outcomes.enforcementOutcomes.last().packageDecisions.single()
            assertTrue("an inapplicable package must not be denied", decision.isAllowed)
            assertTrue("an inapplicable package must not carry denial ids", decision.denyingRuleIds.isEmpty())
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun persistenceFailureIsLoggedContainedAndNextForegroundDecisionIsProcessed() {
        val repository = RecordingRepository()
        repository.startFailure = IllegalStateException("injected persistence failure")
        val outcomes = RecordingOutcomeSink()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = worker(
            repository = repository,
            sink = outcomes,
            onNonFatalError = { errors += it }
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitIdle())
            assertTrue(outcomes.values.isEmpty())
            assertEquals(1, errors.size)

            repository.startFailure = null
            worker.submit(request(2L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(1))
            assertFalse(outcomes.enforcementOutcomes.single().packageDecisions.single().isAllowed)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun evaluatorRequestCancellationIsContainedAndNextForegroundDecisionContinues() {
        val repository = RequestCancellingRepository()
        val outcomes = RecordingOutcomeSink()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = worker(
            repository = repository,
            sink = outcomes,
            onNonFatalError = { errors += it }
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue(repository.childCompleted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(repository.evaluatorJob.get()?.isCancelled == true)
            assertTrue(worker.isReadyForSubmission())
            assertTrue(errors.isEmpty())
            assertTrue(outcomes.values.isEmpty())

            repository.cancelNext = false
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(2L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            )
            assertTrue(outcomes.awaitCount(1))
            assertFalse(outcomes.enforcementOutcomes.last().packageDecisions.single().isAllowed)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun validEmptyEvaluationStillPublishesEvaluationAndEvidenceOutcome() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val base = acceptedRuntime(RuntimeRevision(1L))
        val excludedRuntime = base.copy(
            runtime = base.runtime.copy(
                snapshot = base.runtime.snapshot.copy(
                    appRules = listOf(
                        base.runtime.snapshot.appRules.single().copy(
                            scope = AppRuleScope(
                                includeAllApps = true,
                                excludedGroupIds = setOf(OTHER_GROUP_ID)
                            )
                        )
                    )
                )
            )
        )
        val worker = worker(
            repository = repository,
            sink = outcomes,
            acceptedRuntime = excludedRuntime
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, OTHER_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue(outcomes.awaitCount(1))
            assertTrue(outcomes.enforcementOutcomes.single().packageDecisions.single().isAllowed)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun workerScopeCancellationDropsOldGenerationAndFreshWorkerIsRequiredForRecovery() {
        val repository = ScopeCancellingRepository()
        val outcomes = RecordingOutcomeSink()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val scopeJob = SupervisorJob()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            workerScope = CoroutineScope(Dispatchers.Default + scopeJob),
            onNonFatalError = { errors += it }
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue(repository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(2L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            )

            runBlocking { scopeJob.cancelAndJoin() }

            assertTrue(errors.isEmpty())
            assertTrue(outcomes.values.isEmpty())
            assertFalse(worker.isReadyForSubmission())
            assertEquals(
                SubmissionResult.REJECTED_NOT_READY,
                worker.submit(request(3L, 1L, TARGET_PACKAGE, capturedAtMs = 3_000L))
            )

            val recoveredOutcomes = RecordingOutcomeSink()
            val recoveredWorker = worker(
                repository = RecordingRepository(),
                sink = recoveredOutcomes,
                lifecycleGeneration = LifecycleGeneration(2L)
            )
            try {
                assertEquals(
                    SubmissionResult.ACCEPTED,
                    recoveredWorker.submit(
                        request(1L, 2L, TARGET_PACKAGE, capturedAtMs = 4_000L)
                    )
                )
                assertTrue(recoveredOutcomes.awaitCount(1))
                assertFalse(recoveredOutcomes.enforcementOutcomes.single().packageDecisions.single().isAllowed)
            } finally {
                recoveredWorker.stop(recoveryStop(LifecycleGeneration(2L)))
            }
        } finally {
            scopeJob.cancel()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun cancellationPropagatesToTheWorkerBoundaryWithoutBeingLogged() {
        val evaluationStarted = CountDownLatch(1)
        val workerScopeJob = SupervisorJob()
        val workerScope = CoroutineScope(Dispatchers.IO + workerScopeJob)
        val repository = object : RecordingRepository() {
            override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
                evaluationStarted.countDown()
                workerScopeJob.cancel(CancellationException("injected evaluator cancellation"))
                throw CancellationException("injected evaluator cancellation")
            }
        }
        val outcomes = RecordingOutcomeSink()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = worker(
            repository = repository,
            sink = outcomes,
            onNonFatalError = { errors += it },
            workerScope = workerScope
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(evaluationStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            var result = SubmissionResult.ACCEPTED
            while (System.nanoTime() < deadline && result == SubmissionResult.ACCEPTED) {
                result = worker.submit(request(2L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
                if (result == SubmissionResult.ACCEPTED) Thread.yield()
            }
            assertEquals(SubmissionResult.REJECTED_NOT_READY, result)
            assertTrue(errors.isEmpty())
            assertTrue(outcomes.values.isEmpty())
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun lifecycleChangeDuringEvaluationPreventsStaleEvaluationPublication() {
        val repository = RecordingRepository()
        repository.evaluatorFailure = IllegalStateException("injected evaluator failure")
        val outcomes = RecordingOutcomeSink()
        val workerReference = AtomicReference<SerializedDecisionWorker>()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            onNonFatalError = {
                workerReference.get().beginLifecycle(
                    lifecycleGeneration = LifecycleGeneration(2L),
                    acceptedRuntime = acceptedRuntime(RuntimeRevision(2L))
                )
            }
        )
        workerReference.set(worker)
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitIdle())
            assertTrue(outcomes.evaluations.isEmpty())
            assertTrue(outcomes.values.isEmpty())

            repository.evaluatorFailure = null
            worker.submit(request(2L, 2L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(1))
            assertFalse(outcomes.enforcementOutcomes.single().packageDecisions.single().isAllowed)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(2L)))
        }
    }

    @Test
    fun visibleSessionFlushCommitsBeforeEvaluationReadsPersistence() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            )
            assertTrue(outcomes.awaitCount(1))
            repository.operations.clear()

            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(2))

            val commitIndex = repository.operations.indexOf("commit:$TARGET_PACKAGE")
            val evaluateIndex = repository.operations.indexOf("evaluate:$OTHER_PACKAGE")
            assertTrue("missing flush commit: ${repository.operations}", commitIndex >= 0)
            assertTrue("missing evaluator read: ${repository.operations}", evaluateIndex >= 0)
            assertTrue(
                "evaluator read before flush commit: ${repository.operations}",
                commitIndex < evaluateIndex
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun workerOwnsWallClockBoundaryDerivationBeforePublishingOutcome() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 10_000L))
            )
            assertTrue(outcomes.awaitCount(1))

            val update = outcomes.recheckPlans.single()
            assertEquals(SourceOrderIdentity(1L), update.sourceOrderIdentity)
            assertEquals(LifecycleGeneration(1L), update.lifecycleGeneration)
            assertEquals(RuntimeRevision(1L), update.acceptedRuntimeRevision)
            assertEquals(TARGET_PACKAGE, update.packageName)
            assertTrue(update.plan != null)
            assertTrue(update.plan!!.dueAtWallClockMs > 10_000L)
            assertEquals(
                update.plan!!.dueAtWallClockMs - 10_000L,
                update.plan!!.delayMillis
            )
            assertTrue(
                "boundary derivation must follow the evaluator read",
                repository.operations.indexOf("evaluate:$TARGET_PACKAGE") >= 0
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun productionPersistenceWaitsForCommitAndEvaluatorSeesCommittedUsage() {
        val repository = BlockingCommitRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            acceptedRuntime = acceptedRuntime(RuntimeRevision(1L), allowedMinutes = 1L)
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitCount(1))

            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 61_001L))
            assertTrue(repository.commitStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertFalse(
                "evaluator started while the durable commit was blocked",
                repository.secondEvaluationStarted.await(100L, TimeUnit.MILLISECONDS)
            )
            assertEquals("decision published while the durable commit was blocked", 1, outcomes.enforcementOutcomes.size)

            repository.releaseCommit()
            assertTrue(outcomes.awaitCount(2))
            assertTrue(repository.secondEvaluationStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            worker.submit(request(3L, 1L, TARGET_PACKAGE, capturedAtMs = 61_002L))
            assertTrue(outcomes.awaitCount(3))
            val finalDecision = outcomes.enforcementOutcomes.last().packageDecisions.single()
            assertEquals(TARGET_PACKAGE, finalDecision.packageName)
            assertFalse("evaluator did not observe the committed minute", finalDecision.isAllowed)
        } finally {
            repository.releaseCommit()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun statisticsDisabledWorkerPersistsOnlyEnforcementLedger() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            usageTrackingDecision = AppUsageTrackingPolicy.decide(
                statisticsTrackingEnabled = false,
                hasActiveTimeBasedRules = true
            )
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 61_001L))

            assertTrue(outcomes.awaitCount(2))
            val targetSession = repository.persistedSessions()
                .first { it.packageName == TARGET_PACKAGE }
            assertFalse("statistics-disabled session was marked tracked", targetSession.statisticsTracked)
            assertTrue("statistics launch ledger was written while disabled", repository.launchEvents.isEmpty())
            assertTrue(
                "calendar usage checkpoints were written while disabled",
                repository.committedCheckpoints.all { it.usage.isEmpty() }
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun statisticsEnabledWorkerPersistsLaunchesAndHourlyUsageCheckpoints() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            usageTrackingDecision = AppUsageTrackingPolicy.decide(
                statisticsTrackingEnabled = true,
                hasActiveTimeBasedRules = true
            )
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 61_001L))

            assertTrue(outcomes.awaitCount(2))
            val targetSession = repository.persistedSessions()
                .first { it.packageName == TARGET_PACKAGE }
            assertTrue("statistics-enabled session was not marked tracked", targetSession.statisticsTracked)
            assertEquals(2, repository.launchEvents.size)
            assertEquals(2, repository.statisticsLaunches.size)

            val targetCommit = repository.committedCheckpoints
                .first { it.sessionId == targetSession.id }
            assertTrue("statistics checkpoint payload was empty", targetCommit.usage.isNotEmpty())
            assertEquals(
                60_001L,
                targetCommit.usage.sumOf { it.durationMs }
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun usageResetRestartsWorkerOwnedSessionBeforeTheNextCheckpoint() {
        val repository = RecordingRepository()
        val resetRepository = RecordingUsageResetRepository(repository)
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            usageResetRepository = resetRepository
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitCount(1))
            val oldSessionId = repository.persistedSessions()
                .first { it.packageName == TARGET_PACKAGE }
                .id

            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitUsageReset(
                    request = UsageResetRequest(
                        useDayId = "1970-01-01",
                        generationStartedAtMs = 0L,
                        packageNames = setOf(TARGET_PACKAGE),
                        resetAtMs = 2_000L,
                        requestId = "reset-1"
                    ),
                    resetAtElapsedMs = 2_000L
                )
            )
            assertTrue(outcomes.awaitUsageResetCount(1))
            assertTrue("reset did not complete successfully", outcomes.usageResets.single().second)

            val restart = resetRepository.commands.single().restarts.single()
            assertEquals(oldSessionId, restart.activeSessionId)
            assertTrue("explicit reset must count a fresh launch", restart.recordLaunch)
            val restartedSessionId = resetRepository.restartedSessionIds.single().second
            assertTrue(restartedSessionId != oldSessionId)

            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 3_000L))
            assertTrue(outcomes.awaitCount(2))
            assertTrue(
                "next checkpoint used the stale pre-reset session ID",
                repository.committedCheckpoints.any { it.sessionId == restartedSessionId }
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun usageResetFailurePublishesFailedOutcomeAndReportsNonFatal() {
        val repository = RecordingRepository()
        val resetRepository = RecordingUsageResetRepository(repository)
        resetRepository.resetFailure = IllegalStateException("injected reset failure")
        val outcomes = RecordingOutcomeSink()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = worker(
            repository = repository,
            sink = outcomes,
            usageResetRepository = resetRepository,
            onNonFatalError = { errors += it }
        )
        try {
            val request = UsageResetRequest(
                useDayId = "1970-01-01",
                generationStartedAtMs = 0L,
                packageNames = setOf(TARGET_PACKAGE),
                resetAtMs = 2_000L,
                requestId = "reset-fail-1"
            )
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submitUsageReset(request, resetAtElapsedMs = 2_000L)
            )
            assertTrue(outcomes.awaitUsageResetCount(1))
            val (publishedRequest, succeeded) = outcomes.usageResets.single()
            assertEquals(request, publishedRequest)
            assertFalse("failed reset must report succeeded = false", succeeded)
            assertEquals(1, errors.size)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun evaluationOutcomeIsPublishedToDecisionOutcomeSinkWithFidelity() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes
        )
        try {
            val req = request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L)
            assertEquals(SubmissionResult.ACCEPTED, worker.submit(req))
            assertTrue(outcomes.awaitEvaluationCount(1))
            val evalReady = outcomes.evaluations.single()
            assertEquals(req.sourceOrderIdentity, evalReady.request.sourceOrderIdentity)
            assertEquals(LifecycleGeneration(1L), evalReady.request.lifecycleGeneration)
            assertEquals(RuntimeRevision(1L), evalReady.accepted.runtimeRevision)
            assertEquals(TARGET_PACKAGE, evalReady.packageName)
            assertFalse(evalReady.evaluation.isAllowed)
            assertTrue(outcomes.awaitCount(1))
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun statisticsPolicyBoundaryDoesNotCountStillVisibleAppAsAnotherLaunch() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(
            repository = repository,
            sink = outcomes,
            usageTrackingDecision = AppUsageTrackingPolicy.decide(
                statisticsTrackingEnabled = false,
                hasActiveTimeBasedRules = true
            )
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitCount(1))

            worker.submit(
                request(
                    sourceOrder = 2L,
                    lifecycle = 1L,
                    packageName = TARGET_PACKAGE,
                    capturedAtMs = 2_000L,
                    runtimePublication = RuntimePublication(
                        runtimeRevision = RuntimeRevision(2L),
                        candidateRuntime = runtime(
                            usageTrackingDecision = AppUsageTrackingPolicy.decide(
                                statisticsTrackingEnabled = true,
                                hasActiveTimeBasedRules = true
                            )
                        )
                    )
                )
            )
            assertTrue(outcomes.awaitCount(2))

            assertTrue(
                "policy rotation was counted as a current-use-day launch",
                repository.launchEvents.isEmpty()
            )
            assertTrue(
                "policy rotation was counted as a calendar launch",
                repository.statisticsLaunches.isEmpty()
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun useDayGenerationBoundaryRotatesStillVisibleSessionAndEnforcesNewGeneration() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val essentialPackage = "com.android.launcher3"
        val initialRuntime = runtime(allowedMinutes = 1L).copy(
            evidencePolicy = ForegroundEvidencePolicySnapshot(
                essentialPackages = setOf(essentialPackage)
            )
        )
        val worker = worker(
            repository = repository,
            sink = outcomes,
            acceptedRuntime = AcceptedRuleRuntimeSnapshot(
                runtime = initialRuntime,
                runtimeRevision = RuntimeRevision(1L)
            )
        )
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            assertTrue(outcomes.awaitCount(1))
            val oldSession = repository.persistedSessions().single()

            // Essential foreground UI preserves the last app session when its own window is
            // unavailable. A following settings generation still needs to cut that old row.
            worker.submit(
                DecisionRequest(
                    sourceOrderIdentity = SourceOrderIdentity(2L),
                    lifecycleGeneration = LifecycleGeneration(1L),
                    reason = ObservationKind.REAL_EVENT,
                    observation = ForegroundFacts(
                        capturedAtWallMs = 1_500L,
                        capturedAtElapsedMs = 1_500L,
                        signal = SignalFact(
                            kind = ObservationKind.REAL_EVENT,
                            eventPackage = essentialPackage,
                            eventWallMs = 1_500L,
                            eventElapsedMs = 1_500L
                        ),
                        activeRoot = ActiveRootFact(
                            packageName = essentialPackage,
                            readState = ForegroundReadState.AVAILABLE
                        ),
                        applicationWindows = ApplicationWindowsFact(
                            readState = ForegroundReadState.EMPTY
                        ),
                        displayState = DisplayState.UNLOCKED
                    )
                )
            )
            assertTrue(outcomes.awaitCount(2))
            assertEquals(null, repository.persistedSessions().single().endedAtMs)

            val newGeneration = 2_000L
            worker.submit(
                request(
                    sourceOrder = 3L,
                    lifecycle = 1L,
                    packageName = TARGET_PACKAGE,
                    capturedAtMs = newGeneration,
                    runtimePublication = RuntimePublication(
                        runtimeRevision = RuntimeRevision(2L),
                        candidateRuntime = initialRuntime.copy(
                            useDayGenerationStartedAtMs = newGeneration
                        )
                    )
                )
            )
            assertTrue(outcomes.awaitCount(3))

            val sessionsAfterRotation = repository.persistedSessions()
                .filter { it.packageName == TARGET_PACKAGE }
            assertEquals(2, sessionsAfterRotation.size)
            val oldSessionAfterRotation = sessionsAfterRotation.first { it.id == oldSession.id }
            val newSessionAfterRotation = sessionsAfterRotation.last()
            assertEquals(newGeneration, oldSessionAfterRotation.endedAtMs)
            assertEquals(0L, oldSessionAfterRotation.useDayGenerationStartedAtMs)
            assertEquals(newGeneration, newSessionAfterRotation.startedAtMs)
            assertEquals(
                newGeneration,
                newSessionAfterRotation.useDayGenerationStartedAtMs
            )
            assertEquals(
                "a still-visible generation rotation must not create another launch",
                1,
                repository.launchEvents.size
            )

            worker.submit(
                request(
                    sourceOrder = 4L,
                    lifecycle = 1L,
                    packageName = TARGET_PACKAGE,
                    capturedAtMs = newGeneration + 60_001L
                )
            )
            assertTrue(outcomes.awaitCount(4))
            val finalDecision = outcomes.enforcementOutcomes.last().packageDecisions.single()
            assertFalse(
                "the new generation's full minute of use must exhaust its allowance",
                finalDecision.isAllowed
            )
            assertTrue(TARGET_RULE_ID in finalDecision.denyingRuleIds)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun rapidSwitchesAreSerializedAndPersistedRowsMatchPublishedDecisions() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE, capturedAtMs = 1_000L))
            worker.submit(request(2L, 1L, OTHER_PACKAGE, capturedAtMs = 2_000L))
            worker.submit(request(3L, 1L, TARGET_PACKAGE, capturedAtMs = 3_000L))

            assertTrue(outcomes.awaitCount(3))
            assertEquals(
                listOf(TARGET_PACKAGE, OTHER_PACKAGE, TARGET_PACKAGE),
                outcomes.enforcementOutcomes.flatMap { it.packageDecisions.map(PackageDecision::packageName) }
            )

            val rows = repository.persistedSessions()
            assertEquals(3, rows.size)
            assertEquals(2_000L, rows[0].endedAtMs)
            assertEquals(3_000L, rows[1].endedAtMs)
            assertEquals(null, rows[2].endedAtMs)
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun staleLifecycleAndRuntimeRevisionCannotPublishSideEffects() {
        val repository = BlockingReadRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            worker.submit(request(1L, 1L, TARGET_PACKAGE))
            assertTrue(repository.readStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

            worker.beginLifecycle(
                lifecycleGeneration = LifecycleGeneration(2L),
                acceptedRuntime = acceptedRuntime(RuntimeRevision(2L))
            )
            repository.releaseRead()
            assertTrue(repository.firstReadFinished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(outcomes.awaitIdle())
            assertTrue("stale request published: ${outcomes.values}", outcomes.values.isEmpty())
            assertTrue("stale request scheduled: ${outcomes.recheckPlans}", outcomes.recheckPlans.isEmpty())

            val staleRevisionRequest = request(
                sourceOrder = 2L,
                lifecycle = 2L,
                packageName = TARGET_PACKAGE,
                runtimePublication = RuntimePublication(
                    runtimeRevision = RuntimeRevision(1L),
                    candidateRuntime = runtime()
                )
            )
            assertEquals(SubmissionResult.ACCEPTED, worker.submit(staleRevisionRequest))
            assertTrue(outcomes.awaitIdle())
            assertTrue("stale revision published: ${outcomes.values}", outcomes.values.isEmpty())
            assertTrue("stale revision scheduled: ${outcomes.recheckPlans}", outcomes.recheckPlans.isEmpty())

            worker.submit(request(3L, 2L, OTHER_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(1))
            assertEquals(RuntimeRevision(2L), outcomes.enforcementOutcomes.single().acceptedRuntimeRevision)
        } finally {
            repository.releaseRead()
            worker.stop(recoveryStop(LifecycleGeneration(2L)))
        }
    }

    @Test
    fun latestRuntimePublicationRemainsAuthoritativeAfterDelayedStalePublication() {
        val repository = RecordingRepository()
        val outcomes = RecordingOutcomeSink()
        val worker = worker(repository, outcomes)
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    request(
                        sourceOrder = 2L,
                        lifecycle = 1L,
                        packageName = TARGET_PACKAGE,
                        runtimePublication = RuntimePublication(
                            runtimeRevision = RuntimeRevision(3L),
                            candidateRuntime = runtime(allowedMinutes = 10L)
                        )
                    )
                )
            )
            assertTrue(outcomes.awaitCount(1))
            assertEquals(RuntimeRevision(3L), outcomes.enforcementOutcomes.single().acceptedRuntimeRevision)
            assertEquals(LifecycleGeneration(1L), outcomes.enforcementOutcomes.single().lifecycleGeneration)
            assertTrue(
                "latest runtime should allow the visible package",
                outcomes.enforcementOutcomes.single().packageDecisions.single().isAllowed
            )

            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    request(
                        sourceOrder = 1L,
                        lifecycle = 1L,
                        packageName = TARGET_PACKAGE,
                        runtimePublication = RuntimePublication(
                            runtimeRevision = RuntimeRevision(2L),
                            candidateRuntime = runtime(allowedMinutes = 0L)
                        )
                    )
                )
            )
            assertTrue(outcomes.awaitIdle())
            assertEquals(
                "stale runtime publication must not publish a second visible outcome",
                1,
                outcomes.enforcementOutcomes.size
            )

            worker.submit(request(3L, 1L, TARGET_PACKAGE, capturedAtMs = 2_000L))
            assertTrue(outcomes.awaitCount(2))
            val finalOutcome = outcomes.enforcementOutcomes.last()
            assertEquals(RuntimeRevision(3L), finalOutcome.acceptedRuntimeRevision)
            assertEquals(LifecycleGeneration(1L), finalOutcome.lifecycleGeneration)
            assertTrue(
                "the worker must continue using the latest visible policy",
                finalOutcome.packageDecisions.single().isAllowed
            )
        } finally {
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    @Test
    fun supersedingNoOpRuntimePublicationDoesNotLoseLatestVisibleOutcome() {
        val evaluationStarted = CountDownLatch(1)
        val releaseEvaluation = CountDownLatch(1)
        val blockFirstEvaluation = AtomicBoolean(true)
        val repository = object : RecordingRepository() {
            override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
                if (blockFirstEvaluation.compareAndSet(true, false)) {
                    evaluationStarted.countDown()
                    assertTrue(releaseEvaluation.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                }
                return super.sessionsForUseDay(useDayId)
            }
        }
        val latestRuntimeRevision = AtomicReference(RuntimeRevision(3L))
        val published = Collections.synchronizedList(mutableListOf<DecisionOutcome.EnforcementOutcome>())
        val sink = object : DecisionOutcomeSink {
            override fun publish(outcome: DecisionOutcome) {
                if (outcome is DecisionOutcome.EnforcementOutcome && outcome.acceptedRuntimeRevision == latestRuntimeRevision.get()) {
                    published += outcome
                }
            }
        }
        val worker = worker(
            repository = repository,
            sink = sink
        )
        try {
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    request(
                        sourceOrder = 1L,
                        lifecycle = 1L,
                        packageName = TARGET_PACKAGE,
                        runtimePublication = RuntimePublication(
                            runtimeRevision = RuntimeRevision(3L),
                            candidateRuntime = runtime(allowedMinutes = 10L)
                        )
                    )
                )
            )
            assertTrue(
                "the first visible evaluation did not enter the forced interleaving",
                evaluationStarted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )

            latestRuntimeRevision.set(RuntimeRevision(4L))
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    refreshRequest(
                        sourceOrder = 2L,
                        lifecycle = 1L,
                        runtimePublication = RuntimePublication(
                            runtimeRevision = RuntimeRevision(4L),
                            candidateRuntime = runtime(allowedMinutes = 10L)
                        )
                    )
                )
            )
            assertEquals(
                SubmissionResult.ACCEPTED,
                worker.submit(
                    request(
                        sourceOrder = 3L,
                        lifecycle = 1L,
                        packageName = TARGET_PACKAGE,
                        capturedAtMs = 2_000L
                    )
                )
            )
            releaseEvaluation.countDown()

            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (System.nanoTime() < deadline && published.size < 2) {
                Thread.yield()
            }
            val finalOutcomes = synchronized(published) { published.toList() }
            assertEquals(
                "the publication-only refresh and visible request must both publish revision 4",
                2,
                finalOutcomes.size
            )
            assertTrue(finalOutcomes.all { it.acceptedRuntimeRevision == RuntimeRevision(4L) })
            val finalOutcome = finalOutcomes.maxBy { it.sourceOrderIdentity.value }
            assertEquals(RuntimeRevision(4L), finalOutcome.acceptedRuntimeRevision)
            assertEquals(LifecycleGeneration(1L), finalOutcome.lifecycleGeneration)
            assertEquals(TARGET_PACKAGE, finalOutcome.packageDecisions.single().packageName)
            assertTrue(
                "the visible replacement must use the latest unchanged allow result",
                finalOutcome.packageDecisions.single().isAllowed
            )
        } finally {
            releaseEvaluation.countDown()
            worker.stop(recoveryStop(LifecycleGeneration(1L)))
        }
    }

    private fun worker(
        repository: RecordingRepository,
        sink: DecisionOutcomeSink,
        acceptedRuntime: AcceptedRuleRuntimeSnapshot = acceptedRuntime(
            RuntimeRevision(1L),
            usageTrackingDecision = AppUsageTrackingPolicy.decide(true, true)
        ),
        lifecycleGeneration: LifecycleGeneration = LifecycleGeneration(1L),
        usageTrackingDecision: AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(true, true),
        usageResetRepository: UsageResetRepository = RecordingUsageResetRepository(repository),
        onNonFatalError: (Throwable) -> Unit = {},
        elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
        workerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ): SerializedDecisionWorker = SerializedDecisionWorker(
        lifecycleGeneration = lifecycleGeneration,
        acceptedRuntime = acceptedRuntime.copy(
            runtime = acceptedRuntime.runtime.copy(
                usageTrackingDecision = usageTrackingDecision
            )
        ),
        repository = repository,
        outcomeSink = sink,
        usageResetRepository = usageResetRepository,
        onNonFatalError = onNonFatalError,
        elapsedRealtimeMs = elapsedRealtimeMs,
        workerScope = workerScope
    )

    private fun request(
        sourceOrder: Long,
        lifecycle: Long,
        packageName: String,
        capturedAtMs: Long = 1_000L,
        runtimePublication: RuntimePublication? = null
    ): DecisionRequest = DecisionRequest(
        sourceOrderIdentity = SourceOrderIdentity(sourceOrder),
        lifecycleGeneration = LifecycleGeneration(lifecycle),
        reason = ObservationKind.REAL_EVENT,
        observation = ForegroundFacts(
            capturedAtWallMs = capturedAtMs,
            capturedAtElapsedMs = capturedAtMs,
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = packageName,
                eventWallMs = capturedAtMs,
                eventElapsedMs = capturedAtMs
            ),
            activeRoot = ActiveRootFact(
                packageName = packageName,
                readState = ForegroundReadState.AVAILABLE
            ),
            applicationWindows = ApplicationWindowsFact(
                packages = setOf(packageName),
                readState = ForegroundReadState.AVAILABLE
            ),
            displayState = DisplayState.UNLOCKED
        ),
        runtimePublication = runtimePublication
    )

    private fun syntheticStaleRequest(
        sourceOrder: Long,
        lifecycle: Long,
        capturedAtMs: Long
    ): DecisionRequest = DecisionRequest(
        sourceOrderIdentity = SourceOrderIdentity(sourceOrder),
        lifecycleGeneration = LifecycleGeneration(lifecycle),
        reason = ObservationKind.SYNTHETIC_RECHECK,
        observation = ForegroundFacts(
            capturedAtWallMs = capturedAtMs,
            capturedAtElapsedMs = capturedAtMs,
            signal = SignalFact(kind = ObservationKind.SYNTHETIC_RECHECK),
            activeRoot = ActiveRootFact(readState = ForegroundReadState.EMPTY),
            applicationWindows = ApplicationWindowsFact(
                readState = ForegroundReadState.EMPTY
            ),
            displayState = DisplayState.UNLOCKED
        )
    )

    private fun refreshRequest(
        sourceOrder: Long,
        lifecycle: Long,
        runtimePublication: RuntimePublication? = null
    ): DecisionRequest = DecisionRequest(
        sourceOrderIdentity = SourceOrderIdentity(sourceOrder),
        lifecycleGeneration = LifecycleGeneration(lifecycle),
        reason = ObservationKind.REFRESH,
        observation = ForegroundFacts(
            capturedAtWallMs = 2_000L,
            capturedAtElapsedMs = 2_000L,
            signal = SignalFact(kind = ObservationKind.REFRESH),
            displayState = DisplayState.UNLOCKED
        ),
        runtimePublication = runtimePublication
    )

    private fun acceptedRuntime(
        revision: RuntimeRevision,
        allowedMinutes: Long = 0L,
        usageTrackingDecision: AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(true, true)
    ): AcceptedRuleRuntimeSnapshot = AcceptedRuleRuntimeSnapshot(
        runtime(allowedMinutes, usageTrackingDecision),
        revision
    )

    private fun approvalRuntime(
        revision: RuntimeRevision,
        useDayId: String,
        receipt: GuardianApprovalWorkReceipt?,
        useDayGenerationStartedAtMs: Long = 0L,
        nightAllowedMinutes: Long = 0L,
        resetTime: UseDayResetTime = UseDayResetTime()
    ): AcceptedRuleRuntimeSnapshot {
        val grants = when (receipt) {
            is GuardianApprovalWorkReceipt.Grant -> listOf(
                AppRuleGuardianGrant(
                    ruleId = receipt.grant.ruleId,
                    useDayId = receipt.grant.useDayId,
                    grantedAtMs = receipt.grant.grantedAtMs,
                    grantedMillis = receipt.grant.grantedMillis,
                    isFromAccumulatedPool = receipt.origin.isFromAccumulatedPool
                )
            )
            else -> emptyList()
        }
        val skips = (receipt as? GuardianApprovalWorkReceipt.RuleSkip)?.let {
            listOf(
                neth.iecal.curbox.data.models.AppRuleGuardianSkip(
                    ruleId = it.ruleId,
                    useDayId = it.useDayId,
                    skipFromMs = it.skipFromMs,
                    skipUntilMs = it.skipUntilMs
                )
            )
        }.orEmpty()
        return AcceptedRuleRuntimeSnapshot(
            runtime = RuleRuntimeSnapshot(
                snapshot = AppRuleSnapshot(
                    appGroups = listOf(
                        AppRuleAppGroup(
                            id = TARGET_GROUP_ID,
                            name = "Target",
                            selectedPackages = listOf(TARGET_PACKAGE)
                        )
                    ),
                    appRules = listOf(
                        AppRule(
                            id = "usage",
                            name = "Usage",
                            weekdays = (0..6).toSet(),
                            scope = AppRuleScope.forGroup(TARGET_GROUP_ID),
                            allowedMinutes = 0L,
                            guardianExtraTimeAllowed = true
                        ),
                        AppRule(
                            id = "night",
                            name = "Night",
                            weekdays = (0..6).toSet(),
                            scope = AppRuleScope(includeAllApps = true),
                            allowedMinutes = nightAllowedMinutes,
                            guardianExtraTimeAllowed = false
                        )
                    )
                ),
                resetTime = resetTime,
                useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                overrideState = neth.iecal.curbox.data.models.AppRuleOverrideState(
                    useDayId = useDayId,
                    useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                    grants = grants,
                    skips = skips
                ),
                launchablePackages = setOf(TARGET_PACKAGE),
                usageTrackingDecision = AppUsageTrackingPolicy.decide(true, true)
            ),
            runtimeRevision = revision
        )
    }

    private fun guardianApprovalRequest(
        sourceOrder: Long,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt,
        capturedAtMs: Long
    ) = GuardianApprovalEvaluationRequest(
        sourceOrderIdentity = SourceOrderIdentity(sourceOrder),
        lifecycleGeneration = LifecycleGeneration(1L),
        screenRequestId = "screen-$operationId",
        operationId = operationId,
        checkId = checkId,
        packageName = TARGET_PACKAGE,
        approvalReceipt = receipt,
        capturedAtWallMs = capturedAtMs,
        capturedAtElapsedMs = capturedAtMs
    )

    private fun runtime(
        allowedMinutes: Long = 0L,
        usageTrackingDecision: AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(true, true)
    ): RuleRuntimeSnapshot = RuleRuntimeSnapshot(
        snapshot = AppRuleSnapshot(
            appGroups = listOf(
                AppRuleAppGroup(
                    id = TARGET_GROUP_ID,
                    name = "Target",
                    selectedPackages = listOf(TARGET_PACKAGE)
                ),
                AppRuleAppGroup(
                    id = OTHER_GROUP_ID,
                    name = "Other",
                    selectedPackages = listOf(OTHER_PACKAGE)
                )
            ),
            appRules = listOf(
                AppRule(
                    id = TARGET_RULE_ID,
                    name = "Target rule",
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(TARGET_GROUP_ID),
                    allowedMinutes = allowedMinutes
                )
            )
        ),
        resetTime = UseDayResetTime(),
        useDayGenerationStartedAtMs = 0L,
        launchablePackages = setOf(TARGET_PACKAGE, OTHER_PACKAGE),
        evidencePolicy = ForegroundEvidencePolicySnapshot(),
        usageTrackingDecision = usageTrackingDecision
    )

    private fun recoveryStop(generation: LifecycleGeneration): RecoveryOnlyStop =
        RecoveryOnlyStop(
            requestedAtElapsedMs = 1_000L,
            reason = StopReason.DESTROY,
            lifecycleGeneration = generation
        )

    private class RecordingOutcomeSink : DecisionOutcomeSink {
        val values = Collections.synchronizedList(mutableListOf<DecisionOutcome>())

        val recheckPlans: List<RecheckPlanUpdate>
            get() = synchronized(values) {
                values.filterIsInstance<DecisionOutcome.RecheckPlanReady>().map { it.update }
            }

        val usageResets: List<Pair<UsageResetRequest, Boolean>>
            get() = synchronized(values) {
                values.filterIsInstance<DecisionOutcome.UsageResetFinished>().map { it.request to it.succeeded }
            }

        val evaluations: List<DecisionOutcome.EvaluationReady>
            get() = synchronized(values) {
                values.filterIsInstance<DecisionOutcome.EvaluationReady>()
            }

        val enforcementOutcomes: List<DecisionOutcome.EnforcementOutcome>
            get() = synchronized(values) {
                values.filterIsInstance<DecisionOutcome.EnforcementOutcome>()
            }

        override fun publish(outcome: DecisionOutcome) {
            values += outcome
        }

        val guardianApprovalEvaluations: List<DecisionOutcome.GuardianApprovalEvaluationReady>
            get() = synchronized(values) {
                values.filterIsInstance<DecisionOutcome.GuardianApprovalEvaluationReady>()
            }

        fun awaitCondition(predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS)
            while (System.nanoTime() < deadline) {
                if (predicate()) return true
                Thread.yield()
            }
            return predicate()
        }

        fun awaitCount(expected: Int): Boolean =
            awaitCondition { enforcementOutcomes.size >= expected }

        fun awaitGuardianApprovalCount(expected: Int): Boolean =
            awaitCondition { guardianApprovalEvaluations.size >= expected }

        fun awaitEvaluationCount(expected: Int): Boolean =
            awaitCondition { evaluations.size >= expected }

        fun awaitRecheckPlanCount(expected: Int): Boolean =
            awaitCondition { recheckPlans.size >= expected }

        fun awaitUsageResetCount(expected: Int): Boolean =
            awaitCondition { usageResets.size >= expected }

        fun awaitTotalCount(expected: Int): Boolean =
            awaitCondition { values.size >= expected }

        fun awaitIdle(): Boolean {
            Thread.sleep(50L)
            return true
        }
    }

    private open class RecordingRepository : CurrentUseDaySessionRepository {
        @Volatile var evaluatorFailure: Throwable? = null
        @Volatile var startFailure: Throwable? = null
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val launchEvents = Collections.synchronizedList(mutableListOf<String>())
        val statisticsLaunches = Collections.synchronizedList(mutableListOf<String>())
        val committedCheckpoints = Collections.synchronizedList(
            mutableListOf<CheckpointCommit>()
        )
        private val nextId = AtomicLong(1L)
        private val sessions = Collections.synchronizedList(mutableListOf<ForegroundSession>())

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            startFailure?.let { throw it }
            operations += "start:$packageName"
            val id = nextId.getAndIncrement()
            sessions += ForegroundSession(id, useDayId, packageName, startedAtMs, null)
            return id
        }

        override suspend fun startSessionAtGeneration(
            useDayId: String,
            packageName: String,
            startedAtMs: Long,
            generationStartedAtMs: Long,
            statisticsTracked: Boolean
        ): Long {
            val id = startSession(useDayId, packageName, startedAtMs)
            synchronized(sessions) {
                val index = sessions.indexOfFirst { it.id == id }
                sessions[index] = sessions[index].copy(
                    useDayGenerationStartedAtMs = generationStartedAtMs,
                    statisticsTracked = statisticsTracked
                )
            }
            return id
        }

        override suspend fun recordLaunch(
            useDayId: String,
            packageName: String,
            launchedAtMs: Long,
            generationStartedAtMs: Long
        ) {
            launchEvents += "$packageName@$launchedAtMs"
        }

        override suspend fun recordLaunchStatistics(packageName: String, launchedAtMs: Long) {
            statisticsLaunches += "$packageName@$launchedAtMs"
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            operations += "finish:$id"
            updateSessionEnd(id, endedAtMs)
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            synchronized(sessions) {
                val index = sessions.indexOfFirst { it.id == id }
                if (index >= 0) sessions[index] = sessions[index].copy(endedAtMs = endedAtMs)
            }
        }

        override suspend fun commitSessionCheckpoint(
            id: Long,
            endedAtMs: Long,
            usage: List<ForegroundUsageCheckpoint>
        ): Boolean {
            val packageName = synchronized(sessions) { sessions.first { it.id == id }.packageName }
            operations += "commit:$packageName"
            committedCheckpoints += CheckpointCommit(id, endedAtMs, usage)
            updateSessionEnd(id, endedAtMs)
            return true
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            evaluatorFailure?.let { throw it }
            operations += "evaluate:${sessions.lastOrNull()?.packageName ?: "none"}"
            return persistedSessions()
        }

        override suspend fun sessionsForUseDay(
            useDayId: String,
            generationStartedAtMs: Long
        ): List<ForegroundSession> = sessionsForUseDay(useDayId).filter { session ->
            generationStartedAtMs <= 0L ||
                session.useDayGenerationStartedAtMs >= generationStartedAtMs
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit

        fun persistedSessions(): List<ForegroundSession> = synchronized(sessions) { sessions.toList() }

        fun restartSession(restart: UsageResetSessionRestart): Long {
            synchronized(sessions) {
                val oldIndex = sessions.indexOfFirst { it.id == restart.activeSessionId }
                if (oldIndex >= 0) {
                    sessions.removeAt(oldIndex)
                }
                val id = nextId.getAndIncrement()
                sessions += ForegroundSession(
                    id = id,
                    useDayId = restart.useDayId,
                    packageName = restart.packageName,
                    startedAtMs = restart.startedAtMs,
                    statisticsTracked = restart.statisticsTracked
                )
                return id
            }
        }
    }

    private class CheckpointFailingRepository : RecordingRepository() {
        @Volatile var failNextCheckpoint = false

        override suspend fun commitSessionCheckpoint(
            id: Long,
            endedAtMs: Long,
            usage: List<ForegroundUsageCheckpoint>
        ): Boolean {
            if (failNextCheckpoint) {
                failNextCheckpoint = false
                return false
            }
            return super.commitSessionCheckpoint(id, endedAtMs, usage)
        }
    }

    private class RequestCancellingRepository : RecordingRepository() {
        @Volatile var cancelNext = true
        val evaluatorJob = AtomicReference<Job?>()
        val childCompleted = CountDownLatch(1)

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (cancelNext) {
                val job = checkNotNull(currentCoroutineContext()[Job])
                evaluatorJob.set(job)
                job.invokeOnCompletion { childCompleted.countDown() }
                throw CancellationException("injected evaluator cancellation")
            }
            return super.sessionsForUseDay(useDayId)
        }
    }

    private class ScopeCancellingRepository : RecordingRepository() {
        val readStarted = CountDownLatch(1)
        private val neverRelease = CompletableDeferred<Unit>()

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            readStarted.countDown()
            neverRelease.await()
            return super.sessionsForUseDay(useDayId)
        }
    }

    private class RecordingUsageResetRepository(
        private val repository: RecordingRepository
    ) : UsageResetRepository {
        @Volatile var resetFailure: Throwable? = null
        val commands = Collections.synchronizedList(mutableListOf<ResetCommand>())
        val restartedSessionIds = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())

        override suspend fun reset(request: UsageResetRequest): UsageResetResult =
            resetAndStartSessions(request, emptyList())

        override suspend fun resetAndStartSessions(
            request: UsageResetRequest,
            restarts: List<UsageResetSessionRestart>
        ): UsageResetResult {
            resetFailure?.let { throw it }
            commands += ResetCommand(request, restarts)
            val restarted = restarts.map { restart ->
                val id = repository.restartSession(restart)
                restartedSessionIds += restart.packageName to id
                restart.packageName to id
            }.toMap()
            return UsageResetResult(
                request = request,
                delta = UsageResetDelta(emptyMap(), emptyMap()),
                restartedSessionIds = restarted
            )
        }
    }

    private data class ResetCommand(
        val request: UsageResetRequest,
        val restarts: List<UsageResetSessionRestart>
    )

    private data class CheckpointCommit(
        val sessionId: Long,
        val endedAtMs: Long,
        val usage: List<ForegroundUsageCheckpoint>
    )

    private class DelayedRepository : RecordingRepository() {
        val startStarted = CountDownLatch(1)
        @Volatile var released = false
        private val releaseStart = CountDownLatch(1)

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            startStarted.countDown()
            releaseStart.await()
            return super.startSession(useDayId, packageName, startedAtMs)
        }

        fun release() {
            released = true
            releaseStart.countDown()
        }
    }

    private class BlockingReadRepository : RecordingRepository() {
        val readStarted = CountDownLatch(1)
        val firstReadFinished = CountDownLatch(1)
        private val releaseRead = CountDownLatch(1)
        private var reads = 0

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (reads++ == 0) {
                readStarted.countDown()
                releaseRead.await()
                firstReadFinished.countDown()
            }
            return super.sessionsForUseDay(useDayId)
        }

        fun releaseRead() = releaseRead.countDown()
    }

    private class BlockingCommitRepository : RecordingRepository() {
        val commitStarted = CountDownLatch(1)
        val secondEvaluationStarted = CountDownLatch(1)
        private val commitRelease = CountDownLatch(1)
        private var evaluations = 0

        override suspend fun commitSessionCheckpoint(
            id: Long,
            endedAtMs: Long,
            usage: List<ForegroundUsageCheckpoint>
        ): Boolean {
            commitStarted.countDown()
            commitRelease.await()
            return super.commitSessionCheckpoint(id, endedAtMs, usage)
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (++evaluations == 2) secondEvaluationStarted.countDown()
            return super.sessionsForUseDay(useDayId)
        }

        fun releaseCommit() = commitRelease.countDown()
    }

    companion object {
        private const val TARGET_PACKAGE = "com.example.target"
        private const val OTHER_PACKAGE = "com.example.other"
        private const val TARGET_GROUP_ID = "target-group"
        private const val OTHER_GROUP_ID = "other-group"
        private const val TARGET_RULE_ID = "target-rule"
        private const val WAIT_TIMEOUT_MS = 2_000L
    }
}
