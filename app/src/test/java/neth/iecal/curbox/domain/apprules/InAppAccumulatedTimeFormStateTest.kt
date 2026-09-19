package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppAccumulatedTimeFormStateTest {

    @Test
    fun initialLoadsCurrentAccumulatedMinutes() {
        val state = InAppAccumulatedTimeFormState.initial(35L)

        assertEquals(35L, state.currentAccumulatedMinutes)
        assertEquals("35", state.accumulatedMinutesText)
    }

    @Test
    fun initialCoercesNegativeInputToZero() {
        val state = InAppAccumulatedTimeFormState.initial(-10L)

        assertEquals(0L, state.currentAccumulatedMinutes)
        assertEquals("0", state.accumulatedMinutesText)
    }

    @Test
    fun editMinutesUpdatesText() {
        val state = InAppAccumulatedTimeFormState.initial(10L)
            .editMinutes("45")

        assertEquals("45", state.accumulatedMinutesText)
    }

    @Test
    fun submittingValidPositiveNumberSucceeds() {
        val state = InAppAccumulatedTimeFormState.initial(10L)
            .editMinutes("60")

        val submission = state.submit()

        assertTrue(submission is InAppAccumulatedTimeSubmission.Valid)
        assertEquals(60L, (submission as InAppAccumulatedTimeSubmission.Valid).minutes)
    }

    @Test
    fun submittingZeroSucceedsForReset() {
        val state = InAppAccumulatedTimeFormState.initial(30L)
            .editMinutes("0")

        val submission = state.submit()

        assertTrue(submission is InAppAccumulatedTimeSubmission.Valid)
        assertEquals(0L, (submission as InAppAccumulatedTimeSubmission.Valid).minutes)
    }

    @Test
    fun submittingBlankOrWhitespaceFails() {
        val state = InAppAccumulatedTimeFormState.initial(10L)
            .editMinutes("   ")

        val submission = state.submit()

        assertTrue(submission is InAppAccumulatedTimeSubmission.Invalid)
        assertEquals(
            InAppAccumulatedTimeValidationError.INVALID_MINUTES,
            (submission as InAppAccumulatedTimeSubmission.Invalid).error
        )
    }

    @Test
    fun submittingNegativeNumberFails() {
        val state = InAppAccumulatedTimeFormState.initial(10L)
            .editMinutes("-5")

        val submission = state.submit()

        assertTrue(submission is InAppAccumulatedTimeSubmission.Invalid)
        assertEquals(
            InAppAccumulatedTimeValidationError.INVALID_MINUTES,
            (submission as InAppAccumulatedTimeSubmission.Invalid).error
        )
    }

    @Test
    fun submittingNonNumericStringFails() {
        val state = InAppAccumulatedTimeFormState.initial(10L)
            .editMinutes("abc")

        val submission = state.submit()

        assertTrue(submission is InAppAccumulatedTimeSubmission.Invalid)
        assertEquals(
            InAppAccumulatedTimeValidationError.INVALID_MINUTES,
            (submission as InAppAccumulatedTimeSubmission.Invalid).error
        )
    }

    @Test
    fun submittingOverflowFails() {
        val state = InAppAccumulatedTimeFormState.initial(10L)
            .editMinutes("9999999999999999999999999")

        val submission = state.submit()

        assertTrue(submission is InAppAccumulatedTimeSubmission.Invalid)
        assertEquals(
            InAppAccumulatedTimeValidationError.INVALID_MINUTES,
            (submission as InAppAccumulatedTimeSubmission.Invalid).error
        )
    }
}
