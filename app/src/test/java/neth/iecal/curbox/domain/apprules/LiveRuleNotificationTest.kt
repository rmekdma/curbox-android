package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LiveRuleNotificationTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-17T10:00:00Z").toEpochMilli()
    private val useDayId = "2026-08-17"

    @Test
    fun formatsRuleStatusWithoutGuardianExtraWhenExtraIsZero() {
        val formatted = LiveRuleNotificationFormatter.formatRuleStatus(
            ruleName = "Social",
            usedMinutes = 15,
            totalAllowedMinutes = 60,
            guardianExtraMinutes = 0
        )
        assertEquals("[Social] 15 min used / 60 min allowed", formatted)

        val formattedKorean = LiveRuleNotificationFormatter.formatRuleStatusKorean(
            ruleName = "소셜",
            usedMinutes = 15,
            totalAllowedMinutes = 60,
            guardianExtraMinutes = 0
        )
        assertEquals("[소셜] 15분 사용 / 60분 허용", formattedKorean)
    }

    @Test
    fun formatsRuleStatusWithGuardianExtraWhenExtraIsGreaterThanZero() {
        val formatted = LiveRuleNotificationFormatter.formatRuleStatus(
            ruleName = "Social",
            usedMinutes = 20,
            totalAllowedMinutes = 75,
            guardianExtraMinutes = 15
        )
        assertEquals("[Social] 20 min used / 75 min allowed (includes 15 min extra)", formatted)

        val formattedKorean = LiveRuleNotificationFormatter.formatRuleStatusKorean(
            ruleName = "소셜",
            usedMinutes = 20,
            totalAllowedMinutes = 75,
            guardianExtraMinutes = 15
        )
        assertEquals("[소셜] 20분 사용 / 75분 허용 (추가 15분 포함)", formattedKorean)
    }

    @Test
    fun formatsRuleStatusWithConditionProgress() {
        val formatted = LiveRuleNotificationFormatter.formatRuleStatus(
            ruleName = "Social",
            usedMinutes = 0,
            totalAllowedMinutes = 30,
            guardianExtraMinutes = 0,
            conditionProgressText = "(19m/20m)"
        )
        assertEquals("[Social] (19m/20m) 0 min used / 30 min allowed", formatted)

        val formattedKorean = LiveRuleNotificationFormatter.formatRuleStatusKorean(
            ruleName = "소셜",
            usedMinutes = 0,
            totalAllowedMinutes = 30,
            guardianExtraMinutes = 0,
            conditionProgressText = "(19분/20분)"
        )
        assertEquals("[소셜] (19분/20분) 0분 사용 / 30분 허용", formattedKorean)
    }
    @Test
    fun formatsScheduleOnlyZeroAllowanceRule() {
        val formatted = LiveRuleNotificationFormatter.formatRuleStatus(
            ruleName = "BlockAll",
            usedMinutes = 0,
            totalAllowedMinutes = 0,
            guardianExtraMinutes = 0
        )
        assertEquals("[BlockAll] 0 min used / 0 min allowed", formatted)

        val formattedKorean = LiveRuleNotificationFormatter.formatRuleStatusKorean(
            ruleName = "전체차단",
            usedMinutes = 0,
            totalAllowedMinutes = 0,
            guardianExtraMinutes = 0
        )
        assertEquals("[전체차단] 0분 사용 / 0분 허용", formattedKorean)
    }

    @Test
    fun doesNotContainAnyHyphensOrDashesInFormattedOutputs() {
        val formattedEn = LiveRuleNotificationFormatter.formatRuleStatus("Test", 10, 30, 5)
        assertFalse(formattedEn.contains("-"))
        assertFalse(formattedEn.contains("–"))
        assertFalse(formattedEn.contains("—"))

        val formattedKo = LiveRuleNotificationFormatter.formatRuleStatusKorean("테스트", 10, 30, 5)
        assertFalse(formattedKo.contains("-"))
        assertFalse(formattedKo.contains("–"))
        assertFalse(formattedKo.contains("—"))
    }

    @Test
    fun computeNotificationItemsCalculatesUsedAndEffectiveAllowanceWithGrants() {
        val group = AppRuleAppGroup.create("Social", listOf("com.social.app"))
        val rule = AppRule(
            id = "rule-1",
            name = "Social Limits",
            weekdays = (0..6).toSet(),
            startMinute = 0,
            endMinute = 24 * 60,
            scope = AppRuleScope.forGroup(group.id),
            allowedMinutes = 45L
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))

        // Session of 10 minutes
        val sessions = listOf(
            ForegroundSession(
                useDayId = useDayId,
                packageName = "com.social.app",
                startedAtMs = now - 10 * 60_000L,
                endedAtMs = now
            )
        )

        // Guardian grant of 15 minutes
        val overrides = AppRuleOverrideState(
            useDayId = useDayId,
            grants = listOf(
                AppRuleGuardianGrant(
                    ruleId = "rule-1",
                    useDayId = useDayId,
                    grantedAtMs = now - 5 * 60_000L,
                    grantedMillis = 15 * 60_000L
                )
            )
        )

        val items = LiveRuleNotificationStateCalculator.computeNotificationItems(
            snapshot = snapshot,
            sessions = sessions,
            useDayId = useDayId,
            nowMs = now,
            zone = zone,
            overrides = overrides
        )

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("rule-1", item.ruleId)
        assertEquals("Social Limits", item.ruleName)
        assertEquals(10L, item.usedMinutes)
        assertEquals(60L, item.totalAllowedMinutes) // 45 base + 15 grant
        assertEquals(15L, item.guardianExtraMinutes)
    }

    @Test
    fun buildNotificationModelHandlesEmptyActiveRules() {
        val model = LiveRuleNotificationStateCalculator.buildNotificationModel(
            items = emptyList(),
            defaultTitle = "Curbox is active",
            defaultText = "Protecting your digital wellbeing",
            formatter = { LiveRuleNotificationFormatter.formatRuleStatusKorean(it.ruleName, it.usedMinutes, it.totalAllowedMinutes, it.guardianExtraMinutes) }
        )

        assertEquals("Curbox is active", model.title)
        assertEquals("Protecting your digital wellbeing", model.collapsedText)
        assertTrue(model.expandedLines.isEmpty())
        assertFalse(model.hasMultipleRules)
    }

    @Test
    fun buildNotificationModelHandlesSingleActiveRule() {
        val item = LiveRuleNotificationItem(
            ruleId = "rule-1",
            ruleName = "소셜",
            usedMinutes = 15,
            totalAllowedMinutes = 60,
            guardianExtraMinutes = 0
        )
        val model = LiveRuleNotificationStateCalculator.buildNotificationModel(
            items = listOf(item),
            defaultTitle = "Curbox",
            defaultText = "Default",
            formatter = { LiveRuleNotificationFormatter.formatRuleStatusKorean(it.ruleName, it.usedMinutes, it.totalAllowedMinutes, it.guardianExtraMinutes) }
        )

        assertEquals("Curbox", model.title)
        assertEquals("[소셜] 15분 사용 / 60분 허용", model.collapsedText)
        assertEquals(1, model.expandedLines.size)
        assertEquals("[소셜] 15분 사용 / 60분 허용", model.expandedText)
        assertFalse(model.hasMultipleRules)
    }

    @Test
    fun buildNotificationModelHandlesMultipleActiveRulesWithExpansionAndForegroundSelection() {
        val item1 = LiveRuleNotificationItem(
            ruleId = "rule-1",
            ruleName = "소셜",
            usedMinutes = 15,
            totalAllowedMinutes = 60,
            guardianExtraMinutes = 0
        )
        val item2 = LiveRuleNotificationItem(
            ruleId = "rule-2",
            ruleName = "게임",
            usedMinutes = 25,
            totalAllowedMinutes = 40,
            guardianExtraMinutes = 10
        )
        val items = listOf(item1, item2)

        // Without foreground package: collapsed defaults to first rule
        val modelDefault = LiveRuleNotificationStateCalculator.buildNotificationModel(
            items = items,
            defaultTitle = "Curbox",
            defaultText = "Default",
            formatter = { LiveRuleNotificationFormatter.formatRuleStatusKorean(it.ruleName, it.usedMinutes, it.totalAllowedMinutes, it.guardianExtraMinutes) }
        )
        assertEquals("[소셜] 15분 사용 / 60분 허용", modelDefault.collapsedText)
        assertEquals(2, modelDefault.expandedLines.size)
        assertEquals(
            "[소셜] 15분 사용 / 60분 허용\n[게임] 25분 사용 / 40분 허용 (추가 10분 포함)",
            modelDefault.expandedText
        )
        assertTrue(modelDefault.hasMultipleRules)

        // With foreground package mapping to rule-2: collapsed shows rule-2
        val modelForeground = LiveRuleNotificationStateCalculator.buildNotificationModel(
            items = items,
            defaultTitle = "Curbox",
            defaultText = "Default",
            formatter = { LiveRuleNotificationFormatter.formatRuleStatusKorean(it.ruleName, it.usedMinutes, it.totalAllowedMinutes, it.guardianExtraMinutes) },
            foregroundPackage = "com.game.app",
            rulePackageResolver = { ruleId -> if (ruleId == "rule-2") setOf("com.game.app") else emptySet() }
        )
        assertEquals("[게임] 25분 사용 / 40분 허용 (추가 10분 포함)", modelForeground.collapsedText)
        assertEquals(2, modelForeground.expandedLines.size)
        assertEquals(
            "[소셜] 15분 사용 / 60분 허용\n[게임] 25분 사용 / 40분 허용 (추가 10분 포함)",
            modelForeground.expandedText
        )
    }

    @Test
    fun computeNotificationItemsWithUnmetTotalConditionPopulatesConditionProgress() {
        val group = AppRuleAppGroup.create("Social", listOf("com.social.app"))
        val contributorGroup = AppRuleAppGroup.create("Study", listOf("com.other.app"))
        val rule = AppRule(
            id = "rule-condition",
            name = "Social Limits",
            weekdays = (0..6).toSet(),
            startMinute = 0,
            endMinute = 24 * 60,
            scope = AppRuleScope.forGroup(group.id),
            allowedMinutes = 30L,
            usageConditionEnabled = true,
            contributorGroupIds = setOf(contributorGroup.id),
            usageConditionMinutes = 20L
        )
        val snapshot = AppRuleSnapshot(listOf(group, contributorGroup), listOf(rule))

        // 19 minutes total phone usage across all apps
        val sessions = listOf(
            ForegroundSession(
                useDayId = useDayId,
                packageName = "com.other.app",
                startedAtMs = now - 19 * 60_000L,
                endedAtMs = now
            )
        )

        val items = LiveRuleNotificationStateCalculator.computeNotificationItems(
            snapshot = snapshot,
            sessions = sessions,
            useDayId = useDayId,
            nowMs = now,
            zone = zone
        )

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(1, item.conditionProgresses.size)
        val cond = item.conditionProgresses.single()
        assertTrue(cond.isTotalCondition)
        assertFalse(cond.isMet)
        assertEquals(19 * 60_000L, cond.currentMillis)
        assertEquals(20 * 60_000L, cond.requiredMillis)

        val curM = cond.currentMillis / 60_000L
        val reqM = cond.requiredMillis / 60_000L
        val progressTextEn = LiveRuleNotificationFormatter.formatConditionProgress(curM, reqM, unit = "m")
        assertEquals("(19m/20m)", progressTextEn)

        val formattedEn = LiveRuleNotificationFormatter.formatRuleStatus(
            ruleName = item.ruleName,
            usedMinutes = item.usedMinutes,
            totalAllowedMinutes = item.totalAllowedMinutes,
            guardianExtraMinutes = item.guardianExtraMinutes,
            conditionProgressText = progressTextEn
        )
        assertEquals("[Social Limits] (19m/20m) 0 min used / 30 min allowed", formattedEn)

        val progressTextKo = LiveRuleNotificationFormatter.formatConditionProgress(curM, reqM, unit = "분")
        assertEquals("(19분/20분)", progressTextKo)

        val formattedKo = LiveRuleNotificationFormatter.formatRuleStatusKorean(
            ruleName = item.ruleName,
            usedMinutes = item.usedMinutes,
            totalAllowedMinutes = item.totalAllowedMinutes,
            guardianExtraMinutes = item.guardianExtraMinutes,
            conditionProgressText = progressTextKo
        )
        assertEquals("[Social Limits] (19분/20분) 0분 사용 / 30분 허용", formattedKo)
    }
}
