package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardianExtraTimeRulePickerTest {
    @Test
    fun candidatesShowEveryEnabledAllowedRuleWithCurrentDenialsFirstAndStableOrder() {
        val rules = listOf(
            AppRule(id = "scheduled-later", name = "Scheduled later"),
            AppRule(id = "disabled", isActive = false),
            AppRule(id = "not-allowed", guardianExtraTimeAllowed = false),
            AppRule(id = "currently-denying-a"),
            AppRule(id = "currently-denying-b"),
            AppRule(id = "unrelated", name = "Unrelated app")
        )

        val candidates = GuardianExtraTimeRulePicker.candidates(
            snapshot = AppRuleSnapshot(appRules = rules),
            currentlyDenyingRuleIds = setOf("currently-denying-a", "currently-denying-b")
        )

        assertEquals(
            listOf("currently-denying-a", "currently-denying-b", "scheduled-later", "unrelated"),
            candidates.map(AppRule::id)
        )
        assertEquals("currently-denying-a", candidates.first().id)
    }

    @Test
    fun noCandidateIsReturnedWhenEveryRuleIsDisabledOrDisallowed() {
        val snapshot = AppRuleSnapshot(
            appRules = listOf(
                AppRule(id = "off", isActive = false),
                AppRule(id = "not-allowed", guardianExtraTimeAllowed = false)
            )
        )

        assertEquals(emptyList<AppRule>(), GuardianExtraTimeRulePicker.candidates(snapshot, emptySet()))
    }

    @Test
    fun existingEvaluationPrioritizesCurrentDenialsWithoutAForegroundSession() {
        val nowMs = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
        val useDay = ConfigurableUseDayCalculator().idAt(nowMs)
        val weekday = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneId.systemDefault())
            .dayOfWeek.value % 7
        val targetGroup = AppRuleAppGroup(
            id = "target",
            name = "Target apps",
            selectedPackages = listOf("example.target")
        )
        val otherGroup = AppRuleAppGroup(
            id = "other",
            name = "Other apps",
            selectedPackages = listOf("example.other")
        )
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(targetGroup, otherGroup),
            appRules = listOf(
                AppRule(
                    id = "night",
                    name = "Night rule",
                    appGroupId = targetGroup.id,
                    allowedMinutes = 0L
                ),
                AppRule(
                    id = "later",
                    name = "Later rule",
                    appGroupId = targetGroup.id,
                    weekdays = setOf((weekday + 1) % 7),
                    usageConditionEnabled = true,
                    usageConditionMinutes = 1L,
                    contributorGroupIds = setOf("missing-contributor"),
                    allowedMinutes = 0L
                ),
                AppRule(
                    id = "unrelated",
                    name = "Unrelated rule",
                    appGroupId = otherGroup.id,
                    allowedMinutes = 0L
                )
            )
        ).normalized()
        val evaluation = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "example.target",
            useDayId = useDay,
            sessions = emptyList(),
            nowMs = nowMs,
            resetTime = UseDayResetTime()
        )

        val candidates = GuardianExtraTimeRulePicker.candidates(
            snapshot = snapshot,
            packageName = "example.target",
            useDayId = useDay,
            sessions = emptyList(),
            nowMs = nowMs,
            resetTime = UseDayResetTime()
        )

        assertEquals(listOf("night", "later", "unrelated"), candidates.map(AppRule::id))
        assertEquals(listOf("night", "later"), evaluation.denyingRules.map { it.ruleId })
        assertEquals(listOf(true, false), evaluation.denyingRules.map { it.isActive })
    }
}
