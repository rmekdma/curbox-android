package neth.iecal.curbox.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncBoundaryTest {
    @Test
    fun guardianCredentialAndApprovalsAreRemovedFromUploadSnapshot() {
        val local = Settings(
            guardianAuthConfig = GuardianAuthConfig("salt", "verifier"),
            appRuleOverrideState = AppRuleOverrideState("2026-08-17")
        )

        val uploaded = SettingsSyncBoundary.forUpload(local)

        assertFalse(uploaded.guardianAuthConfig.isConfigured)
        assertEquals(AppRuleOverrideState(), uploaded.appRuleOverrideState)
        assertTrue(local.guardianAuthConfig.isConfigured)
        assertEquals("2026-08-17", local.appRuleOverrideState.useDayId)
    }
}
