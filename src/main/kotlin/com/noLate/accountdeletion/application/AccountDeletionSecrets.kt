package com.noLate.accountdeletion.application

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class AccountDeletionSecrets(
    private val properties: AccountDeletionProperties,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun normalizeEmail(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 3..254 }
            ?: return null
        return normalized.takeIf(EMAIL_PATTERN::matches)
    }

    fun newVerificationCode(): String =
        buildString(VERIFICATION_CODE_LENGTH) {
            repeat(VERIFICATION_CODE_LENGTH) {
                append(VERIFICATION_ALPHABET[secureRandom.nextInt(VERIFICATION_ALPHABET.length)])
            }
        }

    fun newDeletionGrant(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun identifierHash(normalizedEmail: String): String =
        hmacHex("identifier:$normalizedEmail")

    fun requesterHash(remoteAddress: String?): String =
        hmacHex("requester:${remoteAddress?.trim().orEmpty().ifBlank { "unknown" }}")

    fun verificationHash(requestId: String, code: String): String =
        hmacHex("verification:$requestId:${code.trim().uppercase(Locale.ROOT)}")

    fun deletionGrantHash(requestId: String, grant: String): String =
        hmacHex("deletion-grant:$requestId:${grant.trim()}")

    fun matches(expectedHex: String?, actualHex: String): Boolean {
        if (expectedHex == null || expectedHex.length != actualHex.length) return false
        return MessageDigest.isEqual(
            expectedHex.toByteArray(Charsets.US_ASCII),
            actualHex.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun hmacHex(value: String): String {
        val secret = properties.hmacSecret.toByteArray(Charsets.UTF_8)
        if (secret.size < 32) {
            throw AccountDeletionSecurityUnavailableException()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val VERIFICATION_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val VERIFICATION_CODE_LENGTH = 10
        val EMAIL_PATTERN = Regex(
            "^[A-Za-z0-9.!#\\$%&'*+/=?^_`{|}~-]+@" +
                "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
                "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
        )
    }
}

class AccountDeletionSecurityUnavailableException : RuntimeException()
