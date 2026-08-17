package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleGuardianSkip
import neth.iecal.curbox.data.models.AppRuleOverrideState

/** Pure operations for rule-scoped guardian approvals. */
object AppRuleGuardianOverrides {
    /**
     * Returns only structurally valid records for the requested use-day generation. Expired skip
     * intervals intentionally stay in the state: they are the durable ledger that subtracts
     * foreground time that happened while the rule was skipped.
     */
    @Suppress("UNUSED_PARAMETER")
    fun normalize(
        state: AppRuleOverrideState,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): AppRuleOverrideState {
        if (state.useDayId != useDayId ||
            state.useDayGenerationStartedAtMs != useDayGenerationStartedAtMs
        ) {
            return AppRuleOverrideState(
                useDayId = useDayId,
                useDayGenerationStartedAtMs = useDayGenerationStartedAtMs
            )
        }
        return state.copy(
            grants = state.grants.filter {
                it.ruleId.isNotBlank() &&
                    it.useDayId == useDayId &&
                    it.grantedMillis > 0L &&
                    it.grantedAtMs >= 0L
            },
            skips = state.skips.filter {
                it.ruleId.isNotBlank() &&
                    it.useDayId == useDayId &&
                    it.skipFromMs >= 0L &&
                    it.skipUntilMs > it.skipFromMs
            }
        )
    }

    /** Merges overlapping and adjacent skip intervals without losing their exclusion history. */
    fun compact(
        state: AppRuleOverrideState,
        useDayId: String,
        useDayGenerationStartedAtMs: Long = 0L
    ): AppRuleOverrideState {
        val normalized = normalize(
            state,
            useDayId,
            nowMs = Long.MAX_VALUE,
            useDayGenerationStartedAtMs = useDayGenerationStartedAtMs
        )
        val merged = normalized.skips
            .groupBy { it.ruleId }
            .values
            .flatMap { intervals ->
                intervals.sortedWith(compareBy<AppRuleGuardianSkip> { it.skipFromMs }
                    .thenBy { it.skipUntilMs })
                    .fold(mutableListOf<AppRuleGuardianSkip>()) { result, interval ->
                        val previous = result.lastOrNull()
                        if (previous != null && interval.skipFromMs <= previous.skipUntilMs) {
                            result[result.lastIndex] = previous.copy(
                                skipUntilMs = maxOf(previous.skipUntilMs, interval.skipUntilMs)
                            )
                        } else {
                            result += interval
                        }
                        result
                    }
            }
            .sortedWith(compareBy<AppRuleGuardianSkip> { it.ruleId }.thenBy { it.skipFromMs })
        return normalized.copy(skips = merged)
    }

    /** Repeated grants accumulate without replacing an earlier grant. */
    fun grant(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        grantedMillis: Long,
        grantedAtMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): AppRuleOverrideState {
        require(ruleId.isNotBlank()) { "rule id must not be blank" }
        require(useDayId.isNotBlank()) { "use day id must not be blank" }
        require(grantedMillis > 0L) { "grant must be positive" }
        val current = state.forUseDay(useDayId, useDayGenerationStartedAtMs)
        return current.copy(
            grants = current.grants + AppRuleGuardianGrant(
                ruleId = ruleId,
                useDayId = useDayId,
                grantedAtMs = grantedAtMs.coerceAtLeast(0L),
                grantedMillis = grantedMillis
            )
        )
    }

    /** A skip is never allowed to cross the current use-day reset. */
    fun skipUntil(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        selectedUntilMs: Long,
        nextResetAtMs: Long,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): AppRuleOverrideState {
        require(ruleId.isNotBlank()) { "rule id must not be blank" }
        require(useDayId.isNotBlank()) { "use day id must not be blank" }
        require(nextResetAtMs > nowMs) { "next reset must be in the future" }
        val current = state.forUseDay(useDayId, useDayGenerationStartedAtMs)
        val capped = selectedUntilMs.coerceIn(nowMs, nextResetAtMs)
        if (capped <= nowMs) return current
        return current.copy(
            skips = current.skips + AppRuleGuardianSkip(
                ruleId = ruleId,
                useDayId = useDayId,
                skipUntilMs = capped,
                skipFromMs = nowMs
            )
        )
    }

    fun grantMillisForRule(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): Long = grantsForRule(state, ruleId, useDayId, nowMs, useDayGenerationStartedAtMs)
        .fold(0L) { total, grant ->
            if (Long.MAX_VALUE - total < grant.grantedMillis) Long.MAX_VALUE
            else total + grant.grantedMillis
        }

    fun grantsForRule(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): List<AppRuleGuardianGrant> = normalize(
        state,
        useDayId,
        nowMs,
        useDayGenerationStartedAtMs
    ).grants.filter { it.ruleId == ruleId && it.grantedAtMs <= nowMs }

    fun skipsForRule(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): List<AppRuleGuardianSkip> = normalize(
        state,
        useDayId,
        nowMs,
        useDayGenerationStartedAtMs
    ).skips.filter { it.ruleId == ruleId }

    fun isSkipped(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): Boolean = skipsForRule(state, ruleId, useDayId, nowMs, useDayGenerationStartedAtMs)
        .any { it.skipFromMs <= nowMs && it.skipUntilMs > nowMs }

    /** Earliest start or end boundary that should wake the service even with zero quota left. */
    fun nextSkipBoundaryMs(
        state: AppRuleOverrideState,
        useDayId: String,
        nowMs: Long,
        useDayGenerationStartedAtMs: Long = 0L
    ): Long? = normalize(state, useDayId, nowMs, useDayGenerationStartedAtMs).skips
        .asSequence()
        .flatMap { skip -> sequenceOf(skip.skipFromMs, skip.skipUntilMs) }
        .filter { it > nowMs }
        .minOrNull()
}
