package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ForegroundEvidenceContractTest {
    @Test
    fun consecutiveRealEventAndRootTransitionEndsPreviousCandidateBeforeNewVisible() {
        val previousPackage = "com.example.reader"
        val activePackage = "com.example.calendar"
        val module = ForegroundEvidenceModule()

        module.classify(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 1_000L,
                signal = SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = previousPackage,
                    eventWallMs = 900L,
                    eventElapsedMs = 900L
                ),
                activeRoot = ActiveRootFact(
                    packageName = previousPackage,
                    readState = ForegroundReadState.AVAILABLE
                )
            ),
            ForegroundEvidencePolicySnapshot()
        )

        val result = module.classify(
            ForegroundFacts(
                capturedAtWallMs = 2_000L,
                capturedAtElapsedMs = 2_000L,
                signal = SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = activePackage,
                    eventWallMs = 1_900L,
                    eventElapsedMs = 1_900L
                ),
                activeRoot = ActiveRootFact(
                    packageName = activePackage,
                    readState = ForegroundReadState.AVAILABLE
                )
            ),
            ForegroundEvidencePolicySnapshot()
        )

        assertEquals(
            listOf(
                ForegroundEvidenceOutcome.NotVisible(
                    packageName = previousPackage,
                    evidenceBasis = EvidenceBasis.DIFFERENT_ACTIVE_ROOT,
                    sessionEffect = SessionEvidenceEffect.END_WITHOUT_RENEWAL,
                    decisionPermission = DecisionPermission.DO_NOT_EVALUATE,
                    evidenceValidity = EvidenceValidity.NotRenewed,
                    followUp = FollowUpKind.NONE
                ),
                ForegroundEvidenceOutcome.Visible(
                    packageName = activePackage,
                    evidenceBasis = EvidenceBasis.REAL_EVENT_AND_ACTIVE_ROOT,
                    sessionEffect = SessionEvidenceEffect.RENEW,
                    decisionPermission = DecisionPermission.EVALUATE,
                    evidenceValidity = EvidenceValidity.RenewedUntil(7_000L),
                    followUp = FollowUpKind.NONE
                )
            ),
            result.outcomes
        )
    }

    @Test
    fun differentActiveRootOnSyntheticRecheckEndsTheLastObservedCandidate() {
        val previousPackage = "com.example.reader"
        val activePackage = "com.example.calendar"
        val module = ForegroundEvidenceModule()

        module.classify(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 1_000L,
                signal = SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = previousPackage,
                    eventWallMs = 900L,
                    eventElapsedMs = 900L
                ),
                activeRoot = ActiveRootFact(
                    packageName = previousPackage,
                    readState = ForegroundReadState.AVAILABLE
                )
            ),
            ForegroundEvidencePolicySnapshot()
        )

        val result = module.classify(
            ForegroundFacts(
                capturedAtWallMs = 2_000L,
                capturedAtElapsedMs = 2_000L,
                signal = SignalFact(kind = ObservationKind.SYNTHETIC_RECHECK),
                activeRoot = ActiveRootFact(
                    packageName = activePackage,
                    readState = ForegroundReadState.AVAILABLE
                )
            ),
            ForegroundEvidencePolicySnapshot()
        )

        assertEquals(
            listOf(
                ForegroundEvidenceOutcome.NotVisible(
                    packageName = previousPackage,
                    evidenceBasis = EvidenceBasis.DIFFERENT_ACTIVE_ROOT,
                    sessionEffect = SessionEvidenceEffect.END_WITHOUT_RENEWAL,
                    decisionPermission = DecisionPermission.DO_NOT_EVALUATE,
                    evidenceValidity = EvidenceValidity.NotRenewed,
                    followUp = FollowUpKind.NONE
                ),
                ForegroundEvidenceOutcome.Visible(
                    packageName = activePackage,
                    evidenceBasis = EvidenceBasis.ACTIVE_ROOT,
                    sessionEffect = SessionEvidenceEffect.RENEW,
                    decisionPermission = DecisionPermission.EVALUATE,
                    evidenceValidity = EvidenceValidity.RenewedUntil(7_000L),
                    followUp = FollowUpKind.NONE
                )
            ),
            result.outcomes
        )
    }

    @Test
    fun deterministicSourceDoesNotExposeMutablePackageSets() {
        val captured = DeterministicForegroundObservationSource(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 10_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = linkedSetOf("com.example.reader"),
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        ).capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                kind = ObservationKind.SYNTHETIC_RECHECK,
                requestedAtWallMs = 1_000L,
                requestedAtElapsedMs = 10_000L
            )
        )

        try {
            @Suppress("UNCHECKED_CAST")
            (captured.applicationWindows.packages as MutableSet<String>) += "com.example.calendar"
            fail("the source must return an immutable package set")
        } catch (_: UnsupportedOperationException) {
            // Expected: callers cannot mutate a captured value after the source seam.
        }
    }

    @Test
    fun applicationWindowsPackagesAreSnapshottedAtConstruction() {
        val packages = linkedSetOf("com.example.reader")
        val fact = ApplicationWindowsFact(packages = packages)

        packages += "com.example.calendar"

        assertEquals(setOf("com.example.reader"), fact.packages)
        try {
            @Suppress("UNCHECKED_CAST")
            (fact.packages as MutableSet<String>) += "com.example.mail"
            fail("the fact must expose an immutable package set")
        } catch (_: UnsupportedOperationException) {
            // Expected: the public fact owns its package snapshot.
        }
    }

    @Test
    fun essentialPackagesAreSnapshottedAtConstruction() {
        val essentialPackages = linkedSetOf("com.android.systemui")
        val policy = ForegroundEvidencePolicySnapshot(essentialPackages = essentialPackages)

        essentialPackages += "com.example.guardian"

        assertEquals(setOf("com.android.systemui"), policy.essentialPackages)
        try {
            @Suppress("UNCHECKED_CAST")
            (policy.essentialPackages as MutableSet<String>) += "com.example.overlay"
            fail("the policy must expose an immutable package set")
        } catch (_: UnsupportedOperationException) {
            // Expected: the public policy owns its package snapshot.
        }
    }

    @Test
    fun evidenceOutcomesAreSnapshottedAtConstruction() {
        val outcome = ForegroundEvidenceOutcome.Unknown(
            candidatePackage = null,
            evidenceBasis = EvidenceBasis.NO_RELIABLE_EVIDENCE,
            sessionEffect = SessionEvidenceEffect.PRESERVE,
            decisionPermission = DecisionPermission.DEFER,
            evidenceValidity = EvidenceValidity.NotRenewed,
            followUp = FollowUpKind.WAIT_FOR_RELIABLE_EVIDENCE
        )
        val outcomes = mutableListOf<ForegroundEvidenceOutcome>(outcome)
        val result = ForegroundEvidenceResult(outcomes)

        outcomes.clear()

        assertEquals(listOf(outcome), result.outcomes)
        try {
            @Suppress("UNCHECKED_CAST")
            (result.outcomes as MutableList<ForegroundEvidenceOutcome>).clear()
            fail("the result must expose an immutable outcome list")
        } catch (_: UnsupportedOperationException) {
            // Expected: the public result owns its outcome snapshot.
        }
    }

    @Test
    fun recentEventAndSameActiveRootAreOneVisibleRenewedCandidate() {
        val targetPackage = "com.example.reader"
        val result = ForegroundEvidenceModule().classify(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 10_000L,
                signal = SignalFact(
                    kind = ObservationKind.REAL_EVENT,
                    eventPackage = targetPackage,
                    eventWallMs = 900L,
                    eventElapsedMs = 9_000L
                ),
                activeRoot = ActiveRootFact(
                    packageName = targetPackage,
                    readState = ForegroundReadState.AVAILABLE
                ),
                applicationWindows = ApplicationWindowsFact(
                    readState = ForegroundReadState.EMPTY
                ),
                displayState = DisplayState.UNLOCKED
            ),
            ForegroundEvidencePolicySnapshot()
        )

        val visible = result.outcomes.single() as ForegroundEvidenceOutcome.Visible
        assertEquals(targetPackage, visible.packageName)
        assertEquals(EvidenceBasis.REAL_EVENT_AND_ACTIVE_ROOT, visible.evidenceBasis)
        assertEquals(SessionEvidenceEffect.RENEW, visible.sessionEffect)
        assertEquals(DecisionPermission.EVALUATE, visible.decisionPermission)
        assertEquals(
            EvidenceValidity.RenewedUntil(15_000L),
            visible.evidenceValidity
        )
        assertEquals(FollowUpKind.NONE, visible.followUp)
    }

    @Test
    fun recentRealEventWithoutReliableRootRemainsVisibleWithoutRenewingEvidence() {
        val targetPackage = "com.example.reader"
        val facts = ForegroundFacts(
            capturedAtWallMs = 1_000L,
            capturedAtElapsedMs = 10_000L,
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = targetPackage,
                eventWallMs = 900L,
                eventElapsedMs = 9_000L
            ),
            activeRoot = ActiveRootFact(
                packageName = null,
                readState = ForegroundReadState.EMPTY
            ),
            applicationWindows = ApplicationWindowsFact(
                packages = emptySet(),
                unknownSlotCount = 0,
                readState = ForegroundReadState.EMPTY,
                freshness = ApplicationWindowsFreshness.FRESH
            ),
            displayState = DisplayState.UNLOCKED
        )
        val source = DeterministicForegroundObservationSource(facts)
        val trigger = ObservationTrigger(
            sourceOrderIdentity = SourceOrderIdentity(1L),
            kind = ObservationKind.REAL_EVENT,
            eventPackage = targetPackage,
            requestedAtWallMs = 1_000L,
            requestedAtElapsedMs = 10_000L
        )

        val result = ForegroundEvidenceModule().classify(
            source.capture(trigger),
            ForegroundEvidencePolicySnapshot()
        )

        val visible = result.outcomes.single() as ForegroundEvidenceOutcome.Visible
        assertEquals(targetPackage, visible.packageName)
        assertEquals(EvidenceBasis.RECENT_REAL_EVENT, visible.evidenceBasis)
        assertEquals(SessionEvidenceEffect.PRESERVE, visible.sessionEffect)
        assertEquals(DecisionPermission.EVALUATE, visible.decisionPermission)
        assertEquals(EvidenceValidity.NotRenewed, visible.evidenceValidity)
        assertEquals(FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE, visible.followUp)
    }

    @Test
    fun obviousNonessentialActiveRootIsVisibleAndRenewsIntrinsicEvidence() {
        val targetPackage = "com.example.reader"
        val facts = ForegroundFacts(
            capturedAtWallMs = 2_000L,
            capturedAtElapsedMs = 10_000L,
            signal = SignalFact(kind = ObservationKind.SYNTHETIC_RECHECK),
            activeRoot = ActiveRootFact(
                packageName = targetPackage,
                readState = ForegroundReadState.AVAILABLE
            ),
            applicationWindows = ApplicationWindowsFact(
                readState = ForegroundReadState.EMPTY
            ),
            displayState = DisplayState.UNLOCKED
        )
        val source = DeterministicForegroundObservationSource(facts)
        val trigger = ObservationTrigger(
            sourceOrderIdentity = SourceOrderIdentity(2L),
            kind = ObservationKind.SYNTHETIC_RECHECK,
            requestedAtWallMs = 2_000L,
            requestedAtElapsedMs = 10_000L
        )

        val result = ForegroundEvidenceModule().classify(
            source.capture(trigger),
            ForegroundEvidencePolicySnapshot()
        )

        val visible = result.outcomes.single() as ForegroundEvidenceOutcome.Visible
        assertEquals(targetPackage, visible.packageName)
        assertEquals(EvidenceBasis.ACTIVE_ROOT, visible.evidenceBasis)
        assertEquals(SessionEvidenceEffect.RENEW, visible.sessionEffect)
        assertEquals(DecisionPermission.EVALUATE, visible.decisionPermission)
        assertEquals(
            EvidenceValidity.RenewedUntil(15_000L),
            visible.evidenceValidity
        )
        assertEquals(FollowUpKind.NONE, visible.followUp)
    }

    @Test
    fun differentNonessentialActiveRootEndsThePreviousCandidateBeforeEvaluatingTheNewOne() {
        val previousPackage = "com.example.reader"
        val activePackage = "com.example.calendar"
        val facts = ForegroundFacts(
            capturedAtWallMs = 2_000L,
            capturedAtElapsedMs = 10_000L,
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = previousPackage,
                eventWallMs = 100L,
                eventElapsedMs = 1_000L
            ),
            activeRoot = ActiveRootFact(
                packageName = activePackage,
                readState = ForegroundReadState.AVAILABLE
            ),
            applicationWindows = ApplicationWindowsFact(
                readState = ForegroundReadState.EMPTY
            ),
            displayState = DisplayState.UNLOCKED
        )
        val source = DeterministicForegroundObservationSource(facts)
        val trigger = ObservationTrigger(
            sourceOrderIdentity = SourceOrderIdentity(3L),
            kind = ObservationKind.REAL_EVENT,
            eventPackage = previousPackage,
            requestedAtWallMs = 2_000L,
            requestedAtElapsedMs = 10_000L
        )

        val result = ForegroundEvidenceModule().classify(
            source.capture(trigger),
            ForegroundEvidencePolicySnapshot()
        )

        assertEquals(2, result.outcomes.size)
        val ended = result.outcomes[0] as ForegroundEvidenceOutcome.NotVisible
        assertEquals(previousPackage, ended.packageName)
        assertEquals(EvidenceBasis.DIFFERENT_ACTIVE_ROOT, ended.evidenceBasis)
        assertEquals(SessionEvidenceEffect.END_WITHOUT_RENEWAL, ended.sessionEffect)
        assertEquals(DecisionPermission.DO_NOT_EVALUATE, ended.decisionPermission)
        assertEquals(EvidenceValidity.NotRenewed, ended.evidenceValidity)
        assertEquals(FollowUpKind.NONE, ended.followUp)

        val visible = result.outcomes[1] as ForegroundEvidenceOutcome.Visible
        assertEquals(activePackage, visible.packageName)
        assertEquals(EvidenceBasis.ACTIVE_ROOT, visible.evidenceBasis)
        assertEquals(SessionEvidenceEffect.RENEW, visible.sessionEffect)
        assertEquals(DecisionPermission.EVALUATE, visible.decisionPermission)
        assertEquals(EvidenceValidity.RenewedUntil(15_000L), visible.evidenceValidity)
        assertEquals(FollowUpKind.NONE, visible.followUp)
    }
}
