package neth.iecal.curbox.data.models

import neth.iecal.curbox.utils.UseDay
import neth.iecal.curbox.utils.UseDayResetTime

data class Settings(
    val blockedAppGroups: List<AppGroup> = listOf(),
    val manualFocusGroups: List<ManualFocusGroup> = listOf(),
    val autoDndGroups: List<AutoDndGroup> = listOf(),
    /**
     * Stores info about active manual focus mode.
     * Format Pair<GroupId?, system ms when it ends>.
     * Set group id as null when no active focus mode is running
     */
    val activeManualFocusGroupId: Pair<String?, Long> = Pair(null, 0),

    val reelBlockerConfig: ReelBlocker = ReelBlocker(),
    val keywordBlockerConfig: KeywordBlocker = KeywordBlocker(),
    val isReelCounterOn: Boolean = true,
    val grayscaleGroups: List<GrayscaleGroup> = listOf(),
    val usageTrackerIgnoredApps: List<String> = listOf(),
    val isAppUsageTrackingEnabled: Boolean = true,
    val isWebsiteUsageTrackingEnabled: Boolean = true,
    val mindfulMessageConfig: MindfulMessageConfig = MindfulMessageConfig(),
    val uiHiderConfig: UiHiderConfig = UiHiderConfig(),
    val reelCounterOverlayConfig: ReelCounterOverlayConfig = ReelCounterOverlayConfig(),
    val nextWebsiteRecheckTime: Long = 0L,
    val serviceProtectionConfig: ServiceProtectionConfig = ServiceProtectionConfig(),
    val antiUninstallConfig2: AntiUninstallConfig = AntiUninstallConfig(),
    val settingsChangeDelayConfig2: SettingsChangeDelayConfig = SettingsChangeDelayConfig(),
    /** New unified app rules. The legacy [blockedAppGroups] field remains readable for old data. */
    val appRuleSnapshot: AppRuleSnapshot = AppRuleSnapshot(),
    /** Local clock time at which the global use day starts. */
    val useDayResetHour: Int = UseDay.DEFAULT_RESET_HOUR,
    val useDayResetMinute: Int = UseDay.DEFAULT_RESET_MINUTE,
    /** Latest reset setting change. It prevents an in-place reset edit from reusing old rows. */
    val useDayGenerationStartedAtMs: Long = 0L
) {
    /** Convenient scalar form for settings UIs and deterministic tests. */
    val useDayResetTimeMinutes: Int
        get() = useDayResetHour.coerceIn(0, 23) * 60 + useDayResetMinute.coerceIn(0, 59)

    val useDayResetTime: UseDayResetTime
        get() = UseDayResetTime(useDayResetHour, useDayResetMinute)
}
