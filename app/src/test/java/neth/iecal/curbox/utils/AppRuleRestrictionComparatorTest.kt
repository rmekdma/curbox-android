package neth.iecal.curbox.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertEquals
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
    fun disablingGuardianExtraTimeIsImmediateButEnablingItIsDelayed() {
        val allowed = rule.copy(guardianExtraTimeAllowed = true)
        val disabled = allowed.copy(guardianExtraTimeAllowed = false)
        val oldAllowed = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(allowed)))
        val proposedDisabled = oldAllowed.copy(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(disabled))
        )
        val oldDisabled = Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(disabled)))
        val proposedEnabled = oldDisabled.copy(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(allowed))
        )

        assertTrue(
            RestrictionComparator.isSameOrStricter(
                GatedSettingsField.APP_RULES,
                oldAllowed,
                proposedDisabled
            )
        )
        assertFalse(
            RestrictionComparator.isSameOrStricter(
                GatedSettingsField.APP_RULES,
                oldDisabled,
                proposedEnabled
            )
        )
    }

    @Test
    fun enablingGuardianExtraTimeIsDelayedEvenWhenTheRuleIsInactive() {
        val disabled = rule.copy(isActive = false, guardianExtraTimeAllowed = false)
        val enabled = disabled.copy(guardianExtraTimeAllowed = true)

        assertFalse(
            RestrictionComparator.isSameOrStricter(
                GatedSettingsField.APP_RULES,
                Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(disabled))),
                Settings(appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(enabled)))
            )
        )
    }

    @Test
    fun missingGuardianExtraTimeFieldDefaultsToAllowed() {
        val json = JsonParser.parseString(Gson().toJson(rule)).asJsonObject.apply {
            remove("guardianExtraTimeAllowed")
        }

        val restored = Gson().fromJson(json, AppRule::class.java)

        assertTrue(restored.guardianExtraTimeAllowed)
    }

    @Test
    fun settingsSerializerRestoresMissingGuardianExtraTimeAsAllowed() = runBlocking {
        val settings = Settings(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(rule))
        )
        val root = JsonParser.parseString(Gson().toJson(settings)).asJsonObject
        root.getAsJsonObject("appRuleSnapshot")
            .getAsJsonArray("appRules")
            .get(0).asJsonObject
            .remove("guardianExtraTimeAllowed")

        val restored = GsonSerializer(Gson(), Settings::class.java, Settings()).readFrom(
            ByteArrayInputStream(root.toString().toByteArray())
        )

        assertTrue(restored.appRuleSnapshot.appRules.single().guardianExtraTimeAllowed)
    }

    @Test
    fun disabledGuardianExtraTimeAndClearedPoolSurviveSettingsReloadAndReenable() = runBlocking {
        val disabledRule = rule.copy(guardianExtraTimeAllowed = false)
        val disabledSettings = Settings(
            appRuleSnapshot = AppRuleSnapshot(listOf(group), listOf(disabledRule)),
            appRuleRolloverState = AppRuleRolloverState(
                pools = mapOf(rule.id to RuleRolloverPool(rule.id, 0L, "2026-08-17"))
            )
        )
        val serializer = GsonSerializer(Gson(), Settings::class.java, Settings())
        val output = ByteArrayOutputStream()
        serializer.writeTo(disabledSettings, output)

        val restored = serializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertFalse(restored.appRuleSnapshot.appRules.single().guardianExtraTimeAllowed)
        assertTrue(restored.appRuleOverrideState.grants.isEmpty())
        assertEquals(0L, restored.appRuleRolloverState.poolFor(rule.id).accumulatedMinutes)

        val reenabled = restored.copy(
            appRuleSnapshot = restored.appRuleSnapshot.copy(
                appRules = restored.appRuleSnapshot.appRules.map {
                    it.copy(guardianExtraTimeAllowed = true)
                }
            )
        )
        assertTrue(reenabled.appRuleSnapshot.appRules.single().guardianExtraTimeAllowed)
        assertTrue(reenabled.appRuleOverrideState.grants.isEmpty())
        assertEquals(0L, reenabled.appRuleRolloverState.poolFor(rule.id).accumulatedMinutes)
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

