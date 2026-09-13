package neth.iecal.curbox.blockers

import neth.iecal.curbox.domain.apprules.ApplicationWindowsFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                hasApplicationWindow = true,
                hasUnknown = true,
                count = 2
            ),
            capturedAtElapsedMs = 7_000L
        )

        assertEquals(setOf("com.example.current"), result.packages)
        assertEquals(setOf("com.example.other"), result.stalePackages)
        assertEquals(ApplicationWindowsFreshness.FRESH, result.freshness)
        assertEquals(1_000L, result.capturedAtElapsedMs)
        assertTrue(result.hasApplicationWindow)
        assertTrue(result.hasUnknownApplicationWindow)
        assertFalse(result.providerFailed)
        assertEquals(2, result.applicationWindowCount)
    }

    @Test
    fun uncertainReadsSeparateCurrentPackagesFromCarriedStalePackages() {
        val uncertainReads = listOf(
            snapshot(hasApplicationWindow = false, hasUnknown = true),
            snapshot(hasApplicationWindow = true, hasUnknown = true, count = 1),
            snapshot(
                packages = setOf("com.example.partial"),
                hasApplicationWindow = true,
                hasUnknown = true,
                count = 2
            ),
            snapshot(hasApplicationWindow = false, hasUnknown = true, providerFailed = true)
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
            assertEquals(1_000L, result.capturedAtElapsedMs)
            assertEquals(uncertain.hasApplicationWindow, result.hasApplicationWindow)
            assertEquals(uncertain.hasUnknownApplicationWindow, result.hasUnknownApplicationWindow)
            assertEquals(uncertain.providerFailed, result.providerFailed)
        }
    }

    @Test
    fun repeatedResolvedReadsRemainFreshRegardlessOfElapsedTime() {
        val cache = ApplicationWindowProvenanceCache()
        cache.resolve(resolvedOther(), capturedAtElapsedMs = 1_000L)

        val result = cache.resolve(resolvedOther(), capturedAtElapsedMs = 60_000L)

        assertEquals(ApplicationWindowsFreshness.FRESH, result.freshness)
        assertEquals(60_000L, result.capturedAtElapsedMs)
        assertTrue(result.stalePackages.isEmpty())
    }

    @Test
    fun lifecycleClearDropsCachedPackages() {
        val cache = ApplicationWindowProvenanceCache()
        cache.resolve(resolvedOther(), capturedAtElapsedMs = 1_000L)

        cache.clear()
        val result = cache.resolve(
            snapshot(hasApplicationWindow = false, hasUnknown = true),
            capturedAtElapsedMs = 7_000L
        )

        assertEquals(ApplicationWindowsFreshness.FRESH, result.freshness)
        assertEquals(7_000L, result.capturedAtElapsedMs)
        assertTrue(result.packages.isEmpty())
        assertTrue(result.stalePackages.isEmpty())
        assertFalse(result.providerFailed)
    }

    private fun resolvedOther() = snapshot(
        packages = setOf("com.example.other"),
        hasApplicationWindow = true,
        hasUnknown = false,
        count = 1
    )

    private fun snapshot(
        packages: Set<String> = emptySet(),
        hasApplicationWindow: Boolean,
        hasUnknown: Boolean,
        providerFailed: Boolean = false,
        count: Int = packages.size
    ) = AppRuleBlocker.ApplicationWindowSnapshot(
        packages = packages,
        hasApplicationWindow = hasApplicationWindow,
        hasUnknownApplicationWindow = hasUnknown,
        providerFailed = providerFailed,
        applicationWindowCount = count
    )
}
