package com.noLate.eta.domain

/** 여정 내 위치에 따른 구분이며, 역 출입구·승강장까지 포함한다는 증거는 아니다. */
enum class TransitWalkingScope { ACCESS, TRANSFER, EGRESS, UNKNOWN }

data class TransitWalkingCoordinate(val lat: Double, val lng: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0)
        require(lng.isFinite() && lng in -180.0..180.0)
    }
}

/** 명목 구간 끝점은 실제 도보 polyline 끝점이나 승강장 좌표와 구분한다. */
enum class TransitWalkingEndpointBasis { UNKNOWN, PROVIDER_LEG_ENDPOINTS }

enum class TransitWalkingPathStatus { PROVIDED, UNAVAILABLE, INVALID, LIMIT_EXCEEDED }

/** 상세 경로가 있다는 사실만으로 역 내부/승강장 포함 여부를 확정하지 않는다. */
enum class TransitStationInteriorCoverage { UNKNOWN, EXCLUDED, INCLUDED_TO_PLATFORM }

data class TransitWalkingStep(
    val coordinates: List<TransitWalkingCoordinate>,
    val distanceMeters: Double? = null,
    val description: String? = null,
) {
    init {
        require(coordinates.size in 2..TransitWalkingPathEvidence.MAX_COORDINATES_PER_STEP)
        require(distanceMeters == null || (distanceMeters.isFinite() && distanceMeters >= 0))
        require(description == null || (description.isNotBlank() &&
            description.length <= TransitWalkingPathEvidence.MAX_DESCRIPTION_LENGTH && description.none(Char::isISOControl)))
    }
}

/** 비교·진단용 원천 증거다. 거리나 설명으로 ETA/승차 여유를 다시 계산하지 않는다. */
data class TransitWalkingPathEvidence(
    val provider: String,
    val status: TransitWalkingPathStatus,
    val steps: List<TransitWalkingStep> = emptyList(),
    val stationInteriorCoverage: TransitStationInteriorCoverage = TransitStationInteriorCoverage.UNKNOWN,
) {
    init {
        require(provider.matches(Regex("[a-z0-9-]{1,40}")))
        require(steps.size <= MAX_STEPS && steps.sumOf { it.coordinates.size } <= MAX_TOTAL_COORDINATES)
        require(if (status == TransitWalkingPathStatus.PROVIDED) steps.isNotEmpty() else steps.isEmpty())
        // 현재 공급자 계약에는 승강장 포함을 증명하는 필드가 없다. 임의 확정값의 저장을 막는다.
        require(stationInteriorCoverage == TransitStationInteriorCoverage.UNKNOWN)
    }

    companion object {
        const val MAX_STEPS = 32
        const val MAX_COORDINATES_PER_STEP = 128
        const val MAX_TOTAL_COORDINATES = 512
        const val MAX_DESCRIPTION_LENGTH = 200
    }
}

/** 기존 JSON에 상세 증거가 없으면 null/UNKNOWN이며, 누락을 0m/0초로 바꾸지 않는다. */
data class TransitWalkingGeometry(
    val from: TransitWalkingCoordinate? = null,
    val to: TransitWalkingCoordinate? = null,
    val distanceMeters: Long? = null,
    val endpointBasis: TransitWalkingEndpointBasis = TransitWalkingEndpointBasis.UNKNOWN,
    val pathEvidence: TransitWalkingPathEvidence? = null,
    /** 공급자 링크 연결의 도보 조회 끝점. 역/승강장 좌표라는 뜻은 아니다. */
    val providerQueryEndpoints: TransitWalkingQueryEndpoints? = null,
) {
    init { require(distanceMeters == null || distanceMeters >= 0) }
}

data class TransitWalkingQueryEndpoints(val start: TransitWalkingCoordinate, val end: TransitWalkingCoordinate)

enum class TransitWalkingTimingSource { ROUTE_MINUTES, PROVIDER_LINK_SECONDS, ODSAY_FULL_PATH_SECONDS }

/** 공식 기준값은 방향·승강장·경로 포함 범위가 확인되지 않아 계산값으로 자동 대체하지 않는다. */
data class TransitTransferReference(
    val stationName: String,
    val fromLine: String,
    val toLine: String,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val datasetDate: String = "2025-03-31",
    val provider: String = "seoulmetro",
    val walkingSpeedMetersPerSecond: Double = 1.2,
    val application: String = "REFERENCE_ONLY_DIRECTION_AND_PATH_UNVERIFIED",
    val sourceUrl: String = "https://data.seoul.go.kr/dataList/OA-13290/F/1/datasetView.do",
)

/** 이동시간과 원천 근거만 담는다. 승차/환승 안전 여유는 이 시간에 포함하지 않는다. */
data class TransitWalkingTimingEvidence(
    val source: TransitWalkingTimingSource,
    val originalDurationMinutes: Int,
    val movementSeconds: Long,
    val fetchedAt: java.time.Instant? = null,
    val sameOrderedPathVerified: Boolean = false,
    val transferReference: TransitTransferReference? = null,
) {
    init {
        require(originalDurationMinutes >= 0 && movementSeconds >= 0)
        require(source != TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS ||
            (fetchedAt != null && sameOrderedPathVerified))
    }
}

/** 판단에 실제 사용한 도보와 여유를 분리한다. 여유는 합이 아니라 max(buffer, confidence)다. */
data class TransitTransferTimingEvidence(
    val previousArrivalAt: java.time.Instant,
    val walkingSeconds: Long,
    val bufferSeconds: Long,
    val confidenceMarginSeconds: Long,
    val boardingAt: java.time.Instant,
) {
    init { require(walkingSeconds >= 0 && bufferSeconds >= 0 && confidenceMarginSeconds >= 0) }
    @get:com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    val requiredMarginSeconds: Long get() = maxOf(bufferSeconds, confidenceMarginSeconds)
    @get:com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    val physicalReadyAt: java.time.Instant get() = previousArrivalAt.plusSeconds(walkingSeconds)
    @get:com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    val confidenceReadyAt: java.time.Instant get() = physicalReadyAt.plusSeconds(requiredMarginSeconds)
    @get:com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    val remainingSlackSeconds: Long get() = java.time.Duration.between(confidenceReadyAt, boardingAt).seconds
}
