package com.noLate.transit.infrastructure

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.Instant
import java.time.LocalDate
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitServiceClass
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

    @Test fun `date-specific route admits only the same train along the whole ordered ODsay ride`() {
        val timed = sample.replace(
            "\"trsitNmtm\":0",
            "\"trsitNmtm\":0,\"schInclYn\":\"Y\"",
        ).replace(
            "\"etrnYn\":\"N\"",
            """"etrnYn":"N","tmnlStnNm":"성수","nonstopYn":"N","trainno":"2031","trainDptreTm":"17:05:30","trainArvlTm":"17:07:30"""",
        )
        val query = TransitTimetableQuery(SeoulSubwayLine.LINE_2, "강남", "역삼", "역삼",
            TransitServiceClass.LOCAL, Instant.parse("2026-11-16T08:05:00Z"), serviceDate = LocalDate.parse("2026-11-16"))
        val receipt = Instant.parse("2026-11-15T07:00:00Z")
        val path = SeoulMetroShortestPathParser.parse(timed.toByteArray(), withTimetable = true)
        val ride = assertNotNull(bindRide(query, listOf("강남", "역삼"), path, receipt))
        assertEquals("2031", ride.trainNumber)
        assertEquals("DOWN", ride.directionCode)
        assertEquals(Instant.parse("2026-11-16T08:05:30Z"), ride.boardingAt)
        assertEquals(Instant.parse("2026-11-16T08:07:30Z"), ride.arrivalAt)
        assertNull(bindRide(query, listOf("강남", "선릉"), path, receipt))
        assertNull(bindRide(query.copy(notBefore = ride.boardingAt.plusSeconds(1)), listOf("강남", "역삼"), path, receipt))
        assertNull(bindRide(query.copy(directionCode = "UP"), listOf("강남", "역삼"), path, receipt))
        val first = path.sections.single()
        val second = first.copy(
            departure = first.arrival, arrival = SeoulMetroShortestPath.Station("0220", "2호선", "선릉"),
            scheduled = first.scheduled!!.copy(departureTime = java.time.LocalTime.of(17, 8),
                arrivalTime = java.time.LocalTime.of(17, 10)),
        )
        val longer = query.copy(toName = "선릉")
        val stops = listOf("강남", "역삼", "선릉")
        assertNotNull(bindRide(longer, stops, SeoulMetroShortestPath(listOf(first, second), 0), receipt))
        val switched = second.copy(scheduled = second.scheduled!!.copy(trainNumber = "2033"))
        assertNull(bindRide(longer, stops, SeoulMetroShortestPath(listOf(first, switched), 0), receipt))
        assertFails { SeoulMetroShortestPathParser.parse(sample.toByteArray(), withTimetable = true) }
        assertFails { SeoulMetroShortestPathParser.parse(timed.replace("17:07:30", "24:01:30").toByteArray(), withTimetable = true) }
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

    @Test fun `the dated path keeps the original service day when the same train arrives after midnight`() {
        val timed = """{
          "header":{"resultCode":"00"},
          "body":{"schInclYn":"Y","trsitNmtm":0,"paths":[
            {"dptreStn":{"stnCd":"1757","lineNm":"4호선","stnNm":"중앙"},
             "arvlStn":{"stnCd":"1758","lineNm":"4호선","stnNm":"고잔"},
             "tmnlStnCd":"1763","upbdnbSe":"하행","trsitYn":"N","etrnYn":"N",
             "tmnlStnNm":"오이도","nonstopYn":"N","trainno":"K4709",
             "trainDptreTm":"23:59:00","trainArvlTm":"00:00:30"}
          ]}
        }""".trimIndent()
        val path = SeoulMetroShortestPathParser.parse(timed.toByteArray(), withTimetable = true)
        val date = LocalDate.parse("2026-11-16")
        val query = TransitTimetableQuery(SeoulSubwayLine.LINE_4, "중앙", "고잔", "고잔",
            TransitServiceClass.LOCAL, Instant.parse("2026-11-16T14:58:30Z"), serviceDate = date)
        val ride = assertNotNull(bindRide(query, listOf("중앙", "고잔"), path, query.notBefore))
        assertEquals(date, ride.serviceDate)
        assertEquals(Instant.parse("2026-11-16T14:59:00Z"), ride.boardingAt)
        assertEquals(Instant.parse("2026-11-16T15:00:30Z"), ride.arrivalAt)
        assertFails {
            SeoulMetroShortestPathParser.parse(timed.replace("00:00:30", "23:58:30").toByteArray(), withTimetable = true)
        }
        val implausiblyLong = SeoulMetroShortestPathParser.parse(timed.replace("23:59:00", "19:59:00").toByteArray(),
            withTimetable = true)
        assertNull(bindRide(query.copy(notBefore = query.notBefore.minusSeconds(5 * 60 * 60)),
            listOf("중앙", "고잔"), implausiblyLong, query.notBefore))
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
