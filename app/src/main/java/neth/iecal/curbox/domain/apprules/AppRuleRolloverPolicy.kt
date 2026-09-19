package neth.iecal.curbox.domain.apprules

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.RuleRolloverPool

/** Role of a use-day for an app rule with rollover enabled. */
enum class AppRuleDayRole {
    ACCRUAL,
    UNLOCK
}

/** Result of a single day transition settlement for an app rule rollover pool. */
data class RolloverSettlementResult(
    val previousPoolMinutes: Long,
    val accumulatedMinutes: Long,
    val addedMinutes: Long,
    val expiredMinutes: Long,
    val endingDayRole: AppRuleDayRole,
    val nextDayRole: AppRuleDayRole
)

/** Result of catch-up reconciliation across one or more use-day boundaries. */
data class RolloverReconciliationResult(
    val pool: RuleRolloverPool,
    val transitions: List<RolloverSettlementResult>
)

/**
 * Pure domain engine for guardian extra time rollover settlement and catch-up.
 *
 * This object has no Android framework or database dependencies.
 */
object AppRuleRolloverPolicy {

    private val ID_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Determines whether rollover settlement should occur between [lastSettledUseDayId] and [currentUseDayId].
     *
     * Returns true ONLY when [lastSettledUseDayId] and [currentUseDayId] represent valid, distinct
     * calendar dates where [lastSettledUseDayId] < [currentUseDayId].
     *
     * In particular, returns false when:
     * - [lastSettledUseDayId] is blank (initial uninitialized pool)
     * - [currentUseDayId] is blank
     * - [lastSettledUseDayId] == [currentUseDayId] (defends against premature settlement when restore time
     *   or use-day generation changes midday without an actual calendar boundary transition)
     * - [lastSettledUseDayId] > [currentUseDayId] (system clock backwards jump)
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

        return when (endingDayRole) {
            AppRuleDayRole.ACCRUAL -> {
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
            AppRuleDayRole.UNLOCK -> {
                if (nextDayRole == AppRuleDayRole.UNLOCK) {
                    val nextPool = safeAdd(previous, unused)
                    RolloverSettlementResult(
                        previousPoolMinutes = previous,
                        accumulatedMinutes = nextPool,
                        addedMinutes = unused,
                        expiredMinutes = 0L,
                        endingDayRole = endingDayRole,
                        nextDayRole = nextDayRole
                    )
                } else {
                    val expired = safeAdd(previous, unused)
                    RolloverSettlementResult(
                        previousPoolMinutes = previous,
                        accumulatedMinutes = 0L,
                        addedMinutes = 0L,
                        expiredMinutes = expired,
                        endingDayRole = endingDayRole,
                        nextDayRole = nextDayRole
                    )
                }
            }
        }
    }

    /**
     * Convenience overload that settles a transition for a [RuleRolloverPool] and advances its
     * [RuleRolloverPool.lastSettledUseDayId] to [nextUseDayId].
     */
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

    /**
     * Resolves the [AppRuleDayRole] for a given [weekday] index (Sunday=0, Monday=1, ..., Saturday=6).
     */
    fun dayRoleFor(weekday: Int, unlockDays: Set<Int>): AppRuleDayRole {
        return if (weekday in unlockDays) AppRuleDayRole.UNLOCK else AppRuleDayRole.ACCRUAL
    }

    /**
     * Resolves the [AppRuleDayRole] for a given [date].
     * Curbox convention maps Sunday to 0 (DayOfWeek.SUNDAY.value % 7 == 0).
     */
    fun dayRoleFor(date: LocalDate, unlockDays: Set<Int>): AppRuleDayRole {
        val weekday = date.dayOfWeek.value % 7
        return dayRoleFor(weekday, unlockDays)
    }

    /**
     * Resolves the [AppRuleDayRole] for a given [useDayId] string (format YYYY-MM-DD).
     */
    fun dayRoleFor(useDayId: String, unlockDays: Set<Int>): AppRuleDayRole {
        val date = LocalDate.parse(useDayId, ID_FORMAT)
        return dayRoleFor(date, unlockDays)
    }

    /**
     * Resolves the [AppRuleDayRole] for a given [useDayId] and [rule].
     */
    fun dayRoleFor(useDayId: String, rule: AppRule): AppRuleDayRole {
        return dayRoleFor(useDayId, rule.unlockDays)
    }

