package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.schedule.domain.EtaAccuracyEligibilityPolicyVersion
import com.noLate.schedule.domain.EtaAccuracyEligibilityReason
import com.noLate.schedule.domain.EtaAlgorithmVersion
import com.noLate.schedule.domain.EtaOnTimeOutcome
import com.noLate.schedule.domain.EtaPredictionBasis
import com.noLate.schedule.domain.EtaProviderId
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleArrivalObservationSource
import com.noLate.schedule.domain.ScheduleArrivalObservationVerification
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleEtaAccuracyObservation
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleEtaAccuracyObservationRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@ExtendWith(MockitoExtension::class)
class ScheduleEtaAccuracyServiceTest {
    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var departureStatusRepository: ScheduleDepartureStatusRepository
    @Mock lateinit var observationRepository: ScheduleEtaAccuracyObservationRepository

    @Test
    fun `user now preserves diagnostics but never enters the verified accuracy cohort`() {
        val registry = SimpleMeterRegistry()
        val status = measuredStatus()
        stubAccessibleStatus(status)
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(
            service(registry),
            CLIENT_ARRIVED_AT,
            clientAppVersion = "1.2.0",
            clientBuildVersion = "42",
        )

        assertEquals(PREDICTED_ARRIVAL_AT, result.predictedArrivalAt)
        assertEquals(CLIENT_ARRIVED_AT, result.actualArrivalAt)
        assertEquals(ScheduleArrivalObservationSource.USER_NOW, result.observationSource)
        assertEquals(
            ScheduleArrivalObservationVerification.UNVERIFIED_CLIENT,
            result.observationVerification,
        )
        assertEquals(USER_NOW_PRECISION_SECONDS, result.precisionSeconds)
        assertEquals(null, result.adjustmentSeconds)
        assertEquals("1.2.0", result.clientAppVersion)
        assertEquals("42", result.clientBuildVersion)
        assertEquals("unversioned", result.backendCohortVersion)
        assertEquals(
            EtaAccuracyEligibilityPolicyVersion.SELF_REPORT_DIAGNOSTIC_V2,
            result.eligibilityPolicyVersion,
        )
        assertEquals(SERVER_RECORDED_AT, result.recordedAt)
        assertEquals(300, result.signedErrorSeconds)
        assertEquals(300, result.absoluteErrorSeconds)
        assertEquals(ScheduleTravelMode.TRANSIT, result.travelMode)
        assertEquals(EtaPredictionBasis.PROVIDER_ABSOLUTE, result.predictionBasis)
        assertEquals(EtaProviderId.ODSAY_TRANSIT, result.providerId)
        assertEquals(EtaAlgorithmVersion.TRANSIT_REALTIME_V3, result.algorithmVersion)
        assertEquals(PREDICTION_EVALUATED_AT, result.providerFetchedAt)
        assertEquals(SCHEDULE_AT, result.targetArrivalAt)
        assertEquals(true, result.predictedOnTime)
        assertEquals(true, result.actualOnTime)
        assertEquals(EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_ON_TIME, result.onTimeOutcome)
        assertEquals(0, result.departureOffsetSeconds)
        assertEquals(60 * 60, result.actualTravelSeconds)
        assertEquals(5 * 60, result.reportDelaySeconds)
        assertFalse(result.accuracyEligible)
        assertEquals(EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW, result.accuracyEligibilityReason)
        assertEquals(
            0L,
            registry.find("nolate.eta.arrival.error.seconds").summaries().sumOf { it.count() },
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.observation.eligibility")
                .tag("reason", "unverified_user_now")
                .counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.observation.funnel")
                .tag("stage", "response_stored")
                .counter().count(),
        )
        assertEquals(SERVER_RECORDED_AT, status.etaObservationRespondedAt)
    }

    @Test
    fun `unverified predicted-on-time actual-late sample cannot emit false-safe score metrics`() {
        val registry = SimpleMeterRegistry()
        val status = measuredStatus()
        stubAccessibleStatus(status)
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(service(registry), SCHEDULE_AT.plusSeconds(1))

        assertEquals(true, result.predictedOnTime)
        assertEquals(false, result.actualOnTime)
        assertEquals(EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_LATE, result.onTimeOutcome)
        assertFalse(result.accuracyEligible)
        assertEquals(EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW, result.accuracyEligibilityReason)
        assertEquals(
            0.0,
            registry.find("nolate.eta.on.time.outcomes").counters().sumOf { it.count() },
        )
    }

