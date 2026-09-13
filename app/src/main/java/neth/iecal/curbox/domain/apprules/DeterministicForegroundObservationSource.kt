package neth.iecal.curbox.domain.apprules

/**
 * Value-only source adapter for deterministic tests. It supplies the same raw facts contract as
 * the production adapter and deliberately does not interpret evidence policy.
 */
class DeterministicForegroundObservationSource(
    private val factProvider: (ObservationTrigger) -> ForegroundFacts
) : ForegroundObservationSource {
    constructor(facts: ForegroundFacts) : this({ facts })

    override fun capture(trigger: ObservationTrigger): ForegroundFacts =
        factProvider(trigger).normalized()
}
