package com.noLate.route.quickshare

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class QuickShareRouteService(
    private val providers: List<QuickShareRouteProvider>,
) {
    fun searchPlaces(query: String): List<QuickSharePlace> {
        val normalized = query.trim()
        if (normalized.length !in 1..120) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "장소 검색어는 1~120자여야 합니다.")
        }
        val provider = providers.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "장소 검색 공급자가 설정되지 않았습니다.")
        return provider.searchPlaces(normalized)
    }

    fun getRouteOptions(request: QuickShareRouteRequest): List<QuickShareRouteOption> {
        val provider = providers.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "경로 공급자가 설정되지 않았습니다.")
        val options = provider.getRouteOptions(request.validated())
        if (options.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "사용 가능한 경로 후보를 찾지 못했습니다.")
        }
        return options.take(3)
    }
}
