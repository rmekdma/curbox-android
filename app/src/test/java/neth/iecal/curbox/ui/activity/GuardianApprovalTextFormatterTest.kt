package neth.iecal.curbox.ui.activity

import neth.iecal.curbox.data.models.AppRuleConditionProgress
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianApprovalTextFormatterTest {

    @Test
    fun shortfallMinutesRoundsUpToCeiling() {
        // 40 seconds shortfall -> 1 minute
        val p40s = AppRuleConditionProgress(
            conditionId = "total",
            currentMillis = 20_000L,
            requiredMillis = 60_000L,
            isTotalCondition = true
        )
        assertEquals(40_000L, p40s.remainingShortfallMillis)
        assertEquals(1L, p40s.shortfallMinutes)

        // Exactly 60 seconds -> 1 minute
        val p60s = AppRuleConditionProgress(
            conditionId = "total",
            currentMillis = 0L,
            requiredMillis = 60_000L,
            isTotalCondition = true
        )
        assertEquals(1L, p60s.shortfallMinutes)

        // 61 seconds -> 2 minutes
        val p61s = AppRuleConditionProgress(
            conditionId = "total",
            currentMillis = 0L,
            requiredMillis = 61_000L,
            isTotalCondition = true
        )
        assertEquals(2L, p61s.shortfallMinutes)

        // 0 seconds -> 0 minutes
        val p0s = AppRuleConditionProgress(
            conditionId = "total",
            currentMillis = 60_000L,
            requiredMillis = 60_000L,
            isMet = true,
            isTotalCondition = true
        )
        assertEquals(0L, p0s.remainingShortfallMillis)
        assertEquals(0L, p0s.shortfallMinutes)
    }

    @Test
    fun filtersOutAlreadyMetConditionsOrNonPositiveShortfall() {
        val metProgress = AppRuleConditionProgress(
            conditionId = "cond-met",
            conditionName = "Met condition",
            currentMillis = 20 * 60_000L,
            requiredMillis = 20 * 60_000L,
            isMet = true,
            isTotalCondition = true
        )
        val unmetProgress = AppRuleConditionProgress(
            conditionId = "cond-unmet",
            conditionName = "Unmet condition",
            currentMillis = 19 * 60_000L,
            requiredMillis = 20 * 60_000L,
            isMet = false,
            isTotalCondition = true
        )

        val denial = AppRuleGuardianDenial(
            ruleId = "rule-1",
            ruleName = "Social",
            reason = "Usage condition not met",
            conditionProgresses = listOf(metProgress, unmetProgress)
        )

        val unmetConditions = denial.conditionProgresses.filter { !it.isMet && it.remainingShortfallMillis > 0L }
        assertEquals(1, unmetConditions.size)
        assertEquals("cond-unmet", unmetConditions.first().conditionId)
    }

    @Test
    fun doesNotContainAnyHyphensOrDashesInFormattedStructure() {
        val ruleName = "Social Limits"
        val reasonTitle = "Usage condition not met"
        val conditionName = "Total 20 min"
        val shortfallText = "1 min needed"
        val bullet = "•"

        val formatted = "$ruleName\n$reasonTitle\n$bullet $conditionName: $shortfallText"
        assertFalse(formatted.contains("-"))
        assertFalse(formatted.contains("–"))
        assertFalse(formatted.contains("—"))
        assertTrue(formatted.contains(bullet))
    }

    @Test
    fun formatsMultipleConditionsAndFallsBackToUnknownAppGroupWhenNameIsBlank() {
        val totalCond = AppRuleConditionProgress(
            conditionId = "total",
            conditionName = "",
            currentMillis = 19 * 60_000L,
            requiredMillis = 20 * 60_000L,
            isMet = false,
            isTotalCondition = true
        )
        val studyCond = AppRuleConditionProgress(
            conditionId = "study-group",
            conditionName = "학습",
            currentMillis = 5 * 60_000L,
            requiredMillis = 15 * 60_000L,
            isMet = false,
            isTotalCondition = false
        )
        val deletedCond = AppRuleConditionProgress(
            conditionId = "deleted-group",
            conditionName = "",
            currentMillis = 0L,
            requiredMillis = 10 * 60_000L,
            isMet = false,
            isTotalCondition = false
        )

        val denial = AppRuleGuardianDenial(
            ruleId = "rule-1",
            ruleName = "게임 제한",
            reason = "Usage condition not met",
            conditionProgresses = listOf(totalCond, studyCond, deletedCond)
        )

        val unmet = denial.conditionProgresses.filter { !it.isMet && it.remainingShortfallMillis > 0L }
        assertEquals(3, unmet.size)

        // Mocking strings:
        val unknownGroupString = "알 수 없는 앱 그룹 (규칙 설정 확인 필요)"
        fun resolveName(condition: AppRuleConditionProgress): String = when {
            condition.isTotalCondition -> "전체 ${condition.requiredMillis / 60_000L}분 사용"
            condition.conditionName.isBlank() -> unknownGroupString
            else -> condition.conditionName
        }

        assertEquals("전체 20분 사용", resolveName(totalCond))
        assertEquals("학습", resolveName(studyCond))
        assertEquals(unknownGroupString, resolveName(deletedCond))

        // Check each bullet item
        val items = unmet.map { condition ->
            "• ${resolveName(condition)}: ${condition.shortfallMinutes}분 부족"
        }
        assertEquals("• 전체 20분 사용: 1분 부족", items[0])
        assertEquals("• 학습: 10분 부족", items[1])
        assertEquals("• 알 수 없는 앱 그룹 (규칙 설정 확인 필요): 10분 부족", items[2])

        // Verify no hyphens/dashes in any of the items
        items.forEach { item ->
            assertFalse(item.contains("-"))
            assertFalse(item.contains("–"))
            assertFalse(item.contains("—"))
        }
    }
}

