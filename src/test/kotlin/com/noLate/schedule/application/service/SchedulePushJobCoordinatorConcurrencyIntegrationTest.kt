package com.noLate.schedule.application.service

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(SchedulePushJobCoordinator::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push-claim;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePushJobCoordinatorConcurrencyIntegrationTest @Autowired constructor(
    private val coordinator: SchedulePushJobCoordinator,
    private val repository: SchedulePushJobRepository,
) {

    private val now = Instant.parse("2026-07-24T03:00:00Z")

    @BeforeEach
    fun clean() {
        repository.deleteAll()
    }

    @Test
    fun `두 worker가 같은 due job을 경합해도 한 worker만 claim한다`() {
        repository.saveAndFlush(createJob())
        val claims = ConcurrentLinkedQueue<Pair<String, Int>>()
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
                    claims += workerId to coordinator.claimDueJobs(now, workerId).size
                } catch (error: Throwable) {
                    failures += error
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
        assertEquals(1, claims.sumOf { it.second })
        assertEquals(setOf(0, 1), claims.map { it.second }.toSet())
        val persisted = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.PROCESSING, persisted.status)
        assertTrue(persisted.lockedBy in setOf("worker-a", "worker-b"))
        assertEquals(now, persisted.lockedAt)
    }

    @Test
    fun `timeout을 넘은 PROCESSING lease만 ACTIVE로 복구해 다음 worker가 다시 claim한다`() {
        repository.saveAndFlush(createJob())
        assertEquals(1, coordinator.claimDueJobs(now, "worker-a").size)

        val recoveredAt = now.plus(11, ChronoUnit.MINUTES)
        assertEquals(
            1,
            coordinator.recoverStaleProcessingJobs(
                now = recoveredAt,
                processingTimeoutMinutes = 10,
                deliveryGraceMinutes = 10,
            ),
        )

        val recovered = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.ACTIVE, recovered.status)
        assertEquals(recoveredAt, recovered.nextCheckAt)
        assertEquals(1, recovered.retryCount)
        assertNull(recovered.lockedBy)
        assertNull(recovered.lockedAt)

        assertEquals(1, coordinator.claimDueJobs(recoveredAt, "worker-b").size)
        val reclaimed = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.PROCESSING, reclaimed.status)
        assertEquals("worker-b", reclaimed.lockedBy)
    }

    @Test
    fun `stale 복구 뒤 이전 worker는 새 owner의 상태를 덮어쓸 수 없다`() {
        repository.saveAndFlush(createJob())
        val staleClaim = coordinator.claimDueJobs(now, "worker-a").single()
        val recoveredAt = now.plus(11, ChronoUnit.MINUTES)
        coordinator.recoverStaleProcessingJobs(
            now = recoveredAt,
            processingTimeoutMinutes = 10,
            deliveryGraceMinutes = 10,
        )
        coordinator.claimDueJobs(recoveredAt, "worker-b")

        staleClaim.cancel()

        assertThrows(IllegalStateException::class.java) {
            coordinator.persist(staleClaim, "worker-a")
        }
        val current = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.PROCESSING, current.status)
        assertEquals("worker-b", current.lockedBy)
    }

    private fun createJob(): SchedulePushJob =
        SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 901L,
            scheduleAt = now.plus(3, ChronoUnit.HOURS),
            departureAt = now.plus(2, ChronoUnit.HOURS),
            monitorStartAt = now.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
}