    @Test
    fun `later schedule edits cannot change the frozen target or false-safe classification`() {
        val status = measuredStatus()
        stubAccessibleStatus(status, currentScheduleAt = SCHEDULE_AT.plusSeconds(3_600))
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(service(), SCHEDULE_AT.plusSeconds(1))

        assertEquals(SCHEDULE_AT, result.targetArrivalAt)
        assertEquals(EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_LATE, result.onTimeOutcome)
    }

    @Test
    fun `unverified source exclusion takes precedence over ETA-quality diagnostics`() {
        val registry = SimpleMeterRegistry()
        val status = measuredStatus(
            departedAt = DEPARTED_AT.plusSeconds(20 * 60),
            stale = false,
        )
        stubAccessibleStatus(status)
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(service(registry), CLIENT_ARRIVED_AT)

        assertFalse(result.accuracyEligible)
        assertEquals(EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW, result.accuracyEligibilityReason)
        assertEquals(20 * 60, result.departureOffsetSeconds)
        assertEquals(
            0L,
            registry.find("nolate.eta.arrival.error.seconds").summaries().sumOf { it.count() },
        )
        assertEquals(
            0.0,
            registry.find("nolate.eta.on.time.outcomes").counters().sumOf { it.count() },
        )
    }

    @Test
    fun `duration prediction is anchored at immutable actual departure`() {
        val status = measuredStatus(absolutePrediction = null)
        stubAccessibleStatus(status)
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(service(), CLIENT_ARRIVED_AT)

        assertEquals(EtaPredictionBasis.DEPARTURE_ANCHORED_DURATION, result.predictionBasis)
        assertEquals(DEPARTED_AT.plusSeconds(55 * 60), result.predictedArrivalAt)
    }

    @Test
    fun `prediction older than bounded freshness is retained but ineligible`() {
        val status = measuredStatus(evaluatedAt = DEPARTED_AT.minusSeconds(61 * 60))
        stubAccessibleStatus(status)
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(service(), CLIENT_ARRIVED_AT)

        assertFalse(result.accuracyEligible)
        assertEquals(EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW, result.accuracyEligibilityReason)
    }

    @Test
    fun `arrival observation requires a departure snapshot and never falls back to canceled job`() {
        val status = ScheduleDepartureStatus(
            scheduleId = SCHEDULE_ID,
            memberId = MEMBER_ID,
            departedAt = DEPARTED_AT,
        )
        stubAccessibleStatus(status)
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)

