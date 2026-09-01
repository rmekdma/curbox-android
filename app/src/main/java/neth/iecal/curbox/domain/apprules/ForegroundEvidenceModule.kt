package neth.iecal.curbox.domain.apprules

/**
 * Interprets raw foreground facts. Timing in this module is intrinsic evidence validity only;
 * execution deadlines and retry scheduling belong to the later worker implementation.
 */
class ForegroundEvidenceModule {
    companion object {
        const val RECENT_EVENT_MAX_AGE_MS = 5_000L
    }

    // Raw signal history is the only state retained across observations. It lets a synthetic
    // recheck reconcile a definite root transition without storing policy-derived visibility or
    // any execution state.
    private var lastRealSignal: SignalFact? = null

    fun classify(
        facts: ForegroundFacts,
        policy: ForegroundEvidencePolicySnapshot
    ): ForegroundEvidenceResult {
        val normalizedFacts = facts.normalized()
        val essentialPackages = policy.normalized().essentialPackages
        val previousRealSignal = lastRealSignal
        val currentRealSignal = normalizedFacts.signal
            .takeIf { it.kind == ObservationKind.REAL_EVENT && it.eventPackage != null }
        currentRealSignal?.let { lastRealSignal = it }
        val candidateSignal = currentRealSignal ?: lastRealSignal
        val eventPackage = candidateSignal?.eventPackage
            ?.takeIf { it !in essentialPackages }
        val eventElapsedMs = candidateSignal?.eventElapsedMs
        val eventAgeMs = eventElapsedMs?.let {
            normalizedFacts.capturedAtElapsedMs - it
        }
        val isRecentEvent = eventPackage != null &&
            eventAgeMs != null &&
            eventAgeMs in 0L..RECENT_EVENT_MAX_AGE_MS

        val activeRootPackage = normalizedFacts.activeRoot.packageName
            ?.takeIf { normalizedFacts.activeRoot.readState == ForegroundReadState.AVAILABLE }
            ?.takeIf { it !in essentialPackages }

        val applicationWindowPackages = normalizedFacts.applicationWindows.packages
            .asSequence()
            .filter { it !in essentialPackages }
            .toList()

        if (activeRootPackage != null) {
            val previousCandidate = if (eventPackage == activeRootPackage) {
                previousRealSignal
                    ?.eventPackage
                    ?.takeIf { it !in essentialPackages && it != activeRootPackage }
            } else {
                eventPackage
            }
            val visible = ForegroundEvidenceOutcome.Visible(
                packageName = activeRootPackage,
                evidenceBasis = if (
                    currentRealSignal != null &&
                    eventPackage == activeRootPackage &&
                    isRecentEvent
                ) {
                    EvidenceBasis.REAL_EVENT_AND_ACTIVE_ROOT
                } else {
                    EvidenceBasis.ACTIVE_ROOT
                },
                sessionEffect = SessionEvidenceEffect.RENEW,
                decisionPermission = DecisionPermission.EVALUATE,
                evidenceValidity = renewedUntil(normalizedFacts.capturedAtElapsedMs),
                followUp = FollowUpKind.NONE
            )
            return ForegroundEvidenceResult(
                outcomes = buildList {
                    previousCandidate?.let {
                        add(
                            ForegroundEvidenceOutcome.NotVisible(
                                packageName = it,
                                evidenceBasis = EvidenceBasis.DIFFERENT_ACTIVE_ROOT,
                                sessionEffect = SessionEvidenceEffect.END_WITHOUT_RENEWAL,
                                decisionPermission = DecisionPermission.DO_NOT_EVALUATE,
                                evidenceValidity = EvidenceValidity.NotRenewed,
                                followUp = FollowUpKind.NONE
                            )
                        )
                    }
                    add(visible)
                }
            )
        }

        if (normalizedFacts.applicationWindows.freshness == ApplicationWindowsFreshness.FRESH &&
            applicationWindowPackages.isNotEmpty()
        ) {
            val previousCandidate = eventPackage?.takeIf { it !in applicationWindowPackages }
            return ForegroundEvidenceResult(
                outcomes = buildList {
                    previousCandidate?.let {
                        add(
                            ForegroundEvidenceOutcome.NotVisible(
                                packageName = it,
                                evidenceBasis = EvidenceBasis.APPLICATION_WINDOW,
                                sessionEffect = SessionEvidenceEffect.END_WITHOUT_RENEWAL,
                                decisionPermission = DecisionPermission.DO_NOT_EVALUATE,
                                evidenceValidity = EvidenceValidity.NotRenewed,
                                followUp = FollowUpKind.NONE
                            )
                        )
                    }
                    applicationWindowPackages.forEach { packageName ->
                        add(
                            ForegroundEvidenceOutcome.Visible(
                                packageName = packageName,
                                evidenceBasis = EvidenceBasis.APPLICATION_WINDOW,
                                sessionEffect = SessionEvidenceEffect.RENEW,
                                decisionPermission = DecisionPermission.EVALUATE,
                                evidenceValidity = renewedUntil(
                                    normalizedFacts.capturedAtElapsedMs
                                ),
                                followUp = FollowUpKind.NONE
                            )
                        )
                    }
                }
            )
        }

        if (isRecentEvent) {
            val recentPackage = requireNotNull(eventPackage)
            return ForegroundEvidenceResult(
                outcomes = listOf(
                    ForegroundEvidenceOutcome.Visible(
                        packageName = recentPackage,
                        evidenceBasis = EvidenceBasis.RECENT_REAL_EVENT,
                        sessionEffect = SessionEvidenceEffect.PRESERVE,
                        decisionPermission = DecisionPermission.EVALUATE,
                        evidenceValidity = EvidenceValidity.NotRenewed,
                        followUp = FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
                    )
                )
            )
        }

        return ForegroundEvidenceResult(
            outcomes = listOf(
                ForegroundEvidenceOutcome.Unknown(
                    candidatePackage = eventPackage,
                    evidenceBasis = EvidenceBasis.NO_RELIABLE_EVIDENCE,
                    sessionEffect = SessionEvidenceEffect.PRESERVE,
                    decisionPermission = if (eventPackage == null) {
                        DecisionPermission.DEFER
                    } else {
                        DecisionPermission.EVALUATE_FAIL_CLOSED
                    },
                    evidenceValidity = EvidenceValidity.NotRenewed,
                    followUp = if (eventPackage == null) {
                        FollowUpKind.WAIT_FOR_RELIABLE_EVIDENCE
                    } else {
                        FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
                    }
                )
            )
        )
    }

    private fun renewedUntil(capturedAtElapsedMs: Long): EvidenceValidity.RenewedUntil =
        EvidenceValidity.RenewedUntil(
            validUntilElapsedMs = (capturedAtElapsedMs + RECENT_EVENT_MAX_AGE_MS)
                .coerceAtLeast(capturedAtElapsedMs)
        )
}
