package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.GuardianAuthConfig
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleEvaluator
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeRulePicker
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianExtraTimeGrantWriteTest {
    @Test
    fun basisCountsOnlyCurrentDayDirectGrantsIncludingAlreadyUsedTime() {
        val settings = settingsWithRuleAndGrants(
            AppRuleGuardianGrant("rule", useDayId, 100L, 15 * MINUTE),
            AppRuleGuardianGrant("rule", useDayId, 200L, 7 * MINUTE, true),
            AppRuleGuardianGrant("rule", priorUseDayId, 300L, 11 * MINUTE),
            AppRuleGuardianGrant("rule", useDayId, nowMs + 1L, 5 * MINUTE)
        )

        val basis = GuardianExtraTimeGrantBasis.capture(settings, "rule", nowMs)

        assertEquals(15 * MINUTE, basis?.currentTotalMillis)
    }

    @Test
    fun repeatedSubmissionWithOneCapturedBasisCanWriteOnlyOnce() {
        val current = settingsWithRuleAndGrants()
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", nowMs)!!
        val first = GuardianExtraTimeGrantWrite.nextSettings(
            current = current,
            password = "",
            basis = basis,
            durationMinutes = 10L,
            grantedAtMs = nowMs + 1L
        )
        val duplicate = GuardianExtraTimeGrantWrite.nextSettings(
            current = first,
            password = "",
            basis = basis,
            durationMinutes = 10L,
            grantedAtMs = nowMs + 2L
        )

        assertEquals(1, first.appRuleOverrideState.grants.size)
        assertEquals(first, duplicate)
        assertEquals(
            GuardianExtraTimeGrantWrite.Result.Stored,
            GuardianExtraTimeGrantWrite.resultFor(first, basis, 10L, nowMs + 1L)
        )
        assertEquals(
            GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation(basis.copy(
                currentTotalMillis = 10 * MINUTE
            )),
            GuardianExtraTimeGrantWrite.resultFor(duplicate, basis, 10L, nowMs + 2L)
        )
    }

    @Test
    fun changedUseDayGenerationOrDirectTotalRejectsTheWriteAndReturnsLatestBasis() {
        val current = settingsWithRuleAndGrants()
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", nowMs)!!
        val withConcurrentGrant = current.copy(
            appRuleOverrideState = current.appRuleOverrideState.copy(
                grants = listOf(AppRuleGuardianGrant("rule", basis.useDayId, nowMs + 1L, 3 * MINUTE))
            )
        )
        val writeAgainstChangedTotal = GuardianExtraTimeGrantWrite.nextSettings(
            withConcurrentGrant, "", basis, 10L, nowMs + 2L
        )
        val generationChanged = current.copy(useDayGenerationStartedAtMs = 10L)
        val writeAgainstChangedGeneration = GuardianExtraTimeGrantWrite.nextSettings(
            generationChanged, "", basis, 10L, nowMs + 2L
        )

        assertEquals(withConcurrentGrant, writeAgainstChangedTotal)
        assertEquals(generationChanged, writeAgainstChangedGeneration)
        assertEquals(
            GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation(
                GuardianExtraTimeGrantBasis.capture(withConcurrentGrant, "rule", nowMs + 2L)!!
            ),
            GuardianExtraTimeGrantWrite.resultFor(writeAgainstChangedTotal, basis, 10L, nowMs + 2L)
        )
        assertNotEquals(basis.useDayGenerationStartedAtMs, writeAgainstChangedGeneration.useDayGenerationStartedAtMs)
        assertEquals(emptyList<AppRuleGuardianGrant>(), writeAgainstChangedGeneration.appRuleOverrideState.grants)
    }

    @Test
    fun changedUseDayRejectsTheOldFormWithoutWriting() {
        val current = settingsWithRuleAndGrants()
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", nowMs)!!
        val afterReset = nowMs + 2L * 24L * 60L * MINUTE
        val rejected = GuardianExtraTimeGrantWrite.nextSettings(
            current = current,
            password = "",
            basis = basis,
            durationMinutes = 10L,
            grantedAtMs = afterReset
        )

        assertEquals(current, rejected)
        val result = GuardianExtraTimeGrantWrite.resultFor(
            settings = rejected,
            basis = basis,
            durationMinutes = 10L,
            grantedAtMs = afterReset
        ) as GuardianExtraTimeGrantWrite.Result.NeedsReconfirmation
        assertNotEquals(basis.useDayId, result.latestBasis.useDayId)
        assertEquals(0L, result.latestBasis.currentTotalMillis)
    }

    @Test
    fun deletedOrDisabledRuleCannotBeGranted() {
        val current = settingsWithRuleAndGrants()
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", nowMs)!!
        val deleted = current.copy(appRuleSnapshot = AppRuleSnapshot())
        val disabled = current.copy(
            appRuleSnapshot = AppRuleSnapshot(
                appRules = listOf(AppRule(id = "rule", guardianExtraTimeAllowed = false))
            )
        )

        assertEquals(deleted, GuardianExtraTimeGrantWrite.nextSettings(deleted, "", basis, 10L, nowMs + 1L))
        assertEquals(disabled, GuardianExtraTimeGrantWrite.nextSettings(disabled, "", basis, 10L, nowMs + 1L))
        assertNull(GuardianExtraTimeGrantBasis.capture(deleted, "rule", nowMs + 1L))
        assertNull(GuardianExtraTimeGrantBasis.capture(disabled, "rule", nowMs + 1L))
        assertFalse(GuardianExtraTimeGrantWrite.resultFor(deleted, basis, 10L, nowMs + 1L) is
            GuardianExtraTimeGrantWrite.Result.Stored)
    }

    @Test
    fun storedPasswordIsCheckedAgainAtTheGrantTransaction() {
        val current = settingsWithRuleAndGrants().copy(
            guardianAuthConfig = GuardianPassword.createCredential("guardian secret")
        )
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", nowMs)!!
        val rejected = GuardianExtraTimeGrantWrite.nextSettings(
            current, "wrong", basis, 10L, nowMs + 1L
        )
        val accepted = GuardianExtraTimeGrantWrite.nextSettings(
            current, "guardian secret", basis, 10L, nowMs + 2L
        )

        assertEquals(current, rejected)
        assertNotEquals(current, accepted)
        assertEquals(
            GuardianExtraTimeGrantWrite.Result.Stored,
            GuardianExtraTimeGrantWrite.resultFor(accepted, basis, 10L, nowMs + 2L)
        )
    }

    @Test
    fun grantingUsageRuleLeavesUnrelatedNightDenialInEffect() {
        val targetGroup = AppRuleAppGroup(
            id = "target",
            name = "Target apps",
            selectedPackages = listOf("example.target")
        )
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(targetGroup),
            appRules = listOf(
                AppRule(
                    id = "night",
                    name = "Night rule",
                    appGroupId = targetGroup.id,
                    allowedMinutes = 0L,
                    guardianExtraTimeAllowed = false
                ),
                AppRule(
                    id = "usage",
                    name = "Usage rule",
                    appGroupId = targetGroup.id,
                    allowedMinutes = 60L
                )
            )
        ).normalized()
        val current = settingsWithRuleAndGrants().copy(
            appRuleSnapshot = snapshot,
            appRuleOverrideState = AppRuleOverrideState(useDayId = useDayId)
        )
        val basis = GuardianExtraTimeGrantBasis.capture(current, "usage", nowMs)!!
        val updated = GuardianExtraTimeGrantWrite.nextSettings(
            current, "", basis, 10L, nowMs + 1L
        )
        val candidates = GuardianExtraTimeRulePicker.candidates(
            snapshot = snapshot,
            packageName = "example.target",
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = nowMs,
            resetTime = UseDayResetTime()
        )
        val evaluation = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "example.target",
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = nowMs,
            resetTime = UseDayResetTime(),
            overrides = updated.appRuleOverrideState
        )

        assertEquals(listOf("usage"), candidates.map(AppRule::id))
        assertEquals(listOf("night"), evaluation.denyingRules.map { it.ruleId })
        assertTrue(evaluation.evaluations.single { it.ruleId == "usage" }.isAllowed)
        assertEquals(
            GuardianExtraTimeGrantWrite.Result.Stored,
            GuardianExtraTimeGrantWrite.resultFor(updated, basis, 10L, nowMs + 1L)
        )
    }

    private fun settingsWithRuleAndGrants(
        vararg grants: AppRuleGuardianGrant
    ) = Settings(
        appRuleSnapshot = AppRuleSnapshot(appRules = listOf(AppRule(id = "rule"))),
        appRuleOverrideState = AppRuleOverrideState(
            useDayId = useDayId,
            grants = grants.toList()
        )
    )

    private companion object {
        const val MINUTE = 60_000L
        val nowMs = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
        val useDayId = ConfigurableUseDayCalculator().idAt(nowMs)
        val priorUseDayId = ConfigurableUseDayCalculator().idAt(nowMs - 86_400_000L)
    }
}
