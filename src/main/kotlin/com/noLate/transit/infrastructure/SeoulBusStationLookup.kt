package com.noLate.transit.infrastructure

internal fun isSeoulBusArsId(value: String?): Boolean = value?.matches(Regex("^\\d{5}$")) == true

internal fun seoulBusStationSearchTerms(stationName: String?): List<String> {
    val normalized = stationName?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (normalized.isBlank()) return emptyList()
    val withoutParentheses = normalized.replace(Regex("\\([^)]*\\)"), "").trim()
    return listOf(normalized, withoutParentheses)
        .filter { it.isNotBlank() }
        .distinct()
}
