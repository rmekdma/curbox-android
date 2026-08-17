package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleGuardianDenial

/** Pure selection seam for the warning surface's one-rule-at-a-time approval contract. */
object GuardianApprovalSelection {
    fun selectedDenial(
        denials: List<AppRuleGuardianDenial>,
        selectedIndex: Int
    ): AppRuleGuardianDenial? = denials.getOrNull(selectedIndex)
}
