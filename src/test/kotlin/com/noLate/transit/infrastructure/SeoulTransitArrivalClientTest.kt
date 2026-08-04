package com.noLate.transit.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaProviderCircuitState
import com.noLate.eta.resilience.EtaProviderGuard
import com.noLate.eta.resilience.EtaProviderResiliencePolicy
import com.noLate.eta.resilience.StaticEtaProviderResiliencePolicyResolver
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeoulTransitArrivalClientTest {
    @Test
    fun `서울 지하철 INFO-200은 빈 성공으로 다음 역명 후보를 조회하고 circuit을 열지 않는다`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                val body = """
                    {"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val registry = SimpleMeterRegistry()
        val deadline = EtaCalculationDeadline()
        val guard = EtaProviderGuard(
            policyResolver = StaticEtaProviderResiliencePolicyResolver(
                policies = mapOf(
                    "seoul_subway" to EtaProviderResiliencePolicy(
                        maxConcurrentCalls = 1,
                        maxQueuedCalls = 0,
                        maxQueueWait = Duration.ZERO,
                        failureThreshold = 1,
                        openDuration = Duration.ofSeconds(30),
                    )
                )
            ),
            calculationDeadline = deadline,
        )
        try {
            val client = SeoulTransitArrivalClient(
                commonApiKey = "",
                subwayApiKey = "key",
                busApiKey = "",
                subwayBaseUrl = "http://127.0.0.1:${server.address.port}",
                busBaseUrl = "http://127.0.0.1:${server.address.port}",
                calculationDeadline = deadline,
                operationalMetrics = NoLateOperationalMetrics(registry),
                providerGuard = guard,
                wireMetrics = TransitProviderWireMetrics(registry),
            )

            val arrivals = client.getSubwayArrivals("강남역", "2호선", null, null, 1)

            assertEquals(emptyList(), arrivals)
            assertEquals(2, calls.get())
            assertEquals(EtaProviderCircuitState.CLOSED, guard.snapshot("seoul_subway").circuitState)
            assertEquals(0, guard.snapshot("seoul_subway").consecutiveFailures)
            assertEquals(
                2L,
                registry.get("nolate.eta.transit.provider.wire.duration")
                    .tag("provider", "seoul_subway")
                    .tag("operation", "arrival")
                    .tag("outcome", "empty")
                    .timer()
                    .count(),
            )
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.duration")
                    .tag("provider", "seoul_subway")
                    .tag("outcome", "empty")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `서울 지하철 HTTP 200 RESULT 오류는 wire application error와 circuit failure가 된다`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                val body = """
                    {"RESULT":{"CODE":"INFO-100","MESSAGE":"invalid key"}}
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val registry = SimpleMeterRegistry()
        val deadline = EtaCalculationDeadline()
        val guard = EtaProviderGuard(
            policyResolver = StaticEtaProviderResiliencePolicyResolver(
                policies = mapOf(
                    "seoul_subway" to EtaProviderResiliencePolicy(
                        maxConcurrentCalls = 1,
                        maxQueuedCalls = 0,
                        maxQueueWait = Duration.ZERO,
                        failureThreshold = 1,
                        openDuration = Duration.ofSeconds(30),
                    )
                )
            ),
            calculationDeadline = deadline,
        )
        try {
            val client = SeoulTransitArrivalClient(
                commonApiKey = "",
                subwayApiKey = "key",
                busApiKey = "",
                subwayBaseUrl = "http://127.0.0.1:${server.address.port}",
                busBaseUrl = "http://127.0.0.1:${server.address.port}",
                calculationDeadline = deadline,
                operationalMetrics = NoLateOperationalMetrics(registry),
                providerGuard = guard,
                wireMetrics = TransitProviderWireMetrics(registry),
            )

            assertFailsWith<TransitProviderApplicationException> {
                client.getSubwayArrivals("강남", "2호선", null, null, 1)
            }

            assertEquals(1, calls.get())
            assertEquals(EtaProviderCircuitState.OPEN, guard.snapshot("seoul_subway").circuitState)
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.wire.duration")
                    .tag("provider", "seoul_subway")
                    .tag("operation", "arrival")
                    .tag("outcome", "application_error")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `서울 지하철 code와 도착목록이 모두 없는 malformed JSON은 빈 성공이 아니다`() {
        val client = createClient()

        listOf(
            "{}",
            """{"RESULT":{"CODE":"INFO-000"}}""",
            """{"RESULT":{"CODE":"INFO-000"},"realtimeArrivalList":{}}""",
        ).forEach { malformedResponse ->
            assertFailsWith<TransitProviderApplicationException> {
                client.parseSubwayArrivals(
                    response = jacksonObjectMapper().readTree(malformedResponse),
                    lineName = "2호선",
                    directionName = null,
                    directionCode = "DOWN",
                    limit = 1,
                    observedAt = Instant.parse("2026-08-01T08:05:00Z"),
                )
            }
        }
    }

    @Test
    fun `서울 버스 HTTP 200 header 오류는 빈 도착정보가 아니라 예외다`() {
        val client = createClient()

        assertFailsWith<TransitProviderApplicationException> {
            client.parseBusArrivals(
                response = """
                    <ServiceResult>
                      <msgHeader><headerCd>7</headerCd><headerMsg>invalid key</headerMsg></msgHeader>
                      <msgBody />
                    </ServiceResult>
                """.trimIndent(),
                routeName = "402",
                limit = 1,
            )
        }
    }

    @Test
    fun `서울 버스 정상 빈 station lookup은 negative cache로 고정되지 않는다`() {
        val stationCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stationinfo/getStationByName") { exchange ->
                val attempt = stationCalls.incrementAndGet()
                val item = if (attempt == 1) "" else "<itemList><arsId>02005</arsId></itemList>"
                val body = """
                    <ServiceResult>
                      <msgHeader><headerCd>0</headerCd><headerMsg>OK</headerMsg></msgHeader>
                      <msgBody>$item</msgBody>
                    </ServiceResult>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/stationinfo/getStationByUid") { exchange ->
                val body = """
                    <ServiceResult>
                      <msgHeader><headerCd>0</headerCd><headerMsg>OK</headerMsg></msgHeader>
                      <msgBody><itemList>
                        <rtNm>402</rtNm><stNm>서울역</stNm><arrmsg1>2분 후</arrmsg1><traTime1>120</traTime1>
                      </itemList></msgBody>
                    </ServiceResult>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val client = SeoulTransitArrivalClient(
                commonApiKey = "",
                subwayApiKey = "",
                busApiKey = "key",
                subwayBaseUrl = "http://127.0.0.1:${server.address.port}",
                busBaseUrl = "http://127.0.0.1:${server.address.port}",
            )

            assertEquals(emptyList(), client.getBusArrivals(null, "서울역", "402", 1))
            assertEquals(1, client.getBusArrivals(null, "서울역", "402", 1).size)
            assertEquals(2, stationCalls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `서울 버스 Encoding 인증키를 decoding 키로 정규화해 정확히 한 번 인코딩한다`() {
        val stationQuery = AtomicReference<String>()
        val arrivalQuery = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stationinfo/getStationByName") { exchange ->
                stationQuery.set(exchange.requestURI.rawQuery)
                val body = """
                    <ServiceResult>
                      <msgHeader><headerCd>0</headerCd><headerMsg>OK</headerMsg></msgHeader>
                      <msgBody><itemList><arsId>02005</arsId></itemList></msgBody>
                    </ServiceResult>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/stationinfo/getStationByUid") { exchange ->
                arrivalQuery.set(exchange.requestURI.rawQuery)
                val body = """
                    <ServiceResult>
                      <msgHeader><headerCd>0</headerCd><headerMsg>OK</headerMsg></msgHeader>
                      <msgBody><itemList>
                        <rtNm>402</rtNm><stNm>서울역</stNm><arrmsg1>2분 후</arrmsg1><traTime1>120</traTime1>
                      </itemList></msgBody>
                    </ServiceResult>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val client = SeoulTransitArrivalClient(
                commonApiKey = "",
                subwayApiKey = "",
                busApiKey = "decoded%2Bkey%2Fwith%3D%3D",
                subwayBaseUrl = "http://127.0.0.1:${server.address.port}",
                busBaseUrl = "http://127.0.0.1:${server.address.port}",
            )

            val arrivals = client.getBusArrivals(
                arsId = null,
                stationName = "서울역",
                routeName = "402",
                limit = 1,
            )

            assertEquals(1, arrivals.size)
            listOf(requireNotNull(stationQuery.get()), requireNotNull(arrivalQuery.get())).forEach { query ->
                val encodedKey = query.substringAfter("serviceKey=").substringBefore('&')
                assertTrue(!encodedKey.contains("%25", ignoreCase = true))
                assertEquals(
                    "decoded+key/with==",
                    URLDecoder.decode(encodedKey, StandardCharsets.UTF_8),
                )
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `서울 버스 논리 조회의 성공 latency를 고정 provider outcome으로 기록한다`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stationinfo/getStationByUid") { exchange ->
                val body = """
                    <ServiceResult><msgBody><itemList>
                      <rtNm>402</rtNm><stNm>서울역버스환승센터</stNm>
                      <arrmsg1>2분 후</arrmsg1><traTime1>120</traTime1>
                    </itemList></msgBody></ServiceResult>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/xml")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val registry = SimpleMeterRegistry()
        try {
            val client = SeoulTransitArrivalClient(
                commonApiKey = "",
                subwayApiKey = "",
                busApiKey = "key",
                subwayBaseUrl = "http://127.0.0.1:${server.address.port}",
                busBaseUrl = "http://127.0.0.1:${server.address.port}",
                operationalMetrics = NoLateOperationalMetrics(registry),
            )

            val arrivals = client.getBusArrivals(
                arsId = "02005",
                stationName = null,
                routeName = "402",
                limit = 1,
            )

            assertEquals(1, arrivals.size)
            assertEquals(
                1L,
                registry.get("nolate.eta.transit.provider.duration")
                    .tag("provider", "seoul_bus")
                    .tag("outcome", "success")
                    .timer()
                    .count(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `서울 버스 차량 유형 코드를 사용자 표시값으로 변환한다`() {
        assertEquals("일반", seoulBusVehicleType("0"))
        assertEquals("저상", seoulBusVehicleType("1"))
        assertEquals("굴절", seoulBusVehicleType("2"))
        assertEquals(null, seoulBusVehicleType(null))
        assertEquals(null, seoulBusVehicleType("9"))
    }

    @Test
    fun `서울 버스 XML에서 저상 막차 행선지와 도착 상태를 보존한다`() {
        val client = createClient()
        val arrivals = client.parseBusArrivals(
            response = """
                <ServiceResult>
                  <msgBody>
                    <itemList>
                      <rtNm>402</rtNm>
                      <stNm>서울역버스환승센터</stNm>
                      <adirection>후암약수터</adirection>
                      <arrmsg1>곧 도착</arrmsg1>
                      <traTime1>0</traTime1>
                      <isArrive1>1</isArrive1>
                      <isLast1>1</isLast1>
                      <busType1>1</busType1>
                      <stationNm1>장지공영차고지</stationNm1>
                    </itemList>
                  </msgBody>
                </ServiceResult>
            """.trimIndent(),
            routeName = "402번",
            limit = 2,
        )

        assertEquals(1, arrivals.size)
        assertEquals("장지공영차고지", arrivals.single().destinationName)
        assertEquals("저상", arrivals.single().vehicleType)
        assertEquals(true, arrivals.single().lowFloor)
        assertEquals(true, arrivals.single().lastTrain)
        assertEquals("ARRIVED", arrivals.single().arrivalStatus.name)
        assertEquals(null, arrivals.single().sourceUpdatedAt)
        assertEquals(
            TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
            arrivals.single().freshnessEvidence,
        )
    }

    @Test
    fun `서울 버스 공식 제공시각 mkTm을 원천 freshness와 도착예정 기준으로 사용한다`() {
        val client = createClient()
        val arrival = client.parseBusArrivals(
            response = """
                <ServiceResult>
                  <msgHeader><headerCd>0</headerCd><headerMsg>OK</headerMsg></msgHeader>
                  <msgBody>
                    <itemList>
                      <rtNm>402</rtNm>
                      <stNm>서울역버스환승센터</stNm>
                      <mkTm>2026-08-01 17:00:00.0</mkTm>
                      <arrmsg1>2분 후</arrmsg1>
                      <traTime1>120</traTime1>
                    </itemList>
                  </msgBody>
                </ServiceResult>
            """.trimIndent(),
            routeName = "402",
            limit = 1,
        ).single()

        assertEquals("2026-08-01T08:00:00Z", arrival.sourceUpdatedAt)
        assertEquals("2026-08-01T08:02:00Z", arrival.expectedAt)
        assertEquals(
            TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
            arrival.freshnessEvidence,
        )
    }

    @Test
    fun `서울 버스 제공시각 형식이 깨지면 로컬 수신 증거로 fail closed 한다`() {
        val client = createClient()
        val arrival = client.parseBusArrivals(
            response = """
                <ServiceResult>
                  <msgHeader><headerCd>0</headerCd><headerMsg>OK</headerMsg></msgHeader>
                  <msgBody>
                    <itemList>
                      <rtNm>402</rtNm>
                      <mkTm>not-a-provider-time</mkTm>
                      <arrmsg1>2분 후</arrmsg1>
                      <traTime1>120</traTime1>
                    </itemList>
                  </msgBody>
                </ServiceResult>
            """.trimIndent(),
            routeName = "402",
            limit = 1,
        ).single()

        assertEquals(null, arrival.sourceUpdatedAt)
        assertEquals(
            TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
            arrival.freshnessEvidence,
        )
    }

    @Test
    fun `ODsay 하행 코드로 반대 방향 지하철 도착정보를 제외한다`() {
        val client = createClient()
        val arrivals = listOf(
            subwayArrival(direction = "상행", destinationName = "당고개"),
            subwayArrival(direction = "하행", destinationName = "오이도"),
        )

        val filtered = client.filterSubwayDirection(
            arrivals = arrivals,
            directionName = "사당 방면",
            directionCode = "DOWN",
        )

        assertEquals(listOf("오이도"), filtered.map { it.destinationName })
    }

    @Test
    fun `방향 매칭이 불가능하면 빈 값 때문에 결과를 버리지 않는다`() {
        val client = createClient()
        val arrivals = listOf(
            subwayArrival(direction = null, destinationName = null),
            subwayArrival(direction = "상행", destinationName = "당고개"),
        )

        val filtered = client.filterSubwayDirection(
            arrivals = arrivals,
            directionName = "사당 방면",
            directionCode = null,
        )

        assertEquals(arrivals, filtered)
    }

    @Test
    fun `서울 지하철 expectedAt은 서버 수신시각이 아니라 recptnDt 원천시각에 대기초를 더한다`() {
        val client = createClient()
        val response = jacksonObjectMapper().readTree(
            """
                {
                  "realtimeArrivalList": [{
                    "subwayId": "1002",
                    "statnNm": "강남",
                    "updnLine": "하행",
                    "barvlDt": "120",
                    "recptnDt": "2026-08-01 17:00:00",
                    "arvlMsg2": "2분 후"
                  }]
                }
            """.trimIndent()
        )

        val arrival = client.parseSubwayArrivals(
            response = response,
            lineName = "2호선",
            directionName = null,
            directionCode = "DOWN",
            limit = 1,
            observedAt = Instant.parse("2026-08-01T08:05:00Z"),
        ).single()

        assertEquals("2026-08-01T08:00:00Z", arrival.sourceUpdatedAt)
        assertEquals("2026-08-01T08:02:00Z", arrival.expectedAt)
        assertEquals("2026-08-01T08:05:00Z", arrival.observedAt)
        assertEquals(
            TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
            arrival.freshnessEvidence,
        )
    }

    @Test
    fun `서울 지하철 원천 관측시각이 없으면 로컬 수신시각만 있다는 증거를 보존한다`() {
        val client = createClient()
        val response = jacksonObjectMapper().readTree(
            """
                {
                  "realtimeArrivalList": [{
                    "subwayId": "1002",
                    "statnNm": "강남",
                    "updnLine": "하행",
                    "barvlDt": "120",
                    "arvlMsg2": "2분 후"
                  }]
                }
            """.trimIndent()
        )

        val arrival = client.parseSubwayArrivals(
            response = response,
            lineName = "2호선",
            directionName = null,
            directionCode = "DOWN",
            limit = 1,
            observedAt = Instant.parse("2026-08-01T08:05:00Z"),
        ).single()

        assertEquals(null, arrival.sourceUpdatedAt)
        assertEquals("2026-08-01T08:07:00Z", arrival.expectedAt)
        assertEquals(
            TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
            arrival.freshnessEvidence,
        )
    }

    private fun createClient() = SeoulTransitArrivalClient(
        commonApiKey = "",
        subwayApiKey = "",
        busApiKey = "",
        subwayBaseUrl = "http://localhost",
        busBaseUrl = "http://localhost",
    )

    private fun subwayArrival(
        direction: String?,
        destinationName: String?,
    ) = TransitArrivalDto(
        provider = "seoul-openapi",
        kind = "SUBWAY",
        direction = direction,
        destinationName = destinationName,
    )
}
