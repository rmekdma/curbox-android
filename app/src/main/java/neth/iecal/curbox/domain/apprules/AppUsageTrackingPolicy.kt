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
}

/** Concise public alias for callers that describe this as the tracking policy seam. */
object TrackingPolicy {
    fun decide(
        statisticsTrackingEnabled: Boolean,
        hasActiveTimeBasedRules: Boolean
    ): AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(
        statisticsTrackingEnabled,
        hasActiveTimeBasedRules
    )
}

object UsageTrackingPolicy {
    fun decide(
        statisticsTrackingEnabled: Boolean,
        hasActiveTimeBasedRules: Boolean
    ): AppUsageTrackingDecision = AppUsageTrackingPolicy.decide(
        statisticsTrackingEnabled,
        hasActiveTimeBasedRules
    )
}

/** Compatibility spelling for callers that treat this as a policy rather than a decision. */
typealias TrackingPolicyDecision = AppUsageTrackingDecision
