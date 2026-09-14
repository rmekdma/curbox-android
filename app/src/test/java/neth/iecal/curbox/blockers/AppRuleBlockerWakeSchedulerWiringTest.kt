package neth.iecal.curbox.blockers

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
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
    fun scheduleRecheckDelegatesToWakeSchedulerAndMirrorsState() {
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

        // 2. Verify legacy maps are mirrored
        val legacyScheduledRechecks = getField(blocker, "scheduledRechecks") as Map<*, *>
        val legacyScheduledAlarms = getField(blocker, "scheduledAlarms") as Map<*, *>
        assertTrue(legacyScheduledRechecks.containsKey(TEST_PACKAGE))
        assertTrue(legacyScheduledAlarms.containsKey(TEST_PACKAGE))

        val alarmRegistration = legacyScheduledAlarms[TEST_PACKAGE]
        assertNotNull(alarmRegistration)
        val token = getField(alarmRegistration!!, "token") as Long
        assertEquals(token, scheduledWake?.token)

        // 3. Cancel recheck and verify both scheduler and legacy maps are updated
        invokePrivate(blocker, "cancelScheduledRecheck", TEST_PACKAGE, token, null)
        assertFalse(fakeScheduler.hasScheduled(TEST_PACKAGE))
        assertFalse(legacyScheduledRechecks.containsKey(TEST_PACKAGE))
        assertFalse(legacyScheduledAlarms.containsKey(TEST_PACKAGE))

        blocker.onDestroy()
    }

    @Test
    fun triggerWakeFromSchedulerReconcilesAndCleansUpLegacyMaps() {
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

        val legacyScheduledRechecks = getField(blocker, "scheduledRechecks") as Map<*, *>
        val legacyScheduledAlarms = getField(blocker, "scheduledAlarms") as Map<*, *>
        assertTrue(legacyScheduledRechecks.containsKey(TEST_PACKAGE))
        assertTrue(legacyScheduledAlarms.containsKey(TEST_PACKAGE))

        // Trigger wake through fakeScheduler
        val triggered = fakeScheduler.triggerWake(TEST_PACKAGE)
        assertTrue(triggered)

        // Verify that onWake cleaned up the legacy maps
        assertFalse(legacyScheduledRechecks.containsKey(TEST_PACKAGE))
        assertFalse(legacyScheduledAlarms.containsKey(TEST_PACKAGE))

        // Verify that a visible application check was posted (observation wake)
        assertTrue(observationChecks.get() > 0)

        blocker.onDestroy()
    }

    @Test
    fun cancelAllDelegatesToWakeSchedulerAndClearsLegacyMaps() {
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
        val legacyScheduledRechecks = getField(blocker, "scheduledRechecks") as Map<*, *>
        val legacyScheduledAlarms = getField(blocker, "scheduledAlarms") as Map<*, *>
        assertTrue(legacyScheduledRechecks.isEmpty())
        assertTrue(legacyScheduledAlarms.isEmpty())

        blocker.onDestroy()
    }
}
