package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.core.DataMigration
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupEditMode
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.GuardianAuthConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.LegacyAppRuleMigration
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.SettingsChangeDelayPrefs
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs
import neth.iecal.curbox.data.models.upgradeLegacyKeywordGroupConfigs
import neth.iecal.curbox.data.models.upgradeLegacyConfig
import neth.iecal.curbox.domain.apprules.AppGroupMembershipTimeline
import neth.iecal.curbox.hardcoded.normalized
import neth.iecal.curbox.services.TemporaryGroupDisableJob
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import kotlin.jvm.java

class GsonSerializer<T>(
    private val gson: Gson,
    private val type: Type,
    override val defaultValue: T
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T {
        return try {
            val raw = input.readBytes().decodeToString()
            gson.fromJson(normalizeSettingsJson(raw), type) ?: defaultValue
        } catch (e: Exception) {
            e.printStackTrace()
            defaultValue
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray())
    }

    /** Adds only fields introduced by the neutral rule cutover before Gson reflects Kotlin data. */
    private fun normalizeSettingsJson(raw: String): String {
        if (type != Settings::class.java) return raw
        val root = JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
            ?: return raw
        if (!root.has("blockedAppGroups") ||
            root.get("blockedAppGroups").isJsonNull ||
            !root.get("blockedAppGroups").isJsonArray
        ) {
            root.add("blockedAppGroups", JsonArray())
        }
        val snapshot = root.get("appRuleSnapshot")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject()
        if (!snapshot.has("appGroups") || snapshot.get("appGroups").isJsonNull) {
            snapshot.add("appGroups", JsonArray())
        }
        if (!snapshot.has("appRules") || snapshot.get("appRules").isJsonNull) {
            snapshot.add("appRules", JsonArray())
        }
        snapshot.add("appGroups", normalizedArray(arrayField(snapshot, "appGroups")) { group ->
            ensureString(group, "id", "")
            ensureString(group, "name", "")
            ensureArray(group, "selectedPackages")
            val history = normalizedArray(arrayField(group, "membershipHistory")) { version ->
                ensureLong(version, "effectiveFromMs", Long.MIN_VALUE)
                ensureArray(version, "selectedPackages")
            }
            group.add("membershipHistory", history)
        })
        snapshot.add("appRules", normalizedArray(arrayField(snapshot, "appRules")) { rule ->
            ensureString(rule, "id", "")
            ensureString(rule, "name", "")
            ensureBoolean(rule, "isActive", true)
            ensureWeekdays(rule)
            ensureInt(rule, "startMinute", 0)
            ensureInt(rule, "endMinute", 0)
            ensureString(rule, "appGroupId", "")
            ensureLong(rule, "allowedMinutes", 0L)
            ensureBoolean(rule, "usageConditionEnabled", false)
            ensureLong(rule, "usageConditionMinutes", 0L)
            val groupConditions = rule.get("contributorGroupConditionMinutes")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val normalizedGroupConditions = JsonObject()
            groupConditions.entrySet().forEach { (key, value) ->
                if (key.isNotBlank() && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                    normalizedGroupConditions.addProperty(key.trim(), value.asLong.coerceAtLeast(0L))
                }
            }
            rule.add("contributorGroupConditionMinutes", normalizedGroupConditions)
            ensureBoolean(rule, "earnedAllowanceEnabled", false)
            ensureArray(rule, "contributorGroupIds")
            ensureArray(rule, "timeRanges")
            val ranges = normalizedArray(arrayField(rule, "timeRanges")) { range ->
                ensureInt(range, "startMinute", 0)
                ensureInt(range, "endMinute", 0)
            }
            rule.add("timeRanges", ranges)
            val scope = rule.get("scope")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: JsonObject()
            ensureBoolean(scope, "includeAllApps", false)
            ensureArray(scope, "includedGroupIds")
            ensureArray(scope, "excludedGroupIds")
            rule.add("scope", scope)
        })
        root.add("appRuleSnapshot", snapshot)
        val marker = root.get("appRuleMigrationVersion")
        if (marker == null || marker.isJsonNull ||
            !marker.isJsonPrimitive || !marker.asJsonPrimitive.isNumber
        ) {
            root.addProperty("appRuleMigrationVersion", 0)
        }
        return root.toString()
    }

    private fun normalizedArray(
        array: JsonArray?,
        normalize: (JsonObject) -> Unit
    ): JsonArray = JsonArray().also { normalized ->
        (array ?: JsonArray()).forEach { element ->
            if (element.isJsonObject) {
                element.asJsonObject.also(normalize).let(normalized::add)
            }
        }
    }

    private fun arrayField(objectValue: JsonObject, name: String): JsonArray? =
        objectValue.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun ensureArray(objectValue: JsonObject, name: String) {
        if (!objectValue.has(name) || !objectValue.get(name).isJsonArray) {
            objectValue.add(name, JsonArray())
        }
    }

    private fun ensureWeekdays(objectValue: JsonObject) {
        if (objectValue.has("weekdays") && objectValue.get("weekdays").isJsonArray) return
        objectValue.add("weekdays", JsonArray().also { days ->
            (0..6).forEach { day -> days.add(day) }
        })
    }

    private fun ensureString(objectValue: JsonObject, name: String, value: String) {
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
            objectValue.addProperty(name, value)
        }
    }

    private fun ensureBoolean(objectValue: JsonObject, name: String, value: Boolean) {
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
            objectValue.addProperty(name, value)
        }
    }

    private fun ensureInt(objectValue: JsonObject, name: String, value: Int) {
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
            objectValue.addProperty(name, value)
        }
    }

    private fun ensureLong(objectValue: JsonObject, name: String, value: Long) {
        if (!objectValue.has(name) || objectValue.get(name).isJsonNull) {
            objectValue.addProperty(name, value)
        }
    }
}

