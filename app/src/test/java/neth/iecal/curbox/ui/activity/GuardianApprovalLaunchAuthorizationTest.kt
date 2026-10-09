package neth.iecal.curbox.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.GuardianApprovalPolicyFingerprint
import org.junit.Test

class GuardianApprovalLaunchAuthorizationTest {
    @Test
    fun anotherCurboxActivityInTheForegroundCannotUseTheApprovalRequestToLaunch() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current),
                current = current,
                confirmationPending = true,
                activityResumed = false,
                windowFocused = false,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun currentGuardianApprovalCanLaunchWhenItOwnsTheForeground() {
        val current = executionIdentity()

        assertTrue(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current),
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun explicitUnboundedEvaluationDeadlineRemainsCurrentAtLaunch() {
        val current = executionIdentity()
        val offer = launchOffer(identity = current).copy(
            deadlineElapsedRealtimeMs = Long.MAX_VALUE,
            capturedAtWallClockMs = 100L,
            capturedAtElapsedRealtimeMs = 100L,
            validUntilWallClockMs = Long.MAX_VALUE
        )

        assertTrue(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = offer,
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 1_000_000_000L,
                nowElapsedRealtimeMs = 1_000_000_000L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun lateResultCannotLaunchAfterTheApprovalRequestChanges() {
        val current = executionIdentity(checkId = "current-check")
        val old = executionIdentity(checkId = "old-check")

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = old),
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun timedOutOrFailedConfirmationCannotAcceptAnAllowedOffer() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current),
                current = current,
                confirmationPending = false,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun tightenedPolicyBetweenOfferAndLaunchRejectsTheOldAuthorization() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current),
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = "tightened-policy"
            )
        )
    }

    @Test
    fun expiredOfferCannotLaunchEvenIfItsBroadcastArrivesBeforeTimeoutUiState() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current),
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 1_000L,
                nowElapsedRealtimeMs = 1_000L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun scheduledPolicyBoundaryExpiresAnOfferEvenWhenSettingsAreUnchanged() {
        val current = executionIdentity()
        val offer = launchOffer(identity = current).copy(
            capturedAtWallClockMs = 1_000L,
            capturedAtElapsedRealtimeMs = 500L,
            validUntilWallClockMs = 2_000L
        )

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = offer,
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 2_000L,
                nowElapsedRealtimeMs = 900L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun elapsedBoundaryExpiresAnOfferIfTheWallClockMovesBackwards() {
        val current = executionIdentity()
        val offer = launchOffer(identity = current).copy(
            capturedAtWallClockMs = 1_000L,
            capturedAtElapsedRealtimeMs = 500L,
            validUntilWallClockMs = 2_000L
        )

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = offer,
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 1_500L,
                nowElapsedRealtimeMs = 1_500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun changedTimeZoneRejectsAnOtherwiseCurrentAllowedOffer() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current),
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = "America/Los_Angeles",
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun missingEvaluationTimeZoneCannotAuthorizeLaunch() {
        val current = executionIdentity()

        assertFalse(
            GuardianApprovalLaunchAuthorization.canLaunch(
                offer = launchOffer(identity = current).copy(evaluationZoneId = ""),
                current = current,
                confirmationPending = true,
                activityResumed = true,
                windowFocused = true,
                displayUnlocked = true,
                currentZoneId = EVALUATION_ZONE_ID,
                nowWallClockMs = 500L,
                nowElapsedRealtimeMs = 500L,
                currentPolicyFingerprint = POLICY_FINGERPRINT
            )
        )
    }

    @Test
    fun policyFingerprintChangesWhenSettingsChangeAndIsStableForTheSameSnapshot() {
        val allowedSettings = Settings()

        assertNotEquals(
            GuardianApprovalPolicyFingerprint.forSettings(allowedSettings),
            GuardianApprovalPolicyFingerprint.forSettings(
                allowedSettings.copy(isAppUsageTrackingEnabled = false)
            )
        )
        assertTrue(
            GuardianApprovalPolicyFingerprint.forSettings(allowedSettings) ==
                GuardianApprovalPolicyFingerprint.forSettings(allowedSettings.copy())
        )
    }

    private fun executionIdentity(
        screenRequestId: String = "guardian-screen-current",
        operationId: String = "guardian-operation-current",
        checkId: String = "guardian-check-current",
        serviceConnectionId: String = "guardian-connection-current"
    ) = GuardianApprovalExecutionIdentity(
        screenRequestId = screenRequestId,
        operationId = operationId,
        checkId = checkId,
        serviceConnectionId = serviceConnectionId
    )

    private fun launchOffer(identity: GuardianApprovalExecutionIdentity) =
        GuardianApprovalLaunchOffer(
            identity = identity,
            offerId = "offer-current",
            policyFingerprint = POLICY_FINGERPRINT,
            runtimeRevision = 21L,
            deadlineElapsedRealtimeMs = 1_000L,
            evaluationZoneId = EVALUATION_ZONE_ID,
            capturedAtWallClockMs = 100L,
            capturedAtElapsedRealtimeMs = 100L,
            validUntilWallClockMs = Long.MAX_VALUE
        )

    private companion object {
        const val POLICY_FINGERPRINT = "allowed-policy"
        const val EVALUATION_ZONE_ID = "UTC"
    }
}
