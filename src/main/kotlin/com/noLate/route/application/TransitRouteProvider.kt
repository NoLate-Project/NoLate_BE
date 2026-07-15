package com.noLate.route.application

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.domain.TransitRouteRequest

fun interface TransitRouteProvider {
    fun getRoutes(request: TransitRouteRequest): JsonNode

    /** QA 진단과 다중 공급자 선택에 사용하는 안정적인 식별자다. */
    fun providerId(): String = javaClass.simpleName.ifBlank { "unknown" }
}
