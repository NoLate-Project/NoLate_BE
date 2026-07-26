package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.CannotAcquireLockException
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class AppleRevocationQueueInvariantTest {
    @Mock
    lateinit var repository: AppleProviderCredentialRepository

    @Mock
    lateinit var rowTransaction: AppleRevocationRowTransaction

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private val now = Instant.parse("2026-07-26T04:00:00Z")

    @Test
    fun `malformed pending envelope is quarantined without creating a provider lease`() {
        val malformed = AppleProviderCredential(
            id = 1L,
            clientId = "com.nolate.test",
            status = AppleProviderCredentialStatus.PENDING,
            nextAttemptAt = now,
        )
        whenever(repository.findByIdForUpdate(1L)).thenReturn(malformed)

        val lease = rowBoundary().claim(1L, now, "worker")

        assertNull(lease)
        assertEquals(AppleProviderCredentialStatus.BLOCKED, malformed.status)
        assertEquals("MALFORMED_PENDING_ENVELOPE", malformed.lastFailureCode)
        assertNull(malformed.nextAttemptAt)
        verify(repository).flush()
    }

    @Test
    fun `malformed expired capture is quarantined and cannot starve later captures`() {
        val malformed = AppleProviderCredential(
            id = 1L,
            clientId = "com.nolate.test",
            status = AppleProviderCredentialStatus.CAPTURED,
            captureExpiresAt = now.minusSeconds(1),
        )
        whenever(repository.findByIdForUpdate(1L)).thenReturn(malformed)

        assertTrue(rowBoundary().expireCapture(1L, now))
        assertEquals(AppleProviderCredentialStatus.BLOCKED, malformed.status)
        assertEquals("MALFORMED_CAPTURED_ENVELOPE", malformed.lastFailureCode)
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `malformed stale processing envelope is quarantined instead of requeued`() {
        val malformed = AppleProviderCredential(
            id = 1L,
            sourceReceiptKey = "processing-receipt",
            clientId = "com.nolate.test",
            status = AppleProviderCredentialStatus.PROCESSING,
            nextAttemptAt = now.minusSeconds(120),
            lockedAt = now.minusSeconds(120),
            lockedBy = "dead-worker",
        )
        whenever(repository.findByIdForUpdate(1L)).thenReturn(malformed)

        assertTrue(
            rowBoundary().recover(
                id = 1L,
                staleBefore = now.minusSeconds(60),
                now = now,
            )
        )
        assertEquals(AppleProviderCredentialStatus.BLOCKED, malformed.status)
        assertEquals("MALFORMED_STALE_ENVELOPE", malformed.lastFailureCode)
        assertNull(malformed.lockedAt)
        assertNull(malformed.lockedBy)
        verify(repository).flush()
    }

    @Test
    fun `poison first candidate does not stop claim of next valid row`() {
        val lease = lease(2L)
        whenever(repository.findDueIds(eq(AppleProviderCredentialStatus.PENDING), eq(now), any()))
            .thenReturn(listOf(1L, 2L))
        whenever(rowTransaction.claim(1L, now, "worker")).thenReturn(null)
        whenever(rowTransaction.claim(2L, now, "worker")).thenReturn(lease)

        assertEquals(lease, coordinator().claimNextDue(now, "worker"))
        verify(rowTransaction).claim(2L, now, "worker")
    }

    @Test
    fun `contended first candidate tries next row`() {
        val lease = lease(2L)
        whenever(repository.findDueIds(eq(AppleProviderCredentialStatus.PENDING), eq(now), any()))
            .thenReturn(listOf(1L, 2L))
        whenever(rowTransaction.claim(1L, now, "worker"))
            .thenThrow(CannotAcquireLockException("busy"))
        whenever(rowTransaction.claim(2L, now, "worker")).thenReturn(lease)

        assertEquals(lease, coordinator().claimNextDue(now, "worker"))
        verify(rowTransaction).claim(2L, now, "worker")
    }

    private fun rowBoundary() =
        AppleRevocationRowTransaction(repository, eventPublisher)

    private fun coordinator() =
        AppleTokenRevocationCoordinator(repository, rowTransaction)

    private fun lease(id: Long) =
        AppleRevocationLease(
            credentialId = id,
            credentialKey = "credential-$id",
            clientId = "com.nolate.test",
            encryptionKeyId = "token-v1",
            initializationVector = "iv",
            encryptedRefreshToken = "ciphertext",
            attemptCount = 1,
            workerId = "worker",
        )
}
