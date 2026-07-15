package com.noLate.transit.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeoulBusStationLookupTest {
    @Test
    fun `서울 ARS와 TMAP 내부 정류장 ID를 구분한다`() {
        assertTrue(isSeoulBusArsId("02005"))
        assertFalse(isSeoulBusArsId("757384"))
        assertFalse(isSeoulBusArsId("DJB8001793"))
    }

    @Test
    fun `승강장 괄호를 제거한 정류장 검색어도 함께 만든다`() {
        assertEquals(
            listOf("서울역버스환승센터(6번승강장)(중)", "서울역버스환승센터"),
            seoulBusStationSearchTerms(" 서울역버스환승센터(6번승강장)(중) "),
        )
    }
}
