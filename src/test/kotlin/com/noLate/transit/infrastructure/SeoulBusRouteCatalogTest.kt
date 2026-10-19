package com.noLate.transit.infrastructure

import com.noLate.eta.resilience.EtaMonotonicTicker
import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SeoulBusRouteCatalogTest {
    private val route = "100100447"
    private fun xml(items: String, code: String = "0") =
        "<ServiceResult><msgHeader><headerCd>$code</headerCd></msgHeader><msgBody>$items</msgBody></ServiceResult>".toByteArray()
    private fun stop(seq: Int = 44, station: String = "101000007") = """
        <itemList><busRouteId>$route</busRouteId><seq>$seq</seq><station>$station</station>
        <stationNm>서울역버스환승센터</stationNm><direction>상명대</direction><section>100000456</section>
        <stationNo>02007</stationNo><arsId>02007</arsId><gpsX>126.972926</gpsX><gpsY>37.555411</gpsY>
        <posX>198000</posX><posY>450000</posY></itemList>
    """.trimIndent()
    private fun point(no: Int) = "<itemList><no>$no</no><gpsX>126.972926</gpsX><gpsY>37.555411</gpsY></itemList>"

    @Test fun `official stop occurrences preserve namespace section and WGS84 only`() {
        val result = SeoulBusRouteCatalogParser.stops(xml(stop() + stop(45)), route)
        assertEquals(listOf(44, 45), result.map { it.sequence })
        assertEquals(listOf("101000007", "101000007"), result.map { it.stationId }) // Loop occurrences are distinct.
        assertEquals("02007", result.first().arsId)
        assertEquals("100000456", result.first().sectionId)
        assertEquals(37.555411, result.first().latitude)
        assertEquals(126.972926, result.first().longitude)
        assertNull(SeoulBusRouteCatalogParser.stops(xml(stop().replace("<section>100000456", "<section>0")), route).single().sectionId)
        assertEquals("02007", SeoulBusRouteCatalogParser.stops(xml(stop().replace("<arsId>02007</arsId>", "")), route).single().arsId)
    }
    @Test fun `duplicates gaps reversed and mismatched routes cannot admit a route`() {
        listOf(stop() + stop(), stop() + stop(46), stop(45) + stop(),
            stop().replace(route, "100100448")).forEach { bad ->
            assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(bad), route) }
        }
        assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(stop()), "7016") }
    }
    @Test fun `empty optional ars uses official station number but never invents stop identity`() {
        val emptyArs = stop().replace("<arsId>02007</arsId>", "<arsId> </arsId>")
        assertEquals("02007", SeoulBusRouteCatalogParser.stops(xml(emptyArs), route).single().arsId)
        val missingFallback = emptyArs.replace("<stationNo>02007</stationNo>", "<stationNo/>")
        assertNull(SeoulBusRouteCatalogParser.stops(xml(missingFallback), route).single().arsId)
        assertNull(SeoulBusRouteCatalogParser.stops(xml(emptyArs.replace("<stationNo>02007", "<stationNo>00000")), route).single().arsId)
        assertNull(SeoulBusRouteCatalogParser.stops(xml(emptyArs.replace("<stationNo>02007", "<stationNo>0")), route).single().arsId)
        listOf(
            emptyArs.replace("<stationNo>02007", "<stationNo>0200"),
            emptyArs.replace("<arsId> </arsId>", "<arsId/><arsId/>"),
            emptyArs.replace("<arsId> </arsId>", "<arsId><value>02007</value></arsId>"),
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(bad), route) } }
    }
    @Test fun `invalid or projected coordinates and conflicting stop identity are rejected`() {
        listOf(
            stop().replace("<gpsX>126.972926", "<gpsX>198000"),
            stop().replace("<gpsY>37.555411", "<gpsY>NaN"),
            stop().replace("<gpsY>37.555411", "<gpsY>Infinity"),
            stop().replace("<gpsY>37.555411", "<gpsY>0"),
            stop().replace("<arsId>02007", "<arsId>02008"),
            stop().replace("02007", "0200"),
            stop().replace("<station>101000007", "<station>02007"),
            stop().replace("<section>100000456", "<section>45"),
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(bad), route) } }
    }
    @Test fun `unnumbered occurrences retain sequence and cannot inherit neighboring numbers`() {
        val middle = stop(45).replace("<arsId>02007</arsId>", "<arsId/>")
            .replace("<stationNo>02007</stationNo>", "<stationNo>00000</stationNo>")
        val rows = SeoulBusRouteCatalogParser.stops(xml(stop() + middle + stop(46)), route)
        assertEquals(listOf(44, 45, 46), rows.map { it.sequence })
        assertEquals(listOf("02007", null, "02007"), rows.map { it.arsId })
        assertEquals("101000007", rows[1].stationId)
    }
    @Test fun `invalid public number has bounded shape diagnostics not raw values or padding`() {
        listOf("2007" to SeoulBusCatalogNumberShape.NUMERIC_TOO_SHORT,
            "101000007" to SeoulBusCatalogNumberShape.NUMERIC_TOO_LONG,
            "private-marker" to SeoulBusCatalogNumberShape.NON_NUMERIC).forEach { (value, shape) ->
            val failure = assertFailsWith<SeoulBusCatalogFieldException> {
                SeoulBusRouteCatalogParser.stops(xml(stop().replace("<arsId>02007", "<arsId>$value")), route)
            }
            assertEquals(shape, failure.numberShape)
            assertEquals(SeoulBusCatalogField.ARS, failure.field)
            assertEquals(1, failure.rowOrdinal)
            assertFalse(failure.toString().contains(value)); assertNull(failure.cause)
        }
    }
    @Test fun `only explicit diagnostic catalog retains malformed numbers without admitting a mapping`() {
        val bad = stop(45).replace("<stationNo>02007", "<stationNo>private-marker")
        assertFailsWith<SeoulBusCatalogFieldException> { SeoulBusRouteCatalogParser.stops(xml(stop() + bad), route) }
        val rows = SeoulBusRouteCatalogParser.stops(xml(stop() + bad), route, retainInvalidPublicNumbers = true)
        assertEquals(listOf(44, 45), rows.map { it.sequence })
        assertEquals("02007", rows[0].arsId)
        assertNull(rows[1].arsId) // Even valid arsId cannot mask malformed stationNo.
        assertTrue(rows[1].publicStopNumberInvalid)
        assertFalse(rows.toString().contains("private-marker"))
        // Diagnostic mode does not tolerate malformed identity, coordinates, or duplicate fields.
        for (invalid in listOf(bad.replace("<station>101000007", "<station>02007"),
            bad.replace("<gpsX>126.972926", "<gpsX>198000"),
            bad.replace("<arsId>02007</arsId>", "<arsId/><arsId/>"))) {
            assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(invalid), route, true) }
        }
    }
    @Test fun `duplicate nested missing and control-character fields fail closed`() {
        listOf(
            stop().replace("<seq>44</seq>", "<seq>44</seq><seq>44</seq>"),
            stop().replace("<seq>44</seq>", "<seq><value>44</value></seq>"),
            stop().replace("<seq>44</seq>", "<wrapper><seq>44</seq></wrapper>"),
            stop().replace("<direction>상명대</direction>", ""),
            stop().replace("상명대", "&#10;상명&#10;대"),
            stop().replace("상명대", "x".repeat(129)),
        ).forEach { bad -> assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(bad), route) } }
    }
    @Test fun `shape keeps its own ordinal namespace and needs contiguous points`() {
        assertEquals(listOf(0, 1), SeoulBusRouteCatalogParser.path(xml(point(0) + point(1))).map { it.order })
        assertEquals(listOf(44, 45), SeoulBusRouteCatalogParser.path(xml(point(44) + point(45))).map { it.order })
        listOf(point(1), point(1) + point(1), point(1) + point(3), point(2) + point(1)).forEach {
            assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.path(xml(it)) }
        }
        assertTrue(SeoulBusRouteCatalogParser.path(xml("")).isEmpty())
    }
    @Test fun `field diagnostics distinguish absent empty duplicate and nested without retaining values`() {
        val mutations = listOf(
            stop().replace("<direction>상명대</direction>", "") to SeoulBusCatalogFieldIssue.ABSENT,
            stop().replace("<direction>상명대</direction>", "<direction> </direction>") to SeoulBusCatalogFieldIssue.EMPTY,
            stop().replace("<direction>상명대</direction>", "<direction>private-marker</direction><direction>private-marker</direction>") to SeoulBusCatalogFieldIssue.DUPLICATE,
            stop().replace("<direction>상명대</direction>", "<direction><value>private-marker</value></direction>") to SeoulBusCatalogFieldIssue.NESTED,
        )
        mutations.forEach { (bad, issue) ->
            val failure = assertFailsWith<SeoulBusCatalogFieldException> {
                SeoulBusRouteCatalogParser.stops(xml(stop() + bad.replace("<seq>44", "<seq>45")), route)
            }
            assertEquals(SeoulBusCatalogField.DIRECTION, failure.field)
            assertEquals(2, failure.rowOrdinal) // Not seq=45.
            assertEquals(issue, failure.issue)
            assertFalse(failure.toString().contains("private-marker")); assertNull(failure.cause)
        }
        assertEquals(SeoulBusCatalogField.UNKNOWN, SeoulBusCatalogField.schemaName("private-marker"))
    }
    @Test fun `provider errors and ambiguous envelopes are not empty successful catalogs`() {
        assertFailsWith<TransitProviderApplicationException> { SeoulBusRouteCatalogParser.path(xml("", "22")) }
        listOf("<foo/>", "<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader></ServiceResult>",
            String(xml("<wrapper>${point(0)}</wrapper>")),
            String(xml("")).replace("<msgBody>", "<wrapper><msgBody>").replace("</msgBody>", "</msgBody></wrapper>"),
        ).forEach { assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.path(it.toByteArray()) } }
    }
    @Test fun `XXE malformed and oversized payloads retain no raw input in errors`() {
        listOf("<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///DO-NOT-READ'>]><foo>&xxe;</foo>".toByteArray(),
            "<sensitive-payload-marker".toByteArray(), ByteArray(SeoulBusRouteCatalogParser.MAX_RESPONSE_BYTES + 1)).forEach {
            val failure = assertFails { SeoulBusRouteCatalogParser.path(it) }
            assertFalse(failure.toString().contains("sensitive-payload-marker"))
            assertNull(failure.cause)
        }
        assertFailsWith<IllegalArgumentException> { SeoulBusRouteCatalogParser.stops(xml(stop().repeat(1000)), route) }
    }
    private fun client(key: String, url: String, registry: SimpleMeterRegistry? = null,
        limiter: TransitProviderWireRateLimiter = TransitProviderWireRateLimiter()) =
        SeoulTransitArrivalClient("", "", key, "http://127.0.0.1:1", url,
            wireMetrics = registry?.let(::TransitProviderWireMetrics), wireRateLimiter = limiter)

    @Test fun `missing key and invalid route never dispatch HTTP`() {
        assertTrue(client("", "http://127.0.0.1:1").getBusRouteStops(route).isEmpty())
        assertTrue(client("", "http://127.0.0.1:1").getBusRoutePath(route).isEmpty())
        assertFailsWith<IllegalArgumentException> { client("synthetic", "http://127.0.0.1:1").getBusRoutePath("7016") }
    }
    @Test fun `official endpoints dispatch once with encoded credential and bounded metrics`() {
        val calls = AtomicInteger()
        val registry = SimpleMeterRegistry()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet()
                val params = exchange.requestURI.rawQuery.split("&").associate {
                    val (name, value) = it.split("=", limit = 2)
                    name to URLDecoder.decode(value, Charsets.UTF_8)
                }
                assertEquals(mapOf("serviceKey" to "key+/=", "busRouteId" to route), params)
                val bytes = when (exchange.requestURI.path) {
                    "/busRouteInfo/getStaionByRoute" -> xml(stop())
                    "/busRouteInfo/getRoutePath" -> xml(point(0) + point(1))
                    else -> error("Unexpected endpoint")
                }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = client("key%2B%2F%3D", "http://127.0.0.1:${server.address.port}", registry)
            assertEquals(1, client.getBusRouteStops(route).size)
            assertEquals(2, client.getBusRoutePath(route).size)
            assertEquals(2, calls.get())
            listOf("station_lookup", "route_geometry").forEach { operation ->
                assertEquals(1L, registry.get("nolate.eta.transit.provider.wire.duration")
                    .tag("provider", "seoul_bus").tag("operation", operation).tag("outcome", "success").timer().count())
            }
        } finally { server.stop(0) }
    }
    @Test fun `redirect never forwards key and HTTP error does not retain body`() {
        val calls = AtomicInteger()
        val traps = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/busRouteInfo/getRoutePath") { exchange ->
                calls.incrementAndGet()
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${address.port}/trap")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            }
            createContext("/trap") { exchange -> traps.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
            createContext("/busRouteInfo/getStaionByRoute") { exchange ->
                calls.incrementAndGet()
                val bytes = "sensitive-provider-message".toByteArray()
                exchange.sendResponseHeaders(429, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val client = client("synthetic-secret", "http://127.0.0.1:${server.address.port}")
            val redirect = assertFailsWith<org.springframework.web.client.RestClientResponseException> { client.getBusRoutePath(route) }
            assertEquals(302, redirect.statusCode.value())
            val rejected = assertFailsWith<org.springframework.web.client.RestClientResponseException> { client.getBusRouteStops(route) }
            assertFalse(rejected.toString().contains("sensitive-provider-message"))
            assertEquals(2, calls.get()); assertEquals(0, traps.get())
        } finally { server.stop(0) }
    }
    @Test fun `catalog shares provider quota and transport errors redact credentials`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                calls.incrementAndGet(); val bytes = xml("")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }; start()
        }
        try {
            val client = client("synthetic", "http://127.0.0.1:${server.address.port}", limiter =
                TransitProviderWireRateLimiter(seoulBusPerSecond = 1, ticker = EtaMonotonicTicker { 0L }))
            client.getBusRouteStops(route)
            assertFailsWith<EtaProviderBulkheadRejectedException> { client.getBusRoutePath(route) }
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
        val failure = assertFails { client("sensitive-secret", "http://127.0.0.1:1").getBusRoutePath(route) }
        assertFalse(failure.toString().contains("sensitive-secret")); assertNull(failure.cause)
    }
}
