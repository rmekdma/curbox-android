package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
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
}
