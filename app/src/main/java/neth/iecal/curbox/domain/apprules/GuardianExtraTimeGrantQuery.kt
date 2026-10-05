package neth.iecal.curbox.domain.apprules

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import java.time.Clock
import java.time.ZoneId

data class GuardianExtraTimeGrantCandidate(
    val rule: AppRule,
    val basis: GuardianExtraTimeGrantBasis
)

/**
 * Reads the eligible direct-grant rules and their current basis from one effective settings
 * snapshot. Candidate ordering follows saved rule order, with rules denying during their current
 * active window first. An empty list means that no rule can receive a grant; read failures throw
 * and coroutine cancellation is propagated. Each lookup captures one instant and zone; a
 * candidate lookup also uses one use-day id and generation for its session read and grant bases.
 */
interface GuardianExtraTimeGrantQuery {
    suspend fun candidates(): List<GuardianExtraTimeGrantCandidate>

    /** Reads the selected rule's latest basis. A missing or ineligible rule returns null. */
    suspend fun currentBasis(ruleId: String): GuardianExtraTimeGrantBasis?
}

/** Shared query implementation used by both guardian extra-time screens. */
class AppRuleGuardianExtraTimeGrantQuery(
    private val effectiveSettings: Flow<Settings>,
    private val sessionRepository: CurrentUseDaySessionRepository,
    private val packageScopeReader: AppRulePackageScopeReader,
    private val clock: Clock = Clock.systemUTC(),
    private val currentZone: () -> ZoneId = { ZoneId.systemDefault() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GuardianExtraTimeGrantQuery {
    override suspend fun candidates(): List<GuardianExtraTimeGrantCandidate> =
        withContext(ioDispatcher) {
            val settings = effectiveSettings.first()
            val nowMs = clock.millis()
            val zone = currentZone()
            val calculator = ConfigurableUseDayCalculator(zone, settings.useDayResetTime)
            val useDayId = calculator.idAt(nowMs)
            val sessions = sessionRepository.sessionsForUseDay(
                useDayId,
                settings.useDayGenerationStartedAtMs
            )
            val snapshot = settings.appRuleSnapshot.normalized()
            val evaluations = GuardianExtraTimeRulePicker.evaluateRules(
                snapshot = snapshot,
                useDayId = useDayId,
                sessions = sessions,
                nowMs = nowMs,
                resetTime = settings.useDayResetTime,
                zone = zone,
                useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
                availablePackages = packageScopeReader.readLaunchablePackages(),
                essentialExcludedPackages = packageScopeReader.readEssentialPackages(),
                overrides = settings.appRuleOverrideState
            )
            GuardianExtraTimeRulePicker.candidates(snapshot, evaluations.values)
                .mapNotNull { rule ->
                    GuardianExtraTimeGrantBasis.capture(settings, rule.id, nowMs, zone)
                        ?.let { basis -> GuardianExtraTimeGrantCandidate(rule, basis) }
                }
        }

    override suspend fun currentBasis(ruleId: String): GuardianExtraTimeGrantBasis? =
        withContext(ioDispatcher) {
            val settings = effectiveSettings.first()
            GuardianExtraTimeGrantBasis.capture(
                settings = settings,
                ruleId = ruleId,
                nowMs = clock.millis(),
                zone = currentZone()
            )
        }
}
