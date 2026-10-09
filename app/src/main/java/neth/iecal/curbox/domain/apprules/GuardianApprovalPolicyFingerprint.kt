package neth.iecal.curbox.domain.apprules

import com.google.gson.Gson
import neth.iecal.curbox.data.models.Settings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Identifies the exact settings snapshot used when an allowed approval result was evaluated. */
object GuardianApprovalPolicyFingerprint {
    private val gson = Gson()
    private val hexDigits = "0123456789abcdef"

    fun forSettings(settings: Settings): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(settings).toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hexDigits[value ushr 4])
                append(hexDigits[value and 0x0f])
            }
        }
    }
}
