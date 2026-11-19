package neth.iecal.curbox.domain.apprules

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.TodoAwardConnection
import neth.iecal.curbox.data.models.TodoAwardLedgerEntry
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

    fun complete(current: Settings, link: TodoAwardConnection, day: String, reported: Long, now: Long, zone: ZoneId, basedOn: Settings = current): Settings {
        if (current.appRuleSnapshot != basedOn.appRuleSnapshot || current.useDayResetTime != basedOn.useDayResetTime ||
            current.useDayGenerationStartedAtMs != basedOn.useDayGenerationStartedAtMs) return current
        if (current.todoAwardConnection != link || reported < 0 || day !in dates(current, now, zone)) return current
        val rule = current.appRuleSnapshot.appRules.singleOrNull { it.id == link.ruleId } ?: return current
        if (!rule.isActive || !rule.guardianExtraTimeAllowed || !rule.rolloverEnabled || rule.unlockDays != setOf(0, 6)) return current
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
            todoAwardLedger = current.todoAwardLedger - entries.toSet() + existing.copy(settled = true))
    }
}
