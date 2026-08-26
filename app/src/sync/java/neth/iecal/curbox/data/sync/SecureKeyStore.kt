package neth.iecal.curbox.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import javax.crypto.AEADBadTagException

class SecureKeyStore(context: Context) {
    private val prefs = try {
        createEncryptedPreferences(context)
    } catch (error: Exception) {
        if (!error.isEncryptedPreferencesAuthenticationFailure() ||
            !context.deleteSharedPreferences(PREFERENCES_NAME)
        ) {
            throw error
        }
        createEncryptedPreferences(context)
    }

    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var dekB64: String?
        get() = prefs.getString("dek", null)
        set(v) = prefs.edit().putString("dek", v).apply()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    fun setSession(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .apply()
    }

    var cursor: String
        get() = prefs.getString("cursor", "1970-01-01T00:00:00Z")!!
        set(v) = prefs.edit().putString("cursor", v).apply()

    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(v) = prefs.edit().putString("fcm_token", v).apply()

    @Volatile
    private var cachedDeviceId: String? = prefs.getString("device_id", null)

    val deviceId: String
        get() = cachedDeviceId ?: synchronized(this) {
            cachedDeviceId
                ?: prefs.getString("device_id", null)?.also { cachedDeviceId = it }
                ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("device_id", it).commit()) { "could not save sync device id" }
                    cachedDeviceId = it
                }
        }

    var deviceName: String
        get() = prefs.getString("device_name", "Android")!!
        set(v) = prefs.edit().putString("device_name", v).apply()

    var syncUsageStats: Boolean
        get() = prefs.getBoolean("sync_usage_stats", true)
        set(v) = prefs.edit().putBoolean("sync_usage_stats", v).apply()

    var syncReducerConfigs: Boolean
        get() = prefs.getBoolean("sync_reducer_configs", true)
        set(v) = prefs.edit().putBoolean("sync_reducer_configs", v).apply()

    var usageDeviceIds: Set<String>
        get() = prefs.getStringSet("usage_device_ids", emptySet())?.toSet().orEmpty()
        set(v) = prefs.edit().putStringSet("usage_device_ids", HashSet(v)).apply()

    var knownFocusGroupIds: Set<String>
        get() = prefs.getStringSet("known_focus_group_ids", emptySet())?.toSet().orEmpty()
        set(v) = prefs.edit().putStringSet("known_focus_group_ids", HashSet(v)).apply()

    fun clear() {
        synchronized(this) {
            prefs.edit()
                .remove("dek")
                .remove("access_token")
                .remove("refresh_token")
                .remove("cursor")
                .remove("fcm_token")
                .remove("device_id")
                .remove("usage_device_ids")
                .remove("known_focus_group_ids")
                .apply()
            cachedDeviceId = null
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "curbox_sync_secrets"
    }
}

internal fun Throwable.isEncryptedPreferencesAuthenticationFailure(): Boolean =
    generateSequence(this) { it.cause }
        .any { it is AEADBadTagException }
