package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AndroidForegroundObservationSourceProvenanceTest {

    @Test
    fun captureCachesResolvedWindowsAndCarriesThemAsStaleOnUncertainRead() {
        val factsToReturn = AtomicReference<ForegroundFacts>()
        val source = AndroidForegroundObservationSource(
            factProvider = { factsToReturn.get() },
            errorReporter = {}
        )

        // 1. Resolved non-empty window read
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 10_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = setOf("com.example.target"),
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        val firstResult = source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                kind = ObservationKind.REAL_EVENT,
                requestedAtWallMs = 1_000L,
                requestedAtElapsedMs = 10_000L
            )
        )
        assertEquals(setOf("com.example.target"), firstResult.applicationWindows.packages)
        assertTrue(firstResult.applicationWindows.stalePackages.isEmpty())
        assertEquals(ApplicationWindowsFreshness.FRESH, firstResult.applicationWindows.freshness)

        // 2. Uncertain read (unknownSlotCount > 0) carries previous packages as stale
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 2_000L,
                capturedAtElapsedMs = 15_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = emptySet(),
                    unknownSlotCount = 1,
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        val secondResult = source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(2L),
                kind = ObservationKind.SYNTHETIC_RECHECK,
                requestedAtWallMs = 2_000L,
                requestedAtElapsedMs = 15_000L
            )
        )
        assertTrue(secondResult.applicationWindows.packages.isEmpty())
        assertEquals(setOf("com.example.target"), secondResult.applicationWindows.stalePackages)
        assertEquals(ApplicationWindowsFreshness.STALE, secondResult.applicationWindows.freshness)
    }

    @Test
    fun captureInvalidatesCacheOnReconnectTrigger() {
        val factsToReturn = AtomicReference<ForegroundFacts>()
        val source = AndroidForegroundObservationSource(
            factProvider = { factsToReturn.get() },
            errorReporter = {}
        )

        // 1. Resolved read
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 10_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = setOf("com.example.target"),
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                kind = ObservationKind.REAL_EVENT,
                requestedAtWallMs = 1_000L,
                requestedAtElapsedMs = 10_000L
            )
        )

        // 2. RECONNECT trigger with uncertain read -> cache must be invalidated, no stale packages
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 2_000L,
                capturedAtElapsedMs = 15_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = emptySet(),
                    unknownSlotCount = 1,
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        val reconnectResult = source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(2L),
                kind = ObservationKind.RECONNECT,
                requestedAtWallMs = 2_000L,
                requestedAtElapsedMs = 15_000L
            )
        )
        assertTrue(reconnectResult.applicationWindows.stalePackages.isEmpty())
    }

    @Test
    fun captureInvalidatesCacheOnScreenOffTrigger() {
        val factsToReturn = AtomicReference<ForegroundFacts>()
        val source = AndroidForegroundObservationSource(
            factProvider = { factsToReturn.get() },
            errorReporter = {}
        )

        // 1. Resolved read
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 10_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = setOf("com.example.target"),
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                kind = ObservationKind.REAL_EVENT,
                requestedAtWallMs = 1_000L,
                requestedAtElapsedMs = 10_000L
            )
        )

        // 2. SCREEN_OFF trigger -> cache invalidated, returns SCREEN_OFF facts
        val screenOffResult = source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(2L),
                kind = ObservationKind.SCREEN_OFF,
                eventPackage = "com.example.target",
                requestedAtWallMs = 2_000L,
                requestedAtElapsedMs = 15_000L
            )
        )
        assertEquals(DisplayState.SCREEN_OFF, screenOffResult.displayState)
        assertTrue(screenOffResult.applicationWindows.stalePackages.isEmpty())
        assertTrue(screenOffResult.applicationWindows.packages.isEmpty())

        // 3. Subsequent uncertain read after screen off does not carry previous stale packages
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 3_000L,
                capturedAtElapsedMs = 20_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = emptySet(),
                    unknownSlotCount = 1,
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        val postScreenOffResult = source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(3L),
                kind = ObservationKind.SCREEN_WAKE,
                requestedAtWallMs = 3_000L,
                requestedAtElapsedMs = 20_000L
            )
        )
        assertTrue(postScreenOffResult.applicationWindows.stalePackages.isEmpty())
    }

    @Test
    fun captureInvalidatesCacheOnRefreshTrigger() {
        val factsToReturn = AtomicReference<ForegroundFacts>()
        val source = AndroidForegroundObservationSource(
            factProvider = { factsToReturn.get() },
            errorReporter = {}
        )

        // 1. Resolved read
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 1_000L,
                capturedAtElapsedMs = 10_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = setOf("com.example.target"),
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(1L),
                kind = ObservationKind.REAL_EVENT,
                requestedAtWallMs = 1_000L,
                requestedAtElapsedMs = 10_000L
            )
        )

        // 2. REFRESH trigger with uncertain read -> cache invalidated, no stale packages
        factsToReturn.set(
            ForegroundFacts(
                capturedAtWallMs = 2_000L,
                capturedAtElapsedMs = 15_000L,
                applicationWindows = ApplicationWindowsFact(
                    packages = emptySet(),
                    unknownSlotCount = 1,
                    readState = ForegroundReadState.AVAILABLE
                )
            )
        )
        val refreshResult = source.capture(
            ObservationTrigger(
                sourceOrderIdentity = SourceOrderIdentity(2L),
                kind = ObservationKind.REFRESH,
                requestedAtWallMs = 2_000L,
                requestedAtElapsedMs = 15_000L
            )
        )
        assertTrue(refreshResult.applicationWindows.stalePackages.isEmpty())
    }
}
