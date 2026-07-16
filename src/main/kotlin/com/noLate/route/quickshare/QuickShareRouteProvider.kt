package com.noLate.route.quickshare

interface QuickShareRouteProvider {
    fun searchPlaces(query: String): List<QuickSharePlace>
    fun getRouteOptions(request: QuickShareRouteRequest): List<QuickShareRouteOption>
}

