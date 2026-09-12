package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
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
        val beforeProgress = before.evaluations.single().conditionProgresses.single()
        assertEquals(8 * MINUTE, beforeProgress.currentMillis)
        assertEquals(10 * MINUTE, beforeProgress.requiredMillis)
        assertEquals(2 * MINUTE, beforeProgress.remainingShortfallMillis)
        assertFalse(beforeProgress.isMet)
        assertTrue(beforeProgress.isTotalCondition)

        assertTrue(after.isAllowed)
        assertEquals(10 * MINUTE, after.evaluations.single().allowanceMillis)
        val afterProgress = after.evaluations.single().conditionProgresses.single()
        assertEquals(10 * MINUTE, afterProgress.currentMillis)
        assertEquals(10 * MINUTE, afterProgress.requiredMillis)
        assertEquals(0L, afterProgress.remainingShortfallMillis)
        assertTrue(afterProgress.isMet)
        assertTrue(afterProgress.isTotalCondition)
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

    @Test
    fun totalOnlyConditionEnforcesOverallUsageAcrossContributorsWithoutPerGroupConstraints() {
        val edu1 = AppRuleAppGroup("group_edu_1", "Edu 1", listOf("com.edu.app1"))
        val edu2 = AppRuleAppGroup("group_edu_2", "Edu 2", listOf("com.edu.app2"))
        val rule = rule(allowedMinutes = 20).copy(
            usageConditionEnabled = true,
            usageConditionMinutes = 30L,
            contributorGroupIds = setOf(edu1.id, edu2.id),
            contributorGroupConditionMinutes = emptyMap()
        )
        val groups = listOf(target, edu1, edu2)

        // Total is 10 + 15 = 25m < 30m -> blocked
        val r1 = evaluate(rule, listOf(session("com.edu.app1", 10), session("com.edu.app2", 15)), groups)
        assertFalse(r1.isAllowed)
        assertEquals(0L, r1.evaluations.single().allowanceMillis)

        // Total is 20 + 15 = 35m >= 30m -> allowed
        val r2 = evaluate(rule, listOf(session("com.edu.app1", 20), session("com.edu.app2", 15)), groups)
        assertTrue(r2.isAllowed)
        assertEquals(20 * MINUTE, r2.evaluations.single().allowanceMillis)
    }

    @Test
    fun perGroupConditionEnforcesIndividualGroupThresholds() {
        val mathGroup = AppRuleAppGroup("math", "Math", listOf("com.math"))
        val readingGroup = AppRuleAppGroup("reading", "Reading", listOf("com.reading"))
        val rule = rule(allowedMinutes = 15).copy(
            contributorGroupIds = setOf(mathGroup.id, readingGroup.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 0L,
            contributorGroupConditionMinutes = mapOf(
                mathGroup.id to 10L,
                readingGroup.id to 20L
            )
        )
        val groups = listOf(target, mathGroup, readingGroup)

        // Only math satisfies
        val r1 = evaluate(rule, listOf(session("com.math", 10), session("com.reading", 15)), groups)
        assertFalse(r1.isAllowed)
        assertEquals(0L, r1.evaluations.single().allowanceMillis)

        // Only reading satisfies
        val r2 = evaluate(rule, listOf(session("com.math", 5), session("com.reading", 20)), groups)
        assertFalse(r2.isAllowed)
        assertEquals(0L, r2.evaluations.single().allowanceMillis)

        // Both satisfy
        val r3 = evaluate(rule, listOf(session("com.math", 10), session("com.reading", 20)), groups)
        assertTrue(r3.isAllowed)
        assertEquals(15 * MINUTE, r3.evaluations.single().allowanceMillis)
    }

    @Test
    fun combinedTotalAndPerGroupRequiresBothToSatisfy() {
        val mathGroup = AppRuleAppGroup("math", "Math", listOf("com.math"))
        val readingGroup = AppRuleAppGroup("reading", "Reading", listOf("com.reading"))
        val rule = rule(allowedMinutes = 20).copy(
            contributorGroupIds = setOf(mathGroup.id, readingGroup.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 35L,
            contributorGroupConditionMinutes = mapOf(
                mathGroup.id to 10L,
                readingGroup.id to 10L
            )
        )
        val groups = listOf(target, mathGroup, readingGroup)

        // Individual groups met (10 + 10 = 20), but total 20 < 35
        val r1 = evaluate(rule, listOf(session("com.math", 10), session("com.reading", 10)), groups)
        assertFalse(r1.isAllowed)
        assertEquals(0L, r1.evaluations.single().allowanceMillis)

        // Total met (35), but math is 5 < 10 (reading is 30)
        val r2 = evaluate(rule, listOf(session("com.math", 5), session("com.reading", 30)), groups)
        assertFalse(r2.isAllowed)
        assertEquals(0L, r2.evaluations.single().allowanceMillis)

        // Both individual and total met (math 15 >= 10, reading 20 >= 10, total 35 >= 35)
        val r3 = evaluate(rule, listOf(session("com.math", 15), session("com.reading", 20)), groups)
        assertTrue(r3.isAllowed)
        assertEquals(20 * MINUTE, r3.evaluations.single().allowanceMillis)
    }

    @Test
    fun evaluatesMultipleContributorGroupConditionsAndMissingGroupInConditionProgresses() {
        val mathGroup = AppRuleAppGroup("math", "Math", listOf("com.math"))
        val readingGroup = AppRuleAppGroup("reading", "Reading", listOf("com.reading"))
        val rule = rule(allowedMinutes = 20).copy(
            contributorGroupIds = setOf(mathGroup.id, readingGroup.id, "deleted_group"),
            usageConditionEnabled = true,
            usageConditionMinutes = 40L,
            contributorGroupConditionMinutes = mapOf(
                mathGroup.id to 15L,
                readingGroup.id to 20L,
                "deleted_group" to 10L
            )
        )
        // Only mathGroup and readingGroup are in snapshot (deleted_group is missing)
        val groups = listOf(target, mathGroup, readingGroup)

        // Math: 10m / 15m (shortfall 5m, unmet)
        // Reading: 25m / 20m (met)
        // Deleted group: 0m / 10m (shortfall 10m, unmet)
        // Total: 35m / 40m (shortfall 5m, unmet)
        val sessions = listOf(
            session("com.math", 10),
            session("com.reading", 25)
        )

        val result = evaluate(rule, sessions, groups)
        val eval = result.evaluations.single()
        assertFalse(eval.isAllowed)
        assertFalse(eval.isConditionMet)

        val progresses = eval.conditionProgresses
        // Expect 4 condition progresses: Total, Math, Reading, Deleted
        assertEquals(4, progresses.size)

        // 1. Total
        val totalCond = progresses[0]
        assertTrue(totalCond.isTotalCondition)
        assertEquals("total", totalCond.conditionId)
        assertEquals(35 * MINUTE, totalCond.currentMillis)
        assertEquals(40 * MINUTE, totalCond.requiredMillis)
        assertFalse(totalCond.isMet)
        assertEquals(5 * MINUTE, totalCond.remainingShortfallMillis)

        // 2. Math
        val mathCond = progresses[1]
        assertFalse(mathCond.isTotalCondition)
        assertEquals(mathGroup.id, mathCond.conditionId)
        assertEquals("Math", mathCond.conditionName)
        assertEquals(10 * MINUTE, mathCond.currentMillis)
        assertEquals(15 * MINUTE, mathCond.requiredMillis)
        assertFalse(mathCond.isMet)
        assertEquals(5 * MINUTE, mathCond.remainingShortfallMillis)

        // 3. Reading
        val readingCond = progresses[2]
        assertFalse(readingCond.isTotalCondition)
        assertEquals(readingGroup.id, readingCond.conditionId)
        assertEquals("Reading", readingCond.conditionName)
        assertEquals(25 * MINUTE, readingCond.currentMillis)
        assertEquals(20 * MINUTE, readingCond.requiredMillis)
        assertTrue(readingCond.isMet)
        assertEquals(0L, readingCond.remainingShortfallMillis)

        // 4. Deleted group
        val deletedCond = progresses[3]
        assertFalse(deletedCond.isTotalCondition)
        assertEquals("deleted_group", deletedCond.conditionId)
        assertEquals("", deletedCond.conditionName) // empty name for missing group, formatter falls back safely
        assertEquals(0L, deletedCond.currentMillis)
        assertEquals(10 * MINUTE, deletedCond.requiredMillis)
        assertFalse(deletedCond.isMet)
        assertEquals(10 * MINUTE, deletedCond.remainingShortfallMillis)
    }

    @Test
    fun unconstrainedConditionAllowsImmediateDirectAllowance() {
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 0L,
            contributorGroupConditionMinutes = mapOf(contributor.id to 0L)
        )

        val result = evaluate(rule, sessions = emptyList())
        assertTrue(result.isAllowed)
        assertEquals(10 * MINUTE, result.evaluations.single().allowanceMillis)
    }

    @Test
    fun earnedAllowanceWithPerGroupConditionUnlocksAndCreditsAllContributorTime() {
        val mathGroup = AppRuleAppGroup("math", "Math", listOf("com.math"))
        val readingGroup = AppRuleAppGroup("reading", "Reading", listOf("com.reading"))
        val rule = rule(allowedMinutes = 10).copy(
            contributorGroupIds = setOf(mathGroup.id, readingGroup.id),
            usageConditionEnabled = true,
            contributorGroupConditionMinutes = mapOf(
                mathGroup.id to 15L
            ),
            earnedAllowanceEnabled = true
        )
        val groups = listOf(target, mathGroup, readingGroup)

        // Before math meets condition (math 10, reading 20, total 30)
        val before = evaluate(rule, listOf(session("com.math", 10), session("com.reading", 20)), groups)
        assertFalse(before.isAllowed)
        assertEquals(0L, before.evaluations.single().allowanceMillis)
        assertEquals(0L, before.evaluations.single().earnedAllowanceMillis)

        // After math meets condition (math 15, reading 20, total 35) -> credits 10 direct + 35 earned = 45 mins
        val after = evaluate(rule, listOf(session("com.math", 15), session("com.reading", 20)), groups)
        assertTrue(after.isAllowed)
        assertEquals(35 * MINUTE, after.evaluations.single().earnedAllowanceMillis)
        assertEquals(45 * MINUTE, after.evaluations.single().allowanceMillis)
    }

    @Test
    fun allowanceExhaustedPreservedIndependentlyEvenWhenConditionIsUnmet() {
        val rule = rule(allowedMinutes = 30).copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 20,
            earnedAllowanceEnabled = false
        )

        // Target used 30m (exhausted), but contributor used only 10m (unmet)
        val sessionsExhaustedAndUnmet = listOf(
            session("com.target", 30),
            session("com.source", 10)
        )
        val result = evaluate(rule, sessionsExhaustedAndUnmet)
        val evaluation = result.evaluations.single()

        assertFalse(result.isAllowed)
        assertFalse(evaluation.isConditionMet)
        assertTrue(evaluation.isAllowanceExhausted)
        assertEquals(30 * MINUTE, evaluation.usedMillis)
        assertEquals(30 * MINUTE, evaluation.directAllowanceMillis)

        // Target used 15m (not exhausted), contributor used 10m (unmet)
        val sessionsNotExhaustedAndUnmet = listOf(
            session("com.target", 15),
            session("com.source", 10)
        )
        val result2 = evaluate(rule, sessionsNotExhaustedAndUnmet)
        val evaluation2 = result2.evaluations.single()

        assertFalse(result2.isAllowed)
        assertFalse(evaluation2.isConditionMet)
        assertFalse(evaluation2.isAllowanceExhausted)
    }

    @Test
    fun guardianGrantRelievesAllowanceExhaustedAndExposesConditionUnmet() {
        val rule = rule(allowedMinutes = 30).copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 20,
            earnedAllowanceEnabled = false
        )

        // Target used 30m (exhausted), contributor used 10m (unmet)
        val sessions = listOf(
            session("com.target", 30),
            session("com.source", 10)
        )

        // Before grant: exhausted and unmet
        val before = evaluate(rule, sessions)
        val beforeEval = before.evaluations.single()
        assertFalse(before.isAllowed)
        assertFalse(beforeEval.isConditionMet)
        assertTrue(beforeEval.isAllowanceExhausted)
        assertFalse(beforeEval.earnedAllowanceEnabled)
        assertEquals(30 * MINUTE, beforeEval.usedMillis)
        assertEquals(0L, beforeEval.guardianAllowanceMillis)

        // Guardian grants 15 minutes extra time
        val overrides = AppRuleOverrideState(
            useDayId = USE_DAY,
            grants = listOf(
                AppRuleGuardianGrant(
                    ruleId = rule.id,
                    useDayId = USE_DAY,
                    grantedAtMs = now - 5 * MINUTE,
                    grantedMillis = 15 * MINUTE
                )
            )
        )
        val after = evaluate(rule, sessions, overrides = overrides)
        val afterEval = after.evaluations.single()

        // After grant: total allowance is 45m (30 base + 15 grant), used is 30m -> no longer exhausted!
        // Grant provides usable remaining time (10m remaining out of 15m grant, since 5m was consumed after grant time)
        assertTrue(after.isAllowed)
        assertFalse(afterEval.isConditionMet)
        assertFalse(afterEval.isAllowanceExhausted)
        assertEquals(15 * MINUTE, afterEval.guardianAllowanceMillis)
        assertEquals(30 * MINUTE, afterEval.directAllowanceMillis)
        assertEquals(30 * MINUTE, afterEval.usedMillis)
        assertEquals(15 * MINUTE, afterEval.allowanceMillis)

        // Verifies the notification immediately exposes the unmet condition shortfall instead of time exhausted
        val item = LiveRuleNotificationStateCalculator.computeNotificationItems(
            snapshot = AppRuleSnapshot(listOf(target, contributor), listOf(rule)),
            sessions = sessions,
            useDayId = USE_DAY,
            nowMs = now,
            zone = zone,
            overrides = overrides
        ).single()
        assertFalse(item.isAllowanceExhausted)
        val formatted = LiveRuleNotificationFormatter.formatNotificationItem(
            item = item,
            unit = "분",
            totalName = "전체",
            unknownGroupName = "알 수 없는 앱 그룹"
        )
        assertTrue(formatted.contains("(10분/20분)"))
    }

    private fun evaluate(
        rule: AppRule,
        sessions: List<ForegroundSession>,
        groups: List<AppRuleAppGroup> = listOf(target, contributor),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): AppRulesEvaluation = AppRuleEvaluator.evaluate(
        snapshot = AppRuleSnapshot(groups, listOf(rule)),
        packageName = "com.target",
        useDayId = USE_DAY,
        sessions = sessions,
        nowMs = now,
        zone = zone,
        overrides = overrides
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
