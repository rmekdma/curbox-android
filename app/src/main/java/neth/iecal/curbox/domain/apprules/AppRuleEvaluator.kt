package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import java.time.ZoneId

data class AppRuleEvaluation(
    val ruleId: String,
    val isApplicable: Boolean,
    val isActive: Boolean,
    val usedMillis: Long,
    val allowanceMillis: Long,
    val remainingMillis: Long,
    val isAllowed: Boolean,
    val validationErrors: List<String> = emptyList()
)

data class AppRulesEvaluation(
    val isAllowed: Boolean,
    val denyingRules: List<AppRuleEvaluation>,
    val evaluations: List<AppRuleEvaluation>
)

object AppRuleValidator {
    fun validate(snapshot: AppRuleSnapshot): List<String> = snapshot.validate()

    fun isValid(snapshot: AppRuleSnapshot): Boolean = snapshot.isValid
}

/** Pure rule decision boundary used by both the service and JVM unit tests. */
object AppRuleEvaluator {

    fun evaluate(
        snapshot: AppRuleSnapshot,
        packageName: String,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AppRulesEvaluation {
        val validationErrors = snapshot.validate()
        if (validationErrors.isNotEmpty()) {
            return AppRulesEvaluation(
                isAllowed = false,
                denyingRules = snapshot.appRules.filter { it.isActive }.map { rule ->
                    AppRuleEvaluation(
                        ruleId = rule.id,
                        isApplicable = true,
                        isActive = false,
                        usedMillis = 0L,
                        allowanceMillis = 0L,
                        remainingMillis = 0L,
                        isAllowed = false,
                        validationErrors = validationErrors
                    )
                },
                evaluations = emptyList()
            )
        }

        val groups = snapshot.appGroups.associateBy { it.id }
        val sessionList = sessions.toList()
        val evaluations = snapshot.appRules
            .filter { it.isActive }
            .mapNotNull { rule ->
                val packages = groups[rule.appGroupId]?.selectedPackages.orEmpty().toSet()
                if (packageName !in packages) return@mapNotNull null
                evaluateRule(rule, packages, useDayId, sessionList, nowMs, zone)
            }
        return AppRulesEvaluation(
            isAllowed = evaluations.all { it.isAllowed },
            denyingRules = evaluations.filterNot { it.isAllowed },
            evaluations = evaluations
        )
    }

    fun evaluateRule(
        rule: AppRule,
        targetPackages: Set<String>,
        useDayId: String,
        sessions: Iterable<ForegroundSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): AppRuleEvaluation {
        val activeWindow = AppRuleSchedule.activeWindow(rule, nowMs, zone)
        val allowanceMillis = rule.allowedMinutes
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / MILLIS_PER_MINUTE) * MILLIS_PER_MINUTE
        if (activeWindow == null) {
            return AppRuleEvaluation(
                ruleId = rule.id,
                isApplicable = true,
                isActive = false,
                usedMillis = 0L,
                allowanceMillis = allowanceMillis,
                remainingMillis = allowanceMillis,
                isAllowed = true
            )
        }

        val usageWindows = AppRuleSchedule.usageWindowsForUseDay(rule, useDayId, zone)
        val usedMillis = sessions.asSequence()
            .filter { it.useDayId == useDayId && it.packageName in targetPackages }
            .sumOf { session ->
                val end = minOf(session.endedAtMs ?: nowMs, nowMs)
                usageWindows.sumOf { (windowStart, windowEnd) ->
                    overlapMillis(session.startedAtMs, end, windowStart, windowEnd)
                }
            }
        val remainingMillis = allowanceMillis - usedMillis
        return AppRuleEvaluation(
            ruleId = rule.id,
            isApplicable = true,
            isActive = true,
            usedMillis = usedMillis,
            allowanceMillis = allowanceMillis,
            remainingMillis = remainingMillis,
            isAllowed = remainingMillis > 0L
        )
    }

    private fun overlapMillis(
        leftStart: Long,
        leftEnd: Long,
        rightStart: Long,
        rightEnd: Long
    ): Long {
        if (leftEnd <= leftStart || rightEnd <= rightStart) return 0L
        return (minOf(leftEnd, rightEnd) - maxOf(leftStart, rightStart)).coerceAtLeast(0L)
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
