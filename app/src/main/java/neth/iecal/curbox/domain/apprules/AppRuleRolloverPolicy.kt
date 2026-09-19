package neth.iecal.curbox.domain.apprules

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.RuleRolloverPool

enum class AppRuleDayRole {
    ACCRUAL,
    UNLOCK
}

data class RolloverSettlementResult(
    val previousPoolMinutes: Long,
    val accumulatedMinutes: Long,
    val addedMinutes: Long,
    val expiredMinutes: Long,
    val endingDayRole: AppRuleDayRole,
    val nextDayRole: AppRuleDayRole
)

data class RolloverReconciliationResult(
    val pool: RuleRolloverPool,
    val transitions: List<RolloverSettlementResult>
)

/**
 * Pure domain engine for guardian extra time rollover settlement and catch-up.
 */
object AppRuleRolloverPolicy {

    private val ID_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Prevents settlement when restore time or generation updates midday without crossing
     * a calendar date boundary, or when the system clock jumps backwards.
     */
    fun shouldSettle(lastSettledUseDayId: String, currentUseDayId: String): Boolean {
        if (lastSettledUseDayId.isBlank() || currentUseDayId.isBlank()) return false
        val lastDate = runCatching { LocalDate.parse(lastSettledUseDayId, ID_FORMAT) }.getOrNull() ?: return false
        val currentDate = runCatching { LocalDate.parse(currentUseDayId, ID_FORMAT) }.getOrNull() ?: return false
        return lastDate < currentDate
    }

    /**
     * Settles the day transition between [endingDayRole] and [nextDayRole].
     *
     * - ACCRUAL ➡️ ACCRUAL / UNLOCK: Unused guardian extra time is added to the accumulated pool.
     * - UNLOCK ➡️ UNLOCK: Unused daily extra time and unused approved accumulated time combine into the next day pool.
     * - UNLOCK ➡️ ACCRUAL: Unlock block ends; accumulated pool resets to 0.
     */
    fun settleDayTransition(
        currentPoolMinutes: Long,
        endingDayRole: AppRuleDayRole,
        nextDayRole: AppRuleDayRole,
        unusedGrantMinutes: Long
    ): RolloverSettlementResult {
        val previous = currentPoolMinutes.coerceAtLeast(0L)
        val unused = unusedGrantMinutes.coerceAtLeast(0L)

        return if (endingDayRole == AppRuleDayRole.UNLOCK && nextDayRole == AppRuleDayRole.ACCRUAL) {
            val expired = safeAdd(previous, unused)
            RolloverSettlementResult(
                previousPoolMinutes = previous,
                accumulatedMinutes = 0L,
                addedMinutes = 0L,
                expiredMinutes = expired,
                endingDayRole = endingDayRole,
                nextDayRole = nextDayRole
            )
        } else {
            val nextPool = safeAdd(previous, unused)
            RolloverSettlementResult(
                previousPoolMinutes = previous,
                accumulatedMinutes = nextPool,
                addedMinutes = unused,
                expiredMinutes = 0L,
                endingDayRole = endingDayRole,
                nextDayRole = nextDayRole
            )
        }
    }

    fun settleDayTransition(
        pool: RuleRolloverPool,
        endingDayRole: AppRuleDayRole,
        nextDayRole: AppRuleDayRole,
        unusedGrantMinutes: Long,
        nextUseDayId: String
    ): Pair<RuleRolloverPool, RolloverSettlementResult> {
        val result = settleDayTransition(
            currentPoolMinutes = pool.accumulatedMinutes,
            endingDayRole = endingDayRole,
            nextDayRole = nextDayRole,
            unusedGrantMinutes = unusedGrantMinutes
        )
        val updatedPool = pool.copy(
            accumulatedMinutes = result.accumulatedMinutes,
            lastSettledUseDayId = nextUseDayId
        )
        return updatedPool to result
    }

    internal fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    fun dayRoleFor(weekday: Int, unlockDays: Set<Int>): AppRuleDayRole {
        return if (weekday in unlockDays) AppRuleDayRole.UNLOCK else AppRuleDayRole.ACCRUAL
    }

