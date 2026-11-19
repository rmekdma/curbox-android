package neth.iecal.curbox.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncBoundaryTest {
    @Test fun todoAwardStateStaysOnDevice() {
        val local = Settings(
            todoAwardConnectionRevision = "local-revision",
            todoAwardConnection = TodoAwardConnection("account", "Child", "rule", "2026-11-05", "link"),
            todoAwardLedger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-05", 40)),
            todoPendingSettlements = listOf(TodoPendingSettlement(TodoAwardConnection("account", "Child", "rule", "2026-11-05", "link"), "2026-11-05"))
        )
        val uploaded = SettingsSyncBoundary.forUpload(local)
        assertEquals(null, uploaded.todoAwardConnection)
        assertEquals("", uploaded.todoAwardConnectionRevision)
        assertTrue(uploaded.todoAwardLedger.isEmpty())
        assertTrue(uploaded.todoPendingSettlements.isEmpty())
    }
    @Test
    fun guardianCredentialAndApprovalsAreRemovedFromUploadSnapshot() {
        val local = Settings(
            useDayGenerationStartedAtMs = 42L,
            guardianAuthConfig = GuardianAuthConfig("salt", "verifier"),
            appRuleOverrideState = AppRuleOverrideState("2026-08-17")
        )

        val uploaded = SettingsSyncBoundary.forUpload(local)

        assertFalse(uploaded.guardianAuthConfig.isConfigured)
        assertEquals(0L, uploaded.useDayGenerationStartedAtMs)
        assertEquals(AppRuleOverrideState(), uploaded.appRuleOverrideState)
        assertTrue(local.guardianAuthConfig.isConfigured)
        assertEquals("2026-08-17", local.appRuleOverrideState.useDayId)
    }
}
