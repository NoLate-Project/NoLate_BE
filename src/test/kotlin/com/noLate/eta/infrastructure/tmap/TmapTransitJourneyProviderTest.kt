package com.noLate.eta.infrastructure.tmap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaMonotonicTicker
import com.noLate.eta.resilience.EtaSoftDeadlineExceededException
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.route.application.TransitRouteProvider
import com.noLate.route.domain.TransitRouteRequest
import com.noLate.route.infrastructure.TmapTransitRouteClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.transit.application.TransitArrivalService
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TmapTransitJourneyProviderTest {
    private val now = Instant.parse("2026-10-04T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val json = jacksonObjectMapper()

    @Test
    fun `기존 길찾기 HTTP 구현에 좌표와 서울 검색시각을 전달하고 추정 여정으로 반환한다`() {
        val method = AtomicReference<String>()
        val header = AtomicReference<String>()
        val body = AtomicReference<JsonNode>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/transit/routes") { exchange ->
            method.set(exchange.requestMethod)
            header.set(exchange.requestHeaders.getFirst("appKey"))
            body.set(json.readTree(exchange.requestBody))
            val bytes = TRANSFER_RESPONSE.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val registry = SimpleMeterRegistry()
            val adapter = adapter(
                TmapTransitRouteClient("synthetic-test-key", "http://127.0.0.1:${server.address.port}"),
                metrics = NoLateOperationalMetrics(registry),
            )
            val request = searchRequest().copy(departureAt = Instant.parse("2026-10-04T15:05:17Z"))
            val result = adapter.search(request).single()

            assertEquals("POST", method.get())
            assertEquals("synthetic-test-key", header.get())
            assertEquals("202610050005", body.get().path("searchDttm").asText())
            assertEquals(request.originLng, body.get().path("startX").asText().toDouble())
            assertEquals(request.originLat, body.get().path("startY").asText().toDouble())
            assertEquals(request.destinationLng, body.get().path("endX").asText().toDouble())
            assertEquals(request.destinationLat, body.get().path("endY").asText().toDouble())
            assertEquals(10, body.get().path("count").asInt())
            assertEquals("tmap", result.provider)
            assertEquals(20, result.totalMinutes)
            assertEquals(now, result.fetchedAt)
            assertEquals(request.departureAt.plusSeconds(1200), result.arrivalAt)
            assertFalse(adapter.supportsAlternativeRouteRecovery)
            assertTrue(adapter.supportsSecondPrecisionDepartureProjection)
            assertEquals(request.departureAt, result.departureAt)
            assertEquals(1.0, registry.get("nolate.eta.transit.provider.events")
                .tag("provider", "tmap_route").tag("outcome", "success").counter().count())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `저장 Tmap 환승 경로는 실제 어댑터를 재호출하며 ODsay로 바꾸거나 추정을 알람에 채택하지 않는다`() {
        val calls = AtomicInteger()
        val tmap = adapter(provider {
            calls.incrementAndGet()
            json.readTree(TRANSFER_RESPONSE)
        })
        val odsayCalls = AtomicInteger()
        val odsay = object : TransitJourneyProvider {
            override val providerId = "odsay"
            override fun search(request: TransitJourneySearchRequest) = emptyList<com.noLate.eta.domain.TransitJourney>()
                .also { odsayCalls.incrementAndGet() }
        }
        val result = service(listOf(odsay, tmap)).calculate(trafficRequest())

        assertEquals(3, calls.get())
        assertEquals(0, odsayCalls.get())
        assertEquals(now, result.fetchedAt)
        assertEquals(20, result.effectiveTransitRoute?.totalMinutes)
        assertEquals("tmap", result.effectiveTransitRoute?.provider)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertEquals(TransitTimingBasis.ROUTE_ESTIMATE_ONLY, result.transitTimingBasis)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        // 인터페이스 연결 단계에서는 기존 ODsay와 동일한 채택 정책을 유지한다.
        assertEquals(40, result.travelMinutes)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `다른 Tmap 후보는 더 빠르더라도 선택 경로 대신 채택하거나 ODsay 대체경로로 표시하지 않는다`() {
        val tmap = adapter(provider { json.readTree(TRANSFER_RESPONSE.replace("bus-402", "bus-500")) })
        val result = service(listOf(tmap)).calculate(trafficRequest())
        assertEquals(TrafficFailureReasons.TRANSIT_SELECTED_ROUTE_NOT_FOUND, result.failureReason)
        assertFalse(result.accepted)
        assertNull(result.effectiveTransitRoute)
        assertNull(result.transitRouteProvenance)
    }

    @Test
    fun `Tmap 어댑터가 함께 등록돼도 저장 ODsay 경로는 ODsay만 재조회한다`() {
        val tmapCalls = AtomicInteger()
        val tmap = adapter(provider {
            tmapCalls.incrementAndGet()
            json.readTree(TRANSFER_RESPONSE)
        })
        val odsayCalls = AtomicInteger()
        val odsay = object : TransitJourneyProvider {
            override val providerId = "odsay"
            override fun search(request: TransitJourneySearchRequest) = emptyList<com.noLate.eta.domain.TransitJourney>()
                .also { odsayCalls.incrementAndGet() }
        }
        val request = trafficRequest().let {
            it.copy(selectedRouteJson = requireNotNull(it.selectedRouteJson).replace("\"tmap\"", "\"odsay\""))
        }

        service(listOf(tmap, odsay)).calculate(request)

        assertEquals(3, odsayCalls.get())
        assertEquals(0, tmapCalls.get())
    }

    @Test
    fun `저장 지하철의 종별이 누락되면 재조회 성공만으로 동일 경로를 확정하지 않는다`() {
        val calls = AtomicInteger()
        val subwayResponse = TRANSFER_RESPONSE.replace("\"mode\":\"BUS\"", "\"mode\":\"SUBWAY\",\"type\":2")
        val tmap = adapter(provider {
            calls.incrementAndGet()
            json.readTree(subwayResponse)
        })
        val request = trafficRequest().let {
            it.copy(selectedRouteJson = requireNotNull(it.selectedRouteJson).replace("\"kind\":\"BUS\"", "\"kind\":\"SUBWAY\""))
        }

        val result = service(listOf(tmap)).calculate(request)

        assertEquals(3, calls.get())
        assertEquals(TrafficFailureReasons.TRANSIT_SELECTED_ROUTE_NOT_FOUND, result.failureReason)
        assertFalse(result.accepted)
        assertNull(result.effectiveTransitRoute)
    }

    @Test
    fun `전송 실패는 공통 실패 경로로 처리하고 ODsay나 저장값을 최신 조회로 위장하지 않는다`() {
        val tmap = adapter(provider { error("synthetic provider error") })
        val result = service(listOf(tmap)).calculate(trafficRequest())
        assertEquals(TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE, result.failureReason)
        assertEquals(40, result.travelMinutes)
        assertNull(result.fetchedAt)
        assertFalse(result.accepted)
    }

    @Test
    fun `만료된 공통 ETA 예산에서는 기존 길찾기 포트도 호출하지 않는다`() {
        val ticks = AtomicLong()
        val deadline = EtaCalculationDeadline(Duration.ofSeconds(1), EtaMonotonicTicker { ticks.get() })
        val calls = AtomicInteger()
        val tmap = TmapTransitJourneyProvider(
            routeProviders = listOf(provider { calls.incrementAndGet(); json.readTree(TRANSFER_RESPONSE) }),
            mapper = TmapTransitJourneyMapper(), clock = clock, calculationDeadline = deadline,
        )
        assertThrows(EtaSoftDeadlineExceededException::class.java) {
            deadline.within {
                ticks.set(Duration.ofSeconds(2).toNanos())
                tmap.search(searchRequest())
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `중복 또는 누락된 Tmap 검색 구현은 임의 공급자로 대체하지 않는다`() {
        assertThrows(IllegalArgumentException::class.java) {
            TmapTransitJourneyProvider(emptyList(), TmapTransitJourneyMapper())
        }
        val transport = provider { json.readTree(TRANSFER_RESPONSE) }
        assertThrows(IllegalArgumentException::class.java) {
            TmapTransitJourneyProvider(listOf(transport, transport), TmapTransitJourneyMapper())
        }
    }

    @Test
    fun `Spring 연결은 명시적 Tmap ETA 활성화 때만 등록한다`() {
        val runner = ApplicationContextRunner().withUserConfiguration(TestConfiguration::class.java)
        runner.run { context ->
            assertNull(context.startupFailure)
            assertEquals(0, context.getBeansOfType(TmapTransitJourneyProvider::class.java).size)
        }
        runner.withPropertyValues("eta.transit.tmap.enabled=true").run { context ->
            assertNull(context.startupFailure)
            assertEquals("tmap", context.getBean(TmapTransitJourneyProvider::class.java).providerId)
        }
    }

    private fun adapter(transport: TransitRouteProvider, metrics: NoLateOperationalMetrics? = null) =
        TmapTransitJourneyProvider(listOf(transport), TmapTransitJourneyMapper(), clock, operationalMetrics = metrics)

    private fun provider(call: (TransitRouteRequest) -> JsonNode) = object : TransitRouteProvider {
        override fun providerId() = "tmap"
        override fun getRoutes(request: TransitRouteRequest) = call(request)
    }

    private fun searchRequest() = TransitJourneySearchRequest(37.65, 127.06, 37.48, 126.93, now, 1440)

    private fun trafficRequest() = TrafficRequest(
        originLat = 37.65, originLng = 127.06, destinationLat = 37.48, destinationLng = 126.93,
        travelMode = ScheduleTravelMode.TRANSIT, fallbackTravelMinutes = 40, selectedRouteTravelMinutes = 40,
        evaluatedAt = now, plannedDepartureAt = now, selectedRouteJson = """
        {"provider":"tmap","transitLegs":[
          {"kind":"BUS","providerRouteId":"bus-402","lineName":"간선:402","durationMinutes":9,
           "startName":"출발 정류장","endName":"환승 정류장","startStopCode":"12345","endStopCode":"222222"},
          {"kind":"BUS","providerRouteId":"bus-701","lineName":"간선:701","durationMinutes":8,
           "startName":"환승 정류장","endName":"도착 정류장","startStopCode":"222222","endStopCode":"333333"}
        ]}
        """.trimIndent(),
    )

    private fun service(providers: List<TransitJourneyProvider>): TransitEtaCalculationService {
        val matcher = TransitJourneyMatcher()
        return TransitEtaCalculationService(
            selectedRouteDecoder = SelectedTransitRouteDecoder(json), journeyProviders = providers,
            journeyMatcher = matcher, safeDepartureResolver = SafeDepartureResolver(matcher),
            firstBoardingRealtimeOverlay = FirstBoardingRealtimeOverlay(mock<TransitArrivalService>(), clock = clock),
            transferFeasibilityEvaluator = TransitTransferFeasibilityEvaluator(),
        )
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TmapTransitJourneyProvider::class, TmapTransitJourneyMapper::class)
    class TestConfiguration {
        @Bean
        fun routeProvider(): TransitRouteProvider = object : TransitRouteProvider {
            override fun providerId() = "tmap"
            override fun getRoutes(request: TransitRouteRequest) = jacksonObjectMapper().readTree(TRANSFER_RESPONSE)
        }
    }

    private companion object {
        val TRANSFER_RESPONSE = """
          {"metaData":{"plan":{"itineraries":[{"totalTime":1200,"legs":[
            {"mode":"WALK","sectionTime":60},
            {"mode":"BUS","sectionTime":540,"service":1,"route":"간선:402","routeId":"bus-402",
             "start":{"name":"출발 정류장"},"end":{"name":"환승 정류장"},
             "passStopList":{"stations":[{"stationID":"12345","stationName":"출발 정류장"},{"stationID":"222222","stationName":"환승 정류장"}]}},
            {"mode":"WALK","sectionTime":60},
            {"mode":"BUS","sectionTime":480,"service":1,"route":"간선:701","routeId":"bus-701",
             "start":{"name":"환승 정류장"},"end":{"name":"도착 정류장"},
             "passStopList":{"stations":[{"stationID":"222222","stationName":"환승 정류장"},{"stationID":"333333","stationName":"도착 정류장"}]}},
            {"mode":"WALK","sectionTime":60}
          ]}]}}}
        """.trimIndent()
    }
}
