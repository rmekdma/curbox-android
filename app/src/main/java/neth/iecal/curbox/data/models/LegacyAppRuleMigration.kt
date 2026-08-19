package neth.iecal.curbox.data.models

/**
 * One-way conversion from the coupled AppGroup/AppBlocker settings to the neutral app-rule
 * graph.  The conversion is pure so DataStore can run it during its single migration and tests
 * can prove that an upgrade does not depend on the wall clock or process ordering.
 */
object LegacyAppRuleMigration {
    const val CURRENT_VERSION = 1

    private const val GROUP_ID_PREFIX = "legacy-app-group:"
    private const val RULE_ID_PREFIX = "legacy-app-rule:"

    /** Stable id used when a pending legacy payload is converted after the main snapshot. */
    fun neutralGroupId(legacyId: String, fallbackIndex: Int = 0): String =
        "$GROUP_ID_PREFIX${legacyId.trim().ifBlank { "index-$fallbackIndex" }}"

    /**
     * Imports [legacyGroups] into [existing] without replacing user-created neutral groups or
     * rules.  Every imported id is derived only from the old id and the old schedule/limit, so a
     * retry after a process death cannot append a second copy.
     */
    @Suppress("DEPRECATION")
    fun migrate(
        legacyGroups: List<AppGroup>,
        existing: AppRuleSnapshot = AppRuleSnapshot(),
        nowMs: Long = 0L
    ): AppRuleSnapshot {
        val upgraded = sanitizeForMigration(legacyGroups).upgradeLegacyAppGroupConfigs()
        val imported = upgraded.mapIndexedNotNull { index, group ->
            importGroup(group, index, nowMs)
        }

        val groups = (existing.appGroups + imported.flatMap { listOf(it.group) })
            .distinctBy(AppRuleAppGroup::id)
        val rules = (existing.appRules + imported.flatMap { it.rules })
            .distinctBy(AppRule::id)
        return AppRuleSnapshot(groups, rules).normalized()
    }

    /**
     * Replaces only the portion previously imported from [legacyGroups].  This is used for an
     * old APP_GROUPS pending payload: neutral groups created by the user must survive while the
     * legacy payload still gets its original edit semantics, including group deletions.
     */
    fun replaceLegacy(
        legacyGroups: List<AppGroup>,
        existing: AppRuleSnapshot = AppRuleSnapshot(),
        nowMs: Long = 0L
    ): AppRuleSnapshot {
        val safeLegacyGroups = sanitizeForMigration(legacyGroups)
        val retained = existing.copy(
            appGroups = existing.appGroups.filterNot { it.id.startsWith(GROUP_ID_PREFIX) },
            appRules = existing.appRules.filterNot { rule ->
                rule.id.startsWith(RULE_ID_PREFIX) ||
                    rule.appGroupId.startsWith(GROUP_ID_PREFIX) ||
                    rule.effectiveScope().includedGroupIds.any { it.startsWith(GROUP_ID_PREFIX) } ||
                    rule.effectiveScope().excludedGroupIds.any { it.startsWith(GROUP_ID_PREFIX) }
            }
        )
        return migrate(safeLegacyGroups, retained, nowMs)
    }

    private data class ImportedGroup(
        val group: AppRuleAppGroup,
        val rules: List<AppRule>
    )

    private data class RuleKey(
        val weekdays: List<Int>,
        val ranges: List<AppRuleTimeRange>,
        val allowedMinutes: Long
    )

    @Suppress("DEPRECATION")
    internal fun sanitizeForMigration(groups: List<AppGroup>): List<AppGroup> =
        groups.mapIndexedNotNull { index, group ->
            runCatching {
                val id = group.id.orEmpty().trim().ifBlank { "index-$index" }
                val config = group.config?.let { value ->
                    value.copy(
                        schedule = value.schedule ?: AppTimeConfig.allDay(),
                        usage = value.usage ?: AppUsageConfig()
                    )
                }
                group.copy(
                    id = id,
                    name = group.name.orEmpty().trim().ifBlank { "App group" },
                    selectedPackages = group.selectedPackages.orEmpty(),
                    config = config,
                    warningScreenConfig = group.warningScreenConfig
                        ?: AppBlockerWarningScreenConfig(),
                    blockingType = group.blockingType ?: AppBlockingType.Usage,
                    setting = group.setting.orEmpty(),
                    isActive = group.isActive,
                    temporarilyDisabledUntilMs = group.temporarilyDisabledUntilMs
                )
            }.getOrNull()
        }

