package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.blockers.AppRuleBlocker

internal class ApplicationWindowProvenanceCache {
    private data class CachedObservation(
        val packages: Set<String>,
        val capturedAtElapsedMs: Long
    )

    private var cached: CachedObservation? = null

    @Synchronized
    fun invalidate() {
        cached = null
    }

    @Synchronized
    fun clear() {
        cached = null
    }

    @Synchronized
    fun resolve(
        raw: ApplicationWindowsFact,
        capturedAtElapsedMs: Long
    ): ApplicationWindowsFact {
        val packages = raw.packages
        val rawStalePackages = raw.stalePackages - packages
        val resolvedNonempty = packages.isNotEmpty() &&
            raw.readState == ForegroundReadState.AVAILABLE &&
            raw.unknownSlotCount == 0
        if (resolvedNonempty) {
            cached = CachedObservation(packages, capturedAtElapsedMs)
            return raw.copy(
                packages = packages,
                stalePackages = emptySet(),
                freshness = ApplicationWindowsFreshness.FRESH
            )
        }
        val previous = cached ?: return raw.copy(
            packages = packages,
            stalePackages = rawStalePackages,
            freshness = ApplicationWindowsFreshness.FRESH
        )
        val carriedPackages = previous.packages - packages
        return raw.copy(
            packages = packages,
            stalePackages = rawStalePackages + carriedPackages,
            freshness = if (packages.isEmpty()) {
                ApplicationWindowsFreshness.STALE
            } else {
                ApplicationWindowsFreshness.FRESH
            }
        )
    }

    @Synchronized
    fun resolve(
        raw: AppRuleBlocker.ApplicationWindowSnapshot,
        capturedAtElapsedMs: Long
    ): AppRuleBlocker.ApplicationWindowSnapshot {
        val packages = raw.packages.toSet()
        val rawStalePackages = raw.stalePackages.toSet() - packages
        val resolvedNonempty = packages.isNotEmpty() &&
            raw.hasApplicationWindow &&
            !raw.hasUnknownApplicationWindow &&
            !raw.providerFailed
        if (resolvedNonempty) {
            cached = CachedObservation(packages, capturedAtElapsedMs)
            return raw.copy(
                packages = packages,
                stalePackages = emptySet(),
                freshness = ApplicationWindowsFreshness.FRESH,
                capturedAtElapsedMs = capturedAtElapsedMs
            )
        }
        val previous = cached ?: return raw.copy(
            packages = packages,
            stalePackages = rawStalePackages,
            freshness = ApplicationWindowsFreshness.FRESH,
            capturedAtElapsedMs = capturedAtElapsedMs
        )
        val carriedPackages = previous.packages - packages
        return raw.copy(
            packages = packages,
            stalePackages = (rawStalePackages + carriedPackages).toSet(),
            freshness = if (packages.isEmpty()) {
                ApplicationWindowsFreshness.STALE
            } else {
                ApplicationWindowsFreshness.FRESH
            },
            capturedAtElapsedMs = previous.capturedAtElapsedMs
        )
    }
}
