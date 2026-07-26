package com.noLate.accountdeletion.application

import com.noLate.accountdeletion.domain.AccountDeletionRequestStatus
import com.noLate.accountdeletion.infrastructure.AccountDeletionRequestRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountDeletionMySqlContainer(imageName: String) :
    MySQLContainer<AccountDeletionMySqlContainer>(imageName)

/**
 * H2 covers the always-on proxy regression, while this tagged test proves the same single-winner
 * contract with InnoDB row locks whenever Docker is available.
 */
@DataJpaTest
@Import(
    AccountDeletionRequestStore::class,
    AccountDeletionStoreIntegrationConfig::class,
)
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AccountDeletionRequestStoreMySqlConcurrencyIntegrationTest @Autowired constructor(
    private val store: AccountDeletionRequestStore,
    private val repository: AccountDeletionRequestRepository,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `InnoDB row lock elects one verification grant and one claim`() {
        repository.deleteAll()
        val requestId = UUID.randomUUID().toString()
        val code = "JKLM234567"
        val firstGrant = "m".repeat(43)
        val secondGrant = "n".repeat(43)
        store.create(
            requestId = requestId,
            identifierHash = "5".repeat(64),
            requesterHash = "6".repeat(64),
            verificationCode = code,
            account = ActionableDeletionAccount(memberId = 93L, observedSessionGeneration = 17L),
            deliveryEnabled = true,
            verificationExpiresAt = Instant.parse("2026-07-26T03:10:00Z"),
        )
        store.markVerificationSent(requestId)

        val verificationResults = runConcurrently(
            { firstGrant to store.verifyAndMintGrant(requestId, code, firstGrant) },
            { secondGrant to store.verifyAndMintGrant(requestId, code, secondGrant) },
        )
        assertEquals(1, verificationResults.count { it.second })
        val winningGrant = verificationResults.single { it.second }.first

        val claimResults = runConcurrently(
            { store.claimDeletion(requestId, winningGrant) != null },
            { store.claimDeletion(requestId, winningGrant) != null },
        )

        assertEquals(1, claimResults.count { it })
        assertNull(store.claimDeletion(requestId, winningGrant))
        val processing = repository.findById(requestId).orElseThrow()
        assertEquals(AccountDeletionRequestStatus.PROCESSING, processing.status)
        assertNull(processing.deletionGrantHash)
        assertNull(processing.deletionGrantExpiresAt)
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
            assertTrue(ready.await(10, TimeUnit.SECONDS), "MySQL workers did not become ready")
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "MySQL workers did not finish")
            assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
            assertEquals(calls.size, results.size)
            return results.toList()
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val mysql = AccountDeletionMySqlContainer("mysql:8.4")
            .withDatabaseName("nolate_account_deletion_test")
            .withUsername("nolate")
            .withPassword("nolate")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName)
            registry.add("spring.jpa.properties.hibernate.dialect") {
                "org.hibernate.dialect.MySQLDialect"
            }
            // The container is disposable. Avoid a delayed JVM-shutdown drop after Testcontainers
            // has already stopped MySQL; startup creation is sufficient for this isolated schema.
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            registry.add("spring.sql.init.mode") { "never" }
        }
    }
}
