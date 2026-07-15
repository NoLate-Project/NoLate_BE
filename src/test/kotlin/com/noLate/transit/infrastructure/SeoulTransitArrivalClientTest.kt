package com.noLate.transit.infrastructure

import com.noLate.transit.domain.TransitArrivalDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SeoulTransitArrivalClientTest {
    @Test
    fun `서울 버스 차량 유형 코드를 사용자 표시값으로 변환한다`() {
        assertEquals("일반", seoulBusVehicleType("0"))
        assertEquals("저상", seoulBusVehicleType("1"))
        assertEquals("굴절", seoulBusVehicleType("2"))
        assertEquals(null, seoulBusVehicleType(null))
        assertEquals(null, seoulBusVehicleType("9"))
    }

    @Test
    fun `서울 버스 XML에서 저상 막차 행선지와 도착 상태를 보존한다`() {
        val client = createClient()
        val arrivals = client.parseBusArrivals(
            response = """
                <ServiceResult>
                  <msgBody>
                    <itemList>
                      <rtNm>402</rtNm>
                      <stNm>서울역버스환승센터</stNm>
                      <adirection>후암약수터</adirection>
                      <arrmsg1>곧 도착</arrmsg1>
                      <traTime1>0</traTime1>
                      <isArrive1>1</isArrive1>
                      <isLast1>1</isLast1>
                      <busType1>1</busType1>
                      <stationNm1>장지공영차고지</stationNm1>
                    </itemList>
                  </msgBody>
                </ServiceResult>
            """.trimIndent(),
            routeName = "402번",
            limit = 2,
        )

        assertEquals(1, arrivals.size)
        assertEquals("장지공영차고지", arrivals.single().destinationName)
        assertEquals("저상", arrivals.single().vehicleType)
        assertEquals(true, arrivals.single().lowFloor)
        assertEquals(true, arrivals.single().lastTrain)
        assertEquals("ARRIVED", arrivals.single().arrivalStatus.name)
    }

    @Test
    fun `ODsay 하행 코드로 반대 방향 지하철 도착정보를 제외한다`() {
        val client = createClient()
        val arrivals = listOf(
            subwayArrival(direction = "상행", destinationName = "당고개"),
            subwayArrival(direction = "하행", destinationName = "오이도"),
        )

        val filtered = client.filterSubwayDirection(
            arrivals = arrivals,
            directionName = "사당 방면",
            directionCode = "DOWN",
        )

        assertEquals(listOf("오이도"), filtered.map { it.destinationName })
    }

    @Test
    fun `방향 매칭이 불가능하면 빈 값 때문에 결과를 버리지 않는다`() {
        val client = createClient()
        val arrivals = listOf(
            subwayArrival(direction = null, destinationName = null),
            subwayArrival(direction = "상행", destinationName = "당고개"),
        )

        val filtered = client.filterSubwayDirection(
            arrivals = arrivals,
            directionName = "사당 방면",
            directionCode = null,
        )

        assertEquals(arrivals, filtered)
    }

    private fun createClient() = SeoulTransitArrivalClient(
        commonApiKey = "",
        subwayApiKey = "",
        busApiKey = "",
        subwayBaseUrl = "http://localhost",
        busBaseUrl = "http://localhost",
    )

    private fun subwayArrival(
        direction: String?,
        destinationName: String?,
    ) = TransitArrivalDto(
        provider = "seoul-openapi",
        kind = "SUBWAY",
        direction = direction,
        destinationName = destinationName,
    )
}
