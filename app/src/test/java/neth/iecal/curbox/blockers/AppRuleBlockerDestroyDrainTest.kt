package neth.iecal.curbox.blockers

import neth.iecal.curbox.domain.apprules.FakeWakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppRuleBlockerDestroyDrainTest {

    private fun hasField(target: Any, name: String): Boolean =
        try {
            target.javaClass.getDeclaredField(name)
            true
        } catch (e: NoSuchFieldException) {
            false
        }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invokePrivate(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first {
            (it.name == name || it.name.startsWith("$name-")) && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    @Test
    fun residualDeadlineDrainCountersAreCleanedUp() {
        val blocker = AppRuleBlocker()
        // Residual inFlight counters must no longer exist as fields on AppRuleBlocker
        assertFalse("inFlightRefreshes must be removed", hasField(blocker, "inFlightRefreshes"))
        assertFalse("inFlightNotifications must be removed", hasField(blocker, "inFlightNotifications"))
        assertFalse("inFlightUsageResetCompletions must be removed", hasField(blocker, "inFlightUsageResetCompletions"))
        assertFalse("inFlightRecheckPlans must be removed", hasField(blocker, "inFlightRecheckPlans"))
        assertTrue("inFlightCallbacks must remain", hasField(blocker, "inFlightCallbacks"))
    }

    @Test
    fun destroyDrainMeasurementTracksCallbacksAndDrainsCleanly() {
        val blocker = AppRuleBlocker().apply {
            elapsedRealtimeMsProvider = { 10_000L }
            wakeScheduler = FakeWakeScheduler()
        }
        val inFlightCallbacks = getField(blocker, "inFlightCallbacks") as AtomicInteger
        assertEquals(0, inFlightCallbacks.get())

        val measurement = blocker.onDestroyForMeasurement(totalDrainBudgetMs = 100L)
        assertEquals(10_000L, measurement.requestedAtElapsedMs)
        assertEquals(10_100L, measurement.deadlineElapsedMs)
        assertEquals(0, measurement.workAtInvalidation.callbacks)
        assertEquals(0, measurement.workAtCompletion.callbacks)
        assertTrue(measurement.completed)
    }

    @Test
    fun productionOnDestroyTransitionsDestroyedAndCancelsScope() {
        val fakeScheduler = FakeWakeScheduler()
        val blocker = AppRuleBlocker().apply {
            elapsedRealtimeMsProvider = { 10_000L }
            wakeScheduler = fakeScheduler
        }
        setField(blocker, "setupReady", true)
        assertFalse(getField(blocker, "destroyed") as Boolean)

        blocker.onDestroy()

        assertTrue(getField(blocker, "destroyed") as Boolean)
        assertFalse(getField(blocker, "setupReady") as Boolean)
        val scope = getField(blocker, "scope") as kotlinx.coroutines.CoroutineScope
        assertFalse("coroutine scope must be cancelled on destroy", scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
    }

    @Test
    fun externalEffectPermitTracksInFlightCallbacksLifecycle() {
        val blocker = AppRuleBlocker().apply {
            wakeScheduler = FakeWakeScheduler()
        }
        setField(blocker, "setupReady", true)
        val inFlightCallbacks = getField(blocker, "inFlightCallbacks") as AtomicInteger
        assertEquals(0, inFlightCallbacks.get())

        // Reserve an external effect
        val isCurrent: () -> Boolean = { true }
        val permit = invokePrivate(blocker, "reserveExternalEffectLocked", isCurrent)
        assertEquals(1, inFlightCallbacks.get())

        // Finish the external effect
        invokePrivate(blocker, "finishExternalEffect", permit)
        assertEquals(0, inFlightCallbacks.get())
    }
}
