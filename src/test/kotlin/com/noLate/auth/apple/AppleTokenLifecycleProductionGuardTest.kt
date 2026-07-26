package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class AppleTokenLifecycleProductionGuardTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `production startup fails closed when token lifecycle is disabled`() {
        val properties = AppleTokenLifecycleProperties()
        val failure = assertThrows<IllegalStateException> {
            guard(properties, "").afterSingletonsInstantiated()
        }

        assertTrue(failure.message!!.startsWith("Production startup blocked:"))
    }

    @Test
    fun `production startup accepts matching audience and valid secret material`() {
        val properties = validProperties()

        assertDoesNotThrow {
            guard(properties, "com.nolate.other,${properties.clientId}")
                .afterSingletonsInstantiated()
        }
    }

    @Test
    fun `production startup rejects non-Apple provider endpoint`() {
        val valid = validProperties()
        val properties = AppleTokenLifecycleProperties(
            enabled = valid.enabled,
            clientId = valid.clientId,
            teamId = valid.teamId,
            keyId = valid.keyId,
            privateKey = valid.privateKey,
            baseUrl = "https://example.invalid",
            currentEncryptionKeyId = valid.currentEncryptionKeyId,
            currentEncryptionKey = valid.currentEncryptionKey,
        )

        assertThrows<IllegalStateException> {
            guard(properties, properties.clientId).afterSingletonsInstantiated()
        }
    }

    @Test
    fun `production startup rejects an EC key that cannot sign Apple ES256 secrets`() {
        val properties = validProperties(curve = "secp384r1")

        val failure = assertThrows<IllegalStateException> {
            guard(properties, properties.clientId).afterSingletonsInstantiated()
        }

        assertTrue(failure.message!!.contains("P-256"))
    }

    @Test
    fun `production startup rejects localhost and IP redirect hosts`() {
        listOf(
            "https://localhost/apple/callback",
            "https://signin.localhost/apple/callback",
            "https://127.0.0.1/apple/callback",
            "https://[::1]/apple/callback",
            "https://2130706433/apple/callback",
            "http://signin.example.com/apple/callback",
        ).forEach { redirect ->
            val properties = validProperties(redirectUri = redirect)
            assertThrows<IllegalStateException> {
                guard(properties, properties.clientId).afterSingletonsInstantiated()
            }
        }
    }

    @Test
    fun `production startup rejects unsafe capture and worker retry invariants`() {
        listOf(
            validProperties(captureDeadlineSeconds = 9),
            validProperties(fixedDelayMillis = 999),
            validProperties(maxAttempts = 0),
            validProperties(retryDelaySeconds = 120, maxRetryDelaySeconds = 60),
        ).forEach { properties ->
            assertThrows<IllegalStateException> {
                guard(properties, properties.clientId).afterSingletonsInstantiated()
            }
        }
    }

    private fun guard(
        properties: AppleTokenLifecycleProperties,
        audiences: String,
    ): AppleTokenLifecycleProductionGuard =
        AppleTokenLifecycleProductionGuard(
            properties = properties,
            clientSecretSigner = AppleClientSecretSigner(properties, clock),
            tokenCipher = AppleTokenCipher(properties),
            appleAudiences = audiences,
        )

    private fun validProperties(
        curve: String = "secp256r1",
        redirectUri: String = "",
        captureDeadlineSeconds: Long = 120,
        fixedDelayMillis: Long = 30_000,
        maxAttempts: Int = 12,
        retryDelaySeconds: Long = 60,
        maxRetryDelaySeconds: Long = 21_600,
    ): AppleTokenLifecycleProperties {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(curve))
        }.generateKeyPair()
        return AppleTokenLifecycleProperties(
            enabled = true,
            clientId = "com.nolate.test",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKey =
                "base64:${Base64.getEncoder().encodeToString(keyPair.private.encoded)}",
            redirectUri = redirectUri,
            currentEncryptionKeyId = "token-v1",
            currentEncryptionKey =
                Base64.getEncoder().encodeToString(ByteArray(32) { 9 }),
            workerEnabled = true,
            captureBindingDeadlineSeconds = captureDeadlineSeconds,
            fixedDelayMillis = fixedDelayMillis,
            maxAttempts = maxAttempts,
            retryDelaySeconds = retryDelaySeconds,
            maxRetryDelaySeconds = maxRetryDelaySeconds,
        )
    }
}
