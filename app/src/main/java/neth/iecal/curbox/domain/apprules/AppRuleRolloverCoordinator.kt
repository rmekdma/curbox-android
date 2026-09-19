package neth.iecal.curbox.domain.apprules

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.data.models.AppRuleRolloverState
import neth.iecal.curbox.data.models.RuleRolloverPool
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager

data class RolloverCoordinatorResult(
    val settledPools: Map<String, RuleRolloverPool>,
    val transitions: Map<String, List<RolloverSettlementResult>>,
    val committed: Boolean
)

class AppRuleRolloverCoordinator(
    private val dataStoreManager: DataStoreManager? = null,
    private val sessionRepository: CurrentUseDaySessionRepository,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val onNonFatalError: (Throwable) -> Unit = {},
    internal val readSettings: (suspend () -> Settings)? = null,
    internal val writeRolloverState: (suspend (AppRuleRolloverState) -> Boolean)? = null
) {
    suspend fun reconcileSettlement(nowMs: Long = wallClockMs()): RolloverCoordinatorResult {
        try {
            val settings = readSettings?.invoke()
                ?: dataStoreManager?.settings?.first()
                ?: throw IllegalStateException("DataStoreManager or readSettings must be provided")

            val snapshot = settings.appRuleSnapshot
            val rolloverState = settings.appRuleRolloverState
            val overrideState = settings.appRuleOverrideState
            val resetTime = settings.useDayResetTime
            val generationStartedAtMs = settings.useDayGenerationStartedAtMs
            val currentZone = zone()

            val calculator = ConfigurableUseDayCalculator(currentZone, resetTime)
            val currentUseDayId = calculator.idAt(nowMs)

            val existingPools = rolloverState.pools.toMutableMap()
            val allTransitions = mutableMapOf<String, List<RolloverSettlementResult>>()
            var stateChanged = false

            for (rule in snapshot.appRules) {
                var pool = existingPools[rule.id]
                if (pool == null) {
                    pool = RuleRolloverPool(
                        ruleId = rule.id,
                        accumulatedMinutes = 0L,
                        lastSettledUseDayId = currentUseDayId
                    )
                    existingPools[rule.id] = pool
                    stateChanged = true
                }

                if (!rule.rolloverEnabled) {
                    if (pool.lastSettledUseDayId != currentUseDayId && pool.lastSettledUseDayId.isNotBlank()) {
                        val updatedPool = pool.copy(lastSettledUseDayId = currentUseDayId)
                        existingPools[rule.id] = updatedPool
                        stateChanged = true
                    }
                    continue
                }

                if (!AppRuleRolloverPolicy.shouldSettle(pool.lastSettledUseDayId, currentUseDayId)) {
                    continue
                }

                val sessions = sessionRepository.sessionsForUseDay(pool.lastSettledUseDayId)
                val unusedMinutes = AppRuleEvaluator.computeUnusedGuardianMinutes(
                    rule = rule,
                    snapshot = snapshot,
                    useDayId = pool.lastSettledUseDayId,
                    sessions = sessions,
                    overrides = overrideState,
                    zone = currentZone,
                    resetTime = resetTime,
                    useDayGenerationStartedAtMs = generationStartedAtMs,
                    availablePackages = sessions.map { it.packageName }.toSet()
                )

                val reconciliation = AppRuleRolloverPolicy.reconcileSettlement(
                    pool = pool,
                    rule = rule,
                    currentUseDayId = currentUseDayId,
                    lastSettledDayUnusedMinutes = unusedMinutes
                )

                if (reconciliation.transitions.isNotEmpty() || reconciliation.pool != pool) {
                    existingPools[rule.id] = reconciliation.pool
                    allTransitions[rule.id] = reconciliation.transitions
                    stateChanged = true
                }
            }

            val committed = if (stateChanged) {
                val newState = AppRuleRolloverState(pools = existingPools)
                val writeResult = writeRolloverState?.invoke(newState)
                    ?: dataStoreManager?.writeAppRuleRolloverState(newState)
                    ?: false
                writeResult
            } else {
                true
            }

            return RolloverCoordinatorResult(
                settledPools = existingPools,
                transitions = allTransitions,
                committed = committed
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            try {
                onNonFatalError(t)
            } catch (_: Throwable) {
            }
            return RolloverCoordinatorResult(
                settledPools = emptyMap(),
                transitions = emptyMap(),
                committed = false
            )
        }
    }
}
