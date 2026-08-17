package neth.iecal.curbox.data.models

import java.util.UUID

/**
 * A reusable app set used by the first generation of unified app rules.
 *
 * This intentionally contains no schedule or limit.  The old [AppGroup] model still contains
 * those fields because it is the compatibility boundary for settings written by older Curbox
 * versions.
 */
data class AppRuleAppGroup(
    val id: String = newId(),
    val name: String = "",
    val selectedPackages: List<String> = emptyList()
) {
    companion object {
        fun create(name: String, selectedPackages: List<String>): AppRuleAppGroup =
            AppRuleAppGroup(
                id = newId(),
                name = name,
                selectedPackages = selectedPackages.distinct()
            )
    }

    fun copyWithNewId(): AppRuleAppGroup = copy(id = newId())
}

/** A semantic active interval. Equal endpoints deliberately mean a full 24 hours. */
data class AppRuleTimeRange(
    val startMinute: Int = 0,
    val endMinute: Int = 0
)

/**
 * The target set for a rule. Group ids are references into [AppRuleSnapshot.appGroups]; the
 * package set for [includeAllApps] is supplied by the launcher at evaluation time so newly
 * installed launchable applications are included without editing the rule.
 */
data class AppRuleScope(
    val includeAllApps: Boolean = false,
    val includedGroupIds: Set<String> = emptySet(),
    val excludedGroupIds: Set<String> = emptySet()
) {
    val includesAllApps: Boolean
        get() = includeAllApps

    val includeGroupIds: Set<String>
        get() = includedGroupIds

    val excludeGroupIds: Set<String>
        get() = excludedGroupIds

    val isEmpty: Boolean
        get() = !includeAllApps && includedGroupIds.isEmpty() && excludedGroupIds.isEmpty()

    fun normalized(): AppRuleScope = copy(
        includedGroupIds = includedGroupIds.map(String::trim).filter(String::isNotEmpty).toSet(),
        excludedGroupIds = excludedGroupIds.map(String::trim).filter(String::isNotEmpty).toSet()
    )

    /**
     * Resolves the current target package set. Exclusions are applied after the complete include
     * union, and the caller supplies internal packages that must never be blocked.
     */
    fun resolve(
        groups: Iterable<AppRuleAppGroup>,
        launchablePackages: Set<String> = emptySet(),
        essentialExcludedPackages: Set<String> = emptySet()
    ): Set<String> {
        val byId = groups.associateBy { it.id.trim() }
        val included = buildSet {
            if (includeAllApps) addAll(launchablePackages)
            includedGroupIds.forEach { id ->
                byId[id]?.selectedPackages?.forEach { packageName ->
                    val normalized = packageName.trim()
                    if (normalized.isNotEmpty()) add(normalized)
                }
            }
        }
        val excluded = excludedGroupIds.flatMap { id ->
            byId[id]?.selectedPackages.orEmpty()
        }.map(String::trim).filter(String::isNotEmpty).toSet()
        val essential = essentialExcludedPackages.map(String::trim).filter(String::isNotEmpty).toSet()
        return (included - excluded - essential)
            .filter(String::isNotBlank)
            .toSet()
    }

    companion object {
        fun forGroup(groupId: String): AppRuleScope =
            AppRuleScope(includedGroupIds = setOf(groupId))

        fun allApps(): AppRuleScope = AppRuleScope(includeAllApps = true)
    }
}

/**
 * One unified rule.  Weekday values use the same convention as the existing settings model:
 * Sunday is 0 and Saturday is 6.  The end minute may be 1440 to represent midnight.
 */
data class AppRule(
    val id: String = newId(),
    val name: String = "",
    val isActive: Boolean = true,
    val weekdays: Set<Int> = (0..6).toSet(),
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    val appGroupId: String = "",
    val allowedMinutes: Long = 0L,
    /** Composite target scope introduced after the first single-group rule. */
    val scope: AppRuleScope = AppRuleScope(),
    /** Additional semantic ranges. Empty keeps the legacy start/end fields readable. */
    val timeRanges: List<AppRuleTimeRange> = emptyList(),
    val ranges: List<AppRuleTimeRange> = emptyList(),
    /** Flat aliases keep serialized and test construction ergonomic during the migration. */
    val includeAllApps: Boolean = false,
    val includedGroupIds: Set<String> = emptySet(),
    val excludedGroupIds: Set<String> = emptySet()
) {
    companion object {
        fun create(
            name: String,
            weekdays: Set<Int>,
            startMinute: Int,
            endMinute: Int,
            appGroupId: String,
            allowedMinutes: Long,
            isActive: Boolean = true
        ): AppRule = AppRule(
            id = newId(),
            name = name,
            isActive = isActive,
            weekdays = weekdays.toSet(),
            startMinute = startMinute,
            endMinute = endMinute,
            appGroupId = appGroupId,
            allowedMinutes = allowedMinutes
        )
    }

    fun copyWithNewId(): AppRule = copy(id = newId())

    fun effectiveScope(): AppRuleScope {
        val direct = AppRuleScope(
            includeAllApps = includeAllApps,
            includedGroupIds = includedGroupIds,
            excludedGroupIds = excludedGroupIds
        )
        return when {
            !scope.isEmpty -> scope.copy(
                includeAllApps = scope.includeAllApps || direct.includeAllApps,
                includedGroupIds = scope.includedGroupIds + direct.includedGroupIds,
                excludedGroupIds = scope.excludedGroupIds + direct.excludedGroupIds
            )
            !direct.isEmpty -> direct
            appGroupId.isNotBlank() -> AppRuleScope.forGroup(appGroupId)
            else -> scope
        }
    }

    fun effectiveTimeRanges(): List<AppRuleTimeRange> =
        (timeRanges + ranges).ifEmpty { listOf(AppRuleTimeRange(startMinute, endMinute)) }
}

