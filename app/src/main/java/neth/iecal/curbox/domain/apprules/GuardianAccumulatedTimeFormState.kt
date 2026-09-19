package neth.iecal.curbox.domain.apprules

/** Pure state for the guardian accumulated-time approval form. */
data class GuardianAccumulatedTimeFormState private constructor(
    val totalAccumulatedMinutes: Long,
    val accumulatedMinutesText: String
) {
    fun editMinutes(text: String): GuardianAccumulatedTimeFormState =
        copy(accumulatedMinutesText = text)

    fun submit(): GuardianAccumulatedTimeSubmission {
        val minutes = accumulatedMinutesText.toLongOrNull()
            ?: return GuardianAccumulatedTimeSubmission.Invalid(
                GuardianAccumulatedTimeValidationError.INVALID_MINUTES
            )

        if (minutes <= 0L) {
            return GuardianAccumulatedTimeSubmission.Invalid(
                GuardianAccumulatedTimeValidationError.INVALID_MINUTES
            )
        }

        if (minutes > totalAccumulatedMinutes) {
            return GuardianAccumulatedTimeSubmission.Invalid(
                GuardianAccumulatedTimeValidationError.EXCEEDS_ACCUMULATED
            )
        }

        return GuardianAccumulatedTimeSubmission.Valid(approvedMinutes = minutes)
    }

    companion object {
        fun initial(totalAccumulatedMinutes: Long): GuardianAccumulatedTimeFormState {
            val total = totalAccumulatedMinutes.coerceAtLeast(0L)
            return GuardianAccumulatedTimeFormState(
                totalAccumulatedMinutes = total,
                accumulatedMinutesText = total.toString()
            )
        }
    }
}

sealed class GuardianAccumulatedTimeSubmission {
    data class Valid(
        val approvedMinutes: Long
    ) : GuardianAccumulatedTimeSubmission()

    data class Invalid(
        val error: GuardianAccumulatedTimeValidationError
    ) : GuardianAccumulatedTimeSubmission()
}

enum class GuardianAccumulatedTimeValidationError {
    INVALID_MINUTES,
    EXCEEDS_ACCUMULATED
}
