package com.noLate.transit.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.security.MemberPrincipal
import com.noLate.transit.controller.TransitArrivalController
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TransitStationBoardHttpTest {
    @Test
    fun `station board uses Seoul only when the boarding city is in the ODsay namespace`() {
        val seoul = mock<SeoulTransitArrivalClient>()
        val tago = mock<TagoTransitArrivalClient>()
        val arrival = TransitArrivalDto(provider = "seoul-bus", kind = "BUS", routeName = "N37", arsId = "01001")
        val expired = arrival.copy(arrivalStatus = TransitArrivalStatus.ARRIVED,
            sourceUpdatedAt = "2026-11-20T02:34:03Z",
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
        whenever(seoul.getBusArrivals("01001", "승차 정류장", "N37", 10)).thenReturn(listOf(expired, arrival))
        val mvc = stationBoard(seoul, tago, Instant.parse("2026-11-20T02:40:03Z"))
        mvc.get("/api/transit-arrivals/bus") {
            param("arsId", "01001")
            param("routeName", "N37")
            param("stationName", "승차 정류장")
            param("cityCode", "1000")
            param("cityCodeNamespace", "ODSAY_CID")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(1) }
            jsonPath("$.data[0].provider") { value("seoul-bus") }
        }
        mvc.get("/api/transit-arrivals/bus") {
            param("arsId", "01001")
            param("routeName", "N37")
            param("stationName", "승차 정류장")
            param("cityCode", "1000")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(0) }
        }
    }

    @Test
    fun `station board JSON omits opposite and duplicate trains without collapsing equal clocks`() {
        val seoul = mock<SeoulTransitArrivalClient>()
        val early = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", direction = "하행",
            subwayLineId = "1004", subwayStationId = "1004000411", trainNumber = "4012",
            expectedAt = "2026-11-15T03:10:00Z", sourceUpdatedAt = "2026-11-15T03:06:00Z",
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
        val opposite = early.copy(direction = "상행", trainNumber = "4099")
        whenever(seoul.getSubwayArrivals("노원", "4호선", null, "DOWN", 10)).thenReturn(
            listOf(early, opposite, early.copy(sourceUpdatedAt = "2026-11-15T03:07:00Z"), early.copy(trainNumber = "4013")),
        )
        whenever(seoul.getSubwayArrivals("사당", "4호선", null, "DOWN", 10)).thenReturn(listOf(opposite))
        val mvc = stationBoard(seoul, mock(), Instant.parse("2026-11-15T03:07:10Z"))

        mvc.get("/api/transit-arrivals/subway") {
            param("stationName", "노원")
            param("lineName", "4호선")
            param("directionCode", "DOWN")
            param("limit", "2")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].trainNumber") { value("4012") }
            jsonPath("$.data[0].sourceUpdatedAt") { value("2026-11-15T03:07:00Z") }
            jsonPath("$.data[1].trainNumber") { value("4013") }
            jsonPath("$.data[0].expectedAt") { value("2026-11-15T03:10:00Z") }
            jsonPath("$.data[1].expectedAt") { value("2026-11-15T03:10:00Z") }
        }
        mvc.get("/api/transit-arrivals/subway") {
            param("stationName", "사당")
            param("lineName", "4호선")
            param("directionCode", "DOWN")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(0) }
        }
    }

    private fun stationBoard(seoul: SeoulTransitArrivalClient, tago: TagoTransitArrivalClient, now: Instant) =
        MockMvcBuilders.standaloneSetup(TransitArrivalController(TransitArrivalService(seoul, tago,
            clock = Clock.fixed(now, ZoneOffset.UTC))))
            .setCustomArgumentResolvers(object : HandlerMethodArgumentResolver {
                override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == MemberPrincipal::class.java
                override fun resolveArgument(parameter: MethodParameter, container: ModelAndViewContainer?,
                    request: NativeWebRequest, factory: WebDataBinderFactory?) = MemberPrincipal(296L, "eta@example.test", "Fixture")
            }).setMessageConverters(MappingJackson2HttpMessageConverter(jacksonObjectMapper())).build()
}
