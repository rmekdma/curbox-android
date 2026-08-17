package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleSnapshot
import java.util.concurrent.atomic.AtomicReference

/** Public service-facing seam that accepts only complete, valid rule snapshots. */
class AppRuleSnapshotCoordinator(
    initial: AppRuleSnapshot = AppRuleSnapshot()
) {
    private val current = AtomicReference(
        initial.normalized().takeIf(AppRuleSnapshot::isValid) ?: AppRuleSnapshot()
    )

    fun accept(candidate: AppRuleSnapshot): Boolean {
        val normalized = candidate.normalized()
        if (!normalized.isValid) return false
        current.set(normalized)
        return true
    }

    fun snapshot(): AppRuleSnapshot = current.get()
}
