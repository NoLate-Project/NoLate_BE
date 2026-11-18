package com.noLate.transit.infrastructure

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Opt-in provider contract check. Never logs the key, request URL or raw response. */
class SeoulMetroShortestPathLiveTest {
    @Test fun `official daytime endpoint returns a usable ordered segment`() {
        val key = System.getenv("DATA_GO_KR_SUBWAY_PATH_API_KEY")
        assumeTrue(!key.isNullOrBlank(), "live provider credential not configured")
        // Verify the station/path contract during service hours. A successful
        // empty path at 04:00 is not evidence of a broken provider contract.
        // This query makes no claim about current trains or passenger boarding.
        val serviceTime = LocalDate.now(ZoneId.of("Asia/Seoul")).atTime(12, 0)
        SeoulMetroShortestPathClient(requireNotNull(key)).use { client ->
            val path = client.fetch(SeoulMetroShortestPathClient.Query("강남", "역삼", serviceTime))
            assertEquals("0222", path.sections.first().departure.code)
            assertEquals("0221", path.sections.last().arrival.code)
            assertFalse(path.trainStationCodeCompatibilityVerified)
            assertFalse(path.passengerBoardingConfirmed)
        }
    }
}
