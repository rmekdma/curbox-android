package neth.iecal.curbox.domain.apprules

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ForegroundObservationSourceContractInstrumentedTest {
    @Test
    fun defaultErrorReporterPersistsProviderFailuresThroughCrashLogger() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val service = AttachedThrowingAccessibilityService().apply {
            attachTo(targetContext)
        }
        val logFile = File(targetContext.filesDir, "crash_log.txt")
        val existingLog = logFile.takeIf(File::exists)?.readBytes()
        val callbackEvent = callbackEvent("com.example.reader", eventTime = 9_000L)

        try {
            AndroidForegroundObservationSource(
                service = service,
                wallClockMs = { 1_000L },
                elapsedRealtimeMs = { 10_000L },
                displayStateProvider = { DisplayState.UNLOCKED }
            ).captureEvent(callbackEvent, trigger("com.example.reader"))

            assertTrue(logFile.exists())
            assertTrue(logFile.readText().contains("Non-Fatal Error"))
        } finally {
            callbackEvent.recycle()
            if (existingLog == null) {
                logFile.delete()
            } else {
                logFile.writeBytes(existingLog)
            }
        }
    }

    @Test
    fun captureEventUsesCopiedValuesAndLeavesCallbackEventWithItsCaller() {
        val targetPackage = "com.example.reader"
        val callbackEvent = callbackEvent(targetPackage, eventTime = 9_000L)
        val source = AndroidForegroundObservationSource(
            service = FakeAccessibilityService(rootPackage = null),
            wallClockMs = { 1_000L },
            elapsedRealtimeMs = { 10_000L },
            displayStateProvider = { DisplayState.UNLOCKED }
        )

        val captured = source.captureEvent(callbackEvent, trigger(targetPackage))

        assertEquals(targetPackage, captured.signal.eventPackage)
        assertEquals(9_000L, captured.signal.eventElapsedMs)
        assertEquals(targetPackage, callbackEvent.packageName)
        assertEquals(9_000L, callbackEvent.eventTime)

        /*
         * AccessibilityEvent is a final pooled framework type. It has no public recycled-state
         * query or injectable recycle hook, so recycle failure and exact pool counts cannot be
         * observed here. The closest ownership invariant is that captureEvent reads a copied
         * value and leaves the callback event owned by this test, which is recycled exactly once
         * below. Production's finally block owns the copy on every path.
         */
        callbackEvent.recycle()
    }

    @Test
    fun captureEventReportsProviderFailuresAfterCopyAndReturnsFailedFacts() {
        val targetPackage = "com.example.reader"
        val callbackEvent = callbackEvent(targetPackage, eventTime = 9_000L)
        val reported = mutableListOf<Throwable>()
        val source = AndroidForegroundObservationSource(
            service = ThrowingAccessibilityService(),
            wallClockMs = { 1_000L },
            elapsedRealtimeMs = { 10_000L },
            displayStateProvider = { throw IllegalStateException("display provider failed") },
            onNonFatalError = { reported += it }
        )

        val captured = source.captureEvent(callbackEvent, trigger(targetPackage))

        assertEquals(ForegroundReadState.FAILED, captured.activeRoot.readState)
        assertEquals(ForegroundReadState.FAILED, captured.applicationWindows.readState)
        assertEquals(DisplayState.UNLOCKED, captured.displayState)
        assertEquals(targetPackage, captured.signal.eventPackage)
        assertEquals(9_000L, captured.signal.eventElapsedMs)
        assertEquals(3, reported.size)
        assertEquals(targetPackage, callbackEvent.packageName)
        callbackEvent.recycle()
    }

    @Test
    fun captureEventContainsReporterCallbackFailures() {
        val callbackEvent = callbackEvent("com.example.reader", eventTime = 9_000L)
        val source = AndroidForegroundObservationSource(
            service = ThrowingAccessibilityService(),
            wallClockMs = { throw IllegalStateException("wall clock failed") },
            elapsedRealtimeMs = { throw IllegalStateException("elapsed clock failed") },
            displayStateProvider = { throw IllegalStateException("display provider failed") },
            onNonFatalError = { throw AssertionError("reporter failed") }
        )

        val captured = source.captureEvent(
            callbackEvent,
            trigger("com.example.reader")
        )

        assertEquals(1_000L, captured.capturedAtWallMs)
        assertEquals(10_000L, captured.capturedAtElapsedMs)
        assertEquals(ForegroundReadState.FAILED, captured.activeRoot.readState)
        assertEquals(ForegroundReadState.FAILED, captured.applicationWindows.readState)
        callbackEvent.recycle()
    }

    @Test
    fun captureEventWithoutFrameworkEventUsesTriggerAndDoesNotCreateAnOwnedCopy() {
        val targetPackage = "com.example.reader"
        val source = AndroidForegroundObservationSource(
            service = FakeAccessibilityService(rootPackage = null),
            wallClockMs = { 1_000L },
            elapsedRealtimeMs = { 10_000L },
            displayStateProvider = { DisplayState.UNLOCKED }
        )

        val captured = source.captureEvent(null, trigger(targetPackage))

        assertEquals(targetPackage, captured.signal.eventPackage)
        assertEquals(10_000L, captured.signal.eventElapsedMs)
        assertEquals(ForegroundReadState.EMPTY, captured.activeRoot.readState)
    }

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

    private fun callbackEvent(packageName: String, eventTime: Long): AccessibilityEvent =
        AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            this.packageName = packageName
            this.eventTime = eventTime
        }

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

    private class ThrowingAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

        override fun onInterrupt() = Unit

        override fun getRootInActiveWindow(): AccessibilityNodeInfo? =
            throw IllegalStateException("root provider failed")

        override fun getWindows(): MutableList<AccessibilityWindowInfo> =
            throw IllegalStateException("windows provider failed")
    }

    private class AttachedThrowingAccessibilityService : AccessibilityService() {
        fun attachTo(context: Context) {
            attachBaseContext(context)
        }

        override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

        override fun onInterrupt() = Unit

        override fun getRootInActiveWindow(): AccessibilityNodeInfo? =
            throw IllegalStateException("root provider failed")

        override fun getWindows(): MutableList<AccessibilityWindowInfo> =
            throw IllegalStateException("windows provider failed")
    }
}
