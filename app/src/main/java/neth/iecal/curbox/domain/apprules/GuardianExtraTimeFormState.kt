package neth.iecal.curbox.domain.apprules

/** The field whose text is currently authoritative for the form. */
enum class GuardianExtraTimeInputSource {
    ADDITIONAL_MINUTES,
    TOTAL_MINUTES
}

/** Pure state for the linked guardian extra-time form inputs. */
data class GuardianExtraTimeFormState private constructor(
    val currentTotalMinutes: Long,
    val additionalMinutesText: String,
    val totalMinutesText: String,
    val previewTotalMinutes: Long?,
    val activeSource: GuardianExtraTimeInputSource
) {
    val currentTotalDisplay: String
        get() = currentTotalMinutes.toString()

    fun editAdditionalMinutes(text: String): GuardianExtraTimeFormState {
        val preview = when (val validation = validateAdditionalMinutes(text)) {
            is MinutesValidation.Valid -> validation.totalMinutes
            is MinutesValidation.Invalid -> null
        }

        return copy(
            additionalMinutesText = text,
            totalMinutesText = preview?.toString().orEmpty(),
            previewTotalMinutes = preview,
            activeSource = GuardianExtraTimeInputSource.ADDITIONAL_MINUTES
        )
    }

    fun editTotalMinutes(text: String): GuardianExtraTimeFormState {
        val validation = validateTotalMinutes(text)
        val preview = when (validation) {
            is MinutesValidation.Valid -> validation.totalMinutes
            is MinutesValidation.Invalid -> null
        }

        return copy(
            additionalMinutesText = when (validation) {
                is MinutesValidation.Valid -> validation.additionalMinutes.toString()
                is MinutesValidation.Invalid -> ""
            },
            totalMinutesText = text,
            previewTotalMinutes = preview,
            activeSource = GuardianExtraTimeInputSource.TOTAL_MINUTES
        )
    }

    fun submit(): GuardianExtraTimeSubmission = when (activeSource) {
        GuardianExtraTimeInputSource.ADDITIONAL_MINUTES ->
            validateAdditionalMinutes(additionalMinutesText)
        GuardianExtraTimeInputSource.TOTAL_MINUTES -> validateTotalMinutes(totalMinutesText)
    }.toSubmission()

    private fun validateAdditionalMinutes(text: String): MinutesValidation {
        val additionalMinutes = text.toLongOrNull()
            ?: return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )
        if (additionalMinutes <= 0L) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )
        }
        if (additionalMinutes > MAX_GRANT_MINUTES) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }
        if (currentTotalMinutes > Long.MAX_VALUE - additionalMinutes) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.TOTAL_OVERFLOW
            )
        }

        val totalMinutes = currentTotalMinutes + additionalMinutes
        if (totalMinutes > MAX_GRANT_MINUTES) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }
        return MinutesValidation.Valid(additionalMinutes, totalMinutes)
    }

    private fun validateTotalMinutes(text: String): MinutesValidation {
        val totalMinutes = text.toLongOrNull()
            ?: return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )
        if (totalMinutes <= 0L) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )
        }
        if (totalMinutes <= currentTotalMinutes) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.TOTAL_NOT_GREATER
            )
        }
        if (totalMinutes > MAX_GRANT_MINUTES) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }

        val additionalMinutes = totalMinutes - currentTotalMinutes
        if (additionalMinutes <= 0L) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.TOTAL_NOT_GREATER
            )
        }
        if (additionalMinutes > MAX_GRANT_MINUTES) {
            return MinutesValidation.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }
        return MinutesValidation.Valid(additionalMinutes, totalMinutes)
    }

    private sealed class MinutesValidation {
        data class Valid(val additionalMinutes: Long, val totalMinutes: Long) :
            MinutesValidation()

        data class Invalid(val error: GuardianExtraTimeValidationError) :
            MinutesValidation()
    }

    private fun MinutesValidation.toSubmission(): GuardianExtraTimeSubmission = when (this) {
        is MinutesValidation.Valid -> GuardianExtraTimeSubmission.Valid(
            additionalMinutes = additionalMinutes,
            totalMinutes = totalMinutes
        )
        is MinutesValidation.Invalid -> GuardianExtraTimeSubmission.Invalid(error)
    }

    companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_GRANT_MINUTES = Long.MAX_VALUE / MILLIS_PER_MINUTE

        fun initial(currentTotalMinutes: Long): GuardianExtraTimeFormState =
            GuardianExtraTimeFormState(
                currentTotalMinutes = currentTotalMinutes.coerceAtLeast(0L),
                additionalMinutesText = "",
                totalMinutesText = "",
                previewTotalMinutes = null,
                activeSource = GuardianExtraTimeInputSource.ADDITIONAL_MINUTES
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
    TOTAL_OVERFLOW,
    TOTAL_NOT_GREATER
}
