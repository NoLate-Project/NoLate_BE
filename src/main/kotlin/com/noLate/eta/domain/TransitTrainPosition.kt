package com.noLate.eta.domain

import java.time.Instant

enum class TransitTrainPositionState { ENTERING, ARRIVED, DEPARTED, PREVIOUS_STATION_DEPARTED }

/** A provider status snapshot, NOT the exact second at which a train arrived/departed. */
data class TransitTrainPosition(
    val line: SeoulSubwayLine,
    val trainNumber: String,
    val stationName: String,
    val directionCode: String,
    val terminalName: String,
    val serviceClass: TransitServiceClass,
    val state: TransitTrainPositionState,
    val sourceUpdatedAt: Instant,
    val receivedAt: Instant,
) {
    init {
        require(trainNumber.matches(Regex("[A-Za-z0-9]{1,16}")))
        require(stationName.isNotBlank() && stationName.length <= 100 && terminalName.isNotBlank() && terminalName.length <= 100)
        require(directionCode in setOf("UP", "DOWN"))
    }
    fun isFresh(maxAgeSeconds: Long = 120): Boolean =
        sourceUpdatedAt >= receivedAt.minusSeconds(maxAgeSeconds) && sourceUpdatedAt <= receivedAt.plusSeconds(5)
}

data class TransitTrainPositionSnapshot(
    val line: SeoulSubwayLine,
    val receivedAt: Instant,
    val positions: List<TransitTrainPosition>,
    val rejectedRows: Int = 0,
)
