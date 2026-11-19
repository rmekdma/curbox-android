package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleRestConfig

/** Owned by the serialized decision worker; times are monotonic elapsed milliseconds. */
internal class RestCycleTracker {
    private data class Interval(val start: Long, val end: Long)
    private data class Cycle(
        var config: AppRuleRestConfig,
        var lastAt: Long,
        var using: Boolean?,
        var started: Boolean = false,
        var used: Long = 0L,
        val rests: MutableList<Interval> = mutableListOf()
    )

    private val cycles = mutableMapOf<String, Cycle>()

    /** Null mode ends observation without inventing time during an unknown interval. */
    fun observe(configs: Map<String, AppRuleRestConfig>, modes: Map<String, Boolean?>, at: Long) {
        cycles.keys.retainAll(configs.keys)
        configs.forEach { (id, config) ->
            var cycle = cycles[id]
            if (cycle == null) {
                cycle = Cycle(config, at, null)
                cycles[id] = cycle
            }
            if (at < cycle.lastAt) return@forEach
            val mode = modes[id]
            if (mode != null) advance(cycle, at)
            cycle.lastAt = at
            cycle.config = config
            if (cycle.started && restAt(cycle, 0L, millis(config.restWindowMinutes)) >= millis(config.requiredRestMinutes)) {
                cycle.started = false
                cycle.used = 0L
                cycle.rests.clear()
            }
            cycle.using = mode
            if (mode == true && !cycle.started) cycle.started = true
        }
    }

    fun usedMillis(id: String): Long = cycles[id]?.used ?: 0L

    /** Earliest boundary assuming the most recently observed mode continues. */
    fun nextDelayMillis(): Long? = cycles.values.mapNotNull { cycle ->
        if (!cycle.started) return@mapNotNull null
        when (cycle.using) {
            true -> (millis(cycle.config.usageLimitMinutes) - cycle.used).takeIf { it > 0L }
            false -> restCompletionDelay(cycle)
            null -> null
        }
    }.minOrNull()

    private fun advance(cycle: Cycle, at: Long) {
        if (!cycle.started || at <= cycle.lastAt) return
        when (cycle.using) {
            true -> cycle.used = safeAdd(cycle.used, at - cycle.lastAt)
            false -> {
                val delay = restCompletionDelay(cycle)
                if (delay != null && delay <= at - cycle.lastAt) {
                    cycle.started = false
                    cycle.used = 0L
                    cycle.rests.clear()
                } else {
                    val last = cycle.rests.lastOrNull()
                    if (last != null && last.end == cycle.lastAt) {
                        cycle.rests[cycle.rests.lastIndex] = Interval(last.start, at)
                    } else {
                        cycle.rests.add(Interval(cycle.lastAt, at))
                    }
                    prune(cycle, at)
                }
            }
            null -> Unit
        }
    }

    private fun restCompletionDelay(cycle: Cycle): Long? {
        val needed = millis(cycle.config.requiredRestMinutes)
        val window = millis(cycle.config.restWindowMinutes)
        if (needed <= 0L || window < needed) return null
        var low = 0L
        var high = window
        while (low < high) {
            val mid = low + (high - low) / 2L
            if (restAt(cycle, mid, window) >= needed) high = mid else low = mid + 1L
        }
        return low.coerceAtLeast(1L)
    }

    private fun restAt(cycle: Cycle, delay: Long, window: Long): Long {
        val end = safeAdd(cycle.lastAt, delay)
        val windowStart = (end - window).coerceAtLeast(0L)
        var total = (end - maxOf(cycle.lastAt, windowStart)).coerceAtLeast(0L)
        cycle.rests.forEach { interval ->
            total = safeAdd(total, (minOf(interval.end, end) - maxOf(interval.start, windowStart)).coerceAtLeast(0L))
        }
        return total
    }

    private fun prune(cycle: Cycle, at: Long) {
        val start = (at - millis(cycle.config.restWindowMinutes)).coerceAtLeast(0L)
        cycle.rests.removeAll { it.end <= start }
    }

    private fun millis(minutes: Long): Long =
        if (minutes > Long.MAX_VALUE / 60_000L) Long.MAX_VALUE else minutes * 60_000L

    private fun safeAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
