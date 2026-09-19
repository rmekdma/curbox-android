package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * End-to-end multi-day lifecycle integration test for guardian extra time rollover:
 * 평일 적립 ➡️ 주말 미승인 잠금 ➡️ 주말 누적 승인 및 소비 ➡️ 22시 취침 차단 ➡️ 일요일 연속 해금 ➡️ 월요일 소멸
 */
class AppRuleRolloverLifecycleE2ETest {

    private val zone = ZoneId.of("UTC")
    private val resetTime = UseDayResetTime(4, 0) // 04:00 AM restore boundary
    private val calculator = ConfigurableUseDayCalculator(zone, resetTime)

    @Test
    fun fullLifecycle_weekdayAccrual_weekendApproval_bedtimeLock_mondayExpiration() {
        val appGroup = AppRuleAppGroup.create("Entertainment", listOf("com.game.app"))
        val gameRule = AppRule(
            id = "game-rule",
            name = "Games",
            weekdays = (0..6).toSet(),
            startMinute = 9 * 60, // 09:00
            endMinute = 24 * 60, // Midnight
            scope = AppRuleScope.forGroup(appGroup.id),
            allowedMinutes = 60L,
            rolloverEnabled = true,
            unlockDays = setOf(0, 6) // Sunday=0, Saturday=6
        )
        val bedtimeRule = AppRule(
            id = "bedtime-lockdown",
            name = "Bedtime Lockdown",
            weekdays = (0..6).toSet(),
            startMinute = 22 * 60, // 22:00
            endMinute = 7 * 60,  // 07:00
            scope = AppRuleScope(includeAllApps = true),
            allowedMinutes = 0L
        )
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(appGroup),
            appRules = listOf(gameRule, bedtimeRule)
        )

        // -----------------------------------------------------------------------------------------
        // 1. 평일 적립 (Friday 2026-08-21: weekday 5 -> ACCRUAL day)
        // -----------------------------------------------------------------------------------------
        val fridayUseDayId = "2026-08-21"
        val fridayGrantTime = Instant.parse("2026-08-21T10:00:00Z").toEpochMilli()

        // Guardian grants 40 minutes of extra daily time on Friday
        val fridayGrant = AppRuleGuardianGrant(
            ruleId = gameRule.id,
            useDayId = fridayUseDayId,
            grantedAtMs = fridayGrantTime,
            grantedMillis = 40 * 60_000L,
            isFromAccumulatedPool = false
        )
        val fridayOverrides = AppRuleOverrideState(
            useDayId = fridayUseDayId,
            grants = listOf(fridayGrant)
        )

        // Kid uses 60 minutes (base allowance) + 15 minutes of guardian extra time = 75 minutes total
        val fridaySessions = listOf(
            ForegroundSession(
                useDayId = fridayUseDayId,
                packageName = "com.game.app",
                startedAtMs = fridayGrantTime + 60_000L,
                endedAtMs = fridayGrantTime + 60_000L + 75 * 60_000L
            )
        )

        // Verify Friday unused extra time calculation: 40m - 15m = 25m unused
        val fridayUnused = AppRuleEvaluator.computeUnusedGuardianMinutes(
            rule = gameRule,
            snapshot = snapshot,
            useDayId = fridayUseDayId,
            sessions = fridaySessions,
            overrides = fridayOverrides,
            zone = zone,
            resetTime = resetTime,
            availablePackages = setOf("com.game.app")
        )
        assertEquals(25L, fridayUnused)

        // Friday night -> Saturday 04:00 AM restore boundary settlement
        val fridayEndingRole = AppRuleRolloverPolicy.dayRoleFor(5, gameRule.unlockDays)
        val saturdayStartingRole = AppRuleRolloverPolicy.dayRoleFor(6, gameRule.unlockDays)
        assertEquals(AppRuleDayRole.ACCRUAL, fridayEndingRole)
        assertEquals(AppRuleDayRole.UNLOCK, saturdayStartingRole)

