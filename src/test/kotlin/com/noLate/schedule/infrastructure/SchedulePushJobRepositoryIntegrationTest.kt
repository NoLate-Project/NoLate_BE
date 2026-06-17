package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class SchedulePushJobRepositoryIntegrationTest @Autowired constructor(
    private val repository: SchedulePushJobRepository,
) {

    @Test
    fun `현재 시각 이전 ACTIVE job만 스케줄러 조회에 검출된다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        repository.save(
            SchedulePushJob.create(
                memberId = 1L,
                scheduleId = 10L,
                scheduleAt = Instant.parse("2026-06-12T03:00:00Z"),
                departureAt = Instant.parse("2026-06-12T02:00:00Z"),
                monitorStartAt = now.minusSeconds(60),
                intervalMinutes = 20,
            )
        )
        repository.save(
            SchedulePushJob.create(
                memberId = 1L,
                scheduleId = 11L,
                scheduleAt = Instant.parse("2026-06-12T04:00:00Z"),
                departureAt = Instant.parse("2026-06-12T03:00:00Z"),
                monitorStartAt = now.plusSeconds(60),
                intervalMinutes = 20,
            )
        )
        repository.flush()

        val dueJobs = repository
            .findAllByStatusAndNextCheckAtLessThanEqualOrderByNextCheckAtAsc(
                SchedulePushJobStatus.ACTIVE,
                now,
            )

        assertEquals(listOf(10L), dueJobs.map { it.scheduleId })
    }

    @Test
    fun `하나의 일정에는 push job을 중복 생성할 수 없다`() {
        val first = createJob(scheduleId = 20L)
        val duplicate = createJob(scheduleId = 20L)

        repository.saveAndFlush(first)

        assertThrows(DataIntegrityViolationException::class.java) {
            repository.saveAndFlush(duplicate)
        }
    }

    @Test
    // Verifies the database query used by the worker recovery step.
    // Only stale PROCESSING jobs should be selected; ACTIVE jobs must not be mixed into recovery.
    fun `timeout boundary 이전에 잠긴 PROCESSING job만 복구 대상으로 조회한다`() {
        val processingJob = createJob(scheduleId = 30L).apply {
            startProcessing("worker-1")
        }
        val activeJob = createJob(scheduleId = 31L)

        repository.save(processingJob)
        repository.save(activeJob)
        repository.flush()

        val boundary = requireNotNull(processingJob.lockedAt).plusSeconds(1)
        val staleJobs = repository
            .findAllByStatusAndLockedAtLessThanEqualOrderByLockedAtAsc(
                SchedulePushJobStatus.PROCESSING,
                boundary,
            )

        assertEquals(listOf(30L), staleJobs.map { it.scheduleId })
    }

    /**
     * 중복 제약 테스트가 scheduleId 외의 값에 영향을 받지 않도록 동일한 기본 작업을 만든다.
     */
    private fun createJob(scheduleId: Long): SchedulePushJob =
        SchedulePushJob.create(
            memberId = 1L,
            scheduleId = scheduleId,
            scheduleAt = Instant.parse("2026-06-12T03:00:00Z"),
            departureAt = Instant.parse("2026-06-12T02:00:00Z"),
            monitorStartAt = Instant.parse("2026-06-12T01:00:00Z"),
            intervalMinutes = 20,
        )
}
