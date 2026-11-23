package neth.iecal.curbox.plugins

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTimeSettlementWriteTest {
    @Test fun settlementCommitsCreditAndStateOnce() {
        val now = System.currentTimeMillis()
        val rule = AppRule(id = "rule", rolloverEnabled = true)
        val original = Settings(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule)),
            pluginTimes = mapOf("sample" to StoredPluginTime("before", "rule", "one")))
        val day = PluginTimeProjection.from(original, "sample", now).useDayId
        val current = original.copy(appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to RuleRolloverPool("rule", 10, day))))
        val expected = PluginTimeProjection.from(current, "sample", now)
        val committed = PluginTimeSettlementWrite.next(current, "sample", expected, 20, "after", "two", now)
        assertEquals(30L, committed.appRuleRolloverState.pools.getValue("rule").accumulatedMinutes)
        assertEquals("after", committed.pluginTimes.getValue("sample").data)
        assertEquals(committed, PluginTimeSettlementWrite.next(committed, "sample", expected, 20, "after", "three", now))
        val disabled = current.copy(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule.copy(isActive = false))))
        assertEquals(disabled, PluginTimeSettlementWrite.next(disabled, "sample", expected, 20, "after", "three", now))
        assertEquals(current, PluginTimeSettlementWrite.next(current, "sample", expected, 20, "before", "three", now))
    }

    @Test fun ruleWithoutRolloverAllowsContributionsButCannotReceiveSettlement() {
        val now = System.currentTimeMillis()
        val rule = AppRule(id = "rule", rolloverEnabled = false)
        val original = Settings(
            appRuleSnapshot = AppRuleSnapshot(appRules = listOf(rule)),
            pluginTimes = mapOf("sample" to StoredPluginTime("before", "rule", "one"))
        )
        val day = PluginTimeProjection.from(original, "sample", now).useDayId
        val current = original.copy(
            appRuleRolloverState = AppRuleRolloverState(mapOf("rule" to RuleRolloverPool("rule", 10, day)))
        )
        val expected = PluginTimeProjection.from(current, "sample", now)

        assertTrue(expected.rules.single().eligibleForContributions)
        assertEquals(current, PluginTimeSettlementWrite.next(current, "sample", expected, 20, "after", "two", now))
    }
}
