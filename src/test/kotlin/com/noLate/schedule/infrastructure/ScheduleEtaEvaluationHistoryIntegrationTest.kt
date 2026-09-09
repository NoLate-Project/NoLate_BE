package com.noLate.schedule.infrastructure

import com.noLate.schedule.domain.ScheduleEtaEvaluationHistory
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID

@DataJpaTest
class ScheduleEtaEvaluationHistoryIntegrationTest @Autowired constructor(
    private val entityManager: TestEntityManager,
    private val repository: ScheduleEtaEvaluationHistoryRepository,
) {
    private val evaluatedAt = Instant.parse("2026-09-08T00:00:00Z")

    @Test
    fun `같은 작업의 정상 복구를 저장해도 이전 fallback과 계산하지 못한 시각은 보존한다`() {
        val fallback = evaluation()
        val recovered = evaluation(
            evaluatedAt = evaluatedAt.plusSeconds(300),
            source = TrafficSource.LIVE_PROVIDER,
            stale = false,
            failureReason = null,
            recommendedDepartureAt = evaluatedAt.plusSeconds(300),
            predictedArrivalAt = evaluatedAt.plusSeconds(2_700),
            fetchedAt = evaluatedAt.plusSeconds(298),
        )

        repository.saveAndFlush(fallback)
        repository.saveAndFlush(recovered)
        entityManager.clear()

        // DB에서 다시 읽어 JPA 기본 생성자·enum 매핑과 서로 다른 평가의 독립 보존을 검증한다.
        val storedFallback = requireNotNull(repository.findByEvaluationId(fallback.evaluationId))
        val storedRecovered = requireNotNull(repository.findByEvaluationId(recovered.evaluationId))

        assertNotEquals(storedFallback.id, storedRecovered.id)
        assertEquals(storedFallback.jobId, storedRecovered.jobId)
        assertEquals(fallback.evaluationId, storedFallback.evaluationId)
        assertEquals(TrafficSource.SELECTED_ROUTE, storedFallback.source)
        assertTrue(storedFallback.stale)
        assertEquals(fallback.failureReason, storedFallback.failureReason)
        assertNull(storedFallback.recommendedDepartureAt)
        assertNull(storedFallback.predictedArrivalAt)
        assertNull(storedFallback.fetchedAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, storedRecovered.source)
        assertFalse(storedRecovered.stale)
        assertNull(storedRecovered.failureReason)
        assertEquals(recovered.evaluatedAt, storedRecovered.evaluatedAt)
        assertEquals(recovered.fetchedAt, storedRecovered.fetchedAt)
        assertEquals(recovered.recommendedDepartureAt, storedRecovered.recommendedDepartureAt)
        assertEquals(recovered.predictedArrivalAt, storedRecovered.predictedArrivalAt)
    }

    @Test
    fun `같은 평가 ID를 다시 저장하면 DB 고유 제약이 중복 이력을 막는다`() {
        val evaluationId = UUID.randomUUID().toString()
        repository.saveAndFlush(evaluation(evaluationId = evaluationId))

        assertThrows(DataIntegrityViolationException::class.java) {
            repository.saveAndFlush(evaluation(evaluationId = evaluationId))
        }
    }

    @Test
    fun `관리 중인 이력 값을 변경하고 저장해도 기존 DB 이력은 덮어쓰지 않는다`() {
        val history = repository.saveAndFlush(evaluation())
        val originalReason = history.failureReason

        // var의 메모리 값은 바뀌어도 @Immutable이 Hibernate UPDATE를 막는지 확인한다.
        // 정상 복구는 기존 이력 수정이 아니라 새 evaluationId의 INSERT로 남겨야 한다.
        history.failureReason = "TRANSIT_ARRIVAL_UNAVAILABLE: A later diagnostic."
        repository.saveAndFlush(history)
        entityManager.clear()

        val stored = requireNotNull(repository.findByEvaluationId(history.evaluationId))
        assertEquals(originalReason, stored.failureReason)
    }

    @Test
    fun `시간표 기반 저신뢰도 결과는 목표보다 늦은 도착 진단도 그대로 보존한다`() {
        val lateArrivalAt = evaluatedAt.plusSeconds(4_200)
        val diagnostic = evaluation(
            source = TrafficSource.TIMETABLE_PROVIDER,
            recommendedDepartureAt = evaluatedAt,
            predictedArrivalAt = lateArrivalAt,
            fetchedAt = evaluatedAt.plusSeconds(2),
        )
        repository.saveAndFlush(diagnostic)
        entityManager.clear()

        val stored = entityManager.find(ScheduleEtaEvaluationHistory::class.java, diagnostic.id)

        assertEquals(TrafficSource.TIMETABLE_PROVIDER, stored.source)
        assertTrue(stored.stale)
        assertEquals(diagnostic.failureReason, stored.failureReason)
        assertEquals(lateArrivalAt, stored.predictedArrivalAt)
        assertTrue(requireNotNull(stored.predictedArrivalAt).isAfter(stored.targetArrivalAt))
        // 응답 취득은 평가 시작보다 늦을 수 있으므로 두 시각을 하나로 덮어쓰면 안 된다.
        assertEquals(evaluatedAt.plusSeconds(2), stored.fetchedAt)
        assertEquals(evaluatedAt, stored.evaluatedAt)
    }

    @Test
    fun `일정과 회원으로 이력을 분리하고 같은 평가 시각도 ID 역순으로 페이지 조회한다`() {
        val oldest = evaluation()
        val recentFirst = evaluation(evaluatedAt = evaluatedAt.plusSeconds(300))
        val recentSecond = evaluation(evaluatedAt = evaluatedAt.plusSeconds(300))
        val anotherMember = evaluation(memberId = 4L, evaluatedAt = evaluatedAt.plusSeconds(600))
        val anotherSchedule = evaluation(scheduleId = 5L, evaluatedAt = evaluatedAt.plusSeconds(600))
        repository.saveAllAndFlush(
            listOf(oldest, recentFirst, recentSecond, anotherMember, anotherSchedule)
        )
        entityManager.clear()

        val firstPage = repository.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
            scheduleId = 2L,
            memberId = 3L,
            pageable = PageRequest.of(0, 2),
        )
        val secondPage = repository.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
            scheduleId = 2L,
            memberId = 3L,
            pageable = firstPage.nextPageable(),
        )

        assertEquals(listOf(recentSecond.id, recentFirst.id), firstPage.content.map { it.id })
        assertTrue(firstPage.hasNext())
        assertEquals(listOf(oldest.id), secondPage.content.map { it.id })
        assertFalse(secondPage.hasNext())
    }

    @Test
    fun `기록되지 않은 평가와 다른 회원의 일정 조회는 빈 결과를 반환한다`() {
        repository.saveAndFlush(evaluation())
        entityManager.clear()

        assertNull(repository.findByEvaluationId(UUID.randomUUID().toString()))
        val page = repository.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
            scheduleId = 2L,
            memberId = 99L,
            pageable = PageRequest.of(0, 10),
        )
        assertTrue(page.isEmpty)
        assertFalse(page.hasNext())
    }

    private fun evaluation(
        evaluationId: String = UUID.randomUUID().toString(),
        scheduleId: Long = 2L,
        memberId: Long = 3L,
        evaluatedAt: Instant = this.evaluatedAt,
        source: TrafficSource = TrafficSource.SELECTED_ROUTE,
        stale: Boolean = true,
        failureReason: String? = "TRANSIT_ARRIVAL_UNAVAILABLE: No verified arrival data.",
        recommendedDepartureAt: Instant? = null,
        predictedArrivalAt: Instant? = null,
        fetchedAt: Instant? = null,
    ) = ScheduleEtaEvaluationHistory(
        evaluationId = evaluationId,
        jobId = 1L,
        scheduleId = scheduleId,
        memberId = memberId,
        evaluatedAt = evaluatedAt,
        source = source,
        stale = stale,
        failureReason = failureReason,
        travelMinutes = 40,
        recommendedDepartureAt = recommendedDepartureAt,
        predictedArrivalAt = predictedArrivalAt,
        targetArrivalAt = this.evaluatedAt.plusSeconds(3_600),
        fetchedAt = fetchedAt,
    )
}
