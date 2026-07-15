package com.noLate.route.domain

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

data class TransitRouteRequest(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val count: Int = 10,
    val lang: Int = 0,
    val format: String = "json",
    val searchDttm: String? = null,
) {
    fun validated(): TransitRouteRequest {
        val coordinatesValid = startX in -180.0..180.0 && endX in -180.0..180.0 &&
            startY in -90.0..90.0 && endY in -90.0..90.0
        if (!coordinatesValid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "출발지 또는 도착지 좌표가 올바르지 않습니다.")
        }
        if (count !in 1..10) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "대중교통 경로 개수는 1~10이어야 합니다.")
        }
        if (lang !in 0..1 || format != "json") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 대중교통 경로 요청 형식입니다.")
        }
        if (searchDttm != null && !searchDttm.matches(Regex("\\d{12}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "검색 시각은 yyyyMMddHHmm 형식이어야 합니다.")
        }
        return this
    }
}
