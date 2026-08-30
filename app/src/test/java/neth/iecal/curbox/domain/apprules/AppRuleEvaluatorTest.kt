package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import neth.iecal.curbox.utils.UseDayResetTime

class AppRuleEvaluatorTest {

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
    private val group = AppRuleAppGroup.create("Study", listOf("com.example.reader"))

    @Test
    fun zeroAllowanceDeniesImmediatelyDuringActiveInterval() {
        val rule = rule(allowedMinutes = 0)

        val result = evaluate(rule)

        assertFalse(result.isAllowed)
        assertEquals(0L, result.denyingRules.single().remainingMillis)
    }

    @Test
    fun allAppsRuleIncludesForegroundPackageWhenLauncherListingOmitsIt() {
        val allAppsRule = AppRule(
            id = "global",
            name = "All apps lockdown",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope(includeAllApps = true),
            allowedMinutes = 0
        )

        val result = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(appRules = listOf(allAppsRule)),
            packageName = "com.example.reader",
            useDayId = "2026-08-17",
            sessions = emptyList(),
            nowMs = now,
            zone = zone,
            // A launcher snapshot can be non-empty while omitting the package currently in the
            // foreground. That package must still be evaluated by an all-apps rule.
            availablePackages = setOf("com.example.other")
        )

