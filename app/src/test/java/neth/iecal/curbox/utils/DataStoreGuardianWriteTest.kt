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
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun accumulatedGrantWriteResultRejectsRuleWithGuardianExtraTimeOff() {
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

    @Test
    fun grantWritesAreRejectedWhenRuleWasDeactivatedAfterApprovalOpened() {
        val activeRule = AppRule(id = "rule")
        val activeSnapshot = AppRuleSnapshot(appRules = listOf(activeRule))
        val latestInactiveSnapshot = activeSnapshot.copy(
            appRules = listOf(activeRule.copy(isActive = false))
        )
        val directGrant = AppRuleGuardianGrant("rule", "day", 100L, 300_000L)
        val accumulatedGrant = directGrant.copy(isFromAccumulatedPool = true)
        val latestInactive = Settings(
            appRuleSnapshot = latestInactiveSnapshot,
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = "day",
                grants = listOf(directGrant, accumulatedGrant)
            ),
            appRuleRolloverState = AppRuleRolloverState(
                pools = mapOf("rule" to RuleRolloverPool("rule", 15L, "day"))
            )
        )

        assertFalse(GuardianDataStoreWriteResult.ruleCanManageExtraTime(latestInactive, "rule"))
        val activeSettings = latestInactive.copy(appRuleSnapshot = activeSnapshot)
        val basis = GuardianExtraTimeGrantBasis.capture(activeSettings, "rule", 1_000L)!!
        assertEquals(
            latestInactive,
            GuardianExtraTimeGrantWrite.nextSettings(
                current = latestInactive,
                password = "",
                basis = basis,
                durationMinutes = 5L,
                grantedAtMs = 1_001L
            )
        )
        assertEquals(
            GuardianExtraTimeGrantWrite.Result.Unavailable,
            GuardianExtraTimeGrantWrite.resultFor(
                settings = latestInactive,
                basis = basis,
                durationMinutes = 5L,
                grantedAtMs = 1_001L
            )
        )
        assertFalse(
            GuardianDataStoreWriteResult.accumulatedGrantWasStored(
                settings = latestInactive,
                ruleId = "rule",
                useDayId = "day",
                grantedMillis = 300_000L,
                grantedAtMs = 100L,
                expectedRemainingPoolMinutes = 15L
            )
        )
    }

    @Test
    fun manualPoolEditIsRejectedWhenRuleBecomesInactiveBeforeSave() {
        val activeSettings = Settings(
            appRuleSnapshot = AppRuleSnapshot(appRules = listOf(AppRule(id = "rule"))),
            appRuleRolloverState = AppRuleRolloverState(
                pools = mapOf("rule" to RuleRolloverPool("rule", 15L, "day"))
            )
        )
        val inactiveSettings = activeSettings.copy(
            appRuleSnapshot = activeSettings.appRuleSnapshot.copy(
                appRules = listOf(AppRule(id = "rule", isActive = false))
            )
        )
        val disabledSettings = activeSettings.copy(
            appRuleSnapshot = activeSettings.appRuleSnapshot.copy(
                appRules = listOf(AppRule(id = "rule", guardianExtraTimeAllowed = false))
            )
        )

        val rejected = GuardianManualPoolWrite.nextState(
            current = inactiveSettings,
            basedOn = activeSettings,
            ruleId = "rule",
            accumulatedMinutes = 30L
        )
        val activeWrite = GuardianManualPoolWrite.nextState(
            current = activeSettings,
            basedOn = activeSettings,
            ruleId = "rule",
            accumulatedMinutes = 30L
        )
        val stillRejected = GuardianManualPoolWrite.nextState(
            current = inactiveSettings,
            basedOn = inactiveSettings,
            ruleId = "rule",
            accumulatedMinutes = 30L
        )
        val disabledRejected = GuardianManualPoolWrite.nextState(
            current = disabledSettings,
            basedOn = disabledSettings,
            ruleId = "rule",
            accumulatedMinutes = 30L
        )

        assertNull(rejected)
        assertNull(stillRejected)
        assertNull(disabledRejected)
        assertEquals(30L, activeWrite?.pools?.get("rule")?.accumulatedMinutes)
        assertEquals("day", activeWrite?.pools?.get("rule")?.lastSettledUseDayId)
        assertTrue(
            GuardianDataStoreWriteResult.manualRolloverPoolWasStored(
                settings = activeSettings.copy(appRuleRolloverState = activeWrite!!),
                ruleId = "rule",
                accumulatedMinutes = 30L,
                lastSettledUseDayId = "day"
            )
        )
        assertFalse(
            GuardianDataStoreWriteResult.manualRolloverPoolWasStored(
                settings = inactiveSettings.copy(
                    appRuleRolloverState = inactiveSettings.appRuleRolloverState.withPool(
                        RuleRolloverPool("rule", 30L, "day")
                    )
                ),
                ruleId = "rule",
                accumulatedMinutes = 30L,
                lastSettledUseDayId = "day"
            )
        )
    }
}
