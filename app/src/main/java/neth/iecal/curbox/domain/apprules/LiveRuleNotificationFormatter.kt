package neth.iecal.curbox.domain.apprules

import android.content.Context
import neth.iecal.curbox.R

object LiveRuleNotificationFormatter {

    fun formatConditionProgress(currentMinutes: Long, requiredMinutes: Long, unit: String = "m"): String {
        return "($currentMinutes$unit/$requiredMinutes$unit)"
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
