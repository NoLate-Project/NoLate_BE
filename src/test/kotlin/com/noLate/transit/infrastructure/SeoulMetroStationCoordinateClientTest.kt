package com.noLate.transit.infrastructure

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.Charset
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SeoulMetroStationCoordinateClientTest {
    private val header = "연번,호선,고유역번호(외부역코드),역명,위도,경도,작성일자,작성기준일"
    private fun csv(vararg rows: String) = (header + "\n" + rows.joinToString("\n"))
        .toByteArray(Charset.forName("MS949"))

    @Test fun `official line code name and saved coordinate must all agree`() {
        val catalog = SeoulMetroStationCoordinateCatalog.parse(csv(
            "1,2,222,강남,37.497958,127.027539,1982-12-30,2025-08-14",
            "2,2,221,역삼,37.500658,127.036430,1982-12-30,2025-08-14"))
        assertTrue(catalog.corroborates(SeoulMetroShortestPath.Station("0222", "2호선", "강남"),
            37.497958, 127.027539))
        assertFalse(catalog.corroborates(SeoulMetroShortestPath.Station("0222", "2호선", "역삼"),
            37.497958, 127.027539))
        assertFalse(catalog.corroborates(SeoulMetroShortestPath.Station("0222", "3호선", "강남"),
            37.497958, 127.027539))
        assertFalse(catalog.corroborates(SeoulMetroShortestPath.Station("0222", "2호선", "강남"),
            37.60, 127.027539))
    }

    @Test fun `duplicate or malformed coordinate file is rejected`() {
        assertFailsWith<IllegalArgumentException> { SeoulMetroStationCoordinateCatalog.parse(csv(
            "1,2,222,강남,37.497958,127.027539,1982-12-30,2025-08-14",
            "2,2,222,강남,37.497958,127.027539,1982-12-30,2025-08-14")) }
        assertFailsWith<IllegalArgumentException> { SeoulMetroStationCoordinateCatalog.parse(csv(
            "1,2,222,강남,91,127.027539,1982-12-30,2025-08-14")) }
    }

    @Test fun `coordinate reader caches source`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/station-coordinates") { exchange ->
            requests++
            val data = csv("1,2,222,강남,37.497958,127.027539,1982-12-30,2025-08-14")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            SeoulMetroStationCoordinateClient(URI.create("http://127.0.0.1:${server.address.port}/station-coordinates")).use { client ->
                assertTrue(client.fetch().corroborates(SeoulMetroShortestPath.Station("0222", "2호선", "강남"),
                    37.497958, 127.027539))
                client.fetch()
                assertTrue(requests == 1)
            }
        } finally { server.stop(0) }
    }

    @Test fun `coordinate reader never follows a redirect`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/station-coordinates") { exchange ->
            exchange.responseHeaders.set("Location", "https://example.invalid/other.csv")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            SeoulMetroStationCoordinateClient(URI.create("http://127.0.0.1:${server.address.port}/station-coordinates")).use { client ->
                assertFailsWith<IllegalStateException> { client.fetch() }
            }
        } finally { server.stop(0) }
    }
}
