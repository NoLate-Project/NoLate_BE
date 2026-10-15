package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.TransitRealtimeOverlay
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.application.transit.TransitTransferStatus
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.infrastructure.tmap.TmapTransitJourneyMapper
import com.noLate.transit.application.TransitArrivalService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 실제 API·시뮬레이터 E2E가 아닌, 공급자 JSON → 구간 모델 → 승차 판단 연결 검증이다. */
class TmapTransitDurationIntegrationTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")

    @ParameterizedTest
    @CsvSource("115,UNKNOWN", "110,MISSED")
    fun `매핑한 접근 111초를 승차 판단까지 보존하고 전체 여정은 확정하지 않는다`(
        boardingSeconds: Long,
        expected: TransitTransferStatus,
    ) {
        val journey = mappedJourney()
        // 승차 시각은 검증용 입력이다. Tmap 응답에 존재하는 운행시각으로 가장하지 않는다.
        val observation = TransitRealtimeOverlay(
            travelMinutes = 65,
            predictedArrivalAt = journey.arrivalAt,
            observedAt = now,
            boardingAt = now.plusSeconds(boardingSeconds),
        )

        val result = TransitTransferFeasibilityEvaluator().evaluate(journey, observation, now)

        assertEquals(listOf(111L, 2882L, 243L, 481L, 171L), journey.legs.map { it.durationSeconds })
        assertEquals(65, journey.totalMinutes)
        assertEquals(3888L, journey.legs.sumOf { it.movementDurationSeconds })
        assertEquals(expected, result.status)
        assertEquals(if (expected == TransitTransferStatus.MISSED) 1L else null, result.missedBySeconds)
        assertFalse(result.eligible)
    }

    @Test
    fun `Tmap 소요초가 있어도 미제공 대기시간이나 미래 환승 차량을 생성하지 않는다`() {
        val arrivals = mock<TransitArrivalService>()
        val journey = mappedJourney()
        val resolution = FirstBoardingRealtimeOverlay(arrivals, Clock.fixed(now, ZoneOffset.UTC))
            .resolve(journey, now, 1440)

        assertNull(resolution.overlay)
        assertEquals(TransitTransferStatus.UNKNOWN, TransitTransferFeasibilityEvaluator().evaluate(journey, null, now).status)
        assertNull(resolution.firstBoardingObservation)
        verify(arrivals).getSubwayArrivalsForEta("노원", "4호선", null, null, 10)
    }

    private fun mappedJourney() = TmapTransitJourneyMapper().map(
        jacksonObjectMapper().readTree(
            """
            {"metaData":{"plan":{"itineraries":[{"totalTime":3888,"legs":[
              {"mode":"WALK","sectionTime":111},
              {"mode":"SUBWAY","sectionTime":2882,"service":1,"type":4,"route":"수도권 4호선",
                "routeId":"110041026","start":{"name":"노원"},"end":{"name":"사당"}},
              {"mode":"WALK","sectionTime":243},
              {"mode":"SUBWAY","sectionTime":481,"service":1,"type":2,"route":"수도권 2호선",
                "routeId":"110021006","start":{"name":"사당"},"end":{"name":"신림"}},
              {"mode":"WALK","sectionTime":171}
            ]}]}}}
            """.trimIndent(),
        ),
        TransitJourneySearchRequest(37.655, 127.061, 37.484, 126.929, now, 1440),
        now,
    ).single()
}
