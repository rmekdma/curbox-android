package neth.iecal.curbox.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
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
}
