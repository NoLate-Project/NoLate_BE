package com.noLate.route.quickshare

import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class QuickShareRouteServiceTest {
    private val places = listOf(QuickSharePlace("서울역", "서울 중구", 37.5547, 126.9707))
    private val options = (1..4).map {
        QuickShareRouteOption("route-$it", ScheduleTravelMode.TRANSIT, 20 + it, summary = "경로 $it")
    }
    private val provider = object : QuickShareRouteProvider {
        override fun searchPlaces(query: String) = places
        override fun getRouteOptions(request: QuickShareRouteRequest) = options
    }
    private val service = QuickShareRouteService(listOf(provider))

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

        assertEquals(3, service.getRouteOptions(request).size)
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

