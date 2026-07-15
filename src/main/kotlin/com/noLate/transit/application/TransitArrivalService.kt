package com.noLate.transit.application

import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import org.springframework.stereotype.Service

@Service
class TransitArrivalService(
    private val seoulTransitArrivalClient: SeoulTransitArrivalClient,
    private val tagoTransitArrivalClient: TagoTransitArrivalClient,
) {
    fun getSubwayArrivals(
        stationName: String,
        lineName: String?,
        directionName: String?,
        directionCode: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val normalizedStationName = stationName.trim()
        if (normalizedStationName.isBlank()) return emptyList()

        return runCatching {
            seoulTransitArrivalClient.getSubwayArrivals(
                stationName = normalizedStationName,
                lineName = lineName?.trim()?.takeIf { it.isNotBlank() },
                directionName = directionName?.trim()?.takeIf { it.isNotBlank() },
                directionCode = directionCode?.trim()?.uppercase()?.takeIf { it == "UP" || it == "DOWN" },
                limit = limit.coerceIn(1, 10),
            )
        }.getOrDefault(emptyList())
    }

    fun getBusArrivals(
        arsId: String?,
        routeName: String?,
        cityCode: String?,
        nodeId: String?,
        stationName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        val normalizedArsId = arsId?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val normalizedRouteName = routeName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedStationName = stationName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedLimit = limit.coerceIn(1, 10)

        val seoulArrivals = if (normalizedArsId == null && normalizedStationName == null) {
            emptyList()
        } else {
            runCatching {
                seoulTransitArrivalClient.getBusArrivals(
                    arsId = normalizedArsId,
                    stationName = normalizedStationName,
                    routeName = normalizedRouteName,
                    limit = normalizedLimit,
                )
            }.getOrDefault(emptyList())
        }
        if (seoulArrivals.isNotEmpty()) return seoulArrivals

        if (
            normalizedArsId == null &&
            cityCode.isNullOrBlank() &&
            nodeId.isNullOrBlank() &&
            stationName.isNullOrBlank()
        ) {
            return emptyList()
        }

        return runCatching {
            tagoTransitArrivalClient.getBusArrivals(
                arsId = normalizedArsId,
                routeName = normalizedRouteName,
                cityCode = cityCode?.trim()?.takeIf { it.isNotBlank() },
                nodeId = nodeId?.trim()?.takeIf { it.isNotBlank() },
                stationName = normalizedStationName,
                limit = normalizedLimit,
            )
        }.getOrDefault(emptyList())
    }
}
