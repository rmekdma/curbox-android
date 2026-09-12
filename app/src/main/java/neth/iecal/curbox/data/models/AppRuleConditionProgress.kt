package neth.iecal.curbox.data.models

/**
 * Progress details for a single usage condition within an app rule.
 */
data class AppRuleConditionProgress(
    val conditionId: String = "",
    val conditionName: String = "",
    val currentMillis: Long = 0L,
    val requiredMillis: Long = 0L,
    val isMet: Boolean = false,
    val isTotalCondition: Boolean = false
) {
    val currentMinutes: Long
        get() = currentMillis / 60_000L

    val requiredMinutes: Long
        get() = requiredMillis / 60_000L

    val remainingShortfallMillis: Long
        get() = (requiredMillis - currentMillis).coerceAtLeast(0L)

    val shortfallMinutes: Long
        get() = if (remainingShortfallMillis <= 0L) 0L else (remainingShortfallMillis + 59_999L) / 60_000L

    val isUnmetWithShortfall: Boolean
        get() = !isMet && remainingShortfallMillis > 0L

    fun resolveDisplayName(
        totalName: String,
        unknownGroupName: String
    ): String = when {
        isTotalCondition -> totalName
        conditionName.isBlank() -> unknownGroupName
        else -> conditionName
    }

    companion object {
        const val CONDITION_ID_TOTAL = "total"

        fun shouldShowConditionProgress(
            unmetConditions: Collection<AppRuleConditionProgress>,
            isAllowanceExhausted: Boolean,
            earnedAllowanceEnabled: Boolean
        ): Boolean = unmetConditions.isNotEmpty() && (!isAllowanceExhausted || earnedAllowanceEnabled)
    }
}