    /**
     * Reconciles rollover settlement sequentially from [pool.lastSettledUseDayId] to [currentUseDayId].
     *
     * Intermediate days where the device was powered off or in deep sleep are simulated with 0 unused minutes,
     * correctly advancing and expiring the pool according to weekday role transitions.
     *
     * When rollover is disabled on the rule, the existing pool balance is safely preserved without
     * accumulation or expiration (User Story 27).
     */
    fun reconcileSettlement(
        pool: RuleRolloverPool,
        rule: AppRule,
        currentUseDayId: String,
        lastSettledDayUnusedMinutes: Long = 0L,
        unusedMinutesByDay: Map<String, Long> = emptyMap()
    ): RolloverReconciliationResult {
        return reconcileSettlement(
            currentPoolMinutes = pool.accumulatedMinutes,
            lastSettledUseDayId = pool.lastSettledUseDayId,
            currentUseDayId = currentUseDayId,
            unlockDays = rule.unlockDays,
            rolloverEnabled = rule.rolloverEnabled,
            ruleId = pool.ruleId.ifBlank { rule.id },
            lastSettledDayUnusedMinutes = lastSettledDayUnusedMinutes,
            unusedMinutesByDay = unusedMinutesByDay
        )
    }

    /**
     * Reconciles rollover settlement sequentially from [lastSettledUseDayId] to [currentUseDayId].
     */
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
        val initialPool = RuleRolloverPool(
            ruleId = ruleId,
            accumulatedMinutes = currentPoolMinutes.coerceAtLeast(0L),
            lastSettledUseDayId = lastSettledUseDayId
        )

        // When rollover is disabled, safely preserve the current pool balance (User Story 27)
        if (!rolloverEnabled) {
            return RolloverReconciliationResult(
                pool = initialPool.copy(lastSettledUseDayId = currentUseDayId.ifBlank { lastSettledUseDayId }),
                transitions = emptyList()
            )
        }

        // First initialization: no previous day to settle, advance lastSettledUseDayId to current
        if (lastSettledUseDayId.isBlank()) {
            return RolloverReconciliationResult(
                pool = initialPool.copy(lastSettledUseDayId = currentUseDayId),
                transitions = emptyList()
            )
        }

        // Defend against premature settlement if no calendar boundary was crossed
        if (!shouldSettle(lastSettledUseDayId, currentUseDayId)) {
            return RolloverReconciliationResult(
                pool = initialPool,
                transitions = emptyList()
            )
        }

        val startDate = LocalDate.parse(lastSettledUseDayId, ID_FORMAT)
        val endDate = LocalDate.parse(currentUseDayId, ID_FORMAT)

        var date = startDate
        var currentMinutes = initialPool.accumulatedMinutes
        val transitions = mutableListOf<RolloverSettlementResult>()

        while (date < endDate) {
            val nextDate = date.plusDays(1)
            val endingDayRole = dayRoleFor(date, unlockDays)
            val nextDayRole = dayRoleFor(nextDate, unlockDays)
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
            pool = initialPool.copy(
                accumulatedMinutes = currentMinutes,
                lastSettledUseDayId = currentUseDayId
            ),
            transitions = transitions
        )
    }

    /**
     * Reconciles all rules in [snapshot] against [state] up to [currentUseDayId].
     *
     * Rules with rollover disabled or unconfigured will safely preserve their existing pool balances.
     * Existing pools for rules not present in [snapshot] are preserved.
     */
    fun reconcileAllRules(
        state: AppRuleRolloverState,
        snapshot: AppRuleSnapshot,
        currentUseDayId: String,
        unusedMinutesByRuleAndDay: Map<Pair<String, String>, Long> = emptyMap()
    ): AppRuleRolloverState {
        var updatedState = state
        snapshot.appRules.forEach { rule ->
            val currentPool = updatedState.poolFor(rule.id)
            val ruleUnusedMinutesByDay = unusedMinutesByRuleAndDay
                .filterKeys { it.first == rule.id }
                .mapKeys { it.key.second }

            val reconciliation = reconcileSettlement(
                pool = currentPool,
                rule = rule,
                currentUseDayId = currentUseDayId,
                lastSettledDayUnusedMinutes = 0L,
                unusedMinutesByDay = ruleUnusedMinutesByDay
            )
            updatedState = updatedState.withPool(reconciliation.pool)
        }
        return updatedState
    }
}
