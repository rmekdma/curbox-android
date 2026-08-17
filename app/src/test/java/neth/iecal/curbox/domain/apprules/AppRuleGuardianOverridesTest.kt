package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AppRuleGuardianOverridesTest {
    private val zone = ZoneId.of("UTC")
    private val group = AppRuleAppGroup("group", "Reader", listOf("com.example.reader"))
    private val rule = AppRule(
        id = "rule",
        name = "Reader",
        weekdays = setOf(1),
        startMinute = 9 * 60,
        endMinute = 17 * 60,
        appGroupId = group.id,
        allowedMinutes = 0
    )
    private val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()

    @Test
    fun guardianGrantAllowsAZeroAllowanceRuleForTheGrantedMinutes() {
        val state = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17", 10 * MINUTE,
            now
        )
        val result = evaluate(state)

        assertTrue(result.isAllowed)
        assertEquals(10 * MINUTE, result.evaluations.single().allowanceMillis)
        assertEquals(10 * MINUTE, result.evaluations.single().guardianRemainingMillis)
    }

    @Test
    fun guardianGrantIsConsumedOnlyByTargetForegroundTimeAfterItWasIssued() {
        val state = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17", 10 * MINUTE,
            now - 5 * MINUTE
        )
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(rule)),
            "com.example.reader",
            "2026-08-17",
            listOf(
                session(now - 20 * MINUTE, now - 5 * MINUTE),
                session(now - 5 * MINUTE, now)
            ),
            now,
            zone,
            overrides = state
        )

        assertTrue(result.isAllowed)
        assertEquals(5 * MINUTE, result.evaluations.single().guardianUsedMillis)
        assertEquals(5 * MINUTE, result.evaluations.single().guardianRemainingMillis)
    }

    @Test
    fun aFutureDatedGrantCannotPreAuthorizeCurrentForeground() {
        val state = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17", 10 * MINUTE,
            now + MINUTE
        )
        val result = evaluate(state)

        assertFalse(result.isAllowed)
        assertEquals(0L, result.evaluations.single().guardianAllowanceMillis)
    }

    @Test
    fun skipMakesOnlyTheSelectedRuleAllowedAndDoesNotHideAnotherDenial() {
        val skipped = AppRuleGuardianOverrides.skipUntil(
            AppRuleOverrideState("2026-08-17"),
            "rule",
            "2026-08-17",
            now + 20 * MINUTE,
            now + 60 * MINUTE,
            now
        )
        val other = rule.copy(id = "other", allowedMinutes = 0)
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(rule, other)),
            "com.example.reader",
            "2026-08-17",
            emptyList(),
            now,
            zone,
            overrides = skipped
        )

        assertFalse(result.isAllowed)
        assertEquals(listOf("other"), result.denyingRules.map { it.ruleId })
        assertTrue(result.evaluations.first { it.ruleId == "rule" }.isSkipped)
        assertEquals(0L, result.evaluations.first { it.ruleId == "rule" }.usedMillis)
    }

    @Test
    fun approvalsFromAnOlderUseDayAreIgnored() {
        val old = AppRuleOverrideState("2026-08-16").copy(
            grants = listOf(
                neth.iecal.curbox.data.models.AppRuleGuardianGrant(
                    "rule", "2026-08-16", now - MINUTE, 60 * MINUTE
                )
            )
        )
        val result = evaluate(old)

        assertFalse(result.isAllowed)
        assertEquals(0L, result.evaluations.single().guardianAllowanceMillis)
    }

    @Test
    fun guardianGrantBypassesAnUnmetConditionWithoutEnablingEarnedAllowance() {
        val conditional = rule.copy(
            usageConditionEnabled = true,
            usageConditionMinutes = 30
        )
        val state = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17", 5 * MINUTE, now
        )
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(conditional)),
            "com.example.reader", "2026-08-17", emptyList(), now, zone,
            overrides = state
        )

        assertTrue(result.isAllowed)
        assertEquals(0L, result.evaluations.single().earnedAllowanceMillis)
        assertEquals(5 * MINUTE, result.evaluations.single().guardianAllowanceMillis)
    }

    @Test
    fun skipIsCappedAtTheNextUseDayReset() {
        val reset = now + 60 * MINUTE
        val state = AppRuleGuardianOverrides.skipUntil(
            AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17",
            selectedUntilMs = reset + 10 * MINUTE,
            nextResetAtMs = reset,
            nowMs = now
        )

        assertEquals(reset, state.skips.single().skipUntilMs)
    }

    @Test
    fun inactiveWindowDoesNotSpendAProactiveGrant() {
        val state = AppRuleGuardianOverrides.grant(
            AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17", 10 * MINUTE,
            now - 2 * 60 * MINUTE
        )
        val outsideWindow = now + 8 * 60 * MINUTE
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(rule)),
            "com.example.reader", "2026-08-17", emptyList(), outsideWindow, zone,
            overrides = state
        )

        assertTrue(result.evaluations.single().isActive.not())
        assertEquals(10 * MINUTE, result.evaluations.single().guardianRemainingMillis)
    }

    @Test
    fun twoRulesKeepIndependentGuardianPoolsForTheSameVisibleMinute() {
        val second = rule.copy(id = "second")
        val state = AppRuleGuardianOverrides.grant(
            AppRuleGuardianOverrides.grant(
                AppRuleOverrideState("2026-08-17"), "rule", "2026-08-17", 5 * MINUTE, now
            ),
            "second", "2026-08-17", 5 * MINUTE, now
        )
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(rule, second)),
            "com.example.reader", "2026-08-17", emptyList(), now, zone,
            overrides = state
        )

        assertTrue(result.isAllowed)
        assertEquals(listOf(5 * MINUTE, 5 * MINUTE), result.evaluations.map { it.guardianRemainingMillis })
    }

    private fun evaluate(state: AppRuleOverrideState) = AppRuleEvaluator.evaluate(
        AppRuleSnapshot(listOf(group), listOf(rule)),
        "com.example.reader",
        "2026-08-17",
        emptyList<ForegroundSession>(),
        now,
        zone,
        overrides = state
    )

    private fun session(start: Long, end: Long) = ForegroundSession(
        useDayId = "2026-08-17",
        packageName = "com.example.reader",
        startedAtMs = start,
        endedAtMs = end
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
