package neth.iecal.curbox.blockers

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.FakeWakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppRuleBlockerWakeSchedulerWiringTest {

    private companion object {
        const val TEST_PACKAGE = "com.example.target"
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invokePrivate(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first {
            (it.name == name || it.name.startsWith("$name-")) && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun sampleSnapshot(): AppRuleSnapshot {
        val group = AppRuleAppGroup(id = "grp1", name = "Group 1", selectedPackages = listOf(TEST_PACKAGE))
        val rule = AppRule(
            id = "rule1",
            name = "Rule 1",
            appGroupId = "grp1",
            allowedMinutes = 60
        )
        return AppRuleSnapshot(appGroups = listOf(group), appRules = listOf(rule))
    }

    @Test
    fun scheduleRecheckDelegatesToWakeSchedulerAndCancelsCleanly() {
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L
        )

        var wallClock = 1_000_000_000L
        var elapsed = 10_000L
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClock }
            elapsedRealtimeMsProvider = { elapsed }
            wakeScheduler = fakeScheduler
        }

        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TEST_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(sampleSnapshot())

        // Schedule a recheck for 5000ms in the future
        invokePrivate(blocker, "scheduleRecheck", TEST_PACKAGE, 5_000L, 20_000L, 0L)

        // 1. Verify FakeWakeScheduler received the schedule call
        assertTrue(fakeScheduler.hasScheduled(TEST_PACKAGE))
        val scheduledWake = fakeScheduler.getScheduled(TEST_PACKAGE)
        assertNotNull(scheduledWake)
        val expectedDueWallClockMs = 1_000_005_000L
        assertEquals(expectedDueWallClockMs, scheduledWake?.dueAtWallClockMs)

        // 2. Cancel recheck and verify scheduler is updated
        invokePrivate(blocker, "cancelScheduledRecheck", TEST_PACKAGE)
        assertFalse(fakeScheduler.hasScheduled(TEST_PACKAGE))

        blocker.onDestroy()
    }

    @Test
    fun triggerWakeFromSchedulerReconcilesAndTriggersObservationCheck() {
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L
        )

        var wallClock = 1_000_000_000L
        var elapsed = 10_000L
        val observationChecks = AtomicInteger(0)
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { wallClock }
            elapsedRealtimeMsProvider = { elapsed }
            visibleApplicationCheckPostDelayed = { _, _ ->
                observationChecks.incrementAndGet()
                true
            }
            wakeScheduler = fakeScheduler
        }

        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TEST_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(sampleSnapshot())

        invokePrivate(blocker, "scheduleRecheck", TEST_PACKAGE, 5_000L, 20_000L, 0L)
        assertTrue(fakeScheduler.hasScheduled(TEST_PACKAGE))

        // Trigger wake through fakeScheduler
        val triggered = fakeScheduler.triggerWake(TEST_PACKAGE)
        assertTrue(triggered)

        // Verify that scheduler no longer has the package scheduled
        assertFalse(fakeScheduler.hasScheduled(TEST_PACKAGE))

        // Verify that a visible application check was posted (observation wake)
        assertTrue(observationChecks.get() > 0)

        blocker.onDestroy()
    }

    @Test
    fun cancelAllDelegatesToWakeScheduler() {
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L
        )

        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { 1_000_000_000L }
            elapsedRealtimeMsProvider = { 10_000L }
            wakeScheduler = fakeScheduler
        }

        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TEST_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(sampleSnapshot())

        invokePrivate(blocker, "scheduleRecheck", TEST_PACKAGE, 5_000L, 20_000L, 0L)
        assertTrue(fakeScheduler.hasScheduled(TEST_PACKAGE))

        // Cancel all
        invokePrivate(blocker, "cancelScheduledRechecks")

        assertFalse(fakeScheduler.hasScheduled(TEST_PACKAGE))
        assertEquals(0, fakeScheduler.scheduledCount())

        blocker.onDestroy()
    }

    @Test
    fun scheduleNextSettlementAlarmSchedulesAlarmForSettlementKey() {
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L
        )

        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { 1_000_000_000L }
            elapsedRealtimeMsProvider = { 10_000L }
            wakeScheduler = fakeScheduler
        }

        setField(blocker, "setupReady", true)

        blocker.scheduleNextSettlementAlarm()

        assertTrue(fakeScheduler.hasScheduled(AppRuleBlocker.SETTLEMENT_WAKE_KEY))
        val scheduled = fakeScheduler.getScheduled(AppRuleBlocker.SETTLEMENT_WAKE_KEY)
        assertNotNull(scheduled)
        assertTrue(scheduled!!.dueAtWallClockMs > 1_000_000_000L)

        blocker.onDestroy()
    }

    @Test
    fun settlementWakeTriggerExecutesReconciliationAndReschedulesAlarm() {
        val nowMs = java.time.Instant.parse("2026-08-18T05:00:00Z").toEpochMilli()
        val fakeScheduler = FakeWakeScheduler(
            initialWallClockMs = nowMs,
            initialElapsedRealtimeMs = 10_000L
        )

        val reconciled = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)

        val rule = AppRule(
            id = "rule1",
            name = "Rule 1",
            appGroupId = "grp1",
            rolloverEnabled = true,
            allowedMinutes = 60
        )
        val initialPool = RuleRolloverPool(ruleId = "rule1", accumulatedMinutes = 10L, lastSettledUseDayId = "2026-08-17")
        val coordinator = neth.iecal.curbox.domain.apprules.AppRuleRolloverCoordinator(
            sessionRepository = object : neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository {
                override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long = 1L
                override suspend fun finishSession(id: Long, endedAtMs: Long) {}
                override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {}
                override suspend fun sessionsForUseDay(useDayId: String): List<neth.iecal.curbox.data.models.ForegroundSession> = emptyList()
                override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {}
            },
            zone = { java.time.ZoneId.of("UTC") },
            readSettings = {
                Settings(
                    appRuleSnapshot = AppRuleSnapshot(
                        appRules = listOf(rule),
                        appGroups = listOf(AppRuleAppGroup(id = "grp1", name = "Group 1", selectedPackages = listOf(TEST_PACKAGE)))
                    ),
                    appRuleRolloverState = AppRuleRolloverState(pools = mapOf(rule.id to initialPool)),
                    useDayResetHour = 4,
                    useDayResetMinute = 0
                )
            },
            writeRolloverState = {
                reconciled.set(true)
                latch.countDown()
                true
            }
        )

        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { nowMs }
            elapsedRealtimeMsProvider = { 10_000L }
            wakeScheduler = fakeScheduler
            rolloverCoordinator = coordinator
        }

        setField(blocker, "setupReady", true)
        blocker.scheduleNextSettlementAlarm()
        assertTrue(fakeScheduler.hasScheduled(AppRuleBlocker.SETTLEMENT_WAKE_KEY))

        // Trigger wake from scheduler
        val triggered = fakeScheduler.triggerWake(AppRuleBlocker.SETTLEMENT_WAKE_KEY)
        assertTrue(triggered)

        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(reconciled.get())

        blocker.onDestroy()
    }
}
