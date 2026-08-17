package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.GuardianAuthConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

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
        assertTrue(
            GuardianReauthPolicy.requiresReauthentication(
                hasPassword = true,
                previous = GuardianFocusSurface.CURBOX_CONTENT,
                next = GuardianFocusSurface.UNKNOWN
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

    @Test
    fun unsupportedOrTooFastStoredKdfIsRejectedEvenWhenVerifierMatches() {
        val salt = ByteArray(16) { it.toByte() }
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec("x".toCharArray(), salt, 1, GuardianPassword.KEY_BITS))
            .encoded
        val tampered = GuardianAuthConfig(
            passwordSalt = Base64.getEncoder().encodeToString(salt),
            passwordVerifier = Base64.getEncoder().encodeToString(derived),
            kdfAlgorithm = "PBKDF2WithHmacSHA1",
            kdfIterations = 1
        )

        assertFalse(GuardianPassword.verify("x", tampered))
        assertTrue(GuardianPassword.createCredential("x", iterations = 1).kdfIterations >= 120_000)
    }

    @Test
    fun internalNavigationUsesAOneShotTokenInsteadOfAnImplicitTimeGrace() {
        val token = GuardianSessionRegistry.issueInternalNavigationToken()

        assertTrue(GuardianSessionRegistry.consumeInternalNavigationToken(token))
        assertFalse(GuardianSessionRegistry.consumeInternalNavigationToken(token))
    }

    @Test
    fun pendingInternalNavigationSurvivesTheHostStopWithoutAGraceWindow() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))
        val token = GuardianSessionRegistry.issueInternalNavigationToken()

        GuardianSessionRegistry.markExternalSystemScreen()
        GuardianSessionRegistry.onCurboxActivityStopped()

        assertTrue(session.isAuthenticated(hasPassword = true))
        assertTrue(GuardianSessionRegistry.consumeInternalNavigationToken(token))
        session.clear()
    }

    @Test
    fun ownedChildReturnHandoffPreservesSessionAndParentConsumesIt() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))

        val forwardToken = GuardianSessionRegistry.issueInternalNavigationToken()
        assertTrue(GuardianSessionRegistry.onCurboxActivityStarted(forwardToken))
        GuardianSessionRegistry.onCurboxActivityStopped(isInternalActivity = false)
        assertTrue(session.isAuthenticated(hasPassword = true))

        val returnToken = GuardianSessionRegistry.issueInternalReturnToken()
        assertTrue(GuardianSessionRegistry.onCurboxActivityStopped(isInternalActivity = true, isFinishing = true))
        assertTrue(GuardianSessionRegistry.consumeInternalReturnToken(returnToken))
        assertTrue(session.isAuthenticated(hasPassword = true))
        session.clear()
    }

    @Test
    fun invalidReturnHandoffIsRejectedAndInvalidatesSession() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))
        GuardianSessionRegistry.issueInternalReturnToken()

        assertFalse(GuardianSessionRegistry.consumeInternalReturnToken("not-the-token"))
        assertFalse(session.isAuthenticated(hasPassword = true))
        session.clear()
    }

    @Test
    fun externalFocusLossInvalidatesAnUnconsumedReturnHandoff() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))
        GuardianSessionRegistry.issueInternalReturnToken()

        assertTrue(GuardianSessionRegistry.handleWindowFocusLost())
        assertFalse(session.isAuthenticated(hasPassword = true))
        session.clear()
    }

    @Test
    fun unexpectedFocusLossInvalidatesButAnExplicitOwnedTokenPreserves() {
        val session = GuardianSessionRegistry.session
        session.clear()
        assertTrue(session.authenticate("x", GuardianPassword.createCredential("x", iterations = 1)))

        val ownedToken = GuardianSessionRegistry.issueOwnedTransitionToken()
        assertFalse(GuardianSessionRegistry.handleWindowFocusLost(ownedToken))
        assertTrue(session.isAuthenticated(hasPassword = true))

        GuardianSessionRegistry.markOwnedDialogHidden()
        assertTrue(GuardianSessionRegistry.handleWindowFocusLost())
        assertFalse(session.isAuthenticated(hasPassword = true))
        session.clear()
    }
}
