package com.noLate.journey.application

import com.noLate.journey.domain.*
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import java.time.Duration
import java.time.Instant

/** Pure, unregistered adapter: no provider fetch, clock access, persistence, UI, or dispatch side effects. */
class JourneyTransitObservationAdapter(private val policy: JourneyTransitObservationPolicy = JourneyTransitObservationPolicy()) {
    fun normalize(arrival: TransitArrivalDto, evaluatedAt: Instant): JourneyTransitObservation {
        val provider = when (arrival.provider) {
            "seoul-bus" -> JourneyTransitProvider.SEOUL_BUS
            "seoul-openapi" -> JourneyTransitProvider.SEOUL_SUBWAY
            "tago" -> JourneyTransitProvider.TAGO_BUS
            else -> JourneyTransitProvider.UNKNOWN
        }
        val kind = when (arrival.kind) {
            "BUS" -> JourneyTransitKind.BUS
            "SUBWAY" -> JourneyTransitKind.SUBWAY
            else -> JourneyTransitKind.UNKNOWN
        }
        val reasons = linkedSetOf<JourneyTransitObservationReason>()
        val qualities = mutableSetOf<JourneyTransitObservationQuality>()
        fun reject(reason: JourneyTransitObservationReason, quality: JourneyTransitObservationQuality) {
            reasons += reason; qualities += quality
        }
        val supported = when (provider) {
            JourneyTransitProvider.SEOUL_BUS, JourneyTransitProvider.TAGO_BUS -> kind == JourneyTransitKind.BUS
            JourneyTransitProvider.SEOUL_SUBWAY -> kind == JourneyTransitKind.SUBWAY
            JourneyTransitProvider.UNKNOWN -> false
        }
        if (!supported) reject(JourneyTransitObservationReason.UNSUPPORTED_PROVIDER_OR_KIND, JourneyTransitObservationQuality.UNAVAILABLE)
        if (!arrival.realtime) reject(JourneyTransitObservationReason.NOT_REALTIME, JourneyTransitObservationQuality.UNAVAILABLE)
        fun parse(value: String?, missing: JourneyTransitObservationReason, invalid: JourneyTransitObservationReason): Instant? {
            if (value.isNullOrBlank()) {
                reject(missing, JourneyTransitObservationQuality.UNVERIFIED)
                return null
            }
            val parsed = value.takeIf { it.length <= 64 }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (parsed == null) reject(invalid, JourneyTransitObservationQuality.INVALID)
            return parsed
        }
        fun plus(base: Instant, seconds: Long): Instant? = runCatching { base.plusSeconds(seconds) }.getOrNull().also {
            if (it == null) reject(JourneyTransitObservationReason.TIMESTAMP_OUT_OF_RANGE, JourneyTransitObservationQuality.INVALID)
        }
        val source = parse(arrival.sourceUpdatedAt, JourneyTransitObservationReason.SOURCE_TIMESTAMP_MISSING, JourneyTransitObservationReason.SOURCE_TIMESTAMP_INVALID)
        val receipt = parse(arrival.observedAt, JourneyTransitObservationReason.RECEIPT_TIMESTAMP_MISSING, JourneyTransitObservationReason.RECEIPT_TIMESTAMP_INVALID)
        val expected = parse(arrival.expectedAt, JourneyTransitObservationReason.EXPECTED_AT_MISSING, JourneyTransitObservationReason.EXPECTED_AT_INVALID)
        if (arrival.freshnessEvidence != TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP) {
            reject(JourneyTransitObservationReason.SOURCE_EVIDENCE_RECEIPT_ONLY, JourneyTransitObservationQuality.UNVERIFIED)
        }
        // The existing TAGO DTO has no provider timestamp. A caller-supplied enum must not invent that capability.
        if (provider == JourneyTransitProvider.TAGO_BUS) {
            reject(JourneyTransitObservationReason.SOURCE_TIMESTAMP_UNSUPPORTED_FOR_PROVIDER, JourneyTransitObservationQuality.UNVERIFIED)
        }
        val futureLimit = plus(evaluatedAt, policy.maxClockSkewSeconds)
        val sourceExpiry = source?.let { plus(it, policy.maxSourceAgeSeconds) }
        if (receipt != null && futureLimit != null && receipt > futureLimit) {
            reject(JourneyTransitObservationReason.RECEIPT_TIMESTAMP_IN_FUTURE, JourneyTransitObservationQuality.INVALID)
        }
        if (source != null) {
            if (futureLimit != null && source > futureLimit) reject(JourneyTransitObservationReason.SOURCE_TIMESTAMP_IN_FUTURE, JourneyTransitObservationQuality.INVALID)
            if (sourceExpiry != null && evaluatedAt >= sourceExpiry) reject(JourneyTransitObservationReason.SOURCE_TIMESTAMP_EXPIRED, JourneyTransitObservationQuality.STALE)
            val receiptLimit = receipt?.let { plus(it, policy.maxClockSkewSeconds) }
            if (receiptLimit != null && source > receiptLimit) reject(JourneyTransitObservationReason.SOURCE_TIMESTAMP_AFTER_RECEIPT, JourneyTransitObservationQuality.INVALID)
        }
        if (expected != null) {
            if (expected <= evaluatedAt) reject(JourneyTransitObservationReason.EXPECTED_AT_EXPIRED, JourneyTransitObservationQuality.STALE)
            if (source != null) {
                if (expected < source) reject(JourneyTransitObservationReason.EXPECTED_AT_BEFORE_SOURCE, JourneyTransitObservationQuality.INVALID)
                val horizon = plus(source, policy.maxExpectedLeadSeconds)
                if (horizon != null && expected > horizon) reject(JourneyTransitObservationReason.EXPECTED_AT_OUT_OF_HORIZON, JourneyTransitObservationQuality.INVALID)
            }
        }
        arrival.waitSeconds?.let { wait ->
            if (wait < 0 || wait.toLong() > policy.maxExpectedLeadSeconds) {
                reject(JourneyTransitObservationReason.WAIT_SECONDS_INVALID, JourneyTransitObservationQuality.INVALID)
            } else if (source != null && expected != null && plus(source, wait.toLong()) != expected) {
                reject(JourneyTransitObservationReason.EXPECTED_AT_WAIT_MISMATCH, JourneyTransitObservationQuality.INVALID)
            }
        }
        val incoming = if (provider == JourneyTransitProvider.TAGO_BUS && kind == JourneyTransitKind.BUS) {
            arrival.remainingStops?.takeIf { it in 0..500 }.also {
                if (arrival.remainingStops != null && it == null) reject(JourneyTransitObservationReason.INCOMING_STOPS_INVALID, JourneyTransitObservationQuality.INVALID)
            }
        } else null
        val quality = listOf(
            JourneyTransitObservationQuality.INVALID, JourneyTransitObservationQuality.UNAVAILABLE,
            JourneyTransitObservationQuality.UNVERIFIED, JourneyTransitObservationQuality.STALE,
        ).firstOrNull { it in qualities } ?: JourneyTransitObservationQuality.FRESH_WAITING_INFORMATION
        val usable = quality == JourneyTransitObservationQuality.FRESH_WAITING_INFORMATION
        val direction = boundedLabel(arrival.direction)
        val directionEvidence = when {
            provider == JourneyTransitProvider.TAGO_BUS && direction != null -> JourneyTransitDirectionEvidence.ROUTE_TYPE_NOT_DIRECTION
            supported && direction != null -> JourneyTransitDirectionEvidence.PROVIDER_LABEL_UNMATCHED
            else -> JourneyTransitDirectionEvidence.UNKNOWN
        }
        val duration = expected?.takeIf { usable }?.let { Duration.between(evaluatedAt, it) }
        return JourneyTransitObservation(
            provider = provider, kind = kind, evaluatedAt = evaluatedAt,
            sourceUpdatedAt = source, receivedAt = receipt, expectedAt = expected,
            inputFreshnessEvidence = arrival.freshnessEvidence, quality = quality, qualityReasons = reasons.toList(),
            validUntil = if (usable && sourceExpiry != null && expected != null) minOf(sourceExpiry, expected) else null,
            remainingWaitSeconds = duration?.let { it.seconds + if (it.nano > 0) 1 else 0 },
            incomingVehicleStopsAway = incoming,
            routeLabel = boundedLabel(arrival.routeName) ?: boundedLabel(arrival.lineName),
            stopLabel = boundedLabel(arrival.stationName), cityCode = boundedLabel(arrival.cityCode),
            nodeId = boundedLabel(arrival.nodeId), arsId = boundedLabel(arrival.arsId),
            directionLabel = direction.takeIf { directionEvidence == JourneyTransitDirectionEvidence.PROVIDER_LABEL_UNMATCHED },
            routeTypeLabel = direction.takeIf { provider == JourneyTransitProvider.TAGO_BUS },
            directionEvidence = directionEvidence,
            boardingBlockedReasons = buildList {
                add(JourneyTransitBoardingBlocker.ROUTE_ID_NOT_IN_INPUT)
                add(JourneyTransitBoardingBlocker.VEHICLE_ID_NOT_IN_INPUT)
                add(JourneyTransitBoardingBlocker.SELECTED_LEG_NOT_BOUND)
                add(JourneyTransitBoardingBlocker.DIRECTION_NOT_MATCHED)
                add(JourneyTransitBoardingBlocker.LOCATION_EVIDENCE_REQUIRED)
                if (!usable) add(JourneyTransitBoardingBlocker.TIMING_NOT_VERIFIED)
            },
        )
    }

    private fun boundedLabel(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)
    }
}
