package neth.iecal.curbox.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleRestrictionComparatorTest {
    private val group = AppRuleAppGroup("group", "Reader", listOf("com.example.reader"))
    private val rule = AppRule(
        id = "rule",
        name = "Reader limit",
        weekdays = (0..6).toSet(),
        startMinute = 9 * 60,
        endMinute = 17 * 60,
        appGroupId = group.id,
        allowedMinutes = 30
    )

    @Test
    fun reducingAllowanceIsSameOrStricter() {
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(rule)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group),
                listOf(rule.copy(allowedMinutes = 20))
            )
        )

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun addingAppsToAnExistingRuleIsSameOrStricter() {
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(rule)))
        val proposedGroup = group.copy(selectedPackages = group.selectedPackages + "com.example.notes")
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(listOf(proposedGroup), listOf(rule))
        )

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun removingAnExistingTargetAppIsDelayed() {
        val oldGroup = group.copy(selectedPackages = group.selectedPackages + "com.example.notes")
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(oldGroup), listOf(rule)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun oldSettingsJsonKeepsLegacyGroupsAndDefaultsNewSnapshot() {
        val legacy = Settings(
            blockedAppGroups = listOf(AppGroup(id = "legacy", name = "Legacy"))
        )
        val json = JsonParser.parseString(Gson().toJson(legacy)).asJsonObject.apply {
            remove("appRuleSnapshot")
        }

        val restored = Gson().fromJson(json, Settings::class.java)

        assertTrue(restored.appRuleSnapshot.appGroups.isEmpty())
        assertTrue(restored.appRuleSnapshot.appRules.isEmpty())
        assertTrue(restored.blockedAppGroups.any { it.id == "legacy" })
    }

    @Test
    fun addingAnExcludedGroupIsDelayedBecauseItRemovesTargetCoverage() {
        val excluded = AppRuleAppGroup("excluded", "Games", listOf("com.example.game"))
        val scoped = rule.copy(
            appGroupId = "",
            scope = AppRuleScope(
                includeAllApps = true,
                excludedGroupIds = setOf(excluded.id)
            ),
            timeRanges = listOf(AppRuleTimeRange(22 * 60, 6 * 60))
        )
        val old = Settings(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, excluded),
                listOf(scoped.copy(scope = AppRuleScope(includeAllApps = true)))
            )
        )
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, excluded),
                listOf(scoped)
            )
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun enablingEarnedAllowanceIsDelayed() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val old = Settings(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, contributor),
                listOf(rule.copy(contributorGroupIds = setOf(contributor.id)))
            )
        )
        val proposed = old.copy(
            appRuleSnapshot = old.appRuleSnapshot.copy(
                appRules = listOf(old.appRuleSnapshot.appRules.single().copy(
                    earnedAllowanceEnabled = true
                ))
            )
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun loweringPerGroupUsageConditionThresholdIsDelayed() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            contributorGroupConditionMinutes = mapOf(contributor.id to 20L)
        )
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, contributor),
                listOf(configured.copy(contributorGroupConditionMinutes = mapOf(contributor.id to 10L)))
            )
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun raisingPerGroupUsageConditionThresholdIsSameOrStricter() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            contributorGroupConditionMinutes = mapOf(contributor.id to 10L)
        )
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, contributor),
                listOf(configured.copy(contributorGroupConditionMinutes = mapOf(contributor.id to 20L)))
            )
        )

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun loweringUsageConditionThresholdIsDelayed() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            usageConditionEnabled = true,
            usageConditionMinutes = 20
        )
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, contributor),
                listOf(configured.copy(usageConditionMinutes = 10))
            )
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun addingContributorGroupIsDelayedWhenItCanEarnTime() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val secondContributor = AppRuleAppGroup("second", "Second", listOf("com.other"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, contributor, secondContributor),
                listOf(configured.copy(contributorGroupIds = setOf(contributor.id, secondContributor.id)))
            )
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun disablingEarnedAllowanceIsSameOrStricter() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured)))
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(
                listOf(group, contributor),
                listOf(configured.copy(earnedAllowanceEnabled = false))
            )
        )

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun deletingReferencedContributorIsImmediatelyStricterEvenWhenTheIdIsRetained() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )
        val old = Settings(
            appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured))
        )
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(configured))
        )

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun repairingAnOldMissingContributorIsDelayedBecauseItRestoresAllowance() {
        val contributor = AppRuleAppGroup("contributor", "Contributor", listOf("com.source"))
        val configured = rule.copy(
            contributorGroupIds = setOf(contributor.id),
            earnedAllowanceEnabled = true
        )
        val old = Settings(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(configured))
        )
        val proposed = old.copy(
            appRuleSnapshot = AppRuleSnapshot(listOf(group, contributor), listOf(configured))
        )

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun enablingRolloverIsDelayedBecauseItWeakensRestriction() {
        val oldRule = rule.copy(rolloverEnabled = false, unlockDays = emptySet())
        val proposedRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(oldRule)))
        val proposed = old.copy(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(proposedRule)))

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun disablingRolloverIsSameOrStricter() {
        val oldRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))
        val proposedRule = rule.copy(rolloverEnabled = false, unlockDays = emptySet())
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(oldRule)))
        val proposed = old.copy(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(proposedRule)))

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun addingUnlockDaysIsDelayedBecauseItWeakensRestriction() {
        val oldRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(6))
        val proposedRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(oldRule)))
        val proposed = old.copy(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(proposedRule)))

        assertFalse(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun removingUnlockDaysIsSameOrStricter() {
        val oldRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))
        val proposedRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(6))
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(oldRule)))
        val proposed = old.copy(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(proposedRule)))

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun keepingSameRolloverAndUnlockDaysIsSameOrStricter() {
        val oldRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))
        val proposedRule = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))
        val old = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(oldRule)))
        val proposed = old.copy(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(proposedRule)))

        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_RULES, old, proposed))
    }

    @Test
    fun appRuleRolloverSameOrStricterPublicSeam() {
        val off = rule.copy(rolloverEnabled = false, unlockDays = emptySet())
        val onSat = rule.copy(rolloverEnabled = true, unlockDays = setOf(6))
        val onWeekend = rule.copy(rolloverEnabled = true, unlockDays = setOf(0, 6))

        // Off to on -> delayed
        assertFalse(RestrictionComparator.appRuleRolloverSameOrStricter(off, onSat))
        // On to off -> stricter
        assertTrue(RestrictionComparator.appRuleRolloverSameOrStricter(onSat, off))
        // Adding unlock day -> delayed
        assertFalse(RestrictionComparator.appRuleRolloverSameOrStricter(onSat, onWeekend))
        // Removing unlock day -> stricter
        assertTrue(RestrictionComparator.appRuleRolloverSameOrStricter(onWeekend, onSat))
        // Both off -> same or stricter
        assertTrue(RestrictionComparator.appRuleRolloverSameOrStricter(off, off))
        // Same unlock days -> same or stricter
        assertTrue(RestrictionComparator.appRuleRolloverSameOrStricter(onWeekend, onWeekend))
    }
}

