package com.noLate.transit.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.SeoulBusVehicleObservationStatus
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.domain.SeoulBusVehicleRejection
import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import com.noLate.eta.resilience.EtaMonotonicTicker
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeoulBusVehicleObservationTest {
    @Test fun `source age aggregates include stale and future clocks without granting freshness`() {
        val stale = SeoulBusVehicleObservationParser.parse(xml(item("20261007124956")), query, now)
        assertEquals(1, stale.sourceAgeSummary?.count)
        assertEquals(15_000L, stale.sourceAgeSummary?.minAgeMillis)
        assertEquals(15_000L, stale.sourceAgeSummary?.maxAgeMillis)
        assertEquals(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS, stale.status)
        val future = SeoulBusVehicleObservationParser.parse(xml(item("20261007125012")), query, now)
        assertEquals(-1_000L, future.sourceAgeSummary?.minAgeMillis)
        assertEquals(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS, future.status)
        assertEquals(mapOf(SeoulBusVehicleRejection.TIMESTAMP_FUTURE to 1), future.rejectionCounts)
        val malformed = SeoulBusVehicleObservationParser.parse(xml(item("unsupported")), query, now)
        assertEquals(null, malformed.sourceAgeSummary)
        val mixed = SeoulBusVehicleObservationParser.parse(
            xml(item() + item("20261007124956").replace("100000123", "100000124")), query, now,
        )
        assertEquals(2, mixed.sourceAgeSummary?.count)
        assertEquals(4_000L, mixed.sourceAgeSummary?.minAgeMillis)
        assertEquals(15_000L, mixed.sourceAgeSummary?.maxAgeMillis)
        val duplicate = SeoulBusVehicleObservationParser.parse(xml(item() + item()), query, now)
        assertEquals(null, duplicate.sourceAgeSummary)
    }
    @Test fun `vehicle redirect is rejected without forwarding credentials or retrying`() {
        val trapCalls = AtomicInteger()
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/buspos/getBusPosByRouteSt") { exchange ->
                calls.incrementAndGet()
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/trap?serviceKey=synthetic")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/trap") { exchange ->
                trapCalls.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            val failure = assertFailsWith<org.springframework.web.client.RestClientResponseException> {
                client("synthetic", "http://127.0.0.1:${server.address.port}").getBusVehicleObservations(query)
            }
            assertEquals(302, failure.statusCode.value())
            assertEquals(1, calls.get())
            assertEquals(0, trapCalls.get())
        } finally { server.stop(0) }
    }
    private val query = SeoulBusVehicleQuery("100100447", 44, 48)
    private val now = Instant.parse("2026-10-07T03:50:11Z")
    private val source = "20261007125007"

    @Test fun `official WGS84 vehicle coordinates are internal and never certify a passenger ride`() {
        val row = item().replace("</itemList>", "<tmX>126.972926</tmX><tmY>37.555411</tmY></itemList>")
        val batch = SeoulBusVehicleObservationParser.parse(xml(row), query, now)
        val coordinate = batch.observations.single().wgs84Coordinate!!
        assertEquals(37.555411, coordinate.latitude)
        assertEquals(126.972926, coordinate.longitude)
        assertEquals(1, batch.wgs84CoordinateCount)
        assertFalse(batch.geographicCoordinatesVerified)
        assertFalse(batch.passengerVehicleAssociation)
        val json = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(batch)
        listOf("126.972926", "37.555411", "wgs84Coordinate\"", "tmX", "tmY").forEach { assertFalse(json.contains(it)) }
        assertFalse(coordinate.toString().contains("37.555411"))
    }

    @Test fun `missing WGS84 is explicit and GRS80 never substitutes for it`() {
        val batch = SeoulBusVehicleObservationParser.parse(xml(item()), query, now)
        assertEquals(SeoulBusVehicleObservationStatus.AVAILABLE, batch.status)
        assertEquals(null, batch.observations.single().wgs84Coordinate)
        assertEquals(0, batch.wgs84CoordinateCount)
    }

    @Test fun `malformed duplicate projected swapped and half-missing WGS84 fields reject the row`() {
        listOf("<tmX>126.97</tmX>", "<tmX>126.97</tmX><tmY>NaN</tmY>",
            "<tmX>198000</tmX><tmY>450000</tmY>", "<tmX>37.55</tmX><tmY>126.97</tmY>",
            "<tmX>126.97</tmX><tmX>126.97</tmX><tmY>37.55</tmY>",
            "<wrapper><tmX>126.97</tmX></wrapper><tmY>37.55</tmY>",
            "<tmX><value>126.97</value></tmX><tmY>37.55</tmY>").forEach { fields ->
            val batch = SeoulBusVehicleObservationParser.parse(xml(item().replace("</itemList>", "$fields</itemList>")), query, now)
            assertEquals(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS, batch.status)
            assertEquals(mapOf(SeoulBusVehicleRejection.COORDINATE_INVALID to 1), batch.rejectionCounts)
        }
    }

    @Test fun `rejection reasons are bounded counts without raw timestamp or vehicle identity`() {
        val unsupported = SeoulBusVehicleObservationParser.parse(xml(item("2026-10-07 12:50:07")), query, now)
        assertEquals(mapOf(SeoulBusVehicleRejection.TIMESTAMP_UNSUPPORTED to 1), unsupported.rejectionCounts)
        val stale = SeoulBusVehicleObservationParser.parse(xml(item("20261007124956")), query, now)
        assertEquals(mapOf(SeoulBusVehicleRejection.TIMESTAMP_STALE to 1), stale.rejectionCounts)
        val duplicate = SeoulBusVehicleObservationParser.parse(xml(item() + item("20261007124956")), query, now)
        assertEquals(mapOf(SeoulBusVehicleRejection.DUPLICATE_VEHICLE to 2), duplicate.rejectionCounts)
        assertEquals(duplicate.rejectedCount, duplicate.rejectionCounts.values.sum())
        val json = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(duplicate)
        assertFalse(json.contains("100000123"))
        assertFalse(json.contains("20261007124956"))
    }

    private fun item(time: String = source) = """
        <itemList><vehId>100000123</vehId><routeId>100100447</routeId>
        <sectionId>100000456</sectionId><sectOrd>73</sectOrd><sectDist>120</sectDist>
        <stopFlag>0</stopFlag><lastStnId>101000007</lastStnId><dataTm>$time</dataTm>
        <plainNo>NEVER-RETAIN-PLATE</plainNo><posX>192321</posX><posY>449300</posY></itemList>
    """.trimIndent()
    private fun xml(items: String, header: String = "<headerCd>0</headerCd>") =
        "<ServiceResult><msgHeader>$header</msgHeader><msgBody>$items</msgBody></ServiceResult>".toByteArray()

    @Test fun `query requires Seoul route and bounded ordered stop occurrences`() {
        listOf("7016", "100100447x", "000000000", " 100100447").forEach {
            assertFailsWith<IllegalArgumentException> { SeoulBusVehicleQuery(it, 44, 48) }
        }
        listOf(0 to 48, 48 to 44, 1 to 101, 999 to 1000).forEach { (first, last) ->
            assertFailsWith<IllegalArgumentException> { SeoulBusVehicleQuery(query.routeId, first, last) }
        }
    }

    @Test fun `fresh section evidence is not converted to stop position or boarding evidence`() {
        val batch = SeoulBusVehicleObservationParser.parse(xml(item()), query, now)
        assertEquals(SeoulBusVehicleObservationStatus.AVAILABLE, batch.status)
        val observation = batch.observations.single()
        assertEquals(73, observation.roadSectionOrder) // Intentionally outside request stop window.
        assertEquals(Instant.parse("2026-10-07T03:50:07Z"), observation.sourceUpdatedAt)
        assertEquals(Instant.parse("2026-10-07T03:50:22Z"), observation.validUntil)
        assertEquals(now, observation.receivedAt)
        assertFalse(batch.passengerVehicleAssociation)
        assertFalse(batch.stopOrderCrosswalkVerified)
        assertFalse(batch.geographicCoordinatesVerified)
        val json = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(batch)
        listOf("100000123", "NEVER-RETAIN-PLATE", "posX", "posY", "vehicleId").forEach {
            assertFalse(json.contains(it))
        }
        assertFalse(batch.toString().contains("100000123"))
    }

    @Test fun `receipt does not freshen stale future malformed or invalid calendar timestamps`() {
        listOf("20261007124956", "20261007124659", "20261007125012", "20260230125007",
            "", "2026-10-07 12:50:07", "20261007125007Z").forEach { time ->
            val batch = SeoulBusVehicleObservationParser.parse(xml(item(time)), query, now)
            assertEquals(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS, batch.status, time)
            assertEquals(1, batch.rejectedCount)
        }
    }

    @Test fun `exact TTL boundary expires and freshest instant remains valid`() {
        assertEquals(SeoulBusVehicleObservationStatus.AVAILABLE,
            SeoulBusVehicleObservationParser.parse(xml(item("20261007125011")), query, now).status)
        assertEquals(SeoulBusVehicleObservationStatus.AVAILABLE,
            SeoulBusVehicleObservationParser.parse(xml(item("20261007124957")), query, now).status)
    }

    @Test fun `wrong route ambiguous fields invalid identity and section information fail closed`() {
        listOf(
            item().replace("100100447", "100100448"),
            item().replace("<routeId>100100447</routeId>", "<routeId></routeId>"),
            item().replace("<routeId>100100447</routeId>", "<routeId>100100447</routeId><routeId>100100447</routeId>"),
            item().replace("100000123", "0"), item().replace("100000456", "bad"),
            item().replace("<sectOrd>73", "<sectOrd>0"),
            item().replace("<sectDist>120", "<sectDist>-1"),
            item().replace("<stopFlag>0", "<stopFlag>2"),
            item().replace("101000007", "bad"),
            item().replace("<dataTm>$source</dataTm>", "<dataTm>$source</dataTm><dataTm>$source</dataTm>"),
        ).forEach { bad ->
            assertEquals(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS,
                SeoulBusVehicleObservationParser.parse(xml(bad), query, now).status)
        }
    }

    @Test fun `optional absent route uses request binding without certifying direction`() {
        val batch = SeoulBusVehicleObservationParser.parse(
            xml(item().replace("<routeId>100100447</routeId>", "")), query, now)
        assertEquals(query.routeId, batch.observations.single().routeId)
        assertFalse(batch.stopOrderCrosswalkVerified)
    }

    @Test fun `duplicates including stale conflicting rows remove all candidates for that vehicle`() {
        listOf(item() + item(), item() + item("20261007124659")).forEach {
            val batch = SeoulBusVehicleObservationParser.parse(xml(it), query, now)
            assertEquals(SeoulBusVehicleObservationStatus.NO_USABLE_OBSERVATIONS, batch.status)
            assertEquals(2, batch.rejectedCount)
        }
    }

    @Test fun `explicit success with no vehicles differs from malformed provider errors`() {
        assertEquals(SeoulBusVehicleObservationStatus.NO_VEHICLES,
            SeoulBusVehicleObservationParser.parse(xml(""), query, now).status)
        listOf("", "<headerCd>22</headerCd>", "<headerCd>0</headerCd><headerCd>0</headerCd>").forEach {
            assertFailsWith<TransitProviderApplicationException> {
                SeoulBusVehicleObservationParser.parse(xml(item(), it), query, now)
            }
        }
    }

    @Test fun `oversized payload and vehicle list rejected before accepting observations`() {
        assertFailsWith<IllegalArgumentException> {
            SeoulBusVehicleObservationParser.parse(ByteArray(1_048_577), query, now)
        }
        assertFailsWith<IllegalArgumentException> {
            SeoulBusVehicleObservationParser.parse(xml(item().repeat(501)), query, now)
        }
    }

    @Test fun `DOCTYPE external entity is rejected`() {
        assertFails {
            SeoulBusVehicleObservationParser.parse(
                "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///DO-NOT-READ'>]><foo>&xxe;</foo>".toByteArray(), query, now)
        }
    }

    @Test fun `missing or nested provider envelope cannot masquerade as successful empty traffic`() {
        listOf("<foo><headerCd>0</headerCd></foo>",
            "<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader></ServiceResult>",
            "<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><wrapper><msgBody/></wrapper></ServiceResult>",
            "<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody><wrapper>${item()}</wrapper></msgBody></ServiceResult>"
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                SeoulBusVehicleObservationParser.parse(bad.toByteArray(), query, now)
            }
        }
    }

    @Test fun `missing credential does not perform HTTP or imply empty road`() {
        val client = client("", "http://127.0.0.1:1")
        assertEquals(SeoulBusVehicleObservationStatus.CREDENTIAL_UNAVAILABLE,
            client.getBusVehicleObservations(query).status)
    }

    @Test fun `network and malformed XML exceptions never retain credential or payload fragments`() {
        val failure = assertFails {
            client("sensitive-key-marker", "http://127.0.0.1:1").getBusVehicleObservations(query)
        }
        assertFalse(failure.toString().contains("sensitive-key-marker"))
        assertEquals(null, failure.cause)
        val malformed = assertFails {
            SeoulBusVehicleObservationParser.parse("<sensitive-payload-marker".toByteArray(), query, now)
        }
        assertFalse(malformed.toString().contains("sensitive-payload-marker"))
        assertEquals(null, malformed.cause)
    }

    @Test fun `remote plaintext credentials remain forbidden`() {
        assertFailsWith<IllegalStateException> { client("synthetic-key", "http://ws.bus.go.kr/api/rest") }
    }

    @Test fun `HTTP contract uses official operation exact once encoding bounded window and wire metric`() {
        val registry = SimpleMeterRegistry()
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/buspos/getBusPosByRouteSt") { exchange ->
                calls.incrementAndGet()
                val params = exchange.requestURI.rawQuery.split("&").associate {
                    val (name, value) = it.split("=", limit = 2)
                    name to URLDecoder.decode(value, Charsets.UTF_8)
                }
                assertEquals("key+/=", params["serviceKey"])
                assertEquals("100100447", params["busRouteId"])
                assertEquals("44", params["startOrd"])
                assertEquals("48", params["endOrd"])
                val time = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
                    .format(DateTimeFormatter.ofPattern("uuuuMMddHHmmss"))
                val bytes = xml(item(time))
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = client("key%2B%2F%3D", "http://127.0.0.1:${server.address.port}", registry)
            assertEquals(SeoulBusVehicleObservationStatus.AVAILABLE, client.getBusVehicleObservations(query).status)
            assertEquals(1, calls.get())
            assertEquals(1L, registry.get("nolate.eta.transit.provider.wire.duration")
                .tag("provider", "seoul_bus").tag("operation", "vehicle_position")
                .tag("outcome", "success").timer().count())
        } finally { server.stop(0) }
    }

    private fun client(key: String, url: String, registry: SimpleMeterRegistry? = null) =
        SeoulTransitArrivalClient("", "", key, "http://127.0.0.1:1", url,
            wireMetrics = registry?.let(::TransitProviderWireMetrics))

    @Test fun `provider quota error is not reported as no vehicles and is never blindly retried`() {
        withResponse(200, xml("", "<headerCd>22</headerCd>")) { url, calls ->
            assertFailsWith<TransitProviderApplicationException> {
                client("synthetic", url).getBusVehicleObservations(query)
            }
            assertEquals(1, calls.get())
        }
    }

    @Test fun `HTTP errors and oversized network body cannot become fresh evidence`() {
        withResponse(429, "sensitive-provider-message".toByteArray()) { url, calls ->
            val failure = assertFailsWith<org.springframework.web.client.RestClientResponseException> {
                client("synthetic", url).getBusVehicleObservations(query)
            }
            assertEquals(429, failure.statusCode.value())
            assertFalse(failure.toString().contains("sensitive-provider-message"))
            assertEquals(1, calls.get())
        }
        withResponse(200, ByteArray(1_048_577)) { url, calls ->
            assertFailsWith<IllegalArgumentException> { client("synthetic", url).getBusVehicleObservations(query) }
            assertEquals(1, calls.get())
        }
    }

    @Test fun `vehicle queries share arrival wire quota before HTTP dispatch`() {
        withResponse(200, xml("")) { url, calls ->
            val client = SeoulTransitArrivalClient("", "", "synthetic", "http://127.0.0.1:1", url,
                wireRateLimiter = TransitProviderWireRateLimiter(seoulBusPerSecond = 1, ticker = EtaMonotonicTicker { 0L }))
            client.getBusVehicleObservations(query)
            assertFailsWith<EtaProviderBulkheadRejectedException> {
                client.getBusVehicleObservations(query)
            }
            assertEquals(1, calls.get())
        }
    }

    private fun withResponse(status: Int, bytes: ByteArray, block: (String, AtomicInteger) -> Unit) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try { block("http://127.0.0.1:${server.address.port}", calls) }
        finally { server.stop(0) }
    }
}
