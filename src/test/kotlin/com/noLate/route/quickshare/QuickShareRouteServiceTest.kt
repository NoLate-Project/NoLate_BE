package com.noLate.route.quickshare

import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class QuickShareRouteServiceTest {
    private val places = listOf(QuickSharePlace("서울역", "서울 중구", 37.5547, 126.9707))
    private var roadRouteCalls = 0
    private val provider = object : QuickShareRouteProvider {
        override fun searchPlaces(query: String) = places
        override fun getRouteOptions(request: QuickShareRouteRequest): List<QuickShareRouteOption> {
            roadRouteCalls++
            return listOf(QuickShareRouteOption("road", request.mode, 20, summary = "도로"))
        }
    }
    private val fixedNow = Instant.parse("2026-10-21T08:00:30Z")
    private val odsay = object : TransitJourneyProvider {
        override val providerId = "odsay"
        var lastRequest: TransitJourneySearchRequest? = null
        override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
            lastRequest = request
            return (1..4).map { index ->
                TransitJourney("odsay", request.departureAt, request.departureAt,
                    request.departureAt.plusSeconds((20 + index) * 60L), 20 + index,
                    listOf(TransitJourneyLeg(0, TransitLegMode.BUS, 20 + index)), fixedNow)
            }
        }
    }
    private val service = QuickShareRouteService(listOf(provider), listOf(odsay), Clock.fixed(fixedNow, ZoneOffset.UTC))

    @Test
    fun `place search trims query and returns provider results`() {
        assertEquals(places, service.searchPlaces("  서울역  "))
    }

    @Test
    fun `route options validate coordinates and limit share sheet candidates`() {
        val request = QuickShareRouteRequest(
            origin = SchedulePlaceDto("서울역", lat = 37.5547, lng = 126.9707),
            destination = SchedulePlaceDto("강남역", lat = 37.4979, lng = 127.0276),
            mode = ScheduleTravelMode.TRANSIT,
        )

        val options = service.getRouteOptions(request)
        assertEquals(3, options.size)
        assertEquals(true, options.all { it.provider == "odsay" && it.mode == ScheduleTravelMode.TRANSIT })
        assertEquals(Instant.parse("2026-10-21T08:01:00Z"), odsay.lastRequest?.departureAt)
        assertEquals(0, roadRouteCalls)
    }

    @Test
    fun `transit never falls back to TMAP when ODsay is disabled`() {
        val request = QuickShareRouteRequest(
            origin = SchedulePlaceDto("서울역", lat = 37.5547, lng = 126.9707),
            destination = SchedulePlaceDto("강남역", lat = 37.4979, lng = 127.0276),
            mode = ScheduleTravelMode.TRANSIT,
        )
        assertThrows(ResponseStatusException::class.java) {
            QuickShareRouteService(listOf(provider), emptyList(), Clock.fixed(fixedNow, ZoneOffset.UTC)).getRouteOptions(request)
        }
        assertEquals(0, roadRouteCalls)
    }

    @Test
    fun `route request rejects unsupported mode`() {
        val request = QuickShareRouteRequest(
            origin = SchedulePlaceDto("서울역", lat = 37.5547, lng = 126.9707),
            destination = SchedulePlaceDto("강남역", lat = 37.4979, lng = 127.0276),
            mode = ScheduleTravelMode.BIKE,
        )

        assertThrows(ResponseStatusException::class.java) { service.getRouteOptions(request) }
    }
}

