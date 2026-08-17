package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleGuardianSkip
import neth.iecal.curbox.data.models.AppRuleOverrideState

/** Pure operations for rule-scoped guardian approvals. */
object AppRuleGuardianOverrides {
    fun normalize(state: AppRuleOverrideState, useDayId: String, nowMs: Long): AppRuleOverrideState {
        if (state.useDayId != useDayId) return AppRuleOverrideState(useDayId)
        return state.copy(
            grants = state.grants.filter {
                    it.ruleId.isNotBlank() &&
                        it.useDayId == useDayId &&
                        it.grantedMillis > 0L &&
                    it.grantedAtMs >= 0L &&
                    it.grantedAtMs <= nowMs
            },
            skips = state.skips.filter {
                it.ruleId.isNotBlank() &&
                    it.useDayId == useDayId &&
                    it.skipUntilMs > nowMs
            }
        )
    }

    /** Repeated grants accumulate without replacing an earlier grant. */
    fun grant(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        grantedMillis: Long,
        grantedAtMs: Long
    ): AppRuleOverrideState {
        require(ruleId.isNotBlank()) { "rule id must not be blank" }
        require(useDayId.isNotBlank()) { "use day id must not be blank" }
        require(grantedMillis > 0L) { "grant must be positive" }
        val current = state.forUseDay(useDayId)
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
        nowMs: Long
    ): AppRuleOverrideState {
        require(ruleId.isNotBlank()) { "rule id must not be blank" }
        require(useDayId.isNotBlank()) { "use day id must not be blank" }
        val capped = selectedUntilMs.coerceIn(nowMs, nextResetAtMs)
        val current = state.forUseDay(useDayId)
        return current.copy(
            skips = current.skips.filterNot { it.ruleId == ruleId } +
                AppRuleGuardianSkip(ruleId, useDayId, capped)
        )
    }

    fun grantMillisForRule(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long
    ): Long = normalize(state, useDayId, nowMs).grants
        .filter { it.ruleId == ruleId }
        .fold(0L) { total, grant ->
            if (Long.MAX_VALUE - total < grant.grantedMillis) Long.MAX_VALUE
            else total + grant.grantedMillis
        }

    fun grantsForRule(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long
    ): List<AppRuleGuardianGrant> = normalize(state, useDayId, nowMs).grants
        .filter { it.ruleId == ruleId }

    fun isSkipped(
        state: AppRuleOverrideState,
        ruleId: String,
        useDayId: String,
        nowMs: Long
    ): Boolean = normalize(state, useDayId, nowMs).skips.any {
        it.ruleId == ruleId && it.skipUntilMs > nowMs
    }
}