        assertFalse(result.isAllowed)
        assertEquals(listOf("global"), result.denyingRules.map { it.ruleId })
    }

    @Test
    fun allAppsDenialStillWinsWhenAnotherRuleHasGuardianAllowance() {
        val targetRule = AppRule(
            id = "target",
            name = "Target allowance",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope.forGroup(group.id),
            allowedMinutes = 0
        )
        val globalRule = AppRule(
            id = "global",
            name = "All apps lockdown",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope(includeAllApps = true),
            allowedMinutes = 0
        )
        val useDayId = "2026-08-17"
        val result = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(
                appGroups = listOf(group),
                appRules = listOf(targetRule, globalRule)
            ),
            packageName = "com.example.reader",
            useDayId = useDayId,
            sessions = listOf(session("com.example.reader", 2 * 60_000L)),
            nowMs = now,
            zone = zone,
            // The non-empty launcher listing omits the app currently being used.
            availablePackages = setOf("com.example.other"),
            overrides = AppRuleOverrideState(
                useDayId = useDayId,
                grants = listOf(
                    AppRuleGuardianGrant(
                        ruleId = targetRule.id,
                        useDayId = useDayId,
                        grantedAtMs = now - 60_000L,
                        grantedMillis = 10 * 60_000L
                    )
                )
            )
        )

        assertFalse(result.isAllowed)
        assertEquals(listOf("global"), result.denyingRules.map { it.ruleId })
    }

    @Test
    fun usageIsSummedAcrossTargetPackagesAndStopsAtAllowance() {
        val secondPackage = "com.example.notes"
        val twoApps = group.copy(selectedPackages = group.selectedPackages + secondPackage)
        val rule = rule(allowedMinutes = 10)
        val sessions = listOf(
            session("com.example.reader", 10 * 60_000L),
            session(secondPackage, 1 * 60_000L)
        )

        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(twoApps), listOf(rule)),
            "com.example.reader",
            useDayId = "2026-08-17",
            sessions,
            now,
            zone
        )

        assertFalse(result.isAllowed)
        assertEquals(11 * 60_000L, result.evaluations.single().usedMillis)
    }

    @Test
    fun usageOutsideActiveIntervalDoesNotConsumeAllowance() {
        val rule = rule(allowedMinutes = 10, startMinute = 12 * 60, endMinute = 13 * 60)
        val result = evaluate(rule, sessions = listOf(session("com.example.reader", 30 * 60_000L)))

        assertTrue(result.isAllowed)
        assertFalse(result.evaluations.single().isActive)
        assertEquals(0L, result.evaluations.single().usedMillis)
    }

    @Test
    fun multipleApplicableRulesUseAnyDenySemantics() {
        val allowing = rule(id = "allowing", allowedMinutes = 30)
        val denying = rule(id = "denying", allowedMinutes = 0)

        val result = evaluate(rule = allowing, additionalRules = listOf(denying))

        assertFalse(result.isAllowed)
        assertEquals(listOf("denying"), result.denyingRules.map { it.ruleId })
    }

    @Test
    fun overnightIntervalBelongsToItsStartWeekday() {
        val overnightNow = Instant.parse("2026-08-18T01:00:00Z").toEpochMilli()
        // 2026-08-17 is Monday, represented by 1 in the app's Sunday based convention.
        val rule = rule(
            weekdays = setOf(1),
            startMinute = 22 * 60,
            endMinute = 6 * 60,
            allowedMinutes = 1
        )
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(rule)),
            "com.example.reader",
            "2026-08-17",
            emptyList(),
            overnightNow,
            zone
        )

        assertTrue(result.evaluations.single().isActive)
    }

    @Test
    fun allowanceIncludesAnOvernightIntervalBeforeTheCurrentActiveWindow() {
        val lateNow = Instant.parse("2026-08-17T23:00:00Z").toEpochMilli()
        val rule = rule(
            weekdays = setOf(0, 1),
            startMinute = 22 * 60,
            endMinute = 6 * 60,
            allowedMinutes = 8 * 60L
        )
        val previousOvernight = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.example.reader",
            // Only the post-reset part belongs to this use day. The rule still owns it by
            // Sunday's start weekday until the overnight window ends at Monday 06:00.
            startedAtMs = Instant.parse("2026-08-17T04:00:00Z").toEpochMilli(),
            endedAtMs = Instant.parse("2026-08-17T05:00:00Z").toEpochMilli()
        )

        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(rule)),
            "com.example.reader",
            "2026-08-17",
            listOf(previousOvernight),
            lateNow,
            zone
        )

        assertEquals(60 * 60_000L, result.evaluations.single().usedMillis)
        assertEquals(7 * 60 * 60_000L, result.evaluations.single().remainingMillis)
    }

    @Test
    fun malformedSnapshotFailsClosed() {
        val rule = rule().copy(appGroupId = "missing")

        val result = evaluate(rule)

        assertFalse(result.isAllowed)
        assertTrue(result.denyingRules.single().validationErrors.isNotEmpty())
    }

    @Test
    fun createAndCopyIssueNewStableIds() {
        val rule = rule()
        val copiedRule = rule.copyWithNewId()
        val copiedGroup = group.copyWithNewId()

        assertFalse(rule.id == copiedRule.id)
        assertFalse(group.id == copiedGroup.id)
        assertEquals(rule.name, copiedRule.name)
        assertEquals(group.selectedPackages, copiedGroup.selectedPackages)
    }

    @Test
    fun customResetTimeUsesTheConfiguredUseDayWindowForIntersections() {
        val rule = rule(allowedMinutes = 10)
        val session = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.example.reader",
            startedAtMs = Instant.parse("2026-08-17T10:00:00Z").toEpochMilli(),
            endedAtMs = Instant.parse("2026-08-17T10:05:00Z").toEpochMilli()
        )

        val result = AppRuleEvaluator.evaluateWithResetTime(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            packageName = "com.example.reader",
            useDayId = "2026-08-17",
            sessions = listOf(session),
            nowMs = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli(),
            resetTime = UseDayResetTime(6, 0),
            zone = zone
        )

        assertEquals(5 * 60_000L, result.evaluations.single().usedMillis)
    }

    @Test
    fun resetGenerationDoesNotReinterpretSessionsFromBeforeTheSettingChange() {
        val rule = rule(allowedMinutes = 30)
        val generation = now - 5 * 60_000L
        val oldSession = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.example.reader",
            startedAtMs = now - 20 * 60_000L,
            endedAtMs = now - 10 * 60_000L,
            useDayGenerationStartedAtMs = 0L
        )
        val newSession = oldSession.copy(
            startedAtMs = generation,
            endedAtMs = now,
            useDayGenerationStartedAtMs = generation
        )

        val result = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            packageName = "com.example.reader",
            useDayId = "2026-08-17",
            sessions = listOf(oldSession, newSession),
            nowMs = now,
            zone = zone,
            useDayGenerationStartedAtMs = generation
        )

        assertEquals(5 * 60_000L, result.evaluations.single().usedMillis)
    }

    @Test
    fun enforcementLedgerStillConsumesTimeWhenStatisticsAreDisabled() {
        val rule = rule(allowedMinutes = 1)
        val enforcementOnlySession = session("com.example.reader", 2 * 60_000L).copy(
            statisticsTracked = false
        )

        val result = evaluate(rule, sessions = listOf(enforcementOnlySession))

        assertFalse(result.isAllowed)
        assertEquals(2 * 60_000L, result.evaluations.single().usedMillis)
    }

    @Test
    fun compositeScopeAppliesAllAppsButExcludesAnIncludedGroup() {
        val excluded = AppRuleAppGroup.create("Excluded", listOf("com.example.notes"))
        val composite = rule(allowedMinutes = 0).copy(
            appGroupId = "",
            scope = AppRuleScope(
                includeAllApps = true,
                includedGroupIds = setOf(group.id),
                excludedGroupIds = setOf(excluded.id)
            )
        )
        val snapshot = AppRuleSnapshot(
            listOf(group, excluded),
            listOf(composite)
        )

        val included = AppRuleEvaluator.evaluate(
            snapshot,
            "com.example.reader",
            "2026-08-17",
            emptyList(),
            now,
            zone,
            availablePackages = setOf("com.example.reader", "com.example.notes", "com.example.future")
        )
        val excludedResult = AppRuleEvaluator.evaluate(
            snapshot,
            "com.example.notes",
            "2026-08-17",
            emptyList(),
            now,
            zone,
            availablePackages = setOf("com.example.reader", "com.example.notes", "com.example.future")
        )

        assertFalse(included.isAllowed)
        assertTrue(excludedResult.isAllowed)
        assertTrue(excludedResult.evaluations.isEmpty())
    }

    @Test
    fun essentialPackagesAreRemovedEvenWhenAllAppsIsTheOnlyInclude() {
        val allAppsRule = rule(allowedMinutes = 0).copy(
            appGroupId = "",
            scope = AppRuleScope(includeAllApps = true)
        )
        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group), listOf(allAppsRule)),
            "com.android.systemui",
            "2026-08-17",
            emptyList(),
            now,
            zone,
            availablePackages = setOf("com.android.systemui", "com.example.reader"),
            essentialExcludedPackages = setOf("com.android.systemui")
        )

        assertTrue(result.isAllowed)
        assertTrue(result.evaluations.isEmpty())
    }

    @Test
    fun foregroundPackageAddedForAllAppsIsStillRemovedWhenItIsEssential() {
        val allAppsRule = AppRule(
            id = "global",
            name = "All apps lockdown",
            weekdays = setOf(1),
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            scope = AppRuleScope(includeAllApps = true),
            allowedMinutes = 0
        )

        val result = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(appRules = listOf(allAppsRule)),
            packageName = "com.android.systemui",
            useDayId = "2026-08-17",
            sessions = emptyList(),
            nowMs = now,
            zone = zone,
            availablePackages = setOf("com.example.reader"),
            essentialExcludedPackages = setOf("com.android.systemui")
        )

        assertTrue(result.isAllowed)
        assertTrue(result.evaluations.isEmpty())
    }

    @Test
    fun touchingRangesShareOneAllowanceAndDoNotDoubleChargeTheirBoundary() {
        val rule = rule(allowedMinutes = 90).copy(
            timeRanges = listOf(
                neth.iecal.curbox.data.models.AppRuleTimeRange(9 * 60, 10 * 60),
                neth.iecal.curbox.data.models.AppRuleTimeRange(10 * 60, 11 * 60)
            )
        )
        val session = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.example.reader",
            startedAtMs = Instant.parse("2026-08-17T09:00:00Z").toEpochMilli(),
            endedAtMs = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        )

        val result = evaluate(rule, sessions = listOf(session))

        assertEquals(90 * 60_000L, result.evaluations.single().usedMillis)
        assertFalse(result.isAllowed)
    }

    @Test
    fun compositeNighttimeScopeUsesOneAllowanceAcrossOvernightSession() {
        val excluded = AppRuleAppGroup.create("Excluded", listOf("com.example.notes"))
        val rule = rule(allowedMinutes = 60).copy(
            appGroupId = "",
            weekdays = setOf(1),
            startMinute = 22 * 60,
            endMinute = 6 * 60,
            scope = AppRuleScope(
                includeAllApps = true,
                includedGroupIds = setOf(group.id),
                excludedGroupIds = setOf(excluded.id)
            )
        )
        val session = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.example.reader",
            startedAtMs = Instant.parse("2026-08-17T22:30:00Z").toEpochMilli(),
            endedAtMs = Instant.parse("2026-08-18T01:30:00Z").toEpochMilli()
        )

        val result = AppRuleEvaluator.evaluate(
            AppRuleSnapshot(listOf(group, excluded), listOf(rule)),
            "com.example.reader",
            "2026-08-17",
            listOf(session),
            Instant.parse("2026-08-18T01:30:00Z").toEpochMilli(),
            zone,
            availablePackages = setOf("com.example.reader", "com.example.notes", "com.example.future")
        )

        assertEquals(3 * 60 * 60_000L, result.evaluations.single().usedMillis)
        assertFalse(result.isAllowed)
    }

    private fun evaluate(
        rule: AppRule,
        sessions: List<ForegroundSession> = emptyList(),
        additionalRules: List<AppRule> = emptyList()
    ): AppRulesEvaluation = AppRuleEvaluator.evaluate(
        AppRuleSnapshot(listOf(group), listOf(rule) + additionalRules),
        "com.example.reader",
        "2026-08-17",
        sessions,
        now,
        zone
    )

    private fun rule(
        id: String = "rule",
        weekdays: Set<Int> = setOf(1),
        startMinute: Int = 9 * 60,
        endMinute: Int = 17 * 60,
        allowedMinutes: Long = 30
    ) = AppRule(
        id = id,
        name = id,
        weekdays = weekdays,
        startMinute = startMinute,
        endMinute = endMinute,
        appGroupId = group.id,
        allowedMinutes = allowedMinutes
    )

    private fun session(packageName: String, durationMillis: Long): ForegroundSession =
        ForegroundSession(
            useDayId = "2026-08-17",
            packageName = packageName,
            startedAtMs = now - durationMillis,
            endedAtMs = now
        )
}
