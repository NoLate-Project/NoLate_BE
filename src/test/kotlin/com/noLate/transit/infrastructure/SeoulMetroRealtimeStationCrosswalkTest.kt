package com.noLate.transit.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.*

class SeoulMetroRealtimeStationCrosswalkTest {
    private val path = SeoulMetroShortestPathParser.parse("""{
      "header":{"resultCode":"00"},"body":{"trsitNmtm":0,"paths":[
        {"dptreStn":{"stnCd":"0222","lineNm":"2호선","stnNm":"강남"},
         "arvlStn":{"stnCd":"0221","lineNm":"2호선","stnNm":"역삼"},
         "tmnlStnCd":"0211","upbdnbSe":"외선","trsitYn":"N","etrnYn":"N"}
      ]}
    }""".trimIndent().toByteArray())
    private fun batch(other: String = "") = """{
      "errorMessage":{"status":200,"code":"INFO-000"},"realtimeArrivalList":[
        {"subwayId":"1002","statnId":"1002000222","statnNm":"강남"},
        {"subwayId":"1002","statnId":"1002000221","statnNm":"역삼"}$other
      ]
    }""".trimIndent().toByteArray()

    @Test fun `exact official line name and code suffix produce only ephemeral candidates`() {
        val candidates = assertNotNull(SeoulMetroRealtimeStationCrosswalk.parse(batch()).correlate(path))
        assertEquals(listOf("1002000222", "1002000221"), candidates.map { it.realtimeStationId })
        assertTrue(candidates.none { it.passengerBoardingConfirmed })
        assertFalse(candidates.first().toString().contains("1002000222"))
    }

    @Test fun `collision and mismatch fail closed`() {
        assertNull(SeoulMetroRealtimeStationCrosswalk.parse(batch(
            ", {\"subwayId\":\"1002\",\"statnId\":\"1002000999\",\"statnNm\":\"역삼\"}"))
            .correlate(path))
        assertNull(SeoulMetroRealtimeStationCrosswalk.parse(batch().toString(Charsets.UTF_8)
            .replace("1002000221", "1002000999").toByteArray()).correlate(path))
        assertNull(SeoulMetroRealtimeStationCrosswalk.parse(batch().toString(Charsets.UTF_8)
            .replace("\"1002\",\"statnId\":\"1002000221\"", "\"1004\",\"statnId\":\"1002000221\"")
            .toByteArray()).correlate(path))
    }

    @Test fun `provider error and duplicate fields cannot create a mapping`() {
        assertFails { SeoulMetroRealtimeStationCrosswalk.parse(batch().toString(Charsets.UTF_8)
            .replace("INFO-000", "INFO-100").toByteArray()) }
        assertFails { SeoulMetroRealtimeStationCrosswalk.parse(batch().toString(Charsets.UTF_8)
            .replace("\"statnId\":\"1002000221\"", "\"statnId\":\"1002000221\",\"statnId\":\"1002000221\"")
            .toByteArray()) }
    }
}
