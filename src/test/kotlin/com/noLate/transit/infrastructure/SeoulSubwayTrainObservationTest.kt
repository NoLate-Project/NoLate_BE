package com.noLate.transit.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.*
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SeoulSubwayTrainObservationTest {
    private val query = SeoulSubwayTrainQuery(SeoulSubwayObservationLine.LINE_2)
    private val now = Instant.parse("2026-10-12T11:10:10Z")
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private fun row(vararg changes: Pair<String, Any?>): Map<String, Any?> = linkedMapOf<String, Any?>(
        "subwayId" to "1002", "subwayNm" to "2호선", "statnId" to "1002000232", "statnTid" to "1002000211",
        "trainNo" to "000123", "updnLine" to "0", "trainSttus" to "1", "directAt" to "0", "lstcarAt" to "0",
        "lastRecptnDt" to "20261012", "recptnDt" to "20:10:05", "statnNm" to "UNRETAINED-NAME",
    ).apply { putAll(changes) }
    private fun body(rows: List<Map<String, Any?>> = listOf(row()), code: String = "INFO-000", total: Any = rows.size): ByteArray =
        mapper.writeValueAsBytes(mapOf("errorMessage" to mapOf("code" to code, "total" to total), "realtimePositionList" to rows))
    private fun parse(bytes: ByteArray = body(), at: Instant = now) = SeoulSubwayTrainObservationParser.parse(bytes, query, at)
    private fun rejection(reason: SeoulSubwayTrainRejection, vararg changes: Pair<String, Any?>) {
        val result = parse(body(listOf(row(*changes))))
        assertEquals(SeoulSubwayTrainStatus.NO_USABLE_OBSERVATIONS, result.status)
        assertEquals(mapOf(reason to 1), result.rejectionCounts)
    }

    @Test fun `station referenced source observation never certifies passenger or station order`() {
        val batch = parse()
        assertEquals(SeoulSubwayTrainStatus.AVAILABLE, batch.status)
        val train = batch.observations.single()
        assertEquals("000123", train.trainNumber) // Leading zero is part of provider identity.
        assertEquals(Instant.parse("2026-10-12T11:10:05Z"), train.sourceUpdatedAt)
        assertEquals(Instant.parse("2026-10-12T11:10:35Z"), train.validUntil)
        assertEquals("UNRETAINED-NAME", train.stationName)
        assertNull(train.destinationStationName)
        assertFalse(batch.passengerTrainAssociation)
        assertFalse(batch.stationOrderCrosswalkVerified)
        assertFalse(batch.passengerGeographicPositionAvailable)
        val json = mapper.writeValueAsString(batch)
        listOf("000123", "trainNumber", "1002000232", "UNRETAINED-NAME", "20:10:05").forEach { assertFalse(json.contains(it)) }
        assertFalse(train.toString().contains("000123"))
        assertFalse(batch.toString().contains("000123"))
    }

    @Test fun `explicit date and clock forms have one strict Seoul instant`() {
        for (day in listOf("20261012", "2026-10-12")) for (clock in listOf("20:10:05", "2026-10-12 20:10:05")) {
            val observation = parse(body(listOf(row("lastRecptnDt" to day, "recptnDt" to clock)))).observations.single()
            assertEquals(Instant.parse("2026-10-12T11:10:05Z"), observation.sourceUpdatedAt)
        }
    }

    @Test fun `unsupported timestamp cannot borrow receipt date or clock`() {
        for (value in listOf(null, "201005", "20:10", "2026-10-12T20:10:05+09:00", " 20:10:05"))
            rejection(SeoulSubwayTrainRejection.TIMESTAMP_UNSUPPORTED, "recptnDt" to value)
        for (value in listOf(null, "2026/10/12", ""))
            rejection(SeoulSubwayTrainRejection.TIMESTAMP_UNSUPPORTED, "lastRecptnDt" to value)
    }

    @Test fun `invalid calendar and mismatched full clock date are rejected`() {
        rejection(SeoulSubwayTrainRejection.TIMESTAMP_INVALID, "lastRecptnDt" to "20260230")
        rejection(SeoulSubwayTrainRejection.TIMESTAMP_INVALID, "recptnDt" to "24:10:05")
        rejection(SeoulSubwayTrainRejection.TIMESTAMP_DATE_MISMATCH, "recptnDt" to "2026-10-11 20:10:05")
    }

    @Test fun `exclusive freshness deadline and future source are not repaired by receipt`() {
        rejection(SeoulSubwayTrainRejection.TIMESTAMP_FUTURE, "recptnDt" to "20:10:11")
        rejection(SeoulSubwayTrainRejection.TIMESTAMP_STALE, "recptnDt" to "20:09:40")
        assertEquals(SeoulSubwayTrainStatus.AVAILABLE, parse(at = Instant.parse("2026-10-12T11:10:34.999Z")).status)
        assertEquals(SeoulSubwayTrainStatus.NO_USABLE_OBSERVATIONS, parse(at = Instant.parse("2026-10-12T11:10:35Z")).status)
        assertEquals(parse().observations.single().validUntil,
            parse(at = now.plusSeconds(10)).observations.single().validUntil)
    }

    @Test fun `midnight does not map yesterday clock to today`() {
        val data = body(listOf(row("lastRecptnDt" to "20261011", "recptnDt" to "23:59:55")))
        assertEquals(Instant.parse("2026-10-11T14:59:55Z"), parse(data, Instant.parse("2026-10-11T15:00:05Z")).observations.single().sourceUpdatedAt)
        assertEquals(SeoulSubwayTrainStatus.NO_USABLE_OBSERVATIONS, parse(data).status)
    }

    @Test fun `line identity both ID and canonical request name must match`() {
        rejection(SeoulSubwayTrainRejection.LINE_MISMATCH, "subwayId" to "1004")
        rejection(SeoulSubwayTrainRejection.LINE_MISMATCH, "subwayNm" to "4호선")
        rejection(SeoulSubwayTrainRejection.LINE_MISMATCH, "subwayNm" to "2")
    }

    @Test fun `bounded provider train and station identities remain opaque strings`() {
        for (id in listOf(null, "0", "000000", "123456789", "train-123", " 123"))
            rejection(SeoulSubwayTrainRejection.TRAIN_ID_INVALID, "trainNo" to id)
        for (id in listOf(null, "0", "station-name", "123456789012345678901"))
            rejection(SeoulSubwayTrainRejection.STATION_ID_INVALID, "statnTid" to id)
        rejection(SeoulSubwayTrainRejection.STATION_ID_INVALID, "statnId" to null)
    }

    @Test fun `direction state service and last train codes never guess`() {
        rejection(SeoulSubwayTrainRejection.DIRECTION_INVALID, "updnLine" to "상행")
        rejection(SeoulSubwayTrainRejection.STATE_INVALID, "trainSttus" to "99")
        rejection(SeoulSubwayTrainRejection.SERVICE_INVALID, "directAt" to "2")
        rejection(SeoulSubwayTrainRejection.LAST_TRAIN_INVALID, "lstcarAt" to "false")
        assertEquals(7, parse(body(listOf(row("directAt" to "7", "lstcarAt" to "1")))).observations.single().service)
        assertTrue(parse(body(listOf(row("lstcarAt" to "1")))).observations.single().lastTrain)
    }

    @Test fun `duplicate train conflicts remove every row rather than selecting newest`() {
        val result = parse(body(listOf(row(), row("recptnDt" to "20:09:00"))))
        assertEquals(0, result.observationCount)
        assertEquals(mapOf(SeoulSubwayTrainRejection.DUPLICATE_TRAIN to 2), result.rejectionCounts)
    }

    @Test fun `partial page cannot establish unique train candidates`() {
        assertEquals(SeoulSubwayTrainStatus.INCOMPLETE_PAGE, parse(body(total = 501)).status)
        assertEquals(0, parse(body(total = 501)).observationCount)
        for (total in listOf(-1, "1", 0, 1.5, 100001)) assertFailsWith<IllegalArgumentException> { parse(body(total = total)) }
        assertFailsWith<IllegalArgumentException> {
            parse("""{"list_total_count":2,"errorMessage":{"code":"INFO-000","total":1},"realtimePositionList":[]} """.toByteArray())
        }
    }

    @Test fun `explicit no data differs from missing or contradictory envelope`() {
        assertEquals(SeoulSubwayTrainStatus.NO_TRAINS, parse(body(emptyList(), "INFO-200")).status)
        assertEquals(SeoulSubwayTrainStatus.NO_TRAINS, parse(body(emptyList())).status)
        assertFailsWith<IllegalArgumentException> { parse(body(code = "INFO-200")) }
        for (value in listOf("{}", "[]", "{\"errorMessage\":{}}", "{\"errorMessage\":{\"code\":\"INFO-000\"}}")) assertFails { parse(value.toByteArray()) }
    }

    @Test fun `malformed duplicate trailing excessive depth size and UTF8 have sanitized failures`() {
        val bad = listOf("PRIVATE-BODY", "{\"errorMessage\":{},\"errorMessage\":{}}", "{} {}",
            "[".repeat(20) + "0" + "]".repeat(20))
        for (data in bad.map { it.toByteArray() } + listOf(byteArrayOf(0xC3.toByte(), 0x28), ByteArray(1_048_577))) {
            val failure = assertFailsWith<IllegalArgumentException> { parse(data) }
            assertFalse(failure.toString().contains("PRIVATE-BODY"))
            assertNull(failure.cause)
        }
    }

    @Test fun `application error cannot expose arbitrary provider message or code`() {
        val failure = assertFailsWith<TransitProviderApplicationException> { parse(body(code = "PRIVATE-KEY-MARKER")) }
        assertEquals("UNKNOWN", failure.code)
        assertFalse(failure.toString().contains("PRIVATE-KEY-MARKER"))
        assertNull(failure.cause)
    }

    @Test fun `query window is bounded and line catalog has unique provider identifiers`() {
        assertFailsWith<IllegalArgumentException> { SeoulSubwayTrainQuery(query.line, 0) }
        assertFailsWith<IllegalArgumentException> { SeoulSubwayTrainQuery(query.line, 501) }
        assertEquals(16, SeoulSubwayObservationLine.entries.map { it.providerId }.distinct().size)
    }

    private fun client(key: String, origin: String, metrics: TransitProviderWireMetrics? = null,
        limiter: TransitProviderWireRateLimiter = TransitProviderWireRateLimiter()) = SeoulTransitArrivalClient(
        commonApiKey = "", subwayApiKey = key, busApiKey = "", subwayBaseUrl = origin,
        busBaseUrl = "http://127.0.0.1:1", wireMetrics = metrics, wireRateLimiter = limiter,
    )

    @Test fun `loopback HTTP end to end uses encoded official line query with fresh source and bounded wire metrics`() {
        val calls = AtomicInteger()
        val registry = SimpleMeterRegistry()
        val sourceClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))
        val day = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneId.of("Asia/Seoul"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                val parts = exchange.requestURI.rawPath.split('/').drop(1).map { URLDecoder.decode(it, Charsets.UTF_8) }
                assertEquals(listOf("key+/=", "json", "realtimePosition", "0", "500", "2호선"), parts)
                val instant = Instant.now().minusSeconds(1)
                val output = body(listOf(row("lastRecptnDt" to day.format(instant), "recptnDt" to sourceClock.format(instant))))
                exchange.sendResponseHeaders(200, output.size.toLong()); exchange.responseBody.use { it.write(output) }
            }
            start()
        }
        try {
            val result = client("key+/=", "http://127.0.0.1:${server.address.port}", TransitProviderWireMetrics(registry))
                .use { it.getSubwayTrainObservations(query) }
            assertEquals(SeoulSubwayTrainStatus.AVAILABLE, result.status)
            // Synthetic service order: HTTP -> strict parser -> station reference matching.
            // This fixture is not an official catalog crosswalk or a passenger ride.
            val matched = com.noLate.journey.application.JourneySubwayTrainStationMatcher.match(
                com.noLate.journey.application.JourneySubwayTrainStationMatcher.Pattern(query.line, 0,
                    "1002000211", 0, listOf("1002000231", "1002000232", "1002000211"), 0, 2), result, Instant.now())
            assertEquals("TRAIN_REFERENCES_ONLY", matched.status)
            assertEquals(1, matched.trains.single().callsAfterReferenceToAlighting)
            assertFalse(matched.passengerBoardingConfirmed); assertFalse(matched.savedRouteCrosswalkVerified)
            assertEquals(1, calls.get())
            assertEquals(1L, registry.get("nolate.eta.transit.provider.wire.duration").tags("provider", "seoul_subway",
                "operation", "vehicle_position", "outcome", "success").timer().count())
        } finally { server.stop(0) }
    }

    @Test fun `redirect never forwards key or retries to trap`() {
        val calls = AtomicInteger(); val traps = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/trap") { exchange -> traps.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
            createContext("/") { exchange ->
                calls.incrementAndGet(); exchange.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/trap")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            }; start()
        }
        try {
            val failure = assertFailsWith<org.springframework.web.client.RestClientResponseException> {
                client("PRIVATE-KEY-MARKER", "http://127.0.0.1:${server.address.port}").use { it.getSubwayTrainObservations(query) }
            }
            assertEquals(302, failure.statusCode.value()); assertEquals(1, calls.get()); assertEquals(0, traps.get())
            assertFalse(failure.toString().contains("PRIVATE-KEY-MARKER")); assertNull(failure.cause)
        } finally { server.stop(0) }
    }

    @Test fun `credential missing makes no request and network error retains no key or cause`() {
        assertEquals(SeoulSubwayTrainStatus.CREDENTIAL_UNAVAILABLE,
            client("", "http://127.0.0.1:1").use { it.getSubwayTrainObservations(query) }.status)
        val failure = assertFails { client("PRIVATE-KEY-MARKER", "http://127.0.0.1:1").use { it.getSubwayTrainObservations(query) } }
        assertFalse(failure.toString().contains("PRIVATE-KEY-MARKER")); assertNull(failure.cause)
        assertFailsWith<IllegalStateException> { client("synthetic", "http://swopenAPI.seoul.go.kr/api/subway") }
    }

    @Test fun `train position shares existing subway wire quota and has no automatic retry`() {
        val calls = AtomicInteger(); val registry = SimpleMeterRegistry()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet(); val output = body(emptyList(), "INFO-200")
                exchange.sendResponseHeaders(200, output.size.toLong()); exchange.responseBody.use { it.write(output) }
            }; start()
        }
        val ticker = com.noLate.eta.resilience.EtaMonotonicTicker { 1_000_000L }
        try {
            client("synthetic", "http://127.0.0.1:${server.address.port}", TransitProviderWireMetrics(registry),
                TransitProviderWireRateLimiter(seoulSubwayPerSecond = 1, ticker = ticker)).use { client ->
                assertEquals(SeoulSubwayTrainStatus.NO_TRAINS, client.getSubwayTrainObservations(query).status)
                assertFailsWith<com.noLate.eta.resilience.EtaProviderBulkheadRejectedException> { client.getSubwayTrainObservations(query) }
            }
            assertEquals(1, calls.get())
            assertEquals(1L, registry.get("nolate.eta.transit.provider.wire.duration").tags("provider", "seoul_subway",
                "operation", "vehicle_position", "outcome", "local_rate_limited").timer().count())
        } finally { server.stop(0) }
    }

    @Test fun `application HTTP quota and malformed responses keep bounded separate outcomes`() {
        for ((status, output, outcome) in listOf(Triple(200, body(code = "INFO-100"), "application_error"),
            Triple(429, "PRIVATE-BODY-MARKER".toByteArray(), "rate_limited"),
            Triple(200, "PRIVATE-BODY-MARKER".toByteArray(), "error"))) {
            val calls = AtomicInteger(); val registry = SimpleMeterRegistry()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    calls.incrementAndGet(); exchange.sendResponseHeaders(status, output.size.toLong())
                    exchange.responseBody.use { it.write(output) }
                }; start()
            }
            try {
                val failure = assertFails {
                    client("PRIVATE-KEY-MARKER", "http://127.0.0.1:${server.address.port}", TransitProviderWireMetrics(registry))
                        .use { it.getSubwayTrainObservations(query) }
                }
                assertEquals(1, calls.get()); assertNull(failure.cause)
                assertFalse(failure.toString().contains("PRIVATE-BODY-MARKER")); assertFalse(failure.toString().contains("PRIVATE-KEY-MARKER"))
                assertEquals(1L, registry.get("nolate.eta.transit.provider.wire.duration").tags("provider", "seoul_subway",
                    "operation", "vehicle_position", "outcome", outcome).timer().count())
            } finally { server.stop(0) }
        }
    }
}