    @Suppress("DEPRECATION")
    private fun importGroup(group: AppGroup, index: Int, nowMs: Long = 0L): ImportedGroup? {
        val sourceId = group.id.orEmpty().trim().ifBlank { "index-$index" }
        val groupId = neutralGroupId(sourceId)
        val effectiveIsActive = when {
            group.temporarilyDisabledUntilMs == -1L -> false
            nowMs > 0L && group.temporarilyDisabledUntilMs in 1..nowMs -> true
            group.temporarilyDisabledUntilMs > 0L -> false
            else -> group.isActive
        }
        val neutralGroup = AppRuleAppGroup(
            id = groupId,
            name = group.name.orEmpty().trim().ifBlank { "App group" },
            selectedPackages = group.selectedPackages.orEmpty()
        )
        val config = runCatching { group.config ?: AppGroupConfig() }
            .getOrElse { AppGroupConfig() }
        val schedule = runCatching { config.schedule ?: AppTimeConfig.allDay() }
            .getOrElse { AppTimeConfig.allDay() }
        val usage = runCatching { config.usage ?: AppUsageConfig() }
            .getOrElse { AppUsageConfig() }

        val groupedSpecs = linkedMapOf<RuleKey, MutableSet<Int>>()
        (0..6).forEach { day ->
            val intervals = if (runCatching { schedule.isEveryday }.getOrDefault(true)) {
                runCatching { schedule.everydayIntervals.orEmpty() }.getOrDefault(emptyList())
            } else {
                runCatching { schedule.dailyIntervals[day].orEmpty() }.getOrDefault(emptyList())
            }
            val ranges = intervals.mapNotNull { interval ->
                runCatching { toRange(interval) }.getOrNull()
            }.distinct()
            if (ranges.isEmpty()) return@forEach
            val allowedMinutes = if (runCatching { usage.isDailyUniform }.getOrDefault(true)) {
                runCatching { usage.uniformLimit }.getOrDefault(0L)
            } else {
                runCatching { usage.dailyLimits.getOrNull(day) }.getOrNull() ?: 0L
            }.coerceAtLeast(0L)
            groupedSpecs.getOrPut(
                RuleKey(
                    weekdays = emptyList(),
                    ranges = ranges,
                    allowedMinutes = allowedMinutes
                )
            ) { linkedSetOf() }.add(day)
        }

        val rules = groupedSpecs.entries
            .map { (keyWithoutDays, weekdays) ->
                val sortedDays = weekdays.toList().sorted()
                val key = keyWithoutDays.copy(weekdays = sortedDays)
                val id = "$RULE_ID_PREFIX$sourceId:${key.idPart()}"
                AppRule(
                    id = id,
                    name = neutralGroup.name,
                    isActive = effectiveIsActive,
                    weekdays = sortedDays.toSet(),
                    startMinute = key.ranges.first().startMinute,
                    endMinute = key.ranges.first().endMinute,
                    appGroupId = groupId,
                    allowedMinutes = key.allowedMinutes,
                    scope = AppRuleScope.forGroup(groupId),
                    timeRanges = key.ranges
                )
            }
        return ImportedGroup(neutralGroup, rules)
    }

    private fun toRange(interval: TimeInterval): AppRuleTimeRange? {
        val start = (interval.startHour * 60 + interval.startMinute).coerceIn(0, 1439)
        val end = (interval.endHour * 60 + interval.endMinute).coerceIn(0, 1440)
        return AppRuleTimeRange(start, end)
    }

    private fun RuleKey.idPart(): String = buildString {
        append(weekdays.joinToString("-"))
        append(":")
        append(ranges.joinToString(",") { "${it.startMinute}-${it.endMinute}" })
        append(":")
        append(allowedMinutes)
    }
}
