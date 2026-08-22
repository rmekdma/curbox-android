package neth.iecal.curbox.domain.apprules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot

class AppRuleSnapshotCoordinatorTest {
    @Test
    fun serviceObserverContinuesReceivingValidSnapshotUpdates() = runBlocking {
        val group = AppRuleAppGroup("group", "Reader", listOf("com.example.reader"))
        val first = AppRuleSnapshot(listOf(group), emptyList())
        val secondGroup = group.copy(name = "Updated reader")
        val updates = MutableStateFlow(first)
        val coordinator = AppRuleSnapshotCoordinator()
        val observer = launch { updates.collect { coordinator.accept(it) } }

        updates.value = AppRuleSnapshot(listOf(secondGroup), listOf(
            AppRule(
                id = "rule",
                name = "Rule",
                appGroupId = secondGroup.id,
                allowedMinutes = 0
            )
        ))
        kotlinx.coroutines.yield()

        assertTrue(coordinator.snapshot().appRules.any { it.id == "rule" })
        assertEquals("Updated reader", coordinator.snapshot().appGroups.single().name)
        observer.cancel()
    }
}
