package neth.iecal.curbox.trackers

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes the one-way handoff from tracker callbacks to the decision worker. */
internal class ForegroundSessionOwnership {
    private val lock = ReentrantLock()
    private val callbacksFinished = lock.newCondition()
    private var workerOwnsForeground = false
    private var activeTrackerCallbacks = 0

    fun runIfTrackerOwner(block: () -> Unit): Boolean {
        lock.withLock {
            if (workerOwnsForeground) return false
            activeTrackerCallbacks++
        }
        try {
            block()
            return true
        } finally {
            lock.withLock {
                activeTrackerCallbacks--
                if (activeTrackerCallbacks == 0) callbacksFinished.signalAll()
            }
        }
    }

    fun handoffToWorker(beforeHandoff: () -> Unit = {}) = lock.withLock {
        if (workerOwnsForeground) return@withLock
        while (activeTrackerCallbacks > 0) callbacksFinished.await()
        beforeHandoff()
        workerOwnsForeground = true
    }

    fun returnToTracker() = lock.withLock {
        workerOwnsForeground = false
    }

    fun isWorkerOwner(): Boolean = lock.withLock { workerOwnsForeground }
}
