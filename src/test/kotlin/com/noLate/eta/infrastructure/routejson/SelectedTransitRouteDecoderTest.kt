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
}
