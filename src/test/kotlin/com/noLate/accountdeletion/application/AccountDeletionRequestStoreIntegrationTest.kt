package com.noLate.accountdeletion.application

import com.noLate.accountdeletion.domain.AccountDeletionRequest
import com.noLate.accountdeletion.domain.AccountDeletionRequestStatus
import com.noLate.accountdeletion.infrastructure.AccountDeletionRequestRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@DataJpaTest
class AccountDeletionRequestStoreIntegrationTest {

    @Autowired
    lateinit var repository: AccountDeletionRequestRepository

    private lateinit var store: AccountDeletionRequestStore

    @BeforeEach
    fun setUp() {
        val properties = AccountDeletionProperties().apply {
            hmacSecret = "account-deletion-test-hmac-secret-at-least-32-bytes"
            verificationCodeTtl = Duration.ofMinutes(10)
            deletionGrantTtl = Duration.ofMinutes(5)
            requestRecordRetention = Duration.ofDays(30)
            maxVerificationAttempts = 5
        }
        val clock = Clock.fixed(Instant.parse("2026-07-26T03:00:00Z"), ZoneOffset.UTC)
        store = AccountDeletionRequestStore(
            repository = repository,
            secrets = AccountDeletionSecrets(properties),
            properties = properties,
            clock = clock,
        )
    }

    @Test
    fun `verification and deletion grants are each single use`() {
        val requestId = UUID.randomUUID().toString()
        val code = "ABCD234567"
        val grant = "a".repeat(43)
        store.create(
            requestId = requestId,
            identifierHash = "1".repeat(64),
            requesterHash = "2".repeat(64),
            verificationCode = code,
            account = ActionableDeletionAccount(42L, 7L),
            deliveryEnabled = true,
            verificationExpiresAt = Instant.parse("2026-07-26T03:10:00Z"),
        )
        store.markVerificationSent(requestId)

        assertTrue(store.verifyAndMintGrant(requestId, code, grant))
        assertFalse(store.verifyAndMintGrant(requestId, code, "b".repeat(43)))

        val claimed = requireNotNull(store.claimDeletion(requestId, grant))
        assertEquals(42L, claimed.memberId)
        assertEquals(7L, claimed.observedSessionGeneration)
        assertNull(store.claimDeletion(requestId, grant))

        store.markCompleted(requestId)
        val completed = repository.findById(requestId).orElseThrow()
        assertEquals(AccountDeletionRequestStatus.COMPLETED, completed.status)
        assertNull(completed.memberId)
        assertNull(completed.observedSessionGeneration)
        assertNull(completed.verificationTokenHash)
        assertNull(completed.deletionGrantHash)
    }

    @Test
    fun `failed verification attempts consume the request without exposing a grant`() {
        val requestId = UUID.randomUUID().toString()
        store.create(
            requestId = requestId,
            identifierHash = "3".repeat(64),
            requesterHash = "4".repeat(64),
            verificationCode = "RIGHT23456",
            account = null,
            deliveryEnabled = true,
            verificationExpiresAt = Instant.parse("2026-07-26T03:10:00Z"),
        )
        store.markVerificationSent(requestId)

        repeat(5) {
            assertFalse(store.verifyAndMintGrant(requestId, "WRONG23456", "c".repeat(43)))
        }

        val rejected = repository.findById(requestId).orElseThrow()
        assertEquals(AccountDeletionRequestStatus.REJECTED, rejected.status)
        assertEquals(5, rejected.verificationAttemptCount)
        assertNull(rejected.verificationTokenHash)
        assertNull(rejected.verificationExpiresAt)
        assertFalse(store.verifyAndMintGrant(requestId, "RIGHT23456", "d".repeat(43)))
    }

    @Test
    fun `stale processing is never replayed and drops its member binding`() {
        val requestId = UUID.randomUUID().toString()
        repository.saveAndFlush(
            AccountDeletionRequest(
                id = requestId,
                identifierHash = "5".repeat(64),
                requesterHash = "6".repeat(64),
                memberId = 91L,
                observedSessionGeneration = 14L,
                status = AccountDeletionRequestStatus.PROCESSING,
                processingStartedAt = Instant.parse("2026-07-26T01:00:00Z"),
                createdAt = Instant.parse("2026-07-26T00:50:00Z"),
                updatedAt = Instant.parse("2026-07-26T01:00:00Z"),
                retentionExpiresAt = Instant.parse("2026-08-25T00:50:00Z"),
            )
        )

        store.purgeExpired()

        val recovered = repository.findById(requestId).orElseThrow()
        assertEquals(AccountDeletionRequestStatus.REJECTED, recovered.status)
        assertEquals(AccountDeletionFailureCode.OUTCOME_UNKNOWN.name, recovered.failureCode)
        assertNull(recovered.memberId)
        assertNull(recovered.observedSessionGeneration)
    }
}
