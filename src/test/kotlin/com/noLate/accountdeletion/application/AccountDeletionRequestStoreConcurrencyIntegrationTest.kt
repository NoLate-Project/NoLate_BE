package com.noLate.accountdeletion.application

import com.noLate.accountdeletion.domain.AccountDeletionRequestStatus
import com.noLate.accountdeletion.infrastructure.AccountDeletionRequestRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(
    AccountDeletionRequestStore::class,
    AccountDeletionStoreIntegrationConfig::class,
)
class AccountDeletionRequestStoreConcurrencyIntegrationTest @Autowired constructor(
    private val store: AccountDeletionRequestStore,
    private val repository: AccountDeletionRequestRepository,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `proxied row locks elect one verification grant and one deletion claim`() {
        assertTrue(AopUtils.isAopProxy(store), "REQUIRES_NEW must be exercised through a Spring proxy")
        repository.deleteAll()
        val requestId = UUID.randomUUID().toString()
        val code = "ABCD234567"
        val firstGrant = "a".repeat(43)
        val secondGrant = "b".repeat(43)
        createSentRequest(requestId, code)

        val verificationResults = runConcurrently(
            { firstGrant to store.verifyAndMintGrant(requestId, code, firstGrant) },
            { secondGrant to store.verifyAndMintGrant(requestId, code, secondGrant) },
        )

        assertEquals(1, verificationResults.count { it.second })
        val winningGrant = verificationResults.single { it.second }.first
        val losingGrant = verificationResults.single { !it.second }.first
        assertFalse(store.verifyAndMintGrant(requestId, code, "c".repeat(43)))
        assertNull(store.claimDeletion(requestId, losingGrant))

        val claimResults = runConcurrently(
            { store.claimDeletion(requestId, winningGrant) != null },
            { store.claimDeletion(requestId, winningGrant) != null },
        )

        assertEquals(1, claimResults.count { it })
        assertNull(store.claimDeletion(requestId, winningGrant))
        val processing = repository.findById(requestId).orElseThrow()
        assertEquals(AccountDeletionRequestStatus.PROCESSING, processing.status)
        assertNull(processing.verificationTokenHash)
        assertNull(processing.deletionGrantHash)
        assertNull(processing.deletionGrantExpiresAt)
    }

    private fun createSentRequest(requestId: String, code: String) {
        store.create(
            requestId = requestId,
            identifierHash = "1".repeat(64),
            requesterHash = "2".repeat(64),
            verificationCode = code,
            account = ActionableDeletionAccount(memberId = 42L, observedSessionGeneration = 7L),
            deliveryEnabled = true,
            verificationExpiresAt = Instant.parse("2026-07-26T03:10:00Z"),
        )
        store.markVerificationSent(requestId)
    }

    private fun <T> runConcurrently(vararg calls: () -> T): List<T> {
        val executor = Executors.newFixedThreadPool(calls.size)
        val ready = CountDownLatch(calls.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(calls.size)
        val results = ConcurrentLinkedQueue<T>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        calls.forEach { call ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    results.add(call())
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "concurrent workers did not become ready")
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "concurrent workers did not finish")
            assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
            assertEquals(calls.size, results.size)
            return results.toList()
        } finally {
            executor.shutdownNow()
        }
    }
}

@TestConfiguration
class AccountDeletionStoreIntegrationConfig {
    @Bean
    fun accountDeletionProperties(): AccountDeletionProperties =
        AccountDeletionProperties().apply {
            enabled = true
            retentionPolicyConfirmed = true
            commonMailboxProofPolicyApproved = true
            hmacSecret = "account-deletion-integration-hmac-secret-at-least-32-bytes"
            publicOrigin = "https://delete.example"
            supportEmail = "privacy@example.com"
            verificationCodeTtl = Duration.ofMinutes(10)
            deletionGrantTtl = Duration.ofMinutes(5)
            requestRecordRetention = AccountDeletionProperties.REQUIRED_REQUEST_RECORD_RETENTION
        }

    @Bean
    fun accountDeletionSecrets(
        properties: AccountDeletionProperties,
    ): AccountDeletionSecrets = AccountDeletionSecrets(properties)

    @Bean
    fun accountDeletionClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-26T03:00:00Z"), ZoneOffset.UTC)
}
