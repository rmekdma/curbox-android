package neth.iecal.curbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianActivityGateTest {
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
}
