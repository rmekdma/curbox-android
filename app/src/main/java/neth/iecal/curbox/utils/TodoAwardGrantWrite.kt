package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.TodoAwardConnection
import neth.iecal.curbox.data.models.TodoAwardLedgerEntry
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.domain.apprules.isTodoAwardEligible

/** Captures the report and all persisted authority used by an approval. */
data class TodoAwardGrantBasis(
    val connection: TodoAwardConnection,
    val grant: GuardianExtraTimeGrantBasis,
    val reportedMinutes: Long,
    val alreadyGrantedMinutes: Long
) {
    val availableMinutes: Long get() = (reportedMinutes - alreadyGrantedMinutes).coerceAtLeast(0)

    companion object {
        fun capture(settings: Settings, grant: GuardianExtraTimeGrantBasis, reportedMinutes: Long): TodoAwardGrantBasis? {
            val link = settings.todoAwardConnection ?: return null
            val rule = settings.appRuleSnapshot.appRules.singleOrNull { it.id == link.ruleId }
            if (link.accountId.isBlank() || link.childName.isBlank() || link.revision.isBlank() ||
                link.connectedUseDayId.isBlank() || grant.ruleId != link.ruleId ||
                grant.useDayId < link.connectedUseDayId || reportedMinutes < 0 ||
                rule == null || !rule.isTodoAwardEligible()) return null
            val entries = settings.todoAwardLedger.filter {
                it.accountId == link.accountId && it.childName == link.childName && it.useDayId == grant.useDayId
            }
            if (entries.size > 1 || entries.singleOrNull()?.settled == true) return null
            val granted = entries.singleOrNull()?.grantedMinutes ?: 0
            if (granted < 0) return null
            return TodoAwardGrantBasis(link, grant, reportedMinutes, granted)
        }
    }
}

/** Applies both direct sources and the durable report ledger in one settings transaction. */
internal object TodoAwardGrantWrite {
    fun nextSettings(current: Settings, password: String, basis: TodoAwardGrantBasis,
        manualMinutes: Long, todoMinutes: Long, grantedAtMs: Long, transactionNowMs: Long): Settings {
        if (manualMinutes < 0 || todoMinutes < 0 || todoMinutes > basis.availableMinutes ||
            manualMinutes > Long.MAX_VALUE - todoMinutes) return current
        val total = manualMinutes + todoMinutes
        if (total <= 0 || total > Long.MAX_VALUE / 60_000L) return current
        val latest = TodoAwardGrantBasis.capture(current, basis.grant, basis.reportedMinutes)
        if (latest != basis || basis.alreadyGrantedMinutes > Long.MAX_VALUE - todoMinutes) return current
        val updated = GuardianExtraTimeGrantWrite.nextSettings(current, password, basis.grant, total, grantedAtMs, transactionNowMs)
        if (updated == current) return current
        val grants = updated.appRuleOverrideState.grants
        val tagged = grants.dropLast(1) + grants.last().copy(todoGrantedMillis = todoMinutes * 60_000L)
        val link = basis.connection
        val ledger = updated.todoAwardLedger.filterNot {
            it.accountId == link.accountId && it.childName == link.childName && it.useDayId == basis.grant.useDayId
        } + TodoAwardLedgerEntry(link.accountId, link.childName, basis.grant.useDayId, basis.alreadyGrantedMinutes + todoMinutes)
        return updated.copy(appRuleOverrideState = updated.appRuleOverrideState.copy(grants = tagged), todoAwardLedger = ledger)
    }
}