    fun dayRoleFor(date: LocalDate, unlockDays: Set<Int>): AppRuleDayRole {
        val weekday = date.dayOfWeek.value % 7
        return dayRoleFor(weekday, unlockDays)
    }

    fun dayRoleFor(useDayId: String, unlockDays: Set<Int>): AppRuleDayRole {
        val date = LocalDate.parse(useDayId, ID_FORMAT)
        return dayRoleFor(date, unlockDays)
    }

    fun dayRoleFor(useDayId: String, rule: AppRule): AppRuleDayRole {
        return dayRoleFor(useDayId, rule.unlockDays)
    }

    /**
     * Reconciles rollover settlement sequentially from [pool.lastSettledUseDayId] to [currentUseDayId].
     *
     * Intermediate days where the device was off are simulated with 0 unused minutes.
     * When rollover is disabled on the rule, the existing pool balance is preserved.
     */
    fun reconcileSettlement(
        pool: RuleRolloverPool,
        rule: AppRule,
        currentUseDayId: String,
        lastSettledDayUnusedMinutes: Long = 0L,
        unusedMinutesByDay: Map<String, Long> = emptyMap()
    ): RolloverReconciliationResult {
        if (!rule.rolloverEnabled) {
            return RolloverReconciliationResult(
                pool = pool.copy(lastSettledUseDayId = currentUseDayId.ifBlank { pool.lastSettledUseDayId }),
                transitions = emptyList()
            )
        }

        if (pool.lastSettledUseDayId.isBlank()) {
            return RolloverReconciliationResult(
                pool = pool.copy(lastSettledUseDayId = currentUseDayId),
                transitions = emptyList()
            )
        }

        if (!shouldSettle(pool.lastSettledUseDayId, currentUseDayId)) {
            return RolloverReconciliationResult(
                pool = pool,
                transitions = emptyList()
            )
        }

        val startDate = LocalDate.parse(pool.lastSettledUseDayId, ID_FORMAT)
        val endDate = LocalDate.parse(currentUseDayId, ID_FORMAT)

        var date = startDate
        var currentMinutes = pool.accumulatedMinutes.coerceAtLeast(0L)
        val transitions = mutableListOf<RolloverSettlementResult>()

        while (date < endDate) {
            val nextDate = date.plusDays(1)
            val endingDayRole = dayRoleFor(date, rule.unlockDays)
            val nextDayRole = dayRoleFor(nextDate, rule.unlockDays)
            val dayId = date.format(ID_FORMAT)
            val unusedMinutes = unusedMinutesByDay[dayId]
                ?: if (date == startDate) lastSettledDayUnusedMinutes else 0L

            val result = settleDayTransition(
                currentPoolMinutes = currentMinutes,
                endingDayRole = endingDayRole,
                nextDayRole = nextDayRole,
                unusedGrantMinutes = unusedMinutes
            )
            transitions += result
            currentMinutes = result.accumulatedMinutes
            date = nextDate
        }

        return RolloverReconciliationResult(
            pool = pool.copy(
                accumulatedMinutes = currentMinutes,
                lastSettledUseDayId = currentUseDayId
            ),
            transitions = transitions
        )
    }

    fun reconcileSettlement(
        currentPoolMinutes: Long,
        lastSettledUseDayId: String,
        currentUseDayId: String,
        unlockDays: Set<Int>,
        rolloverEnabled: Boolean = true,
        ruleId: String = "",
        lastSettledDayUnusedMinutes: Long = 0L,
        unusedMinutesByDay: Map<String, Long> = emptyMap()
    ): RolloverReconciliationResult {
        val pool = RuleRolloverPool(
            ruleId = ruleId,
            accumulatedMinutes = currentPoolMinutes.coerceAtLeast(0L),
            lastSettledUseDayId = lastSettledUseDayId
        )
        val dummyRule = AppRule(
            id = ruleId,
            rolloverEnabled = rolloverEnabled,
            unlockDays = unlockDays
        )
        return reconcileSettlement(
            pool = pool,
            rule = dummyRule,
            currentUseDayId = currentUseDayId,
            lastSettledDayUnusedMinutes = lastSettledDayUnusedMinutes,
            unusedMinutesByDay = unusedMinutesByDay
        )
    }
}
