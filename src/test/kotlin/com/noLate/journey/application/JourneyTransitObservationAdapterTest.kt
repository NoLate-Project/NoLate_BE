package com.noLate.journey.application

import com.noLate.journey.domain.*
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class JourneyTransitObservationAdapterTest {
    private val now = Instant.parse("2026-10-04T03:00:30Z")
    private val source = now.minusSeconds(30)
    private val adapter = JourneyTransitObservationAdapter()
    private fun arrival() = TransitArrivalDto(
        provider = "seoul-bus", kind = "BUS", routeName = "Synthetic 100", stationName = "Fixture stop",
        direction = "Fixture destination", waitSeconds = 120, waitMinutes = 2,
        expectedAt = source.plusSeconds(120).toString(), observedAt = now.toString(),
        sourceUpdatedAt = source.toString(), freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
        arsId = "fixture-stop", arrivalStatus = TransitArrivalStatus.APPROACHING,
    )

    @Test fun `source anchored expected time produces 90 seconds not receipt anchored 120`() {
        val observation = adapter.normalize(arrival(), now)
        assertThat(observation.schemaVersion).isEqualTo(1)
        assertThat(observation.quality).isEqualTo(JourneyTransitObservationQuality.FRESH_WAITING_INFORMATION)
        assertThat(observation.qualityReasons).isEmpty()
        assertThat(observation.waitingObservationUsable).isTrue()
        assertThat(observation.sourceUpdatedAt).isEqualTo(source)
        assertThat(observation.receivedAt).isEqualTo(now)
        assertThat(observation.expectedAt).isEqualTo(source.plusSeconds(120))
        assertThat(observation.remainingWaitSeconds).isEqualTo(90)
        assertThat(observation.validUntil).isEqualTo(source.plusSeconds(120))
    }

    @Test fun `validUntil is source lifetime even when predicted vehicle arrival is much later`() {
        val observation = adapter.normalize(arrival().copy(waitSeconds = 600, expectedAt = source.plusSeconds(600).toString()), now)
        assertThat(observation.remainingWaitSeconds).isEqualTo(570)
        assertThat(observation.validUntil).isEqualTo(source.plusSeconds(120))
    }

    @Test fun `new receipt cannot refresh old source even when ETA remains in future`() {
        val old = now.minusSeconds(121)
        val observation = adapter.normalize(arrival().copy(sourceUpdatedAt = old.toString(), waitSeconds = 600, expectedAt = old.plusSeconds(600).toString()), now)
        assertThat(observation.quality).isEqualTo(JourneyTransitObservationQuality.STALE)
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.SOURCE_TIMESTAMP_EXPIRED)
        assertThat(observation.sourceUpdatedAt).isEqualTo(old)
        assertThat(observation.validUntil).isNull()
        assertThat(observation.remainingWaitSeconds).isNull()
    }

    @Test fun `source lifetime and predicted arrival are exclusive boundaries`() {
        val candidate = arrival().copy(waitSeconds = 600, expectedAt = source.plusSeconds(600).toString())
        assertThat(adapter.normalize(candidate, source.plusSeconds(120).minusNanos(1)).waitingObservationUsable).isTrue()
        assertThat(adapter.normalize(candidate, source.plusSeconds(120)).qualityReasons).contains(JourneyTransitObservationReason.SOURCE_TIMESTAMP_EXPIRED)
        val arriving = arrival().copy(waitSeconds = 30, expectedAt = now.toString())
        assertThat(adapter.normalize(arriving, now).qualityReasons).contains(JourneyTransitObservationReason.EXPECTED_AT_EXPIRED)
    }

    @Test fun `missing source and malformed source never use receipt or realtime as source proof`() {
        for (missing in listOf(null, "", "bad-time")) {
            val observation = adapter.normalize(arrival().copy(sourceUpdatedAt = missing), now)
            assertThat(observation.waitingObservationUsable).isFalse()
            assertThat(observation.sourceUpdatedAt).isNull()
            assertThat(observation.validUntil).isNull()
            assertThat(observation.receivedAt).isEqualTo(now)
        }
    }

    @Test fun `timestamp alone cannot override receipt only evidence`() {
        val observation = adapter.normalize(arrival().copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY), now)
        assertThat(observation.sourceUpdatedAt).isEqualTo(source)
        assertThat(observation.quality).isEqualTo(JourneyTransitObservationQuality.UNVERIFIED)
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.SOURCE_EVIDENCE_RECEIPT_ONLY)
    }

    @Test fun `future source and receipt beyond bounded clock skew reject`() {
        val future = now.plusSeconds(6)
        val observation = adapter.normalize(arrival().copy(sourceUpdatedAt = future.toString(), expectedAt = future.plusSeconds(120).toString()), now)
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.SOURCE_TIMESTAMP_IN_FUTURE)
        assertThat(adapter.normalize(arrival().copy(observedAt = future.toString()), now).qualityReasons)
            .contains(JourneyTransitObservationReason.RECEIPT_TIMESTAMP_IN_FUTURE)
    }

    @Test fun `source cannot be later than its receipt outside clock skew`() {
        val observation = adapter.normalize(arrival().copy(observedAt = source.minusSeconds(6).toString()), now)
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.SOURCE_TIMESTAMP_AFTER_RECEIPT)
        assertThat(observation.waitingObservationUsable).isFalse()
    }

    @Test fun `missing and malformed receipt stay unverified or invalid rather than synthesized`() {
        assertThat(adapter.normalize(arrival().copy(observedAt = null), now).qualityReasons)
            .contains(JourneyTransitObservationReason.RECEIPT_TIMESTAMP_MISSING)
        assertThat(adapter.normalize(arrival().copy(observedAt = "bad"), now).qualityReasons)
            .contains(JourneyTransitObservationReason.RECEIPT_TIMESTAMP_INVALID)
    }

    @Test fun `missing malformed and elapsed expected time never fallback to rounded wait minutes`() {
        for (expected in listOf(null, "bad", now.minusSeconds(1).toString())) {
            val observation = adapter.normalize(arrival().copy(expectedAt = expected), now)
            assertThat(observation.waitingObservationUsable).isFalse()
            assertThat(observation.remainingWaitSeconds).isNull()
        }
    }

    @Test fun `receipt rebased expected time disagrees with source plus wait and is rejected`() {
        val observation = adapter.normalize(arrival().copy(expectedAt = now.plusSeconds(120).toString()), now)
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.EXPECTED_AT_WAIT_MISMATCH)
        assertThat(observation.waitingObservationUsable).isFalse()
    }

    @Test fun `negative wait source inverted ETA and excessive horizon reject`() {
        assertThat(adapter.normalize(arrival().copy(waitSeconds = -1), now).qualityReasons)
            .contains(JourneyTransitObservationReason.WAIT_SECONDS_INVALID)
        assertThat(adapter.normalize(arrival().copy(expectedAt = source.minusSeconds(1).toString()), now).qualityReasons)
            .contains(JourneyTransitObservationReason.EXPECTED_AT_BEFORE_SOURCE)
        assertThat(adapter.normalize(arrival().copy(waitSeconds = null, expectedAt = source.plusSeconds(3601).toString()), now).qualityReasons)
            .contains(JourneyTransitObservationReason.EXPECTED_AT_OUT_OF_HORIZON)
    }

    @Test fun `absolute expected time is usable without wait seconds but never reconstructed from wait minutes`() {
        val observation = adapter.normalize(arrival().copy(waitSeconds = null, waitMinutes = 999), now)
        assertThat(observation.waitingObservationUsable).isTrue()
        assertThat(observation.remainingWaitSeconds).isEqualTo(90)
        assertThat(adapter.normalize(arrival().copy(expectedAt = null), now).waitingObservationUsable).isFalse()
    }

    @Test fun `TAGO previous stop count is incoming vehicle distance and route type is not direction`() {
        val observation = adapter.normalize(arrival().copy(
            provider = "tago", direction = "일반버스", remainingStops = 1,
            sourceUpdatedAt = null, freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
        ), now)
        assertThat(observation.incomingVehicleStopsAway).isEqualTo(1)
        assertThat(observation.remainingStopsToAlight).isNull()
        assertThat(observation.routeTypeLabel).isEqualTo("일반버스")
        assertThat(observation.directionLabel).isNull()
        assertThat(observation.directionEvidence).isEqualTo(JourneyTransitDirectionEvidence.ROUTE_TYPE_NOT_DIRECTION)
        assertThat(observation.quality).isEqualTo(JourneyTransitObservationQuality.UNVERIFIED)
    }

    @Test fun `TAGO cannot invent supported source capability by providing enum and timestamp`() {
        val observation = adapter.normalize(arrival().copy(provider = "tago"), now)
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.SOURCE_TIMESTAMP_UNSUPPORTED_FOR_PROVIDER)
        assertThat(observation.waitingObservationUsable).isFalse()
    }

    @Test fun `unknown stop-count semantics and invalid incoming stop counts never become onboard data`() {
        assertThat(adapter.normalize(arrival().copy(remainingStops = 1), now).incomingVehicleStopsAway).isNull()
        for (invalid in listOf(-1, 501, Int.MAX_VALUE)) {
            val observation = adapter.normalize(arrival().copy(provider = "tago", remainingStops = invalid), now)
            assertThat(observation.incomingVehicleStopsAway).isNull()
            assertThat(observation.remainingStopsToAlight).isNull()
            assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.INCOMING_STOPS_INVALID)
        }
        assertThat(adapter.normalize(arrival().copy(provider = "tago", remainingStops = 0), now).incomingVehicleStopsAway).isZero()
    }

    @Test fun `labels names arrived status and IDs of queried stop cannot prove route vehicle or boarding`() {
        for (provider in listOf("seoul-bus", "seoul-openapi", "tago", "unknown")) {
            val observation = adapter.normalize(arrival().copy(
                provider = provider, kind = if (provider == "seoul-openapi") "SUBWAY" else "BUS",
                arrivalStatus = TransitArrivalStatus.ARRIVED, cityCode = "fixture-city", nodeId = "fixture-node",
            ), now)
            assertThat(observation.confirmedBoarding).isFalse()
            assertThat(observation.vehicleTrackingEnabled).isFalse()
            assertThat(observation.guidanceAnnouncementEnabled).isFalse()
            assertThat(observation.providerRouteId).isNull()
            assertThat(observation.vehicleId).isNull()
            assertThat(observation.remainingStopsToAlight).isNull()
            assertThat(observation.boardingBlockedReasons).contains(
                JourneyTransitBoardingBlocker.ROUTE_ID_NOT_IN_INPUT, JourneyTransitBoardingBlocker.VEHICLE_ID_NOT_IN_INPUT,
                JourneyTransitBoardingBlocker.SELECTED_LEG_NOT_BOUND, JourneyTransitBoardingBlocker.DIRECTION_NOT_MATCHED,
                JourneyTransitBoardingBlocker.LOCATION_EVIDENCE_REQUIRED,
            )
        }
        assertThat(adapter.normalize(arrival(), now).directionEvidence).isEqualTo(JourneyTransitDirectionEvidence.PROVIDER_LABEL_UNMATCHED)
    }

    @Test fun `unsupported provider-kind combinations and nonrealtime observations stay unusable`() {
        for (candidate in listOf(arrival().copy(provider = "unknown"), arrival().copy(kind = "WALK"),
            arrival().copy(provider = "seoul-openapi"), arrival().copy(realtime = false))) {
            assertThat(adapter.normalize(candidate, now).quality).isEqualTo(JourneyTransitObservationQuality.UNAVAILABLE)
        }
    }

    @Test fun `normalizing later preserves original timestamps and never renews validity`() {
        val first = adapter.normalize(arrival(), now)
        val later = adapter.normalize(arrival(), now.plusSeconds(30))
        assertThat(later.sourceUpdatedAt).isEqualTo(first.sourceUpdatedAt)
        assertThat(later.expectedAt).isEqualTo(first.expectedAt)
        assertThat(later.validUntil).isEqualTo(first.validUntil)
        assertThat(later.remainingWaitSeconds).isEqualTo(60)
        assertThat(adapter.normalize(arrival(), now.plusSeconds(90)).waitingObservationUsable).isFalse()
    }

    @Test fun `extreme timestamps and oversized labels fail safely without overflow or truncation inference`() {
        val observation = adapter.normalize(arrival().copy(sourceUpdatedAt = Instant.MAX.toString(), expectedAt = Instant.MAX.toString(),
            routeName = "x".repeat(129), lineName = null, direction = "bad\nlabel"), now)
        assertThat(observation.waitingObservationUsable).isFalse()
        assertThat(observation.qualityReasons).contains(JourneyTransitObservationReason.TIMESTAMP_OUT_OF_RANGE)
        assertThat(observation.routeLabel).isNull()
        assertThat(observation.directionLabel).isNull()
        assertThat(adapter.normalize(arrival(), Instant.MAX).waitingObservationUsable).isFalse()
    }

    @Test fun `invalid policy bounds cannot silently widen timing trust`() {
        assertThatThrownBy { JourneyTransitObservationPolicy(maxSourceAgeSeconds = 121) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JourneyTransitObservationPolicy(maxClockSkewSeconds = 31) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JourneyTransitObservationPolicy(maxExpectedLeadSeconds = 3601) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
