package neth.iecal.curbox.domain.apprules

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundObservationSourceContractInstrumentedTest {
    @Test
    fun recentEventWithoutReliableRootIsPreservedByBothAdapters() {
        val targetPackage = "com.example.reader"
        val facts = facts(
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = targetPackage,
                eventWallMs = 900L,
                eventElapsedMs = 9_000L
            )
        )

        sourcePair(facts, rootPackage = null).forEach { source ->
            val result = ForegroundEvidenceModule().classify(
                source.capture(trigger(targetPackage)),
                ForegroundEvidencePolicySnapshot()
            )

            assertEquals(
                listOf(
                    ForegroundEvidenceOutcome.Visible(
                        packageName = targetPackage,
                        evidenceBasis = EvidenceBasis.RECENT_REAL_EVENT,
                        sessionEffect = SessionEvidenceEffect.PRESERVE,
                        decisionPermission = DecisionPermission.EVALUATE,
                        evidenceValidity = EvidenceValidity.NotRenewed,
                        followUp = FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE
                    )
                ),
                result.outcomes
            )
        }
    }

    @Test
    fun obviousTargetRootIsRenewedByBothAdapters() {
        val targetPackage = "com.example.reader"
        val facts = facts(
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = targetPackage,
                eventWallMs = 900L,
                eventElapsedMs = 9_000L
            ),
            rootPackage = targetPackage
        )

        sourcePair(facts, rootPackage = targetPackage).forEach { source ->
            val visible = ForegroundEvidenceModule()
                .classify(source.capture(trigger(targetPackage)), ForegroundEvidencePolicySnapshot())
                .outcomes
                .single() as ForegroundEvidenceOutcome.Visible

            assertEquals(targetPackage, visible.packageName)
            assertEquals(EvidenceBasis.REAL_EVENT_AND_ACTIVE_ROOT, visible.evidenceBasis)
            assertEquals(SessionEvidenceEffect.RENEW, visible.sessionEffect)
            assertEquals(DecisionPermission.EVALUATE, visible.decisionPermission)
            assertEquals(EvidenceValidity.RenewedUntil(15_000L), visible.evidenceValidity)
            assertEquals(FollowUpKind.NONE, visible.followUp)
        }
    }

    @Test
    fun definiteDifferentActiveRootEndsPreviousCandidateInBothAdapters() {
        val previousPackage = "com.example.reader"
        val activePackage = "com.example.calendar"
        val facts = facts(
            signal = SignalFact(
                kind = ObservationKind.REAL_EVENT,
                eventPackage = previousPackage,
                eventWallMs = 100L,
                eventElapsedMs = 1_000L
            ),
            rootPackage = activePackage
        )

        sourcePair(facts, rootPackage = activePackage).forEach { source ->
            val result = ForegroundEvidenceModule().classify(
                source.capture(trigger(previousPackage)),
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
                        evidenceValidity = EvidenceValidity.RenewedUntil(15_000L),
                        followUp = FollowUpKind.NONE
                    )
                ),
                result.outcomes
            )
        }
    }

    private fun sourcePair(
        facts: ForegroundFacts,
        rootPackage: String?
    ): List<ForegroundObservationSource> {
        val service = FakeAccessibilityService(rootPackage)
        val production = AndroidForegroundObservationSource(
            service = service,
            wallClockMs = { facts.capturedAtWallMs },
            elapsedRealtimeMs = { facts.capturedAtElapsedMs },
            displayStateProvider = { facts.displayState }
        )
        return listOf(DeterministicForegroundObservationSource(facts), production)
    }

    private fun facts(
        signal: SignalFact,
        rootPackage: String? = null
    ): ForegroundFacts = ForegroundFacts(
        capturedAtWallMs = 1_000L,
        capturedAtElapsedMs = 10_000L,
        signal = signal,
        activeRoot = ActiveRootFact(
            packageName = rootPackage,
            readState = if (rootPackage == null) {
                ForegroundReadState.EMPTY
            } else {
                ForegroundReadState.AVAILABLE
            }
        ),
        applicationWindows = ApplicationWindowsFact(readState = ForegroundReadState.EMPTY),
        displayState = DisplayState.UNLOCKED
    )

    private fun trigger(eventPackage: String): ObservationTrigger = ObservationTrigger(
        sourceOrderIdentity = SourceOrderIdentity(10L),
        kind = ObservationKind.REAL_EVENT,
        eventPackage = eventPackage,
        requestedAtWallMs = 1_000L,
        requestedAtElapsedMs = 10_000L
    )

    private class FakeAccessibilityService(
        private val rootPackage: String?
    ) : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

        override fun onInterrupt() = Unit

        override fun getRootInActiveWindow(): AccessibilityNodeInfo? = rootPackage?.let {
            AccessibilityNodeInfo.obtain().apply { packageName = it }
        }

        override fun getWindows(): MutableList<AccessibilityWindowInfo> = mutableListOf()
    }
}
