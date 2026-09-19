package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianAccumulatedTimeFormStateTest {

    @Test
    fun initialStatePreFillsTotalAccumulatedMinutes() {
        val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 45L)

        assertEquals(45L, state.totalAccumulatedMinutes)
        assertEquals("45", state.accumulatedMinutesText)
        assertEquals(
            GuardianAccumulatedTimeSubmission.Valid(approvedMinutes = 45L),
            state.submit()
        )
    }

    @Test
    fun oneTapApprovalWithoutEditsSucceedsWithTotalAccumulatedMinutes() {
        val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 60L)
        val submission = state.submit()

        assertTrue(submission is GuardianAccumulatedTimeSubmission.Valid)
        assertEquals(60L, (submission as GuardianAccumulatedTimeSubmission.Valid).approvedMinutes)
    }

    @Test
    fun partialApprovalWithSmallerNumberSucceeds() {
        val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 60L)
            .editMinutes("15")

        assertEquals("15", state.accumulatedMinutesText)
        val submission = state.submit()
        assertTrue(submission is GuardianAccumulatedTimeSubmission.Valid)
        assertEquals(15L, (submission as GuardianAccumulatedTimeSubmission.Valid).approvedMinutes)
    }

    @Test
    fun blankInputFailsWithInvalidMinutesToPreventAccidentalFullDepletion() {
        val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 60L)
            .editMinutes("")

        val submission = state.submit()
        assertEquals(
            GuardianAccumulatedTimeSubmission.Invalid(GuardianAccumulatedTimeValidationError.INVALID_MINUTES),
            submission
        )
    }

    @Test
    fun zeroOrNegativeInputFailsWithInvalidMinutes() {
        listOf("0", "-5", "not_a_number").forEach { input ->
            val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 60L)
                .editMinutes(input)

            assertEquals(
                GuardianAccumulatedTimeSubmission.Invalid(GuardianAccumulatedTimeValidationError.INVALID_MINUTES),
                state.submit()
            )
        }
    }

    @Test
    fun inputExceedingTotalAccumulatedMinutesFailsWithExceedsAccumulated() {
        val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 45L)
            .editMinutes("46")

        val submission = state.submit()
        assertEquals(
            GuardianAccumulatedTimeSubmission.Invalid(GuardianAccumulatedTimeValidationError.EXCEEDS_ACCUMULATED),
            submission
        )
    }

    @Test
    fun zeroTotalAccumulatedMinutesRejectsAnyPositiveInput() {
        val state = GuardianAccumulatedTimeFormState.initial(totalAccumulatedMinutes = 0L)
        assertEquals("0", state.accumulatedMinutesText)

        assertEquals(
            GuardianAccumulatedTimeSubmission.Invalid(GuardianAccumulatedTimeValidationError.INVALID_MINUTES),
            state.submit()
        )
    }
}
