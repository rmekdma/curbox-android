package neth.iecal.curbox.domain.apprules

import android.content.Context
import neth.iecal.curbox.R

object LiveRuleNotificationFormatter {

    fun formatConditionProgress(currentMinutes: Long, requiredMinutes: Long, unit: String = "m"): String {
        return "($currentMinutes$unit/$requiredMinutes$unit)"
    }

    fun formatConditionProgresses(
        conditions: List<neth.iecal.curbox.data.models.AppRuleConditionProgress>,
        unit: String,
        totalName: String,
        unknownGroupName: String
    ): String {
        val unmetConditions = conditions.filter { !it.isMet && it.requiredMillis > 0L }
        if (unmetConditions.isEmpty()) return ""
        return unmetConditions.joinToString(", ") { condition ->
            val name = when {
                condition.isTotalCondition -> totalName
                condition.conditionName.isBlank() -> unknownGroupName
                else -> condition.conditionName
            }
            val curM = condition.currentMillis / 60_000L
            val reqM = condition.requiredMillis / 60_000L
            val progress = formatConditionProgress(curM, reqM, unit)
            "$name $progress"
        }
    }

    fun formatConditionProgresses(
        context: Context,
        conditions: List<neth.iecal.curbox.data.models.AppRuleConditionProgress>
    ): String {
        val totalName = context.getString(R.string.app_rules_condition_total_short_name)
        val unknownGroupName = context.getString(R.string.app_rules_unknown_contributor_group)
        val unmetConditions = conditions.filter { !it.isMet && it.requiredMillis > 0L }
        if (unmetConditions.isEmpty()) return ""
        return unmetConditions.joinToString(", ") { condition ->
            val name = when {
                condition.isTotalCondition -> totalName
                condition.conditionName.isBlank() -> unknownGroupName
                else -> condition.conditionName
            }
            val curM = condition.currentMillis / 60_000L
            val reqM = condition.requiredMillis / 60_000L
            val progress = context.getString(R.string.app_rules_condition_progress_format, curM, reqM)
            "$name $progress"
        }
    }

    fun formatRuleStatus(
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        guardianExtraMinutes: Long,
        conditionProgressText: String = "",
        template: String = if (conditionProgressText.isNotBlank()) "[%1\$s] %5\$s %2\$d min used / %3\$d min allowed%4\$s" else "[%1\$s] %2\$d min used / %3\$d min allowed%4\$s",
        extraTemplate: String = " (includes %1\$d min extra)"
    ): String {
        val extra = if (guardianExtraMinutes > 0L) {
            String.format(extraTemplate, guardianExtraMinutes)
        } else {
            ""
        }
        return String.format(template, ruleName, usedMinutes, totalAllowedMinutes, extra, conditionProgressText)
    }

    fun formatRuleStatus(
        context: Context,
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        guardianExtraMinutes: Long,
        conditionProgressText: String = ""
    ): String {
        val extra = if (guardianExtraMinutes > 0L) {
            context.getString(R.string.app_rules_notification_guardian_extra, guardianExtraMinutes)
        } else {
            ""
        }
        return if (conditionProgressText.isNotBlank()) {
            context.getString(
                R.string.app_rules_notification_rule_status_with_condition,
                ruleName,
                conditionProgressText,
                usedMinutes,
                totalAllowedMinutes,
                extra
            )
        } else {
            context.getString(
                R.string.app_rules_notification_rule_status,
                ruleName,
                usedMinutes,
                totalAllowedMinutes,
                extra
            )
        }
    }

    fun formatRuleStatusKorean(
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        guardianExtraMinutes: Long,
        conditionProgressText: String = ""
    ): String = formatRuleStatus(
        ruleName = ruleName,
        usedMinutes = usedMinutes,
        totalAllowedMinutes = totalAllowedMinutes,
        guardianExtraMinutes = guardianExtraMinutes,
        conditionProgressText = conditionProgressText,
        template = if (conditionProgressText.isNotBlank()) "[%1\$s] %5\$s %2\$d분 사용 / %3\$d분 허용%4\$s" else "[%1\$s] %2\$d분 사용 / %3\$d분 허용%4\$s",
        extraTemplate = " (추가 %1\$d분 포함)"
    )
}
