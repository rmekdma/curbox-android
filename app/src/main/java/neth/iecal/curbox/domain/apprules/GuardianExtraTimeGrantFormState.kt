package neth.iecal.curbox.domain.apprules

/** Selection and linked inputs for a direct grant to one app rule. */
data class GuardianExtraTimeGrantFormState private constructor(
    val selectionRevision: Long,
    val selectedRuleId: String,
    val basis: GuardianExtraTimeGrantBasis?,
    val input: GuardianExtraTimeFormState
) {
    fun selectRule(ruleId: String): GuardianExtraTimeGrantFormState {
        if (ruleId.isBlank() || ruleId == selectedRuleId) return this
        return copy(
            selectionRevision = selectionRevision + 1L,
            selectedRuleId = ruleId,
            basis = null,
            input = GuardianExtraTimeFormState.initial(0L)
        )
    }

    /** Ignores a delayed read after the user has moved to another rule. */
    fun receiveBasis(
        requestedRevision: Long,
        latestBasis: GuardianExtraTimeGrantBasis
    ): GuardianExtraTimeGrantFormState {
        if (requestedRevision != selectionRevision ||
            selectedRuleId != latestBasis.ruleId
        ) return this
        return copy(
            basis = latestBasis,
            input = GuardianExtraTimeFormState.initial(latestBasis.currentTotalMinutes)
        )
    }

    fun clearInputs(): GuardianExtraTimeGrantFormState = copy(
        basis = null,
        input = GuardianExtraTimeFormState.initial(0L)
    )

    fun editAdditionalMinutes(text: String): GuardianExtraTimeGrantFormState = copy(
        input = input.editAdditionalMinutes(text)
    )

    fun editTotalMinutes(text: String): GuardianExtraTimeGrantFormState = copy(
        input = input.editTotalMinutes(text)
    )

    companion object {
        fun initial(basis: GuardianExtraTimeGrantBasis): GuardianExtraTimeGrantFormState =
            GuardianExtraTimeGrantFormState(
                selectionRevision = 0L,
                selectedRuleId = basis.ruleId,
                basis = basis,
                input = GuardianExtraTimeFormState.initial(basis.currentTotalMinutes)
            )
    }
}
