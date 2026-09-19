package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.RuleRolloverPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AppRuleRolloverPolicyTest {

    @Test
    fun dayRoleFollowsCurboxWeekdayConvention() {
        val unlockDays = setOf(0, 6) // Sunday=0, Saturday=6

        // Explicit weekday index
        assertEquals(AppRuleDayRole.UNLOCK, AppRuleRolloverPolicy.dayRoleFor(weekday = 0, unlockDays = unlockDays))
        assertEquals(AppRuleDayRole.ACCRUAL, AppRuleRolloverPolicy.dayRoleFor(weekday = 1, unlockDays = unlockDays))
        assertEquals(AppRuleDayRole.ACCRUAL, AppRuleRolloverPolicy.dayRoleFor(weekday = 5, unlockDays = unlockDays))
        assertEquals(AppRuleDayRole.UNLOCK, AppRuleRolloverPolicy.dayRoleFor(weekday = 6, unlockDays = unlockDays))

        // LocalDate: 2026-09-20 is Sunday, 2026-09-21 is Monday, 2026-09-19 is Saturday
        assertEquals(AppRuleDayRole.UNLOCK, AppRuleRolloverPolicy.dayRoleFor(LocalDate.of(2026, 9, 20), unlockDays))
        assertEquals(AppRuleDayRole.ACCRUAL, AppRuleRolloverPolicy.dayRoleFor(LocalDate.of(2026, 9, 21), unlockDays))
        assertEquals(AppRuleDayRole.ACCRUAL, AppRuleRolloverPolicy.dayRoleFor(LocalDate.of(2026, 9, 18), unlockDays))
        assertEquals(AppRuleDayRole.UNLOCK, AppRuleRolloverPolicy.dayRoleFor(LocalDate.of(2026, 9, 19), unlockDays))

        // String useDayId
        assertEquals(AppRuleDayRole.UNLOCK, AppRuleRolloverPolicy.dayRoleFor("2026-09-20", unlockDays))
        assertEquals(AppRuleDayRole.ACCRUAL, AppRuleRolloverPolicy.dayRoleFor("2026-09-21", unlockDays))
    }

    @Test
    fun accrualToAccrualTransfersUnusedGuardianExtraTimeToPool() {
        val result = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = 0L,
            endingDayRole = AppRuleDayRole.ACCRUAL,
            nextDayRole = AppRuleDayRole.ACCRUAL,
            unusedGrantMinutes = 20L
        )

        assertEquals(0L, result.previousPoolMinutes)
        assertEquals(20L, result.accumulatedMinutes)
        assertEquals(20L, result.addedMinutes)
        assertEquals(0L, result.expiredMinutes)
        assertEquals(AppRuleDayRole.ACCRUAL, result.endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, result.nextDayRole)

        // Subsequent accrual day adds on top of accumulated pool
        val result2 = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = result.accumulatedMinutes,
            endingDayRole = AppRuleDayRole.ACCRUAL,
            nextDayRole = AppRuleDayRole.ACCRUAL,
            unusedGrantMinutes = 15L
        )

        assertEquals(20L, result2.previousPoolMinutes)
        assertEquals(35L, result2.accumulatedMinutes)
        assertEquals(15L, result2.addedMinutes)
        assertEquals(0L, result2.expiredMinutes)
    }

    @Test
    fun accrualToUnlockTransfersUnusedGuardianExtraTimeToPool() {
        // e.g. Friday (Accrual) -> Saturday (Unlock)
        val result = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = 35L,
            endingDayRole = AppRuleDayRole.ACCRUAL,
            nextDayRole = AppRuleDayRole.UNLOCK,
            unusedGrantMinutes = 10L
        )

        assertEquals(35L, result.previousPoolMinutes)
        assertEquals(45L, result.accumulatedMinutes)
        assertEquals(10L, result.addedMinutes)
        assertEquals(0L, result.expiredMinutes)
        assertEquals(AppRuleDayRole.ACCRUAL, result.endingDayRole)
        assertEquals(AppRuleDayRole.UNLOCK, result.nextDayRole)
    }

    @Test
    fun unlockToUnlockConsolidatesRemainingPoolAndUnusedGrants() {
        // e.g. Saturday (Unlock) -> Sunday (Unlock)
        // Saturday had 10m left in pool, and 25m unused grants (approved accumulated + daily extra)
        val result = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = 10L,
            endingDayRole = AppRuleDayRole.UNLOCK,
            nextDayRole = AppRuleDayRole.UNLOCK,
            unusedGrantMinutes = 25L
        )

        assertEquals(10L, result.previousPoolMinutes)
        assertEquals(35L, result.accumulatedMinutes)
        assertEquals(25L, result.addedMinutes)
        assertEquals(0L, result.expiredMinutes)
        assertEquals(AppRuleDayRole.UNLOCK, result.endingDayRole)
        assertEquals(AppRuleDayRole.UNLOCK, result.nextDayRole)
    }

    @Test
    fun unlockToAccrualResetsPoolToZeroWhenUnlockBlockEnds() {
        // e.g. Sunday (Unlock) -> Monday (Accrual)
        // Unlock block ends: accumulated pool expires completely to prevent indefinite hoarding
        val result = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = 20L,
            endingDayRole = AppRuleDayRole.UNLOCK,
            nextDayRole = AppRuleDayRole.ACCRUAL,
            unusedGrantMinutes = 15L
        )

        assertEquals(20L, result.previousPoolMinutes)
        assertEquals(0L, result.accumulatedMinutes)
        assertEquals(0L, result.addedMinutes)
        assertEquals(35L, result.expiredMinutes) // 20m in pool + 15m unused grant expired
        assertEquals(AppRuleDayRole.UNLOCK, result.endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, result.nextDayRole)
    }

    @Test
    fun settleDayTransitionSafelyHandlesZeroAndNegativeInputs() {
        val result = AppRuleRolloverPolicy.settleDayTransition(
            currentPoolMinutes = -5L,
            endingDayRole = AppRuleDayRole.ACCRUAL,
            nextDayRole = AppRuleDayRole.ACCRUAL,
            unusedGrantMinutes = -10L
        )

        assertEquals(0L, result.previousPoolMinutes)
        assertEquals(0L, result.accumulatedMinutes)
        assertEquals(0L, result.addedMinutes)
        assertEquals(0L, result.expiredMinutes)
    }

    @Test
    fun settleDayTransitionWithPoolAdvancesLastSettledUseDayId() {
        val initialPool = RuleRolloverPool(ruleId = "rule-1", accumulatedMinutes = 20L, lastSettledUseDayId = "2026-09-18")
        val (nextPool, result) = AppRuleRolloverPolicy.settleDayTransition(
            pool = initialPool,
            endingDayRole = AppRuleDayRole.ACCRUAL,
            nextDayRole = AppRuleDayRole.UNLOCK,
            unusedGrantMinutes = 15L,
            nextUseDayId = "2026-09-19"
        )

        assertEquals("rule-1", nextPool.ruleId)
        assertEquals(35L, nextPool.accumulatedMinutes)
        assertEquals("2026-09-19", nextPool.lastSettledUseDayId)
        assertEquals(35L, result.accumulatedMinutes)
        assertEquals(15L, result.addedMinutes)
    }

    @Test
    fun shouldSettleDefendsAgainstPrematureSettlementOnSameCalendarDay() {
        // Same calendar day: restore time changed e.g. from 04:00 to 05:00 midday
        // Artificial generation change without date transition must NOT trigger settlement
        assertFalse(AppRuleRolloverPolicy.shouldSettle("2026-09-19", "2026-09-19"))

        // Blank initial state: no settlement until a use-day boundary has actually completed
        assertFalse(AppRuleRolloverPolicy.shouldSettle("", "2026-09-19"))
        assertFalse(AppRuleRolloverPolicy.shouldSettle("2026-09-19", ""))

        // Clock moved backwards: must not trigger settlement
        assertFalse(AppRuleRolloverPolicy.shouldSettle("2026-09-20", "2026-09-19"))

        // Calendar boundary crossed: settlement is required
        assertTrue(AppRuleRolloverPolicy.shouldSettle("2026-09-18", "2026-09-19"))
        assertTrue(AppRuleRolloverPolicy.shouldSettle("2026-09-15", "2026-09-19"))
    }

    @Test
    fun reconcileSettlementAcrossWeekendPatternConsolidatesAndExpires() {
        val unlockDays = setOf(0, 6) // Sunday=0, Saturday=6
        // Friday 2026-09-18 (Accrual) had 30m unused extra time
        // Saturday 2026-09-19 (Unlock) had 20m unused grant/pool time
        // Sunday 2026-09-20 (Unlock) had 15m unused grant/pool time
        // Current day is Monday 2026-09-21 (Accrual)
        val unusedMinutesByDay = mapOf(
            "2026-09-18" to 30L,
            "2026-09-19" to 20L,
            "2026-09-20" to 15L
        )

        val result = AppRuleRolloverPolicy.reconcileSettlement(
            currentPoolMinutes = 0L,
            lastSettledUseDayId = "2026-09-18",
            currentUseDayId = "2026-09-21",
            unlockDays = unlockDays,
            rolloverEnabled = true,
            unusedMinutesByDay = unusedMinutesByDay
        )

        // Monday is reached, unlock block ended -> pool is reset to 0
        assertEquals(0L, result.pool.accumulatedMinutes)
        assertEquals("2026-09-21", result.pool.lastSettledUseDayId)
        assertEquals(3, result.transitions.size)

        // Transition 1: Fri -> Sat (Accrual -> Unlock)
        val t1 = result.transitions[0]
        assertEquals(AppRuleDayRole.ACCRUAL, t1.endingDayRole)
        assertEquals(AppRuleDayRole.UNLOCK, t1.nextDayRole)
        assertEquals(0L, t1.previousPoolMinutes)
        assertEquals(30L, t1.accumulatedMinutes)
        assertEquals(30L, t1.addedMinutes)
        assertEquals(0L, t1.expiredMinutes)

        // Transition 2: Sat -> Sun (Unlock -> Unlock)
        val t2 = result.transitions[1]
        assertEquals(AppRuleDayRole.UNLOCK, t2.endingDayRole)
        assertEquals(AppRuleDayRole.UNLOCK, t2.nextDayRole)
        assertEquals(30L, t2.previousPoolMinutes)
        assertEquals(50L, t2.accumulatedMinutes)
        assertEquals(20L, t2.addedMinutes)
        assertEquals(0L, t2.expiredMinutes)

        // Transition 3: Sun -> Mon (Unlock -> Accrual)
        val t3 = result.transitions[2]
        assertEquals(AppRuleDayRole.UNLOCK, t3.endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, t3.nextDayRole)
        assertEquals(50L, t3.previousPoolMinutes)
        assertEquals(0L, t3.accumulatedMinutes)
        assertEquals(0L, t3.addedMinutes)
        assertEquals(65L, t3.expiredMinutes) // 50 in pool + 15 unused on Sunday
    }

    @Test
    fun reconcileSettlementMidweekPatternAlternatesAccumulationAndReset() {
        // Mon/Wed/Fri unlock days: unlockDays = {1, 3, 5}
        val unlockDays = setOf(1, 3, 5)

        val unusedMinutes = mapOf(
            "2026-09-13" to 20L, // Sun (Accrual) -> Mon: pool = 20
            "2026-09-14" to 10L, // Mon (Unlock) -> Tue: reset to 0
            "2026-09-15" to 15L, // Tue (Accrual) -> Wed: pool = 15
            "2026-09-16" to 5L,  // Wed (Unlock) -> Thu: reset to 0
            "2026-09-17" to 25L, // Thu (Accrual) -> Fri: pool = 25
            "2026-09-18" to 10L, // Fri (Unlock) -> Sat: reset to 0
            "2026-09-19" to 10L, // Sat (Accrual) -> Sun (Accrual): pool = 10
            "2026-09-20" to 15L  // Sun (Accrual) -> Mon (Unlock): pool = 10 + 15 = 25
        )

        val result = AppRuleRolloverPolicy.reconcileSettlement(
            currentPoolMinutes = 0L,
            lastSettledUseDayId = "2026-09-13",
            currentUseDayId = "2026-09-21",
            unlockDays = unlockDays,
            rolloverEnabled = true,
            unusedMinutesByDay = unusedMinutes
        )

        assertEquals(8, result.transitions.size)
        assertEquals("2026-09-21", result.pool.lastSettledUseDayId)
        // Final pool on Monday is 25 minutes accumulated from Sat and Sun!
        assertEquals(25L, result.pool.accumulatedMinutes)

        // Verify key intermediate transitions:
        // Mon -> Tue (Unlock -> Accrual) resets to 0
        val monToTue = result.transitions[1]
        assertEquals(AppRuleDayRole.UNLOCK, monToTue.endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, monToTue.nextDayRole)
        assertEquals(0L, monToTue.accumulatedMinutes)

        // Wed -> Thu (Unlock -> Accrual) resets to 0
        val wedToThu = result.transitions[3]
        assertEquals(AppRuleDayRole.UNLOCK, wedToThu.endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, wedToThu.nextDayRole)
        assertEquals(0L, wedToThu.accumulatedMinutes)

        // Sat -> Sun (Accrual -> Accrual) retains and adds
        val satToSun = result.transitions[6]
        assertEquals(AppRuleDayRole.ACCRUAL, satToSun.endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, satToSun.nextDayRole)
        assertEquals(0L, satToSun.previousPoolMinutes)
        assertEquals(10L, satToSun.accumulatedMinutes)

        // Sun -> Mon (Accrual -> Unlock) adds Sunday's unused time to Saturday's pool
        val sunToMon = result.transitions[7]
        assertEquals(AppRuleDayRole.ACCRUAL, sunToMon.endingDayRole)
        assertEquals(AppRuleDayRole.UNLOCK, sunToMon.nextDayRole)
        assertEquals(10L, sunToMon.previousPoolMinutes)
        assertEquals(25L, sunToMon.accumulatedMinutes)
    }

    @Test
    fun offlineGapPreservesPoolAcrossPoweredOffDaysUntilUnlockDay() {
        val unlockDays = setOf(0, 6) // Sat/Sun unlock
        // Device turned off Thursday night (2026-09-17)
        // Thursday had 15m unused extra time, and pool already had 45m
        // Friday (2026-09-18) was completely powered off (0m usage, 0m extra time)
        // Device boots Saturday morning (2026-09-19)
        val result = AppRuleRolloverPolicy.reconcileSettlement(
            currentPoolMinutes = 45L,
            lastSettledUseDayId = "2026-09-17",
            currentUseDayId = "2026-09-19",
            unlockDays = unlockDays,
            rolloverEnabled = true,
            lastSettledDayUnusedMinutes = 15L,
            unusedMinutesByDay = emptyMap() // Friday is off -> defaults to 0
        )

        assertEquals("2026-09-19", result.pool.lastSettledUseDayId)
        // Pool is 45 + 15 (Thursday) + 0 (Friday) = 60m!
        assertEquals(60L, result.pool.accumulatedMinutes)
        assertEquals(2, result.transitions.size)

        // Transition 1: Thu -> Fri (Accrual -> Accrual)
        assertEquals(60L, result.transitions[0].accumulatedMinutes)
        // Transition 2: Fri -> Sat (Accrual -> Unlock)
        assertEquals(60L, result.transitions[1].accumulatedMinutes)
        assertEquals(0L, result.transitions[1].addedMinutes)
    }

    @Test
    fun offlineGapExpiresPoolWhenCrossingUnlockBlockBoundary() {
        val unlockDays = setOf(0, 6) // Sat/Sun unlock
        // Device turned off Sunday night (2026-09-20) with 30m in pool
        // Powered off through Monday (2026-09-21)
        // Boots on Tuesday (2026-09-22)
        val result = AppRuleRolloverPolicy.reconcileSettlement(
            currentPoolMinutes = 30L,
            lastSettledUseDayId = "2026-09-20",
            currentUseDayId = "2026-09-22",
            unlockDays = unlockDays,
            rolloverEnabled = true,
            lastSettledDayUnusedMinutes = 0L
        )

        assertEquals("2026-09-22", result.pool.lastSettledUseDayId)
        // Sun -> Mon reset pool to 0!
        assertEquals(0L, result.pool.accumulatedMinutes)
        assertEquals(2, result.transitions.size)

        // Sun -> Mon (Unlock -> Accrual)
        assertEquals(AppRuleDayRole.UNLOCK, result.transitions[0].endingDayRole)
        assertEquals(AppRuleDayRole.ACCRUAL, result.transitions[0].nextDayRole)
        assertEquals(0L, result.transitions[0].accumulatedMinutes)
        assertEquals(30L, result.transitions[0].expiredMinutes)

        // Mon -> Tue (Accrual -> Accrual)
        assertEquals(0L, result.transitions[1].accumulatedMinutes)
    }

    @Test
    fun multiWeekLongOfflineGapSettlesSequentiallyWithoutError() {
        val unlockDays = setOf(0, 6) // Weekend unlock
        // Device off for 21 days
        val result = AppRuleRolloverPolicy.reconcileSettlement(
            currentPoolMinutes = 50L,
            lastSettledUseDayId = "2026-09-01",
            currentUseDayId = "2026-09-22",
            unlockDays = unlockDays,
            rolloverEnabled = true,
            lastSettledDayUnusedMinutes = 10L
        )

        assertEquals(21, result.transitions.size)
        assertEquals("2026-09-22", result.pool.lastSettledUseDayId)
        // Since it crossed several weekend-to-accrual boundaries and final day (2026-09-22, Tue) is accrual with no unused time since last Sunday, pool is 0
        assertEquals(0L, result.pool.accumulatedMinutes)
    }

    @Test
    fun disabledRolloverPreservesPoolWithoutAccumulatingOrExpiring() {
        // Rule had 40m accumulated before guardian temporarily disabled rollover
        val pool = RuleRolloverPool(ruleId = "rule-1", accumulatedMinutes = 40L, lastSettledUseDayId = "2026-09-18")

        // Over the weekend with rollover disabled, 20m was left over
        val result = AppRuleRolloverPolicy.reconcileSettlement(
            pool = pool,
            rule = AppRule(id = "rule-1", rolloverEnabled = false, unlockDays = setOf(0, 6)),
            currentUseDayId = "2026-09-21",
            lastSettledDayUnusedMinutes = 20L
        )

        // 40m is safely preserved without being expired on Monday or accumulating
        assertEquals(40L, result.pool.accumulatedMinutes)
        assertEquals("2026-09-21", result.pool.lastSettledUseDayId)
        assertTrue(result.transitions.isEmpty())
    }

    @Test
    fun sameDayRestoreTimeEditPreservesPoolWithoutSettlement() {
        val pool = RuleRolloverPool(ruleId = "rule-1", accumulatedMinutes = 50L, lastSettledUseDayId = "2026-09-19")

        // Midday change of reset time hour/minute on Saturday (same useDayId)
        val result = AppRuleRolloverPolicy.reconcileSettlement(
            pool = pool,
            rule = AppRule(id = "rule-1", rolloverEnabled = true, unlockDays = setOf(0, 6)),
            currentUseDayId = "2026-09-19"
        )

        assertEquals(50L, result.pool.accumulatedMinutes)
        assertEquals("2026-09-19", result.pool.lastSettledUseDayId)
        assertTrue(result.transitions.isEmpty())
    }
}
