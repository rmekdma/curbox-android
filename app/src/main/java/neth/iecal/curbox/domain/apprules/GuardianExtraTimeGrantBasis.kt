package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator

/** The form's authority to add minutes to one rule's current use-day total. */
data class GuardianExtraTimeGrantBasis(
    val ruleId: String,
    val useDayId: String,
    val useDayGenerationStartedAtMs: Long,
    val currentTotalMillis: Long
) {
    val currentTotalMinutes: Long
        get() = currentTotalMillis / GuardianExtraTimeFormState.MILLIS_PER_MINUTE

    companion object {
        fun capture(settings: Settings, ruleId: String, nowMs: Long): GuardianExtraTimeGrantBasis? {
            if (ruleId.isBlank() || settings.appRuleSnapshot.appRules.none {
                    it.id == ruleId && it.isActive && it.guardianExtraTimeAllowed
                }
            ) return null

            val useDayId = ConfigurableUseDayCalculator(
                resetTime = settings.useDayResetTime
            ).idAt(nowMs)
            return GuardianExtraTimeGrantBasis(
                ruleId = ruleId,
                useDayId = useDayId,
                useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
                currentTotalMillis = AppRuleGuardianOverrides.grantMillisForRule(
                    state = settings.appRuleOverrideState,
                    ruleId = ruleId,
                    useDayId = useDayId,
                    nowMs = nowMs,
                    useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
                    includeAccumulatedGrants = false
                )
            )
        }
    }
}
