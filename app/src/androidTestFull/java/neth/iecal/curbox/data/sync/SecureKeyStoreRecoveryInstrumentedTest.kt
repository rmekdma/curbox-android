package neth.iecal.curbox.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureKeyStoreRecoveryInstrumentedTest {
    @Test
    fun recreatesPreferencesAfterTamperedKeyset() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(testPreferencesName(name), mode)

            override fun deleteSharedPreferences(name: String): Boolean =
                super.deleteSharedPreferences(testPreferencesName(name))

            private fun testPreferencesName(name: String): String =
                if (name == PREFERENCES_NAME) TEST_PREFERENCES_NAME else name
        }

        context.deleteSharedPreferences(PREFERENCES_NAME)
        try {
            SecureKeyStore(context)
            val rawPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val keyset = requireNotNull(rawPreferences.getString(KEY_KEYSET, null))
            require(keyset.length > 1)
            val replacement = keyset.dropLast(1) + if (keyset.last() == '0') '1' else '0'
            assertTrue(rawPreferences.edit().putString(KEY_KEYSET, replacement).commit())

            val recovered = SecureKeyStore(context)

            assertNotNull(recovered.deviceId)
        } finally {
            context.deleteSharedPreferences(PREFERENCES_NAME)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "curbox_sync_secrets"
        const val TEST_PREFERENCES_NAME = "curbox_sync_secrets_recovery_test"
        const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    }
}
