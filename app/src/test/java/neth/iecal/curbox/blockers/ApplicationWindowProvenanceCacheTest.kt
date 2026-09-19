package neth.iecal.curbox.blockers

import neth.iecal.curbox.domain.apprules.ApplicationWindowProvenanceCache
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFact
import neth.iecal.curbox.domain.apprules.ApplicationWindowsFreshness
import neth.iecal.curbox.domain.apprules.ForegroundReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationWindowProvenanceCacheTest {
    @Test
    fun partialReadKeepsCurrentPackagesFreshAndSeparatesCachedPackagesAsStale() {
        val cache = ApplicationWindowProvenanceCache()
        cache.resolve(resolvedOther(), capturedAtElapsedMs = 1_000L)

        val result = cache.resolve(
            snapshot(
                packages = setOf("com.example.current"),
                hasUnknown = true,
                unknownSlotCount = 1
            ),
            capturedAtElapsedMs = 7_000L
        )

        assertEquals(setOf("com.example.current"), result.packages)
        assertEquals(setOf("com.example.other"), result.stalePackages)
        assertEquals(ApplicationWindowsFreshness.FRESH, result.freshness)
        assertEquals(1, result.unknownSlotCount)
        assertEquals(ForegroundReadState.AVAILABLE, result.readState)
    }

    @Test
    fun uncertainReadsSeparateCurrentPackagesFromCarriedStalePackages() {
        val uncertainReads = listOf(
            snapshot(packages = emptySet(), hasUnknown = true, unknownSlotCount = 1),
            snapshot(packages = emptySet(), hasUnknown = true, unknownSlotCount = 1),
            snapshot(
                packages = setOf("com.example.partial"),
                hasUnknown = true,
                unknownSlotCount = 1
            ),
            snapshot(packages = emptySet(), hasUnknown = true, providerFailed = true)
        )

        uncertainReads.forEach { uncertain ->
            val cache = ApplicationWindowProvenanceCache()
            cache.resolve(resolvedOther(), capturedAtElapsedMs = 1_000L)

            val result = cache.resolve(uncertain, capturedAtElapsedMs = 7_000L)

            assertEquals(uncertain.packages, result.packages)
            assertEquals(setOf("com.example.other") - uncertain.packages, result.stalePackages)
            assertEquals(
                if (uncertain.packages.isEmpty()) {
                    ApplicationWindowsFreshness.STALE
                } else {
                    ApplicationWindowsFreshness.FRESH
                },
                result.freshness
            )
            assertEquals(uncertain.unknownSlotCount, result.unknownSlotCount)
            assertEquals(uncertain.readState, result.readState)
        }
    }

    @Test
    fun repeatedResolvedReadsRemainFreshRegardlessOfElapsedTime() {
        val cache = ApplicationWindowProvenanceCache()
        cache.resolve(resolvedOther(), capturedAtElapsedMs = 1_000L)

        val result = cache.resolve(resolvedOther(), capturedAtElapsedMs = 60_000L)

        assertEquals(ApplicationWindowsFreshness.FRESH, result.freshness)
        assertEquals(setOf("com.example.other"), result.packages)
        assertTrue(result.stalePackages.isEmpty())
    }

    @Test
    fun lifecycleClearDropsCachedPackages() {
        val cache = ApplicationWindowProvenanceCache()
        cache.resolve(resolvedOther(), capturedAtElapsedMs = 1_000L)

        cache.clear()
        val result = cache.resolve(
            snapshot(packages = emptySet(), hasUnknown = true, unknownSlotCount = 1),
            capturedAtElapsedMs = 7_000L
        )

        assertEquals(ApplicationWindowsFreshness.FRESH, result.freshness)
        assertTrue(result.packages.isEmpty())
        assertTrue(result.stalePackages.isEmpty())
    }

    private fun resolvedOther() = snapshot(
        packages = setOf("com.example.other"),
        hasUnknown = false,
        unknownSlotCount = 0
    )

    private fun snapshot(
        packages: Set<String> = emptySet(),
        hasUnknown: Boolean = false,
        unknownSlotCount: Int = if (hasUnknown) 1 else 0,
        providerFailed: Boolean = false
    ) = ApplicationWindowsFact(
        packages = packages,
        unknownSlotCount = unknownSlotCount,
        readState = when {
            providerFailed -> ForegroundReadState.FAILED
            packages.isEmpty() && unknownSlotCount == 0 -> ForegroundReadState.EMPTY
            else -> ForegroundReadState.AVAILABLE
        },
        freshness = ApplicationWindowsFreshness.FRESH
    )
}
