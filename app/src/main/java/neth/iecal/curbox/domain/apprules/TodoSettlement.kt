package neth.iecal.curbox.domain.apprules

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.TodoAwardConnection
import neth.iecal.curbox.data.models.TodoAwardLedgerEntry
import neth.iecal.curbox.data.models.TodoPendingSettlement
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator

/** Commits a report once to the pool for its still-open weekend. */
internal object TodoSettlement {
    fun dates(settings: Settings, now: Long, zone: ZoneId): List<String> {
        val link = settings.todoAwardConnection ?: return emptyList()
        val day = LocalDate.parse(ConfigurableUseDayCalculator(zone, settings.useDayResetTime).idAt(now))
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val start = runCatching { LocalDate.parse(link.connectedUseDayId) }.getOrNull() ?: return emptyList()
        return generateSequence(monday) { it.plusDays(1) }.takeWhile { it < day }
            .filter { it >= start && it.dayOfWeek != DayOfWeek.SUNDAY }
            .map { it.toString() }.toList()
    }

    fun recordPending(current: Settings, next: AppRuleRolloverState): Settings {
        val link = current.todoAwardConnection ?: return current.copy(appRuleRolloverState = next)
        val rule = current.appRuleSnapshot.appRules.singleOrNull { it.id == link.ruleId }
        val previous = current.appRuleRolloverState.pools[link.ruleId]
        val updated = next.pools[link.ruleId]
        val start = runCatching { LocalDate.parse(link.connectedUseDayId) }.getOrNull()
        val end = runCatching { LocalDate.parse(updated?.lastSettledUseDayId) }.getOrNull()
        val old = runCatching { LocalDate.parse(previous?.lastSettledUseDayId) }.getOrNull() ?: start
        if (rule == null || !rule.isTodoAwardEligible() || start == null || end == null || old == null || old >= end) {
            return current.copy(appRuleRolloverState = next)
        }
        val monday = end.minusDays((end.dayOfWeek.value - 1).toLong())
        val first = maxOf(start, old, monday)
        val added = generateSequence(first) { it.plusDays(1) }.takeWhile { it < end }
            .filter { it.dayOfWeek != DayOfWeek.SUNDAY }
            .map { TodoPendingSettlement(link, it.toString()) }
            .filter { pending -> current.todoAwardLedger.none { it.accountId == link.accountId && it.childName == link.childName && it.useDayId == pending.useDayId && it.settled } }
            .toList()
        return current.copy(appRuleRolloverState = next, todoPendingSettlements = (current.todoPendingSettlements + added).distinct())
    }

    fun prunePending(current: Settings, now: Long, zone: ZoneId): Settings {
        val today = LocalDate.parse(ConfigurableUseDayCalculator(zone, current.useDayResetTime).idAt(now))
        val pending = current.todoPendingSettlements.filter { item ->
            val day = runCatching { LocalDate.parse(item.useDayId) }.getOrNull()
            val start = runCatching { LocalDate.parse(item.connection.connectedUseDayId) }.getOrNull()
            val cutoff = day?.plusDays((8 - day.dayOfWeek.value).toLong())
            item.connection == current.todoAwardConnection && day != null && start != null && day >= start &&
                day.dayOfWeek != DayOfWeek.SUNDAY && cutoff != null && today < cutoff &&
                current.todoAwardLedger.none { entry -> entry.accountId == item.connection.accountId && entry.childName == item.connection.childName && entry.useDayId == item.useDayId && entry.settled }
        }
        return if (pending == current.todoPendingSettlements) current else current.copy(todoPendingSettlements = pending)
    }

    fun complete(current: Settings, link: TodoAwardConnection, day: String, reported: Long, now: Long, zone: ZoneId, basedOn: Settings = current): Settings {
        if (current.appRuleSnapshot != basedOn.appRuleSnapshot || current.useDayResetTime != basedOn.useDayResetTime ||
            current.useDayGenerationStartedAtMs != basedOn.useDayGenerationStartedAtMs) return current
        if (current.todoAwardConnection != link || reported < 0 || day !in dates(current, now, zone)) return current
        val rule = current.appRuleSnapshot.appRules.singleOrNull { it.id == link.ruleId } ?: return current
        if (!rule.isTodoAwardEligible()) return current
        val pool = current.appRuleRolloverState.pools[rule.id] ?: return current
        val currentDay = ConfigurableUseDayCalculator(zone, current.useDayResetTime).idAt(now)
        if (pool.lastSettledUseDayId != currentDay) return current
        val entries = current.todoAwardLedger.filter { it.accountId == link.accountId && it.childName == link.childName && it.useDayId == day }
        if (entries.size > 1) return current
        val existing = entries.singleOrNull() ?: TodoAwardLedgerEntry(link.accountId, link.childName, day)
        if (existing.settled || existing.grantedMinutes < 0) return current
        val unpaid = (reported - existing.grantedMinutes).coerceAtLeast(0)
        val updatedPool = pool.copy(accumulatedMinutes = AppRuleRolloverPolicy.safeAdd(pool.accumulatedMinutes.coerceAtLeast(0), unpaid))
        return current.copy(appRuleRolloverState = current.appRuleRolloverState.withPool(updatedPool),
            todoAwardLedger = current.todoAwardLedger - entries.toSet() + existing.copy(settled = true),
            todoPendingSettlements = current.todoPendingSettlements.filterNot { it.connection == link && it.useDayId == day })
    }
}
