package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class AppleTokenCipherTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val properties = AppleTokenLifecycleProperties(
        enabled = true,
        clientId = "com.nolate.test",
        teamId = "TEAM123456",
        keyId = "KEY1234567",
        privateKey = "configured-for-cipher-only",
        currentEncryptionKeyId = "token-v2",
        currentEncryptionKey = key,
        previousEncryptionKeys =
            "token-v1=${Base64.getEncoder().encodeToString(ByteArray(32) { 7 })}",
    )
    private val cipher = AppleTokenCipher(properties)

    @Test
    fun `refresh token is authenticated and never stored as plaintext`() {
        val encrypted = cipher.encrypt("credential-a", "apple-refresh-secret")

        assertFalse(encrypted.ciphertext.contains("apple-refresh-secret"))
        assertEquals("token-v2", encrypted.keyId)
        assertEquals(
            "apple-refresh-secret",
            cipher.decrypt(
                credentialKey = "credential-a",
                keyId = encrypted.keyId,
                initializationVector = encrypted.initializationVector,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    @Test
    fun `ciphertext cannot move to a different credential envelope`() {
        val encrypted = cipher.encrypt("credential-a", "apple-refresh-secret")

        assertThrows<Exception> {
            cipher.decrypt(
                credentialKey = "credential-b",
                keyId = encrypted.keyId,
                initializationVector = encrypted.initializationVector,
                ciphertext = encrypted.ciphertext,
            )
        }
    }

    @Test
    fun `previous key remains decrypt-only during rotation`() {
        val oldProperties = AppleTokenLifecycleProperties(
            enabled = true,
            clientId = "com.nolate.test",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKey = "configured-for-cipher-only",
            currentEncryptionKeyId = "token-v1",
            currentEncryptionKey =
                Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        )
        val oldEnvelope = AppleTokenCipher(oldProperties)
            .encrypt("credential-a", "old-refresh-token")

        assertEquals(
            "old-refresh-token",
            cipher.decrypt(
                credentialKey = "credential-a",
                keyId = oldEnvelope.keyId,
                initializationVector = oldEnvelope.initializationVector,
                ciphertext = oldEnvelope.ciphertext,
            ),
        )
        assertEquals("token-v2", cipher.encrypt("credential-b", "new-token").keyId)
    }
}
