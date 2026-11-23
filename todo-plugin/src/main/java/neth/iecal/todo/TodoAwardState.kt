package neth.iecal.todo

import java.time.LocalDate

/** Persisted Todo award data. The host stores this as plugin-owned opaque state. */
data class TodoAwardState(
    val connection: TodoAwardConnection? = null,
    val ledger: List<TodoAwardLedgerEntry> = emptyList(),
    val pending: List<TodoPendingSettlement> = emptyList()
)

data class TodoAwardConnection(
    val accountId: String = "",
    val childName: String = "",
    val ruleId: String = "",
    val connectedUseDayId: String = "",
    val revision: String = "",
    val autoSettlementEnabled: Boolean = true,
    val settlementStartUseDayId: String = ""
)

/** Changes automatic settlement without altering the grant ledger. */
fun TodoAwardState.withAutoSettlement(enabled: Boolean, useDayId: String): TodoAwardState {
    val link = connection ?: return this
    if (link.autoSettlementEnabled == enabled) return if (!enabled && pending.isNotEmpty()) copy(pending = emptyList()) else this
    return copy(connection = link.copy(autoSettlementEnabled = enabled,
        settlementStartUseDayId = if (enabled) useDayId else link.settlementStartUseDayId), pending = emptyList())
}

data class TodoAwardLedgerEntry(
    val accountId: String = "",
    val childName: String = "",
    val useDayId: String = "",
    val grantedMinutes: Long = 0,
    val settled: Boolean = false
)

data class TodoPendingSettlement(val connection: TodoAwardConnection = TodoAwardConnection(), val useDayId: String = "")

/** A report and the ledger values on which an award was based. */
data class TodoAwardBasis(
    val connection: TodoAwardConnection,
    val useDayId: String,
    val reportedMinutes: Long,
    val alreadyGrantedMinutes: Long
) {
    val availableMinutes get() = (reportedMinutes - alreadyGrantedMinutes).coerceAtLeast(0)

    companion object {
        fun capture(state: TodoAwardState, ruleId: String, useDayId: String, reportedMinutes: Long): TodoAwardBasis? {
            val link = state.connection ?: return null
            if (link.accountId.isBlank() || link.childName.isBlank() || link.revision.isBlank() ||
                link.connectedUseDayId.isBlank() || ruleId != link.ruleId || useDayId < link.connectedUseDayId || reportedMinutes < 0) return null
            val entries = state.entries(link, useDayId)
            if (entries.size > 1 || entries.singleOrNull()?.settled == true) return null
            val granted = entries.singleOrNull()?.grantedMinutes ?: 0
            if (granted < 0) return null
            return TodoAwardBasis(link, useDayId, reportedMinutes, granted)
        }
    }
}

/** Computes plugin state for a host transaction; the host must atomically validate and commit it with the grant. */
object TodoAwardGrantWrite {
    fun apply(state: TodoAwardState, basis: TodoAwardBasis, minutes: Long): TodoAwardState? {
        if (minutes < 0 || minutes > basis.availableMinutes || basis.alreadyGrantedMinutes > Long.MAX_VALUE - minutes ||
            TodoAwardBasis.capture(state, basis.connection.ruleId, basis.useDayId, basis.reportedMinutes) != basis) return null
        val link = basis.connection
        val entry = TodoAwardLedgerEntry(link.accountId, link.childName, basis.useDayId, basis.alreadyGrantedMinutes + minutes)
        return state.copy(ledger = state.ledger - state.entries(link, basis.useDayId).toSet() + entry)
    }
}

private fun TodoAwardState.entries(link: TodoAwardConnection, day: String) = ledger.filter {
    it.accountId == link.accountId && it.childName == link.childName && it.useDayId == day
}

/** Computes pending and completed reports; host rollover updates and state writes must be atomic. */
object TodoSettlement {
    fun recordPending(state: TodoAwardState, previousDay: String?, nextDay: String, eligible: Boolean,
                      unlockDays: Set<Int> = setOf(0, 6)): TodoAwardState {
        val link = state.connection ?: return state
        val start = settlementStart(link) ?: return state
        val end = parse(nextDay) ?: return state
        val old = previousDay?.let(::parse) ?: start
        if (!eligible || !link.autoSettlementEnabled || old >= end) return state
        val cycleStart = cycleStart(end, unlockDays) ?: start
        val retained = prunePending(state, end, unlockDays).pending
        val added = generateSequence(maxOf(start, old, cycleStart)) { it.plusDays(1) }.takeWhile { it < end }
            .map { TodoPendingSettlement(link, it.toString()) }
            .filter { item -> state.entries(link, item.useDayId).none { it.settled } }.toList()
        return state.copy(pending = (retained + added).distinct())
    }

    private fun isEligibleDay(link: TodoAwardConnection, day: String, today: LocalDate, unlockDays: Set<Int>): Boolean {
        if (!link.autoSettlementEnabled) return false
        val date = parse(day) ?: return false
        val start = settlementStart(link) ?: return false
        val cycleStart = cycleStart(today, unlockDays)
        return date >= start && date < today && (cycleStart == null || date >= cycleStart)
    }

    fun prunePending(state: TodoAwardState, today: LocalDate, unlockDays: Set<Int> = setOf(0, 6)): TodoAwardState {
        val pending = state.pending.filter { item ->
            item.connection == state.connection && isEligibleDay(item.connection, item.useDayId, today, unlockDays) &&
                state.entries(item.connection, item.useDayId).none { it.settled }
        }
        return if (pending == state.pending) state else state.copy(pending = pending)
    }

    // A weekly schedule with a reset reaches its most recent boundary within seven days.
    private fun cycleStart(today: LocalDate, unlockDays: Set<Int>): LocalDate? {
        for (offset in 1L..7L) {
            val day = today.minusDays(offset)
            if (day.dayOfWeek.value % 7 in unlockDays && day.plusDays(1).dayOfWeek.value % 7 !in unlockDays) {
                return day.plusDays(1)
            }
        }
        return null
    }

    private fun settlementStart(link: TodoAwardConnection): LocalDate? =
        parse(link.settlementStartUseDayId.ifBlank { link.connectedUseDayId })

    /** Returns the next opaque state and unpaid minutes for one atomic host settlement. */
    fun complete(state: TodoAwardState, link: TodoAwardConnection, day: String, reported: Long, today: LocalDate, unlockDays: Set<Int> = setOf(0, 6)): Pair<TodoAwardState, Long>? {
        if (state.connection != link || reported < 0 || !isEligibleDay(link, day, today, unlockDays)) return null
        val entries = state.entries(link, day)
        if (entries.size > 1) return null
        val existing = entries.singleOrNull() ?: TodoAwardLedgerEntry(link.accountId, link.childName, day)
        if (existing.settled || existing.grantedMinutes < 0) return null
        val unpaid = (reported - existing.grantedMinutes).coerceAtLeast(0)
        return state.copy(ledger = state.ledger - entries.toSet() + existing.copy(settled = true),
            pending = state.pending.filterNot { it.connection == link && it.useDayId == day }) to unpaid
    }

    private fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}
