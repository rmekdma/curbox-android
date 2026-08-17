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
    val allowedMinutes: Long = 0L
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
            rule.copy(id = rule.id.trim(), name = rule.name.trim(), appGroupId = rule.appGroupId.trim())
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
            if (rule.appGroupId !in groupIds) {
                errors += "App rule ${rule.id} references missing app group ${rule.appGroupId}"
            }
            if (rule.weekdays.any { it !in 0..6 }) {
                errors += "App rule ${rule.id} contains an invalid weekday"
            }
            if (rule.startMinute !in 0 until 24 * 60) {
                errors += "App rule ${rule.id} has an invalid start time"
            }
            if (rule.endMinute !in 0..24 * 60) {
                errors += "App rule ${rule.id} has an invalid end time"
            }
            if (rule.allowedMinutes < 0L) {
                errors += "App rule ${rule.id} has a negative allowance"
            }
        }
        return errors
    }

    val isValid: Boolean
        get() = validate().isEmpty()
}

/** Name used by callers that prefer the longer domain wording. */
typealias AppRuleConfiguration = AppRuleSnapshot
typealias NeutralAppGroup = AppRuleAppGroup

/** A persisted foreground session for one app in one current use day. */
data class ForegroundSession(
    val id: Long = 0L,
    val useDayId: String = "",
    val packageName: String = "",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null
)

private fun newId(): String = UUID.randomUUID().toString()
