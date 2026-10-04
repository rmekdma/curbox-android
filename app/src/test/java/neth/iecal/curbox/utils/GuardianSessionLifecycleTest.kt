package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuardianSessionLifecycleTest {

    @Before
    fun setUp() {
        GuardianSessionRegistry.resetForTest()
    }

    @Test
    fun sessionRemainsAuthenticatedDuringInternalNavigationBetweenActivities() {
        val session = GuardianSessionRegistry.session
        assertTrue(session.authenticate("pwd", GuardianPassword.createCredential("pwd", iterations = 1)))
        assertTrue(session.isAuthenticated(hasPassword = true))

        // Activity A starts
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        assertTrue(session.isAuthenticated(hasPassword = true))

        // Activity B starts (internal navigation: e.g. SelectAppsActivity opened)
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        // Activity A stops
        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)

        // Session must remain authenticated
        assertTrue(session.isAuthenticated(hasPassword = true))

        // Activity B finishes and stops, Activity A resumes and starts
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)

        assertTrue(session.isAuthenticated(hasPassword = true))
    }

    @Test
    fun sessionIsPreservedWhenLaunchingAndReturningFromOneShotSystemTask() {
        val session = GuardianSessionRegistry.session
        assertTrue(session.authenticate("pwd", GuardianPassword.createCredential("pwd", iterations = 1)))

        // Activity A is in foreground
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)

        // One-shot system task marked (e.g. barcode scanner, file picker, permission guide)
        GuardianSessionRegistry.markOneShotSystemResult()
        assertTrue(GuardianSessionRegistry.isAwaitingOneShotSystemResult())

        // Activity A stops because external task is shown (all Curbox activities stopped)
        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)

        // Session must still be preserved because one-shot system token is pending
        assertTrue(session.isAuthenticated(hasPassword = true))

        // System task completes and returns to Activity A
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        GuardianSessionRegistry.completeOneShotSystemResult()

        // Session is still authenticated
        assertTrue(session.isAuthenticated(hasPassword = true))
        assertFalse(GuardianSessionRegistry.isAwaitingOneShotSystemResult())
    }

    @Test
    fun sessionIsInvalidatedImmediatelyWhenCurboxTransitionsToBackgroundWithoutOneShotToken() {
        val session = GuardianSessionRegistry.session
        assertTrue(session.authenticate("pwd", GuardianPassword.createCredential("pwd", iterations = 1)))

        // Activity A started
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        assertTrue(session.isAuthenticated(hasPassword = true))

        // User presses Home or switches to another app (Activity A stops, count reaches 0)
        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)

        // Session must be invalidated immediately!
        assertFalse(session.isAuthenticated(hasPassword = true))
    }

    @Test
    fun externalAppActivityDoesNotPreventSessionInvalidationOnBackgrounding() {
        val session = GuardianSessionRegistry.session
        assertTrue(session.authenticate("pwd", GuardianPassword.createCredential("pwd", iterations = 1)))

        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        // External non-curbox activity starts/stops shouldn't increment curbox foreground count
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = false)
        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)

        assertFalse(session.isAuthenticated(hasPassword = true))
    }

    @Test
    fun openingCurboxFromLauncherAfterBackgroundingRequiresPassword() {
        val session = GuardianSessionRegistry.session
        assertTrue(session.authenticate("pwd", GuardianPassword.createCredential("pwd", iterations = 1)))

        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)

        // Backgrounded -> unauthenticated
        assertFalse(session.isAuthenticated(hasPassword = true))

        // Reopened from launcher
        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        assertFalse(session.isAuthenticated(hasPassword = true))

        // Authenticate again
        assertTrue(session.authenticate("pwd", GuardianPassword.createCredential("pwd", iterations = 1)))
        assertTrue(session.isAuthenticated(hasPassword = true))
    }

    @Test
    fun mainAppGrantAuthorizationFollowsManagementSessionAfterBackgrounding() {
        val session = GuardianSessionRegistry.session
        val credential = GuardianPassword.createCredential("pwd", iterations = 1)
        assertTrue(session.authenticate("pwd", credential))

        val current = Settings(
            guardianAuthConfig = credential,
            appRuleSnapshot = AppRuleSnapshot(appRules = listOf(AppRule(id = "rule")))
        )
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", 1_000L)!!

        GuardianSessionRegistry.onActivityStarted(isCurboxActivity = true)
        assertTrue(session.isAuthenticated(hasPassword = true))
        val authorized = GuardianExtraTimeGrantWrite.nextSettingsFromAuthenticatedSession(
            current = current,
            basis = basis,
            durationMinutes = 10L,
            grantedAtMs = 1_001L,
            sessionAuthenticated = session.isAuthenticated(hasPassword = true)
        )
        assertNotEquals(current, authorized)

        GuardianSessionRegistry.onActivityStopped(isCurboxActivity = true)
        assertFalse(session.isAuthenticated(hasPassword = true))
        val denied = GuardianExtraTimeGrantWrite.nextSettingsFromAuthenticatedSession(
            current = current,
            basis = basis,
            durationMinutes = 10L,
            grantedAtMs = 1_002L,
            sessionAuthenticated = session.isAuthenticated(hasPassword = true)
        )
        assertEquals(current, denied)
    }
}

