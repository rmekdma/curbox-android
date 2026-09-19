package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.RuleRolloverPool

/** Pure selection seam for the warning surface's one-rule-at-a-time approval contract. */
object GuardianApprovalSelection {
    fun selectedDenial(
        denials: List<AppRuleGuardianDenial>,
        selectedIndex: Int
    ): AppRuleGuardianDenial? = denials.getOrNull(selectedIndex)

    data class AccumulatedTimeButtonState(
        val isVisible: Boolean,
        val accumulatedMinutes: Long
    )

    fun resolveAccumulatedButtonState(
        rule: AppRule?,
        pool: RuleRolloverPool?,
        useDayId: String
    ): AccumulatedTimeButtonState {
        if (rule == null || !rule.rolloverEnabled) {
            return AccumulatedTimeButtonState(isVisible = false, accumulatedMinutes = 0L)
        }
        val isUnlockDay = AppRuleRolloverPolicy.dayRoleFor(useDayId, rule.unlockDays) == AppRuleDayRole.UNLOCK
        val minutes = pool?.accumulatedMinutes?.coerceAtLeast(0L) ?: 0L
        val isVisible = isUnlockDay && minutes > 0L
        return AccumulatedTimeButtonState(isVisible = isVisible, accumulatedMinutes = minutes)
    }
}
