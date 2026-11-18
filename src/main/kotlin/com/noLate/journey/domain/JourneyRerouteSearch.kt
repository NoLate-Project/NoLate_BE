package com.noLate.journey.domain

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import java.time.Instant

/** A fresh device fix requests candidates; it never changes the active route by itself. */
data class JourneyRerouteSearchCommand(
    val generation: Long,
    val routeRevision: Long,
    val clientOwnerId: String,
    val routeInputDigest: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAt: Instant,
) {
    fun fence() = AutomaticJourneyStopCommand(generation, routeRevision, clientOwnerId, routeInputDigest)
}

object JourneyRerouteFixPolicy {
    fun requireFresh(command: JourneyRerouteSearchCommand, now: Instant) {
        if (!command.latitude.isFinite() || command.latitude !in -90.0..90.0 ||
            !command.longitude.isFinite() || command.longitude !in -180.0..180.0 ||
            !command.accuracyMeters.isFinite() || command.accuracyMeters !in 0.0..25.0 ||
            command.observedAt.isBefore(now.minusSeconds(15)) || command.observedAt.isAfter(now.plusSeconds(2))) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "현재 위치를 다시 확인해 주세요.")
        }
    }
}
