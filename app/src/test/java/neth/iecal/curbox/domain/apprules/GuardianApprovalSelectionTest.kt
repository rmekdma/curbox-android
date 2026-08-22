package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianApprovalSelectionTest {
    private val denials = listOf(
        AppRuleGuardianDenial("first", "School", "No direct time left"),
        AppRuleGuardianDenial("second", "Night", "Condition not met"),
        AppRuleGuardianDenial("third", "Focus", "Rule is active")
    )

    @Test
    fun warningKeepsAllDenialsButResolvesExactlyOneSelectedRule() {
        assertEquals(listOf("first", "second", "third"), denials.map { it.ruleId })
        assertEquals("second", GuardianApprovalSelection.selectedDenial(denials, 1)?.ruleId)
        assertNull(GuardianApprovalSelection.selectedDenial(denials, -1))
        assertNull(GuardianApprovalSelection.selectedDenial(denials, denials.size))
    }
}
