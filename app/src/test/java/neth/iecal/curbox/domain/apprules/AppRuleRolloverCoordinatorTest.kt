package neth.iecal.curbox.domain.apprules

import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppRuleRolloverCoordinatorTest {

    private val zone = ZoneId.of("UTC")
    private val group = AppRuleAppGroup.create("Social", listOf("com.example.app"))

    private class FakeSessionRepository : CurrentUseDaySessionRepository {
        val sessionsByDay = mutableMapOf<String, List<ForegroundSession>>()

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long = 1L
        override suspend fun finishSession(id: Long, endedAtMs: Long) {}
        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {}
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> =
            sessionsByDay[useDayId] ?: emptyList()
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {}
    }

    @Test
    fun reconcileSettlementSavesUpdatedPoolWhenDayTransitionsOnResetBoundary() = runBlocking {
        val rule = AppRule(
            id = "rule1",
            name = "Test Rule",
            appGroupId = group.id,
            allowedMinutes = 30,
            rolloverEnabled = true,
            unlockDays = setOf(6, 0) // Sat, Sun unlock. Mon=1 is Accrual, Tue=2 is Accrual.
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        // 2026-08-17 is Monday (Accrual). Grant 20 min unused extra time.
        val mondayStartMs = Instant.parse("2026-08-17T04:00:00Z").toEpochMilli()
        val grant = AppRuleGuardianGrant(rule.id, "2026-08-17", mondayStartMs + 1000L, 20 * 60_000L)
        val overrides = AppRuleOverrideState(useDayId = "2026-08-17", grants = listOf(grant))

        val initialPool = RuleRolloverPool(ruleId = "rule1", accumulatedMinutes = 10L, lastSettledUseDayId = "2026-08-17")
        val initialSettings = Settings(
            appRuleSnapshot = snapshot,
            appRuleRolloverState = AppRuleRolloverState(pools = mapOf(initialPool.ruleId to initialPool)),
            appRuleOverrideState = overrides,
            useDayResetHour = 4,
            useDayResetMinute = 0
        )

        var writtenState: AppRuleRolloverState? = null
        val repo = FakeSessionRepository()

        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = repo,
            zone = { zone },
            readSettings = { initialSettings },
            writeRolloverState = { state: AppRuleRolloverState ->
                writtenState = state
                true
            }
        )

        // Run at 2026-08-18 04:01:00Z (Tuesday, currentUseDayId = "2026-08-18")
        val tuesdayResetMs = Instant.parse("2026-08-18T04:01:00Z").toEpochMilli()
        val result = coordinator.reconcileSettlement(tuesdayResetMs)

        assertTrue(result.committed)
        assertEquals(1, result.settledPools.size)
        val pool = result.settledPools["rule1"]!!
        assertEquals("rule1", pool.ruleId)
        // Previous 10 + 20 unused = 30 min
        assertEquals(30L, pool.accumulatedMinutes)
        assertEquals("2026-08-18", pool.lastSettledUseDayId)
        assertEquals(writtenState?.pools?.get("rule1")?.accumulatedMinutes, 30L)
    }

    @Test
    fun reconcileSettlementResetsPoolWhenUnlockBlockEnds() = runBlocking {
        // Unlock days: Friday (5), Saturday (6). Sunday (0) is Accrual.
        val rule = AppRule(
            id = "rule1",
            appGroupId = group.id,
            allowedMinutes = 30,
            rolloverEnabled = true,
            unlockDays = setOf(5, 6)
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        // 2026-08-15 is Saturday (Unlock day). Last settled day was Saturday.
        // Current day is 2026-08-16 Sunday (Accrual day).
        val initialPool = RuleRolloverPool(ruleId = "rule1", accumulatedMinutes = 60L, lastSettledUseDayId = "2026-08-15")
        val initialSettings = Settings(
            appRuleSnapshot = snapshot,
            appRuleRolloverState = AppRuleRolloverState(pools = mapOf(initialPool.ruleId to initialPool)),
            useDayResetHour = 4,
            useDayResetMinute = 0
        )

        var writtenState: AppRuleRolloverState? = null
        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = FakeSessionRepository(),
            zone = { zone },
            readSettings = { initialSettings },
            writeRolloverState = { state: AppRuleRolloverState ->
                writtenState = state
                true
            }
        )

        val sundayMs = Instant.parse("2026-08-16T04:05:00Z").toEpochMilli()
        val result = coordinator.reconcileSettlement(sundayMs)

        assertTrue(result.committed)
        val pool = result.settledPools["rule1"]!!
        // Unlock -> Accrual resets accumulated minutes to 0
        assertEquals(0L, pool.accumulatedMinutes)
        assertEquals("2026-08-16", pool.lastSettledUseDayId)
        assertEquals(0L, writtenState?.pools?.get("rule1")?.accumulatedMinutes)
    }

    @Test
    fun reconcileSettlementCatchesUpMultipleMissedDaysSequentially() = runBlocking {
        // Unlock days: Saturday (6), Sunday (0).
        val rule = AppRule(
            id = "rule1",
            appGroupId = group.id,
            allowedMinutes = 30,
            rolloverEnabled = true,
            unlockDays = setOf(6, 0)
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        // Device went offline on 2026-08-17 (Monday, Accrual) with 15 min unused.
        val mondayStartMs = Instant.parse("2026-08-17T04:00:00Z").toEpochMilli()
        val grant = AppRuleGuardianGrant(rule.id, "2026-08-17", mondayStartMs + 1000L, 15 * 60_000L)
        val overrides = AppRuleOverrideState(useDayId = "2026-08-17", grants = listOf(grant))

        // Device boots up 3 days later on Thursday 2026-08-20!
        // Intermediate days: Tue 08-18 (Accrual, 0 min), Wed 08-19 (Accrual, 0 min).
        val initialPool = RuleRolloverPool(ruleId = "rule1", accumulatedMinutes = 0L, lastSettledUseDayId = "2026-08-17")
        val initialSettings = Settings(
            appRuleSnapshot = snapshot,
            appRuleRolloverState = AppRuleRolloverState(pools = mapOf(initialPool.ruleId to initialPool)),
            appRuleOverrideState = overrides,
            useDayResetHour = 4,
            useDayResetMinute = 0
        )

        var writtenState: AppRuleRolloverState? = null
        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = FakeSessionRepository(),
            zone = { zone },
            readSettings = { initialSettings },
            writeRolloverState = { state: AppRuleRolloverState ->
                writtenState = state
                true
            }
        )

        val thursdayMs = Instant.parse("2026-08-20T08:00:00Z").toEpochMilli()
        val result = coordinator.reconcileSettlement(thursdayMs)

        assertTrue(result.committed)
        val pool = result.settledPools["rule1"]!!
        // Day 1 (Mon): 0 + 15 = 15.
        // Day 2 (Tue): 15 + 0 = 15.
        // Day 3 (Wed): 15 + 0 = 15.
        assertEquals(15L, pool.accumulatedMinutes)
        assertEquals("2026-08-20", pool.lastSettledUseDayId)
        assertEquals(3, result.transitions["rule1"]?.size)
        assertEquals(15L, writtenState?.pools?.get("rule1")?.accumulatedMinutes)
    }


    @Test
    fun reconcileSettlementPreservesPoolWhenRolloverDisabled() = runBlocking {
        val rule = AppRule(
            id = "rule1",
            appGroupId = group.id,
            rolloverEnabled = false
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        val initialPool = RuleRolloverPool(ruleId = "rule1", accumulatedMinutes = 25L, lastSettledUseDayId = "2026-08-17")
        val initialSettings = Settings(
            appRuleSnapshot = snapshot,
            appRuleRolloverState = AppRuleRolloverState(pools = mapOf(initialPool.ruleId to initialPool))
        )

        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = FakeSessionRepository(),
            zone = { zone },
            readSettings = { initialSettings },
            writeRolloverState = { true }
        )

        val tuesdayMs = Instant.parse("2026-08-18T06:00:00Z").toEpochMilli()
        val result = coordinator.reconcileSettlement(tuesdayMs)

        assertTrue(result.committed)
        assertEquals(25L, result.settledPools["rule1"]?.accumulatedMinutes)
        assertEquals("2026-08-18", result.settledPools["rule1"]?.lastSettledUseDayId)
    }

    @Test
    fun reconcileSettlementDoesNotThrowAndCallsNonFatalErrorOnException() = runBlocking {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = FakeSessionRepository(),
            zone = { zone },
            readSettings = { throw IllegalStateException("Boom!") },
            onNonFatalError = { errors += it }
        )

        val result = coordinator.reconcileSettlement(1_000L)

        assertFalse(result.committed)
        assertTrue(result.settledPools.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IllegalStateException)
    }

    @Test
    fun reconcileSettlementRethrowsCancellationException() = runBlocking {
        val coordinator = AppRuleRolloverCoordinator(
            sessionRepository = FakeSessionRepository(),
            zone = { zone },
            readSettings = { throw CancellationException("Cancelled") },
            onNonFatalError = { fail("Should not report CancellationException as non-fatal") }
        )

        try {
            coordinator.reconcileSettlement(1_000L)
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Cancelled", e.message)
        }
    }
}
