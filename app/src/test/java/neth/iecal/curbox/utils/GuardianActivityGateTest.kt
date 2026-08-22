package neth.iecal.curbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianActivityGateTest {
    @Test
    fun configuredOnboardingRouteStillRequiresGuardianGate() {
        assertTrue(
            GuardianRoutePolicy.requiresGate(
                guardianConfigured = true,
                route = GuardianRoutePolicy.Route.ONBOARDING
            )
        )
        assertTrue(
            GuardianRoutePolicy.requiresGate(
                guardianConfigured = true,
                route = GuardianRoutePolicy.Route.MANAGED
            )
        )
        assertFalse(
            GuardianRoutePolicy.requiresGate(
                guardianConfigured = false,
                route = GuardianRoutePolicy.Route.ONBOARDING
            )
        )
    }

    @Test
    fun configuredContentNeedsSuccessfulReauthenticationBeforeCommit() {
        val gate = GuardianGateAccess()

        assertFalse(gate.canCommit(hasPassword = true, sessionAuthenticated = false))
        assertTrue(gate.authorize(hasPassword = true, sessionAuthenticated = true))
        assertTrue(gate.canCommit(hasPassword = true, sessionAuthenticated = true))

        gate.invalidate()

        assertFalse(gate.canCommit(hasPassword = true, sessionAuthenticated = true))
    }

    @Test
    fun externalReturnCannotConfirmSelectionUntilReauthenticationRevealsContent() {
        val gate = GuardianGateAccess()

        assertTrue(gate.authorize(hasPassword = true, sessionAuthenticated = true))
        gate.invalidate()

        assertFalse(gate.canCommit(hasPassword = true, sessionAuthenticated = true))
        assertTrue(gate.authorize(hasPassword = true, sessionAuthenticated = true))
        assertTrue(gate.canCommit(hasPassword = true, sessionAuthenticated = true))
    }

    @Test
    fun successfulReauthenticationRevealsContentAfterFocusLoss() {
        val gate = GuardianGateAccess()

        assertTrue(gate.authorize(hasPassword = true, sessionAuthenticated = true))
        gate.invalidate()

        assertFalse(gate.authorize(hasPassword = true, sessionAuthenticated = false))
        assertFalse(gate.canCommit(hasPassword = true, sessionAuthenticated = true))
        assertTrue(gate.authorize(hasPassword = true, sessionAuthenticated = true))
        assertTrue(gate.canCommit(hasPassword = true, sessionAuthenticated = true))
    }

    @Test
    fun noPasswordRevealsAndAllowsCommitWithoutSessionAuthentication() {
        val gate = GuardianGateAccess()

        assertTrue(gate.authorize(hasPassword = false, sessionAuthenticated = false))
        assertTrue(gate.canCommit(hasPassword = false, sessionAuthenticated = false))
    }

    @Test
    fun ownedDialogStateIsClearedBeforeUnexpectedFocusLossCanPreserveIt() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))

        GuardianSessionRegistry.markOwnedDialogShown()
        assertFalse(GuardianSessionRegistry.handleWindowFocusLost())
        assertTrue(session.isAuthenticated(hasPassword = true))

        GuardianSessionRegistry.markOwnedDialogHidden()
        assertTrue(GuardianSessionRegistry.handleWindowFocusLost())
        assertFalse(session.isAuthenticated(hasPassword = true))
        session.clear()
    }

    @Test
    fun bottomSheetDismissalDoesNotLeaveAStaleOwnedDialogMarker() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))

        GuardianSessionRegistry.markOwnedDialogShown()
        assertTrue(GuardianSessionRegistry.isOwnedDialogActive())
        GuardianSessionRegistry.markOwnedDialogHidden()

        assertFalse(GuardianSessionRegistry.isOwnedDialogActive())
        assertTrue(GuardianSessionRegistry.handleWindowFocusLost())
        assertFalse(session.isAuthenticated(hasPassword = true))
        session.clear()
    }

    @Test
    fun nestedOwnedDialogDismissalKeepsTheOuterBottomSheetOwned() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))

        GuardianSessionRegistry.markOwnedDialogShown()
        GuardianSessionRegistry.markOwnedDialogShown()
        GuardianSessionRegistry.markOwnedDialogHidden()

        assertTrue(GuardianSessionRegistry.isOwnedDialogActive())
        assertFalse(GuardianSessionRegistry.handleWindowFocusLost())

        GuardianSessionRegistry.markOwnedDialogHidden()
        assertFalse(GuardianSessionRegistry.isOwnedDialogActive())
        assertTrue(GuardianSessionRegistry.handleWindowFocusLost())
        session.clear()
    }
}
