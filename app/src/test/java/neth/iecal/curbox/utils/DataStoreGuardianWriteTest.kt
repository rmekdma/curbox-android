package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleGuardianSkip
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.GuardianAuthConfig
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreGuardianWriteTest {
    @Test
    fun writeResultsAreDerivedFromTheFinalSettingsValue() {
        val credential = GuardianAuthConfig("salt", "verifier")
        val finalSettings = Settings(
            guardianAuthConfig = credential,
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = "day",
                grants = listOf(AppRuleGuardianGrant("rule", "day", 100L, 300_000L)),
                skips = listOf(AppRuleGuardianSkip("other", "day", 900L, 100L))
            )
        )

        assertTrue(GuardianDataStoreWriteResult.passwordWasStored(finalSettings, credential))
        assertFalse(
            GuardianDataStoreWriteResult.passwordWasStored(
                finalSettings,
                GuardianAuthConfig("different", "verifier")
            )
        )
        assertTrue(
            GuardianDataStoreWriteResult.grantWasStored(
                finalSettings,
                "rule",
                "day",
                300_000L,
                100L
            )
        )
        assertFalse(
            GuardianDataStoreWriteResult.grantWasStored(
                finalSettings,
                "rule",
                "day",
                600_000L,
                100L
            )
        )
        assertTrue(
            GuardianDataStoreWriteResult.skipWasStored(
                finalSettings,
                "other",
                "day",
                100L,
                900L
            )
        )
        assertFalse(
            GuardianDataStoreWriteResult.skipWasStored(
                finalSettings,
                "rule",
                "day",
                100L,
                900L
            )
        )
    }
}
