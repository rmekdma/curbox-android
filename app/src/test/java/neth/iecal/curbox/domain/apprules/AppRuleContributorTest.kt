package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AppRuleContributorTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
    private val target = AppRuleAppGroup("target", "Target", listOf("com.target"))
    private val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
    private val overlappingContributor = AppRuleAppGroup(
        "overlap",
        "Overlap",
        listOf("com.source", "com.other")
    )

    @Test
    fun noOptionsUseOnlyTheDirectAllowance() {
        val result = evaluate(rule(allowedMinutes = 10), sessions = listOf(sourceSession(8)))

        assertTrue(result.isAllowed)
        assertEquals(10 * MINUTE, result.evaluations.single().allowanceMillis)
        assertEquals(0L, result.evaluations.single().earnedAllowanceMillis)
    }

    @Test
    fun conditionOnlyUnlocksTheDirectAllowanceAfterTheThreshold() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 10
        )

        val before = evaluate(rule, sessions = listOf(sourceSession(8)))
        val after = evaluate(rule, sessions = listOf(sourceSession(10)))

        assertFalse(before.isAllowed)
        assertEquals(8 * MINUTE, before.evaluations.single().contributorUsageMillis)
        assertEquals(0L, before.evaluations.single().allowanceMillis)
        assertTrue(after.isAllowed)
        assertEquals(10 * MINUTE, after.evaluations.single().allowanceMillis)
    }

    @Test
    fun earningOnlyAddsAllContributorUsageWithoutADailyCap() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )

        val result = evaluate(rule, sessions = listOf(sourceSession(90)))

        assertTrue(result.isAllowed)
        assertEquals(90 * MINUTE, result.evaluations.single().earnedAllowanceMillis)
        assertEquals(100 * MINUTE, result.evaluations.single().allowanceMillis)
    }

    @Test
    fun conditionAndEarningRemainIndependentButEarningStartsAfterTheCondition() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 10,
            earnedAllowanceEnabled = true
        )

        val before = evaluate(rule, sessions = listOf(sourceSession(8)))
        val after = evaluate(rule, sessions = listOf(sourceSession(10)))

        assertFalse(before.isAllowed)
        assertEquals(0L, before.evaluations.single().allowanceMillis)
        assertTrue(after.isAllowed)
        assertEquals(20 * MINUTE, after.evaluations.single().allowanceMillis)
    }

    @Test
    fun overlappingContributorGroupsCountEachRawPackageOnlyOnce() {
        val rule = rule(allowedMinutes = 1).copy(
            contributorGroupIds = setOf(contributor.id, overlappingContributor.id),
            earnedAllowanceEnabled = true
        )
        val sessions = listOf(sourceSession(3), session("com.other", 2))

        val result = evaluate(
            rule,
            sessions = sessions,
            groups = listOf(target, contributor, overlappingContributor)
        )

        assertEquals(5 * MINUTE, result.evaluations.single().contributorUsageMillis)
    }

    @Test
    fun contributorUsageUsesTheWholeUseDayOutsideTheTargetWindow() {
        val rule = rule(allowedMinutes = 1).copy(
            startMinute = 12 * 60,
            endMinute = 13 * 60,
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )
        val source = ForegroundSession(
            useDayId = USE_DAY,
            packageName = "com.source",
            startedAtMs = Instant.parse("2026-08-17T08:00:00Z").toEpochMilli(),
            endedAtMs = Instant.parse("2026-08-17T08:10:00Z").toEpochMilli()
        )

        val result = evaluate(rule, sessions = listOf(source))

        assertTrue(result.evaluations.single().isActive.not())
        assertEquals(10 * MINUTE, result.evaluations.single().contributorUsageMillis)
        assertEquals(11 * MINUTE, result.evaluations.single().allowanceMillis)
    }

    @Test
    fun sharedContributorUsageIsCalculatedIndependentlyForEachRule() {
        val first = rule(id = "first", allowedMinutes = 1).copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )
        val second = rule(id = "second", allowedMinutes = 1).copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )

        val result = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(listOf(target, contributor), listOf(first, second)),
            packageName = "com.target",
            useDayId = USE_DAY,
            sessions = listOf(sourceSession(7)),
            nowMs = now,
            zone = zone
        )

        assertEquals(listOf(8 * MINUTE, 8 * MINUTE), result.evaluations.map { it.allowanceMillis })
    }

    @Test
    fun missingContributorGroupFailsClosedForItsKnownTargetOnly() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf("deleted"),
            earnedAllowanceEnabled = true
        )

        val result = evaluate(rule, sessions = emptyList())

        assertFalse(result.isAllowed)
        assertFalse(result.evaluations.single().isAllowed)
        assertTrue(result.evaluations.single().validationErrors.isNotEmpty())
    }

    @Test
    fun targetAndContributorOverlapDoesNotCreateRuleRecursion() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(target.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 1,
            earnedAllowanceEnabled = true
        )

        val result = evaluate(rule, sessions = listOf(session("com.target", 2)))

        assertTrue(result.isAllowed)
        assertEquals(12 * MINUTE, result.evaluations.single().allowanceMillis)
    }

    @Test
    fun snapshotRuleEvaluationExposesTheCurrentUseDayBreakdownForCards() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 5,
            earnedAllowanceEnabled = true
        )
        val evaluation = AppRuleEvaluator.evaluateRuleForSnapshot(
            snapshot = AppRuleSnapshot(listOf(target, contributor), listOf(rule)),
            rule = rule,
            useDayId = USE_DAY,
            sessions = listOf(sourceSession(8), session("com.target", 3)),
            nowMs = now,
            zone = zone
        )

        assertEquals(8 * MINUTE, evaluation.contributorUsageMillis)
        assertEquals(5 * MINUTE, evaluation.conditionRequiredMillis)
        assertEquals(8 * MINUTE, evaluation.earnedAllowanceMillis)
        assertEquals(10 * MINUTE, evaluation.directAllowanceMillis)
        assertEquals(15 * MINUTE, evaluation.remainingMillis)
    }

    private fun evaluate(
        rule: AppRule,
        sessions: List<ForegroundSession>,
        groups: List<AppRuleAppGroup> = listOf(target, contributor)
    ): AppRulesEvaluation = AppRuleEvaluator.evaluate(
        snapshot = AppRuleSnapshot(groups, listOf(rule)),
        packageName = "com.target",
        useDayId = USE_DAY,
        sessions = sessions,
        nowMs = now,
        zone = zone
    )

    private fun rule(
        id: String = "rule",
        allowedMinutes: Long = 30,
        startMinute: Int = 9 * 60,
        endMinute: Int = 17 * 60
    ) = AppRule(
        id = id,
        name = id,
        weekdays = setOf(1),
        startMinute = startMinute,
        endMinute = endMinute,
        scope = AppRuleScope(includedGroupIds = setOf(target.id)),
        allowedMinutes = allowedMinutes
    )

    private fun sourceSession(minutes: Long): ForegroundSession = session("com.source", minutes)

    private fun session(packageName: String, minutes: Long): ForegroundSession = ForegroundSession(
        useDayId = USE_DAY,
        packageName = packageName,
        startedAtMs = now - minutes * MINUTE,
        endedAtMs = now
    )

    private companion object {
        const val USE_DAY = "2026-08-17"
        const val MINUTE = 60_000L
    }
}
