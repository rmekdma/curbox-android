package neth.iecal.curbox.domain.apprules

import android.content.Context
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleConditionProgress

object LiveRuleNotificationFormatter {

    fun formatNotificationTitle(
        ruleName: String,
        prefix: String = "Curbox",
        template: String = "%1\$s • %2\$s"
    ): String {
        return String.format(template, prefix, ruleName)
    }

    fun formatNotificationTitle(context: Context, ruleName: String): String {
        return context.getString(R.string.app_rules_notification_title, ruleName)
    }

    fun formatConditionProgress(currentMinutes: Long, requiredMinutes: Long, unit: String = "m"): String {
        return "($currentMinutes$unit/$requiredMinutes$unit)"
    }

    fun formatConditionProgresses(
        conditions: List<neth.iecal.curbox.data.models.AppRuleConditionProgress>,
        unit: String,
        totalName: String,
        unknownGroupName: String
    ): String {
        val unmetConditions = conditions.filter { it.isUnmetWithShortfall }
        if (unmetConditions.isEmpty()) return ""
        return unmetConditions.joinToString(", ") { condition ->
            val name = condition.resolveDisplayName(totalName, unknownGroupName)
            val progress = formatConditionProgress(condition.currentMinutes, condition.requiredMinutes, unit)
            "$name $progress"
        }
    }

    fun formatConditionProgresses(
        context: Context,
        conditions: List<neth.iecal.curbox.data.models.AppRuleConditionProgress>
    ): String {
        val totalName = context.getString(R.string.app_rules_condition_total_short_name)
        val unknownGroupName = context.getString(R.string.app_rules_unknown_contributor_group)
        val unmetConditions = conditions.filter { it.isUnmetWithShortfall }
        if (unmetConditions.isEmpty()) return ""
        return unmetConditions.joinToString(", ") { condition ->
            val name = condition.resolveDisplayName(totalName, unknownGroupName)
            val progress = context.getString(
                R.string.app_rules_condition_progress_format,
                condition.currentMinutes,
                condition.requiredMinutes
            )
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

    fun formatRuleExhausted(
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long,
        unit: String = "m",
        template: String = "[%1\$s] Time exhausted: %2\$d%4\$s/%3\$d%4\$s"
    ): String {
        return String.format(template, ruleName, usedMinutes, totalAllowedMinutes, unit)
    }

    fun formatRuleExhausted(
        context: Context,
        ruleName: String,
        usedMinutes: Long,
        totalAllowedMinutes: Long
    ): String {
        return context.getString(
            R.string.app_rules_notification_time_exhausted,
            ruleName,
            usedMinutes,
            totalAllowedMinutes
        )
    }

    fun formatNotificationItem(
        context: Context,
        item: LiveRuleNotificationItem
    ): String {
        val unmetConditions = item.conditionProgresses.filter { it.isUnmetWithShortfall }
        val showConditionProgress = AppRuleConditionProgress.shouldShowConditionProgress(
            unmetConditions = unmetConditions,
            isAllowanceExhausted = item.isAllowanceExhausted,
            earnedAllowanceEnabled = item.earnedAllowanceEnabled
        )

        return if (showConditionProgress) {
            val progressText = formatConditionProgresses(context, unmetConditions)
            formatRuleStatus(
                context = context,
                ruleName = item.ruleName,
                usedMinutes = item.usedMinutes,
                totalAllowedMinutes = item.totalAllowedMinutes,
                guardianExtraMinutes = item.guardianExtraMinutes,
                conditionProgressText = progressText
            )
        } else if (item.isAllowanceExhausted) {
            formatRuleExhausted(
                context = context,
                ruleName = item.ruleName,
                usedMinutes = item.usedMinutes,
                totalAllowedMinutes = item.totalAllowedMinutes
            )
        } else {
            formatRuleStatus(
                context = context,
                ruleName = item.ruleName,
                usedMinutes = item.usedMinutes,
                totalAllowedMinutes = item.totalAllowedMinutes,
                guardianExtraMinutes = item.guardianExtraMinutes
            )
        }
    }

    fun formatNotificationItem(
        item: LiveRuleNotificationItem,
        unit: String = "m",
        totalName: String = "Total",
        unknownGroupName: String = "Unknown app group (check rule settings)",
        exhaustedTemplate: String = "[%1\$s] Time exhausted: %2\$d%4\$s/%3\$d%4\$s",
        statusTemplate: String = if (item.conditionProgresses.any { it.isUnmetWithShortfall }) "[%1\$s] %5\$s %2\$d min used / %3\$d min allowed%4\$s" else "[%1\$s] %2\$d min used / %3\$d min allowed%4\$s",
        extraTemplate: String = " (includes %1\$d min extra)"
    ): String {
        val unmetConditions = item.conditionProgresses.filter { it.isUnmetWithShortfall }
        val showConditionProgress = AppRuleConditionProgress.shouldShowConditionProgress(
            unmetConditions = unmetConditions,
            isAllowanceExhausted = item.isAllowanceExhausted,
            earnedAllowanceEnabled = item.earnedAllowanceEnabled
        )

        return if (showConditionProgress) {
            val progressText = formatConditionProgresses(
                conditions = unmetConditions,
                unit = unit,
                totalName = totalName,
                unknownGroupName = unknownGroupName
            )
            formatRuleStatus(
                ruleName = item.ruleName,
                usedMinutes = item.usedMinutes,
                totalAllowedMinutes = item.totalAllowedMinutes,
                guardianExtraMinutes = item.guardianExtraMinutes,
                conditionProgressText = progressText,
                template = statusTemplate,
                extraTemplate = extraTemplate
            )
        } else if (item.isAllowanceExhausted) {
            formatRuleExhausted(
                ruleName = item.ruleName,
                usedMinutes = item.usedMinutes,
                totalAllowedMinutes = item.totalAllowedMinutes,
                unit = unit,
                template = exhaustedTemplate
            )
        } else {
            formatRuleStatus(
                ruleName = item.ruleName,
                usedMinutes = item.usedMinutes,
                totalAllowedMinutes = item.totalAllowedMinutes,
                guardianExtraMinutes = item.guardianExtraMinutes,
                template = statusTemplate,
                extraTemplate = extraTemplate
            )
        }
    }
}
