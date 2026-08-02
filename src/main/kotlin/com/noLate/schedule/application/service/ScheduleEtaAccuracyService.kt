package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.EtaObservationFunnelStage
import com.noLate.global.observability.recordOperationalMetricAfterCommit
import com.noLate.global.observability.recordSafely
import com.noLate.schedule.domain.EtaAlgorithmVersion
import com.noLate.schedule.domain.EtaAccuracyEligibilityPolicyVersion
import com.noLate.schedule.domain.EtaAccuracyEligibilityReason
import com.noLate.schedule.domain.EtaOnTimeOutcome
import com.noLate.schedule.domain.EtaPredictionBasis
import com.noLate.schedule.domain.EtaProviderId
import com.noLate.schedule.domain.MAX_ARRIVAL_OBSERVATION_PRECISION_SECONDS
import com.noLate.schedule.domain.MAX_ARRIVAL_OBSERVATION_ADJUSTMENT_SECONDS
import com.noLate.schedule.domain.ScheduleArrivalObservationSource
import com.noLate.schedule.domain.ScheduleArrivalObservationVerification
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleEtaAccuracyObservation
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.domain.safeAbsolute
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleEtaAccuracyObservationRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class ScheduleEtaAccuracyObservationDto(
    val scheduleId: Long,
    val pushJobId: Long?,
    val departedAt: Instant,
    val predictionEvaluatedAt: Instant,
    val recommendedDepartureAt: Instant,
    val targetArrivalAt: Instant,
    val predictedArrivalAt: Instant,
    val actualArrivalAt: Instant,
    val observationSource: ScheduleArrivalObservationSource,
    val observationVerification: ScheduleArrivalObservationVerification,
    val precisionSeconds: Int,
    val adjustmentSeconds: Int?,
    val clientAppVersion: String?,
    val clientBuildVersion: String?,
    val backendCohortVersion: String,
    val eligibilityPolicyVersion: EtaAccuracyEligibilityPolicyVersion,
    val recordedAt: Instant,
    val etaSource: TrafficSource,
    val etaStale: Boolean,
    val travelMinutes: Int,
    val travelMode: ScheduleTravelMode,
    val predictionBasis: EtaPredictionBasis,
    val providerId: EtaProviderId,
    val algorithmVersion: EtaAlgorithmVersion,
    val providerFetchedAt: Instant?,
    val predictedOnTime: Boolean,
    val actualOnTime: Boolean,
    val onTimeOutcome: EtaOnTimeOutcome,
    /** actual departure minus recommended departure; positive means the user left later. */
    val departureOffsetSeconds: Long,
    val actualTravelSeconds: Long,
    val reportDelaySeconds: Long,
    /** false samples are retained for diagnosis but excluded from ETA accuracy aggregates. */
    val accuracyEligible: Boolean,
    val accuracyEligibilityReason: EtaAccuracyEligibilityReason,
    /** actual minus predicted; positive means later than predicted. */
    val signedErrorSeconds: Long,
    val absoluteErrorSeconds: Long,
)

enum class ScheduleEtaObservationEngagementEvent {
    EXPOSED,
    PROMPT_OPENED,
}

data class ScheduleEtaObservationEngagementDto(
    val scheduleId: Long,
    val exposedAt: Instant?,
    val exposedClientAppVersion: String?,
    val exposedClientBuildVersion: String?,
    val exposedUxVariant: String?,
    val promptedAt: Instant?,
    val promptedClientAppVersion: String?,
    val promptedClientBuildVersion: String?,
    val promptedUxVariant: String?,
    val respondedAt: Instant?,
)

