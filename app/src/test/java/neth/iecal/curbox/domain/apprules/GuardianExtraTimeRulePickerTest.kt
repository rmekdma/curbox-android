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
    fun mainAppCandidatesUseEveryEnabledAllowedRuleAndPrioritizeCurrentDenials() {
        val snapshot = AppRuleSnapshot(
            appRules = listOf(
                AppRule(id = "first-allowed"),
                AppRule(id = "inactive", isActive = false),
                AppRule(id = "inactive-window", weekdays = setOf(6)),
                AppRule(id = "denying", allowedMinutes = 0L),
                AppRule(id = "disallowed", guardianExtraTimeAllowed = false)
            )
        )
        val evaluations = listOf(
            AppRuleEvaluation("first-allowed", true, true, 0L, 10L, 10L, true),
            AppRuleEvaluation("inactive", true, false, 0L, 0L, 0L, false),
            AppRuleEvaluation("inactive-window", true, false, 0L, 0L, 0L, true),
            AppRuleEvaluation("denying", true, true, 0L, 0L, 0L, false),
            AppRuleEvaluation("disallowed", true, true, 0L, 0L, 0L, false)
        )

        val candidates = GuardianExtraTimeRulePicker.candidates(snapshot, evaluations)

        assertEquals(
            listOf("denying", "first-allowed", "inactive-window"),
            candidates.map(AppRule::id)
        )
        assertEquals("denying", candidates.first().id)
    }

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

        assertEquals(
            emptyList<AppRule>(),
            GuardianExtraTimeRulePicker.candidates(snapshot, emptySet<String>())
        )
    }

    @Test
    fun lockScreenAndMainPickerShareGlobalScopedRulePriority() {
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
        val laterGroup = AppRuleAppGroup(
            id = "later",
            name = "Later apps",
            selectedPackages = listOf("example.later")
        )
        val inactiveGroup = AppRuleAppGroup(
            id = "inactive",
            name = "Inactive apps",
            selectedPackages = listOf("example.other")
        )
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(targetGroup, laterGroup, inactiveGroup),
            appRules = listOf(
                AppRule(
                    id = "first-allowed",
                    name = "First allowed rule",
                    appGroupId = targetGroup.id,
                    allowedMinutes = 20L
                ),
                AppRule(
                    id = "later-denial",
                    name = "Later denial",
                    appGroupId = laterGroup.id,
                    allowedMinutes = 0L
                ),
                AppRule(
                    id = "inactive-window",
                    name = "Inactive window",
                    appGroupId = inactiveGroup.id,
                    weekdays = setOf((weekday + 1) % 7),
                    usageConditionEnabled = true,
                    usageConditionMinutes = 1L,
                    contributorGroupIds = setOf("missing-contributor"),
                    allowedMinutes = 0L
                )
            )
        ).normalized()
        val sessions = emptyList<neth.iecal.curbox.data.models.ForegroundSession>()
        val evaluations = snapshot.appRules.map { rule ->
            AppRuleEvaluator.evaluateRuleForSnapshot(
                snapshot = snapshot,
                rule = rule,
                useDayId = useDay,
                sessions = sessions,
                nowMs = nowMs,
                useDayCalculator = ConfigurableUseDayCalculator(resetTime = UseDayResetTime())
            )
        }
        val mainCandidates = GuardianExtraTimeRulePicker.candidates(snapshot, evaluations)

        val lockScreenEvaluations = GuardianExtraTimeRulePicker.evaluateRules(
            snapshot = snapshot,
            useDayId = useDay,
            sessions = sessions,
            nowMs = nowMs,
            resetTime = UseDayResetTime()
        )
        val lockScreenCandidates = GuardianExtraTimeRulePicker.candidates(
            snapshot,
            lockScreenEvaluations.values
        )

        assertEquals(
            listOf("later-denial", "first-allowed", "inactive-window"),
            mainCandidates.map(AppRule::id)
        )
        assertEquals(mainCandidates, lockScreenCandidates)
    }
}
