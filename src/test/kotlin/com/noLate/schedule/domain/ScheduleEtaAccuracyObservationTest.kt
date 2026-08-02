package com.noLate.schedule.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ScheduleEtaAccuracyObservationTest {

    @Test
    fun `검증된 도착만으로 duration 기반 ETA를 정확도 표본으로 승격할 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) {
            verifiedEligibleObservation(EtaPredictionBasis.DEPARTURE_ANCHORED_DURATION)
        }
    }

    @Test
    fun `검증된 도착의 provider absolute ETA는 도메인 eligibility 경계를 통과한다`() {
        assertDoesNotThrow {
            verifiedEligibleObservation(EtaPredictionBasis.PROVIDER_ABSOLUTE)
        }
    }

    private fun verifiedEligibleObservation(
        predictionBasis: EtaPredictionBasis,
    ): ScheduleEtaAccuracyObservation {
        val departedAt = Instant.parse("2026-08-01T03:00:00Z")
        val arrivalAt = Instant.parse("2026-08-01T04:00:00Z")
        return ScheduleEtaAccuracyObservation(
            scheduleId = 1L,
            memberId = 2L,
            pushJobId = 3L,
            departedAt = departedAt,
            predictionEvaluatedAt = departedAt.minusSeconds(60),
            predictedArrivalAt = arrivalAt,
            recommendedDepartureAt = departedAt,
            targetArrivalAt = arrivalAt,
            actualArrivalAt = arrivalAt,
            observationVerification = ScheduleArrivalObservationVerification.VERIFIED_GEOFENCE,
            observationSource = ScheduleArrivalObservationSource.GEOFENCE,
            precisionSeconds = 30,
            adjustmentSeconds = null,
            clientAppVersion = "1.0.0",
            clientBuildVersion = "100",
            backendCohortVersion = "api-2026.08.01",
            eligibilityPolicyVersion = EtaAccuracyEligibilityPolicyVersion.SELF_REPORT_DIAGNOSTIC_V2,
            etaSource = TrafficSource.LIVE_PROVIDER,
            etaStale = false,
            travelMinutes = 60,
            predictionBasis = predictionBasis,
            travelMode = ScheduleTravelMode.CAR,
            providerId = EtaProviderId.TMAP,
            algorithmVersion = EtaAlgorithmVersion.ROAD_LIVE_V1,
            providerFetchedAt = departedAt.minusSeconds(60),
            predictedOnTime = true,
            actualOnTime = true,
            onTimeOutcome = EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_ON_TIME,
            departureOffsetSeconds = 0,
            actualTravelSeconds = 3_600,
            reportDelaySeconds = 1,
            accuracyEligible = true,
            accuracyEligibilityReason = EtaAccuracyEligibilityReason.ELIGIBLE,
            signedErrorSeconds = 0,
            absoluteErrorSeconds = 0,
            recordedAt = arrivalAt.plusSeconds(1),
        )
    }
}
