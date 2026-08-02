package com.noLate.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * One opt-in, authenticated arrival ground-truth sample per member and schedule.
 *
 * It freezes the latest prediction that existed when the member actually departed. The signed
 * error is actual minus predicted, so positive values mean a later-than-predicted arrival.
 */
@Entity
@Table(
    name = "schedule_eta_accuracy_observations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_eta_accuracy_schedule_member",
            columnNames = ["schedule_id", "member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_eta_accuracy_recorded_at", columnList = "recorded_at"),
        Index(name = "idx_eta_accuracy_source", columnList = "eta_source, recorded_at"),
        Index(
            name = "idx_eta_accuracy_observation_quality",
            columnList = "accuracy_eligible, observation_source, precision_seconds, recorded_at",
        ),
        Index(
            name = "idx_eta_accuracy_provenance",
            columnList =
                "algorithm_version, travel_mode, provider_id, prediction_basis, recorded_at",
        ),
        Index(
            name = "idx_eta_accuracy_cohort",
            columnList =
                "backend_cohort_version, client_app_version, algorithm_version, recorded_at",
        ),
        Index(name = "idx_eta_accuracy_member", columnList = "member_id, id"),
    ],
)
class ScheduleEtaAccuracyObservation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "push_job_id")
    val pushJobId: Long?,

    @Column(name = "departed_at", nullable = false)
    val departedAt: Instant,

    @Column(name = "prediction_evaluated_at", nullable = false)
    val predictionEvaluatedAt: Instant,

    @Column(name = "predicted_arrival_at", nullable = false)
    val predictedArrivalAt: Instant,

    @Column(name = "recommended_departure_at", nullable = false)
    val recommendedDepartureAt: Instant,

    @Column(name = "target_arrival_at", nullable = false)
    val targetArrivalAt: Instant,

    @Column(name = "actual_arrival_at", nullable = false)
    val actualArrivalAt: Instant,

    /** Server-owned verification state. The public client API can only create UNVERIFIED rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "observation_verification", nullable = false, length = 30)
    val observationVerification: ScheduleArrivalObservationVerification,

    @Enumerated(EnumType.STRING)
    @Column(name = "observation_source", nullable = false, length = 30)
    val observationSource: ScheduleArrivalObservationSource,

    /** Temporal uncertainty of the actual-arrival observation, not location accuracy. */
    @Column(name = "precision_seconds", nullable = false)
    val precisionSeconds: Int,

    /** Whole-minute correction from the user's selection time; USER_ADJUSTED only. */
    @Column(name = "adjustment_seconds")
    val adjustmentSeconds: Int?,

    /** Optional client release provenance; diagnostic only and never a metric tag. */
    @Column(name = "client_app_version", length = 64)
    val clientAppVersion: String?,

    @Column(name = "client_build_version", length = 64)
    val clientBuildVersion: String?,

    /** Operator-provided immutable backend cohort identifier for offline comparisons. */
    @Column(name = "backend_cohort_version", nullable = false, length = 64)
    val backendCohortVersion: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_policy_version", nullable = false, length = 50)
    val eligibilityPolicyVersion: EtaAccuracyEligibilityPolicyVersion,

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_source", nullable = false, length = 30)
    val etaSource: TrafficSource,

    @Column(name = "eta_stale", nullable = false)
    val etaStale: Boolean,

    @Column(name = "travel_minutes", nullable = false)
    val travelMinutes: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "prediction_basis", nullable = false, length = 40)
    val predictionBasis: EtaPredictionBasis,

    @Enumerated(EnumType.STRING)
    @Column(name = "travel_mode", nullable = false, length = 20)
    val travelMode: ScheduleTravelMode,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_id", nullable = false, length = 30)
    val providerId: EtaProviderId,

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm_version", nullable = false, length = 40)
    val algorithmVersion: EtaAlgorithmVersion,

    @Column(name = "provider_fetched_at")
    val providerFetchedAt: Instant?,

    @Column(name = "predicted_on_time", nullable = false)
    val predictedOnTime: Boolean,

    @Column(name = "actual_on_time", nullable = false)
    val actualOnTime: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "on_time_outcome", nullable = false, length = 50)
    val onTimeOutcome: EtaOnTimeOutcome,

    /** actual departure minus recommended departure; positive means the user left later. */
    @Column(name = "departure_offset_seconds", nullable = false)
    val departureOffsetSeconds: Long,

    @Column(name = "actual_travel_seconds", nullable = false)
    val actualTravelSeconds: Long,

    /** Server receipt minus client capture, floored at zero for bounded offline-cohort analysis. */
    @Column(name = "report_delay_seconds", nullable = false)
    val reportDelaySeconds: Long,

    @Column(name = "accuracy_eligible", nullable = false)
    val accuracyEligible: Boolean,

    @Enumerated(EnumType.STRING)
    @Column(name = "accuracy_eligibility_reason", nullable = false, length = 60)
    val accuracyEligibilityReason: EtaAccuracyEligibilityReason,

    @Column(name = "signed_error_seconds", nullable = false)
    val signedErrorSeconds: Long,

    @Column(name = "absolute_error_seconds", nullable = false)
    val absoluteErrorSeconds: Long,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
) {
    init {
        require(scheduleId > 0 && memberId > 0)
        require(travelMinutes > 0)
        require(precisionSeconds in 1..MAX_ARRIVAL_OBSERVATION_PRECISION_SECONDS)
        clientAppVersion?.let { require(it.length <= 64) }
        clientBuildVersion?.let { require(it.length <= 64) }
        require(backendCohortVersion.isNotBlank() && backendCohortVersion.length <= 64)
        when (observationSource) {
            ScheduleArrivalObservationSource.USER_ADJUSTED -> {
                val correction = adjustmentSeconds
                require(
                    correction != null &&
                        correction in 60..MAX_ARRIVAL_OBSERVATION_ADJUSTMENT_SECONDS &&
                        correction % 60 == 0 &&
                        precisionSeconds >= 60
                )
            }
            ScheduleArrivalObservationSource.USER_NOW,
            ScheduleArrivalObservationSource.GEOFENCE,
            -> require(adjustmentSeconds == null)
        }
        if (observationVerification == ScheduleArrivalObservationVerification.VERIFIED_GEOFENCE) {
            require(observationSource == ScheduleArrivalObservationSource.GEOFENCE)
        }
        require(actualTravelSeconds >= 0)
        require(actualTravelSeconds == java.time.Duration.between(departedAt, actualArrivalAt).seconds)
        require(reportDelaySeconds >= 0)
        require(accuracyEligible == (accuracyEligibilityReason == EtaAccuracyEligibilityReason.ELIGIBLE))
        if (accuracyEligible) {
            require(observationVerification == ScheduleArrivalObservationVerification.VERIFIED_GEOFENCE)
            require(observationSource == ScheduleArrivalObservationSource.GEOFENCE)
            require(!clientAppVersion.isNullOrBlank())
            require(!clientBuildVersion.isNullOrBlank())
            require(!backendCohortVersion.equals("unversioned", ignoreCase = true))
            require(algorithmVersion != EtaAlgorithmVersion.UNKNOWN)
            // Arrival verification alone cannot make a departure-anchored prediction measurable.
            // Until a server-owned verified departure producer exists, the user's depart-now tap
            // is not sufficiently trustworthy to anchor an accuracy cohort.
            require(predictionBasis == EtaPredictionBasis.PROVIDER_ABSOLUTE)
        }
        require(absoluteErrorSeconds >= 0)
        require(absoluteErrorSeconds == signedErrorSeconds.safeAbsolute())
        require(!actualArrivalAt.isBefore(departedAt))
        require(predictedOnTime == !predictedArrivalAt.isAfter(targetArrivalAt))
        require(actualOnTime == !actualArrivalAt.isAfter(targetArrivalAt))
        require(onTimeOutcome == EtaOnTimeOutcome.of(predictedOnTime, actualOnTime))
    }

    protected constructor() : this(
        scheduleId = 1,
        memberId = 1,
        pushJobId = null,
        departedAt = Instant.EPOCH,
        predictionEvaluatedAt = Instant.EPOCH,
        predictedArrivalAt = Instant.EPOCH,
        recommendedDepartureAt = Instant.EPOCH,
        targetArrivalAt = Instant.EPOCH,
        actualArrivalAt = Instant.EPOCH,
        observationVerification = ScheduleArrivalObservationVerification.UNVERIFIED_CLIENT,
        observationSource = ScheduleArrivalObservationSource.USER_NOW,
        precisionSeconds = 1,
        adjustmentSeconds = null,
        clientAppVersion = null,
        clientBuildVersion = null,
        backendCohortVersion = "unknown",
        eligibilityPolicyVersion = EtaAccuracyEligibilityPolicyVersion.SELF_REPORT_DIAGNOSTIC_V2,
        etaSource = TrafficSource.SAVED_FALLBACK,
        etaStale = true,
        travelMinutes = 1,
        predictionBasis = EtaPredictionBasis.DEPARTURE_ANCHORED_DURATION,
        travelMode = ScheduleTravelMode.ETC,
        providerId = EtaProviderId.UNKNOWN,
        algorithmVersion = EtaAlgorithmVersion.UNKNOWN,
        providerFetchedAt = null,
        predictedOnTime = true,
        actualOnTime = true,
        onTimeOutcome = EtaOnTimeOutcome.PREDICTED_ON_TIME_ACTUAL_ON_TIME,
        departureOffsetSeconds = 0,
        actualTravelSeconds = 0,
        reportDelaySeconds = 0,
        accuracyEligible = false,
        accuracyEligibilityReason = EtaAccuracyEligibilityReason.UNVERIFIED_USER_NOW,
        signedErrorSeconds = 0,
        absoluteErrorSeconds = 0,
        recordedAt = Instant.EPOCH,
    )
}