        val saturdaySettlement = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = 0L,
            endingDayRole = fridayEndingRole,
            nextDayRole = saturdayStartingRole,
            unusedGrantMinutes = fridayUnused
        )
        assertEquals(25L, saturdaySettlement.accumulatedMinutes)
        assertEquals(25L, saturdaySettlement.addedMinutes)
        assertEquals(0L, saturdaySettlement.expiredMinutes)

        var rolloverState = AppRuleRolloverState(
            pools = mapOf(
                gameRule.id to RuleRolloverPool(
                    ruleId = gameRule.id,
                    accumulatedMinutes = saturdaySettlement.accumulatedMinutes,
                    lastSettledUseDayId = "2026-08-22"
                )
            )
        )

        // -----------------------------------------------------------------------------------------
        // 2. 주말 미승인 잠금 유지 (Saturday 2026-08-22: weekday 6 -> UNLOCK day)
        // -----------------------------------------------------------------------------------------
        val saturdayUseDayId = "2026-08-22"
        val saturdayAfternoon = Instant.parse("2026-08-22T14:00:00Z").toEpochMilli()

        // Kid uses all 60 minutes of Saturday's base allowance
        val saturdayBaseSessions = listOf(
            ForegroundSession(
                useDayId = saturdayUseDayId,
                packageName = "com.game.app",
                startedAtMs = saturdayAfternoon - 60 * 60_000L,
                endedAtMs = saturdayAfternoon
            )
        )

        // Saturday overrides initially has NO approved grants
        var saturdayOverrides = AppRuleOverrideState(useDayId = saturdayUseDayId)

        // Evaluate: Unapproved accumulated pool (25m) must NOT count toward allowance; app is locked
        val satEvalLocked = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.game.app",
            useDayId = saturdayUseDayId,
            sessions = saturdayBaseSessions,
            nowMs = saturdayAfternoon,
            zone = zone,
            useDayCalculator = calculator,
            overrides = saturdayOverrides,
            availablePackages = setOf("com.game.app")
        )
        assertFalse(satEvalLocked.isAllowed)
        assertTrue(satEvalLocked.evaluations.first { it.ruleId == gameRule.id }.isAllowanceExhausted)

        // Notification shows time exhausted (60/60)
        val satNotifItemsLocked = LiveRuleNotificationStateCalculator.computeNotificationItems(
            snapshot = snapshot,
            sessions = saturdayBaseSessions,
            useDayId = saturdayUseDayId,
            nowMs = saturdayAfternoon,
            zone = zone,
            useDayCalculator = calculator,
            overrides = saturdayOverrides,
            availablePackages = setOf("com.game.app")
        )
        val satGameNotifLocked = satNotifItemsLocked.first { it.ruleId == gameRule.id }
        assertTrue(satGameNotifLocked.isAllowanceExhausted)
        assertEquals(60L, satGameNotifLocked.totalAllowedMinutes)
        assertEquals(0L, satGameNotifLocked.guardianExtraMinutes)

        // -----------------------------------------------------------------------------------------
        // 3. 주말 누적 승인 및 소비 (Saturday 2026-08-22 15:00)
        // -----------------------------------------------------------------------------------------
        val satApprovalTime = Instant.parse("2026-08-22T15:00:00Z").toEpochMilli()
        val approvedMinutes = 20L // Approve 20 minutes out of 25 minutes accumulated

        // Atomic grant transaction: deduct from pool, add grant tagged isFromAccumulatedPool = true
        val currentPool = rolloverState.pools.getValue(gameRule.id)
        val updatedPool = currentPool.copy(accumulatedMinutes = currentPool.accumulatedMinutes - approvedMinutes)
        assertEquals(5L, updatedPool.accumulatedMinutes) // 5 minutes remain in pool
        rolloverState = rolloverState.withPool(updatedPool)

        saturdayOverrides = AppRuleGuardianOverrides.grant(
            saturdayOverrides,
            ruleId = gameRule.id,
            useDayId = saturdayUseDayId,
            grantedMillis = approvedMinutes * 60_000L,
            grantedAtMs = satApprovalTime,
            isFromAccumulatedPool = true
        )

        // Evaluate immediately after grant: app is unlocked with 20 minutes allowed
        val satEvalUnlocked = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.game.app",
            useDayId = saturdayUseDayId,
            sessions = saturdayBaseSessions,
            nowMs = satApprovalTime + 1000L,
            zone = zone,
            useDayCalculator = calculator,
            overrides = saturdayOverrides,
            availablePackages = setOf("com.game.app")
        )
        assertTrue(satEvalUnlocked.isAllowed)
        val gameEvalUnlocked = satEvalUnlocked.evaluations.first { it.ruleId == gameRule.id }
        assertEquals(20 * 60_000L, gameEvalUnlocked.remainingMillis)
        assertEquals(20 * 60_000L, gameEvalUnlocked.guardianRemainingMillis)

        // Notification reflects total 80 min (60 base + 20 extra)
        val satNotifItemsUnlocked = LiveRuleNotificationStateCalculator.computeNotificationItems(
            snapshot = snapshot,
            sessions = saturdayBaseSessions,
            useDayId = saturdayUseDayId,
            nowMs = satApprovalTime + 1000L,
            zone = zone,
            useDayCalculator = calculator,
            overrides = saturdayOverrides,
            availablePackages = setOf("com.game.app")
        )
        val satGameNotifUnlocked = satNotifItemsUnlocked.first { it.ruleId == gameRule.id }
        assertEquals(80L, satGameNotifUnlocked.totalAllowedMinutes)
        assertEquals(20L, satGameNotifUnlocked.guardianExtraMinutes)
        assertFalse(satGameNotifUnlocked.isAllowanceExhausted)

        // Kid uses 10 minutes of the approved accumulated time (leaves 10 minutes)
        val satExtraSession = ForegroundSession(
            useDayId = saturdayUseDayId,
            packageName = "com.game.app",
            startedAtMs = satApprovalTime + 2000L,
            endedAtMs = satApprovalTime + 2000L + 10 * 60_000L
        )
        val saturdayAllSessions = saturdayBaseSessions + satExtraSession

        // -----------------------------------------------------------------------------------------
        // 4. 22시 취침 잠금 엄격 차단 (Saturday 2026-08-22 22:05)
        // -----------------------------------------------------------------------------------------
        val satBedtime = Instant.parse("2026-08-22T22:05:00Z").toEpochMilli()

        // Even though gameRule has 10 minutes of approved extra time remaining, bedtimeRule is active and blocks!
        val satEvalBedtime = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.game.app",
            useDayId = saturdayUseDayId,
            sessions = saturdayAllSessions,
            nowMs = satBedtime,
            zone = zone,
            useDayCalculator = calculator,
            overrides = saturdayOverrides,
            availablePackages = setOf("com.game.app")
        )
        assertFalse(satEvalBedtime.isAllowed)
        assertEquals(listOf("bedtime-lockdown"), satEvalBedtime.denyingRules.map { it.ruleId })

        // -----------------------------------------------------------------------------------------
        // 5. 일요일 연속 해금일 이월 (Sunday 2026-08-23 04:00)
        // -----------------------------------------------------------------------------------------
        // Unused Saturday extra time = 10 minutes
        val satUnused = AppRuleEvaluator.computeUnusedGuardianMinutes(
            rule = gameRule,
            snapshot = snapshot,
            useDayId = saturdayUseDayId,
            sessions = saturdayAllSessions,
            overrides = saturdayOverrides,
            zone = zone,
            resetTime = resetTime,
            availablePackages = setOf("com.game.app")
        )
        assertEquals(10L, satUnused)

        val saturdayEndingRole = AppRuleRolloverPolicy.dayRoleFor(6, gameRule.unlockDays)
        val sundayStartingRole = AppRuleRolloverPolicy.dayRoleFor(0, gameRule.unlockDays)
        assertEquals(AppRuleDayRole.UNLOCK, saturdayEndingRole)
        assertEquals(AppRuleDayRole.UNLOCK, sundayStartingRole)

        // Consecutive unlock days consolidate pool: 5m left in pool + 10m unused = 15m pool for Sunday
        val sundaySettlement = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = rolloverState.pools.getValue(gameRule.id).accumulatedMinutes,
            endingDayRole = saturdayEndingRole,
            nextDayRole = sundayStartingRole,
            unusedGrantMinutes = satUnused
        )
        assertEquals(15L, sundaySettlement.accumulatedMinutes)
        assertEquals(10L, sundaySettlement.addedMinutes)
        assertEquals(0L, sundaySettlement.expiredMinutes)

        rolloverState = rolloverState.withPool(
            RuleRolloverPool(
                ruleId = gameRule.id,
                accumulatedMinutes = sundaySettlement.accumulatedMinutes,
                lastSettledUseDayId = "2026-08-23"
            )
        )

        // -----------------------------------------------------------------------------------------
        // 6. 월요일 소멸 (Monday 2026-08-24 04:00)
        // -----------------------------------------------------------------------------------------
        // Sunday ends (UNLOCK), Monday begins (ACCRUAL)
        val sundayEndingRole = AppRuleRolloverPolicy.dayRoleFor(0, gameRule.unlockDays)
        val mondayStartingRole = AppRuleRolloverPolicy.dayRoleFor(1, gameRule.unlockDays)
        assertEquals(AppRuleDayRole.UNLOCK, sundayEndingRole)
        assertEquals(AppRuleDayRole.ACCRUAL, mondayStartingRole)

        // Transition from unlock block to accrual day resets the entire pool to 0 (EXPIRE)
        val mondaySettlement = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = rolloverState.pools.getValue(gameRule.id).accumulatedMinutes,
            endingDayRole = sundayEndingRole,
            nextDayRole = mondayStartingRole,
            unusedGrantMinutes = 0L
        )
        assertEquals(0L, mondaySettlement.accumulatedMinutes)
        assertEquals(15L, mondaySettlement.expiredMinutes)

        rolloverState = rolloverState.withPool(
            RuleRolloverPool(
                ruleId = gameRule.id,
                accumulatedMinutes = mondaySettlement.accumulatedMinutes,
                lastSettledUseDayId = "2026-08-24"
            )
        )
        assertEquals(0L, rolloverState.pools.getValue(gameRule.id).accumulatedMinutes)
    }
}
