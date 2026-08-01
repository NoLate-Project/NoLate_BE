package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.transit.domain.TransitCityCodeNamespace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OdsayTransitJourneyMapperTest {
    private val objectMapper = jacksonObjectMapper()
    private val mapper = OdsayTransitJourneyMapper()
    private val fetchedAt = Instant.parse("2026-07-29T02:59:30Z")

    @Test
    fun `maasRP의 시간 대기 노선과 지역 정류장 식별자를 canonical 여정으로 보존한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": {
                      "pathType": "2",
                      "totalTime": "40",
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291240",
                      "rps": [
                        {
                          "trafficType": 3,
                          "duration": 5,
                          "startDateTime": "202607291200",
                          "endDateTime": "202607291205"
                        },
                        {
                          "trafficType": 2,
                          "duration": 20,
                          "waitingTime": 4,
                          "startDateTime": "202607291205",
                          "endDateTime": "202607291225",
                          "startID": 100,
                          "startLocalStationID": "SEOUL_NODE_1",
                          "startStationCityCode": 1000,
                          "startStationProviderCode": 1,
                          "startArsID": "02005",
                          "startName": "서울역버스환승센터",
                          "endID": 200,
                          "endLocalStationID": "SEOUL_NODE_2",
                          "endStationCityCode": 1000,
                          "endStationProviderCode": 1,
                          "endArsID": "22009",
                          "endName": "사당역",
                          "lane": {
                            "busNo": "402",
                            "busID": "12018",
                            "busLocalBlID": "100100057",
                            "busCityCode": 1000,
                            "busProviderCode": 1
                          }
                        },
                        {
                          "trafficType": 3,
                          "duration": 3,
                          "startDateTime": "202607291225",
                          "endDateTime": "202607291228"
                        },
                        {
                          "trafficType": 1,
                          "duration": 10,
                          "waitingTime": 2,
                          "startDateTime": "202607291228",
                          "endDateTime": "202607291238",
                          "startID": 226,
                          "startName": "사당",
                          "endID": 222,
                          "endName": "강남",
                          "way": "강남 방면",
                          "wayCode": 2,
                          "lane": [{
                            "name": "수도권 2호선",
                            "subwayCode": 2,
                            "subwayCityCode": 1000
                          }]
                        },
                        {
                          "trafficType": 3,
                          "duration": 2,
                          "startDateTime": "202607291238",
                          "endDateTime": "202607291240"
                        }
                      ]
                    }
                  }
                }
            """.trimIndent()
        )

        val journeys = mapper.map(response, request(), fetchedAt)

        assertEquals(1, journeys.size)
        val journey = journeys.single()
        assertEquals("odsay", journey.provider)
        assertEquals(Instant.parse("2026-07-29T03:00:00Z"), journey.departureAt)
        assertEquals(Instant.parse("2026-07-29T03:40:00Z"), journey.arrivalAt)
        assertEquals(40, journey.totalMinutes)
        assertEquals(fetchedAt, journey.fetchedAt)
        assertEquals(5, journey.legs.size)

        val bus = journey.legs[1]
        assertEquals(TransitLegMode.BUS, bus.mode)
        assertEquals(20, bus.durationMinutes)
        assertEquals(4, bus.waitingMinutes)
        assertEquals(Instant.parse("2026-07-29T03:05:00Z"), bus.scheduledDepartureAt)
        assertEquals(Instant.parse("2026-07-29T03:25:00Z"), bus.scheduledArrivalAt)
        assertEquals(TransitLegTimingBasis.TIMETABLE, bus.timingBasis)
        assertEquals(TransitCityCodeNamespace.ODSAY_CID, bus.from?.cityCodeNamespace)
        assertEquals("12018", bus.line?.providerRouteId)
        assertEquals("100100057", bus.line?.localRouteId)
        assertEquals("1000", bus.line?.cityCode)
        assertEquals("1", bus.line?.providerCode)
        assertEquals("402", bus.line?.name)
        assertEquals(
            setOf("ODsay:100", "ARS:02005", "CITY:1000:SEOUL_NODE_1"),
            bus.from?.stableIds(),
        )
        assertEquals(
            setOf("ODsay:200", "ARS:22009", "CITY:1000:SEOUL_NODE_2"),
            bus.to?.stableIds(),
        )

        val subway = journey.legs[3]
        assertEquals(TransitLegMode.SUBWAY, subway.mode)
        assertEquals("1000:2", subway.line?.providerRouteId)
        assertEquals("수도권 2호선", subway.line?.name)
        assertEquals(TransitServiceClass.LOCAL, subway.line?.serviceClass)
        assertEquals("강남 방면", subway.directionName)
        assertEquals("DOWN", subway.directionCode)
    }

    @Test
    fun `ODsay 급행 표기 계약으로 일반과 급행을 분류하고 상충 표기는 UNKNOWN으로 제한한다`() {
        val cases = listOf(
            "수도권 9호선(급행)" to TransitServiceClass.EXPRESS,
            "수도권 1호선 일반열차" to TransitServiceClass.LOCAL,
            "수도권 2호선" to TransitServiceClass.LOCAL,
            "수도권 9호선(급행)(일반)" to TransitServiceClass.UNKNOWN,
        )

        cases.forEach { (lineName, expectedClass) ->
            val response = objectMapper.readTree(
                """
                    {
                      "result": {
                        "paths": {
                          "pathType": 2,
                          "totalTime": 20,
                          "startDateTime": "202607291200",
                          "endDateTime": "202607291220",
                          "rps": {
                            "trafficType": 1,
                            "duration": 20,
                            "waitingTime": 3,
                            "startDateTime": "202607291200",
                            "endDateTime": "202607291220",
                            "startID": 226,
                            "startName": "서울역",
                            "endID": 222,
                            "endName": "사당",
                            "way": "사당 방면",
                            "wayCode": 2,
                            "lane": {
                              "name": "$lineName",
                              "subwayCode": 2,
                              "subwayCityCode": 1000
                            }
                          }
                        }
                      }
                    }
                """.trimIndent()
            )

            val serviceClass = mapper.map(response, request(), fetchedAt)
                .single()
                .legs
                .single()
                .line
                ?.serviceClass

            assertEquals(expectedClass, serviceClass, lineName)
        }
    }

    @Test
    fun `ODsay 지하철 노선명이 누락되면 serviceClass를 UNKNOWN으로 제한한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": {
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291220",
                      "rps": {
                        "trafficType": 1,
                        "duration": 20,
                        "startDateTime": "202607291200",
                        "endDateTime": "202607291220",
                        "startID": 226,
                        "startName": "서울역",
                        "endID": 222,
                        "endName": "사당",
                        "way": "사당 방면",
                        "wayCode": 2,
                        "lane": {
                          "subwayCode": 2,
                          "subwayCityCode": 1000
                        }
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val serviceClass = mapper.map(response, request(), fetchedAt)
            .single()
            .legs
            .single()
            .line
            ?.serviceClass

        assertEquals(TransitServiceClass.UNKNOWN, serviceClass)
    }

    @Test
    fun `공급자 오류가 최상위 배열이나 result 객체에 있으면 정상 경로로 처리하지 않는다`() {
        val errorResponses = listOf(
            """{"error":[{"code":"-99","message":"검색결과가 없습니다."}]}""",
            """{"result":{"error":{"code":"-8","msg":"입력값 오류"}}}""",
        )

        errorResponses.forEach { payload ->
            val error = assertThrows(IllegalStateException::class.java) {
                mapper.map(objectMapper.readTree(payload), request(), fetchedAt)
            }
            assertEquals("ODsay가 경로 조회 오류를 반환했습니다.", error.message)
            assertTrue(error.message.orEmpty().contains("ODsay"))
        }
    }

    @Test
    fun `시간 합계나 달력 날짜가 깨진 후보는 제외한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": [
                      {
                        "pathType": 2,
                        "totalTime": 30,
                        "startDateTime": "202607291200",
                        "endDateTime": "202607291230",
                        "rps": {"trafficType": 2, "duration": 20, "lane": {"busID": "1"}}
                      },
                      {
                        "pathType": 2,
                        "totalTime": 20,
                        "startDateTime": "202602301200",
                        "endDateTime": "202602301220",
                        "rps": {"trafficType": 2, "duration": 20, "lane": {"busID": "1"}}
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `필수 duration이 깨진 구간 하나라도 있으면 해당 여정 전체를 제외한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": [{
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291220",
                      "rps": [
                        {"trafficType": 3, "duration": "invalid"},
                        {
                          "trafficType": 2,
                          "duration": 20,
                          "startID": 100,
                          "endID": 200,
                          "lane": {"busID": "12018", "busNo": "402"}
                        }
                      ]
                    }]
                  }
                }
            """.trimIndent()
        )

        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `공급자 경로가 요청 departure보다 먼저 시작하면 같은 분이어도 제외한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": {
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291220",
                      "rps": {
                        "trafficType": 2,
                        "duration": 20,
                        "waitingTime": 3,
                        "startDateTime": "202607291200",
                        "endDateTime": "202607291220",
                        "startID": 100,
                        "endID": 200,
                        "lane": {"busID": "12018", "busNo": "402"}
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val requestAfterMinuteBoundary = request().copy(
            departureAt = Instant.parse("2026-07-29T03:00:30Z")
        )
        assertTrue(mapper.map(response, requestAfterMinuteBoundary, fetchedAt).isEmpty())
    }

    @Test
    fun `개별 구간은 유효해도 rps 사이에 숨은 시간 공백이 있으면 여정 전체를 제외한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": {
                      "pathType": 2,
                      "totalTime": 40,
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291242",
                      "rps": [
                        {
                          "trafficType": 2,
                          "duration": 20,
                          "waitingTime": 3,
                          "startDateTime": "202607291200",
                          "endDateTime": "202607291220",
                          "startID": 100,
                          "endID": 200,
                          "lane": {"busID": "12018", "busNo": "402"}
                        },
                        {
                          "trafficType": 3,
                          "duration": 20,
                          "startDateTime": "202607291222",
                          "endDateTime": "202607291242"
                        }
                      ]
                    }
                  }
                }
            """.trimIndent()
        )

        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `시각 한쪽만 있는 rps는 환승 시간표로 신뢰하지 않고 제외한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": {
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291220",
                      "rps": {
                        "trafficType": 2,
                        "duration": 20,
                        "waitingTime": 3,
                        "startDateTime": "202607291200",
                        "startID": 100,
                        "endID": 200,
                        "lane": {"busID": "12018", "busNo": "402"}
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `구간 시간표와 duration이 서로 모순되면 실시간 보정에 사용하지 않는다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": [{
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607291200",
                      "endDateTime": "202607291220",
                      "rps": {
                        "trafficType": 2,
                        "duration": 20,
                        "waitingTime": 5,
                        "startDateTime": "202607291200",
                        "endDateTime": "202607291210",
                        "startID": 100,
                        "endID": 200,
                        "lane": {"busID": "12018", "busNo": "402"}
                      }
                    }]
                  }
                }
            """.trimIndent()
        )

        assertTrue(mapper.map(response, request(), fetchedAt).isEmpty())
    }

    @Test
    fun `자정을 넘는 한국 시간표를 정확한 Instant로 변환한다`() {
        val response = objectMapper.readTree(
            """
                {
                  "result": {
                    "paths": [{
                      "pathType": 2,
                      "totalTime": 20,
                      "startDateTime": "202607292355",
                      "endDateTime": "202607300015",
                      "rps": {
                        "trafficType": 1,
                        "duration": 20,
                        "waitingTime": 3,
                        "startDateTime": "202607292355",
                        "endDateTime": "202607300015",
                        "startID": 226,
                        "endID": 222,
                        "wayCode": 1,
                        "lane": {"name": "2호선", "subwayCode": 2, "subwayCityCode": 1000}
                      }
                    }]
                  }
                }
            """.trimIndent()
        )

        val journey = mapper.map(response, request(), fetchedAt).single()

        assertEquals(Instant.parse("2026-07-29T14:55:00Z"), journey.departureAt)
        assertEquals(Instant.parse("2026-07-29T15:15:00Z"), journey.arrivalAt)
        assertEquals(Instant.parse("2026-07-29T14:55:00Z"), journey.legs.single().scheduledDepartureAt)
        assertEquals(Instant.parse("2026-07-29T15:15:00Z"), journey.legs.single().scheduledArrivalAt)
    }

    private fun request() = TransitJourneySearchRequest(
        originLat = 37.5547,
        originLng = 126.9726,
        destinationLat = 37.4979,
        destinationLng = 127.0276,
        departureAt = Instant.parse("2026-07-29T03:00:00Z"),
        maxTravelMinutes = 180,
    )
}
