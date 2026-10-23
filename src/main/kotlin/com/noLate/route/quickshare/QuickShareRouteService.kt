package com.noLate.route.quickshare

import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class QuickShareRouteService(
    private val providers: List<QuickShareRouteProvider>,
    private val transitProviders: List<TransitJourneyProvider> = emptyList(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun searchPlaces(query: String): List<QuickSharePlace> {
        val normalized = query.trim()
        if (normalized.length !in 1..120) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "장소 검색어는 1~120자여야 합니다.")
        }
        val provider = providers.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "장소 검색을 지금 사용할 수 없어요.")
        return provider.searchPlaces(normalized)
    }

    fun getRouteOptions(request: QuickShareRouteRequest): List<QuickShareRouteOption> {
        val validated = request.validated()
        if (validated.mode == ScheduleTravelMode.TRANSIT) return odsayOptions(validated)
        val provider = providers.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "경로 찾기를 지금 사용할 수 없어요.")
        val options = provider.getRouteOptions(validated)
        if (options.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "이동 경로를 찾지 못했어요.")
        }
        return options.take(3)
    }

    private fun odsayOptions(request: QuickShareRouteRequest): List<QuickShareRouteOption> {
        val provider = transitProviders.singleOrNull { it.providerId.equals("odsay", ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ODsay 대중교통 경로를 지금 사용할 수 없어요.")
        val requested = request.departureAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: Instant.now(clock)
        val minute = requested.truncatedTo(ChronoUnit.MINUTES)
        val departure = if (minute == requested) minute else minute.plus(1, ChronoUnit.MINUTES)
        val search = TransitJourneySearchRequest(
            originLat = requireNotNull(request.origin.lat), originLng = requireNotNull(request.origin.lng),
            destinationLat = requireNotNull(request.destination.lat), destinationLng = requireNotNull(request.destination.lng),
            departureAt = departure, maxTravelMinutes = 1_440,
        )
        val journeys = try { provider.search(search) } catch (_: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "ODsay 대중교통 경로를 확인하지 못했어요.")
        }
        val options = journeys.filter { it.provider.equals("odsay", ignoreCase = true) && it.totalMinutes > 0 }
            .sortedWith(compareBy({ it.arrivalAt }, { it.totalMinutes }))
            .take(3)
            .mapIndexed { index, journey ->
                val transfers = (journey.rideLegs.size - 1).coerceAtLeast(0)
                QuickShareRouteOption(
                    id = "odsay-transit-$index-${journey.totalMinutes}-$transfers",
                    mode = ScheduleTravelMode.TRANSIT,
                    minutes = journey.totalMinutes,
                    transferCount = transfers,
                    summary = if (transfers == 0) "추천 대중교통 경로" else "대중교통 · 환승 ${transfers}회",
                    provider = "odsay",
                )
            }
        if (options.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "이동 경로를 찾지 못했어요.")
        return options
    }
}
