package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleGuardianSkip
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.GuardianAuthConfig
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreGuardianWriteTest {
    @Test
    fun writeResultsAreDerivedFromTheFinalSettingsValue() {
        val credential = GuardianAuthConfig("salt", "verifier")
        val finalSettings = Settings(
            guardianAuthConfig = credential,
            appRuleSnapshot = AppRuleSnapshot(appRules = listOf(AppRule(id = "rule"))),
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

    @Test
    fun accumulatedGrantWriteResultVerifiesBothPoolDeductionAndGrantWithFlag() {
        val finalSettings = Settings(
            appRuleSnapshot = AppRuleSnapshot(appRules = listOf(AppRule(id = "rule"))),
            appRuleRolloverState = AppRuleRolloverState(
                pools = mapOf("rule" to RuleRolloverPool("rule", 15L, "day"))
            ),
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = "day",
                grants = listOf(
                    AppRuleGuardianGrant("rule", "day", 100L, 30 * 60_000L, isFromAccumulatedPool = true)
                )
            )
        )

        assertTrue(
            GuardianDataStoreWriteResult.accumulatedGrantWasStored(
                settings = finalSettings,
                ruleId = "rule",
                useDayId = "day",
                grantedMillis = 30 * 60_000L,
                grantedAtMs = 100L,
                expectedRemainingPoolMinutes = 15L
            )
        )
        assertFalse(
            GuardianDataStoreWriteResult.accumulatedGrantWasStored(
                settings = finalSettings,
                ruleId = "rule",
                useDayId = "day",
                grantedMillis = 30 * 60_000L,
                grantedAtMs = 100L,
                expectedRemainingPoolMinutes = 20L
            )
        )
        assertFalse(
            GuardianDataStoreWriteResult.accumulatedGrantWasStored(
                settings = finalSettings.copy(
                    appRuleOverrideState = AppRuleOverrideState(
                        useDayId = "day",
                        grants = listOf(
                            AppRuleGuardianGrant("rule", "day", 100L, 30 * 60_000L, isFromAccumulatedPool = false)
                        )
                    )
                ),
                ruleId = "rule",
                useDayId = "day",
                grantedMillis = 30 * 60_000L,
                grantedAtMs = 100L,
                expectedRemainingPoolMinutes = 15L
            )
        )
    }

    @Test
    fun turningGuardianExtraTimeOffClearsOnlyThatRulesGrantsAndPool() {
        val disabledRule = AppRule(id = "disabled", guardianExtraTimeAllowed = false)
        val otherRule = AppRule(id = "other")
        val skip = AppRuleGuardianSkip("disabled", "today", 900L, 100L)
        val original = Settings(
            appRuleSnapshot = AppRuleSnapshot(appRules = listOf(disabledRule, otherRule)),
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = "today",
                grants = listOf(
                    AppRuleGuardianGrant("disabled", "today", 100L, 300_000L),
                    AppRuleGuardianGrant("disabled", "today", 200L, 600_000L, true),
                    AppRuleGuardianGrant("other", "today", 300L, 900_000L)
                ),
                skips = listOf(skip)
            ),
            appRuleRolloverState = AppRuleRolloverState(
                pools = mapOf(
                    "disabled" to RuleRolloverPool("disabled", 25L, "yesterday"),
                    "other" to RuleRolloverPool("other", 40L, "yesterday")
                )
            )
        )

        val cleared = original.clearGuardianExtraTimeForRules(setOf("disabled"), "today")

        assertTrue(cleared.appRuleOverrideState.grants.none { it.ruleId == "disabled" })
        assertEquals(listOf("other"), cleared.appRuleOverrideState.grants.map { it.ruleId })
        assertEquals(listOf(skip), cleared.appRuleOverrideState.skips)
        assertEquals(0L, cleared.appRuleRolloverState.poolFor("disabled").accumulatedMinutes)
        assertEquals("today", cleared.appRuleRolloverState.poolFor("disabled").lastSettledUseDayId)
        assertEquals(
            original.appRuleRolloverState.poolFor("other"),
            cleared.appRuleRolloverState.poolFor("other")
        )
    }

    @Test
    fun grantWriteResultRejectsRuleWithGuardianExtraTimeOff() {
        val disabled = Settings(
            appRuleSnapshot = AppRuleSnapshot(
                appRules = listOf(AppRule(id = "rule", guardianExtraTimeAllowed = false))
            ),
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = "day",
                grants = listOf(AppRuleGuardianGrant("rule", "day", 100L, 300_000L))
            ),
            appRuleRolloverState = AppRuleRolloverState(
                pools = mapOf("rule" to RuleRolloverPool("rule", 15L, "day"))
            )
        )

        assertFalse(GuardianDataStoreWriteResult.grantWasStored(disabled, "rule", "day", 300_000L, 100L))
        assertFalse(
            GuardianDataStoreWriteResult.accumulatedGrantWasStored(
                settings = disabled,
                ruleId = "rule",
                useDayId = "day",
                grantedMillis = 30 * 60_000L,
                grantedAtMs = 100L,
                expectedRemainingPoolMinutes = 15L
            )
        )
    }
}
