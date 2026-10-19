package com.noLate.transit.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

/** Provider namespaces, not ODsay station/line codes. Official OA-12601 line allow-list. */
enum class SeoulSubwayObservationLine(val providerId: String, val requestName: String) {
    LINE_1("1001", "1호선"), LINE_2("1002", "2호선"), LINE_3("1003", "3호선"),
    LINE_4("1004", "4호선"), LINE_5("1005", "5호선"), LINE_6("1006", "6호선"),
    LINE_7("1007", "7호선"), LINE_8("1008", "8호선"), LINE_9("1009", "9호선"),
    GYEONGUI_JUNGANG("1063", "경의중앙선"), AIRPORT("1065", "공항철도"),
    GYEONGCHUN("1067", "경춘선"), SUIN_BUNDANG("1075", "수인분당선"),
    SHINBUNDANG("1077", "신분당선"), UI_SINSEOL("1092", "우이신설선"), GTX_A("1032", "GTX-A"),
}

data class SeoulSubwayTrainQuery(val line: SeoulSubwayObservationLine, val limit: Int = 500) {
    init { require(limit in 1..500) { "Invalid subway observation limit" } }
}

enum class SeoulSubwayTrainStatus { AVAILABLE, NO_TRAINS, CREDENTIAL_UNAVAILABLE, NO_USABLE_OBSERVATIONS, INCOMPLETE_PAGE }
enum class SeoulSubwayTrainRejection {
    LINE_MISMATCH, TRAIN_ID_INVALID, STATION_ID_INVALID, DIRECTION_INVALID, STATE_INVALID,
    SERVICE_INVALID, LAST_TRAIN_INVALID, TIMESTAMP_UNSUPPORTED, TIMESTAMP_INVALID,
    TIMESTAMP_DATE_MISMATCH, TIMESTAMP_FUTURE, TIMESTAMP_STALE, DUPLICATE_TRAIN,
}

/** A station-referenced train observation is NOT a passenger position or boarding confirmation. */
class SeoulSubwayTrainObservation internal constructor(
    @get:JsonIgnore val trainNumber: String,
    val line: SeoulSubwayObservationLine,
    val stationId: String,
    val destinationStationId: String,
    /** 0: up/inner, 1: down/outer. Neither code identifies a branch or terminal by itself. */
    val direction: Int,
    /** 0: entering, 1: arrived, 2: departed, 3: previous-station departed. */
    val state: Int,
    /** 0: regular, 1: express, 7: limited express. */
    val service: Int,
    val lastTrain: Boolean,
    val sourceUpdatedAt: Instant,
    val receivedAt: Instant,
    val validUntil: Instant,
    val stationName: String? = null,
    val destinationStationName: String? = null,
) {
    override fun toString() = "SeoulSubwayTrainObservation(REDACTED)"
}

class SeoulSubwayTrainObservationBatch(
    val status: SeoulSubwayTrainStatus,
    @get:JsonIgnore val observations: List<SeoulSubwayTrainObservation> = emptyList(),
    val rejectionCounts: Map<SeoulSubwayTrainRejection, Int> = emptyMap(),
) {
    val observationCount: Int get() = observations.size
    val rejectedCount: Int get() = rejectionCounts.values.sum()
    val passengerTrainAssociation: Boolean = false
    val stationOrderCrosswalkVerified: Boolean = false
    val passengerGeographicPositionAvailable: Boolean = false
    override fun toString() = "SeoulSubwayTrainObservationBatch(status=$status, count=$observationCount, rejected=$rejectedCount)"
}
