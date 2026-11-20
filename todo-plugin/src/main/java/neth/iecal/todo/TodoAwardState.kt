package neth.iecal.todo

import java.time.DayOfWeek
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
    val revision: String = ""
)

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
    fun recordPending(state: TodoAwardState, previousDay: String?, nextDay: String, eligible: Boolean): TodoAwardState {
        val link = state.connection ?: return state
        val start = parse(link.connectedUseDayId) ?: return state
        val end = parse(nextDay) ?: return state
        val old = previousDay?.let(::parse) ?: start
        if (!eligible || old >= end) return state
        val monday = end.minusDays((end.dayOfWeek.value - 1).toLong())
        val added = generateSequence(maxOf(start, old, monday)) { it.plusDays(1) }.takeWhile { it < end }
            .filter { it.dayOfWeek != DayOfWeek.SUNDAY }
            .map { TodoPendingSettlement(link, it.toString()) }
            .filter { item -> state.entries(link, item.useDayId).none { it.settled } }.toList()
        return state.copy(pending = (state.pending + added).distinct())
    }

    fun dates(state: TodoAwardState, today: LocalDate): List<String> {
        val start = state.connection?.connectedUseDayId?.let(::parse) ?: return emptyList()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return generateSequence(monday) { it.plusDays(1) }.takeWhile { it < today }
            .filter { it >= start && it.dayOfWeek != DayOfWeek.SUNDAY }.map { it.toString() }.toList()
    }

    fun prunePending(state: TodoAwardState, today: LocalDate): TodoAwardState {
        val pending = state.pending.filter { item ->
            val day = parse(item.useDayId)
            val start = parse(item.connection.connectedUseDayId)
            item.connection == state.connection && day != null && start != null && day >= start &&
                day.dayOfWeek != DayOfWeek.SUNDAY && today < day.plusDays((8 - day.dayOfWeek.value).toLong()) &&
                state.entries(item.connection, item.useDayId).none { it.settled }
        }
        return if (pending == state.pending) state else state.copy(pending = pending)
    }

    /** Returns the next opaque state and unpaid minutes for one atomic host settlement. */
    fun complete(state: TodoAwardState, link: TodoAwardConnection, day: String, reported: Long, today: LocalDate): Pair<TodoAwardState, Long>? {
        if (state.connection != link || reported < 0 || day !in dates(state, today)) return null
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
