package neth.iecal.curbox.domain.apprules

import android.content.Context
import neth.iecal.curbox.R

object LiveRuleNotificationFormatter {

    fun formatRuleStatus(
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        guardianExtraMinutes: Long,
        template: String = "[%1\$s] %2\$d min used / %3\$d min allowed%4\$s",
        extraTemplate: String = " (includes %1\$d min extra)"
    ): String {
        val extra = if (guardianExtraMinutes > 0L) {
            String.format(extraTemplate, guardianExtraMinutes)
        } else {
            ""
        }
        return String.format(template, ruleName, usedMinutes, totalAllowedMinutes, extra)
    }

    fun formatRuleStatus(
        context: Context,
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        guardianExtraMinutes: Long
    ): String {
        val extra = if (guardianExtraMinutes > 0L) {
            context.getString(R.string.app_rules_notification_guardian_extra, guardianExtraMinutes)
        } else {
            ""
        }
        return context.getString(
            R.string.app_rules_notification_rule_status,
            ruleName,
            usedMinutes,
            totalAllowedMinutes,
            extra
        )
    }

    fun formatRuleStatusKorean(
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        guardianExtraMinutes: Long
    ): String = formatRuleStatus(
        ruleName = ruleName,
        usedMinutes = usedMinutes,
        totalAllowedMinutes = totalAllowedMinutes,
        guardianExtraMinutes = guardianExtraMinutes,
        template = "[%1\$s] %2\$d분 사용 / %3\$d분 허용%4\$s",
        extraTemplate = " (추가 %1\$d분 포함)"
    )
}
