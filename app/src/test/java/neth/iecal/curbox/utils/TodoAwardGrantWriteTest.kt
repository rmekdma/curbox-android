package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.*
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TodoAwardGrantWriteTest {
    @Test fun `partial approval records its source and leaves the rest available`() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
        val day = ConfigurableUseDayCalculator().idAt(now)
        val link = TodoAwardConnection("account", "Child", "rule", day, "link1")
        val current = Settings(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(
            AppRule(id = "rule", rolloverEnabled = true, unlockDays = setOf(0, 6))
        )), todoAwardConnection = link)
        val basis = GuardianExtraTimeGrantBasis.capture(current, "rule", now)!!
        val award = TodoAwardGrantBasis(link, basis, reportedMinutes = 60, alreadyGrantedMinutes = 0)
        val committed = TodoAwardGrantWrite.nextSettings(current, "", award, 20, 40, now, now)
        assertEquals(60 * 60_000L, committed.appRuleOverrideState.grants.single().grantedMillis)
        assertEquals(40 * 60_000L, committed.appRuleOverrideState.grants.single().todoGrantedMillis)
        assertEquals(40L, committed.todoAwardLedger.single().grantedMinutes)
        assertEquals(20L, TodoAwardGrantBasis.capture(committed, basis.copy(currentTotalMillis = 60 * 60_000L), 60)?.availableMinutes)
        val relinked = current.copy(todoAwardConnection = link.copy(revision = "link2"))
        assertEquals(relinked, TodoAwardGrantWrite.nextSettings(relinked, "", award, 20, 40, now, now))
        assertEquals(committed, TodoAwardGrantWrite.nextSettings(committed, "", award, 20, 40, now + 2, now + 2))
    }
    @Test fun `relink preserves the report ledger and never reopens settled reports`() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
        val day = ConfigurableUseDayCalculator().idAt(now)
        val link = TodoAwardConnection("account", "Child", "new-rule", day, "new-link")
        val current = Settings(appRuleSnapshot = AppRuleSnapshot(appRules = listOf(
            AppRule(id = "new-rule", rolloverEnabled = true, unlockDays = setOf(0, 6))
        )), todoAwardConnection = link,
            todoAwardLedger = listOf(TodoAwardLedgerEntry("account", "Child", day, 40)))
        val grant = GuardianExtraTimeGrantBasis.capture(current, "new-rule", now)!!
        assertEquals(20L, TodoAwardGrantBasis.capture(current, grant, 60)?.availableMinutes)
        val settled = current.copy(todoAwardLedger = listOf(TodoAwardLedgerEntry("account", "Child", day, 40, true)))
        assertNull(TodoAwardGrantBasis.capture(settled, grant, 60))
    }
}
