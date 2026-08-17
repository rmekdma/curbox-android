package neth.iecal.curbox.data.models

/**
 * Local guardian credential material.  The verifier is a PBKDF2 output, never the password or a
 * fast reusable digest.  Empty values intentionally mean that guardian authentication is off.
 */
data class GuardianAuthConfig(
    val passwordSalt: String = "",
    val passwordVerifier: String = "",
    val kdfAlgorithm: String = "PBKDF2WithHmacSHA256",
    val kdfIterations: Int = 120_000
) {
    val isConfigured: Boolean
        get() = passwordSalt.isNotBlank() && passwordVerifier.isNotBlank()
}

/** A single rule's extra foreground allowance for one use day. */
data class AppRuleGuardianGrant(
    val ruleId: String = "",
    val useDayId: String = "",
    val grantedAtMs: Long = 0L,
    val grantedMillis: Long = 0L
)

/** A single rule's temporary skip.  The end is always capped by the use-day reset. */
data class AppRuleGuardianSkip(
    val ruleId: String = "",
    val useDayId: String = "",
    val skipUntilMs: Long = 0L
)

/** Local, multi-process-safe state for guardian approvals. */
data class AppRuleOverrideState(
    val useDayId: String = "",
    val grants: List<AppRuleGuardianGrant> = emptyList(),
    val skips: List<AppRuleGuardianSkip> = emptyList()
) {
    fun forUseDay(currentUseDayId: String): AppRuleOverrideState =
        if (useDayId == currentUseDayId) this else AppRuleOverrideState(currentUseDayId)
}

/** Snapshot passed to the internal guardian approval surface; it contains no mutable authority. */
data class AppRuleGuardianDenial(
    val ruleId: String = "",
    val ruleName: String = "",
    val reason: String = ""
)

/** Names used by callers that describe the same persisted approval objects. */
typealias GuardianRuleGrant = AppRuleGuardianGrant
typealias GuardianRuleSkip = AppRuleGuardianSkip
typealias GuardianRuleOverrideState = AppRuleOverrideState
