package neth.iecal.curbox.domain.apprules

/**
 * Decides which local records are needed for the current settings. Statistics are a display and
 * sync preference; an active time rule must still have a short lived ledger to enforce its limit.
 */
data class AppUsageTrackingDecision(
    val recordStatistics: Boolean,
    val recordEnforcementLedger: Boolean
) {
    val shouldRecordSessions: Boolean
        get() = recordStatistics || recordEnforcementLedger
}

object AppUsageTrackingPolicy {
    fun decide(
        statisticsTrackingEnabled: Boolean,
        hasActiveTimeBasedRules: Boolean
    ): AppUsageTrackingDecision = AppUsageTrackingDecision(
        recordStatistics = statisticsTrackingEnabled,
        recordEnforcementLedger = hasActiveTimeBasedRules
    )

    /** A policy change is a persistence boundary so each interval keeps its old write policy. */
    fun requiresSessionBoundary(
        previous: AppUsageTrackingDecision,
        next: AppUsageTrackingDecision
    ): Boolean = previous != next
}
