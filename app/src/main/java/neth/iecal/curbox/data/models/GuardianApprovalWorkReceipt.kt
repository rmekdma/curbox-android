package neth.iecal.curbox.data.models

/** Identifies whether a grant was given directly or drawn from the accumulated pool. */
enum class GuardianApprovalGrantOrigin(val isFromAccumulatedPool: Boolean) {
    DIRECT(false),
    ACCUMULATED_POOL(true)
}

/** Identifies the exact persisted effect of one guardian approval operation. */
sealed interface GuardianApprovalWorkReceipt {
    val ruleId: String
    val useDayId: String
    val useDayGenerationStartedAtMs: Long

    fun isPresentIn(state: AppRuleOverrideState): Boolean =
        isPresentIn(
            state = state,
            currentUseDayId = useDayId,
            currentUseDayGenerationStartedAtMs = useDayGenerationStartedAtMs
        )

    fun isPresentIn(
        state: AppRuleOverrideState,
        currentUseDayId: String,
        currentUseDayGenerationStartedAtMs: Long
    ): Boolean {
        if (ruleId.isBlank() || useDayId != currentUseDayId ||
            useDayGenerationStartedAtMs != currentUseDayGenerationStartedAtMs ||
            state.useDayId != useDayId ||
            state.useDayGenerationStartedAtMs != useDayGenerationStartedAtMs
        ) return false

        return when (this) {
            is Grant -> state.grants.any {
                grant.matches(it, origin)
            }
            is RuleSkip -> state.skips.any {
                it.ruleId == ruleId &&
                    it.useDayId == useDayId &&
                    it.skipFromMs <= skipFromMs &&
                    it.skipUntilMs >= skipUntilMs
            }
        }
    }

    data class Grant(
        val grant: GuardianApprovalGrantReceipt,
        val origin: GuardianApprovalGrantOrigin,
        override val useDayGenerationStartedAtMs: Long
    ) : GuardianApprovalWorkReceipt {
        init {
            require(useDayGenerationStartedAtMs >= 0L) {
                "approval receipt generation must not be negative"
            }
        }

        override val ruleId: String get() = grant.ruleId
        override val useDayId: String get() = grant.useDayId
    }

    data class RuleSkip(
        override val ruleId: String,
        override val useDayId: String,
        val skipFromMs: Long,
        val skipUntilMs: Long,
        override val useDayGenerationStartedAtMs: Long
    ) : GuardianApprovalWorkReceipt {
        init {
            require(ruleId.isNotBlank()) { "skip receipt must name a rule" }
            require(useDayId.isNotBlank()) { "skip receipt must name a use day" }
            require(skipFromMs >= 0L) { "skip receipt start must not be negative" }
            require(skipUntilMs > skipFromMs) { "skip receipt must contain a nonempty interval" }
            require(useDayGenerationStartedAtMs >= 0L) {
                "approval receipt generation must not be negative"
            }
        }
    }
}
