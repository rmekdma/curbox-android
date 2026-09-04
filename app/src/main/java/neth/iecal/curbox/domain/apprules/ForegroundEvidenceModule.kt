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
        if (normalizedFacts.signal.kind == ObservationKind.RECONNECT ||
            normalizedFacts.signal.kind == ObservationKind.SCREEN_WAKE ||
            normalizedFacts.signal.kind == ObservationKind.USER_PRESENT
        ) {
            lastRealSignal = null
        }
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
        val essentialActiveRoot = normalizedFacts.activeRoot.packageName
            ?.takeIf { normalizedFacts.activeRoot.readState == ForegroundReadState.AVAILABLE }
            ?.takeIf { it in essentialPackages }

        val freshApplicationPackages = normalizedFacts.applicationWindows.packages
            .asSequence()
            .filter { it !in essentialPackages }
            .takeIf {
                normalizedFacts.applicationWindows.freshness == ApplicationWindowsFreshness.FRESH
            }
            .orEmpty()
            .toCollection(LinkedHashSet())

        val applicationWindowPackages = normalizedFacts.applicationWindows.packages
            .asSequence()
            .filter { it !in essentialPackages }
            .toList()

        if (normalizedFacts.displayState == DisplayState.KEYGUARD) {
            return keyguardDeferred()
        }

        if (normalizedFacts.displayState == DisplayState.SCREEN_OFF) {
            return screenOff(eventPackage)
        }

        if (essentialActiveRoot != null && freshApplicationPackages.isNotEmpty()) {
            return ForegroundEvidenceResult(
                outcomes = buildList {
                    freshApplicationPackages.forEach { packageName ->
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
                    repeat(normalizedFacts.applicationWindows.unknownSlotCount) {
                        add(unknownApplicationSlot())
                    }
                }
            )
        }

        if (essentialActiveRoot != null) {
            return deferred(candidatePackage = null)
        }

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
                    freshApplicationPackages
                        .filter { it != activeRootPackage }
                        .forEach { packageName ->
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
                    repeat(normalizedFacts.applicationWindows.unknownSlotCount) {
                        add(unknownApplicationSlot())
                    }
                }
            )
        }

        if (isRecentEvent && normalizedFacts.applicationWindows.unknownSlotCount > 0 &&
            freshApplicationPackages.isNotEmpty()
        ) {
            val recentPackage = requireNotNull(eventPackage)
            return ForegroundEvidenceResult(
                outcomes = buildList {
                    add(
                        ForegroundEvidenceOutcome.Visible(
                            packageName = recentPackage,
                            evidenceBasis = EvidenceBasis.RECENT_REAL_EVENT,
                            sessionEffect = SessionEvidenceEffect.PRESERVE,
                            decisionPermission = DecisionPermission.EVALUATE,
                            evidenceValidity = EvidenceValidity.NotRenewed,
                            followUp = FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
                        )
                    )
                    freshApplicationPackages
                        .filter { it != recentPackage }
                        .forEach { packageName ->
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
                    repeat(normalizedFacts.applicationWindows.unknownSlotCount) {
                        add(unknownApplicationSlot())
                    }
                }
            )
        }

        if (!isRecentEvent &&
            normalizedFacts.applicationWindows.freshness == ApplicationWindowsFreshness.FRESH &&
            freshApplicationPackages.isNotEmpty()
        ) {
            return ForegroundEvidenceResult(
                outcomes = buildList {
                    freshApplicationPackages.forEach { packageName ->
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
                    repeat(normalizedFacts.applicationWindows.unknownSlotCount) {
                        add(unknownApplicationSlot())
                    }
                }
            )
        }

        if (!isRecentEvent &&
            normalizedFacts.applicationWindows.freshness == ApplicationWindowsFreshness.FRESH &&
            applicationWindowPackages.isNotEmpty()
        ) {
            return deferred(eventPackage)
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

    private fun deferred(candidatePackage: String?): ForegroundEvidenceResult =
        ForegroundEvidenceResult(
            outcomes = listOf(
                ForegroundEvidenceOutcome.Unknown(
                    candidatePackage = candidatePackage,
                    evidenceBasis = EvidenceBasis.NO_RELIABLE_EVIDENCE,
                    sessionEffect = SessionEvidenceEffect.PRESERVE,
                    decisionPermission = DecisionPermission.DEFER,
                    evidenceValidity = EvidenceValidity.NotRenewed,
                    followUp = FollowUpKind.WAIT_FOR_RELIABLE_EVIDENCE
                )
            )
        )

    private fun keyguardDeferred(): ForegroundEvidenceResult =
        ForegroundEvidenceResult(
            outcomes = listOf(
                ForegroundEvidenceOutcome.Unknown(
                    candidatePackage = null,
                    evidenceBasis = EvidenceBasis.KEYGUARD,
                    sessionEffect = SessionEvidenceEffect.PRESERVE,
                    decisionPermission = DecisionPermission.DEFER,
                    evidenceValidity = EvidenceValidity.NotRenewed,
                    followUp = FollowUpKind.WAIT_FOR_USER_PRESENT
                )
            )
        )

    private fun screenOff(candidatePackage: String?): ForegroundEvidenceResult =
        if (candidatePackage == null) {
            ForegroundEvidenceResult(emptyList())
        } else {
            ForegroundEvidenceResult(
                outcomes = listOf(
                    ForegroundEvidenceOutcome.NotVisible(
                        packageName = candidatePackage,
                        evidenceBasis = EvidenceBasis.SCREEN_OFF,
                        sessionEffect = SessionEvidenceEffect.END_WITHOUT_RENEWAL,
                        decisionPermission = DecisionPermission.DO_NOT_EVALUATE,
                        evidenceValidity = EvidenceValidity.NotRenewed,
                        followUp = FollowUpKind.NONE
                    )
                )
            )
        }

    private fun unknownApplicationSlot(): ForegroundEvidenceOutcome.Unknown =
        ForegroundEvidenceOutcome.Unknown(
            candidatePackage = null,
            evidenceBasis = EvidenceBasis.NO_RELIABLE_EVIDENCE,
            sessionEffect = SessionEvidenceEffect.PRESERVE,
            decisionPermission = DecisionPermission.DEFER,
            evidenceValidity = EvidenceValidity.NotRenewed,
            followUp = FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
        )
}
