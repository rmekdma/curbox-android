package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleReevaluationGateTest {
    @Test
    fun overrideApprovalForcesTheNextApplicableAllRuleCheckButNotAnUnrelatedPackage() {
        val gate = AppRuleReevaluationGate()
        gate.markOverrideChanged()

        assertFalse(gate.consumeIfApplicable(hasApplicableRules = false))
        assertTrue(gate.consumeIfApplicable(hasApplicableRules = true))
        assertFalse(gate.consumeIfApplicable(hasApplicableRules = true))
    }
}
