package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TodoAwardStateTest {
    private val link = TodoAwardConnection("account", "Child", "rule", "2026-11-02", "revision")
    private val state = TodoAwardState(connection = link)

    @Test fun grantTracksAmountsAndRejectsStaleBasis() {
        val basis = TodoAwardBasis.capture(state, "rule", "2026-11-06", 60)!!
        val updated = TodoAwardGrantWrite.apply(state, basis, 40)!!
        assertEquals(20L, TodoAwardBasis.capture(updated, "rule", "2026-11-06", 60)!!.availableMinutes)
        assertNull(TodoAwardGrantWrite.apply(updated, basis, 40))
    }

    @Test fun settlementPaysOnlyUnpaidAndOnlyOnce() {
        val existing = state.copy(ledger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-06", 40)))
        val (settled, unpaid) = TodoSettlement.complete(existing, link, "2026-11-06", 60, LocalDate.parse("2026-11-07"))!!
        assertEquals(20L, unpaid)
        assertNull(TodoSettlement.complete(settled, link, "2026-11-06", 60, LocalDate.parse("2026-11-07")))
        assertNull(TodoSettlement.complete(existing, link, "2026-11-06", 60, LocalDate.parse("2026-11-09")))
    }

    @Test fun rolloverPendingDoesNotRepeatSettledReports() {
        val existing = state.copy(ledger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-05", 0, true)))
        val pending = TodoSettlement.recordPending(existing, "2026-11-05", "2026-11-07", true)
        assertEquals(listOf("2026-11-06"), pending.pending.map { it.useDayId })
    }
    @Test fun reconnectKeepsLedgerAndSettledReportsCannotBeGranted() {
        val prior = state.copy(ledger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-06", 40)))
        val relinked = prior.copy(connection = link.copy(ruleId = "new-rule", revision = "new"))
        assertEquals(20L, TodoAwardBasis.capture(relinked, "new-rule", "2026-11-06", 60)!!.availableMinutes)
        assertNull(TodoAwardBasis.capture(relinked.copy(ledger = listOf(prior.ledger.single().copy(settled = true))), "new-rule", "2026-11-06", 60))
    }

    @Test fun rejectsDuplicateLedgerAndOutdatedConnection() {
        val entry = TodoAwardLedgerEntry("account", "Child", "2026-11-06", 10)
        val duplicate = state.copy(ledger = listOf(entry, entry))
        assertNull(TodoAwardBasis.capture(duplicate, "rule", "2026-11-06", 60))
        assertNull(TodoSettlement.complete(duplicate, link, "2026-11-06", 60, LocalDate.parse("2026-11-07")))
        assertNull(TodoSettlement.complete(state.copy(connection = link.copy(revision = "new")), link, "2026-11-06", 60, LocalDate.parse("2026-11-07")))
    }

    @Test fun pendingExpiresOnMondayAndDisabledRuleDoesNotRecord() {
        val pending = state.copy(pending = listOf(TodoPendingSettlement(link, "2026-11-06")))
        assertTrue(TodoSettlement.prunePending(pending, LocalDate.parse("2026-11-09")).pending.isEmpty())
        assertEquals(state, TodoSettlement.recordPending(state, "2026-11-05", "2026-11-07", false))
    }
    @Test fun legacyMigrationPreservesLedgerAndRevocationInvalidatesPending() {
        val original = state.copy(ledger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-06", 40)),
            pending = listOf(TodoPendingSettlement(link, "2026-11-06")))
        val gson = com.google.gson.Gson()
        val migrated = TodoAwardLegacyMigration.migrate(mapOf(
            "todoAwardConnection" to gson.toJson(original.connection),
            "todoAwardLedger" to gson.toJson(original.ledger),
            "todoPendingSettlements" to gson.toJson(original.pending)
        ))!!
        assertEquals("rule", migrated.second)
        assertEquals(original, gson.fromJson(migrated.first, TodoAwardState::class.java))
        val invalidated = gson.fromJson(TodoAwardLegacyMigration.onLinkedTimeRevoked(migrated.first, "2026-11-07"), TodoAwardState::class.java)
        assertEquals(original.ledger, invalidated.ledger)
        assertNotEquals(link.revision, invalidated.connection!!.revision)
        assertTrue(TodoSettlement.prunePending(invalidated, LocalDate.parse("2026-11-07")).pending.isEmpty())
    }
    @Test fun arbitraryScheduleExpiresPreviousCycleAndIncludesConsecutiveUnlockDays() {
        val days = setOf(2, 3)
        val pending = TodoSettlement.recordPending(state, "2026-11-02", "2026-11-05", true, days)
        assertEquals(emptyList<String>(), pending.pending.map { it.useDayId })
        val next = TodoSettlement.recordPending(state, "2026-11-02", "2026-11-04", true, days)
        assertEquals(listOf("2026-11-02", "2026-11-03"), next.pending.map { it.useDayId })
        assertTrue(TodoSettlement.prunePending(next, LocalDate.parse("2026-11-05"), days).pending.isEmpty())
    }

    @Test fun allUnlockDaysRetainPendingAcrossWeeks() {
        val old = state.copy(connection = link.copy(connectedUseDayId = "2026-10-01"))
        val days = (0..6).toSet()
        val result = TodoSettlement.recordPending(old, "2026-10-01", "2026-11-10", true, days)
        assertEquals(40, result.pending.size)
        assertEquals("2026-10-01", result.pending.first().useDayId)
        val later = LocalDate.parse("2026-11-20")
        val retained = TodoSettlement.prunePending(result, later, days)
        assertEquals(result.pending, retained.pending)
        val (settled, unpaid) = TodoSettlement.complete(retained, old.connection!!, "2026-10-01", 60, later, days)!!
        assertEquals(60L, unpaid)
        assertNull(TodoSettlement.complete(settled, old.connection!!, "2026-10-01", 60, later, days))
    }

    @Test fun togglingClearsPendingAndRestartsWithoutLosingGrantLedger() {
        val original = state.copy(ledger = listOf(TodoAwardLedgerEntry("account", "Child", "2026-11-06", 40)),
            pending = listOf(TodoPendingSettlement(link, "2026-11-06")))
        val disabled = original.withAutoSettlement(false, "2026-11-07")
        assertTrue(disabled.pending.isEmpty())
        assertEquals(original.ledger, disabled.ledger)
        val enabled = disabled.withAutoSettlement(true, "2026-11-08")
        assertEquals("2026-11-08", enabled.connection!!.settlementStartUseDayId)
        assertEquals(20L, TodoAwardBasis.capture(enabled, "rule", "2026-11-06", 60)!!.availableMinutes)
        assertTrue(TodoSettlement.recordPending(enabled, "2026-11-05", "2026-11-08", true).pending.isEmpty())
    }

    @Test fun oldJsonDefaultsToAutomaticSettlement() {
        val json = """{"accountId":"account","childName":"Child","ruleId":"rule","connectedUseDayId":"2026-11-02","revision":"revision"}"""
        assertTrue(com.google.gson.Gson().fromJson(json, TodoAwardConnection::class.java).autoSettlementEnabled)
    }

    @Test fun invalidLegacyLedgerIsRejected() {
        try {
            TodoAwardLegacyMigration.migrate(mapOf("todoAwardLedger" to "true"))
            fail("Invalid legacy ledger must not be accepted")
        } catch (_: com.google.gson.JsonParseException) {
        }
    }

}