/** Bounded provenance for how the client determined the actual arrival timestamp. */
enum class ScheduleArrivalObservationSource {
    USER_NOW,
    USER_ADJUSTED,
    GEOFENCE,
}

/**
 * Verification is server-owned and deliberately absent from the public request DTO. A client that
 * merely labels an event GEOFENCE must remain UNVERIFIED_CLIENT until a separately reviewed,
 * consented field producer can prove the observation.
 */
enum class ScheduleArrivalObservationVerification {
    UNVERIFIED_CLIENT,
    VERIFIED_GEOFENCE,
}

enum class EtaAccuracyEligibilityPolicyVersion {
    SELF_REPORT_DIAGNOSTIC_V2,
}

/** One bounded primary reason per retained sample; safe for an operational metric tag. */
enum class EtaAccuracyEligibilityReason {
    ELIGIBLE,
    UNVERIFIED_USER_NOW,
    UNVERIFIED_USER_ADJUSTED,
    UNVERIFIED_GEOFENCE,
    MISSING_CLIENT_APP_VERSION,
    MISSING_CLIENT_BUILD_VERSION,
    UNVERSIONED_BACKEND_COHORT,
    UNKNOWN_ALGORITHM_VERSION,
    /** Duration predictions remain diagnostic until departure time has server-owned verification. */
    UNVERIFIED_DEPARTURE,
    OBSERVATION_PRECISION_TOO_COARSE,
    STALE_ETA,
    UNSUPPORTED_ETA_SOURCE,
    UNSUPPORTED_PROVIDER,
    MISSING_PROVIDER_FETCH_TIME,
    PROVIDER_FETCH_AFTER_DEPARTURE,
    PROVIDER_PREDICTION_TOO_OLD,
    PREDICTION_EVALUATED_AFTER_DEPARTURE,
    PREDICTION_TOO_OLD,
    PROVIDER_ABSOLUTE_DEPARTURE_OFFSET_TOO_LARGE,
    ACTUAL_TRAVEL_DURATION_IMPLAUSIBLE,
}

const val MAX_ARRIVAL_OBSERVATION_PRECISION_SECONDS = 3_600
const val MAX_ARRIVAL_OBSERVATION_ADJUSTMENT_SECONDS = 3_600

internal fun Long.safeAbsolute(): Long = when {
    this == Long.MIN_VALUE -> Long.MAX_VALUE
    this < 0 -> -this
    else -> this
}
