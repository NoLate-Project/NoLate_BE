package com.noLate.transit.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0 characterization of the existing display-oriented arrival contract.
 * These fixtures prove normalization, not live provider coverage or a rider's boarding state.
 * Direction fallback and receipt-only freshness must not become navigation evidence implicitly.
 */
class JourneyTransitCapabilityContractTest {
    @Test fun `bus provider route and each incoming slot vehicle IDs preserved without zero or malformed IDs`() {
        val rows = seoul.parseBusArrivals("""<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody><itemList>
          <rtNm>synthetic</rtNm><busRouteId>999999</busRouteId><vehId1>999997</vehId1><vehId2>999996</vehId2>
          <arrmsg1>2분 후</arrmsg1><traTime1>120</traTime1><arrmsg2>3분 후</arrmsg2><traTime2>180</traTime2>
          </itemList></msgBody></ServiceResult>""", null, 2)
        assertEquals(listOf("999997", "999996"), rows.map { it.vehicleId })
        assertEquals(listOf("999999", "999999"), rows.map { it.providerRouteId })
        val invalid = seoul.parseBusArrivals("""<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody><itemList>
          <rtNm>synthetic</rtNm><busRouteId>0</busRouteId><vehId1>bad</vehId1><arrmsg1>2분 후</arrmsg1>
          </itemList></msgBody></ServiceResult>""", null, 1).single()
        assertNull(invalid.providerRouteId); assertNull(invalid.vehicleId)
    }
    private val mapper = jacksonObjectMapper()
    private val receiptTime = Instant.parse("2026-10-04T03:00:30Z")
    private val seoul = SeoulTransitArrivalClient(
        commonApiKey = "",
        subwayApiKey = "",
        busApiKey = "",
        subwayBaseUrl = "http://127.0.0.1",
        busBaseUrl = "http://127.0.0.1",
    )

    @Test
    fun `subway two minute prediction stays anchored to provider time not response receipt`() {
        val arrival = subwayArrival(sourceTime = "2026-10-04 12:00:00")

        assertEquals(120, arrival.waitSeconds)
        assertEquals(2, arrival.waitMinutes)
        assertEquals("2026-10-04T03:00:00Z", arrival.sourceUpdatedAt)
        assertEquals("2026-10-04T03:02:00Z", arrival.expectedAt)
        assertEquals(receiptTime.toString(), arrival.observedAt)
        assertEquals(TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, arrival.freshnessEvidence)
        assertEquals("내선", arrival.direction)
        assertEquals("성수", arrival.destinationName)
        // A 30-second-old source prediction has 90 seconds remaining at receipt, not 120.
        assertEquals(90, Instant.parse(arrival.expectedAt).epochSecond - receiptTime.epochSecond)
    }

    @Test
    fun `stale subway source is not rejuvenated by a fresh HTTP receipt`() {
        val arrival = subwayArrival(sourceTime = "2026-10-04 11:50:00")

        assertEquals("2026-10-04T02:52:00Z", arrival.expectedAt)
        assertTrue(Instant.parse(arrival.expectedAt).isBefore(receiptTime))
        assertEquals(receiptTime.toString(), arrival.observedAt)
        // This enum identifies timestamp provenance; it is not an assertion that the feed is fresh.
        assertEquals(TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, arrival.freshnessEvidence)
    }

    @Test
    fun `absent and malformed subway source timestamps remain receipt only`() {
        listOf(null, "not-a-provider-timestamp").forEach { sourceTime ->
            val arrival = subwayArrival(sourceTime)

            assertNull(arrival.sourceUpdatedAt)
            assertEquals(TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY, arrival.freshnessEvidence)
            assertEquals("2026-10-04T03:02:30Z", arrival.expectedAt)
            assertTrue(arrival.realtime)
            // realtime=true and an expectedAt are not sufficient evidence for actionable speech.
        }
    }

    @Test
    fun `subway display fallback can return the opposite direction and must be rechecked by navigation`() {
        val arrival = subwayArrival(
            sourceTime = "2026-10-04 12:00:00",
            direction = "외선",
            directionCode = "UP",
        )

        assertEquals("외선", arrival.direction)
        // Existing display compatibility deliberately keeps unmatched results instead of returning [];
        // a navigation adapter must require an explicit positive direction/route match.
    }

