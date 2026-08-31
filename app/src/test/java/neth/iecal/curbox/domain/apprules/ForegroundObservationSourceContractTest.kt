package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Test
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class ForegroundObservationSourceContractTest {
    @Test
    fun productionAndDeterministicSourcesShareTheRecentEventContract() {
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
            activeRoot = ActiveRootFact(readState = ForegroundReadState.EMPTY),
            applicationWindows = ApplicationWindowsFact(readState = ForegroundReadState.EMPTY),
            displayState = DisplayState.UNLOCKED
        )
        val sources = listOf<ForegroundObservationSource>(
            DeterministicForegroundObservationSource(facts),
            AndroidForegroundObservationSource(
                service = EmptyAccessibilityService(),
                wallClockMs = { facts.capturedAtWallMs },
                elapsedRealtimeMs = { facts.capturedAtElapsedMs },
                displayStateProvider = { facts.displayState }
            )
        )
        val trigger = ObservationTrigger(
            sourceOrderIdentity = SourceOrderIdentity(10L),
            kind = ObservationKind.REAL_EVENT,
            eventPackage = targetPackage,
            requestedAtWallMs = 1_000L,
            requestedAtElapsedMs = 10_000L
        )

        sources.forEach { source ->
            val visible = ForegroundEvidenceModule()
                .classify(source.capture(trigger), ForegroundEvidencePolicySnapshot())
                .outcomes
                .single() as ForegroundEvidenceOutcome.Visible

            assertEquals(targetPackage, visible.packageName)
            assertEquals(EvidenceBasis.RECENT_REAL_EVENT, visible.evidenceBasis)
            assertEquals(SessionEvidenceEffect.PRESERVE, visible.sessionEffect)
            assertEquals(DecisionPermission.EVALUATE, visible.decisionPermission)
            assertEquals(EvidenceValidity.NotRenewed, visible.evidenceValidity)
            assertEquals(FollowUpKind.RETRY_FOR_RELIABLE_EVIDENCE, visible.followUp)
        }
    }

    private class EmptyAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

        override fun onInterrupt() = Unit

        override fun getRootInActiveWindow() = null

        override fun getWindows(): MutableList<AccessibilityWindowInfo> = mutableListOf()
    }
}
