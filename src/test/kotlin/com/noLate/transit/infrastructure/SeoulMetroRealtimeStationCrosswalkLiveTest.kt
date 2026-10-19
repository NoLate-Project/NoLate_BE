package com.noLate.transit.infrastructure

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Opt-in provider check; never logs the credential, redirect, or raw provider response. */
class SeoulMetroRealtimeStationCrosswalkLiveTest {
    @Test fun `official ALL arrivals corroborate line qualified station IDs`() {
        val key = System.getenv("SEOUL_SUBWAY_API_KEY")
        val pathKey = System.getenv("DATA_GO_KR_SUBWAY_PATH_API_KEY")
        assumeTrue(System.getenv("RUN_SEOUL_REALTIME_CROSSWALK_LIVE") == "1" &&
            key?.matches(Regex("[A-Za-z0-9]{8,128}")) == true && !pathKey.isNullOrBlank(),
            "live provider check not enabled")

        val crosswalk = SeoulMetroRealtimeArrivalBatchClient(requireNotNull(key), true).use { it.fetch() }
        fun station(code: String, line: String, name: String) =
            SeoulMetroShortestPath.Station(code, line, name)
        fun path(from: SeoulMetroShortestPath.Station, to: SeoulMetroShortestPath.Station) =
            SeoulMetroShortestPath(listOf(SeoulMetroShortestPath.Section(from, to, null, null,
                if (from.line == to.line) "N" else "Y", "N")),
                if (from.line == to.line) 0 else 1)

        val adjacent = assertNotNull(crosswalk.correlate(path(
            station("0222", "2호선", "강남"), station("0221", "2호선", "역삼"))))
        assertEquals(listOf("1002000222", "1002000221"), adjacent.map { it.realtimeStationId })
        val transfer = assertNotNull(crosswalk.correlate(path(
            station("0226", "2호선", "사당"), station("0433", "4호선", "사당"))))
        assertEquals(listOf("1002000226", "1004000433"), transfer.map { it.realtimeStationId })
        val fullService = SeoulMetroShortestPathClient(requireNotNull(pathKey)).use { client ->
            client.fetch(SeoulMetroShortestPathClient.Query("0222", "0211",
                LocalDateTime.now(ZoneId.of("Asia/Seoul")), SeoulMetroShortestPathClient.StationValueType.CODE))
        }
        assertEquals("0211", fullService.sections.last().arrival.code)
        assertEquals(listOf("1002000222", "1002000221"),
            assertNotNull(crosswalk.correlate(fullService)).take(2).map { it.realtimeStationId })
        assertFalse((adjacent + transfer).any { it.passengerBoardingConfirmed })
    }

}
