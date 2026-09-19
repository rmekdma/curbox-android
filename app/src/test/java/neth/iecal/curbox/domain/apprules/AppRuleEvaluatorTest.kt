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

    @Test
    fun computeUnusedGuardianMinutesReturnsZeroWhenRolloverDisabled() {
        val rule = rule(allowedMinutes = 30).copy(rolloverEnabled = false)
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        val grant = AppRuleGuardianGrant(rule.id, "2026-08-17", now - 3600_000L, 20 * 60_000L)
        val overrides = AppRuleOverrideState(grants = listOf(grant))

        val unused = AppRuleEvaluator.computeUnusedGuardianMinutes(
            rule = rule,
            snapshot = snapshot,
            useDayId = "2026-08-17",
            sessions = emptyList(),
            overrides = overrides,
            zone = zone
        )

        assertEquals(0L, unused)
    }

    @Test
    fun computeUnusedGuardianMinutesReturnsRemainingWhenUsageConsumesBaseAllowanceFirst() {
        val rule = rule(allowedMinutes = 30).copy(rolloverEnabled = true)
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        val grantTime = now - 60 * 60_000L
        val grant = AppRuleGuardianGrant(rule.id, "2026-08-17", grantTime, 20 * 60_000L)
        val overrides = AppRuleOverrideState(useDayId = "2026-08-17", grants = listOf(grant))

        val s = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.example.reader",
            startedAtMs = grantTime + 1000L,
            endedAtMs = grantTime + 1000L + 35 * 60_000L
        )

        val unused = AppRuleEvaluator.computeUnusedGuardianMinutes(
            rule = rule,
            snapshot = snapshot,
            useDayId = "2026-08-17",
            sessions = listOf(s),
            overrides = overrides,
            zone = zone
        )

        assertEquals(15L, unused)
    }

    @Test
    fun computeUnusedGuardianMinutesMatchesAllAppsRuleAgainstAvailablePackages() {
        val allAppsRule = rule(allowedMinutes = 30).copy(
            rolloverEnabled = true,
            scope = neth.iecal.curbox.data.models.AppRuleScope(includeAllApps = true)
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(allAppsRule))
        val grantTime = now - 60 * 60_000L
        val grant = AppRuleGuardianGrant(allAppsRule.id, "2026-08-17", grantTime, 20 * 60_000L)
        val overrides = AppRuleOverrideState(useDayId = "2026-08-17", grants = listOf(grant))

        val s = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "com.other.unlisted.app",
            startedAtMs = grantTime + 1000L,
            endedAtMs = grantTime + 1000L + 35 * 60_000L
        )

        val unused = AppRuleEvaluator.computeUnusedGuardianMinutes(
            rule = allAppsRule,
            snapshot = snapshot,
            useDayId = "2026-08-17",
            sessions = listOf(s),
            overrides = overrides,
            zone = zone,
            availablePackages = setOf("com.other.unlisted.app")
        )

        assertEquals(15L, unused)
    }

    @Test
    fun unapprovedAccumulatedPoolDoesNotCountAsAllowanceOnUnlockDay() {
        // Saturday (unlock day: 6), base allowance 30 minutes
        val saturdayNow = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli()
        val rule = rule(
            id = "game",
            weekdays = setOf(6),
            startMinute = 9 * 60,
            endMinute = 21 * 60,
            allowedMinutes = 30
        ).copy(rolloverEnabled = true, unlockDays = setOf(6))

        // Kid used all 30 minutes of base allowance
        val sessions = listOf(
            ForegroundSession(
                useDayId = "2026-08-22",
                packageName = "com.example.reader",
                startedAtMs = saturdayNow - 30 * 60_000L,
                endedAtMs = saturdayNow
            )
        )

        // Without an approved grant in overrides, unapproved pool is never added to allowance
        val result = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            packageName = "com.example.reader",
            useDayId = "2026-08-22",
            sessions = sessions,
            nowMs = saturdayNow,
            zone = zone
        )

        assertFalse(result.isAllowed)
        assertEquals(0L, result.evaluations.single().remainingMillis)
        assertTrue(result.evaluations.single().isAllowanceExhausted)
    }

    @Test
    fun approvedAccumulatedTimeUnlocksAppForApprovedDuration() {
        val saturdayNow = Instant.parse("2026-08-22T12:00:00Z").toEpochMilli()
        val rule = rule(
            id = "game",
            weekdays = setOf(6),
            startMinute = 9 * 60,
            endMinute = 21 * 60,
            allowedMinutes = 30
        ).copy(rolloverEnabled = true, unlockDays = setOf(6))

        val baseSession = ForegroundSession(
            useDayId = "2026-08-22",
            packageName = "com.example.reader",
            startedAtMs = saturdayNow - 30 * 60_000L,
            endedAtMs = saturdayNow
        )

        // Guardian approves 15 minutes of accumulated time
        val grantTime = saturdayNow + 60_000L
        val accumulatedGrant = AppRuleGuardianGrant(
            ruleId = "game",
            useDayId = "2026-08-22",
            grantedAtMs = grantTime,
            grantedMillis = 15 * 60_000L,
            isFromAccumulatedPool = true
        )
        val overrides = AppRuleOverrideState(
            useDayId = "2026-08-22",
            grants = listOf(accumulatedGrant)
        )

        // Right after grant: unlocked with 15 minutes remaining
        val evalAfterGrant = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            packageName = "com.example.reader",
            useDayId = "2026-08-22",
            sessions = listOf(baseSession),
            nowMs = grantTime + 1000L,
            zone = zone,
            overrides = overrides
        )
        assertTrue(evalAfterGrant.isAllowed)
        assertEquals(15 * 60_000L, evalAfterGrant.evaluations.single().remainingMillis)
        assertEquals(15 * 60_000L, evalAfterGrant.evaluations.single().guardianRemainingMillis)

        // After consuming 15 minutes: locked again
        val extraSession = ForegroundSession(
            useDayId = "2026-08-22",
            packageName = "com.example.reader",
            startedAtMs = grantTime + 1000L,
            endedAtMs = grantTime + 1000L + 15 * 60_000L
        )
        val evalAfterConsumed = AppRuleEvaluator.evaluate(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            packageName = "com.example.reader",
            useDayId = "2026-08-22",
            sessions = listOf(baseSession, extraSession),
            nowMs = grantTime + 1000L + 15 * 60_000L,
            zone = zone,
            overrides = overrides
        )
        assertFalse(evalAfterConsumed.isAllowed)
        assertEquals(0L, evalAfterConsumed.evaluations.single().remainingMillis)
    }

    @Test
    fun strictTimeRangeCutoffBlocksAppRegardlessOfApprovedAccumulatedTime() {
        val saturdayEvening = Instant.parse("2026-08-22T21:55:00Z").toEpochMilli() // 21:55 UTC
        val gameRule = rule(
            id = "game",
            weekdays = setOf(6),
            startMinute = 9 * 60,
            endMinute = 24 * 60, // Game rule itself allows up to midnight
            allowedMinutes = 30
        ).copy(rolloverEnabled = true, unlockDays = setOf(6))

        // Bedtime rule: 22:00 to 07:00 lockdown (0 allowance) for all apps
        val bedtimeRule = AppRule(
            id = "bedtime-lockdown",
            name = "Bedtime Lockdown",
            weekdays = (0..6).toSet(),
            startMinute = 22 * 60,
            endMinute = 7 * 60,
            scope = AppRuleScope(includeAllApps = true),
            allowedMinutes = 0L
        )

        // Kid has 30 minutes approved accumulated time granted at 21:50
        val grant = AppRuleGuardianGrant(
            ruleId = "game",
            useDayId = "2026-08-22",
            grantedAtMs = saturdayEvening - 5 * 60_000L,
            grantedMillis = 30 * 60_000L,
            isFromAccumulatedPool = true
        )
        val overrides = AppRuleOverrideState(
            useDayId = "2026-08-22",
            grants = listOf(grant)
        )

        val snapshot = AppRuleSnapshot(
            appGroups = listOf(group),
            appRules = listOf(gameRule, bedtimeRule)
        )

        // At 21:55: bedtime rule is inactive; game rule allows usage
        val evalBeforeBedtime = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.example.reader",
            useDayId = "2026-08-22",
            sessions = emptyList(),
            nowMs = saturdayEvening,
            zone = zone,
            overrides = overrides,
            availablePackages = setOf("com.example.reader")
        )
        assertTrue(evalBeforeBedtime.isAllowed)

        // At 22:01: bedtime rule becomes active and strictly denies, despite 24m extra time left on game rule
        val pastBedtime = Instant.parse("2026-08-22T22:01:00Z").toEpochMilli()
        val sessionBeforeBedtime = ForegroundSession(
            useDayId = "2026-08-22",
            packageName = "com.example.reader",
            startedAtMs = saturdayEvening,
            endedAtMs = pastBedtime
        )
        val evalAtBedtime = AppRuleEvaluator.evaluate(
            snapshot = snapshot,
            packageName = "com.example.reader",
            useDayId = "2026-08-22",
            sessions = listOf(sessionBeforeBedtime),
            nowMs = pastBedtime,
            zone = zone,
            overrides = overrides,
            availablePackages = setOf("com.example.reader")
        )
        assertFalse(evalAtBedtime.isAllowed)
        assertEquals(listOf("bedtime-lockdown"), evalAtBedtime.denyingRules.map { it.ruleId })
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
