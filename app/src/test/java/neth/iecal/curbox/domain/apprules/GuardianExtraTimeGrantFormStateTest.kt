package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardianExtraTimeGrantFormStateTest {
    @Test
    fun changingRulesClearsBothInputsAndLateReadsCannotReplaceTheNewSelection() {
        val original = GuardianExtraTimeGrantBasis("rule-a", "day", 0L, 4 * MINUTE)
        val other = GuardianExtraTimeGrantBasis("rule-b", "day", 0L, 31 * MINUTE)
        val edited = GuardianExtraTimeGrantFormState.initial(original)
            .editAdditionalMinutes("5")

        val selectingOther = edited.selectRule("rule-b")
        val withLateOldRead = selectingOther.receiveBasis(0L, original)
        val readyForOther = selectingOther.receiveBasis(
            selectingOther.selectionRevision,
            other
        )

        assertNull(withLateOldRead.basis)
        assertEquals("rule-b", withLateOldRead.selectedRuleId)
        assertEquals("", readyForOther.input.additionalMinutesText)
        assertEquals("", readyForOther.input.totalMinutesText)
        assertEquals(31L, readyForOther.input.currentTotalMinutes)
    }

    @Test
    fun changedBasisClearsDeltaAndTargetForReconfirmation() {
        val original = GuardianExtraTimeGrantBasis("rule", "day", 0L, 20 * MINUTE)
        val changed = original.copy(currentTotalMillis = 25 * MINUTE)
        val form = GuardianExtraTimeGrantFormState.initial(original)
            .editTotalMinutes("40")

        val refreshed = form.receiveBasis(form.selectionRevision, changed)

        assertEquals(25L, refreshed.input.currentTotalMinutes)
        assertEquals("", refreshed.input.additionalMinutesText)
        assertEquals("", refreshed.input.totalMinutesText)
    }

    private companion object {
        const val MINUTE = GuardianExtraTimeFormState.MILLIS_PER_MINUTE
    }
}
