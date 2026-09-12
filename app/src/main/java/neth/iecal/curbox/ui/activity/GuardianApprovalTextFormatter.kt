package neth.iecal.curbox.ui.activity

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleConditionProgress
import neth.iecal.curbox.data.models.AppRuleGuardianDenial

object GuardianApprovalTextFormatter {

    fun formatDenial(context: Context, denial: AppRuleGuardianDenial): CharSequence {
        val unmetConditions = denial.conditionProgresses.filter { it.isUnmetWithShortfall }
        val showConditionProgress = AppRuleConditionProgress.shouldShowConditionProgress(
            unmetConditions = unmetConditions,
            isAllowanceExhausted = denial.isAllowanceExhausted,
            earnedAllowanceEnabled = denial.earnedAllowanceEnabled
        )

        if (showConditionProgress) {
            val reasonTitle = context.getString(R.string.app_rules_lock_reason_condition_not_met)
            val bullet = context.getString(R.string.app_rules_bullet)

            val builder = SpannableStringBuilder()
            builder.appendBold(denial.ruleName).append("\n")
            builder.appendBold(reasonTitle)

            unmetConditions.forEach { condition: AppRuleConditionProgress ->
                val conditionName = when {
                    condition.isTotalCondition -> context.getString(R.string.app_rules_condition_total_short_name)
                    condition.conditionName.isBlank() -> context.getString(R.string.app_rules_unknown_contributor_group)
                    else -> condition.conditionName
                }
                val progressText = context.getString(
                    R.string.app_rules_time_usage_allowance_format,
                    condition.currentMinutes,
                    condition.requiredMinutes
                )
                val shortfallText = context.getString(R.string.app_rules_shortfall_minutes, condition.shortfallMinutes)

                builder.append("\n").append(bullet).append(" ")
                builder.appendBold(conditionName)
                builder.append(": ")
                builder.append(progressText)
                builder.append(" (")
                builder.appendBold(shortfallText)
                builder.append(")")
            }

            return builder
        }

        if (denial.isAllowanceExhausted) {
            val reasonTitle = context.getString(R.string.app_rules_lock_reason_time_exhausted)
            val bullet = context.getString(R.string.app_rules_bullet)
            val usageAllowanceText = context.getString(
                R.string.app_rules_time_usage_allowance_format,
                denial.usedMinutes,
                denial.totalAllowedMinutes
            )

            val builder = SpannableStringBuilder()
            builder.appendBold(denial.ruleName).append("\n")
            builder.appendBold(reasonTitle)
            builder.append("\n").append(bullet).append(" ")
            builder.appendBold(usageAllowanceText)
            return builder
        }

        return context.getString(R.string.guardian_denial_row, denial.ruleName, denial.reason)
    }

    private fun SpannableStringBuilder.appendBold(text: String): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return this
    }
}
