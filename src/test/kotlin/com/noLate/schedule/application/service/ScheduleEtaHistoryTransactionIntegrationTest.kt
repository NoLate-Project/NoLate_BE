package com.noLate.schedule.application.service

import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.toEvaluationHistory
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.ScheduleEtaEvaluationHistoryRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** 테스트 트랜잭션으로 감싸지 않고 실제 REQUIRES_NEW의 commit/rollback 결과를 다시 조회한다. */
@DataJpaTest(properties = ["spring.sql.init.mode=never"])
@Import(SchedulePushJobCoordinator::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleEtaHistoryTransactionIntegrationTest @Autowired constructor(
    private val coordinator: SchedulePushJobCoordinator,
    private val jobs: SchedulePushJobRepository,
    private val histories: ScheduleEtaEvaluationHistoryRepository,
    private val jdbc: JdbcTemplate,
) {
    @MockitoBean
    private lateinit var alarmService: DepartureAlarmSyncService

    private val now = Instant.parse("2026-09-09T00:00:00Z")
    private val memberId = 93_001L
    private val owner = "eta-history-worker"

    @BeforeEach
    fun prepareDatabase() {
        histories.deleteAll()
        jobs.deleteAll()
        ensureActivePushMember(jdbc, memberId)
        jdbc.update("UPDATE member SET deleted = FALSE WHERE id = ?", memberId)
    }

    @ParameterizedTest
    @EnumSource(TrafficSource::class)
    fun `정상 시간표 및 두 fallback 출처를 작업 전이와 함께 commit한다`(source: TrafficSource) {
        val job = claim()
        val history = evaluation(job, source)
        job.complete()

        assertTrue(coordinator.persist(job, owner, scheduleEtaEvaluationHistory = history))

        val storedJob = jobs.findById(requireNotNull(job.id)).orElseThrow()
        val stored = requireNotNull(histories.findByEvaluationId(history.evaluationId))
        assertEquals(SchedulePushJobStatus.COMPLETED, storedJob.status)
        assertNull(storedJob.lockedBy)
        assertEquals(job.id, stored.jobId)
        assertEquals(job.memberId, stored.memberId)
        assertEquals(job.scheduleId, stored.scheduleId)
        assertEquals(source, stored.source)
        assertEquals(history.stale, stored.stale)
        assertEquals(history.failureReason, stored.failureReason)
        assertEquals(history.travelMinutes, stored.travelMinutes)
        assertEquals(history.evaluatedAt, stored.evaluatedAt)
        assertEquals(history.targetArrivalAt, stored.targetArrivalAt)
        assertEquals(history.recommendedDepartureAt, stored.recommendedDepartureAt)
        assertEquals(history.predictedArrivalAt, stored.predictedArrivalAt)
        assertEquals(history.fetchedAt, stored.fetchedAt)
        assertEquals(1L, histories.count())
    }

    @Test
    fun `이력 INSERT 이후 알람 처리가 실패하면 작업과 이력 모두 rollback한다`() {
        val job = claim()
        val history = evaluation(job)
        job.complete()
        whenever(alarmService.cancel(memberId, job.scheduleId))
            .thenThrow(IllegalStateException("test alarm outbox failure"))

        assertThrows(IllegalStateException::class.java) {
            coordinator.persist(
                job, owner,
                alarmIntent = SchedulePushAlarmIntent.Cancel(memberId, job.scheduleId),
                scheduleEtaEvaluationHistory = history,
            )
        }

        assertClaimPreserved(job)
        assertEquals(0L, histories.count())
    }

    @Test
    fun `이력 고유 키 위반으로 INSERT가 실패하면 앞선 작업 저장도 rollback한다`() {
        val job = claim()
        val evaluationId = UUID.randomUUID().toString()
        val original = histories.saveAndFlush(evaluation(job, evaluationId = evaluationId))
        // 같은 엔티티를 merge하는 것이 아니라 동일 평가 ID의 신규 INSERT를 시도한다.
        val duplicate = evaluation(job, evaluationId = evaluationId)
        job.complete()

        assertThrows(DataIntegrityViolationException::class.java) {
            coordinator.persist(job, owner, scheduleEtaEvaluationHistory = duplicate)
        }

        assertClaimPreserved(job)
        assertEquals(1L, histories.count())
        assertEquals(original.id, histories.findByEvaluationId(evaluationId)?.id)
    }

    @ParameterizedTest
    @ValueSource(strings = ["job", "schedule", "member"])
    fun `다른 작업 일정 회원의 이력은 거부하고 작업 전이를 rollback한다`(mismatch: String) {
        val job = claim()
        val history = evaluation(job).apply {
            when (mismatch) {
                "job" -> jobId += 1
                "schedule" -> scheduleId += 1
                "member" -> memberId += 1
            }
        }
        job.complete()

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.persist(job, owner, scheduleEtaEvaluationHistory = history)
        }

        assertClaimPreserved(job)
        assertEquals(0L, histories.count())
    }

    @Test
    fun `lease를 잃은 worker는 새 소유자의 상태와 이력을 변경할 수 없다`() {
        val staleJob = claim()
        val history = evaluation(staleJob)
        val later = now.plusSeconds(660)
        assertEquals(1, coordinator.recoverStaleProcessingJobs(later, 10, 10, 50))
        assertNotNull(coordinator.claimNextDueJob(later, "new-owner"))
        staleJob.complete()

        assertThrows(IllegalStateException::class.java) {
            coordinator.persist(staleJob, owner, scheduleEtaEvaluationHistory = history)
        }

        val current = jobs.findById(requireNotNull(staleJob.id)).orElseThrow()
        assertEquals(SchedulePushJobStatus.PROCESSING, current.status)
        assertEquals("new-owner", current.lockedBy)
        assertEquals(0L, histories.count())
    }

    @Test
    fun `평가 도중 탈퇴한 회원은 작업 전이와 이력 저장을 거부한다`() {
        val job = claim()
        val history = evaluation(job)
        jdbc.update("UPDATE member SET deleted = TRUE WHERE id = ?", memberId)
        job.complete()

        assertFalse(coordinator.persist(job, owner, scheduleEtaEvaluationHistory = history))

        assertClaimPreserved(job)
        assertEquals(0L, histories.count())
    }

    @Test
    fun `ETA 계산 없는 종료는 이력 없이 작업만 commit한다`() {
        val job = claim()
        job.complete()

        assertTrue(coordinator.persist(job, owner))

        assertEquals(SchedulePushJobStatus.COMPLETED, jobs.findById(requireNotNull(job.id)).orElseThrow().status)
        assertEquals(0L, histories.count())
    }

    private fun assertClaimPreserved(job: SchedulePushJob) {
        val stored = jobs.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(SchedulePushJobStatus.PROCESSING, stored.status)
        assertEquals(owner, stored.lockedBy)
        assertEquals(now, stored.lockedAt)
        assertEquals(0, stored.checkCount)
    }

    private fun claim(): SchedulePushJob {
        jobs.saveAndFlush(SchedulePushJob.create(
            memberId = memberId,
            scheduleId = 94_001L,
            scheduleAt = now.plusSeconds(10_800),
            departureAt = now.plusSeconds(7_200),
            monitorStartAt = now.minusSeconds(60),
            intervalMinutes = 20,
        ))
        return requireNotNull(coordinator.claimNextDueJob(now, owner))
    }

    private fun evaluation(
        job: SchedulePushJob,
        source: TrafficSource = TrafficSource.LIVE_PROVIDER,
        evaluationId: String = UUID.randomUUID().toString(),
    ) = TrafficResult(
        travelMinutes = 40,
        source = source,
        fetchedAt = now.plusSeconds(2).takeIf { source.isProvider() },
        stale = !source.isProvider(),
        failureReason = TrafficFailureReasons.PROVIDER_TIMEOUT.takeUnless { source.isProvider() },
        recommendedDepartureAt = now.plusSeconds(600).takeIf { source.isProvider() },
        predictedArrivalAt = now.plusSeconds(3_000).takeIf { source.isProvider() },
    ).toEvaluationHistory(
        evaluationId = evaluationId,
        jobId = requireNotNull(job.id),
        scheduleId = job.scheduleId,
        memberId = job.memberId,
        request = TrafficRequest(
            originLat = 37.1, originLng = 127.1,
            destinationLat = 37.2, destinationLng = 127.2,
            travelMode = ScheduleTravelMode.TRANSIT,
            fallbackTravelMinutes = 40,
            evaluatedAt = now,
            targetArrivalAt = job.scheduleAt,
        ),
    )

    private fun TrafficSource.isProvider() =
        this == TrafficSource.LIVE_PROVIDER || this == TrafficSource.TIMETABLE_PROVIDER
}
