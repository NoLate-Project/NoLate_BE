package com.noLate.transit.domain

enum class TransitArrivalStatus(val displayLabel: String) {
    APPROACHING("진입"),
    ARRIVED("도착"),
    DEPARTED("출발"),
    PREVIOUS_STOP("전역 이동"),
    IN_TRANSIT("운행 중"),
    UNKNOWN("도착 예정"),
}

fun seoulSubwayArrivalStatus(code: String?, message: String?): TransitArrivalStatus = when (code?.trim()) {
    "0" -> TransitArrivalStatus.APPROACHING
    "1" -> TransitArrivalStatus.ARRIVED
    "2" -> TransitArrivalStatus.DEPARTED
    "3", "4", "5" -> TransitArrivalStatus.PREVIOUS_STOP
    "99" -> TransitArrivalStatus.IN_TRANSIT
    else -> inferTransitArrivalStatus(message)
}

fun estimatedTransitArrivalStatus(
    waitSeconds: Int?,
    remainingStops: Int?,
    message: String?,
): TransitArrivalStatus {
    val inferred = inferTransitArrivalStatus(message)
    if (inferred != TransitArrivalStatus.UNKNOWN) return inferred
    if (remainingStops == 0 || (waitSeconds != null && waitSeconds <= 60)) {
        return TransitArrivalStatus.APPROACHING
    }
    return if (waitSeconds != null || remainingStops != null) {
        TransitArrivalStatus.IN_TRANSIT
    } else {
        TransitArrivalStatus.UNKNOWN
    }
}

private fun inferTransitArrivalStatus(message: String?): TransitArrivalStatus {
    val normalized = message?.trim().orEmpty()
    return when {
        normalized.contains("전역") -> TransitArrivalStatus.PREVIOUS_STOP
        normalized.contains("진입") || normalized.contains("곧") -> TransitArrivalStatus.APPROACHING
        normalized.contains("도착") -> TransitArrivalStatus.ARRIVED
        normalized.contains("출발") -> TransitArrivalStatus.DEPARTED
        else -> TransitArrivalStatus.UNKNOWN
    }
}
