package com.noLate.notification.application.service

import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushManifestState
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.support.ensureActivePushMember
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(PushOutboxDispatchCoordinator::class, PushOutboxDispatchWriter::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:push-outbox-claim;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushOutboxDispatchCoordinatorConcurrencyIntegrationTest @Autowired constructor(
    private val coordinator: PushOutboxDispatchCoordinator,
    private val repository: AppNotificationRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    private val now = Instant.parse("2026-07-24T05:00:00Z")

    @BeforeEach
    fun clean() {
        repository.deleteAll()
        listOf(31L, 41L, 42L, 43L).forEach {
            ensureActivePushMember(jdbcTemplate, it)
        }
    }

    @Test
    fun `two instances racing the same due event produce one lease`() {
        repository.saveAndFlush(dueNotification())
        val claims = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        listOf("worker-a", "worker-b").forEach { workerId ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    claims += if (coordinator.claimNextDue(now, workerId) == null) 0 else 1
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, claims.count { it == 1 })
        assertEquals(1, claims.count { it == 0 })
        val persisted = repository.findAll().single()
        assertEquals(PushOutboxDispatchStatus.PROCESSING, persisted.dispatchStatus)
        assertTrue(persisted.dispatchLockedBy in setOf("worker-a", "worker-b"))
        assertEquals(1, persisted.dispatchAttemptCount)
    }

    @Test
    fun `claim changes only one event so unprocessed tail remains available`() {
        repository.saveAndFlush(dueNotification(memberId = 41, key = "event:one"))
        repository.saveAndFlush(dueNotification(memberId = 42, key = "event:two"))
        repository.saveAndFlush(dueNotification(memberId = 43, key = "event:three"))

        val first = requireNotNull(coordinator.claimNextDue(now, "worker-a"))

        assertEquals(PushOutboxDispatchStatus.PROCESSING, repository.findById(first.notificationId).orElseThrow().dispatchStatus)
        assertEquals(
            2,
            repository.findAll().count { it.dispatchStatus == PushOutboxDispatchStatus.PENDING },
        )
    }

    @Test
    fun `bounded stale recovery permits a new owner and fences the old owner`() {
        repository.saveAndFlush(dueNotification())
        val stale = requireNotNull(coordinator.claimNextDue(now, "worker-a"))
        val recoveredAt = now.plusSeconds(601)

        assertEquals(
            1,
            coordinator.recoverStale(
                now = recoveredAt,
                processingTimeoutSeconds = 600,
                batchSize = 1,
            ),
        )
        val replacement = requireNotNull(coordinator.claimNextDue(recoveredAt, "worker-b"))

        assertFalse(coordinator.complete(stale, recoveredAt))
        assertTrue(coordinator.complete(replacement, recoveredAt))
        val persisted = repository.findAll().single()
        assertEquals(PushOutboxDispatchStatus.COMPLETED, persisted.dispatchStatus)
        assertEquals(2, persisted.dispatchAttemptCount)
    }

    @Test
    fun `same worker id가 lease를 다시 잡아도 old attempt ABA transition은 거절된다`() {
        repository.saveAndFlush(dueNotification())
        val first = requireNotNull(coordinator.claimNextDue(now, "same-worker"))
        val recoveredAt = now.plusSeconds(601)
        assertEquals(
            1,
            coordinator.recoverStale(
                now = recoveredAt,
                processingTimeoutSeconds = 600,
                batchSize = 1,
            ),
        )
        val second = requireNotNull(coordinator.claimNextDue(recoveredAt, "same-worker"))

        assertEquals(first.workerId, second.workerId)
        assertEquals(1, first.attemptCount)
        assertEquals(2, second.attemptCount)
        assertFalse(coordinator.complete(first, recoveredAt))
        assertTrue(coordinator.complete(second, recoveredAt))
        val persisted = repository.findAll().single()
        assertEquals(PushOutboxDispatchStatus.COMPLETED, persisted.dispatchStatus)
        assertEquals(2, persisted.dispatchAttemptCount)
    }

    private fun dueNotification(
        memberId: Long = 31,
        key: String = "event:durable",
    ): AppNotification =
        AppNotification(
            memberId = memberId,
            deduplicationKey = "dedupe:$key",
            logicalEventKey = key,
            type = "SCHEDULE_SHARE_RECEIVED",
            title = "새 일정 공유",
            body = "공유됐어요.",
            dataJson = "{}",
            createdAt = now.minusSeconds(1),
            manifestState = PushManifestState.FROZEN,
            manifestRecipientCount = 1,
            manifestFrozenAt = now.minusSeconds(1),
            dispatchStatus = PushOutboxDispatchStatus.PENDING,
            nextDispatchAt = now,
        )
}
