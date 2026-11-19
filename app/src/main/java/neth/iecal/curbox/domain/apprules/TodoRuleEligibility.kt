package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule

/** Whether a rule can receive Todo learning time. */
internal fun AppRule.isTodoAwardEligible(): Boolean =
    isActive && guardianExtraTimeAllowed && rolloverEnabled && unlockDays == setOf(0, 6)
