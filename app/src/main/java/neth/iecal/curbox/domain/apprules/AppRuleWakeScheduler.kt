package neth.iecal.curbox.domain.apprules

/**
 * Encapsulates platform alarm and handler scheduling for app-rule evaluations.
 */
interface AppRuleWakeScheduler {
    /**
     * Callback invoked when an alarm or handler delay arrives.
     * Receives the scheduling [key] and the matching [token].
     */
    var onWake: ((key: String, token: Long) -> Unit)?

    /**
     * Schedules a wake for [key] at wall clock millisecond [dueAtWallClockMs] with unique [token].
     * Any existing alarm or handler for [key] is atomically replaced and cancelled.
     */
    fun schedule(key: String, dueAtWallClockMs: Long, token: Long)

    /**
     * Cancels any scheduled wake for [key].
     */
    fun cancel(key: String)

    /**
     * Cancels all scheduled wakes.
     */
    fun cancelAll()

    companion object {
        const val MAX_HANDLER_DELAY_MS = 20_000L
    }
}
