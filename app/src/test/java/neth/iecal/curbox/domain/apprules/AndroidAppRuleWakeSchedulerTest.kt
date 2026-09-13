package neth.iecal.curbox.domain.apprules

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppRuleWakeSchedulerTest {

    private fun createDummyPendingIntent(): PendingIntent {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateMethod.invoke(unsafe, PendingIntent::class.java) as PendingIntent
    }

    private class TestAlarmRecord(
        val type: Int,
        val triggerAtMillis: Long,
        val operation: PendingIntent,
        val exact: Boolean
    )

    private class FakeAlarmPublisher(
        private val onSetExact: (Int, Long, PendingIntent) -> Unit = { _, _, _ -> },
        private val onSetInexact: (Int, Long, PendingIntent) -> Unit = { _, _, _ -> },
        private val onCancel: (PendingIntent) -> Unit = { }
    ) : AlarmPublisher {
        override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
            onSetExact(type, triggerAtMillis, operation)
        }

        override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
            onSetInexact(type, triggerAtMillis, operation)
        }

        override fun cancel(operation: PendingIntent) {
            onCancel(operation)
        }
    }

    private class StubContext(private val pkgName: String = "neth.iecal.curbox.test") : ContextWrapper(null) {
        override fun getPackageName(): String = pkgName
    }

    @Test
    fun scheduleCalculatesMonotonicDelayAndCapsHandlerToTwentySeconds() {
        val currentWallClock = 1_000_000_000L
        val currentElapsed = 50_000L
        val recordedAlarms = mutableListOf<TestAlarmRecord>()
        val postedHandlers = mutableListOf<Pair<Runnable, Long>>()
        val dummyPi = createDummyPendingIntent()

        val alarmPublisher = FakeAlarmPublisher(
            onSetExact = { type, trigger, op ->
                recordedAlarms.add(TestAlarmRecord(type, trigger, op, exact = true))
            }
        )

        val scheduler = AndroidAppRuleWakeScheduler(
            context = StubContext(),
            alarmPublisher = alarmPublisher,
            handlerPostDelayed = { runnable, delay ->
                postedHandlers.add(runnable to delay)
                true
            },
            handlerRemoveCallbacks = { },
            wallClockMs = { currentWallClock },
            elapsedRealtimeMs = { currentElapsed },
            pendingIntentFactory = { _, _ -> dummyPi },
            cancelPendingIntent = { },
            onNonFatalError = { throw it }
        )

        // Schedule for 60 seconds into future (exceeds 20s handler cap)
        scheduler.schedule("pkg.a", dueAtWallClockMs = 1_000_060_000L, token = 101L)

        // AlarmManager should be scheduled for full 60s: currentElapsed (50_000) + 60_000 = 110_000L
        assertEquals(1, recordedAlarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, recordedAlarms[0].type)
        assertEquals(110_000L, recordedAlarms[0].triggerAtMillis)
        assertSame(dummyPi, recordedAlarms[0].operation)
        assertTrue(recordedAlarms[0].exact)

        // Handler must be capped at 20_000L
        assertEquals(1, postedHandlers.size)
        assertEquals(20_000L, postedHandlers[0].second)
    }

    @Test
    fun scheduleFallsBackToSetAndAllowWhileIdleOnSecurityException() {
        val currentWallClock = 1_000_000_000L
        val currentElapsed = 50_000L
        val recordedAlarms = mutableListOf<TestAlarmRecord>()
        val dummyPi = createDummyPendingIntent()

        val alarmPublisher = FakeAlarmPublisher(
            onSetExact = { _, _, _ ->
                throw SecurityException("Caller not allowed to schedule exact alarms")
            },
            onSetInexact = { type, trigger, op ->
                recordedAlarms.add(TestAlarmRecord(type, trigger, op, exact = false))
            }
        )

        val scheduler = AndroidAppRuleWakeScheduler(
            context = StubContext(),
            alarmPublisher = alarmPublisher,
            handlerPostDelayed = { _, _ -> true },
            handlerRemoveCallbacks = { },
            wallClockMs = { currentWallClock },
            elapsedRealtimeMs = { currentElapsed },
            pendingIntentFactory = { _, _ -> dummyPi },
            cancelPendingIntent = { },
            onNonFatalError = { }
        )

        scheduler.schedule("pkg.fallback", dueAtWallClockMs = 1_000_010_000L, token = 202L)

        assertEquals(1, recordedAlarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, recordedAlarms[0].type)
        assertEquals(60_000L, recordedAlarms[0].triggerAtMillis)
        assertSame(dummyPi, recordedAlarms[0].operation)
        assertFalse(recordedAlarms[0].exact)
    }

    @Test
    fun reschedulingSameKeyAtomicallyCancelsPreviousAlarmAndHandler() {
        val currentWallClock = 1_000_000_000L
        val currentElapsed = 50_000L
        val cancelledPendingIntents = mutableListOf<PendingIntent>()
        val removedRunnables = mutableListOf<Runnable>()
        val postedHandlers = mutableListOf<Pair<Runnable, Long>>()
        val pi1 = createDummyPendingIntent()
        val pi2 = createDummyPendingIntent()
        var callCount = 0

        val scheduler = AndroidAppRuleWakeScheduler(
            context = StubContext(),
            alarmPublisher = FakeAlarmPublisher(),
            handlerPostDelayed = { runnable, delay ->
                postedHandlers.add(runnable to delay)
                true
            },
            handlerRemoveCallbacks = { removedRunnables.add(it) },
            wallClockMs = { currentWallClock },
            elapsedRealtimeMs = { currentElapsed },
            pendingIntentFactory = { _, _ ->
                callCount++
                if (callCount == 1) pi1 else pi2
            },
            cancelPendingIntent = { cancelledPendingIntents.add(it) },
            onNonFatalError = { throw it }
        )

        scheduler.schedule("pkg.replace", dueAtWallClockMs = 1_000_030_000L, token = 1L)
        assertEquals(1, postedHandlers.size)
        val firstRunnable = postedHandlers[0].first

        scheduler.schedule("pkg.replace", dueAtWallClockMs = 1_000_015_000L, token = 2L)
        assertEquals(2, postedHandlers.size)
        assertEquals(1, cancelledPendingIntents.size)
        assertSame(pi1, cancelledPendingIntents[0])
        assertEquals(1, removedRunnables.size)
        assertEquals(firstRunnable, removedRunnables[0])
    }

    @Test
    fun alarmDeliveryNotifiesWakeAndRemovesHandlerCallback() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val removedRunnables = mutableListOf<Runnable>()
        val postedHandlers = mutableListOf<Pair<Runnable, Long>>()
        val dummyPi = createDummyPendingIntent()

        val scheduler = AndroidAppRuleWakeScheduler(
            context = StubContext(),
            alarmPublisher = FakeAlarmPublisher(),
            handlerPostDelayed = { runnable, delay ->
                postedHandlers.add(runnable to delay)
                true
            },
            handlerRemoveCallbacks = { removedRunnables.add(it) },
            wallClockMs = { 1_000_000_000L },
            elapsedRealtimeMs = { 50_000L },
            pendingIntentFactory = { _, _ -> dummyPi },
            cancelPendingIntent = { },
            onNonFatalError = { throw it },
            onWake = { key, token -> wakes.add(key to token) }
        )

        scheduler.schedule("pkg.wake", dueAtWallClockMs = 1_000_010_000L, token = 99L)
        assertEquals(1, postedHandlers.size)

        scheduler.onAlarmTriggered("pkg.wake", 99L)

        assertEquals(listOf("pkg.wake" to 99L), wakes)
        assertEquals(1, removedRunnables.size)
        assertEquals(postedHandlers[0].first, removedRunnables[0])

        // Second delivery with same token must be ignored (already fired)
        scheduler.onAlarmTriggered("pkg.wake", 99L)
        assertEquals(listOf("pkg.wake" to 99L), wakes)
    }

    @Test
    fun handlerFiringNotifiesWakeAndCancelsAlarm() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val cancelledPendingIntents = mutableListOf<PendingIntent>()
        val postedHandlers = mutableListOf<Pair<Runnable, Long>>()
        val dummyPi = createDummyPendingIntent()

        val scheduler = AndroidAppRuleWakeScheduler(
            context = StubContext(),
            alarmPublisher = FakeAlarmPublisher(),
            handlerPostDelayed = { runnable, delay ->
                postedHandlers.add(runnable to delay)
                true
            },
            handlerRemoveCallbacks = { },
            wallClockMs = { 1_000_000_000L },
            elapsedRealtimeMs = { 50_000L },
            pendingIntentFactory = { _, _ -> dummyPi },
            cancelPendingIntent = { cancelledPendingIntents.add(it) },
            onNonFatalError = { throw it },
            onWake = { key, token -> wakes.add(key to token) }
        )

        scheduler.schedule("pkg.handler", dueAtWallClockMs = 1_000_010_000L, token = 88L)
        assertEquals(1, postedHandlers.size)

        // Execute posted runnable
        postedHandlers[0].first.run()

        assertEquals(listOf("pkg.handler" to 88L), wakes)
        assertEquals(1, cancelledPendingIntents.size)
        assertSame(dummyPi, cancelledPendingIntents[0])

        // Alarm arriving afterwards should be ignored
        scheduler.onAlarmTriggered("pkg.handler", 88L)
        assertEquals(listOf("pkg.handler" to 88L), wakes)
    }

    @Test
    fun cancelAndCancelAllCancelAlarmsAndHandlers() {
        val cancelledPendingIntents = mutableListOf<PendingIntent>()
        val removedRunnables = mutableListOf<Runnable>()
        val piA = createDummyPendingIntent()
        val piB = createDummyPendingIntent()
        val piC = createDummyPendingIntent()

        val scheduler = AndroidAppRuleWakeScheduler(
            context = StubContext(),
            alarmPublisher = FakeAlarmPublisher(),
            handlerPostDelayed = { _, _ -> true },
            handlerRemoveCallbacks = { removedRunnables.add(it) },
            wallClockMs = { 1_000_000_000L },
            elapsedRealtimeMs = { 50_000L },
            pendingIntentFactory = { key, _ ->
                when (key) {
                    "pkg.a" -> piA
                    "pkg.b" -> piB
                    else -> piC
                }
            },
            cancelPendingIntent = { cancelledPendingIntents.add(it) },
            onNonFatalError = { throw it }
        )

        scheduler.schedule("pkg.a", dueAtWallClockMs = 1_000_010_000L, token = 1L)
        scheduler.cancel("pkg.a")
        assertEquals(1, cancelledPendingIntents.size)
        assertSame(piA, cancelledPendingIntents[0])
        assertEquals(1, removedRunnables.size)

        scheduler.schedule("pkg.b", dueAtWallClockMs = 1_000_010_000L, token = 2L)
        scheduler.schedule("pkg.c", dueAtWallClockMs = 1_000_020_000L, token = 3L)
        scheduler.cancelAll()
        assertEquals(3, cancelledPendingIntents.size)
        assertEquals(3, removedRunnables.size)
    }
}
