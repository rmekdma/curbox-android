package neth.iecal.curbox.trackers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSessionOwnershipTest {
    @Test
    fun failedHandoffKeepsTrackerOwnershipForFallback() {
        val ownership = ForegroundSessionOwnership()

        val handoff = runCatching {
            ownership.handoffToWorker { error("handoff failed") }
        }

        assertTrue(handoff.isFailure)
        var callbackRan = false
        assertTrue(ownership.runIfTrackerOwner { callbackRan = true })
        assertTrue(callbackRan)
    }

    @Test
    fun handoffWaitsForTrackerCallbackAndRejectsLaterTrackerMutations() {
        val ownership = ForegroundSessionOwnership()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val handoffFinished = CountDownLatch(1)
        val mutations = AtomicInteger()

        val callback = thread {
            ownership.runIfTrackerOwner {
                callbackEntered.countDown()
                releaseCallback.await()
                mutations.incrementAndGet()
            }
        }
        assertTrue(callbackEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        val handoff = thread {
            ownership.handoffToWorker()
            handoffFinished.countDown()
        }
        assertFalse(handoffFinished.await(100L, TimeUnit.MILLISECONDS))

        releaseCallback.countDown()
        assertTrue(handoffFinished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        callback.join(WAIT_TIMEOUT_MS)
        handoff.join(WAIT_TIMEOUT_MS)

        assertFalse(ownership.runIfTrackerOwner { mutations.incrementAndGet() })
        assertEquals(1, mutations.get())
    }

    companion object {
        private const val WAIT_TIMEOUT_MS = 2_000L
    }
}
