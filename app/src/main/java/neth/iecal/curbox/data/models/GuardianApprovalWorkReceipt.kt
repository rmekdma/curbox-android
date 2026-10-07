package neth.iecal.curbox.data.models

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
            is DirectGrant -> state.grants.any(grant::matches)
            is AccumulatedGrant -> state.grants.any {
                it.ruleId == ruleId &&
                    it.useDayId == useDayId &&
                    it.grantedAtMs == grantedAtMs &&
                    it.grantedMillis == grantedMillis &&
                    it.isFromAccumulatedPool
            }
            is RuleSkip -> state.skips.any {
                it.ruleId == ruleId &&
                    it.useDayId == useDayId &&
                    it.skipFromMs <= skipFromMs &&
                    it.skipUntilMs >= skipUntilMs
            }
        }
    }

    data class DirectGrant(
        val grant: GuardianApprovalGrantReceipt,
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

    data class AccumulatedGrant(
        override val ruleId: String,
        override val useDayId: String,
        val grantedAtMs: Long,
        val grantedMillis: Long,
        override val useDayGenerationStartedAtMs: Long
    ) : GuardianApprovalWorkReceipt {
        init {
            require(ruleId.isNotBlank()) { "accumulated receipt must name a rule" }
            require(useDayId.isNotBlank()) { "accumulated receipt must name a use day" }
            require(grantedAtMs >= 0L) { "accumulated receipt timestamp must not be negative" }
            require(grantedMillis > 0L) { "accumulated receipt must contain a positive grant" }
            require(useDayGenerationStartedAtMs >= 0L) {
                "approval receipt generation must not be negative"
            }
        }
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
