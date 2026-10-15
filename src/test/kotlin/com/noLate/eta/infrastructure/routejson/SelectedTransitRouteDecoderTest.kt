package com.noLate.eta.infrastructure.routejson

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitServiceClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SelectedTransitRouteDecoderTest {
    private val decoder = SelectedTransitRouteDecoder(jacksonObjectMapper())

    @Test
    fun `Tmap의 raw 정류장 코드는 내부 ID이며 5자리여도 ARS로 추론하지 않는다`() {
        val route = """{"provider":"tmap","transitLegs":[{
          "kind":"BUS","lineName":"402","startName":"A","endName":"B",
          "passStops":[{"code":"12345"},{"code":"197386"}]
        }]}"""
        val ride = requireNotNull(decoder.decode(route)).rides.single()
        assertEquals(setOf("PROVIDER:tmap:12345"), ride.fromIds)
        assertEquals(setOf("PROVIDER:tmap:197386"), ride.toIds)
    }

    @Test
    fun `Tmap도 명시적인 ARS와 provider namespace는 중복 변경하지 않는다`() {
        val route = """{"provider":"tmap","transitLegs":[{
          "kind":"BUS","lineName":"402","startStopCode":"ARS:12345",
          "endStopCode":"PROVIDER:tmap:197386"
        }]}"""
        val ride = requireNotNull(decoder.decode(route)).rides.single()
        assertEquals(setOf("ARS:12345"), ride.fromIds)
        assertEquals(setOf("PROVIDER:tmap:197386"), ride.toIds)
    }

    @Test
    fun `선택 경로 JSON의 명시된 지하철 종별을 서명에 보존한다`() {
        val selected = decoder.decode(routeJson(serviceClass = "express"))

        assertNotNull(selected)
        val ride = requireNotNull(selected).rides.single()
        assertEquals(TransitLegMode.SUBWAY, ride.mode)
        assertEquals(TransitServiceClass.EXPRESS, ride.serviceClass)
    }

    @Test
    fun `serviceClass가 없는 기존 선택 경로 JSON도 UNKNOWN으로 디코드한다`() {
        val selected = decoder.decode(routeJson(serviceClass = null))

        assertNotNull(selected)
        assertEquals(TransitServiceClass.UNKNOWN, requireNotNull(selected).rides.single().serviceClass)
    }

    @Test
    fun `알 수 없는 serviceClass 값은 선택 경로를 깨뜨리지 않고 UNKNOWN으로 제한한다`() {
        val selected = decoder.decode(routeJson(serviceClass = "RAPID_PLUS"))

        assertNotNull(selected)
        assertEquals(TransitServiceClass.UNKNOWN, requireNotNull(selected).rides.single().serviceClass)
    }

    @Test
    fun `시외 구간의 수단과 순서를 저장 JSON에서 복원한다`() {
        val selected = requireNotNull(decoder.decode("""{"schemaVersion":2,"provider":"tmap","transitLegs":[
          {"kind":"BUS","lineName":"시내"},
          {"kind":"TRAIN","lineName":"SRT"},
          {"kind":"EXPRESS_BUS","lineName":"고속"},
          {"kind":"INTERCITY_BUS","lineName":"시외"},
          {"kind":"COACH","lineName":"미분류 버스"}
        ]}"""))
        assertEquals(listOf(TransitLegMode.BUS, TransitLegMode.TRAIN, TransitLegMode.EXPRESS_BUS,
            TransitLegMode.INTERCITY_BUS, TransitLegMode.COACH), selected.rides.map { it.mode })
    }

    private fun routeJson(serviceClass: String?): String {
        val serviceClassField = serviceClass?.let { "\"serviceClass\": \"$it\"," }.orEmpty()
        return """
            {
              "provider": "odsay",
              "transitLegs": [
                {
                  "kind": "SUBWAY",
                  $serviceClassField
                  "durationMinutes": 20,
                  "providerRouteId": "subway-9",
                  "lineName": "수도권 9호선(급행)",
                  "startID": "station-a",
                  "startName": "여의도역",
                  "endID": "station-b",
                  "endName": "김포공항역",
                  "directionName": "김포공항 방면",
                  "directionCode": "DOWN"
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun `Kakao 정류장 ID는 Kakao provider namespace로 디코드한다`() {
        val kakaoRouteJson = routeJson(serviceClass = "local")
            .replace(
                "\"provider\": \"odsay\"",
                "\"provider\": \"kakao\"",
            )

        val ride = requireNotNull(
            decoder.decode(kakaoRouteJson)
        ).rides.single()

        // 공급자 내부 정류장 ID는 값이 겹칠 수 있으므로 provider namespace와 함께 비교해야 한다.
        assertEquals(
            setOf("PROVIDER:kakao:station-a"),
            ride.fromIds,
        )
        assertEquals(
            setOf("PROVIDER:kakao:station-b"),
            ride.toIds,
        )
    }
}
