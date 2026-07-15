package com.noLate.transit.application

import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TransitArrivalServiceTest {
    private val seoulClient = mock<SeoulTransitArrivalClient>()
    private val tagoClient = mock<TagoTransitArrivalClient>()
    private val service = TransitArrivalService(seoulClient, tagoClient)

    @Test
    fun `ARS가 없어도 정류장명으로 서울 버스 도착 정보를 조회한다`() {
        val expected = listOf(
            TransitArrivalDto(provider = "seoul-bus", kind = "BUS", routeName = "402"),
        )
        whenever(
            seoulClient.getBusArrivals(
                arsId = null,
                stationName = "서울역버스환승센터(6번승강장)(중)",
                routeName = "402",
                limit = 2,
            )
        ).thenReturn(expected)

        val result = service.getBusArrivals(
            arsId = null,
            routeName = "402",
            cityCode = null,
            nodeId = null,
            stationName = "서울역버스환승센터(6번승강장)(중)",
            limit = 2,
        )

        assertEquals(expected, result)
        verify(seoulClient).getBusArrivals(
            arsId = null,
            stationName = "서울역버스환승센터(6번승강장)(중)",
            routeName = "402",
            limit = 2,
        )
    }
}
