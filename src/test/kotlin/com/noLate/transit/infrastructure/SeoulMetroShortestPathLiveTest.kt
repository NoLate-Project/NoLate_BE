package com.noLate.transit.infrastructure

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Opt-in provider contract check. Never logs the key, request URL or raw response. */
class SeoulMetroShortestPathLiveTest {
    @Test fun `official endpoint returns a usable ordered segment`() {
        val key = System.getenv("DATA_GO_KR_SUBWAY_PATH_API_KEY")
        assumeTrue(!key.isNullOrBlank(), "live provider credential not configured")
        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        SeoulMetroShortestPathClient(requireNotNull(key)).use { client ->
            val path = client.fetch(SeoulMetroShortestPathClient.Query("강남", "역삼", now))
            assertEquals("0222", path.sections.first().departure.code)
            assertEquals("0221", path.sections.last().arrival.code)
            assertFalse(path.trainStationCodeCompatibilityVerified)
            assertFalse(path.passengerBoardingConfirmed)
        }
    }
}
