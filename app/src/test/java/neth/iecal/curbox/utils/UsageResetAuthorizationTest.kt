package neth.iecal.curbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageResetAuthorizationTest {
    @Test
    fun `configured guardian requires the raw password for every reset action`() {
        val config = GuardianPassword.createCredential("correct horse", iterations = 120_000)

        assertFalse(UsageResetAuthorization.accepts(config, ""))
        assertFalse(UsageResetAuthorization.accepts(config, "wrong horse"))
        assertTrue(UsageResetAuthorization.accepts(config, "correct horse"))
        // There is intentionally no session parameter or cached authorization in this seam.
        assertFalse(UsageResetAuthorization.accepts(config, "authenticated session"))
    }

    @Test
    fun `reset is allowed without a configured guardian password`() {
        assertTrue(UsageResetAuthorization.accepts(
            config = neth.iecal.curbox.data.models.GuardianAuthConfig(),
            rawPassword = ""
        ))
    }
}
