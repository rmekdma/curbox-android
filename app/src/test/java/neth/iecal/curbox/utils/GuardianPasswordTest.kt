package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.GuardianAuthConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class GuardianPasswordTest {
    @Test
    fun credentialUsesRandomSaltAndVerifiesOnlyTheOriginalPassword() {
        val first = GuardianPassword.createCredential("same", SecureRandom())
        val second = GuardianPassword.createCredential("same", SecureRandom())

        assertNotEquals(first.passwordSalt, second.passwordSalt)
        assertNotEquals(first.passwordVerifier, second.passwordVerifier)
        assertTrue(GuardianPassword.verify("same", first))
        assertFalse(GuardianPassword.verify("different", first))
    }

    @Test
    fun emptyPasswordDisablesAuthenticationWithoutAMinimumLength() {
        val short = GuardianPassword.createCredential("x", SecureRandom(), iterations = 1)
        assertTrue(short.isConfigured)
        assertTrue(GuardianPassword.verify("x", short))
        assertFalse(GuardianPassword.createCredential("").isConfigured)
        assertFalse(GuardianPassword.verify("", GuardianAuthConfig()))
    }

    @Test
    fun externalFocusRequiresReauthenticationButCurboxDialogsDoNot() {
        assertTrue(
            GuardianReauthPolicy.requiresReauthentication(
                hasPassword = true,
                previous = GuardianFocusSurface.CURBOX_CONTENT,
                next = GuardianFocusSurface.EXTERNAL_SYSTEM
            )
        )
        assertFalse(
            GuardianReauthPolicy.requiresReauthentication(
                hasPassword = true,
                previous = GuardianFocusSurface.CURBOX_DIALOG,
                next = GuardianFocusSurface.CURBOX_CONTENT
            )
        )
        assertFalse(
            GuardianReauthPolicy.requiresReauthentication(
                hasPassword = false,
                previous = GuardianFocusSurface.CURBOX_CONTENT,
                next = GuardianFocusSurface.UNKNOWN
            )
        )
    }

    @Test
    fun authenticatedSessionSurvivesInternalDialogsAndOneShotSystemResultOnly() {
        val config = GuardianPassword.createCredential("x", iterations = 1)
        val session = GuardianAuthSession()

        assertTrue(session.authenticate("x", config))
        assertFalse(
            session.transition(GuardianFocusSurface.CURBOX_DIALOG, hasPassword = true)
        )
        assertTrue(session.isAuthenticated(hasPassword = true))
        assertFalse(
            session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
        )
        session.markOneShotSystemResult()
        assertFalse(
            session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
        )
        assertTrue(session.isAuthenticated(hasPassword = true))
        assertTrue(
            session.transition(GuardianFocusSurface.EXTERNAL_APP, hasPassword = true)
        )
        assertFalse(session.isAuthenticated(hasPassword = true))
    }
}
