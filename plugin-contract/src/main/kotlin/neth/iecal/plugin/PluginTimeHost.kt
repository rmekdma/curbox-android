package neth.iecal.plugin

data class PluginRule(
    val id: String,
    val label: String,
    val eligibleForContributions: Boolean,
    val lastSettledUseDayId: String?,
    val unlockDays: Set<Int> = emptySet(),
    val rolloverEnabled: Boolean = false
)

/** Host-owned rule information and opaque, plugin-scoped state captured together. */
data class PluginTimeState(
    val useDayId: String,
    val revision: String,
    val rules: List<PluginRule>,
    val linkedRuleId: String?,
    val data: String?
)

interface PluginTimeHost {
    suspend fun read(): PluginTimeState
    suspend fun update(expected: PluginTimeState, data: String?, linkedRuleId: String?): Boolean
    suspend fun settle(expected: PluginTimeState, endedUseDayId: String, minutes: Long, data: String): Boolean
}

interface AdditionalTimeProvider {
    suspend fun offer(ruleId: String): AdditionalTimeOffer?
    /** Records ended periods as part of the host's rollover write. This must be pure. */
    fun recordRollover(
        state: PluginTimeState, previouslySettledUseDayId: String?, settledUseDayId: String
    ): String? = null
    /** Returns true when outstanding work should be retried. */
    suspend fun settleEnded(): Boolean
}

data class AdditionalTimeOffer(
    val displayName: String,
    val proposedMinutes: Long,
    val maxMinutes: Long,
    val expected: PluginTimeState,
    val prepared: PreparedTimeContribution,
    val isCurrent: suspend () -> Boolean = { true }
)

fun interface PreparedTimeContribution {
    /** Holds any plugin-side validation for the duration of the host's atomic commit. */
    suspend fun withCurrentState(minutes: Long, commit: suspend (updatedData: String) -> Boolean): Boolean
}
