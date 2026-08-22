package neth.iecal.curbox.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class LegacyAppRuleMigrationTest {
    private val gson = Gson()

    @Test
    fun migrationPreservesGroupAndCreatesDeterministicRule() {
        val legacy = AppGroup(
            id = "study",
            name = "Study apps",
            selectedPackages = listOf(" com.reader ", "com.reader"),
            isActive = true,
            config = AppGroupConfig(
                schedule = AppTimeConfig(
                    isEveryday = true,
                    everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))
                ),
                usage = AppUsageConfig(uniformLimit = 45)
            )
        )

        val snapshot = LegacyAppRuleMigration.migrate(listOf(legacy))

        assertEquals(listOf("legacy-app-group:study"), snapshot.appGroups.map { it.id })
        assertEquals(setOf("com.reader"), snapshot.appGroups.single().selectedPackages.toSet())
        assertEquals(1, snapshot.appRules.size)
        assertEquals("legacy-app-rule:study:0-1-2-3-4-5-6:540-1020:45", snapshot.appRules.single().id)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), snapshot.appRules.single().weekdays)
        assertEquals(45L, snapshot.appRules.single().allowedMinutes)
        assertEquals(AppRuleScope.forGroup("legacy-app-group:study"), snapshot.appRules.single().effectiveScope())
        assertTrue(snapshot.isValid)
    }

    @Test
    fun migrationGroupsDifferentDailyLimitsWithoutChangingMeaning() {
        val legacy = AppGroup(
            id = "daily",
            name = "Daily",
            isActive = true,
            config = AppGroupConfig(
                schedule = AppTimeConfig(
                    isEveryday = false,
                    dailyIntervals = mutableMapOf(
                        1 to mutableListOf(TimeInterval(9, 0, 10, 0)),
                        2 to mutableListOf(TimeInterval(9, 0, 10, 0)),
                        3 to mutableListOf(TimeInterval(18, 0, 20, 0))
                    )
                ),
                usage = AppUsageConfig(
                    isDailyUniform = false,
                    dailyLimits = longArrayOf(0, 30, 45, 60, 0, 0, 0)
                )
            )
        )

        val rules = LegacyAppRuleMigration.migrate(listOf(legacy)).appRules

        assertEquals(3, rules.size)
        assertEquals(setOf(1), rules.single { it.allowedMinutes == 30L }.weekdays)
        assertEquals(setOf(2), rules.single { it.allowedMinutes == 45L }.weekdays)
        assertEquals(setOf(3), rules.single { it.allowedMinutes == 60L }.weekdays)
    }

    @Test
    fun migrationIsIdempotentAndDoesNotDuplicateExistingNeutralData() {
        val legacy = AppGroup(
            id = "same",
            name = "Same",
            selectedPackages = listOf("com.example"),
            config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 10))
        )
        val first = LegacyAppRuleMigration.migrate(listOf(legacy))
        val second = LegacyAppRuleMigration.migrate(listOf(legacy), first)

        assertEquals(first, second)
        assertEquals(1, second.appGroups.size)
        assertEquals(1, second.appRules.size)
    }

    @Test
    fun migrationKeepsUserCreatedNeutralGroupsAndRules() {
        val userGroup = AppRuleAppGroup("user", "User group", listOf("com.user"))
        val userRule = AppRule(
            id = "user-rule",
            name = "User rule",
            appGroupId = userGroup.id,
            scope = AppRuleScope.forGroup(userGroup.id),
            weekdays = (0..6).toSet(),
            timeRanges = listOf(AppRuleTimeRange(0, 0)),
            allowedMinutes = 20
        )
        val legacy = AppGroup(
            id = "legacy",
            name = "Legacy",
            config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 10))
        )

        val migrated = LegacyAppRuleMigration.migrate(
            listOf(legacy),
            AppRuleSnapshot(listOf(userGroup), listOf(userRule))
        )

        assertTrue(migrated.appGroups.any { it.id == userGroup.id })
        assertTrue(migrated.appRules.any { it.id == userRule.id })
        assertTrue(migrated.isValid)
    }

    @Test
    fun oldJsonDefaultsStillBecomeAValidNeutralSnapshot() {
        val oldJson = gson.fromJson(
            "{\"id\":\"on-open\",\"name\":\"On open\",\"isActive\":true,\"blockingType\":\"OnOpen\"}",
            AppGroup::class.java
        )

        val migrated = LegacyAppRuleMigration.migrate(listOf(oldJson))

        assertEquals(1, migrated.appGroups.size)
        assertEquals(1, migrated.appRules.size)
        assertEquals(0L, migrated.appRules.single().allowedMinutes)
        assertEquals(AppRuleTimeRange(0, 1440), migrated.appRules.single().effectiveTimeRanges().single())
        assertTrue(migrated.isValid)
    }

    @Test
    fun replaceLegacyDeletesRemovedLegacyGroupsAndRulesWhileRetainingNeutralOnes() {
        val legacy1 = AppGroup(id = "study", name = "Study", config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 30)))
        val legacy2 = AppGroup(id = "games", name = "Games", config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 15)))
        val initial = LegacyAppRuleMigration.migrate(listOf(legacy1, legacy2))

        val userGroup = AppRuleAppGroup("user-1", "Custom group", listOf("com.custom"))
        val userRule = AppRule("rule-1", "Custom rule", appGroupId = "user-1", scope = AppRuleScope.forGroup("user-1"), weekdays = (0..6).toSet(), allowedMinutes = 10)
        val combined = initial.copy(
            appGroups = initial.appGroups + userGroup,
            appRules = initial.appRules + userRule
        )

        // Replacing legacy with only legacy1 (legacy2 is deleted)
        val replaced = LegacyAppRuleMigration.replaceLegacy(listOf(legacy1), combined)

        assertEquals(setOf("legacy-app-group:study", "user-1"), replaced.appGroups.map { it.id }.toSet())
        assertTrue(replaced.appRules.none { it.id.contains("games") })
        assertTrue(replaced.appRules.any { it.id.contains("study") })
        assertTrue(replaced.appRules.any { it.id == "rule-1" })
        assertTrue(replaced.isValid)
    }

    @Test
    fun migrationRespectsTemporaryDisableDeadlines() {
        val now = 1_700_000_000_000L
        val expired = AppGroup(id = "exp", name = "Expired", isActive = false, temporarilyDisabledUntilMs = now - 5_000L, config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 20)))
        val activeFuture = AppGroup(id = "fut", name = "Future", isActive = false, temporarilyDisabledUntilMs = now + 50_000L, config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 20)))
        val manual = AppGroup(id = "man", name = "Manual", isActive = false, temporarilyDisabledUntilMs = -1L, config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 20)))

        val migrated = LegacyAppRuleMigration.migrate(listOf(expired, activeFuture, manual), nowMs = now)

        assertTrue(migrated.appRules.single { it.appGroupId.contains("exp") }.isActive)
        org.junit.Assert.assertFalse(migrated.appRules.single { it.appGroupId.contains("fut") }.isActive)
        org.junit.Assert.assertFalse(migrated.appRules.single { it.appGroupId.contains("man") }.isActive)
    }

    @Test
    fun corruptedAndEmptyLegacyGroupsSanitization() {
        val corrupt = AppGroup(id = "  ", name = "  ", config = null)
        val migrated = LegacyAppRuleMigration.migrate(listOf(corrupt))

        assertEquals(1, migrated.appGroups.size)
        assertEquals("legacy-app-group:index-0", migrated.appGroups.single().id)
        assertEquals("App group", migrated.appGroups.single().name)
        assertTrue(migrated.isValid)
    }

    @Test
    fun multiIntervalScheduleMigrationPreservesRanges() {
        val legacy = AppGroup(
            id = "intervals",
            name = "Intervals",
            config = AppGroupConfig(
                schedule = AppTimeConfig(
                    isEveryday = true,
                    everydayIntervals = mutableListOf(
                        TimeInterval(8, 30, 11, 45),
                        TimeInterval(13, 0, 17, 30)
                    )
                ),
                usage = AppUsageConfig(uniformLimit = 60)
            )
        )

        val snapshot = LegacyAppRuleMigration.migrate(listOf(legacy))
        val rule = snapshot.appRules.single()
        assertEquals(2, rule.effectiveTimeRanges().size)
        assertEquals(AppRuleTimeRange(510, 705), rule.effectiveTimeRanges()[0])
        assertEquals(AppRuleTimeRange(780, 1050), rule.effectiveTimeRanges()[1])
        assertEquals(60L, rule.allowedMinutes)
        assertTrue(snapshot.isValid)
    }
}
