package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleSnapshotIntegrityTest {
    @Test
    fun referencedTargetGroupCannotBeDeletedWithoutAnExplicitChoice() {
        val group = AppRuleAppGroup("group", "Study", listOf("com.reader"))
        val rule = AppRule(
            id = "rule",
            name = "Study rule",
            weekdays = setOf(1),
            appGroupId = "",
            scope = AppRuleScope(includedGroupIds = setOf(group.id))
        )
        val snapshot = AppRuleSnapshot(listOf(group), listOf(rule))

        assertFalse(snapshot.canDeleteTargetGroup(group.id))
        assertNull(snapshot.deleteTargetGroup(group.id))
        assertTrue(
            snapshot.deleteTargetGroup(group.id, removeReferences = true)
                ?.isValid == true
        )
    }

    @Test
    fun mixedLegacyAndCompositeTargetsAreBothReferencesWithoutDoubleCountingTheRule() {
        val legacyGroup = AppRuleAppGroup("legacy", "Legacy", listOf("com.legacy"))
        val compositeGroup = AppRuleAppGroup("composite", "Composite", listOf("com.composite"))
        val rule = AppRule(
            id = "mixed",
            name = "Mixed target",
            appGroupId = legacyGroup.id,
            scope = AppRuleScope(includedGroupIds = setOf(compositeGroup.id))
        )
        val snapshot = AppRuleSnapshot(listOf(legacyGroup, compositeGroup), listOf(rule))

        assertEquals(listOf(rule.id), snapshot.targetRuleIdsReferencing(legacyGroup.id))
        assertEquals(listOf(rule.id), snapshot.targetRuleIdsReferencing(compositeGroup.id))

        val updated = snapshot.deleteTargetGroup(legacyGroup.id, removeReferences = true)
        assertEquals(compositeGroup.id, updated?.appRules?.single()?.scope?.includedGroupIds?.single())
        assertEquals("", updated?.appRules?.single()?.appGroupId)
    }

    @Test
    fun normalizationMigratesTicketOneTargetAndTimeFieldsToCanonicalSeams() {
        val group = AppRuleAppGroup("legacy", "Legacy", listOf("com.legacy"))
        val legacyRule = AppRule(
            id = "legacy-rule",
            name = "Legacy rule",
            appGroupId = " ${group.id} ",
            startMinute = 22 * 60,
            endMinute = 6 * 60
        )

        val normalized = AppRuleSnapshot(listOf(group), listOf(legacyRule)).normalized()
        val rule = normalized.appRules.single()

        assertEquals(setOf(group.id), rule.scope.includedGroupIds)
        assertEquals(listOf(AppRuleTimeRange(22 * 60, 6 * 60)), rule.timeRanges)
    }
}
