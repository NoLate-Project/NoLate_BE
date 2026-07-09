package com.noLate.transit.application

import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.springframework.stereotype.Service

@Service
class TransitArrivalService(
    private val seoulTransitArrivalClient: SeoulTransitArrivalClient,
) {
    fun getSubwayArrivals(
        stationName: String,
        lineName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val normalizedStationName = stationName.trim()
        if (normalizedStationName.isBlank()) return emptyList()

        return runCatching {
            seoulTransitArrivalClient.getSubwayArrivals(
                stationName = normalizedStationName,
                lineName = lineName?.trim()?.takeIf { it.isNotBlank() },
                limit = limit.coerceIn(1, 10),
            )
        }.getOrDefault(emptyList())
    }

    fun getBusArrivals(
        arsId: String,
        routeName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val normalizedArsId = arsId.filter { it.isDigit() }
        if (normalizedArsId.isBlank()) return emptyList()

        return runCatching {
            seoulTransitArrivalClient.getBusArrivals(
                arsId = normalizedArsId,
                routeName = routeName?.trim()?.takeIf { it.isNotBlank() },
                limit = limit.coerceIn(1, 10),
            )
        }.getOrDefault(emptyList())
    }
}
