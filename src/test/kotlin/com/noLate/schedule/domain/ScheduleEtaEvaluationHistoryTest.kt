package com.noLate.schedule.domain

import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

/** 결과 DTO와 이력 엔티티가 동일한 조회 증거 계약을 지키는지 확인한다. */
class ScheduleEtaEvaluationHistoryTest {
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val reason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY

    @ParameterizedTest
    @EnumSource(TrafficSource::class, names = ["SELECTED_ROUTE", "SAVED_FALLBACK"])
    fun `저신뢰도 fallback의 조회 증거는 허용하지만 정상 ETA로 승격하지 않는다`(source: TrafficSource) {
        val result = TrafficResult(40, source, now, stale = true, failureReason = reason)
        val history = history(source, now, stale = true, failureReason = reason)

        assertFalse(result.accepted)
        assertEquals(source, history.source)
        assertEquals(now, history.fetchedAt)
    }

    @ParameterizedTest
    @EnumSource(TrafficSource::class, names = ["SELECTED_ROUTE", "SAVED_FALLBACK"])
    fun `진단 사유나 저신뢰도 표시 없는 fallback에 조회시각만 붙일 수 없다`(source: TrafficSource) {
        for ((stale, failureReason) in listOf(false to reason, true to null, true to " ")) {
            assertThrows(IllegalArgumentException::class.java) {
                TrafficResult(40, source, now, stale = stale, failureReason = failureReason)
            }
            assertThrows(IllegalArgumentException::class.java) {
                history(source, now, stale = stale, failureReason = failureReason)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TrafficSource::class, names = ["LIVE_PROVIDER", "TIMETABLE_PROVIDER"])
    fun `공급자 ETA에는 여전히 취득시각이 필수다`(source: TrafficSource) {
        assertThrows(IllegalArgumentException::class.java) {
            TrafficResult(40, source, fetchedAt = null, stale = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            history(source, fetchedAt = null, stale = false, failureReason = null)
        }
    }

    private fun history(source: TrafficSource, fetchedAt: Instant?, stale: Boolean, failureReason: String?) =
        ScheduleEtaEvaluationHistory(
            evaluationId = "12345678-1234-1234-1234-123456789abc",
            jobId = 1,
            scheduleId = 2,
            memberId = 3,
            evaluatedAt = now,
            source = source,
            stale = stale,
            failureReason = failureReason,
            travelMinutes = 40,
            recommendedDepartureAt = null,
            predictedArrivalAt = null,
            targetArrivalAt = now.plusSeconds(3_600),
            fetchedAt = fetchedAt,
        )
}
