package neth.iecal.curbox.domain.apprules

/** Pure state for in-app management of rule accumulated extra time. */
data class InAppAccumulatedTimeFormState private constructor(
    val currentAccumulatedMinutes: Long,
    val accumulatedMinutesText: String
) {
    fun editMinutes(text: String): InAppAccumulatedTimeFormState =
        copy(accumulatedMinutesText = text)

    fun submit(): InAppAccumulatedTimeSubmission {
        val trimmed = accumulatedMinutesText.trim()
        val minutes = trimmed.toLongOrNull()
            ?: return InAppAccumulatedTimeSubmission.Invalid(
                InAppAccumulatedTimeValidationError.INVALID_MINUTES
            )

        if (minutes < 0L || minutes > Long.MAX_VALUE / 60_000L) {
            return InAppAccumulatedTimeSubmission.Invalid(
                InAppAccumulatedTimeValidationError.INVALID_MINUTES
            )
        }

        return InAppAccumulatedTimeSubmission.Valid(minutes = minutes)
    }

    companion object {
        fun initial(currentAccumulatedMinutes: Long): InAppAccumulatedTimeFormState {
            val validInitial = currentAccumulatedMinutes.coerceAtLeast(0L)
            return InAppAccumulatedTimeFormState(
                currentAccumulatedMinutes = validInitial,
                accumulatedMinutesText = validInitial.toString()
            )
        }
    }
}

sealed class InAppAccumulatedTimeSubmission {
    data class Valid(
        val minutes: Long
    ) : InAppAccumulatedTimeSubmission()

    data class Invalid(
        val error: InAppAccumulatedTimeValidationError
    ) : InAppAccumulatedTimeSubmission()
}

enum class InAppAccumulatedTimeValidationError {
    INVALID_MINUTES
}
