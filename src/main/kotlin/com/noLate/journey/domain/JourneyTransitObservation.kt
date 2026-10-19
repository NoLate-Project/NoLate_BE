package com.noLate.journey.domain

import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import java.time.Instant

enum class JourneyTransitProvider { SEOUL_BUS, SEOUL_SUBWAY, TAGO_BUS, UNKNOWN }
enum class JourneyTransitKind { BUS, SUBWAY, UNKNOWN }
enum class JourneyTransitObservationQuality { FRESH_WAITING_INFORMATION, UNVERIFIED, STALE, INVALID, UNAVAILABLE }
enum class JourneyTransitObservationReason {
    UNSUPPORTED_PROVIDER_OR_KIND, NOT_REALTIME,
    RECEIPT_TIMESTAMP_MISSING, RECEIPT_TIMESTAMP_INVALID, RECEIPT_TIMESTAMP_IN_FUTURE,
    SOURCE_EVIDENCE_RECEIPT_ONLY, SOURCE_TIMESTAMP_UNSUPPORTED_FOR_PROVIDER,
    SOURCE_TIMESTAMP_MISSING, SOURCE_TIMESTAMP_INVALID, SOURCE_TIMESTAMP_IN_FUTURE,
    SOURCE_TIMESTAMP_AFTER_RECEIPT, SOURCE_TIMESTAMP_EXPIRED, TIMESTAMP_OUT_OF_RANGE,
    EXPECTED_AT_MISSING, EXPECTED_AT_INVALID, EXPECTED_AT_EXPIRED, EXPECTED_AT_BEFORE_SOURCE,
    EXPECTED_AT_OUT_OF_HORIZON, EXPECTED_AT_WAIT_MISMATCH, WAIT_SECONDS_INVALID, INCOMING_STOPS_INVALID,
}
enum class JourneyTransitDirectionEvidence { PROVIDER_LABEL_UNMATCHED, ROUTE_TYPE_NOT_DIRECTION, UNKNOWN }
enum class JourneyTransitBoardingBlocker {
    ROUTE_ID_NOT_IN_INPUT, VEHICLE_ID_NOT_IN_INPUT, SELECTED_LEG_NOT_BOUND,
    DIRECTION_NOT_MATCHED, LOCATION_EVIDENCE_REQUIRED, TIMING_NOT_VERIFIED,
}

/** These are adapter policy bounds, not an assertion of a provider SLA or live-data validation. */
data class JourneyTransitObservationPolicy(
    val maxSourceAgeSeconds: Long = 120,
    val maxClockSkewSeconds: Long = 5,
    val maxExpectedLeadSeconds: Long = 3600,
) {
    init {
        require(maxSourceAgeSeconds in 1..120)
        require(maxClockSkewSeconds in 0..30)
        require(maxExpectedLeadSeconds in 1..3600)
    }
}

/**
 * An arrival at a queried stop is not an observation of the user's vehicle.
 * Constant negative capabilities cannot be promoted by copying this DTO.
 */
data class JourneyTransitObservation(
    val provider: JourneyTransitProvider,
    val kind: JourneyTransitKind,
    val evaluatedAt: Instant,
    val sourceUpdatedAt: Instant?,
    val receivedAt: Instant?,
    val expectedAt: Instant?,
    val inputFreshnessEvidence: TransitArrivalFreshnessEvidence,
    val quality: JourneyTransitObservationQuality,
    val qualityReasons: List<JourneyTransitObservationReason>,
    /** Exclusive upper bound. Re-evaluate the original source evidence, never rebase on receipt. */
    val validUntil: Instant?,
    val remainingWaitSeconds: Long?,
    val incomingVehicleStopsAway: Int?,
    val routeLabel: String?,
    val stopLabel: String?,
    val cityCode: String?,
    val nodeId: String?,
    val arsId: String?,
    val directionLabel: String?,
    val routeTypeLabel: String?,
    val directionEvidence: JourneyTransitDirectionEvidence,
    val boardingBlockedReasons: List<JourneyTransitBoardingBlocker>,
) {
    val schemaVersion: Int get() = 1
    val waitingObservationUsable: Boolean get() = quality == JourneyTransitObservationQuality.FRESH_WAITING_INFORMATION
    val providerRouteId: String? get() = null
    val vehicleId: String? get() = null
    val remainingStopsToAlight: Int? get() = null
    val confirmedBoarding: Boolean get() = false
    val vehicleTrackingEnabled: Boolean get() = false
    val guidanceAnnouncementEnabled: Boolean get() = false
}
