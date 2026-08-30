package neth.iecal.curbox.domain.apprules

/** Pure state for the first direction of the guardian extra-time form. */
data class GuardianExtraTimeFormState private constructor(
    val currentTotalMinutes: Long,
    val additionalMinutesText: String,
    val totalMinutesText: String,
    val previewTotalMinutes: Long?
) {
    val currentTotalDisplay: String
        get() = currentTotalMinutes.toString()

    fun editAdditionalMinutes(text: String): GuardianExtraTimeFormState {
        val preview = when (val validation = validateAdditionalMinutes(text)) {
            is AdditionalMinutesValidation.Valid -> validation.totalMinutes
            is AdditionalMinutesValidation.Invalid -> null
        }

        return copy(
            additionalMinutesText = text,
            totalMinutesText = preview?.toString().orEmpty(),
            previewTotalMinutes = preview
        )
    }

    fun submit(): GuardianExtraTimeSubmission = when (
        val validation = validateAdditionalMinutes(additionalMinutesText)
    ) {
        is AdditionalMinutesValidation.Valid -> GuardianExtraTimeSubmission.Valid(
            additionalMinutes = validation.additionalMinutes,
            totalMinutes = validation.totalMinutes
        )

        is AdditionalMinutesValidation.Invalid -> GuardianExtraTimeSubmission.Invalid(
            error = validation.error
        )
    }

    private fun validateAdditionalMinutes(text: String): AdditionalMinutesValidation {
        val additionalMinutes = text.toLongOrNull()
            ?: return AdditionalMinutesValidation.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )
        if (additionalMinutes <= 0L) {
            return AdditionalMinutesValidation.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )
        }
        if (additionalMinutes > MAX_GRANT_MINUTES) {
            return AdditionalMinutesValidation.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }
        if (currentTotalMinutes > Long.MAX_VALUE - additionalMinutes) {
            return AdditionalMinutesValidation.Invalid(
                GuardianExtraTimeValidationError.TOTAL_OVERFLOW
            )
        }

        val totalMinutes = currentTotalMinutes + additionalMinutes
        if (totalMinutes > MAX_GRANT_MINUTES) {
            return AdditionalMinutesValidation.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }
        return AdditionalMinutesValidation.Valid(additionalMinutes, totalMinutes)
    }

    private sealed class AdditionalMinutesValidation {
        data class Valid(val additionalMinutes: Long, val totalMinutes: Long) :
            AdditionalMinutesValidation()

        data class Invalid(val error: GuardianExtraTimeValidationError) :
            AdditionalMinutesValidation()
    }

    companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_GRANT_MINUTES = Long.MAX_VALUE / MILLIS_PER_MINUTE

        fun initial(currentTotalMinutes: Long): GuardianExtraTimeFormState =
            GuardianExtraTimeFormState(
                currentTotalMinutes = currentTotalMinutes.coerceAtLeast(0L),
                additionalMinutesText = "",
                totalMinutesText = "",
                previewTotalMinutes = null
            )
    }
}

sealed class GuardianExtraTimeSubmission {
    data class Valid(
        val additionalMinutes: Long,
        val totalMinutes: Long
    ) : GuardianExtraTimeSubmission()

    data class Invalid(
        val error: GuardianExtraTimeValidationError
    ) : GuardianExtraTimeSubmission()
}

enum class GuardianExtraTimeValidationError {
    INVALID_MINUTES,
    DURATION_OVERFLOW,
    TOTAL_OVERFLOW
}
