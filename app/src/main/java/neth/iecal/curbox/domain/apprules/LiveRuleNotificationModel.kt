package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleConditionProgress

data class LiveRuleNotificationItem(
    val ruleId: String,
    val ruleName: String,
    val usedMinutes: Long,
    val totalAllowedMinutes: Long,
    val guardianExtraMinutes: Long,
    val conditionProgresses: List<AppRuleConditionProgress> = emptyList()
)

data class LiveRuleNotificationModel(
    val title: String,
    val collapsedText: String,
    val expandedLines: List<String> = emptyList()
) {
    val expandedText: String get() = expandedLines.joinToString("\n")
    val hasMultipleRules: Boolean get() = expandedLines.size > 1
}
