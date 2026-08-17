package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.GuardianAuthConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password derivation for the local guardian credential. */
object GuardianPassword {
    const val DEFAULT_ITERATIONS = 120_000
    const val SALT_BYTES = 16
    const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun createCredential(
        password: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS
    ): GuardianAuthConfig {
        if (password.isEmpty()) return GuardianAuthConfig()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val safeIterations = iterations.coerceAtLeast(1)
        val derived = derive(password, salt, safeIterations)
        return GuardianAuthConfig(
            passwordSalt = Base64.getEncoder().encodeToString(salt),
            passwordVerifier = Base64.getEncoder().encodeToString(derived),
            kdfAlgorithm = ALGORITHM,
            kdfIterations = safeIterations
        )
    }

    fun verify(password: String, config: GuardianAuthConfig): Boolean {
        if (!config.isConfigured || password.isEmpty()) return false
        return try {
            val salt = Base64.getDecoder().decode(config.passwordSalt)
            val expected = Base64.getDecoder().decode(config.passwordVerifier)
            val actual = derive(
                password,
                salt,
                config.kdfIterations.coerceAtLeast(1),
                config.kdfAlgorithm
            )
            MessageDigest.isEqual(expected, actual)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
        algorithm: String = ALGORITHM
    ): ByteArray {
        val factory = SecretKeyFactory.getInstance(algorithm)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Public seam for deciding whether a focus transition invalidates a guardian session.  Internal
 * Curbox dialogs do not invalidate it, while unknown or external surfaces do.
 */
enum class GuardianFocusSurface {
    CURBOX_CONTENT,
    CURBOX_DIALOG,
    EXTERNAL_APP,
    EXTERNAL_SYSTEM,
    ONE_SHOT_SYSTEM_RESULT,
    UNKNOWN
}

object GuardianReauthPolicy {
    fun requiresReauthentication(
        hasPassword: Boolean,
        previous: GuardianFocusSurface,
        next: GuardianFocusSurface
    ): Boolean {
        if (!hasPassword) return false
        if (previous == GuardianFocusSurface.CURBOX_DIALOG &&
            next == GuardianFocusSurface.CURBOX_CONTENT
        ) return false
        if (previous == GuardianFocusSurface.ONE_SHOT_SYSTEM_RESULT &&
            next == GuardianFocusSurface.CURBOX_CONTENT
        ) return false
        return next == GuardianFocusSurface.EXTERNAL_APP ||
            next == GuardianFocusSurface.EXTERNAL_SYSTEM ||
            next == GuardianFocusSurface.UNKNOWN
    }
}

/** In memory session used by activities. The credential itself remains in DataStore. */
class GuardianAuthSession {
    private var authenticated = false
    private var currentSurface = GuardianFocusSurface.CURBOX_CONTENT
    private var oneShotSystemResult = false

    fun isAuthenticated(hasPassword: Boolean): Boolean = !hasPassword || authenticated

    fun authenticate(password: String, config: GuardianAuthConfig): Boolean {
        val valid = !config.isConfigured || GuardianPassword.verify(password, config)
        if (valid) authenticated = true
        return valid
    }

    fun clear() {
        authenticated = false
        oneShotSystemResult = false
    }

    fun markOneShotSystemResult() {
        oneShotSystemResult = true
        currentSurface = GuardianFocusSurface.ONE_SHOT_SYSTEM_RESULT
    }

    fun transition(next: GuardianFocusSurface, hasPassword: Boolean): Boolean {
        val preserveOneShot = oneShotSystemResult &&
            next == GuardianFocusSurface.CURBOX_CONTENT
        val requires = if (preserveOneShot) {
            false
        } else {
            GuardianReauthPolicy.requiresReauthentication(hasPassword, currentSurface, next)
        }
        if (requires) authenticated = false
        currentSurface = next
        if (next != GuardianFocusSurface.ONE_SHOT_SYSTEM_RESULT) oneShotSystemResult = false
        return requires
    }
}
