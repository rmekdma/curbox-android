package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppGroupEditMode
import neth.iecal.curbox.data.models.AppGroupMembershipVersion
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.utils.UseDayCalculator

/**
 * Applies one group edit without losing the meaning of sessions already written to the ledger.
 * The timestamp is supplied by the caller because a delayed settings change must choose its
 * effective time inside the same settings transaction that makes the change live.
 */
object AppGroupMembershipTimeline {
    fun effectiveAt(
        mode: AppGroupEditMode,
        appliedAtMs: Long,
        useDayCalculator: UseDayCalculator
    ): Long = when (mode) {
        AppGroupEditMode.NOW -> appliedAtMs
        AppGroupEditMode.CURRENT_USE_DAY_START -> useDayCalculator
            .windowFor(useDayCalculator.idAt(appliedAtMs))
            .first
    }

    @Suppress("UNUSED_PARAMETER")
    fun apply(
        previous: AppRuleSnapshot,
        proposed: AppRuleSnapshot,
        mode: AppGroupEditMode,
        effectiveAtMs: Long
    ): AppRuleSnapshot {
        // Kept as a small source-compatible seam for callers that edit one group.  The mode has
        // already been resolved into a concrete timestamp by the caller.
        val changedGroupIds = proposed.appGroups.map { it.id }.toSet()
        return apply(
            previous = previous,
            proposed = proposed,
            effectiveAtByGroup = changedGroupIds.associateWith { effectiveAtMs }
        )
    }

    fun apply(
        previous: AppRuleSnapshot,
        proposed: AppRuleSnapshot,
        effectiveAtByGroup: Map<String, Long>
    ): AppRuleSnapshot {
        val previousById = previous.appGroups.associateBy { it.id }
        val groups = proposed.appGroups.map { next ->
            val old = previousById[next.id]
            val oldPackages = old?.selectedPackages.orEmpty().toSet()
            val nextPackages = next.selectedPackages.toSet()
            if (old == null) {
                val effectiveAtMs = effectiveAtByGroup[next.id] ?: Long.MIN_VALUE
                next.copy(
                    membershipHistory = listOf(
                        AppGroupMembershipVersion(effectiveAtMs, nextPackages.toList())
                    )
                )
            } else if (oldPackages == nextPackages) {
                // A name only edit must not create an artificial membership boundary.
                next.copy(membershipHistory = old.membershipHistory)
            } else {
                val oldHistory = old.normalizedMembershipHistory()
                val effectiveAtMs = effectiveAtByGroup[next.id] ?: Long.MIN_VALUE
                val retained = oldHistory.filter { it.effectiveFromMs < effectiveAtMs }
                val updated = (retained + AppGroupMembershipVersion(
                    effectiveFromMs = effectiveAtMs,
                    selectedPackages = nextPackages.toList()
                )).distinctBy { it.effectiveFromMs }
                    .sortedBy { it.effectiveFromMs }
                next.copy(membershipHistory = updated)
            }
        }
        return proposed.copy(appGroups = groups)
    }
}

/** Resolves a snapshot's target and contributor memberships at one ledger timestamp. */
class AppRuleMembershipResolver(private val snapshot: AppRuleSnapshot) {
    private val groupsById = snapshot.appGroups.associateBy { it.id.trim() }

    fun targetPackagesAt(
        rule: neth.iecal.curbox.data.models.AppRule,
        atMs: Long,
        launchablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet()
    ): Set<String> = rule.effectiveScope().resolve(
        groups = snapshot.appGroups,
        launchablePackages = launchablePackages,
        essentialExcludedPackages = essentialExcludedPackages,
        atMs = atMs
    )

    fun packagesForGroupAt(groupId: String, atMs: Long): Set<String> = groupsById[groupId.trim()]?.packagesAt(atMs).orEmpty().map(String::trim).filter(String::isNotEmpty).toSet()

    fun contributorPackagesAt(
        rule: neth.iecal.curbox.data.models.AppRule,
        atMs: Long
    ): Set<String> = rule.effectiveContributorGroupIds()
        .flatMap { id -> groupsById[id]?.packagesAt(atMs).orEmpty() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    fun targetAndContributorBoundaries(
        rule: neth.iecal.curbox.data.models.AppRule
    ): Set<Long> {
        val ids = buildSet {
            addAll(rule.effectiveScope().includedGroupIds)
            addAll(rule.effectiveScope().excludedGroupIds)
            addAll(rule.effectiveContributorGroupIds())
        }
        return ids.flatMap { groupsById[it]?.membershipBoundaries().orEmpty() }.toSet()
    }

    fun hasMissingContributor(rule: neth.iecal.curbox.data.models.AppRule): Boolean =
        rule.effectiveContributorGroupIds().any { it !in groupsById }
}
