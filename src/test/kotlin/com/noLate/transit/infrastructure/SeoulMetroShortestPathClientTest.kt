package com.noLate.transit.infrastructure

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SeoulMetroShortestPathClientTest {
    // Sanitized subset of the credentialed 강남→역삼 response on 2026-10-13.
    private val sample = """{
      "header":{"resultCode":"00","resultMsg":"성공"},
      "body":{"trsitNmtm":0,"paths":[
        {"dptreStn":{"stnCd":"0222","lineNm":"2호선","stnNm":"강남"},
         "arvlStn":{"stnCd":"0221","lineNm":"2호선","stnNm":"역삼"},
         "tmnlStnCd":"0211","upbdnbSe":"외선","trsitYn":"N","etrnYn":"N"}
      ]}
    }""".trimIndent()

    @Test fun `published path fields are captured but never promoted to a verified train mapping`() {
        val result = SeoulMetroShortestPathParser.parse(sample.toByteArray())
        assertEquals("0222", result.sections.single().departure.code)
        assertEquals("0221", result.sections.single().arrival.code)
        assertEquals("0211", result.sections.single().terminalCode)
        assertEquals(0, result.reportedTransferCount)
        assertFalse(result.trainStationCodeCompatibilityVerified)
        assertFalse(result.passengerBoardingConfirmed)
        assertFalse(result.toString().contains("0222"))
    }

    @Test fun `actual transfer shape retains distinct station codes for the same interchange name`() {
        val transfer = """{
          "header":{"resultCode":"00","resultMsg":"성공"},
          "body":{"trsitNmtm":1,"paths":[
            {"dptreStn":{"stnCd":"0225","lineNm":"2호선","stnNm":"방배"},
             "arvlStn":{"stnCd":"0226","lineNm":"2호선","stnNm":"사당"},
             "tmnlStnCd":"0211","upbdnbSe":"내선","trsitYn":"N","etrnYn":"N"},
            {"dptreStn":{"stnCd":"0226","lineNm":"2호선","stnNm":"사당"},
             "arvlStn":{"stnCd":"0433","lineNm":"4호선","stnNm":"사당"},
             "tmnlStnCd":null,"upbdnbSe":null,"trsitYn":"Y","etrnYn":"N"},
            {"dptreStn":{"stnCd":"0433","lineNm":"4호선","stnNm":"사당"},
             "arvlStn":{"stnCd":"0432","lineNm":"4호선","stnNm":"총신대입구"},
             "tmnlStnCd":"0405","upbdnbSe":"상행","trsitYn":"N","etrnYn":"N"}
          ]}
        }""".trimIndent()
        val result = SeoulMetroShortestPathParser.parse(transfer.toByteArray())
        assertEquals(1, result.reportedTransferCount)
        assertEquals(listOf("0225", "0226", "0433"), result.sections.map { it.departure.code })
        assertEquals("Y", result.sections[1].transfer)
        assertEquals("2호선", result.sections[1].departure.line)
        assertEquals("4호선", result.sections[1].arrival.line)
    }

    @Test fun `malformed conflicting and non-success provider bodies fail closed without leaking content`() {
        for (value in listOf(sample.replace("\"resultCode\":\"00\"", "\"resultCode\":\"20\""),
            sample.replace("\"stnCd\":\"0222\"", "\"stnCd\":\"강남\""),
            sample.replace("\"stnCd\":\"0222\"", "\"stnCd\":\"0000\""),
            sample.replace("\"trsitYn\":\"N\"", "\"trsitYn\":\"Y\""),
            sample.replace("\"header\":", "\"header\":{},\"header\":"),
            sample + " PRIVATE-BODY", "{\"error\":\"PRIVATE-BODY\"}")) {
            val error = assertFails { SeoulMetroShortestPathParser.parse(value.toByteArray()) }
            assertFalse(error.toString().contains("PRIVATE-BODY"))
            assertNull(error.cause)
        }
    }

    @Test fun `credentialed query uses only the documented exact endpoint and bounded inputs`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/getShtrmPath2") { exchange ->
                requests.incrementAndGet()
                assertEquals("GET", exchange.requestMethod)
                val values = exchange.requestURI.rawQuery.split('&').associate { part ->
                    part.split('=',limit=2).let { it[0] to URLDecoder.decode(it[1], Charsets.UTF_8) }
                }
                assertEquals("PRIVATE-KEY", values["serviceKey"])
                assertEquals("강남", values["dptreStn"])
                assertEquals("역삼", values["arvlStn"])
                assertEquals("2026-10-13 17:00:00", values["searchDt"])
                assertEquals("N", values["schInclYn"])
                assertEquals("name", values["stationValueType"])
                val bytes = sample.toByteArray()
                exchange.sendResponseHeaders(200,bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            SeoulMetroShortestPathClient("PRIVATE%2DKEY",URI.create("http://127.0.0.1:${server.address.port}"))
                .use { client -> assertEquals(1,client.fetch(SeoulMetroShortestPathClient.Query(
                    "강남","역삼",LocalDateTime.of(2026,10,13,17,0))).sections.size) }
            assertEquals(1,requests.get())
        } finally { server.stop(0) }
        assertFailsWith<IllegalArgumentException> {
            SeoulMetroShortestPathClient("PRIVATE-KEY",URI.create("http://example.com"))
        }
        assertFailsWith<IllegalArgumentException> {
            SeoulMetroShortestPathClient.Query("강남", "강남", LocalDateTime.now())
        }
        SeoulMetroShortestPathClient.Query("0222", "0221", LocalDateTime.now(),
            SeoulMetroShortestPathClient.StationValueType.CODE)
    }

    @Test fun `redirect and provider errors do not forward or print the key`() {
        val redirects = AtomicInteger()
        val traps = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            createContext("/trap") { exchange -> traps.incrementAndGet(); exchange.sendResponseHeaders(204,-1); exchange.close() }
            createContext("/getShtrmPath2") { exchange ->
                redirects.incrementAndGet()
                exchange.responseHeaders.add("Location","http://127.0.0.1:${address.port}/trap")
                exchange.sendResponseHeaders(302,-1); exchange.close()
            }
            start()
        }
        try {
            val error = assertFails {
                SeoulMetroShortestPathClient("PRIVATE-KEY",URI.create("http://127.0.0.1:${server.address.port}"))
                    .use { it.fetch(SeoulMetroShortestPathClient.Query("강남","역삼",LocalDateTime.of(2026,10,13,17,0))) }
            }
            assertEquals(1,redirects.get()); assertEquals(0,traps.get())
            assertFalse(error.toString().contains("PRIVATE-KEY")); assertNull(error.cause)
        } finally { server.stop(0) }
    }
}
