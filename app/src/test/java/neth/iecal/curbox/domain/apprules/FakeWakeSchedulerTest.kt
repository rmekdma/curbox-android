package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeWakeSchedulerTest {

    @Test
    fun scheduleStoresEntryAndCancelRemovesIt() {
        val scheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L
        )

        scheduler.schedule("com.example.a", dueAtWallClockMs = 1_000_010_000L, token = 1L)

        assertTrue(scheduler.hasScheduled("com.example.a"))
        val scheduled = scheduler.getScheduled("com.example.a")
        assertEquals(1L, scheduled?.token)
        assertEquals(1_000_010_000L, scheduled?.dueAtWallClockMs)

        scheduler.cancel("com.example.a")
        assertFalse(scheduler.hasScheduled("com.example.a"))
        assertNull(scheduler.getScheduled("com.example.a"))
    }

    @Test
    fun cancelAllClearsAllScheduledEntries() {
        val scheduler = FakeWakeScheduler()
        scheduler.schedule("com.example.a", dueAtWallClockMs = 1_000_010_000L, token = 1L)
        scheduler.schedule("com.example.b", dueAtWallClockMs = 1_000_020_000L, token = 2L)

        assertEquals(2, scheduler.scheduledCount())

        scheduler.cancelAll()
        assertEquals(0, scheduler.scheduledCount())
        assertFalse(scheduler.hasScheduled("com.example.a"))
        assertFalse(scheduler.hasScheduled("com.example.b"))
    }

    @Test
    fun advancingVirtualTimeTriggersWakeWhenDue() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val scheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L,
            onWake = { key, token -> wakes.add(key to token) }
        )

        // 5 seconds from now
        scheduler.schedule("com.example.a", dueAtWallClockMs = 1_000_005_000L, token = 100L)

        scheduler.advanceTimeBy(4_999L)
        assertTrue(wakes.isEmpty())
        assertTrue(scheduler.hasScheduled("com.example.a"))

        scheduler.advanceTimeBy(1L)
        assertEquals(listOf("com.example.a" to 100L), wakes)
        assertFalse(scheduler.hasScheduled("com.example.a"))
    }

    @Test
    fun longAlarmWakesAtFullDelayWithoutPrematureWake() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val scheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L,
            onWake = { key, token -> wakes.add(key to token) }
        )

        // 60 seconds from now
        scheduler.schedule("com.example.long", dueAtWallClockMs = 1_000_060_000L, token = 42L)

        // At 20 seconds, healthy alarm must not wake prematurely
        scheduler.advanceTimeBy(20_000L)
        assertTrue(wakes.isEmpty())
        assertTrue(scheduler.hasScheduled("com.example.long"))

        // At 60 seconds, alarm wakes
        scheduler.advanceTimeBy(40_000L)
        assertEquals(listOf("com.example.long" to 42L), wakes)
        assertFalse(scheduler.hasScheduled("com.example.long"))
    }

    @Test
    fun handlerFallbackCapsWakeDelayToTwentySecondsWhenAlarmFails() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val scheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L,
            onWake = { key, token -> wakes.add(key to token) }
        )

        // 60 seconds from now, but platform alarm fails
        scheduler.schedule("com.example.failed", dueAtWallClockMs = 1_000_060_000L, token = 55L)
        scheduler.simulateAlarmFailure("com.example.failed")

        scheduler.advanceTimeBy(19_999L)
        assertTrue(wakes.isEmpty())

        // At 20s, handler fallback activates to provide bounded recovery
        scheduler.advanceTimeBy(1L)
        assertEquals(listOf("com.example.failed" to 55L), wakes)
        assertFalse(scheduler.hasScheduled("com.example.failed"))
    }

    @Test
    fun reschedulingSameKeyAtomicallyReplacesPreviousWake() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val scheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L,
            onWake = { key, token -> wakes.add(key to token) }
        )

        // Schedule first with token 1 for 15 seconds
        scheduler.schedule("com.example.a", dueAtWallClockMs = 1_000_015_000L, token = 1L)

        // Replace with token 2 for 8 seconds
        scheduler.schedule("com.example.a", dueAtWallClockMs = 1_000_008_000L, token = 2L)

        scheduler.advanceTimeBy(8_000L)
        assertEquals(listOf("com.example.a" to 2L), wakes)

        // Advance past original 15s - token 1 must never fire
        scheduler.advanceTimeBy(10_000L)
        assertEquals(listOf("com.example.a" to 2L), wakes)
    }

    @Test
    fun triggerWakeDirectlyNotifiesListenerAndRemovesEntry() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val scheduler = FakeWakeScheduler(
            initialWallClockMs = 1_000_000_000L,
            initialElapsedRealtimeMs = 10_000L
        )
        scheduler.onWake = { key, token -> wakes.add(key to token) }

        scheduler.schedule("com.example.manual", dueAtWallClockMs = 1_000_030_000L, token = 77L)
        assertTrue(scheduler.hasScheduled("com.example.manual"))

        val triggered = scheduler.triggerWake("com.example.manual")
        assertTrue(triggered)
        assertEquals(listOf("com.example.manual" to 77L), wakes)
        assertFalse(scheduler.hasScheduled("com.example.manual"))

        // Triggering again returns false
        assertFalse(scheduler.triggerWake("com.example.manual"))
    }
}
