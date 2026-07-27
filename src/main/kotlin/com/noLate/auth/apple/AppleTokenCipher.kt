package com.noLate.auth.apple

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedAppleToken(
    val keyId: String,
    val initializationVector: String,
    val ciphertext: String,
)

/**
 * Refresh tokens are encrypted with an authenticated envelope before entering persistence.
 *
 * The credential key is authenticated as AAD, so ciphertext copied between rows cannot be
 * decrypted. Previous keys are decrypt-only to support an explicit online rotation window.
 */
@Component
class AppleTokenCipher(
    private val properties: AppleTokenLifecycleProperties,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val keys: Map<String, SecretKey> by lazy { loadKeys() }

    fun encrypt(credentialKey: String, plaintext: String): EncryptedAppleToken {
        properties.requireReady()
        require(plaintext.isNotBlank()) { "Apple refresh token is empty." }
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = cipher(Cipher.ENCRYPT_MODE, currentKey(), iv, credentialKey)
            EncryptedAppleToken(
                keyId = properties.currentEncryptionKeyId,
                initializationVector = Base64.getEncoder().encodeToString(iv),
                ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintextBytes)),
            )
        } finally {
            plaintextBytes.fill(0)
        }
    }

    fun decrypt(
        credentialKey: String,
        keyId: String,
        initializationVector: String,
        ciphertext: String,
    ): String {
        properties.requireReady()
        val key = keys[keyId] ?: error("Apple token encryption key-id is unavailable.")
        val iv = decode(initializationVector, "initialization vector")
        check(iv.size == GCM_IV_BYTES) {
            "Apple token initialization vector is malformed."
        }
        val encrypted = decode(ciphertext, "ciphertext")
        val plaintext = cipher(Cipher.DECRYPT_MODE, key, iv, credentialKey).doFinal(encrypted)
        return try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    fun validateKeys() {
        properties.requireReady()
        currentKey()
    }

    private fun currentKey(): SecretKey =
        keys[properties.currentEncryptionKeyId]
            ?: error("Apple current token encryption key is unavailable.")

    private fun loadKeys(): Map<String, SecretKey> {
        val configured = linkedMapOf(properties.currentEncryptionKeyId to properties.currentEncryptionKey)
        properties.previousEncryptionKeys
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { entry ->
                val separator = entry.indexOf('=')
                check(separator in 1 until entry.lastIndex) {
                    "Apple previous encryption key entry is malformed."
                }
                val keyId = entry.substring(0, separator).trim()
                val encoded = entry.substring(separator + 1).trim()
                check(AppleTokenLifecycleProperties.ENCRYPTION_KEY_ID_PATTERN.matches(keyId)) {
                    "Apple previous encryption key-id is malformed."
                }
                check(keyId !in configured) {
                    "Apple token encryption key-id is duplicated."
                }
                configured[keyId] = encoded
            }
        return configured.mapValues { (_, encoded) ->
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
                throw IllegalStateException("Apple token encryption key is not valid Base64.")
            }
            check(bytes.size == AES_256_BYTES) {
                "Apple token encryption key must decode to 32 bytes."
            }
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                // SecretKeySpec copies the key material; clear the decoded configuration buffer.
                bytes.fill(0)
            }
        }
    }

    private fun cipher(
        mode: Int,
        key: SecretKey,
        iv: ByteArray,
        credentialKey: String,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD("$AAD_PREFIX$credentialKey".toByteArray(Charsets.UTF_8))
        }

    private fun decode(encoded: String, label: String): ByteArray =
        runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
            throw IllegalStateException("Apple token $label is malformed.")
        }

    private companion object {
        const val AES_256_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val AAD_PREFIX = "nolate:apple-provider-token:"
    }
}