/** Records explicit, opt-in arrival ground truth against the immutable departure ETA snapshot. */
@Service
class ScheduleEtaAccuracyService(
    private val scheduleRepository: ScheduleRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val observationRepository: ScheduleEtaAccuracyObservationRepository,
    private val operationalMetrics: NoLateOperationalMetrics? = null,
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
    @Value("\${schedule.traffic.accuracy.max-prediction-age-minutes:60}")
    private val maxPredictionAgeMinutes: Long = DEFAULT_MAX_PREDICTION_AGE_MINUTES,
    @Value("\${schedule.traffic.accuracy.max-provider-departure-offset-minutes:15}")
    private val maxProviderDepartureOffsetMinutes: Long =
        DEFAULT_MAX_PROVIDER_DEPARTURE_OFFSET_MINUTES,
    @Value("\${schedule.traffic.accuracy.max-arrival-report-delay-minutes:1440}")
    private val maxArrivalReportDelayMinutes: Long = DEFAULT_MAX_ARRIVAL_REPORT_DELAY_MINUTES,
    @Value("\${schedule.traffic.accuracy.max-arrival-future-skew-seconds:60}")
    private val maxArrivalFutureSkewSeconds: Long = DEFAULT_MAX_ARRIVAL_FUTURE_SKEW_SECONDS,
    @Value("\${schedule.traffic.accuracy.max-observation-precision-seconds:120}")
    private val maxObservationPrecisionSeconds: Int =
        DEFAULT_MAX_OBSERVATION_PRECISION_SECONDS,
    @Value("\${schedule.traffic.accuracy.max-arrival-adjustment-minutes:60}")
    private val maxArrivalAdjustmentMinutes: Int = DEFAULT_MAX_ARRIVAL_ADJUSTMENT_MINUTES,
    @Value("\${schedule.traffic.accuracy.max-arrival-after-departure-minutes:1440}")
    private val maxArrivalAfterDepartureMinutes: Long = DEFAULT_MAX_ARRIVAL_AFTER_DEPARTURE_MINUTES,
    @Value("\${schedule.traffic.accuracy.max-eligible-delay-vs-prediction-minutes:360}")
    private val maxEligibleDelayVsPredictionMinutes: Long =
        DEFAULT_MAX_ELIGIBLE_DELAY_VS_PREDICTION_MINUTES,
    @Value("\${schedule.traffic.accuracy.backend-cohort-version:unversioned}")
    backendCohortVersion: String = DEFAULT_BACKEND_COHORT_VERSION,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val backendCohortVersion = requireCohortValue(backendCohortVersion)

    init {
        require(maxPredictionAgeMinutes in 1..MAX_PREDICTION_AGE_MINUTES)
        require(maxProviderDepartureOffsetMinutes in 0..MAX_PROVIDER_DEPARTURE_OFFSET_MINUTES)
        require(maxArrivalReportDelayMinutes in 1..MAX_ARRIVAL_REPORT_DELAY_MINUTES)
        require(maxArrivalFutureSkewSeconds in 0..MAX_ARRIVAL_FUTURE_SKEW_SECONDS)
        require(maxObservationPrecisionSeconds in 1..MAX_ARRIVAL_OBSERVATION_PRECISION_SECONDS)
        require(maxArrivalAdjustmentMinutes in 1..MAX_ARRIVAL_ADJUSTMENT_MINUTES)
        require(maxArrivalAfterDepartureMinutes in 1..MAX_ARRIVAL_AFTER_DEPARTURE_MINUTES)
        require(maxEligibleDelayVsPredictionMinutes in 1..MAX_ELIGIBLE_DELAY_VS_PREDICTION_MINUTES)
    }

    @Transactional
    fun recordEngagement(
        memberId: Long,
        scheduleId: Long,
        event: ScheduleEtaObservationEngagementEvent,
        clientAppVersion: String? = null,
        clientBuildVersion: String? = null,
        uxVariant: String? = null,
    ): ScheduleEtaObservationEngagementDto {
        requireTravelAccess(memberId, scheduleId)
        val status = departureStatusRepository.findActiveForUpdate(scheduleId, memberId)
            ?: throw BusinessException(
                ErrorCode.INVALID_STATE,
                "출발 완료 후 도착 기록 안내를 표시할 수 있습니다.",
            )
        if (status.departedAt == null) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "출발 완료 후 도착 기록 안내를 표시할 수 있습니다.",
            )
        }

        val recordedAt = Instant.now(clock)
        val normalizedClientAppVersion = normalizeOptionalCohortValue(clientAppVersion)
        val normalizedClientBuildVersion = normalizeOptionalCohortValue(clientBuildVersion)
        val normalizedUxVariant = normalizeOptionalCohortValue(uxVariant)
        val transitions = linkedSetOf<EtaObservationFunnelStage>()
        if (
            status.keepFirstEtaObservationExposure(
                recordedAt,
                normalizedClientAppVersion,
                normalizedClientBuildVersion,
                normalizedUxVariant,
            )
        ) {
            transitions += EtaObservationFunnelStage.EXPOSED
        }
        if (
            event == ScheduleEtaObservationEngagementEvent.PROMPT_OPENED &&
            status.keepFirstEtaObservationPrompt(
                recordedAt,
                normalizedClientAppVersion,
                normalizedClientBuildVersion,
                normalizedUxVariant,
            )
        ) {
            transitions += EtaObservationFunnelStage.PROMPT_OPENED
        }
        departureStatusRepository.saveAndFlush(status)
        if (transitions.isNotEmpty()) {
            recordOperationalMetricAfterCommit {
                transitions.forEach { stage ->
                    operationalMetrics.recordSafely { recordEtaObservationFunnel(stage) }
                }
            }
        }
        return ScheduleEtaObservationEngagementDto(
            scheduleId = scheduleId,
            exposedAt = status.etaObservationExposedAt,
            exposedClientAppVersion = status.etaObservationExposedClientAppVersion,
            exposedClientBuildVersion = status.etaObservationExposedClientBuildVersion,
            exposedUxVariant = status.etaObservationExposedUxVariant,
            promptedAt = status.etaObservationPromptedAt,
            promptedClientAppVersion = status.etaObservationPromptedClientAppVersion,
            promptedClientBuildVersion = status.etaObservationPromptedClientBuildVersion,
            promptedUxVariant = status.etaObservationPromptedUxVariant,
            respondedAt = status.etaObservationRespondedAt,
        )
    }

    @Transactional
    fun recordArrival(
        memberId: Long,
        scheduleId: Long,
        arrivedAt: Instant,
        observationSource: ScheduleArrivalObservationSource,
        precisionSeconds: Int,
        adjustmentSeconds: Int? = null,
        clientAppVersion: String? = null,
        clientBuildVersion: String? = null,
    ): ScheduleEtaAccuracyObservationDto {
        requireTravelAccess(memberId, scheduleId)
        // 출발 transaction과 같은 row를 잠가 snapshot 생성과 arrival 관측을 직렬화한다.
        // canceled push job은 의도적으로 조회하지 않는다.
        val departureStatus = departureStatusRepository.findActiveForUpdate(scheduleId, memberId)
            ?: throw BusinessException(
                ErrorCode.INVALID_STATE,
                "출발 완료 후 도착을 기록할 수 있습니다.",
            )
        val departedAt = departureStatus.departedAt
            ?: throw BusinessException(
                ErrorCode.INVALID_STATE,
                "출발 완료 후 도착을 기록할 수 있습니다.",
            )
        val recordedAt = Instant.now(clock)
        validateArrivalInput(
            arrivedAt = arrivedAt,
            observationSource = observationSource,
            precisionSeconds = precisionSeconds,
            adjustmentSeconds = adjustmentSeconds,
            departedAt = departedAt,
            recordedAt = recordedAt,
        )
        // Validate every replay fail-closed, then preserve the first accepted ground-truth sample.
        observationRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
            ?.let { return it.toDto() }

        val snapshot = departureStatus.requireEtaSnapshot()
        val normalizedClientAppVersion = normalizeOptionalCohortValue(clientAppVersion)
        val normalizedClientBuildVersion = normalizeOptionalCohortValue(clientBuildVersion)
        val departureOffsetSeconds = Duration.between(
            snapshot.recommendedDepartureAt,
            departedAt,
        ).seconds
        val actualTravelSeconds = Duration.between(departedAt, arrivedAt).seconds
        val capturedAt = observationCapturedAt(
            arrivedAt = arrivedAt,
            observationSource = observationSource,
            adjustmentSeconds = adjustmentSeconds,
        )
        val reportDelaySeconds = Duration.between(capturedAt, recordedAt).seconds.coerceAtLeast(0)
        val observationVerification = ScheduleArrivalObservationVerification.UNVERIFIED_CLIENT
        val accuracyEligibilityReason = accuracyEligibilityReason(
            snapshot = snapshot,
            departedAt = departedAt,
            departureOffsetSeconds = departureOffsetSeconds,
            precisionSeconds = precisionSeconds,
            observationSource = observationSource,
            observationVerification = observationVerification,
            clientAppVersion = normalizedClientAppVersion,
            clientBuildVersion = normalizedClientBuildVersion,
            arrivedAt = arrivedAt,
        )
        val accuracyEligible = accuracyEligibilityReason == EtaAccuracyEligibilityReason.ELIGIBLE
        val signedErrorSeconds = Duration.between(
            snapshot.predictedArrivalAt,
            arrivedAt,
        ).seconds
        val actualOnTime = !arrivedAt.isAfter(snapshot.targetArrivalAt)
        val onTimeOutcome = EtaOnTimeOutcome.of(snapshot.onTimeArrivalPossible, actualOnTime)
        val observation = observationRepository.saveAndFlush(
            ScheduleEtaAccuracyObservation(
                scheduleId = scheduleId,
                memberId = memberId,
                pushJobId = snapshot.pushJobId,
                departedAt = departedAt,
                predictionEvaluatedAt = snapshot.evaluatedAt,
                predictedArrivalAt = snapshot.predictedArrivalAt,
                recommendedDepartureAt = snapshot.recommendedDepartureAt,
                targetArrivalAt = snapshot.targetArrivalAt,
                actualArrivalAt = arrivedAt,
                observationVerification = observationVerification,
                observationSource = observationSource,
                precisionSeconds = precisionSeconds,
                adjustmentSeconds = adjustmentSeconds,
                clientAppVersion = normalizedClientAppVersion,
                clientBuildVersion = normalizedClientBuildVersion,
                backendCohortVersion = backendCohortVersion,
                eligibilityPolicyVersion = EtaAccuracyEligibilityPolicyVersion.SELF_REPORT_DIAGNOSTIC_V2,
                etaSource = snapshot.source,
                etaStale = snapshot.stale,
                travelMinutes = snapshot.travelMinutes,
                predictionBasis = snapshot.predictionBasis,
                travelMode = snapshot.travelMode,
                providerId = snapshot.providerId,
                algorithmVersion = snapshot.algorithmVersion,
                providerFetchedAt = snapshot.providerFetchedAt,
                predictedOnTime = snapshot.onTimeArrivalPossible,
                actualOnTime = actualOnTime,
                onTimeOutcome = onTimeOutcome,
                departureOffsetSeconds = departureOffsetSeconds,
                actualTravelSeconds = actualTravelSeconds,
                reportDelaySeconds = reportDelaySeconds,
                accuracyEligible = accuracyEligible,
                accuracyEligibilityReason = accuracyEligibilityReason,
                signedErrorSeconds = signedErrorSeconds,
                absoluteErrorSeconds = signedErrorSeconds.safeAbsolute(),
                recordedAt = recordedAt,
            )
        )
        val firstResponse = departureStatus.keepFirstEtaObservationResponse(recordedAt)
        departureStatusRepository.saveAndFlush(departureStatus)
        recordOperationalMetricAfterCommit {
            operationalMetrics.recordSafely {
                recordEtaObservationEligibility(accuracyEligibilityReason)
                if (firstResponse) {
                    recordEtaObservationFunnel(EtaObservationFunnelStage.RESPONSE_STORED)
                }
                if (accuracyEligible) {
                    recordEtaArrivalError(
                        source = snapshot.source,
                        travelMode = snapshot.travelMode,
                        providerId = snapshot.providerId,
                        predictionBasis = snapshot.predictionBasis,
                        algorithmVersion = snapshot.algorithmVersion,
                        signedErrorSeconds = signedErrorSeconds,
                    )
                    recordEtaOnTimeOutcome(
                        travelMode = snapshot.travelMode,
                        providerId = snapshot.providerId,
                        algorithmVersion = snapshot.algorithmVersion,
                        outcome = onTimeOutcome,
                    )
                }
            }
        }
        return observation.toDto()
    }

    private fun requireTravelAccess(memberId: Long, scheduleId: Long) {
        val schedule = if (scheduleAccessPolicy?.isSharingDisabled() == true) {
            scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
        } else {
            scheduleRepository.findScheduleDetail(scheduleId, memberId)
        } ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        scheduleAccessPolicy?.resolve(memberId, schedule)?.let { access ->
            if (!access.travelEnabled) {
                throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
            }
        }
    }

    private fun validateArrivalInput(
        arrivedAt: Instant,
        observationSource: ScheduleArrivalObservationSource,
        precisionSeconds: Int,
        adjustmentSeconds: Int?,
        departedAt: Instant,
        recordedAt: Instant,
    ) {
        if (precisionSeconds !in 1..MAX_ARRIVAL_OBSERVATION_PRECISION_SECONDS) {
            invalidArrival("도착 관측 정밀도 범위를 벗어났습니다.")
        }
        validatePhysicalArrivalTimestamp(arrivedAt, departedAt)

        when (observationSource) {
            ScheduleArrivalObservationSource.USER_NOW,
            ScheduleArrivalObservationSource.GEOFENCE,
            -> if (adjustmentSeconds != null) {
                invalidArrival("현재시각 또는 지오펜스 관측에는 시각 보정을 적용할 수 없습니다.")
            }

            ScheduleArrivalObservationSource.USER_ADJUSTED -> {
                val adjustment = adjustmentSeconds
                    ?: invalidArrival("사용자 보정 관측에는 보정 시간이 필요합니다.")
                if (
                    adjustment !in MIN_ARRIVAL_ADJUSTMENT_SECONDS..maxArrivalAdjustmentMinutes * 60 ||
                    adjustment % 60 != 0
                ) {
                    invalidArrival("도착시각 보정은 허용된 분 단위 범위여야 합니다.")
                }
                if (precisionSeconds < USER_ADJUSTED_MIN_PRECISION_SECONDS) {
                    invalidArrival("분 단위 사용자 보정의 정밀도는 60초 이상이어야 합니다.")
                }
            }
        }
        // USER_ADJUSTED carries the corrected arrival time, not the time at which the client
        // captured and queued the response. Reconstruct that immutable capture boundary so the
        // report-delay window and persisted diagnostic do not include the user's correction.
        validateReportTimestamp(
            observationCapturedAt(arrivedAt, observationSource, adjustmentSeconds),
            recordedAt,
        )
    }

    private fun validatePhysicalArrivalTimestamp(
        arrivedAt: Instant,
        departedAt: Instant,
    ) {
        if (arrivedAt.isBefore(departedAt)) {
            invalidArrival("도착시각이 출발시각보다 빠릅니다.")
        }
        if (arrivedAt.isAfter(departedAt.plusSeconds(maxArrivalAfterDepartureMinutes * 60L))) {
            invalidArrival("출발 후 도착 기록 가능 시간을 벗어났습니다.")
        }
    }

    private fun observationCapturedAt(
        arrivedAt: Instant,
        observationSource: ScheduleArrivalObservationSource,
        adjustmentSeconds: Int?,
    ): Instant = when (observationSource) {
        ScheduleArrivalObservationSource.USER_ADJUSTED ->
            arrivedAt.plusSeconds(requireNotNull(adjustmentSeconds).toLong())
        ScheduleArrivalObservationSource.USER_NOW,
        ScheduleArrivalObservationSource.GEOFENCE,
        -> arrivedAt
    }

    private fun validateReportTimestamp(timestamp: Instant, recordedAt: Instant) {
        if (timestamp.isAfter(recordedAt.plusSeconds(maxArrivalFutureSkewSeconds))) {
            invalidArrival("도착시각이 허용된 미래 오차를 넘었습니다.")
        }
        if (timestamp.isBefore(recordedAt.minusSeconds(maxArrivalReportDelayMinutes * 60L))) {
            invalidArrival("도착 기록 가능 시간이 지났습니다.")
        }
    }

    private fun invalidArrival(message: String): Nothing =
        throw BusinessException(ErrorCode.INVALID_INPUT, message)

    private fun accuracyEligibilityReason(
        snapshot: CompleteDepartureEtaSnapshot,
        departedAt: Instant,
        departureOffsetSeconds: Long,
        precisionSeconds: Int,
        observationSource: ScheduleArrivalObservationSource,
        observationVerification: ScheduleArrivalObservationVerification,
        clientAppVersion: String?,
        clientBuildVersion: String?,
        arrivedAt: Instant,
    ): EtaAccuracyEligibilityReason {
        if (observationVerification != ScheduleArrivalObservationVerification.VERIFIED_GEOFENCE) {
            return when (observationSource) {
                ScheduleArrivalObservationSource.USER_NOW ->
                    EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW
                ScheduleArrivalObservationSource.USER_ADJUSTED ->
                    EtaAccuracyEligibilityReason.UNVERIFIED_USER_ADJUSTED
                ScheduleArrivalObservationSource.GEOFENCE ->
                    EtaAccuracyEligibilityReason.UNVERIFIED_GEOFENCE
            }
        }
        if (clientAppVersion == null) {
            return EtaAccuracyEligibilityReason.MISSING_CLIENT_APP_VERSION
        }
        if (clientBuildVersion == null) {
            return EtaAccuracyEligibilityReason.MISSING_CLIENT_BUILD_VERSION
        }
        if (backendCohortVersion.equals(DEFAULT_BACKEND_COHORT_VERSION, ignoreCase = true)) {
            return EtaAccuracyEligibilityReason.UNVERSIONED_BACKEND_COHORT
        }
        if (snapshot.algorithmVersion == EtaAlgorithmVersion.UNKNOWN) {
            return EtaAccuracyEligibilityReason.UNKNOWN_ALGORITHM_VERSION
        }
        // VERIFIED_GEOFENCE proves only arrival. Duration-based ETA is anchored to the server
        // receipt time of the user's depart-now tap, for which no trusted producer exists yet.
        if (snapshot.predictionBasis == EtaPredictionBasis.DEPARTURE_ANCHORED_DURATION) {
            return EtaAccuracyEligibilityReason.UNVERIFIED_DEPARTURE
        }
        if (precisionSeconds > maxObservationPrecisionSeconds) {
            return EtaAccuracyEligibilityReason.OBSERVATION_PRECISION_TOO_COARSE
        }
        if (snapshot.stale) return EtaAccuracyEligibilityReason.STALE_ETA
        if (snapshot.source !in PROVIDER_SOURCES) {
            return EtaAccuracyEligibilityReason.UNSUPPORTED_ETA_SOURCE
        }
        if (snapshot.providerId !in MEASURABLE_PROVIDERS) {
            return EtaAccuracyEligibilityReason.UNSUPPORTED_PROVIDER
        }

        val providerFetchedAt = snapshot.providerFetchedAt
            ?: return EtaAccuracyEligibilityReason.MISSING_PROVIDER_FETCH_TIME
        val providerAge = Duration.between(providerFetchedAt, departedAt)
        if (providerAge.isNegative) {
            return EtaAccuracyEligibilityReason.PROVIDER_FETCH_AFTER_DEPARTURE
        }
        if (providerAge > Duration.ofMinutes(maxPredictionAgeMinutes)) {
            return EtaAccuracyEligibilityReason.PROVIDER_PREDICTION_TOO_OLD
        }

        val predictionAge = Duration.between(snapshot.evaluatedAt, departedAt)
        if (predictionAge.isNegative) {
            return EtaAccuracyEligibilityReason.PREDICTION_EVALUATED_AFTER_DEPARTURE
        }
        if (predictionAge > Duration.ofMinutes(maxPredictionAgeMinutes)) {
            return EtaAccuracyEligibilityReason.PREDICTION_TOO_OLD
        }
        if (
            snapshot.predictionBasis == EtaPredictionBasis.PROVIDER_ABSOLUTE &&
            departureOffsetSeconds.safeAbsolute() > maxProviderDepartureOffsetMinutes * 60L
        ) {
            return EtaAccuracyEligibilityReason.PROVIDER_ABSOLUTE_DEPARTURE_OFFSET_TOO_LARGE
        }
        if (
            arrivedAt.isAfter(
                snapshot.predictedArrivalAt.plusSeconds(maxEligibleDelayVsPredictionMinutes * 60L)
            )
        ) {
            return EtaAccuracyEligibilityReason.ACTUAL_TRAVEL_DURATION_IMPLAUSIBLE
        }
        return EtaAccuracyEligibilityReason.ELIGIBLE
    }

    private fun ScheduleDepartureStatus.requireEtaSnapshot(): CompleteDepartureEtaSnapshot =
        DepartureEtaSnapshot(
            pushJobId = etaSnapshotPushJobId,
            evaluatedAt = etaSnapshotEvaluatedAt,
            recommendedDepartureAt = etaSnapshotRecommendedDepartureAt,
            predictedArrivalAt = etaSnapshotPredictedArrivalAt,
            source = etaSnapshotSource,
            stale = etaSnapshotStale,
            travelMinutes = etaSnapshotTravelMinutes,
            predictionBasis = etaSnapshotPredictionBasis,
            travelMode = etaSnapshotTravelMode,
            providerId = etaSnapshotProviderId,
            targetArrivalAt = etaSnapshotTargetArrivalAt,
            onTimeArrivalPossible = etaSnapshotOnTimeArrivalPossible,
            algorithmVersion = etaSnapshotAlgorithmVersion,
            providerFetchedAt = etaSnapshotProviderFetchedAt,
        ).requireComplete()

    private fun ScheduleEtaAccuracyObservation.toDto() = ScheduleEtaAccuracyObservationDto(
        scheduleId = scheduleId,
        pushJobId = pushJobId,
        departedAt = departedAt,
        predictionEvaluatedAt = predictionEvaluatedAt,
        recommendedDepartureAt = recommendedDepartureAt,
        targetArrivalAt = targetArrivalAt,
        predictedArrivalAt = predictedArrivalAt,
        actualArrivalAt = actualArrivalAt,
        observationSource = observationSource,
        observationVerification = observationVerification,
        precisionSeconds = precisionSeconds,
        adjustmentSeconds = adjustmentSeconds,
        clientAppVersion = clientAppVersion,
        clientBuildVersion = clientBuildVersion,
        backendCohortVersion = backendCohortVersion,
        eligibilityPolicyVersion = eligibilityPolicyVersion,
        recordedAt = recordedAt,
        etaSource = etaSource,
        etaStale = etaStale,
        travelMinutes = travelMinutes,
        travelMode = travelMode,
        predictionBasis = predictionBasis,
        providerId = providerId,
        algorithmVersion = algorithmVersion,
        providerFetchedAt = providerFetchedAt,
        predictedOnTime = predictedOnTime,
        actualOnTime = actualOnTime,
        onTimeOutcome = onTimeOutcome,
        departureOffsetSeconds = departureOffsetSeconds,
        actualTravelSeconds = actualTravelSeconds,
        reportDelaySeconds = reportDelaySeconds,
        accuracyEligible = accuracyEligible,
        accuracyEligibilityReason = accuracyEligibilityReason,
        signedErrorSeconds = signedErrorSeconds,
        absoluteErrorSeconds = absoluteErrorSeconds,
    )

    private data class DepartureEtaSnapshot(
        val pushJobId: Long?,
        val evaluatedAt: Instant?,
        val recommendedDepartureAt: Instant?,
        val predictedArrivalAt: Instant?,
        val source: TrafficSource?,
        val stale: Boolean?,
        val travelMinutes: Int?,
        val predictionBasis: EtaPredictionBasis?,
        val travelMode: ScheduleTravelMode?,
        val providerId: EtaProviderId?,
        val targetArrivalAt: Instant?,
        val onTimeArrivalPossible: Boolean?,
        val algorithmVersion: EtaAlgorithmVersion?,
        val providerFetchedAt: Instant?,
    ) {
        fun requireComplete(): CompleteDepartureEtaSnapshot {
            val completePredictedArrivalAt = predictedArrivalAt ?: missing()
            val completeTargetArrivalAt = targetArrivalAt ?: missing()
            val completeOnTimeArrivalPossible = onTimeArrivalPossible ?: missing()
            if (
                completeOnTimeArrivalPossible !=
                !completePredictedArrivalAt.isAfter(completeTargetArrivalAt)
            ) inconsistent()
            return CompleteDepartureEtaSnapshot(
                pushJobId = pushJobId,
                evaluatedAt = evaluatedAt ?: missing(),
                recommendedDepartureAt = recommendedDepartureAt ?: missing(),
                predictedArrivalAt = completePredictedArrivalAt,
                targetArrivalAt = completeTargetArrivalAt,
                onTimeArrivalPossible = completeOnTimeArrivalPossible,
                source = source ?: missing(),
                stale = stale ?: missing(),
                travelMinutes = travelMinutes?.takeIf { it > 0 } ?: missing(),
                predictionBasis = predictionBasis ?: missing(),
                travelMode = travelMode ?: missing(),
                providerId = providerId ?: missing(),
                algorithmVersion = algorithmVersion ?: missing(),
                providerFetchedAt = providerFetchedAt,
            )
        }

        private fun missing(): Nothing = throw BusinessException(
            ErrorCode.INVALID_STATE,
            "출발 시점에 동결된 ETA가 없습니다.",
        )

        private fun inconsistent(): Nothing = throw BusinessException(
            ErrorCode.INVALID_STATE,
            "출발 시점 ETA 정시 판정 snapshot이 일관되지 않습니다.",
        )
    }

    private data class CompleteDepartureEtaSnapshot(
        val pushJobId: Long?,
        val evaluatedAt: Instant,
        val recommendedDepartureAt: Instant,
        val predictedArrivalAt: Instant,
        val targetArrivalAt: Instant,
        val onTimeArrivalPossible: Boolean,
        val source: TrafficSource,
        val stale: Boolean,
        val travelMinutes: Int,
        val predictionBasis: EtaPredictionBasis,
        val travelMode: ScheduleTravelMode,
        val providerId: EtaProviderId,
        val algorithmVersion: EtaAlgorithmVersion,
        val providerFetchedAt: Instant?,
    )

    private companion object {
        const val DEFAULT_MAX_PREDICTION_AGE_MINUTES = 60L
        const val MAX_PREDICTION_AGE_MINUTES = 1_440L
        const val DEFAULT_MAX_PROVIDER_DEPARTURE_OFFSET_MINUTES = 15L
        const val MAX_PROVIDER_DEPARTURE_OFFSET_MINUTES = 240L
        // The client persists the callback timestamp before sending, so a process restart or
        // overnight network outage must not turn a valid captured event into an endless retry.
        const val DEFAULT_MAX_ARRIVAL_REPORT_DELAY_MINUTES = 1_440L
        const val MAX_ARRIVAL_REPORT_DELAY_MINUTES = 1_440L
        const val DEFAULT_MAX_ARRIVAL_FUTURE_SKEW_SECONDS = 60L
        const val MAX_ARRIVAL_FUTURE_SKEW_SECONDS = 300L
        const val DEFAULT_MAX_OBSERVATION_PRECISION_SECONDS = 120
        const val DEFAULT_MAX_ARRIVAL_ADJUSTMENT_MINUTES = 60
        const val MAX_ARRIVAL_ADJUSTMENT_MINUTES =
            MAX_ARRIVAL_OBSERVATION_ADJUSTMENT_SECONDS / 60
        const val MIN_ARRIVAL_ADJUSTMENT_SECONDS = 60
        const val USER_ADJUSTED_MIN_PRECISION_SECONDS = 60
        const val DEFAULT_MAX_ARRIVAL_AFTER_DEPARTURE_MINUTES = 1_440L
        const val MAX_ARRIVAL_AFTER_DEPARTURE_MINUTES = 1_440L
        const val DEFAULT_MAX_ELIGIBLE_DELAY_VS_PREDICTION_MINUTES = 360L
        const val MAX_ELIGIBLE_DELAY_VS_PREDICTION_MINUTES = 1_440L
        const val DEFAULT_BACKEND_COHORT_VERSION = "unversioned"
        const val MAX_COHORT_VALUE_LENGTH = 64
        val COHORT_VALUE_PATTERN = Regex("[A-Za-z0-9._+\\-]{1,$MAX_COHORT_VALUE_LENGTH}")

        val PROVIDER_SOURCES = setOf(TrafficSource.LIVE_PROVIDER, TrafficSource.TIMETABLE_PROVIDER)
        val MEASURABLE_PROVIDERS = setOf(EtaProviderId.ODSAY_TRANSIT, EtaProviderId.TMAP)

        fun requireCohortValue(value: String): String =
            value.trim().takeIf(COHORT_VALUE_PATTERN::matches)
                ?: throw IllegalArgumentException("backend cohort version must be a bounded token")

        fun normalizeOptionalCohortValue(value: String?): String? =
            value?.trim()?.takeIf(COHORT_VALUE_PATTERN::matches)
    }
}
