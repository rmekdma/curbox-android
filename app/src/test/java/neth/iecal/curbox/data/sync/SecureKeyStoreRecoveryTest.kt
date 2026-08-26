package neth.iecal.curbox.data.sync

import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureKeyStoreRecoveryTest {
    @Test
    fun resetsOnlyAnEncryptedPreferencesAuthenticationFailure() {
        assertTrue(AEADBadTagException().isEncryptedPreferencesAuthenticationFailure())
        assertTrue(
            GeneralSecurityException(
                "could not decrypt restored keyset",
                AEADBadTagException()
            ).isEncryptedPreferencesAuthenticationFailure()
        )
        assertFalse(IOException("storage unavailable").isEncryptedPreferencesAuthenticationFailure())
    }
}
