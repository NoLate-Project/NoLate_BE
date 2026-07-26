package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

@ExtendWith(MockitoExtension::class)
class AppleTokenRevocationWorkerTest {
    @Mock
    lateinit var coordinator: AppleTokenRevocationCoordinator

    @Mock
    lateinit var oauthClient: AppleOAuthClient

    private val now = Instant.parse("2026-07-26T03:00:00Z")
    private val properties = AppleTokenLifecycleProperties(
        enabled = true,
        clientId = "com.nolate.test",
        teamId = "TEAM123456",
        keyId = "KEY1234567",
        privateKey = "not-read-by-worker-test",
        currentEncryptionKeyId = "token-v1",
        currentEncryptionKey = Base64.getEncoder().encodeToString(ByteArray(32) { 5 }),
        retryDelaySeconds = 60,
        maxRetryDelaySeconds = 3_600,
        maxAttempts = 3,
        processingTimeoutSeconds = 120,
    )
    private val cipher = AppleTokenCipher(properties)
    private val encrypted = cipher.encrypt("credential-key", "refresh-secret")

    @Test
    fun `provider success completes durable lease`() {
        val lease = lease(attempt = 1)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(eq(now), any(), isNull())).thenReturn(lease, null)
        whenever(coordinator.complete(lease, now)).thenReturn(true)

        assertEquals(1, worker().runDue(now))

        verify(oauthClient).revokeRefreshToken("refresh-secret")
        verify(coordinator).complete(lease, now)
        verify(coordinator, never()).retry(any(), any(), any())
    }

    @Test
    fun `retryable provider failure preserves lease with exponential next attempt`() {
        val lease = lease(attempt = 2)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(eq(now), any(), isNull())).thenReturn(lease, null)
        whenever(oauthClient.revokeRefreshToken("refresh-secret")).thenThrow(
            AppleProviderCallException("APPLE_AUTH_REVOKE_IO", retryable = true)
        )
        whenever(
            coordinator.retry(
                lease,
                now.plusSeconds(120),
                "APPLE_AUTH_REVOKE_IO",
            )
        ).thenReturn(true)

        assertEquals(1, worker().runDue(now))

        verify(coordinator).retry(
            lease,
            now.plusSeconds(120),
            "APPLE_AUTH_REVOKE_IO",
        )
        verify(coordinator, never()).complete(any(), any())
    }

    @Test
    fun `nonretryable client credential failure blocks without deleting ciphertext`() {
        val lease = lease(attempt = 1)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(eq(now), any(), isNull())).thenReturn(lease, null)
        whenever(oauthClient.revokeRefreshToken("refresh-secret")).thenThrow(
            AppleProviderCallException("APPLE_CLIENT_CREDENTIALS", retryable = false)
        )
        whenever(coordinator.block(lease, "APPLE_CLIENT_CREDENTIALS")).thenReturn(true)

        assertEquals(1, worker().runDue(now))

        verify(coordinator).block(lease, "APPLE_CLIENT_CREDENTIALS")
        verify(coordinator, never()).complete(any(), any())
    }

    @Test
    fun `retryable failure blocks after the bounded attempt limit`() {
        val lease = lease(attempt = properties.maxAttempts)
        whenever(coordinator.recoverStale(any(), any(), any())).thenReturn(0)
        whenever(coordinator.claimNextDue(eq(now), any(), isNull())).thenReturn(lease, null)
        whenever(oauthClient.revokeRefreshToken("refresh-secret")).thenThrow(
            AppleProviderCallException("APPLE_AUTH_REVOKE_IO", retryable = true)
        )
        whenever(coordinator.block(lease, "APPLE_AUTH_REVOKE_IO")).thenReturn(true)

        assertEquals(1, worker().runDue(now))

        verify(coordinator).block(lease, "APPLE_AUTH_REVOKE_IO")
        verify(coordinator, never()).retry(any(), any(), any())
    }

    private fun worker(): AppleTokenRevocationWorker =
        AppleTokenRevocationWorker(
            properties = properties,
            coordinator = coordinator,
            oauthClient = oauthClient,
            tokenCipher = cipher,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun lease(attempt: Int): AppleRevocationLease =
        AppleRevocationLease(
            credentialId = 9L,
            credentialKey = "credential-key",
            clientId = properties.clientId,
            encryptionKeyId = encrypted.keyId,
            initializationVector = encrypted.initializationVector,
            encryptedRefreshToken = encrypted.ciphertext,
            attemptCount = attempt,
            workerId = "lease-worker",
        )
}
