package neth.iecal.curbox.ui.activity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Synthetic out-of-order offers are defensive cases; normal service re-evaluation is serialized. */
class GuardianApprovalOfferLaunchWorkflowTest {
    @Test
    fun syntheticReplacementDuringLookupFencesTheOldAttempt() = runBlocking {
        val oldLookup = ManualGate<String?>()
        var lookupNumber = 0
        val host = RecordingHost().apply {
            lookup = { packageName ->
                if (++lookupNumber == 1) {
                    oldLookup.await()
                } else {
                    packageName
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            workflow.accept(request(offerId = "offer-a"))
            workflow.accept(request(offerId = "offer-b"))

            assertEquals(listOf("offer-b"), host.launchedOfferIds)
            oldLookup.resume("old-target")
            yield()

            assertEquals(listOf("offer-b"), host.launchedOfferIds)
            assertEquals(listOf(GuardianApprovalLaunchOutcome.LaunchStarted), host.outcomes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activityIdentityChangeDuringLookupStopsBeforeSettingsRead() = runBlocking {
        val oldLookup = ManualGate<String?>()
        val host = RecordingHost().apply { lookup = { oldLookup.await() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            workflow.accept(request(offerId = "activity-identity-changed"))

            val currentRequestState = host.snapshot.requestState
            host.snapshot = host.snapshot.copy(
                requestState = currentRequestState.copy(
                    identity = currentRequestState.identity.copy(checkId = "replacement-check")
                )
            )
            oldLookup.resume(TARGET_PACKAGE)
            yield()

            assertEquals(0, host.policyReadCount)
            assertTrue(host.launchedTargets.isEmpty())
            assertTrue(host.outcomes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syntheticReplacementFencesAnOldPolicyReadFailure() = runBlocking {
        val oldPolicyRead = ManualGate<Result<String>>()
        val host = RecordingHost().apply {
            readPolicy = { offerId ->
                if (offerId == "offer-a") {
                    oldPolicyRead.await().getOrThrow()
                } else {
                    POLICY_FINGERPRINT
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            workflow.accept(request(offerId = "offer-a"))
            workflow.accept(request(offerId = "offer-b"))

            assertEquals(listOf("offer-b"), host.launchedOfferIds)
            oldPolicyRead.resume(Result.failure(IllegalStateException("stale settings failure")))
            yield()

            assertEquals(listOf("offer-b"), host.launchedOfferIds)
            assertEquals(listOf(GuardianApprovalLaunchOutcome.LaunchStarted), host.outcomes)
            assertEquals(1, host.loggedErrors.size)
            assertEquals("stale settings failure", host.loggedErrors.single().message)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun firstAuthorizationReportsPolicyInvalidationBeforeExpiredDeadline() {
        val host = RecordingHost().apply {
            snapshot = validSnapshot().copy(nowElapsedRealtimeMs = 2_000L)
            policyFingerprint = "new-policy"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            GuardianApprovalOfferLaunchWorkflow(scope, host)
                .accept(request(offerId = "offer-stale-policy", deadlineElapsedRealtimeMs = 1_000L))

            assertEquals(
                listOf(GuardianApprovalLaunchOutcome.OfferInvalidated),
                host.outcomes
            )
            assertTrue(host.launchedOfferIds.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestForAnotherTargetIsRejectedBeforeLookup() {
        val host = RecordingHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            GuardianApprovalOfferLaunchWorkflow(scope, host)
                .accept(request(offerId = "wrong-target", targetPackageName = "another.app"))

            assertFalse(host.lookupCount > 0)
            assertTrue(host.launchedTargets.isEmpty())
            assertTrue(host.outcomes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syntheticReplacementDuringPolicyReadFencesTheOldSuccessfulRead() = runBlocking {
        val oldPolicyRead = ManualGate<String>()
        val host = RecordingHost().apply {
            readPolicy = { offerId ->
                if (offerId == "offer-a") oldPolicyRead.await() else POLICY_FINGERPRINT
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            workflow.accept(request(offerId = "offer-a"))
            workflow.accept(request(offerId = "offer-b"))

            assertEquals(listOf("offer-b"), host.launchedOfferIds)
            oldPolicyRead.resume(POLICY_FINGERPRINT)
            yield()

            assertEquals(listOf("offer-b"), host.launchedOfferIds)
            assertEquals(listOf(GuardianApprovalLaunchOutcome.LaunchStarted), host.outcomes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun currentSettingsReadFailureReportsFailureAndReleasesTheClaim() {
        var policyReadCount = 0
        val host = RecordingHost().apply {
            readPolicy = {
                policyReadCount += 1
                if (policyReadCount == 1) {
                    throw IllegalStateException("settings unavailable")
                }
                POLICY_FINGERPRINT
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            val request = request(offerId = "settings-failure")
            workflow.accept(request)

            assertEquals(
                listOf(GuardianApprovalLaunchOutcome.SettingsReadFailed),
                host.outcomes
            )
            assertEquals(1, host.loggedErrors.size)
            assertEquals(1, host.lookupCount)

            workflow.accept(request)

            assertEquals(
                listOf(
                    GuardianApprovalLaunchOutcome.SettingsReadFailed,
                    GuardianApprovalLaunchOutcome.LaunchStarted
                ),
                host.outcomes
            )
            assertEquals(2, host.lookupCount)
            assertEquals(listOf("settings-failure"), host.launchedOfferIds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun targetPackageChangeDuringSettingsReadStopsBeforeLaunch() = runBlocking {
        val oldPolicyRead = ManualGate<String>()
        val host = RecordingHost().apply {
            readPolicy = { oldPolicyRead.await() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            workflow.accept(request(offerId = "target-package-changed"))

            assertEquals(1, host.lookupCount)
            assertEquals(1, host.policyReadCount)
            host.snapshot = host.snapshot.copy(
                requestState = host.snapshot.requestState.copy(
                    targetPackageName = "replacement.app"
                )
            )
            oldPolicyRead.resume(POLICY_FINGERPRINT)
            yield()

            assertTrue(host.launchedTargets.isEmpty())
            assertTrue(host.outcomes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deadlineOnlyFirstAuthorizationRejectionReportsDeadlineExpired() {
        val host = RecordingHost().apply {
            snapshot = validSnapshot().copy(nowElapsedRealtimeMs = 2_000L)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            GuardianApprovalOfferLaunchWorkflow(scope, host)
                .accept(request(offerId = "deadline-only", deadlineElapsedRealtimeMs = 1_000L))

            assertEquals(listOf(GuardianApprovalLaunchOutcome.DeadlineExpired), host.outcomes)
            assertTrue(host.launchedTargets.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun launchExceptionIsReportedWithoutReleasingTheOfferClaim() {
        val host = RecordingHost().apply {
            effect = { _, _ -> throw IllegalStateException("launch rejected") }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            val request = request(offerId = "launch-failure")
            workflow.accept(request)
            workflow.accept(request)

            assertEquals(listOf(GuardianApprovalLaunchOutcome.LaunchFailed), host.outcomes)
            assertEquals(1, host.loggedErrors.size)
            assertEquals(1, host.lookupCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun laterSynchronousGuardRejectionRetainsTheClaim() {
        val host = RecordingHost().apply {
            effect = { _, _ -> GuardianApprovalLaunchEffectResult.Rejected }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            val request = request(offerId = "sync-rejected")
            workflow.accept(request)
            host.effect = { _, _ -> GuardianApprovalLaunchEffectResult.Started }
            workflow.accept(request)

            assertEquals(1, host.effectCount)
            assertTrue(host.launchedTargets.isEmpty())
            assertTrue(host.outcomes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun synchronousWindowInvalidationReleasesTheClaimForReevaluation() {
        val host = RecordingHost().apply {
            effect = { _, _ -> GuardianApprovalLaunchEffectResult.EvaluationWindowInvalid }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            val request = request(offerId = "sync-window-expired")
            workflow.accept(request)
            host.effect = { _, _ -> GuardianApprovalLaunchEffectResult.Started }
            workflow.accept(request)

            assertEquals(listOf("sync-window-expired"), host.launchedOfferIds)
            assertEquals(
                listOf(
                    GuardianApprovalLaunchOutcome.OfferInvalidated,
                    GuardianApprovalLaunchOutcome.LaunchStarted
                ),
                host.outcomes
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syntheticSameOfferIdReuseAfterReplacementGetsANewGeneration() = runBlocking {
        val firstA = ManualGate<String?>()
        val latestA = ManualGate<String?>()
        var lookupNumber = 0
        val host = RecordingHost().apply {
            lookup = { _ ->
                when (++lookupNumber) {
                    1 -> firstA.await()
                    2 -> "middle-b"
                    else -> latestA.await()
                }
            }
            effect = { offerId, _ ->
                if (offerId == "offer-b") {
                    GuardianApprovalLaunchEffectResult.Rejected
                } else {
                    GuardianApprovalLaunchEffectResult.Started
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            workflow.accept(request(offerId = "offer-a"))
            workflow.accept(request(offerId = "offer-b"))
            workflow.accept(request(offerId = "offer-a"))

            firstA.resume("stale-a")
            yield()
            assertTrue(host.launchedTargets.isEmpty())

            latestA.resume("latest-a")
            yield()

            assertEquals(listOf("latest-a"), host.launchedTargets)
            assertEquals(listOf("offer-a"), host.launchedOfferIds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lookupFailureKeepsTheActiveClaimUntilAStateTransition() {
        val host = RecordingHost().apply { lookup = { null } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            val request = request(offerId = "missing-launch-target")
            workflow.accept(request)
            workflow.accept(request)

            assertEquals(1, host.lookupCount)
            assertEquals(
                listOf(GuardianApprovalLaunchOutcome.LookupFailed),
                host.outcomes
            )
            assertTrue(host.launchedTargets.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun quietFocusRejectionReleasesClaimWithoutChangingCheckingState() {
        val host = RecordingHost().apply {
            snapshot = validSnapshot().copy(windowFocused = false)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workflow = GuardianApprovalOfferLaunchWorkflow(scope, host)
            val request = request(offerId = "focus-rejected")
            workflow.accept(request)
            host.snapshot = validSnapshot()
            workflow.accept(request)

            assertEquals(listOf("focus-rejected"), host.launchedOfferIds)
            assertEquals(listOf(GuardianApprovalLaunchOutcome.LaunchStarted), host.outcomes)
        } finally {
            scope.cancel()
        }
    }

    private class RecordingHost : GuardianApprovalLaunchHost<String> {
        var snapshot = validSnapshot()
        var policyFingerprint = POLICY_FINGERPRINT
        var lookup: suspend (String) -> String? = { it }
        var readPolicy: suspend (String) -> String = { policyFingerprint }
        var effect: (String, String) -> GuardianApprovalLaunchEffectResult = { _, _ ->
            GuardianApprovalLaunchEffectResult.Started
        }
        var lookupCount = 0
        var policyReadCount = 0
        var effectCount = 0
        val launchedTargets = mutableListOf<String>()
        val launchedOfferIds = mutableListOf<String>()
        val outcomes = mutableListOf<GuardianApprovalLaunchOutcome>()
        val loggedErrors = mutableListOf<Exception>()

        override fun currentRequestState(): GuardianApprovalLaunchRequestState = snapshot.requestState

        override fun currentSnapshot(): GuardianApprovalLaunchSnapshot = snapshot

        override suspend fun lookupTargetLaunchIntent(targetPackageName: String): String? {
            lookupCount++
            return lookup(targetPackageName)
        }

        override suspend fun readCurrentPolicyFingerprint(offer: GuardianApprovalLaunchOffer): String {
            policyReadCount++
            return readPolicy(offer.offerId)
        }

        override fun launchIfCurrent(
            request: GuardianApprovalLaunchRequest,
            target: String,
            currentPolicyFingerprint: String
        ): GuardianApprovalLaunchEffectResult = effect(request.offer.offerId, target).also {
            effectCount++
            if (it == GuardianApprovalLaunchEffectResult.Started) {
                launchedTargets += target
                launchedOfferIds += request.offer.offerId
            }
        }

        override fun applyOutcome(
            request: GuardianApprovalLaunchRequest,
            outcome: GuardianApprovalLaunchOutcome
        ) {
            outcomes += outcome
        }

        override fun logNonFatalError(error: Exception) {
            loggedErrors += error
        }
    }

    private class ManualGate<Value> {
        private var continuation: Continuation<Value>? = null

        suspend fun await(): Value = suspendCoroutine { continuation = it }

        fun resume(value: Value) {
            val pending = continuation ?: error("The gate has not been reached")
            continuation = null
            pending.resume(value)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "target.app"
        const val POLICY_FINGERPRINT = "current-policy"

        fun request(
            offerId: String,
            targetPackageName: String = TARGET_PACKAGE,
            deadlineElapsedRealtimeMs: Long = 10_000L
        ) = GuardianApprovalLaunchRequest(
            targetPackageName = targetPackageName,
            offer = GuardianApprovalLaunchOffer(
                identity = validSnapshot().requestState.identity,
                offerId = offerId,
                policyFingerprint = POLICY_FINGERPRINT,
                runtimeRevision = 1L,
                deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                evaluationZoneId = "UTC",
                capturedAtWallClockMs = 100L,
                capturedAtElapsedRealtimeMs = 100L,
                validUntilWallClockMs = Long.MAX_VALUE
            )
        )

        fun validSnapshot() = GuardianApprovalLaunchSnapshot(
            requestState = GuardianApprovalLaunchRequestState(
                targetPackageName = TARGET_PACKAGE,
                identity = GuardianApprovalExecutionIdentity(
                    screenRequestId = "screen",
                    operationId = "operation",
                    checkId = "check",
                    serviceConnectionId = "connection"
                ),
                callbacksAllowed = true,
                confirmationPending = true
            ),
            activityResumed = true,
            windowFocused = true,
            displayUnlocked = true,
            currentZoneId = "UTC",
            nowWallClockMs = 500L,
            nowElapsedRealtimeMs = 500L
        )
    }
}