private class ScheduledUsageConfigMigration(
    private val gson: Gson
) : DataMigration<Settings> {
    override suspend fun shouldMigrate(currentData: Settings): Boolean {
        if (currentData.appRuleMigrationVersion < LegacyAppRuleMigration.CURRENT_VERSION) {
            return true
        }
        if (currentData.blockedAppGroups.any { it.config == null }) return true
        if (currentData.keywordBlockerConfig.keywordGroups.any { it.config == null }) return true
        return currentData.settingsChangeDelayConfig2.pendingChanges.any {
            pendingChangeNeedsMigration(it)
        }
    }

    override suspend fun migrate(currentData: Settings): Settings {
        val now = System.currentTimeMillis()
        val upgradedGroups = LegacyAppRuleMigration.sanitizeForMigration(
            currentData.blockedAppGroups
        ).upgradeLegacyAppGroupConfigs(gson)
        val migratedSnapshot = if (
            currentData.appRuleMigrationVersion < LegacyAppRuleMigration.CURRENT_VERSION
        ) {
            LegacyAppRuleMigration.migrate(upgradedGroups, currentData.appRuleSnapshot, now)
        } else {
            currentData.appRuleSnapshot
        }

        // updateGated keeps one pending value per field, but older versions could leave both
        // legacy APP_GROUPS and neutral APP_RULES entries after an interrupted write.  Prefer the
        // already-neutral entry and convert the legacy one only when it is the sole request.
        val pendingChanges = currentData.settingsChangeDelayConfig2.pendingChanges
        val hasNeutralPending = pendingChanges.any { it.field == GatedSettingsField.APP_RULES.name }
        val convertedLegacyPending = pendingChanges
            .firstOrNull { it.field == GatedSettingsField.APP_GROUPS.name }
            ?.let { change ->
                val groups = parseAppGroups(change.newValueJson)
                    ?.let(LegacyAppRuleMigration::sanitizeForMigration)
                    ?.upgradeLegacyAppGroupConfigs(gson)
                    .orEmpty()
                val snapshot = LegacyAppRuleMigration.replaceLegacy(groups, migratedSnapshot, now)
                val modes = groups.mapIndexed { index, group ->
                    LegacyAppRuleMigration.neutralGroupId(group.id, index) to AppGroupEditMode.NOW
                }.toMap()
                change.copy(
                    field = GatedSettingsField.APP_RULES.name,
                    newValueJson = gson.toJson(snapshot),
                    appGroupEditModes = modes
                )
            }
        val upgradedPending = buildList {
            pendingChanges.forEach { change ->
                when (change.field) {
                    GatedSettingsField.APP_GROUPS.name -> Unit
                    GatedSettingsField.KEYWORD_BLOCKER.name -> {
                        val config = parseKeywordBlocker(change.newValueJson)
                            ?: return@forEach
                        add(change.copy(
                            newValueJson = gson.toJson(
                                config.upgradeLegacyKeywordGroupConfigs(gson)
                            )
                        ))
                    }
                    else -> add(change)
                }
            }
            if (!hasNeutralPending) convertedLegacyPending?.let(::add)
        }
        return currentData.copy(
            blockedAppGroups = upgradedGroups,
            keywordBlockerConfig =
                currentData.keywordBlockerConfig.upgradeLegacyKeywordGroupConfigs(gson),
            appRuleSnapshot = migratedSnapshot,
            appRuleMigrationVersion = LegacyAppRuleMigration.CURRENT_VERSION,
            settingsChangeDelayConfig2 = currentData.settingsChangeDelayConfig2.copy(
                pendingChanges = upgradedPending
            )
        )
    }

    override suspend fun cleanUp() = Unit

    private fun pendingChangeNeedsMigration(change: PendingSettingsChange): Boolean =
        when (change.field) {
            // The legacy editor is no longer a runtime path.  Any old pending APP_GROUPS value
            // must be converted to the neutral snapshot before the next due-apply sweep.
            GatedSettingsField.APP_GROUPS.name -> true
            GatedSettingsField.KEYWORD_BLOCKER.name ->
                parseKeywordBlocker(change.newValueJson)
                    ?.keywordGroups
                    ?.any { it.config == null } == true
            else -> false
        }

    private fun parseAppGroups(json: String): List<AppGroup>? = runCatching {
        gson.fromJson<List<AppGroup>>(json, object : TypeToken<List<AppGroup>>() {}.type)
    }.getOrNull()

    private fun parseKeywordBlocker(json: String): KeywordBlocker? = runCatching {
        gson.fromJson(json, KeywordBlocker::class.java)
    }.getOrNull()
}

class DataStoreManager(private val context: Context) {
    private val gson = Gson()

