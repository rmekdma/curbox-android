package neth.iecal.curbox.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleRolloverSerializationTest {
    private val gson = Gson()

    @Test
    fun oldAppRuleJsonDefaultsRolloverFields() {
        val oldJson = """
            {
                "id": "test-rule",
                "name": "Test Rule",
                "isActive": true,
                "weekdays": [0, 1, 2, 3, 4, 5, 6],
                "startMinute": 540,
                "endMinute": 1020,
                "allowedMinutes": 30
            }
        """.trimIndent()

        val rule = gson.fromJson(oldJson, AppRule::class.java)

        assertEquals("test-rule", rule.id)
        assertFalse(rule.rolloverEnabled)
        assertTrue(rule.unlockDays.isEmpty())
    }

    @Test
    fun roundTripSerializesAndDeserializesRolloverFields() {
        val original = AppRule(
            id = "rollover-rule",
            name = "Weekend Unlock",
            rolloverEnabled = true,
            unlockDays = setOf(0, 6)
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, AppRule::class.java)

        assertEquals(original.id, restored.id)
        assertTrue(restored.rolloverEnabled)
        assertEquals(setOf(0, 6), restored.unlockDays)
    }

    @Test
    fun oldSettingsJsonDefaultsAppRuleRolloverState() {
        val oldSettingsJson = """
            {
                "isReelCounterOn": true,
                "isAppUsageTrackingEnabled": true
            }
        """.trimIndent()

        val restored = gson.fromJson(oldSettingsJson, Settings::class.java)

        assertEquals(AppRuleRolloverState(), restored.appRuleRolloverState)
        assertTrue(restored.appRuleRolloverState.pools.isEmpty())
    }

    @Test
    fun appRuleRolloverStateStoresAndRetrievesPools() {
        val pool = RuleRolloverPool(
            ruleId = "rule-1",
            accumulatedMinutes = 45L,
            lastSettledUseDayId = "2026-09-19"
        )
        val state = AppRuleRolloverState(pools = mapOf(pool.ruleId to pool))

        val json = gson.toJson(state)
        val restored = gson.fromJson(json, AppRuleRolloverState::class.java)

        assertEquals(45L, restored.poolFor("rule-1").accumulatedMinutes)
        assertEquals("2026-09-19", restored.poolFor("rule-1").lastSettledUseDayId)
        assertEquals(0L, restored.poolFor("missing-rule").accumulatedMinutes)
    }

    @Test
    fun settingsSyncBoundaryResetsAppRuleRolloverState() {
        val pool = RuleRolloverPool(
            ruleId = "rule-1",
            accumulatedMinutes = 45L,
            lastSettledUseDayId = "2026-09-19"
        )
        val settings = Settings(
            appRuleRolloverState = AppRuleRolloverState(pools = mapOf(pool.ruleId to pool))
        )

        val uploaded = SettingsSyncBoundary.forUpload(settings)

        assertEquals(AppRuleRolloverState(), uploaded.appRuleRolloverState)
    }
}
