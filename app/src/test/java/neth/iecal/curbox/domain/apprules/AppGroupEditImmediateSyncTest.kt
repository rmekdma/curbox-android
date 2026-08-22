package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppGroupEditMode
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

class AppGroupEditImmediateSyncTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
    private val useDayId = "2026-08-17"

    @Test
    fun addingPackageToTargetGroupWithNowModeImmediatelyBlocksNewPackage() {
        val initialGroup = AppRuleAppGroup.create("Social", listOf("com.social.one"))
        val rule = AppRule(
            id = "rule-social",
            name = "Block Social",
            weekdays = setOf(1), // Monday
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope.forGroup(initialGroup.id),
            allowedMinutes = 0L // 0 allowed means deny
        )
        val initialSnapshot = AppRuleSnapshot(listOf(initialGroup), listOf(rule))

        // Verify initial state: com.social.two is not in scope and therefore allowed
        val initialEvalTwo = AppRuleEvaluator.evaluate(
            snapshot = initialSnapshot,
            packageName = "com.social.two",
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = now,
            zone = zone
        )
        assertTrue("com.social.two should initially be allowed", initialEvalTwo.isAllowed)

        // User edits group: adds "com.social.two" with NOW mode at editMs
        val editMs = now
        val editedGroup = initialGroup.copy(selectedPackages = listOf("com.social.one", "com.social.two"))
        val updatedSnapshot = AppGroupMembershipTimeline.apply(
            previous = initialSnapshot,
            proposed = initialSnapshot.copy(appGroups = listOf(editedGroup)),
            mode = AppGroupEditMode.NOW,
            effectiveAtMs = editMs
        )

        // Verify that immediately at now (>= editMs), com.social.two is denied
        val updatedEvalTwo = AppRuleEvaluator.evaluate(
            snapshot = updatedSnapshot,
            packageName = "com.social.two",
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = now,
            zone = zone
        )
        assertFalse("com.social.two should immediately be denied after edit", updatedEvalTwo.isAllowed)
        assertEquals("rule-social", updatedEvalTwo.denyingRules.single().ruleId)
    }

    @Test
    fun removingPackageFromTargetGroupWithNowModeImmediatelyUnblocksRemovedPackage() {
        val initialGroup = AppRuleAppGroup.create("Games", listOf("com.game.one", "com.game.two"))
        val rule = AppRule(
            id = "rule-games",
            name = "Block Games",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope.forGroup(initialGroup.id),
            allowedMinutes = 0L
        )
        val initialSnapshot = AppRuleSnapshot(listOf(initialGroup), listOf(rule))

        // Verify initial state: com.game.two is denied
        val initialEval = AppRuleEvaluator.evaluate(
            snapshot = initialSnapshot,
            packageName = "com.game.two",
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = now,
            zone = zone
        )
        assertFalse("com.game.two should initially be denied", initialEval.isAllowed)

        // User edits group: removes "com.game.two" with NOW mode
        val editMs = now
        val editedGroup = initialGroup.copy(selectedPackages = listOf("com.game.one"))
        val updatedSnapshot = AppGroupMembershipTimeline.apply(
            previous = initialSnapshot,
            proposed = initialSnapshot.copy(appGroups = listOf(editedGroup)),
            mode = AppGroupEditMode.NOW,
            effectiveAtMs = editMs
        )

        // Verify that immediately at now (>= editMs), com.game.two is allowed
        val updatedEval = AppRuleEvaluator.evaluate(
            snapshot = updatedSnapshot,
            packageName = "com.game.two",
            useDayId = useDayId,
            sessions = emptyList(),
            nowMs = now,
            zone = zone
        )
        assertTrue("com.game.two should immediately be allowed after removal", updatedEval.isAllowed)
    }

    @Test
    fun addingContributorPackageImmediatelyUpdatesEarnedAllowanceCalculation() {
        val targetGroup = AppRuleAppGroup.create("Entertainment", listOf("com.video.app"))
        val initialStudyGroup = AppRuleAppGroup.create("Study", listOf("com.study.math"))
        val rule = AppRule(
            id = "rule-reward",
            name = "Study to unlock video",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope.forGroup(targetGroup.id),
            contributorGroupIds = setOf(initialStudyGroup.id),
            allowedMinutes = 0L,
            earnedAllowanceEnabled = true
        )
        val initialSnapshot = AppRuleSnapshot(listOf(targetGroup, initialStudyGroup), listOf(rule))

        // Prior usage in com.study.reading (which wasn't in Study group yet)
        val sessionReading = ForegroundSession(
            useDayId = useDayId,
            packageName = "com.study.reading",
            startedAtMs = now - 30 * 60_000L,
            endedAtMs = now
        )

        // Before adding to group, com.study.reading does not contribute
        val beforeEval = AppRuleEvaluator.evaluate(
            snapshot = initialSnapshot,
            packageName = "com.video.app",
            useDayId = useDayId,
            sessions = listOf(sessionReading),
            nowMs = now,
            zone = zone
        )
        assertEquals(0L, beforeEval.evaluations.single().earnedAllowanceMillis)
        assertFalse(beforeEval.isAllowed)

        // User adds "com.study.reading" to Study group with CURRENT_USE_DAY_START mode
        val editedGroup = initialStudyGroup.copy(selectedPackages = listOf("com.study.math", "com.study.reading"))
        val updatedSnapshot = initialSnapshot.copy(appGroups = listOf(targetGroup, editedGroup))

        // When evaluated with the updated snapshot, earned allowance is immediately reflected
        val afterEval = AppRuleEvaluator.evaluate(
            snapshot = updatedSnapshot,
            packageName = "com.video.app",
            useDayId = useDayId,
            sessions = listOf(sessionReading),
            nowMs = now,
            zone = zone
        )
        assertEquals(30 * 60_000L, afterEval.evaluations.single().earnedAllowanceMillis)
        assertTrue(afterEval.isAllowed)
    }
}