        assertFailsWith<BusinessException> {
            record(service(), CLIENT_ARRIVED_AT)
        }
    }

    @Test
    fun `client arrival timestamp rejects pre-departure stale and excessive future values`() {
        val candidates = listOf(
            DEPARTED_AT.minusSeconds(1),
            SERVER_RECORDED_AT.minusSeconds(24 * 60 * 60 + 1),
            SERVER_RECORDED_AT.plusSeconds(61),
        )
        candidates.forEach { arrivedAt ->
            val status = measuredStatus()
            stubAccessibleStatus(status)

            assertFailsWith<BusinessException> {
                record(service(), arrivedAt)
            }
        }
    }

    @Test
    fun `client geofence label is retained but never treated as verified ground truth`() {
        val registry = SimpleMeterRegistry()
        stubAccessibleStatus(measuredStatus())
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(
            service(registry),
            CLIENT_ARRIVED_AT,
            source = ScheduleArrivalObservationSource.GEOFENCE,
            precisionSeconds = 121,
        )

        assertFalse(result.accuracyEligible)
        assertEquals(ScheduleArrivalObservationSource.GEOFENCE, result.observationSource)
        assertEquals(
            ScheduleArrivalObservationVerification.UNVERIFIED_CLIENT,
            result.observationVerification,
        )
        assertEquals(EtaAccuracyEligibilityReason.UNVERIFIED_GEOFENCE, result.accuracyEligibilityReason)
        assertEquals(121, result.precisionSeconds)
        assertEquals(
            0L,
            registry.find("nolate.eta.arrival.error.seconds").summaries().sumOf { it.count() },
        )
    }

    @Test
    fun `source precision and user adjustment combinations fail closed`() {
        data class InvalidInput(
            val source: ScheduleArrivalObservationSource,
            val precisionSeconds: Int,
            val adjustmentSeconds: Int?,
        )

        listOf(
            InvalidInput(ScheduleArrivalObservationSource.USER_NOW, 0, null),
            InvalidInput(ScheduleArrivalObservationSource.USER_NOW, 3_601, null),
            InvalidInput(ScheduleArrivalObservationSource.USER_NOW, 30, 60),
            InvalidInput(ScheduleArrivalObservationSource.GEOFENCE, 30, 60),
            InvalidInput(ScheduleArrivalObservationSource.USER_ADJUSTED, 60, null),
            InvalidInput(ScheduleArrivalObservationSource.USER_ADJUSTED, 60, 59),
            InvalidInput(ScheduleArrivalObservationSource.USER_ADJUSTED, 60, 61),
            InvalidInput(ScheduleArrivalObservationSource.USER_ADJUSTED, 60, 3_660),
            InvalidInput(ScheduleArrivalObservationSource.USER_ADJUSTED, 59, 300),
        ).forEach { input ->
            stubAccessibleStatus(measuredStatus())

            assertFailsWith<BusinessException> {
                record(
                    service(),
                    CLIENT_ARRIVED_AT.minusSeconds(300),
                    source = input.source,
                    precisionSeconds = input.precisionSeconds,
                    adjustmentSeconds = input.adjustmentSeconds,
                )
            }
        }

        stubAccessibleStatus(measuredStatus())
        assertFailsWith<BusinessException> {
            record(
                service(),
                SERVER_RECORDED_AT,
                source = ScheduleArrivalObservationSource.USER_ADJUSTED,
                precisionSeconds = 60,
                adjustmentSeconds = 300,
            )
        }
    }

    @Test
    fun `valid idempotent duplicate preserves the first adjusted observation and correction`() {
        var stored: ScheduleEtaAccuracyObservation? = null
        stubAccessibleStatus(measuredStatus())
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenAnswer { stored }
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer {
                (it.arguments[0] as ScheduleEtaAccuracyObservation).also { saved -> stored = saved }
            }
        val accuracyService = service()

        val first = record(
            accuracyService,
            CLIENT_ARRIVED_AT.minusSeconds(300),
            source = ScheduleArrivalObservationSource.USER_ADJUSTED,
            precisionSeconds = 60,
            adjustmentSeconds = 300,
        )
        val duplicate = record(accuracyService, CLIENT_ARRIVED_AT)

        assertEquals(first, duplicate)
        assertEquals(CLIENT_ARRIVED_AT.minusSeconds(300), duplicate.actualArrivalAt)
        assertEquals(ScheduleArrivalObservationSource.USER_ADJUSTED, duplicate.observationSource)
        assertEquals(60, duplicate.precisionSeconds)
        assertEquals(300, duplicate.adjustmentSeconds)
        assertEquals(5 * 60, duplicate.reportDelaySeconds)
        assertEquals(
            EtaAccuracyEligibilityReason.UNVERIFIED_USER_ADJUSTED,
            duplicate.accuracyEligibilityReason,
        )
        verify(observationRepository, times(1)).saveAndFlush(any<ScheduleEtaAccuracyObservation>())
    }

    @Test
    fun `adjusted report delay uses reconstructed capture time`() {
        stubAccessibleStatus(measuredStatus())
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }
        val adjustedArrivalAt = CLIENT_ARRIVED_AT.minusSeconds(300)

        val result = record(
            service(clockAt = CLIENT_ARRIVED_AT),
            adjustedArrivalAt,
            source = ScheduleArrivalObservationSource.USER_ADJUSTED,
            precisionSeconds = 300,
            adjustmentSeconds = 300,
        )

        assertEquals(0, result.reportDelaySeconds)
    }

    @Test
    fun `adjusted offline replay accepts capture boundary and rejects one second beyond it`() {
        val adjustedArrivalAt = CLIENT_ARRIVED_AT.minusSeconds(300)
        val capturedAt = adjustedArrivalAt.plusSeconds(300)
        val exactBoundary = capturedAt.plusSeconds(24 * 60 * 60)
        stubAccessibleStatus(measuredStatus())
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val accepted = record(
            service(clockAt = exactBoundary),
            adjustedArrivalAt,
            source = ScheduleArrivalObservationSource.USER_ADJUSTED,
            precisionSeconds = 300,
            adjustmentSeconds = 300,
        )

        assertEquals(24 * 60 * 60, accepted.reportDelaySeconds)

        stubAccessibleStatus(measuredStatus())
        assertFailsWith<BusinessException> {
            record(
                service(clockAt = exactBoundary.plusSeconds(1)),
                adjustedArrivalAt,
                source = ScheduleArrivalObservationSource.USER_ADJUSTED,
                precisionSeconds = 300,
                adjustmentSeconds = 300,
            )
        }
    }

    @Test
    fun `engagement funnel stores first exposure and prompt transitions idempotently`() {
        val registry = SimpleMeterRegistry()
        val status = measuredStatus()
        stubAccessibleStatus(status)
        val accuracyService = service(registry)

        accuracyService.recordEngagement(
            MEMBER_ID,
            SCHEDULE_ID,
            ScheduleEtaObservationEngagementEvent.EXPOSED,
            "1.2.0",
            "42",
            "arrival-card-v1",
        )
        accuracyService.recordEngagement(
            MEMBER_ID,
            SCHEDULE_ID,
            ScheduleEtaObservationEngagementEvent.EXPOSED,
            "9.9.9",
            "999",
            "replacement-must-not-win",
        )
        val prompted = accuracyService.recordEngagement(
            MEMBER_ID,
            SCHEDULE_ID,
            ScheduleEtaObservationEngagementEvent.PROMPT_OPENED,
            "1.2.1",
            "43",
            "arrival-card-v1",
        )
        accuracyService.recordEngagement(
            MEMBER_ID,
            SCHEDULE_ID,
            ScheduleEtaObservationEngagementEvent.PROMPT_OPENED,
            "bad value",
            "x".repeat(65),
            "replacement-must-not-win",
        )

        assertEquals(SERVER_RECORDED_AT, prompted.exposedAt)
        assertEquals("1.2.0", prompted.exposedClientAppVersion)
        assertEquals("42", prompted.exposedClientBuildVersion)
        assertEquals("arrival-card-v1", prompted.exposedUxVariant)
        assertEquals(SERVER_RECORDED_AT, prompted.promptedAt)
        assertEquals("1.2.1", prompted.promptedClientAppVersion)
        assertEquals("43", prompted.promptedClientBuildVersion)
        assertEquals("arrival-card-v1", prompted.promptedUxVariant)
        assertEquals(null, prompted.respondedAt)
        assertEquals(
            1.0,
            registry.get("nolate.eta.observation.funnel")
                .tag("stage", "exposed")
                .counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.observation.funnel")
                .tag("stage", "prompt_opened")
                .counter().count(),
        )
    }

    @Test
    fun `arrival plausibility rejects travel beyond the departure bound`() {
        val serverTime = DEPARTED_AT.plusSeconds(24 * 60 * 60 + 10 * 60)
        stubAccessibleStatus(measuredStatus())

        assertFailsWith<BusinessException> {
            record(
                service(clockAt = serverTime),
                DEPARTED_AT.plusSeconds(24 * 60 * 60 + 1),
            )
        }
    }

    @Test
    fun `arrival plausibility rejects a report outside the offline replay window`() {
        val serverTime = DEPARTED_AT.plusSeconds(24 * 60 * 60 + 1)
        stubAccessibleStatus(measuredStatus())

        assertFailsWith<BusinessException> {
            record(service(clockAt = serverTime), DEPARTED_AT)
        }
    }

    @Test
    fun `invalid optional client cohorts are discarded without trusting the self report`() {
        stubAccessibleStatus(measuredStatus())
        whenever(observationRepository.findByScheduleIdAndMemberId(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(null)
        whenever(observationRepository.saveAndFlush(any<ScheduleEtaAccuracyObservation>()))
            .thenAnswer { it.arguments[0] as ScheduleEtaAccuracyObservation }

        val result = record(
            service(),
            CLIENT_ARRIVED_AT,
            clientAppVersion = "bad cohort value",
            clientBuildVersion = "x".repeat(65),
        )

        assertEquals(null, result.clientAppVersion)
        assertEquals(null, result.clientBuildVersion)
        assertEquals(EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW, result.accuracyEligibilityReason)
    }

    private fun stubAccessibleStatus(
        status: ScheduleDepartureStatus,
        currentScheduleAt: Instant = SCHEDULE_AT,
    ) {
        whenever(scheduleRepository.findScheduleDetail(SCHEDULE_ID, MEMBER_ID)).thenReturn(
            Schedule(
                id = SCHEDULE_ID,
                memberId = MEMBER_ID,
                title = "ETA accuracy",
                startAt = currentScheduleAt,
                endAt = currentScheduleAt.plusSeconds(3_600),
            )
        )
        whenever(departureStatusRepository.findActiveForUpdate(SCHEDULE_ID, MEMBER_ID))
            .thenReturn(status)
    }

    private fun service(
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        clockAt: Instant = SERVER_RECORDED_AT,
    ) =
        ScheduleEtaAccuracyService(
            scheduleRepository = scheduleRepository,
            departureStatusRepository = departureStatusRepository,
            observationRepository = observationRepository,
            operationalMetrics = NoLateOperationalMetrics(registry),
            clock = Clock.fixed(clockAt, ZoneOffset.UTC),
        )

    private fun record(
        service: ScheduleEtaAccuracyService,
        arrivedAt: Instant,
        source: ScheduleArrivalObservationSource = ScheduleArrivalObservationSource.USER_NOW,
        precisionSeconds: Int = USER_NOW_PRECISION_SECONDS,
        adjustmentSeconds: Int? = null,
        clientAppVersion: String? = null,
        clientBuildVersion: String? = null,
    ) = service.recordArrival(
        memberId = MEMBER_ID,
        scheduleId = SCHEDULE_ID,
        arrivedAt = arrivedAt,
        observationSource = source,
        precisionSeconds = precisionSeconds,
        adjustmentSeconds = adjustmentSeconds,
        clientAppVersion = clientAppVersion,
        clientBuildVersion = clientBuildVersion,
    )

    private fun measuredStatus(
        departedAt: Instant = DEPARTED_AT,
        stale: Boolean = false,
        absolutePrediction: Instant? = PREDICTED_ARRIVAL_AT,
        evaluatedAt: Instant = PREDICTION_EVALUATED_AT,
    ): ScheduleDepartureStatus {
        val job = SchedulePushJob.create(
            memberId = MEMBER_ID,
            scheduleId = SCHEDULE_ID,
            scheduleAt = SCHEDULE_AT,
            departureAt = DEPARTED_AT,
            monitorStartAt = DEPARTED_AT.minusSeconds(3_600),
            intervalMinutes = 20,
        ).apply {
            finishCheck(
                travelMinutes = 55,
                recommendedDepartureAt = DEPARTED_AT,
                pushSent = false,
                notifiedDepartureAt = null,
                nextCheckAt = null,
                completeAfterCheck = true,
                etaSource = TrafficSource.LIVE_PROVIDER,
                liveFetchedAt = evaluatedAt,
                etaStale = stale,
                predictedArrivalAt = absolutePrediction,
                etaTravelMode = ScheduleTravelMode.TRANSIT,
                now = evaluatedAt,
            )
        }
        return ScheduleDepartureStatus(
            scheduleId = SCHEDULE_ID,
            memberId = MEMBER_ID,
        ).apply {
            keepFirstDeparture(departedAt)
            freezeEtaSnapshot(job)
        }
    }

    private companion object {
        const val MEMBER_ID = 7L
        const val SCHEDULE_ID = 9L
        val DEPARTED_AT: Instant = Instant.parse("2026-07-31T03:00:00Z")
        val PREDICTION_EVALUATED_AT: Instant = Instant.parse("2026-07-31T02:59:00Z")
        val PREDICTED_ARRIVAL_AT: Instant = Instant.parse("2026-07-31T03:55:00Z")
        val SCHEDULE_AT: Instant = Instant.parse("2026-07-31T04:00:00Z")
        val CLIENT_ARRIVED_AT: Instant = Instant.parse("2026-07-31T04:00:00Z")
        val SERVER_RECORDED_AT: Instant = Instant.parse("2026-07-31T04:05:00Z")
        const val USER_NOW_PRECISION_SECONDS = 30
    }
}