    companion object {
        @Volatile
        private var INSTANCE: androidx.datastore.core.DataStore<Settings>? = null

        fun getSettingsDataStore(context: Context, gson: Gson): androidx.datastore.core.DataStore<Settings> {
            // Double checked locking: the inner re-check is essential. Without it,
            // two threads racing the first access each build a DataStore for the
            // same file, which crashes with "There are multiple DataStores active
            // for the same file". This is easy to hit on a cold start (e.g. right
            // after a reinstall) when several coroutines touch settings at once.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MultiProcessDataStoreFactory.create(
                    serializer = GsonSerializer(
                        gson = gson,
                        type = Settings::class.java,
                        defaultValue = Settings()
                    ),
                    migrations = listOf(ScheduledUsageConfigMigration(gson)),
                    produceFile = { File(context.applicationContext.filesDir, "datastore/settings.json") }
                ).also { INSTANCE = it }
            }
        }
    }

    private val settingsDataStore = getSettingsDataStore(context, gson)

    val settings = settingsDataStore.data

    /**
     * The values the user has requested, including changes still waiting for their deadline.
     * Settings editors should use this flow; blockers must keep using [settings].
     */
    val settingsForEditing = settings.map(::overlayPendingChanges)

    suspend fun updateAppGroups(newGroups: List<AppGroup>) {
        updateGated(GatedSettingsField.APP_GROUPS) { newGroups }
    }

    /**
     * Writes groups and rules as one validated restriction snapshot. Invalid references are
     * rejected before the snapshot reaches either process, so the service never observes a
     * half-edited configuration.
     */
   suspend fun updateAppRuleSnapshot(
       snapshot: AppRuleSnapshot,
       appGroupEditMode: AppGroupEditMode? = null
   ): Boolean {
       val normalized = snapshot.normalized()
       if (!normalized.isValid) return false
        updateGated(
            field = GatedSettingsField.APP_RULES,
            appGroupEditMode = appGroupEditMode,
            computeNewValue = { normalized }
        )
        sendAppRuleRefreshBroadcast()
        return true
    }

    /** Stores only a salted, slow-derived verifier. Empty input deliberately clears the password. */
    suspend fun setGuardianPassword(
        password: String,
        currentPassword: String = ""
    ): Boolean = setGuardianPasswordAuthorized(currentPassword, password)

    /** Changing or clearing an existing credential requires the existing guardian password. */
    suspend fun setGuardianPasswordAuthorized(
        currentPassword: String,
        newPassword: String
    ): Boolean {
        val credential = GuardianPassword.createCredential(newPassword)
        val updated = settingsDataStore.updateData { current ->
            if (current.guardianAuthConfig.isConfigured &&
                !GuardianPassword.verify(currentPassword, current.guardianAuthConfig)
            ) return@updateData current
            current.copy(guardianAuthConfig = credential)
        }
        return GuardianDataStoreWriteResult.passwordWasStored(updated, credential)
    }

    suspend fun clearGuardianPassword(currentPassword: String = ""): Boolean =
        setGuardianPasswordAuthorized(currentPassword, "")

    suspend fun guardianPasswordIsValid(password: String): Boolean =
        GuardianPassword.verify(password, settingsDataStore.data.first().guardianAuthConfig)

    suspend fun guardianPasswordConfigured(): Boolean =
        settingsDataStore.data.first().guardianAuthConfig.isConfigured

    /**
     * Guardian approval writes stay in the owner DataStore transaction. There is no exported
     * broadcast carrying a rule id or duration; the service observes the shared flow instead.
     */
    suspend fun grantAppRuleTime(
        password: String,
        ruleId: String,
        useDayId: String,
        durationMinutes: Long,
        grantedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (durationMinutes <= 0L ||
            durationMinutes > Long.MAX_VALUE / 60_000L ||
            ruleId.isBlank() ||
            useDayId.isBlank()
        ) return false
        val grantedMillis = durationMinutes * 60_000L
        val updated = settingsDataStore.updateData { current ->
            if (current.guardianAuthConfig.isConfigured &&
                !GuardianPassword.verify(password, current.guardianAuthConfig)
            ) return@updateData current
            val next = neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides.grant(
                neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides.compact(
                    current.appRuleOverrideState,
                    useDayId,
                    current.useDayGenerationStartedAtMs
                ),
                ruleId,
                useDayId,
                grantedMillis,
                grantedAtMs,
                current.useDayGenerationStartedAtMs
            )
            current.copy(appRuleOverrideState = next)
        }
        return GuardianDataStoreWriteResult.grantWasStored(
            updated,
            ruleId,
            useDayId,
            grantedMillis,
            grantedAtMs.coerceAtLeast(0L)
        )
    }

    suspend fun skipAppRuleUntil(
        password: String,
        ruleId: String,
        useDayId: String,
        selectedUntilMs: Long,
        nextResetAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (ruleId.isBlank() || useDayId.isBlank() || nextResetAtMs <= nowMs) return false
        val updated = settingsDataStore.updateData { current ->
            if (current.guardianAuthConfig.isConfigured &&
                !GuardianPassword.verify(password, current.guardianAuthConfig)
            ) return@updateData current
            val next = neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides.skipUntil(
                neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides.compact(
                    current.appRuleOverrideState,
                    useDayId,
                    current.useDayGenerationStartedAtMs
                ),
                ruleId,
                useDayId,
                selectedUntilMs,
                nextResetAtMs,
                nowMs,
                current.useDayGenerationStartedAtMs
            )
            current.copy(appRuleOverrideState = next)
        }
        return GuardianDataStoreWriteResult.skipWasStored(
            updated,
            ruleId,
            useDayId,
            nowMs,
            selectedUntilMs.coerceIn(nowMs, nextResetAtMs)
        )
    }

    /** Trusted internal callers use this after an already completed guardian session. */
    suspend fun writeAppRuleOverrideState(
        password: String,
        state: AppRuleOverrideState
    ): Boolean {
        val updated = settingsDataStore.updateData { current ->
            if (current.guardianAuthConfig.isConfigured &&
                !GuardianPassword.verify(password, current.guardianAuthConfig)
            ) return@updateData current
            current.copy(appRuleOverrideState = state)
        }
        return updated.appRuleOverrideState == state
    }

    /** Compacts the current local approval ledger without uploading or changing credentials. */
    suspend fun compactAppRuleOverrides(nowMs: Long = System.currentTimeMillis()): Boolean {
        val before = settingsDataStore.data.first()
        val updated = settingsDataStore.updateData { current ->
            val calculator = ConfigurableUseDayCalculator(resetTime = current.useDayResetTime)
            val useDayId = calculator.idAt(nowMs)
            val compacted = neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides.compact(
                current.appRuleOverrideState,
                useDayId,
                current.useDayGenerationStartedAtMs
            )
            current.copy(appRuleOverrideState = compacted)
        }
        return updated.appRuleOverrideState != before.appRuleOverrideState
    }

    suspend fun updateManualFocusGroups(newGroup: List<ManualFocusGroup>){
        settingsDataStore.updateData { it.copy(manualFocusGroups = newGroup) }
    }

    /**
     * Applies a configuration received from sync without letting another device bypass this
     * device's settings change delay. Runtime focus state stays on its dedicated sync channel,
     * and pending changes remain local because their deadlines are based on this device's clock.
     */
    suspend fun updateFromSync(remote: Settings) {
        settingsDataStore.updateData { current ->
            val delayConfig = current.settingsChangeDelayConfig2
            val timeGateActive = delayConfig.isEnabled && delayConfig.delayMinutes > 0
            val tamperGated = delayConfig.requireTamperProtectionOff &&
                current.antiUninstallConfig2.isEnabled

            val remoteWithLocalRuntime = remote.copy(
                blockedAppGroups = remote.blockedAppGroups.map { group ->
                    group.copy(
                        temporarilyDisabledUntilMs = current.blockedAppGroups
                            .find { it.id == group.id }
                            ?.temporarilyDisabledUntilMs
                            ?: 0L
                    )
                },
                reelBlockerConfig = remote.reelBlockerConfig.copy(
                    temporarilyDisabledUntilMs = current.reelBlockerConfig.temporarilyDisabledUntilMs
                ),
                keywordBlockerConfig = remote.keywordBlockerConfig.copy(
                    keywordGroups = remote.keywordBlockerConfig.keywordGroups.map { group ->
                        group.copy(
                            temporarilyDisabledUntilMs = current.keywordBlockerConfig.keywordGroups
                                .find { it.id == group.id }
                                ?.temporarilyDisabledUntilMs
                                ?: 0L
                        )
                    }
                ),
                antiUninstallConfig2 = remote.antiUninstallConfig2.copy(
                    unlockRequestedAtMs = current.antiUninstallConfig2.unlockRequestedAtMs
                ),
                serviceProtectionConfig = remote.serviceProtectionConfig.copy(
                    appBlockerLastAliveMs = current.serviceProtectionConfig.appBlockerLastAliveMs
                ),
            )

            // Start with non-gated configuration from the remote device. Each gated field is
            // restored to its local value and considered independently below.
            var updated = remoteWithLocalRuntime.copy(
                blockedAppGroups = current.blockedAppGroups,
                manualFocusGroups = current.manualFocusGroups,
                autoDndGroups = current.autoDndGroups,
                activeManualFocusGroupId = current.activeManualFocusGroupId,
                reelBlockerConfig = current.reelBlockerConfig,
                keywordBlockerConfig = current.keywordBlockerConfig,
                isReelCounterOn = current.isReelCounterOn,
                grayscaleGroups = current.grayscaleGroups,
                isAppUsageTrackingEnabled = current.isAppUsageTrackingEnabled,
                isWebsiteUsageTrackingEnabled = current.isWebsiteUsageTrackingEnabled,
                mindfulMessageConfig = current.mindfulMessageConfig,
                uiHiderConfig = current.uiHiderConfig,
                appRuleSnapshot = current.appRuleSnapshot,
                useDayResetHour = current.useDayResetHour,
                useDayResetMinute = current.useDayResetMinute,
                useDayGenerationStartedAtMs = current.useDayGenerationStartedAtMs,
                guardianAuthConfig = current.guardianAuthConfig,
                appRuleOverrideState = current.appRuleOverrideState,
                nextWebsiteRecheckTime = current.nextWebsiteRecheckTime,
                settingsChangeDelayConfig2 = delayConfig,
            )

            for (field in GatedSettingsField.values()) {
                val newValueJson = syncedFieldValueJson(remoteWithLocalRuntime, field) ?: continue
                val existingPending = updated.settingsChangeDelayConfig2.pendingChanges
                    .find { it.field == field.name }
                if (existingPending?.newValueJson == newValueJson) continue

                val proposed = withFieldValue(updated, field, newValueJson) ?: continue
                if ((!timeGateActive && !tamperGated) ||
                    RestrictionComparator.isSameOrStricter(field, current, proposed)
                ) {
                    updated = proposed.copy(
                        settingsChangeDelayConfig2 = proposed.settingsChangeDelayConfig2.copy(
                            pendingChanges = proposed.settingsChangeDelayConfig2.pendingChanges
                                .filterNot { it.field == field.name }
                        )
                    )
                } else {
                    val now = System.currentTimeMillis()
                    val delayMinutes = delayConfig.delayMinutes.coerceIn(
                        0,
                        SettingsChangeDelayConfig.MAX_DELAY_MINUTES,
                    )
                    val appliesAt = if (timeGateActive) now + delayMinutes * 60_000L else now
                    val pending = PendingSettingsChange(
                        field = field.name,
                        newValueJson = newValueJson,
                        requestedAtMs = now,
                        appliesAtMs = appliesAt,
                    )
                    updated = updated.copy(
                        settingsChangeDelayConfig2 = updated.settingsChangeDelayConfig2.copy(
                            pendingChanges = updated.settingsChangeDelayConfig2.pendingChanges
                                .filterNot { it.field == field.name } + pending
                        )
                    )
                }
            }
            updated
        }
        schedulePendingChanges()
    }

    suspend fun updateAutoDndGroups(newGroups: List<neth.iecal.curbox.data.models.AutoDndGroup>) {
        updateGated(GatedSettingsField.AUTO_DND_GROUPS) { newGroups }
    }
    
    suspend fun setManualFocusStateToActive(focusGroupId:String, durationInMs: Long){
        settingsDataStore.updateData { it.copy(activeManualFocusGroupId = Pair(focusGroupId, System.currentTimeMillis() + durationInMs)) }
    }
    suspend fun setManualFocusStateToInactive(){
        settingsDataStore.updateData { it.copy(activeManualFocusGroupId = Pair(null, 0)) }
    }

    suspend fun updateReelBlockerConfig(config: neth.iecal.curbox.data.models.ReelBlocker) {
        updateGated(GatedSettingsField.REEL_BLOCKER) { config.upgradeLegacyConfig(gson) }
    }

    suspend fun updateKeywordBlockerConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        updateGated(GatedSettingsField.KEYWORD_BLOCKER) { transform(it.keywordBlockerConfig) }
    }

    suspend fun updateReelCounterState(isActive: Boolean) {
        updateGated(GatedSettingsField.REEL_COUNTER) { isActive }
    }

    suspend fun updateGrayscaleGroups(newGroups: List<neth.iecal.curbox.data.models.GrayscaleGroup>) {
        updateGated(GatedSettingsField.GRAYSCALE_GROUPS) { newGroups }
    }

    suspend fun updateUsageTrackerIgnoredApps(newApps: List<String>) {
        settingsDataStore.updateData { it.copy(usageTrackerIgnoredApps = newApps) }
    }

    suspend fun updateAppUsageTrackingEnabled(isEnabled: Boolean) {
        updateGated(GatedSettingsField.APP_USAGE_TRACKING) { isEnabled }
    }

    /**
     * Changes the global use-day boundary. This is local runtime configuration rather than a
     * restriction snapshot: the tracker observes the settings flow and closes the old session at
     * the moment the new boundary is saved. Existing aggregate rows are never rewritten.
     */
    suspend fun updateUseDayResetTime(hour: Int, minute: Int) {
        require(hour in 0..23) { "reset hour must be between 0 and 23" }
        require(minute in 0..59) { "reset minute must be between 0 and 59" }
        settingsDataStore.updateData {
            if (it.useDayResetHour == hour && it.useDayResetMinute == minute) {
                it
            } else {
                val generation = System.currentTimeMillis()
                it.copy(
                    useDayResetHour = hour,
                    useDayResetMinute = minute,
                    useDayGenerationStartedAtMs = generation,
                    appRuleOverrideState = AppRuleOverrideState(
                        useDayGenerationStartedAtMs = generation
                    )
                )
            }
        }
        sendAppRuleRefreshBroadcast()
    }

    suspend fun updateUseDayResetMinutes(minutesSinceMidnight: Int) {
        require(minutesSinceMidnight in 0 until 24 * 60) {
            "reset time must be within one local day"
        }
        updateUseDayResetTime(minutesSinceMidnight / 60, minutesSinceMidnight % 60)
    }

    suspend fun updateUseDayResetTime(resetTime: UseDayResetTime) {
        updateUseDayResetTime(resetTime.hour, resetTime.minute)
    }

    suspend fun updateWebsiteUsageTrackingEnabled(isEnabled: Boolean) {
        updateGated(GatedSettingsField.WEBSITE_USAGE_TRACKING) { isEnabled }
    }

    suspend fun updateMindfulMessageConfig(config: neth.iecal.curbox.data.models.MindfulMessageConfig) {
        updateGated(GatedSettingsField.MINDFUL_MESSAGES) { config }
    }

    suspend fun updateUiHiderConfig(transform: (neth.iecal.curbox.data.models.UiHiderConfig) -> neth.iecal.curbox.data.models.UiHiderConfig) {
        updateGated(GatedSettingsField.UI_HIDER) { transform(it.uiHiderConfig.normalized()) }
    }

    suspend fun updateReelCounterOverlayConfig(config: neth.iecal.curbox.data.models.ReelCounterOverlayConfig) {
        settingsDataStore.updateData { it.copy(reelCounterOverlayConfig = config) }
    }

    suspend fun updateNextWebsiteRecheckTime(time: Long) {
        settingsDataStore.updateData { it.copy(nextWebsiteRecheckTime = time) }
    }

    suspend fun updateAntiUninstallConfig(transform: (neth.iecal.curbox.data.models.AntiUninstallConfig) -> neth.iecal.curbox.data.models.AntiUninstallConfig) {
        settingsDataStore.updateData { it.copy(antiUninstallConfig2 = transform(it.antiUninstallConfig2)) }
        // Turning tamper protection off may release changes whose time delay already elapsed.
        applyDuePendingChanges()
    }

    suspend fun updateServiceProtectionConfig(transform: (neth.iecal.curbox.data.models.ServiceProtectionConfig) -> neth.iecal.curbox.data.models.ServiceProtectionConfig) {
        settingsDataStore.updateData { it.copy(serviceProtectionConfig = transform(it.serviceProtectionConfig)) }
    }

    suspend fun updateSettingsChangeDelay(
        isEnabled: Boolean,
        delayMinutes: Int,
        requireTamperProtectionOff: Boolean
    ) {
        val clamped = delayMinutes.coerceIn(0, SettingsChangeDelayConfig.MAX_DELAY_MINUTES)
        updateGated(GatedSettingsField.CHANGE_DELAY) {
            SettingsChangeDelayPrefs(
                isEnabled,
                clamped,
                requireTamperProtectionOff
            )
        }
    }

    suspend fun temporarilyDisableAppGroup(groupId: String, durationMinutes: Long): Boolean {
        val untilMs = temporaryDisableDeadline(durationMinutes)
        var changed = false
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            if (current.settingsChangeDelayConfig2.isEnabled) return@updateData current
            val groups = current.blockedAppGroups.map { group ->
                if (group.id == groupId && group.isActive) {
                    changed = true
                    group.copy(isActive = false, temporarilyDisabledUntilMs = untilMs)
                } else {
                    group
                }
            }
            val updated = current.copy(blockedAppGroups = groups)
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed) {
            neth.iecal.curbox.services.TemporaryGroupDisableJob.schedule(context, nextDeadline)
        }
        return changed
    }

    suspend fun temporarilyDisableKeywordGroup(groupId: String, durationMinutes: Long): Boolean {
        val untilMs = temporaryDisableDeadline(durationMinutes)
        var changed = false
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            if (current.settingsChangeDelayConfig2.isEnabled) return@updateData current
            val config = current.keywordBlockerConfig
            val groups = config.keywordGroups.map { group ->
                if (group.id == groupId && group.isActive) {
                    changed = true
                    group.copy(isActive = false, temporarilyDisabledUntilMs = untilMs)
                } else group
            }
            val updated = current.copy(keywordBlockerConfig = config.copy(keywordGroups = groups))
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed) TemporaryGroupDisableJob.schedule(context, nextDeadline)
        return changed
    }

    suspend fun temporarilyDisableReelBlocker(durationMinutes: Long): Boolean {
        val untilMs = temporaryDisableDeadline(durationMinutes)
        var changed = false
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            if (current.settingsChangeDelayConfig2.isEnabled ||
                !current.reelBlockerConfig.isActive
            ) return@updateData current
            changed = true
            val updated = current.copy(
                reelBlockerConfig = current.reelBlockerConfig.copy(
                    isActive = false,
                    temporarilyDisabledUntilMs = untilMs
                )
            )
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        if (changed) TemporaryGroupDisableJob.schedule(context, nextDeadline)
        return changed
    }

    suspend fun restoreDueTemporaryAppGroups() {
        var nextDeadline: Long? = null
        settingsDataStore.updateData { current ->
            val now = System.currentTimeMillis()
            val groups = current.blockedAppGroups.map { group ->
                when {
                    group.temporarilyDisabledUntilMs in 1..now ->
                        group.copy(isActive = true, temporarilyDisabledUntilMs = 0L)
                    group.temporarilyDisabledUntilMs > now -> {
                        nextDeadline = minOf(nextDeadline ?: Long.MAX_VALUE, group.temporarilyDisabledUntilMs)
                        group
                    }
                    else -> group
                }
            }
            val keywordConfig = current.keywordBlockerConfig
            val keywordGroups = keywordConfig.keywordGroups.map { group ->
                if (group.temporarilyDisabledUntilMs in 1..now) {
                    group.copy(isActive = true, temporarilyDisabledUntilMs = 0L)
                } else group
            }
            val reelConfig = current.reelBlockerConfig.let { config ->
                if (config.temporarilyDisabledUntilMs in 1..now) {
                    config.copy(isActive = true, temporarilyDisabledUntilMs = 0L)
                } else config
            }
            val updated = current.copy(
                blockedAppGroups = groups,
                keywordBlockerConfig = keywordConfig.copy(keywordGroups = keywordGroups),
                reelBlockerConfig = reelConfig
            )
            nextDeadline = earliestTemporaryDisable(updated)
            updated
        }
        neth.iecal.curbox.services.TemporaryGroupDisableJob.schedule(context, nextDeadline)
    }

    private fun temporaryDisableDeadline(durationMinutes: Long): Long =
        if (durationMinutes == TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED) {
            TemporaryDisableDialog.UNTIL_MANUALLY_ENABLED
        } else {
            System.currentTimeMillis() + durationMinutes.coerceIn(1L, 43_200L) * 60_000L
        }

    private fun earliestTemporaryDisable(settings: Settings): Long? {
        val deadlines = buildList {
            addAll(settings.blockedAppGroups.map { it.temporarilyDisabledUntilMs })
            addAll(
                settings.keywordBlockerConfig.keywordGroups.map {
                    it.temporarilyDisabledUntilMs
                }
            )
            add(settings.reelBlockerConfig.temporarilyDisabledUntilMs)
        }
        val now = System.currentTimeMillis()
        return deadlines.filter { it > now }.minOrNull()
    }

    suspend fun cancelPendingSettingsChange(fieldName: String) {
        settingsDataStore.updateData {
            val config = it.settingsChangeDelayConfig2
            it.copy(settingsChangeDelayConfig2 = config.copy(
                pendingChanges = config.pendingChanges.filterNot { p -> p.field == fieldName }
            ))
        }
        schedulePendingChanges()
    }

    /**
     * Writes any pending settings change whose countdown has run out into the live settings.
     * Called from the blocking service heartbeat, app start and the change delay screen, so
     * a due change lands no matter which process is alive. Returns true if anything applied.
     */
    suspend fun applyDuePendingChanges(): Boolean {
        var appliedAny = false
        settingsDataStore.updateData { current ->
            val now = System.currentTimeMillis()
            val delayConfig = current.settingsChangeDelayConfig2
            // Tamper protection being on holds every pending change back, no matter how long
            // its own timer has already run out, until the user turns tamper protection off
            val tamperBlocked = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig2.isEnabled
            val (due, waiting) = delayConfig.pendingChanges
                .partition { it.appliesAtMs <= now && !tamperBlocked }
            appliedAny = due.isNotEmpty()
            if (due.isEmpty()) {
                current
            } else {
                var settings = current
                due.forEach { change ->
                    applyPendingValue(
                        settings,
                        change,
                        effectiveAtNowMs = now
                    )?.let { settings = it }
                }
                settings.copy(settingsChangeDelayConfig2 = settings.settingsChangeDelayConfig2.copy(
                    pendingChanges = waiting
                ))
            }
        }
        schedulePendingChanges()
        if (appliedAny) {
            sendAppRuleRefreshBroadcast()
        }
        return appliedAny
    }

    private fun sendAppRuleRefreshBroadcast() {
        runCatching {
            context.sendBroadcast(
                Intent(neth.iecal.curbox.blockers.AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES)
                    .setPackage(context.packageName)
            )
        }
    }

    /**
     * The settings change delay gate. Changes that keep every restriction at least as strong
     * apply right away; anything else is parked as a [PendingSettingsChange] until the
     * countdown ends. A stricter instant write also drops the field's pending change, because
     * that pending snapshot no longer matches what the user sees.
     */
    private suspend fun updateGated(
        field: GatedSettingsField,
        appGroupEditMode: AppGroupEditMode? = null,
        computeNewValue: (Settings) -> Any
    ) {
        var deferredUntilMs = 0L
        var hadPendingForField = false
        var tamperGated = false
        var unchangedPending = false
        settingsDataStore.updateData { current ->
            deferredUntilMs = 0L
            hadPendingForField = false
            tamperGated = false
            val newValueJson = gson.toJson(computeNewValue(current))
            val proposed = withFieldValue(current, field, newValueJson) ?: return@updateData current
            val delayConfig = current.settingsChangeDelayConfig2
            val existingPending = delayConfig.pendingChanges.find { it.field == field.name }
            hadPendingForField = existingPending != null
            val pendingGroupModes = if (field == GatedSettingsField.APP_RULES) {
                val existingPendingSnapshot = existingPending?.let { pending ->
                    withFieldValue(current, field, pending.newValueJson)?.appRuleSnapshot
                }
                pendingAppGroupEditModes(
                    previous = current.appRuleSnapshot,
                    proposed = proposed.appRuleSnapshot,
                    requestedMode = appGroupEditMode,
                    existing = existingPending?.appGroupEditModes.orEmpty(),
                    existingProposed = existingPendingSnapshot
                )
            } else {
                emptyMap()
            }
            if (existingPending?.newValueJson == newValueJson &&
                existingPending?.appGroupEditModes.orEmpty() == pendingGroupModes
            ) {
                unchangedPending = true
                return@updateData current
            }
            val timeGateActive = delayConfig.isEnabled && delayConfig.delayMinutes > 0
            tamperGated = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig2.isEnabled
            val transactionNowMs = System.currentTimeMillis()
            if ((!timeGateActive && !tamperGated) ||
                RestrictionComparator.isSameOrStricter(field, current, proposed)
            ) {
                tamperGated = false
                val proposedDelayConfig = proposed.settingsChangeDelayConfig2
                val effective = if (field == GatedSettingsField.APP_RULES &&
                    pendingGroupModes.isNotEmpty()
                ) {
                    applyGroupEditEffectiveAt(proposed, current, pendingGroupModes, transactionNowMs)
                } else {
                    proposed
                }
                val effectiveDelayConfig = effective.settingsChangeDelayConfig2
                effective.copy(settingsChangeDelayConfig2 = effectiveDelayConfig.copy(
                    pendingChanges = effectiveDelayConfig.pendingChanges.filterNot { it.field == field.name }
                ))
            } else {
                val now = transactionNowMs
                // With no active timer, the pending value is due the instant tamper protection
                // turns off; applyDuePendingChanges re-checks that condition on every sweep.
                deferredUntilMs = if (timeGateActive) now + delayConfig.delayMinutes * 60_000L else now
                val pending = PendingSettingsChange(
                    field = field.name,
                    newValueJson = newValueJson,
                    requestedAtMs = now,
                    appliesAtMs = deferredUntilMs,
                    appGroupEditModes = if (field == GatedSettingsField.APP_RULES) {
                        pendingGroupModes
                    } else {
                        emptyMap()
                    }
                )
                current.copy(settingsChangeDelayConfig2 = delayConfig.copy(
                    pendingChanges = delayConfig.pendingChanges.filterNot { it.field == field.name } + pending
                ))
            }
        }
        schedulePendingChanges()
        if (unchangedPending) {
            return
        } else if (deferredUntilMs > 0L) {
            notifyChangeDeferred(field, deferredUntilMs, hadPendingForField, tamperGated)
        } else if (hadPendingForField) {
            notifyPendingChangeDropped(field)
        }
    }

    private fun applyPendingValue(
        settings: Settings,
        change: PendingSettingsChange,
        effectiveAtNowMs: Long? = null
    ): Settings? {
        val field = runCatching { GatedSettingsField.valueOf(change.field) }.getOrNull() ?: return null
        val parsed = withFieldValue(settings, field, change.newValueJson) ?: return null
        return if (field == GatedSettingsField.APP_RULES &&
            change.appGroupEditModes.isNotEmpty() &&
            effectiveAtNowMs != null
        ) {
            applyGroupEditEffectiveAt(
                parsed,
                settings,
                change.appGroupEditModes,
                effectiveAtNowMs
            )
        } else {
            parsed
        }
    }

    private fun overlayPendingChanges(settings: Settings): Settings {
        var displayed = settings
        settings.settingsChangeDelayConfig2.pendingChanges.forEach { change ->
            // The editor sees the requested membership, but not a fabricated effective timestamp.
            applyPendingValue(displayed, change)?.let { displayed = it }
        }
        return displayed
    }

    private fun applyGroupEditEffectiveAt(
        proposed: Settings,
        previous: Settings,
        modesByGroupId: Map<String, AppGroupEditMode>,
        nowMs: Long
    ): Settings {
        val calculator = ConfigurableUseDayCalculator(resetTime = previous.useDayResetTime)
        val effectiveAtByGroup = modesByGroupId.mapValues { (_, mode) ->
            AppGroupMembershipTimeline.effectiveAt(mode, nowMs, calculator)
        }
        return proposed.copy(
            appRuleSnapshot = AppGroupMembershipTimeline.apply(
                previous = previous.appRuleSnapshot,
                proposed = proposed.appRuleSnapshot,
                effectiveAtByGroup = effectiveAtByGroup
            )
        )
    }

    private fun pendingAppGroupEditModes(
        previous: AppRuleSnapshot,
        proposed: AppRuleSnapshot,
        requestedMode: AppGroupEditMode?,
        existing: Map<String, AppGroupEditMode>,
        existingProposed: AppRuleSnapshot?
    ): Map<String, AppGroupEditMode> {
        val previousById = previous.appGroups.associateBy { it.id }
        val proposedById = proposed.appGroups.associateBy { it.id }
        val changedIds = (previousById.keys + proposedById.keys).filter { id ->
            previousById[id]?.selectedPackages?.toSet() != proposedById[id]?.selectedPackages?.toSet()
        }.toSet()
        val retained = existing.filterKeys { it in changedIds && it in proposedById }
        val editedSincePending = if (existingProposed == null) {
            changedIds
        } else {
            val pendingById = existingProposed.appGroups.associateBy { it.id }
            (pendingById.keys + proposedById.keys).filter { id ->
                pendingById[id]?.selectedPackages?.toSet() != proposedById[id]?.selectedPackages?.toSet()
            }.toSet()
        }
        return if (requestedMode == null) {
            retained
        } else {
            retained + editedSincePending.associateWith { requestedMode }
        }
    }

    private suspend fun schedulePendingChanges() {
        val pending = settingsDataStore.data.first().settingsChangeDelayConfig2.pendingChanges
        neth.iecal.curbox.services.PendingSettingsChangeJob.schedule(
            context.applicationContext,
            pending
        )
    }

    private fun withFieldValue(settings: Settings, field: GatedSettingsField, valueJson: String): Settings? {
        return runCatching {
            when (field) {
                GatedSettingsField.APP_GROUPS -> settings.copy(
                    blockedAppGroups = gson.fromJson<List<AppGroup>>(
                        valueJson,
                        object : TypeToken<List<AppGroup>>() {}.type
                    ).upgradeLegacyAppGroupConfigs(gson)
                )
                GatedSettingsField.APP_RULES -> settings.copy(
                    appRuleSnapshot = gson.fromJson(
                        valueJson,
                        AppRuleSnapshot::class.java
                    ).also { snapshot ->
                        if (!snapshot.isValid) throw IllegalArgumentException("Invalid app rule snapshot")
                    }
                )
                GatedSettingsField.AUTO_DND_GROUPS -> settings.copy(
                    autoDndGroups = gson.fromJson(valueJson, object : TypeToken<List<neth.iecal.curbox.data.models.AutoDndGroup>>() {}.type)
                )
                GatedSettingsField.REEL_BLOCKER -> settings.copy(
                    reelBlockerConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.ReelBlocker::class.java)
                        .upgradeLegacyConfig(gson)
                )
                GatedSettingsField.KEYWORD_BLOCKER -> settings.copy(
                    keywordBlockerConfig = gson.fromJson(
                        valueJson,
                        neth.iecal.curbox.data.models.KeywordBlocker::class.java
                    ).upgradeLegacyKeywordGroupConfigs(gson)
                )
                GatedSettingsField.REEL_COUNTER -> settings.copy(
                    isReelCounterOn = gson.fromJson(valueJson, Boolean::class.java)
                )
                GatedSettingsField.GRAYSCALE_GROUPS -> settings.copy(
                    grayscaleGroups = gson.fromJson(valueJson, object : TypeToken<List<neth.iecal.curbox.data.models.GrayscaleGroup>>() {}.type)
                )
                GatedSettingsField.MINDFUL_MESSAGES -> settings.copy(
                    mindfulMessageConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.MindfulMessageConfig::class.java)
                )
                GatedSettingsField.UI_HIDER -> settings.copy(
                    uiHiderConfig = gson.fromJson(valueJson, neth.iecal.curbox.data.models.UiHiderConfig::class.java)
                )
                GatedSettingsField.APP_USAGE_TRACKING -> settings.copy(
                    isAppUsageTrackingEnabled = gson.fromJson(valueJson, Boolean::class.java)
                )
                GatedSettingsField.WEBSITE_USAGE_TRACKING -> settings.copy(
                    isWebsiteUsageTrackingEnabled = gson.fromJson(valueJson, Boolean::class.java)
                )
                GatedSettingsField.CHANGE_DELAY -> {
                    val prefs = gson.fromJson(valueJson, SettingsChangeDelayPrefs::class.java)
                    settings.copy(settingsChangeDelayConfig2 = settings.settingsChangeDelayConfig2.copy(
                        isEnabled = prefs.isEnabled,
                        delayMinutes = prefs.delayMinutes,
                        requireTamperProtectionOff = prefs.requireTamperProtectionOff
                    ))
                }
            }
        }.getOrNull()
    }

    private fun syncedFieldValueJson(settings: Settings, field: GatedSettingsField): String? =
        runCatching {
            val value: Any = when (field) {
                GatedSettingsField.APP_GROUPS -> settings.blockedAppGroups
                GatedSettingsField.APP_RULES -> settings.appRuleSnapshot
                GatedSettingsField.AUTO_DND_GROUPS -> settings.autoDndGroups
                GatedSettingsField.REEL_BLOCKER -> settings.reelBlockerConfig
                GatedSettingsField.KEYWORD_BLOCKER -> settings.keywordBlockerConfig
                GatedSettingsField.REEL_COUNTER -> settings.isReelCounterOn
                GatedSettingsField.GRAYSCALE_GROUPS -> settings.grayscaleGroups
                GatedSettingsField.MINDFUL_MESSAGES -> settings.mindfulMessageConfig
                GatedSettingsField.UI_HIDER -> settings.uiHiderConfig
                GatedSettingsField.APP_USAGE_TRACKING -> settings.isAppUsageTrackingEnabled
                GatedSettingsField.WEBSITE_USAGE_TRACKING -> settings.isWebsiteUsageTrackingEnabled
                GatedSettingsField.CHANGE_DELAY -> SettingsChangeDelayPrefs(
                    isEnabled = settings.settingsChangeDelayConfig2.isEnabled,
                    delayMinutes = settings.settingsChangeDelayConfig2.delayMinutes.coerceIn(
                        0,
                        SettingsChangeDelayConfig.MAX_DELAY_MINUTES,
                    ),
                    requireTamperProtectionOff =
                        settings.settingsChangeDelayConfig2.requireTamperProtectionOff,
                )
            }
            gson.toJson(value)
        }.getOrNull()

    /**
     * Pops the review warning screen so an accidental weakening can be undone on the spot.
     * Falls back to a toast when the screen cannot be shown, for example when the change came
     * from the Curbox API while the app is in the background.
     */
    private fun notifyChangeDeferred(
        field: GatedSettingsField,
        appliesAtMs: Long,
        replacedExisting: Boolean,
        tamperGated: Boolean
    ) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent(appContext, neth.iecal.curbox.ui.activity.PendingChangeReviewActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_FIELD, field.name)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_APPLIES_AT_MS, appliesAtMs)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_REPLACED_EXISTING, replacedExisting)
                    putExtra(neth.iecal.curbox.ui.activity.PendingChangeReviewActivity.EXTRA_TAMPER_GATED, tamperGated)
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val message = if (tamperGated) {
                        appContext.getString(R.string.change_delay_tamper_gated_toast)
                    } else {
                        val remaining = SettingsChangeDelayUtils.formatRemaining(appContext, appliesAtMs - System.currentTimeMillis())
                        appContext.getString(R.string.change_delay_deferred_toast, remaining)
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun notifyPendingChangeDropped(field: GatedSettingsField) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(
                    appContext,
                    appContext.getString(
                        R.string.change_delay_dropped_toast,
                        SettingsChangeDelayUtils.fieldLabel(appContext, field.name)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Pure success predicates for guardian writes.  The caller must use the value returned by
 * DataStore.updateData; inspecting mutable state from inside its transform is not retry safe.
 */
internal object GuardianDataStoreWriteResult {
    fun passwordWasStored(settings: Settings, credential: GuardianAuthConfig): Boolean =
        settings.guardianAuthConfig == credential

    fun grantWasStored(
        settings: Settings,
        ruleId: String,
        useDayId: String,
        grantedMillis: Long,
        grantedAtMs: Long
    ): Boolean = settings.appRuleOverrideState.grants.any {
        it.ruleId == ruleId &&
            it.useDayId == useDayId &&
            it.grantedMillis == grantedMillis &&
            it.grantedAtMs == grantedAtMs
    }

    fun skipWasStored(
        settings: Settings,
        ruleId: String,
        useDayId: String,
        skipFromMs: Long,
        skipUntilMs: Long
    ): Boolean = settings.appRuleOverrideState.skips.any {
        it.ruleId == ruleId &&
            it.useDayId == useDayId &&
            it.skipFromMs == skipFromMs &&
            it.skipUntilMs == skipUntilMs &&
            it.skipUntilMs > it.skipFromMs
    }
}
