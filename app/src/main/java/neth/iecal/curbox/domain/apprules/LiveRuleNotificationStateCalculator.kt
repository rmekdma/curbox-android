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
        val membershipResolver = AppRuleMembershipResolver(snapshot)
        return activeRules.mapNotNull { rule ->
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
            if (!evaluation.isActive) return@mapNotNull null

            val ruleName = rule.name.ifBlank { rule.id }
            val usedMinutes = (evaluation.usedMillis / 60_000L).coerceAtLeast(0L)
            val totalAllowedMinutes = (evaluation.effectiveAllowanceMillis / 60_000L).coerceAtLeast(0L)
            val guardianExtraMinutes = (evaluation.guardianAllowanceMillis / 60_000L).coerceAtLeast(0L)
            val targetPackages = membershipResolver.targetPackagesAt(
                rule = rule,
                atMs = nowMs,
                launchablePackages = availablePackages,
                essentialExcludedPackages = essentialExcludedPackages
            )

            LiveRuleNotificationItem(
                ruleId = rule.id,
                ruleName = ruleName,
                usedMinutes = usedMinutes,
                totalAllowedMinutes = totalAllowedMinutes,
                guardianExtraMinutes = guardianExtraMinutes,
                conditionProgresses = evaluation.conditionProgresses,
                isAllowanceExhausted = evaluation.isAllowanceExhausted,
                earnedAllowanceEnabled = evaluation.earnedAllowanceEnabled,
                isAllowed = evaluation.isAllowed,
                targetPackages = targetPackages
            )
        }
    }

    fun buildNotificationModel(
        items: List<LiveRuleNotificationItem>,
        defaultTitle: String,
        defaultText: String,
        formatter: (LiveRuleNotificationItem) -> String,
        foregroundPackage: String? = null,
        rulePackageResolver: ((String) -> Set<String>)? = null,
        titleFormatter: ((String) -> String)? = null
    ): LiveRuleNotificationModel {
        if (items.isEmpty()) {
            return LiveRuleNotificationModel(
                title = defaultTitle,
                collapsedText = defaultText,
                expandedLines = emptyList()
            )
        }

        // 4-tier stable priority sort:
        // (1) Foreground app blocked
        // (2) Foreground app allowed
        // (3) Other app blocked
        // (4) Other app allowed
        // Stable sort preserves original snapshot order within the same tier.
        val sortedItems = items.sortedBy { item ->
            val targets = rulePackageResolver?.invoke(item.ruleId) ?: item.targetPackages
            val isForeground = !foregroundPackage.isNullOrBlank() && targets.contains(foregroundPackage)
            val isBlocked = !item.isAllowed
            when {
                isForeground && isBlocked -> 1
                isForeground && !isBlocked -> 2
                !isForeground && isBlocked -> 3
                else -> 4
            }
        }

        val topItem = sortedItems.first()
        val title = titleFormatter?.invoke(topItem.ruleName)
            ?: LiveRuleNotificationFormatter.formatNotificationTitle(topItem.ruleName)
        val formattedLines = sortedItems.map(formatter)
        val collapsedText = formattedLines.first()

        return LiveRuleNotificationModel(
            title = title,
            collapsedText = collapsedText,
            expandedLines = formattedLines
        )
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
