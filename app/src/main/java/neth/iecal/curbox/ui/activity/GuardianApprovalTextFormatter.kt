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
        val unmetConditions = denial.conditionProgresses.filter { !it.isMet && it.remainingShortfallMillis > 0L }
        if (unmetConditions.isNotEmpty()) {
            val reasonTitle = context.getString(R.string.app_rules_lock_reason_condition_not_met)
            val bullet = context.getString(R.string.app_rules_bullet)

            val builder = SpannableStringBuilder()
            builder.appendBold(denial.ruleName).append("\n")
            builder.appendBold(reasonTitle)

            unmetConditions.forEach { condition: AppRuleConditionProgress ->
                val conditionName = if (condition.isTotalCondition) {
                    val totalMinutesRequired = condition.requiredMillis / 60_000L
                    context.getString(R.string.app_rules_condition_total_name, totalMinutesRequired)
                } else if (condition.conditionName.isBlank()) {
                    context.getString(R.string.app_rules_unknown_contributor_group)
                } else {
                    condition.conditionName
                }
                val shortfallText = context.getString(R.string.app_rules_shortfall_minutes, condition.shortfallMinutes)

                builder.append("\n").append(bullet).append(" ")
                builder.appendBold(conditionName)
                builder.append(": ")
                builder.appendBold(shortfallText)
            }

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
