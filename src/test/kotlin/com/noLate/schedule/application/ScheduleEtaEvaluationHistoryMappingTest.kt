package com.noLate.schedule.application

import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ScheduleEtaEvaluationHistoryMappingTest {

    // 현재 시각 대신 고정값을 사용해 언제 실행해도 같은 결과가 나오게 한다.
    private val evaluatedAt = Instant.parse("2026-09-09T00:00:00Z")
    private val evaluationId = "12345678-1234-1234-1234-123456789abc"

    private val baseRequest = TrafficRequest(
        originLat = 37.5,
        originLng = 127.0,
        destinationLat = 37.6,
        destinationLng = 127.1,
        travelMode = ScheduleTravelMode.TRANSIT,
        fallbackTravelMinutes = 40,
        evaluatedAt = evaluatedAt,
        plannedDepartureAt = evaluatedAt.plusSeconds(600),
        targetArrivalAt = evaluatedAt.plusSeconds(3_600),
    )

    @Test
    fun `정상 결과의 식별자와 요청 및 결과 값을 보존한다`() {
        // 준비: 계산 기준시각과 취득 시각을 일부러 다르게 설정한다.
        val result = TrafficResult(
            travelMinutes = 40,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = evaluatedAt.plusSeconds(2),
            stale = false,
            recommendedDepartureAt = evaluatedAt.plusSeconds(600),
            predictedArrivalAt = evaluatedAt.plusSeconds(3_000),
        )

        // 실행
        val history = map(result)

        // 검증
        assertNull(history.id) // 아직 저장하지 않았으므로 DB ID는 없다.
        assertEquals(evaluationId, history.evaluationId)
        assertEquals(1L, history.jobId)
        assertEquals(2L, history.scheduleId)
        assertEquals(3L, history.memberId)
        assertEquals(baseRequest.evaluatedAt, history.evaluatedAt)
        assertEquals(baseRequest.targetArrivalAt, history.targetArrivalAt)
        assertEquals(result.fetchedAt, history.fetchedAt)
        assertEquals(result.source, history.source)
        assertEquals(result.travelMinutes, history.travelMinutes)
        assertEquals(result.recommendedDepartureAt, history.recommendedDepartureAt)
        assertEquals(result.predictedArrivalAt, history.predictedArrivalAt)
        assertFalse(history.stale)
        assertNull(history.failureReason)
    }


    @Test
    fun `fallback의 계산하지 못한 시각은 null로 남기고 오류 원문은 정제한다`() {
        val result = TrafficResult(
            travelMinutes = 40,
            source = TrafficSource.SAVED_FALLBACK,
            stale = true,
            failureReason = "PROVIDER_TIMEOUT: token=test-secret",
        )

        val history = map(result)

        assertEquals(TrafficSource.SAVED_FALLBACK, history.source)
        assertTrue(history.stale)

        // 요청에 plannedDepartureAt이 있어도 결과의 null을 채우면 안 된다.
        assertNull(history.recommendedDepartureAt)
        assertNull(history.predictedArrivalAt)
        assertNull(history.fetchedAt)

        // 원문의 가짜 토큰이 아니라 미리 정의한 안전한 메시지가 저장돼야 한다.
        assertEquals(TrafficFailureReasons.PROVIDER_TIMEOUT, history.failureReason)
    }

    @Test
    fun `저신뢰도 시간표 결과와 목표보다 늦은 예측 도착도 보존한다`() {
        val lateArrivalAt = evaluatedAt.plusSeconds(5_400)
        val result = TrafficResult(
            travelMinutes = 70,
            source = TrafficSource.TIMETABLE_PROVIDER,
            fetchedAt = evaluatedAt.plusSeconds(2),
            stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            recommendedDepartureAt = evaluatedAt.plusSeconds(1_200),
            predictedArrivalAt = lateArrivalAt,
        )

        val history = map(result)

        // 시간표 결과라고 무조건 정상으로 바꾸거나 fallback으로 분류하지 않는다.
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, history.source)
        assertTrue(history.stale)
        assertEquals(result.failureReason, history.failureReason)
        assertEquals(result.fetchedAt, history.fetchedAt)
        assertEquals(lateArrivalAt, history.predictedArrivalAt)
        assertTrue(lateArrivalAt.isAfter(history.targetArrivalAt))
    }

    @Test
    fun `경로 추정값 사유는 원문을 제거하고 이력에 별도 코드로 보존한다`() {
        val result = TrafficResult(
            travelMinutes = 40,
            source = TrafficSource.SELECTED_ROUTE,
            stale = true,
            failureReason = "TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY: token=test-secret",
            transitTimingBasis = TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
            firstBoardingWaitMinutes = 5,
        )

        val history = map(result)

        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, history.failureReason)
        assertEquals(TrafficSource.SELECTED_ROUTE, history.source)
        assertTrue(history.stale)
        assertNull(history.recommendedDepartureAt)
        assertNull(history.predictedArrivalAt)
    }

    @Test
    fun `요청에 목표 도착시각이 없으면 예외가 발생한다`() {
        // copy는 기존 요청의 나머지 값을 유지하면서 지정한 값만 바꾼다.
        val requestWithoutTarget = baseRequest.copy(targetArrivalAt = null)
        val result = TrafficResult(
            travelMinutes = 40,
            source = TrafficSource.SAVED_FALLBACK,
            stale = true,
        )

        assertThrows(IllegalArgumentException::class.java) {
            map(result, requestWithoutTarget)
        }
    }



    private fun map(
        result: TrafficResult,
        request: TrafficRequest = baseRequest ,
    ) = result.toEvaluationHistory(
        evaluationId = evaluationId,
        jobId =  1L,
        scheduleId =  2L,
        memberId =  3L,
        request = request,
    )

}
