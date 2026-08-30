package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianExtraTimeFormStateTest {
    @Test
    fun initialStateExposesCurrentTotalAndLeavesInputsEmpty() {
        val state = GuardianExtraTimeFormState.initial(currentTotalMinutes = 30L)

        assertEquals(30L, state.currentTotalMinutes)
        assertEquals("30", state.currentTotalDisplay)
        assertEquals("", state.additionalMinutesText)
        assertEquals("", state.totalMinutesText)
    }

    @Test
    fun additionalMinutesPreviewAddsToCurrentTotalWithoutPlusSign() {
        val state = GuardianExtraTimeFormState.initial(30L)
            .editAdditionalMinutes("10")

        assertEquals("10", state.additionalMinutesText)
        assertEquals(40L, state.previewTotalMinutes)
        assertEquals("40", state.totalMinutesText)
        assertTrue(!state.totalMinutesText.startsWith("+"))
        assertEquals(
            GuardianExtraTimeSubmission.Valid(additionalMinutes = 10L, totalMinutes = 40L),
            state.submit()
        )
    }

    @Test
    fun blankZeroMalformedAndUnrepresentableMinutesAreRejected() {
        val inputs = listOf("", "0", "not a number", "9223372036854775808")

        inputs.forEach { input ->
            val result = GuardianExtraTimeFormState.initial(30L)
                .editAdditionalMinutes(input)
                .submit()

            assertEquals(
                GuardianExtraTimeSubmission.Invalid(
                    GuardianExtraTimeValidationError.INVALID_MINUTES
                ),
                result
            )
        }
    }

    @Test
    fun minuteDurationOverflowIsRejectedBeforeSubmission() {
        val result = GuardianExtraTimeFormState.initial(30L)
            .editAdditionalMinutes((GuardianExtraTimeFormState.MAX_GRANT_MINUTES + 1L).toString())
            .submit()

        assertEquals(
            GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            ),
            result
        )
    }

    @Test
    fun currentTotalAndAdditionalMinutesOverflowIsRejectedBeforeSubmission() {
        val result = GuardianExtraTimeFormState.initial(Long.MAX_VALUE - 1L)
            .editAdditionalMinutes("2")
            .submit()

        assertEquals(
            GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.TOTAL_OVERFLOW
            ),
            result
        )
    }

    @Test
    fun persistedTotalDurationOverflowIsRejectedBeforeSubmission() {
        val result = GuardianExtraTimeFormState.initial(
            GuardianExtraTimeFormState.MAX_GRANT_MINUTES
        ).editAdditionalMinutes("1").submit()

        assertEquals(
            GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            ),
            result
        )
    }
}
