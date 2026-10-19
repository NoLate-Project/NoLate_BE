package com.noLate.journey.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** No default encryption secret. Keep the same externally supplied key across restarts. */
@Component
class JourneyActivityCredentials(@Value("\${journey.activity.encryption-key:}") private val encodedKey: String = "") {
    private fun key(): SecretKeySpec {
        val bytes = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()
        check(bytes?.size == 32) { "JOURNEY_ACTIVITY_KEY_UNAVAILABLE" }
        return SecretKeySpec(bytes, "AES")
    }
    fun seal(session: String, fcm: String, token: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(session.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal("$fcm\n$token".toByteArray(Charsets.UTF_8)))
    }
    /** Fixed sanitized error: neither input nor crypto exception is exposed. */
    fun open(session: String, sealed: String): Pair<String, String> = try {
        val bytes = Base64.getDecoder().decode(sealed)
        require(bytes.size in 29..9000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(session.toByteArray(Charsets.UTF_8))
        val values = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8).split('\n')
        require(values.size == 2)
        values[0] to values[1]
    } catch (_: Exception) { throw IllegalStateException("JOURNEY_ACTIVITY_CREDENTIALS_UNAVAILABLE") }
    companion object {
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
