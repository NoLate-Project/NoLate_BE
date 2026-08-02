package com.noLate.schedule.application.service

import com.noLate.notification.support.ensureActivePushMember
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.mockito.kotlin.whenever
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
    private val jdbcTemplate: JdbcTemplate,
) {

    @MockitoBean
    private lateinit var departureAlarmSyncService: DepartureAlarmSyncService

    private val now = Instant.parse("2026-07-24T03:00:00Z")

    @BeforeEach
    fun clean() {
        repository.deleteAll()
        ensureActivePushMember(jdbcTemplate, MEMBER_ID)
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
                    claims += workerId to if (
                        coordinator.claimNextDueJob(now, workerId) == null
                    ) {
                        0
                    } else {
                        1
                    }
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
        assertTrue(coordinator.claimNextDueJob(now, "worker-a") != null)

        val recoveredAt = now.plus(11, ChronoUnit.MINUTES)
        assertEquals(
            1,
            coordinator.recoverStaleProcessingJobs(
                now = recoveredAt,
                processingTimeoutMinutes = 10,
                deliveryGraceMinutes = 10,
                batchSize = 50,
            ),
        )

        val recovered = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.ACTIVE, recovered.status)
        assertEquals(recoveredAt, recovered.nextCheckAt)
        assertEquals(1, recovered.retryCount)
        assertNull(recovered.lockedBy)
        assertNull(recovered.lockedAt)

        assertTrue(coordinator.claimNextDueJob(recoveredAt, "worker-b") != null)
        val reclaimed = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.PROCESSING, reclaimed.status)
        assertEquals("worker-b", reclaimed.lockedBy)
    }

    @Test
    fun `stale 복구 뒤 이전 worker는 새 owner의 상태를 덮어쓸 수 없다`() {
        repository.saveAndFlush(createJob())
        val staleClaim = requireNotNull(coordinator.claimNextDueJob(now, "worker-a"))
        val recoveredAt = now.plus(11, ChronoUnit.MINUTES)
        coordinator.recoverStaleProcessingJobs(
            now = recoveredAt,
            processingTimeoutMinutes = 10,
            deliveryGraceMinutes = 10,
            batchSize = 50,
        )
        coordinator.claimNextDueJob(recoveredAt, "worker-b")

        staleClaim.cancel()

        assertThrows(IllegalStateException::class.java) {
            coordinator.persist(staleClaim, "worker-a")
        }
        val current = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.PROCESSING, current.status)
        assertEquals("worker-b", current.lockedBy)
    }

    @Test
    fun `alarm outbox 전이가 실패하면 job 완료 전이도 함께 rollback한다`() {
        repository.saveAndFlush(createJob())
        val claimed = requireNotNull(coordinator.claimNextDueJob(now, "worker-a"))
        claimed.cancel()
        whenever(departureAlarmSyncService.cancel(MEMBER_ID, claimed.scheduleId))
            .thenThrow(IllegalStateException("alarm outbox unavailable"))

        assertThrows(IllegalStateException::class.java) {
            coordinator.persist(
                job = claimed,
                workerId = "worker-a",
                alarmIntent = SchedulePushAlarmIntent.Cancel(MEMBER_ID, claimed.scheduleId),
            )
        }

        val current = repository.findAll().single()
        assertEquals(SchedulePushJobStatus.PROCESSING, current.status)
        assertEquals("worker-a", current.lockedBy)
    }

    @Test
    fun `claim은 처리 직전 한 건만 PROCESSING으로 바꾸고 backlog는 ACTIVE로 남긴다`() {
        repository.saveAndFlush(createJob(scheduleId = 901L))
        repository.saveAndFlush(createJob(scheduleId = 902L))
        repository.saveAndFlush(createJob(scheduleId = 903L))

        val first = requireNotNull(coordinator.claimNextDueJob(now, "worker-a"))

        assertEquals(SchedulePushJobStatus.PROCESSING, first.status)
        val persisted = repository.findAll()
        assertEquals(1, persisted.count { it.status == SchedulePushJobStatus.PROCESSING })
        assertEquals(2, persisted.count { it.status == SchedulePushJobStatus.ACTIVE })
    }

    @Test
    fun `한 worker의 느린 provider 처리 중에도 tail job은 lease 없이 다른 worker가 claim한다`() {
        repository.saveAndFlush(createJob(scheduleId = 911L))
        repository.saveAndFlush(createJob(scheduleId = 912L))
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        val slowWorker = executor.submit<Pair<Long, String>> {
            val claimed = requireNotNull(coordinator.claimNextDueJob(now, "slow-worker"))
            providerEntered.countDown()
            check(releaseProvider.await(5, TimeUnit.SECONDS))
            requireNotNull(claimed.id) to requireNotNull(claimed.lockedBy)
        }
        assertTrue(providerEntered.await(5, TimeUnit.SECONDS))

        val tail = requireNotNull(coordinator.claimNextDueJob(now, "fast-worker"))

        assertEquals("fast-worker", tail.lockedBy)
        releaseProvider.countDown()
        val slowClaim = slowWorker.get(5, TimeUnit.SECONDS)
        assertTrue(requireNotNull(tail.id) != slowClaim.first)
        assertEquals(setOf(911L, 912L), setOf(tail.scheduleId, repository.findById(slowClaim.first).orElseThrow().scheduleId))
        executor.shutdownNow()
        assertEquals(2, repository.findAll().count { it.status == SchedulePushJobStatus.PROCESSING })
    }

    private fun createJob(scheduleId: Long = 901L): SchedulePushJob =
        SchedulePushJob.create(
            memberId = MEMBER_ID,
            scheduleId = scheduleId,
            scheduleAt = now.plus(3, ChronoUnit.HOURS),
            departureAt = now.plus(2, ChronoUnit.HOURS),
            monitorStartAt = now.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )

    companion object {
        private const val MEMBER_ID = 1L
    }
}
