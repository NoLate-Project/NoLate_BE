package com.noLate.transit.application

import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitCityCodeNamespace
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import com.noLate.transit.infrastructure.TransitProviderWireMetrics
import org.springframework.stereotype.Service

@Service
class TransitArrivalService(
    private val seoulTransitArrivalClient: SeoulTransitArrivalClient,
    private val tagoTransitArrivalClient: TagoTransitArrivalClient,
    private val cityCodeResolver: TransitCityCodeResolver = TransitCityCodeResolver(),
    private val wireMetrics: TransitProviderWireMetrics? = null,
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
        cityCodeNamespace: TransitCityCodeNamespace = TransitCityCodeNamespace.TAGO,
        providerCode: String? = null,
    ): List<TransitArrivalDto> {
        val normalizedArsId = arsId?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val normalizedRouteName = routeName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedStationName = stationName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedLimit = limit.coerceIn(1, 10)
        val normalizedNodeId = nodeId?.trim()?.takeIf(String::isNotBlank)
        val normalizedProviderCode = providerCode?.trim()?.takeIf(String::isNotBlank)
        val city = cityCodeResolver.resolve(cityCode, cityCodeNamespace)
        if (city == TransitCityResolution.Unsupported) {
            wireMetrics?.recordUnsupportedMapping(cityCodeNamespace)
            return emptyList()
        }

        val explicitCity = city as? TransitCityResolution.Tago
        val seoulIsExplicit = city == TransitCityResolution.Seoul ||
            (city == TransitCityResolution.Absent && normalizedProviderCode in SEOUL_PROVIDER_CODES)
        if (seoulIsExplicit) {
            val seoulArrivals = fetchSeoul(
                arsId = normalizedArsId,
                routeName = normalizedRouteName,
                stationName = normalizedStationName,
                limit = normalizedLimit,
            )
            // TAGO의 현재 cityCode 12는 서울이 아니라 세종이다. 서울 조회 실패를 TAGO
            // 무도시 검색으로 넘기면 동명 정류장을 오염시키므로 서울 결과로 즉시 닫는다.
            return seoulArrivals
        }

        if (
            normalizedArsId == null &&
            explicitCity == null &&
            normalizedNodeId == null &&
            normalizedStationName == null
        ) {
            return emptyList()
        }

        // 명시적 비서울 및 불명확 metadata는 서울 동명 정류장 검색으로 되돌아가지 않는다.
        val tagoCityCode = explicitCity?.code
        val tagoArrivals = runCatching {
            tagoTransitArrivalClient.getBusArrivals(
                arsId = normalizedArsId,
                routeName = normalizedRouteName,
                cityCode = tagoCityCode,
                nodeId = normalizedNodeId,
                stationName = normalizedStationName,
                limit = normalizedLimit,
            )
        }.getOrDefault(emptyList())
            .filter {
                it.matchesRequestIdentifiers(
                    expectedCityCode = tagoCityCode,
                    expectedNodeId = normalizedNodeId,
                    expectedArsId = normalizedArsId,
                    expectedStationName = normalizedStationName,
                    expectedRouteName = normalizedRouteName,
                )
            }

        return tagoArrivals
    }

    private fun fetchSeoul(
        arsId: String?,
        routeName: String?,
        stationName: String?,
        limit: Int,
    ): List<TransitArrivalDto> {
        if (arsId == null && stationName == null) return emptyList()
        return runCatching {
            seoulTransitArrivalClient.getBusArrivals(
                arsId = arsId,
                stationName = stationName,
                routeName = routeName,
                limit = limit,
            )
        }.getOrDefault(emptyList())
            .filter {
                it.matchesRequestIdentifiers(
                    expectedCityCode = null,
                    expectedNodeId = null,
                    expectedArsId = arsId,
                    expectedStationName = stationName,
                    expectedRouteName = routeName,
                )
            }
    }

    private fun TransitArrivalDto.matchesRequestIdentifiers(
        expectedCityCode: String?,
        expectedNodeId: String?,
        expectedArsId: String?,
        expectedStationName: String?,
        expectedRouteName: String?,
    ): Boolean {
        if (expectedCityCode != null && cityCode != expectedCityCode) return false
        when {
            expectedNodeId != null -> if (nodeId != expectedNodeId) return false
            expectedArsId != null -> if (arsId != expectedArsId) return false
            expectedStationName != null -> {
                val expected = normalizeStationName(expectedStationName)
                val actual = normalizeStationName(stationName)
                if (actual == null || actual != expected) return false
            }
        }
        if (expectedRouteName != null) {
            val expected = normalizeRouteName(expectedRouteName)
            val actual = normalizeRouteName(routeName ?: lineName)
            if (actual == null || actual != expected) return false
        }
        return true
    }

    private fun normalizeStationName(value: String?): String? = value
        ?.replace(PARENTHESIZED_PATTERN, "")
        ?.replace(WHITESPACE_PATTERN, "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun normalizeRouteName(value: String?): String? = value
        ?.replace(WHITESPACE_PATTERN, "")
        ?.replace("버스", "")
        ?.removeSuffix("번")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private companion object {
        val SEOUL_PROVIDER_CODES = setOf("4", "10")
        val WHITESPACE_PATTERN = Regex("""\s+""")
        val PARENTHESIZED_PATTERN = Regex("""\([^)]*\)""")
    }
}