/**
 * Atomic restriction configuration for unified rules.  Groups and rules are always validated
 * together before this value is written to settings or handed to the service.
 */
data class AppRuleSnapshot(
    val appGroups: List<AppRuleAppGroup> = emptyList(),
    val appRules: List<AppRule> = emptyList()
) {
    fun normalized(): AppRuleSnapshot = copy(
        appGroups = appGroups.map { group ->
            group.copy(
                id = group.id.trim(),
                selectedPackages = group.selectedPackages
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
            )
        },
        appRules = appRules.map { rule ->
            rule.copy(
                id = rule.id.trim(),
                name = rule.name.trim(),
                appGroupId = rule.appGroupId.trim(),
                scope = rule.scope.normalized(),
                timeRanges = rule.timeRanges.toList(),
                ranges = rule.ranges.toList(),
                includedGroupIds = rule.includedGroupIds
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet(),
                excludedGroupIds = rule.excludedGroupIds
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
            )
        }
    )

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val groupIds = mutableSetOf<String>()
        appGroups.forEach { group ->
            if (group.id.isBlank()) errors += "App group id must not be blank"
            if (!groupIds.add(group.id)) errors += "Duplicate app group id: ${group.id}"
            if (group.name.isBlank()) errors += "App group name must not be blank"
            if (group.selectedPackages.any { it.isBlank() }) {
                errors += "App group ${group.id} contains a blank package"
            }
        }

        val ruleIds = mutableSetOf<String>()
        appRules.forEach { rule ->
            if (rule.id.isBlank()) errors += "App rule id must not be blank"
            if (!ruleIds.add(rule.id)) errors += "Duplicate app rule id: ${rule.id}"
            if (rule.name.isBlank()) errors += "App rule name must not be blank"
            val scope = rule.effectiveScope()
            if (rule.appGroupId.isNotBlank() && rule.appGroupId !in groupIds) {
                errors += "App rule ${rule.id} references missing app group ${rule.appGroupId}"
            }
            if (scope.includedGroupIds.any { it !in groupIds }) {
                errors += "App rule ${rule.id} references a missing included app group"
            }
            if (scope.excludedGroupIds.any { it !in groupIds }) {
                errors += "App rule ${rule.id} references a missing excluded app group"
            }
            if (rule.weekdays.isEmpty() || rule.weekdays.any { it !in 0..6 }) {
                errors += "App rule ${rule.id} contains an invalid weekday"
            }
            rule.effectiveTimeRanges().forEach { range ->
                if (range.startMinute !in 0 until 24 * 60) {
                    errors += "App rule ${rule.id} has an invalid start time"
                }
                if (range.endMinute !in 0..24 * 60) {
                    errors += "App rule ${rule.id} has an invalid end time"
                }
            }
            if (rule.allowedMinutes < 0L) {
                errors += "App rule ${rule.id} has a negative allowance"
            }
        }
        return errors
    }

    val isValid: Boolean
        get() = validate().isEmpty()

    /** Target rules must be handled explicitly before a referenced group can be removed. */
    fun targetRuleIdsReferencing(groupId: String): List<String> = appRules.filter { rule ->
        val scope = rule.effectiveScope()
        groupId in scope.includedGroupIds || groupId in scope.excludedGroupIds
    }.map { it.id }

    fun canDeleteTargetGroup(groupId: String): Boolean =
        targetRuleIdsReferencing(groupId).isEmpty()

    /** Removes a group only when the caller has explicitly chosen how its target references go. */
    fun deleteTargetGroup(
        groupId: String,
        removeReferences: Boolean = false,
        deleteDependentRules: Boolean = false
    ): AppRuleSnapshot? {
        val references = targetRuleIdsReferencing(groupId).toSet()
        if (references.isNotEmpty() && !removeReferences && !deleteDependentRules) return null
        val nextRules = when {
            deleteDependentRules -> appRules.filterNot { it.id in references }
            removeReferences -> appRules.map { rule ->
                val scope = rule.effectiveScope()
                rule.copy(
                    appGroupId = if (rule.appGroupId == groupId) "" else rule.appGroupId,
                    scope = scope.copy(
                        includedGroupIds = scope.includedGroupIds - groupId,
                        excludedGroupIds = scope.excludedGroupIds - groupId
                    ),
                    includeAllApps = scope.includeAllApps,
                    includedGroupIds = emptySet(),
                    excludedGroupIds = emptySet()
                )
            }
            else -> appRules
        }
        return copy(
            appGroups = appGroups.filterNot { it.id == groupId },
            appRules = nextRules
        )
    }
}

/** A persisted foreground session for one app in one current use day. */
data class ForegroundSession(
    val id: Long = 0L,
    val useDayId: String = "",
    val packageName: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    /** Nonzero only when a changed reset setting starts a new use-day instance. */
    val useDayGenerationStartedAtMs: Long = 0L,
    /** False for temporary enforcement-only rows created while statistics are disabled. */
    val statisticsTracked: Boolean = true
)

private fun newId(): String = UUID.randomUUID().toString()
