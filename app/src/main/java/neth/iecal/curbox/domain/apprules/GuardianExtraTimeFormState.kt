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
        val parsed = text.toLongOrNull()
        val preview = parsed
            ?.takeIf { it > 0L }
            ?.takeIf { it <= MAX_GRANT_MINUTES }
            ?.let { additionalMinutes ->
                if (currentTotalMinutes <= Long.MAX_VALUE - additionalMinutes) {
                    (currentTotalMinutes + additionalMinutes)
                        .takeIf { it <= MAX_GRANT_MINUTES }
                } else {
                    null
                }
            }

        return copy(
            additionalMinutesText = text,
            totalMinutesText = preview?.toString().orEmpty(),
            previewTotalMinutes = preview
        )
    }

    fun submit(): GuardianExtraTimeSubmission {
        val additionalMinutes = additionalMinutesText.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.INVALID_MINUTES
            )

        if (additionalMinutes > MAX_GRANT_MINUTES) {
            return GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }

        if (currentTotalMinutes > Long.MAX_VALUE - additionalMinutes) {
            return GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.TOTAL_OVERFLOW
            )
        }

        val totalMinutes = currentTotalMinutes + additionalMinutes
        if (totalMinutes > MAX_GRANT_MINUTES) {
            return GuardianExtraTimeSubmission.Invalid(
                GuardianExtraTimeValidationError.DURATION_OVERFLOW
            )
        }

        return GuardianExtraTimeSubmission.Valid(
            additionalMinutes = additionalMinutes,
            totalMinutes = totalMinutes
        )
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
