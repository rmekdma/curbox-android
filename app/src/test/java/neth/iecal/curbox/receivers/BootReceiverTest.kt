package neth.iecal.curbox.receivers

import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    @Test
    fun parseAppRulesPayloadValidatesAndReturnsNormalizedSnapshot() {
        val group = AppRuleAppGroup(id = "grp1", name = "Group 1", selectedPackages = listOf("com.test.app"))
        val rule = AppRule(
            id = "rule1",
            name = "Rule 1",
            appGroupId = "grp1",
            allowedMinutes = 0
        )
        val validSnapshot = AppRuleSnapshot(appGroups = listOf(group), appRules = listOf(rule))
        val json = Gson().toJson(validSnapshot)

        val parsed = BootReceiver.parseTestAppRules(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.isValid)
        assertEquals(1, parsed.appRules.size)
        assertEquals("rule1", parsed.appRules.first().id)
    }

    @Test
    fun parseAppRulesPayloadRejectsInvalidOrEmptyJson() {
        assertNull(BootReceiver.parseTestAppRules(null))
        assertNull(BootReceiver.parseTestAppRules(""))
        assertNull(BootReceiver.parseTestAppRules("{ invalid json }"))

        // Rule references nonexistent group -> isValid is false
        val invalidRule = AppRule(
            id = "rule1",
            name = "Rule 1",
            appGroupId = "nonexistent_group",
            allowedMinutes = 0
        )
        val invalidSnapshot = AppRuleSnapshot(appGroups = emptyList(), appRules = listOf(invalidRule))
        val json = Gson().toJson(invalidSnapshot)
        assertNull(BootReceiver.parseTestAppRules(json))
    }
}
