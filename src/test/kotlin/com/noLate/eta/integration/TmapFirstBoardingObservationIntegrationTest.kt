package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.infrastructure.tmap.TmapTransitJourneyMapper
import com.noLate.schedule.application.EffectiveTransitRouteSnapshot
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** 로컬 HTTP 계약 통합 테스트다. 실제 공급자 API·운영 DB·앱 시뮬레이터 E2E가 아니다. */
class TmapFirstBoardingObservationIntegrationTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `Tmap 매핑부터 서울 HTTP 차량 조회 계산 스냅샷까지 관측만 전달하고 전체 ETA는 확정하지 않는다`() {
        val wireCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                wireCalls.incrementAndGet()
                val body = """
                  {"realtimeArrivalList":[{"subwayId":"1004","statnId":"1004000426","statnNm":"서울역",
                  "updnLine":"하행","bstatnNm":"사당","btrainSttus":"일반","btrainNo":"4012",
                  "barvlDt":"360","recptnDt":"2026-10-04 15:00:00"}]}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val arrivalService = TransitArrivalService(
                SeoulTransitArrivalClient("", "fixture-only", "", url, url, allowInsecureHttp = true),
                mock<TagoTransitArrivalClient>(),
            )
            val response = objectMapper.readTree("""
                {"metaData":{"plan":{"itineraries":[{"totalTime":1482,"legs":[
                  {"mode":"WALK","sectionTime":111},
                  {"mode":"SUBWAY","sectionTime":1200,"service":1,"type":4,"route":"수도권 4호선",
                    "routeId":"11004","start":{"name":"서울역"},"end":{"name":"사당"},
                    "directionName":"사당 방면","directionCode":"DOWN"},
                  {"mode":"WALK","sectionTime":171}
                ]}]}}}
            """.trimIndent())
            val provider = object : TransitJourneyProvider {
                override val providerId = "tmap"
                override val supportsAlternativeRouteRecovery = false
                override fun search(request: TransitJourneySearchRequest) =
                    TmapTransitJourneyMapper().map(response, request, now)
            }
            val matcher = TransitJourneyMatcher()
            val service = TransitEtaCalculationService(
                SelectedTransitRouteDecoder(objectMapper), listOf(provider), SafeDepartureResolver(matcher, maxSearches = 3),
                FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(now, ZoneOffset.UTC)), matcher,
                TransitTransferFeasibilityEvaluator(),
            )
            // fixture는 방향을 명시한다. 실제 Tmap 응답에 방향이 없으면 관측도 거절한다.
            val request = TrafficRequest(
                originLat = 37.55, originLng = 126.97, destinationLat = 37.48, destinationLng = 126.98,
                travelMode = ScheduleTravelMode.TRANSIT, fallbackTravelMinutes = 25, selectedRouteTravelMinutes = 25,
                evaluatedAt = now, plannedDepartureAt = now,
                selectedRouteJson = """{"provider":"tmap","steps":[{"mode":"SUBWAY","routeId":"11004",
                  "lineName":"수도권 4호선","serviceClass":"LOCAL","startName":"서울역","endName":"사당",
                  "directionName":"사당 방면","directionCode":"DOWN"}]}""",
            )

            val result = service.calculate(request)
            val snapshot = requireNotNull(EffectiveTransitRouteSnapshot.from(result, null, 0))
            val restored = requireNotNull(EffectiveTransitRouteSnapshot.parse(objectMapper, objectMapper.writeValueAsString(snapshot)))

            assertEquals(1, wireCalls.get()) // 재탐색 중 동일 정류장 조회는 15초 캐시로 흡수한다.
            assertNotNull(result.firstBoardingObservation)
            assertEquals(now.plusSeconds(360), result.firstBoardingObservation?.expectedBoardingAt)
            assertEquals("4012", restored.firstBoardingObservation?.trainNumber)
            assertEquals(result.firstBoardingObservation, restored.firstBoardingObservation)
            assertEquals(25, result.travelMinutes)
            assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
            assertFalse(result.accepted)
            assertFalse(restored.accepted)
            assertNull(result.recommendedDepartureAt)
            assertNull(result.predictedArrivalAt)
            assertNull(result.firstBoardingWaitMinutes)
            assertNull(restored.acceptedRoute())
        } finally {
            server.stop(0)
        }
    }
}
