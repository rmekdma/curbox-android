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
    fun totalMinutesPreviewDerivesThePositiveAdditionalMinutes() {
        val state = GuardianExtraTimeFormState.initial(30L)
            .editTotalMinutes("45")

        assertEquals("45", state.totalMinutesText)
        assertEquals("15", state.additionalMinutesText)
        assertEquals(45L, state.previewTotalMinutes)
        assertEquals(
            GuardianExtraTimeSubmission.Valid(additionalMinutes = 15L, totalMinutes = 45L),
            state.submit()
        )
    }

    @Test
    fun switchingInputSourcesRecomputesOnlyFromTheMostRecentEdit() {
        val fromAdditional = GuardianExtraTimeFormState.initial(30L)
            .editAdditionalMinutes("10")
        val fromTotal = fromAdditional.editTotalMinutes("50")
        val switchedBack = fromTotal.editAdditionalMinutes("5")

        assertEquals("50", fromTotal.totalMinutesText)
        assertEquals("20", fromTotal.additionalMinutesText)
        assertEquals("35", switchedBack.totalMinutesText)
        assertEquals("5", switchedBack.additionalMinutesText)
        assertEquals(
            GuardianExtraTimeSubmission.Valid(additionalMinutes = 5L, totalMinutes = 35L),
            switchedBack.submit()
        )
    }

    @Test
    fun equalOrSmallerTotalLeavesDerivedAdditionalMinutesBlank() {
        listOf("30", "29").forEach { input ->
            val state = GuardianExtraTimeFormState.initial(30L)
                .editTotalMinutes(input)

            assertEquals(input, state.totalMinutesText)
            assertEquals("", state.additionalMinutesText)
            assertEquals(
                GuardianExtraTimeSubmission.Invalid(
                    GuardianExtraTimeValidationError.TOTAL_NOT_GREATER
                ),
                state.submit()
            )
        }
    }

    @Test
    fun invalidTotalInputLeavesDerivedAdditionalMinutesBlank() {
        listOf("", "0", "not a number", "9223372036854775808").forEach { input ->
            val result = GuardianExtraTimeFormState.initial(30L)
                .editTotalMinutes(input)

            assertEquals("", result.additionalMinutesText)
            assertEquals(
                GuardianExtraTimeSubmission.Invalid(
                    GuardianExtraTimeValidationError.INVALID_MINUTES
                ),
                result.submit()
            )
        }
    }

    @Test
    fun totalDurationOverflowIsRejectedBeforeSubmission() {
        val result = GuardianExtraTimeFormState.initial(30L)
            .editTotalMinutes((GuardianExtraTimeFormState.MAX_GRANT_MINUTES + 1L).toString())

        assertEquals("", result.additionalMinutesText)
        assertEquals(
            GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            ),
            result.submit()
        )
    }

    @Test
    fun largestRepresentableTotalRemainsAvailableWithoutAProductMaximum() {
        val result = GuardianExtraTimeFormState.initial(30L)
            .editTotalMinutes(GuardianExtraTimeFormState.MAX_GRANT_MINUTES.toString())

        assertEquals(
            GuardianExtraTimeSubmission.Valid(
                additionalMinutes = GuardianExtraTimeFormState.MAX_GRANT_MINUTES - 30L,
                totalMinutes = GuardianExtraTimeFormState.MAX_GRANT_MINUTES
            ),
            result.submit()
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

    @Test
    fun negativeInitialTotalIsCoercedToZero() {
        val state = GuardianExtraTimeFormState.initial(currentTotalMinutes = -10L)

        assertEquals(0L, state.currentTotalMinutes)
        assertEquals("0", state.currentTotalDisplay)
    }

    @Test
    fun zeroInitialTotalAllowsEnteringAdditionalAndTotalMinutes() {
        val state = GuardianExtraTimeFormState.initial(currentTotalMinutes = 0L)

        assertEquals(0L, state.currentTotalMinutes)
        assertEquals("0", state.currentTotalDisplay)

        // Adding 15 minutes additional from 0
        val withAdditional = state.editAdditionalMinutes("15")
        assertEquals("15", withAdditional.additionalMinutesText)
        assertEquals("15", withAdditional.totalMinutesText)
        assertEquals(15L, withAdditional.previewTotalMinutes)
        assertEquals(
            GuardianExtraTimeSubmission.Valid(additionalMinutes = 15L, totalMinutes = 15L),
            withAdditional.submit()
        )

        // Setting total to 20 from 0
        val withTotal = state.editTotalMinutes("20")
        assertEquals("20", withTotal.totalMinutesText)
        assertEquals("20", withTotal.additionalMinutesText)
        assertEquals(20L, withTotal.previewTotalMinutes)
        assertEquals(
            GuardianExtraTimeSubmission.Valid(additionalMinutes = 20L, totalMinutes = 20L),
            withTotal.submit()
        )
    }
}
