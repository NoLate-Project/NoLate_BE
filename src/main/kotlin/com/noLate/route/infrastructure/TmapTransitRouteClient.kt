package com.noLate.route.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.application.TransitRouteProvider
import com.noLate.route.domain.TransitRouteRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@ConditionalOnProperty(prefix = "routing.tmap", name = ["enabled"], havingValue = "true")
class TmapTransitRouteClient(
    @Value("\${routing.tmap.app-key}") appKey: String,
    @Value("\${routing.tmap.base-url}") baseUrl: String,
) : TransitRouteProvider {
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("appKey", appKey)
        .build()

    override fun providerId(): String = "tmap"

    override fun getRoutes(request: TransitRouteRequest): JsonNode = restClient.post()
        .uri("/transit/routes")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            linkedMapOf<String, Any>(
                "startX" to request.startX.toString(),
                "startY" to request.startY.toString(),
                "endX" to request.endX.toString(),
                "endY" to request.endY.toString(),
                "count" to request.count,
                "lang" to request.lang,
                "format" to request.format,
            ).apply {
                request.searchDttm?.let { put("searchDttm", it) }
            }
        )
        .retrieve()
        .body(JsonNode::class.java)
        ?: error("TMAP 대중교통 경로 응답이 비어 있습니다.")
}
