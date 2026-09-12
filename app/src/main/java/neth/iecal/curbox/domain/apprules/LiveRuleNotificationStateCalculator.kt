package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayCalculator
import java.time.ZoneId

object LiveRuleNotificationStateCalculator {

    fun computeNotificationItems(
        snapshot: AppRuleSnapshot,
        sessions: Iterable<ForegroundSession>,
        useDayId: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(zone),
        useDayGenerationStartedAtMs: Long = 0L,
        availablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet(),
        overrides: AppRuleOverrideState = AppRuleOverrideState()
    ): List<LiveRuleNotificationItem> {
        val activeRules = snapshot.appRules.filter { it.isActive }
        if (activeRules.isEmpty()) return emptyList()

        val sessionList = sessions.toList()
        return activeRules.map { rule ->
            val evaluation = AppRuleEvaluator.evaluateRuleForSnapshot(
                snapshot = snapshot,
                rule = rule,
                useDayId = useDayId,
                sessions = sessionList,
                nowMs = nowMs,
                zone = zone,
                useDayCalculator = useDayCalculator,
                useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
                availablePackages = availablePackages,
                essentialExcludedPackages = essentialExcludedPackages,
                overrides = overrides
            )
            val ruleName = rule.name.ifBlank { rule.id }
            val usedMinutes = (evaluation.usedMillis / 60_000L).coerceAtLeast(0L)
            val totalAllowedMinutes = (evaluation.effectiveAllowanceMillis / 60_000L).coerceAtLeast(0L)
            val guardianExtraMinutes = (evaluation.guardianAllowanceMillis / 60_000L).coerceAtLeast(0L)

            LiveRuleNotificationItem(
                ruleId = rule.id,
                ruleName = ruleName,
                usedMinutes = usedMinutes,
                totalAllowedMinutes = totalAllowedMinutes,
                guardianExtraMinutes = guardianExtraMinutes,
                conditionProgresses = evaluation.conditionProgresses,
                isAllowanceExhausted = evaluation.isAllowanceExhausted,
                earnedAllowanceEnabled = evaluation.earnedAllowanceEnabled
            )
        }
    }

    fun buildNotificationModel(
        items: List<LiveRuleNotificationItem>,
        defaultTitle: String,
        defaultText: String,
        formatter: (LiveRuleNotificationItem) -> String,
        foregroundPackage: String? = null,
        rulePackageResolver: ((String) -> Set<String>)? = null
    ): LiveRuleNotificationModel {
        if (items.isEmpty()) {
            return LiveRuleNotificationModel(
                title = defaultTitle,
                collapsedText = defaultText,
                expandedLines = emptyList()
            )
        }

        val formattedLines = items.map(formatter)

        val selectedIndex = if (!foregroundPackage.isNullOrBlank() && rulePackageResolver != null) {
            val matchingIndex = items.indexOfFirst { item ->
                foregroundPackage in rulePackageResolver(item.ruleId)
            }
            if (matchingIndex >= 0) matchingIndex else 0
        } else {
            0
        }

        val collapsedText = formattedLines.getOrElse(selectedIndex) { formattedLines.first() }

        return LiveRuleNotificationModel(
            title = defaultTitle,
            collapsedText = collapsedText,
            expandedLines = formattedLines
        )
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
