package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TransitRealtimeTrafficClientTest {
    private val evaluatedAt = Instant.parse("2026-07-29T03:00:00Z")
    private val clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC)
    private val arrivalService = mock<TransitArrivalService>()
    private val objectMapper = jacksonObjectMapper()
    private val matcher = TransitJourneyMatcher()
    private val firstBoardingOverlay = FirstBoardingRealtimeOverlay(
        transitArrivalService = arrivalService,
        clock = clock,
        boardingBufferSeconds = 60,
        arrivalLimit = 10,
    )
    private val client = TransitRealtimeTrafficClient(
        TransitEtaCalculationService(
            selectedRouteDecoder = SelectedTransitRouteDecoder(objectMapper),
            journeyProviders = emptyList(),
            safeDepartureResolver = SafeDepartureResolver(matcher, maxSearches = 3, toleranceSeconds = 60),
            firstBoardingRealtimeOverlay = firstBoardingOverlay,
            journeyMatcher = matcher,
            transferFeasibilityEvaluator = TransitTransferFeasibilityEvaluator(),
        )
    )

    @Test
    fun `정류장 도보 중 놓치는 첫 버스는 제외하고 탈 수 있는 다음 버스 대기시간을 ETA에 더한다`() {
        whenever(busArrivals()).thenReturn(
            listOf(
                busArrival(expectedAt = "2026-07-29T03:05:00Z"),
                busArrival(expectedAt = "2026-07-29T03:12:00Z"),
            )
        )

        val result = client.getTravelMinutes(
            request(
                routeJson = busRouteJson(accessMinutes = 8, rideMinutes = 20, egressMinutes = 5),
            )
        )

        // 순수 이동 33분 + 정류장 도착(03:08) 후 실제 대기 4분
        assertEquals(37, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(evaluatedAt, result.fetchedAt)
        assertFalse(result.stale)
        assertNull(result.failureReason)
    }

    @Test
    fun `출발 예정이 미래이면 지금 오는 차량이 아니라 예정 승차시각 이후 차량을 고른다`() {
        whenever(busArrivals())
            .thenReturn(
                listOf(
                    busArrival(expectedAt = "2026-07-29T03:10:00Z"),
                    busArrival(expectedAt = "2026-07-29T03:28:00Z"),
                )
            )

        val result = client.getTravelMinutes(
            request(
                routeJson = busRouteJson(accessMinutes = 5, rideMinutes = 15, egressMinutes = 5),
                plannedDepartureAt = Instant.parse("2026-07-29T03:20:00Z"),
            )
        )

        // 03:20 출발, 03:25 정류장 도착, 03:28 버스까지 3분 대기
        assertEquals(28, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
    }

    @Test
    fun `계획 승차시각 이후 차량이 조회 범위에 없으면 저장 ETA로 안전하게 fallback한다`() {
        whenever(busArrivals())
            .thenReturn(listOf(busArrival(expectedAt = "2026-07-29T03:10:00Z")))

        val result = client.getTravelMinutes(
            request(
                routeJson = busRouteJson(accessMinutes = 5, rideMinutes = 15, egressMinutes = 5),
                plannedDepartureAt = Instant.parse("2026-07-29T03:20:00Z"),
            )
        )

        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertNull(result.fetchedAt)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON, result.failureReason)
    }

    @Test
    fun `legacy 지하철 경로에 권역 하차역 서비스유형 증거가 없으면 실시간 overlay를 적용하지 않는다`() {
        whenever(
            arrivalService.getSubwayArrivals(
                stationName = "강남역",
                lineName = "2호선",
                directionName = "역삼 방면",
                directionCode = "UP",
                limit = 10,
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-subway",
                    kind = "SUBWAY",
                    lineName = "2호선",
                    direction = "상행",
                    expectedAt = "2026-07-29T03:05:00Z",
                    observedAt = evaluatedAt.toString(),
                )
            )
        )

        val result = client.getTravelMinutes(request(routeJson = subwayRouteJson()))

        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, result.failureReason)
        verify(arrivalService, never()).getSubwayArrivals(
            stationName = any(),
            lineName = anyOrNull(),
            directionName = anyOrNull(),
            directionCode = anyOrNull(),
            limit = any(),
        )
    }

    @Test
    fun `선택 경로와 반대 방향 지하철만 응답하면 live ETA로 사용하지 않는다`() {
        whenever(
            arrivalService.getSubwayArrivals(
                stationName = "강남역",
                lineName = "2호선",
                directionName = "역삼 방면",
                directionCode = "UP",
                limit = 10,
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-subway",
                    kind = "SUBWAY",
                    lineName = "2호선",
                    direction = "하행",
                    destinationName = "성수",
                    expectedAt = "2026-07-29T03:05:00Z",
                    observedAt = evaluatedAt.toString(),
                )
            )
        )

        val result = client.getTravelMinutes(request(routeJson = subwayRouteJson()))

        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, result.failureReason)
    }

    @Test
    fun `expectedAt이 없어도 provider 관측시각과 waitSeconds로 도착시각을 복원한다`() {
        whenever(busArrivals())
            .thenReturn(
                listOf(
                    TransitArrivalDto(
                        provider = "tago",
                        kind = "BUS",
                        routeName = "402",
                        waitSeconds = 600,
                        expectedAt = null,
                        observedAt = evaluatedAt.toString(),
                        sourceUpdatedAt = evaluatedAt.toString(),
                        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                    )
                )
            )

        val result = client.getTravelMinutes(
            request(routeJson = busRouteJson(accessMinutes = 4, rideMinutes = 12, egressMinutes = 4))
        )

        // 순수 이동 20분 + 03:04 정류장 도착 후 6분 대기
        assertEquals(26, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
    }

    @Test
    fun `저장 구간에 포함된 공급자 대기시간은 실시간 대기시간으로 교체해 이중 합산하지 않는다`() {
        whenever(busArrivals())
            .thenReturn(listOf(busArrival(expectedAt = "2026-07-29T03:07:00Z")))

        val result = client.getTravelMinutes(
            request(
                routeJson = busRouteJson(
                    accessMinutes = 5,
                    rideMinutes = 20,
                    egressMinutes = 5,
                    storedFirstWaitMinutes = 5,
                )
            )
        )

        // 저장 30분 중 기존 대기 5분을 빼고, 정류장 도착 후 실시간 대기 2분으로 교체
        assertEquals(27, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
    }

    @Test
    fun `cityCode nodeId 형식의 비서울 정류장도 원래 식별자로 조회한다`() {
        whenever(busArrivals())
            .thenReturn(listOf(busArrival(expectedAt = "2026-07-29T03:06:00Z")))

        val routeJson = busRouteJson(
            accessMinutes = 2,
            rideMinutes = 10,
            egressMinutes = 3,
            stationName = "대전역",
            lineName = "100",
            stopCode = "25:DJB8001793",
        )
        val result = client.getTravelMinutes(request(routeJson = routeJson))

        assertEquals(19, result.travelMinutes)
        verify(arrivalService).getBusArrivals(
            arsId = isNull(),
            routeName = eq("100"),
            cityCode = eq("25"),
            nodeId = eq("DJB8001793"),
            stationName = eq("대전역"),
            limit = eq(10),
            cityCodeNamespace = eq(com.noLate.transit.domain.TransitCityCodeNamespace.UNKNOWN),
            providerCode = isNull(),
        )
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
    }

    @Test
    fun `실시간 도착정보가 비거나 조회가 실패하면 저장 ETA와 안정된 실패 사유를 사용한다`() {
        whenever(busArrivals())
            .thenReturn(emptyList())

        val emptyResult = client.getTravelMinutes(request(routeJson = busRouteJson()))

        assertEquals(40, emptyResult.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, emptyResult.source)
        assertTrue(emptyResult.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, emptyResult.failureReason)

        whenever(busArrivals())
            .thenThrow(IllegalStateException("provider credential must not leak"))

        val failedResult = client.getTravelMinutes(request(routeJson = busRouteJson()))

        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, failedResult.failureReason)
        assertFalse(failedResult.failureReason.orEmpty().contains("credential"))
    }

    @Test
    fun `경로 JSON이 없거나 구간 시간이 불완전하면 외부 조회 없이 저장 ETA를 사용한다`() {
        val noJson = client.getTravelMinutes(request(routeJson = null))
        val incomplete = client.getTravelMinutes(
            request(
                routeJson = """
                    {
                      "routeInfo": {
                        "steps": [
                          {"type":"ORIGIN","title":"집"},
                          {"type":"WALK","title":"도보"},
                          {"type":"BUS","title":"서울역","durationMinutes":20,"lineName":"402"},
                          {"type":"DESTINATION","title":"회사"}
                        ]
                      }
                    }
                """.trimIndent()
            )
        )

        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING, noJson.failureReason)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING, incomplete.failureReason)
        verify(arrivalService, never()).getBusArrivals(
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
            any(),
            anyOrNull(),
        )
        verify(arrivalService, never()).getSubwayArrivals(
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )
    }

    @Test
    fun `실시간 대기시간을 더한 결과가 제품 상한을 넘으면 live로 승격하지 않는다`() {
        whenever(busArrivals())
            .thenReturn(listOf(busArrival(expectedAt = "2026-07-29T03:20:00Z")))

        val result = client.getTravelMinutes(
            request(
                routeJson = busRouteJson(accessMinutes = 5, rideMinutes = 105, egressMinutes = 5),
                maxTravelMinutes = 120,
            )
        )

        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(TrafficFailureReasons.PROVIDER_INVALID_RESPONSE, result.failureReason)
    }

    @Test
    fun `TRANSIT 이외 이동수단 요청은 계약 위반으로 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            client.getTravelMinutes(
                request(routeJson = busRouteJson()).copy(travelMode = ScheduleTravelMode.CAR)
            )
        }
    }

    private fun request(
        routeJson: String?,
        plannedDepartureAt: Instant? = evaluatedAt,
        maxTravelMinutes: Int = 1_440,
    ) = TrafficRequest(
        originLat = 37.1,
        originLng = 127.1,
        destinationLat = 37.2,
        destinationLng = 127.2,
        travelMode = ScheduleTravelMode.TRANSIT,
        fallbackTravelMinutes = 40,
        selectedRouteJson = routeJson,
        selectedRouteTravelMinutes = 40,
        evaluatedAt = evaluatedAt,
        plannedDepartureAt = plannedDepartureAt,
        maxTravelMinutes = maxTravelMinutes,
    )

    private fun busArrivals(): List<TransitArrivalDto> =
        arrivalService.getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )

    private fun busArrival(expectedAt: String) = TransitArrivalDto(
        provider = "seoul-bus",
        kind = "BUS",
        routeName = "402",
        expectedAt = expectedAt,
        observedAt = evaluatedAt.toString(),
        sourceUpdatedAt = evaluatedAt.toString(),
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
    )

    private fun busRouteJson(
        accessMinutes: Int = 5,
        rideMinutes: Int = 20,
        egressMinutes: Int = 5,
        stationName: String = "서울역버스환승센터",
        lineName: String = "402",
        stopCode: String = "ARS:02005",
        storedFirstWaitMinutes: Int? = null,
    ): String {
        val waitingJson = storedFirstWaitMinutes?.let { ""","waitingMinutes":$it""" }.orEmpty()
        return """
            {
              "minutes": 40,
              "routeInfo": {
                "totalDurationMinutes": 40,
                "steps": [
                  {"type":"ORIGIN","title":"집"},
                  {"type":"WALK","title":"도보","durationMinutes":$accessMinutes},
                  {
                    "type":"BUS",
                    "title":"$stationName",
                    "durationMinutes":$rideMinutes$waitingJson,
                    "lineName":"$lineName",
                    "passStops":[
                      {"name":"$stationName","code":"$stopCode"},
                      {"name":"다음 정류장","code":"ARS:02006"}
                    ]
                  },
                  {"type":"WALK","title":"도보","durationMinutes":$egressMinutes},
                  {"type":"DESTINATION","title":"회사"}
                ]
              }
            }
        """.trimIndent()
    }

    private fun subwayRouteJson(): String =
        """
            {
              "minutes": 25,
              "routeInfo": {
                "totalDurationMinutes": 25,
                "steps": [
                  {"type":"ORIGIN","title":"집"},
                  {"type":"WALK","title":"도보","durationMinutes":3},
                  {
                    "type":"SUBWAY",
                    "title":"강남역",
                    "durationMinutes":15,
                    "lineName":"2호선",
                    "directionName":"역삼 방면",
                    "directionCode":"UP"
                  },
                  {"type":"WALK","title":"도보","durationMinutes":2},
                  {"type":"DESTINATION","title":"회사"}
                ]
              }
            }
        """.trimIndent()
}