    @Test
    fun `bus source time survives normalization but incoming slot metadata is not onboard progress`() {
        val arrival = seoul.parseBusArrivals(
            response = """
                <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody><itemList>
                  <rtNm>402</rtNm><stNm>검증 출발 정류소</stNm><adirection>검증 방면</adirection>
                  <mkTm>2026-10-04 12:00:00</mkTm><arrmsg1>2분 후</arrmsg1><traTime1>120</traTime1>
                  <vehId1>synthetic-vehicle-1</vehId1><staOrd1>7</staOrd1>
                </itemList></msgBody></ServiceResult>
            """.trimIndent(),
            routeName = "402",
            limit = 1,
        ).single()

        assertEquals("2026-10-04T03:00:00Z", arrival.sourceUpdatedAt)
        assertEquals("2026-10-04T03:02:00Z", arrival.expectedAt)
        assertEquals(TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, arrival.freshnessEvidence)
        assertNull(arrival.remainingStops)
        assertEquals("검증 방면", arrival.direction)
    }

    @Test
    fun `bus missing source time does not gain provider freshness from receipt`() {
        val arrival = seoul.parseBusArrivals(
            response = """
                <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody><itemList>
                  <rtNm>402</rtNm><stNm>검증 출발 정류소</stNm><arrmsg1>2분 후</arrmsg1><traTime1>120</traTime1>
                </itemList></msgBody></ServiceResult>
            """.trimIndent(),
            routeName = "402",
            limit = 1,
        ).single()

        assertNotNull(arrival.observedAt)
        assertNotNull(arrival.expectedAt)
        assertNull(arrival.sourceUpdatedAt)
        assertEquals(TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY, arrival.freshnessEvidence)
    }

    @Test
    fun `TAGO stops away means incoming vehicle to queried stop and direction currently contains route type`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList") { exchange ->
                val body = """
                    {"response":{"header":{"resultCode":"00","resultMsg":"OK"},"body":{"items":{"item":{
                      "nodeid":"SYNTHETIC_BOARDING_STOP","nodenm":"검증 출발 정류소",
                      "routeid":"SYNTHETIC_ROUTE","routeno":"30","routetp":"마을버스",
                      "arrtime":120,"arrprevstationcnt":1,"vehicletp":"저상버스"
                    }}}}}
                """.trimIndent().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val client = TagoTransitArrivalClient(
                commonApiKey = "",
                busApiKey = "synthetic-loopback-test-key",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                cityCodeCandidatesValue = "25",
            )
            val arrival = client.getBusArrivals(
                arsId = null,
                nodeId = "SYNTHETIC_BOARDING_STOP",
                cityCode = "25",
                stationName = null,
                routeName = "30",
                limit = 1,
            ).single()

            assertEquals("SYNTHETIC_BOARDING_STOP", arrival.nodeId)
            assertEquals(1, arrival.remainingStops)
            assertEquals("2분 후 · 1정류장 전", arrival.arrivalMessage)
            assertEquals("마을버스", arrival.direction)
            assertEquals(120, arrival.waitSeconds)
            assertNull(arrival.sourceUpdatedAt)
            assertEquals(TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY, arrival.freshnessEvidence)
            // This fixture has no rider location, alighting stop, or tracked vehicle identity.
            // remainingStops=1 must never directly trigger an ALIGHT_ONE_STOP event.
        } finally {
            server.stop(0)
        }
    }

    private fun subwayArrival(
        sourceTime: String?,
        direction: String = "내선",
        directionCode: String = "UP",
    ): TransitArrivalDto {
        val node = mapper.createObjectNode().apply {
            put("subwayId", "1002")
            put("statnNm", "검증역")
            put("updnLine", direction)
            put("bstatnNm", "성수")
            put("barvlDt", "120")
            put("arvlCd", "99")
            put("arvlMsg2", "2분 후")
            sourceTime?.let { put("recptnDt", it) }
        }
        val response = mapper.createObjectNode().apply {
            putArray("realtimeArrivalList").add(node)
        }
        return seoul.parseSubwayArrivals(
            response = response,
            lineName = "2호선",
            directionName = null,
            directionCode = directionCode,
            limit = 1,
            observedAt = receiptTime,
        ).single()
    }
}
