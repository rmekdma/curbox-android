package neth.iecal.curbox.domain.apprules

/**
 * Deterministic, virtual-clock test adapter for [AppRuleWakeScheduler].
 *
 * It manages virtual wall clock and elapsed realtime timestamps without real thread sleep
 * or Android framework dependencies.
 */
class FakeWakeScheduler(
    initialWallClockMs: Long = 1_000_000_000L,
    initialElapsedRealtimeMs: Long = 10_000L,
    override var onWake: ((key: String, token: Long) -> Unit)? = null
) : AppRuleWakeScheduler {

    companion object {
        const val MAX_HANDLER_DELAY_MS = 20_000L
    }

    var currentWallClockMs: Long = initialWallClockMs
    var currentElapsedRealtimeMs: Long = initialElapsedRealtimeMs

    data class ScheduledWake(
        val key: String,
        val token: Long,
        val dueAtWallClockMs: Long,
        val scheduledElapsedRealtimeMs: Long,
        val alarmDueElapsedMs: Long,
        val handlerDueElapsedMs: Long
    ) {
        val effectiveDueElapsedMs: Long
            get() = minOf(alarmDueElapsedMs, handlerDueElapsedMs)
    }

    private val lock = Any()
    private val scheduled = mutableMapOf<String, ScheduledWake>()
    val cancellationHistory = mutableListOf<String>()

    override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
        synchronized(lock) {
            val existing = scheduled.remove(key)
            if (existing != null) {
                cancellationHistory.add(key)
            }
            val delayMs = AppRuleWallClockScheduler.delayUntil(
                dueAtWallClockMs = dueAtWallClockMs,
                nowWallClockMs = currentWallClockMs,
                nowElapsedRealtimeMs = currentElapsedRealtimeMs
            )
            val alarmDue = currentElapsedRealtimeMs + delayMs
            val handlerDelay = minOf(delayMs, MAX_HANDLER_DELAY_MS).coerceAtLeast(0L)
            val handlerDue = currentElapsedRealtimeMs + handlerDelay

            scheduled[key] = ScheduledWake(
                key = key,
                token = token,
                dueAtWallClockMs = dueAtWallClockMs,
                scheduledElapsedRealtimeMs = currentElapsedRealtimeMs,
                alarmDueElapsedMs = alarmDue,
                handlerDueElapsedMs = handlerDue
            )
        }
    }

    override fun cancel(key: String) {
        synchronized(lock) {
            val removed = scheduled.remove(key)
            if (removed != null) {
                cancellationHistory.add(key)
            }
        }
    }

    override fun cancelAll() {
        synchronized(lock) {
            cancellationHistory.addAll(scheduled.keys)
            scheduled.clear()
        }
    }

    fun hasScheduled(key: String): Boolean = synchronized(lock) {
        scheduled.containsKey(key)
    }

    fun getScheduled(key: String): ScheduledWake? = synchronized(lock) {
        scheduled[key]
    }

    fun scheduledCount(): Int = synchronized(lock) {
        scheduled.size
    }

    fun scheduledKeys(): Set<String> = synchronized(lock) {
        scheduled.keys.toSet()
    }

    /**
     * Advances virtual time for both clocks and triggers any wakes that became due.
     */
    fun advanceTimeBy(millis: Long) {
        require(millis >= 0L) { "millis must be non-negative: $millis" }
        synchronized(lock) {
            currentWallClockMs += millis
            currentElapsedRealtimeMs += millis
        }
        drainDueWakes()
    }

    /**
     * Advances virtual time to explicit clock values and triggers any wakes that became due.
     */
    fun advanceTimeTo(wallClockMs: Long, elapsedRealtimeMs: Long) {
        require(wallClockMs >= currentWallClockMs) { "wall clock cannot move backwards" }
        require(elapsedRealtimeMs >= currentElapsedRealtimeMs) { "elapsed time cannot move backwards" }
        synchronized(lock) {
            currentWallClockMs = wallClockMs
            currentElapsedRealtimeMs = elapsedRealtimeMs
        }
        drainDueWakes()
    }

    /**
     * Manually triggers the wake for [key] if currently scheduled.
     * Returns true if triggered, false if not scheduled.
     */
    fun triggerWake(key: String): Boolean {
        val entry = synchronized(lock) {
            scheduled.remove(key)
        } ?: return false

        onWake?.invoke(entry.key, entry.token)
        return true
    }

    private fun drainDueWakes() {
        val dueEntries = mutableListOf<ScheduledWake>()
        synchronized(lock) {
            val iterator = scheduled.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.effectiveDueElapsedMs <= currentElapsedRealtimeMs) {
                    iterator.remove()
                    dueEntries.add(entry)
                }
            }
        }
        // Dispatch outside the lock, ordered by earliest due time
        dueEntries.sortedBy { it.effectiveDueElapsedMs }.forEach { entry ->
            onWake?.invoke(entry.key, entry.token)
        }
    }
}
