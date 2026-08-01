package com.noLate.schedule.domain

import com.noLate.global.common.BaseEntity
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
import jakarta.persistence.Version
import org.hibernate.annotations.Comment
import java.time.Instant

/**
 * 공유 일정에서 "일정 자체"와 "각 참가자의 출발 여부"를 분리해 저장한다.
 *
 * schedules.route.departedAt은 기존 오너 일정 알림을 중지하는 값으로 남겨 두고,
 * 이 테이블은 오너/공유 대상자 각각의 출발 완료 상태를 표현한다. 이렇게 해야 공유받은
 * 사용자가 출발 완료를 눌러도 오너나 다른 참가자의 상태를 덮어쓰지 않는다.
 */
@Entity
@Table(
    name = "schedule_departure_statuses",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_departure_statuses_schedule_member",
            columnNames = ["schedule_id", "member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_departure_statuses_schedule", columnList = "schedule_id"),
        Index(name = "idx_schedule_departure_statuses_member", columnList = "member_id"),
    ],
)
@Comment("공유 일정 참가자별 출발 완료 상태")
class ScheduleDepartureStatus(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("공유 일정 출발 상태 PK")
    var id: Long? = null,

    @Column(name = "schedule_id", nullable = false)
    @Comment("일정 id")
    var scheduleId: Long = 0L,

    @Column(name = "member_id", nullable = false)
    @Comment("출발 상태를 가진 회원 id")
    var memberId: Long = 0L,

    @Column(name = "departed_at")
    @Comment("처음 출발 완료 처리한 시각")
    var departedAt: Instant? = null,

    /**
     * 같은 참가자가 거의 동시에 출발 완료를 누르는 경우 최초 완료 시각 보존 정책을
     * 깨지 않도록 낙관적 락을 둔다. 서비스에서는 schedule row 잠금으로 생성 경합도 줄인다.
     */
    @Version
    @Column(nullable = false)
    var version: Long = 0L,
) : BaseEntity() {

    @Column(name = "eta_snapshot_push_job_id")
    @Comment("출발 전환 시 동결한 ETA job id; job 생명주기와 FK로 결합하지 않음")
    var etaSnapshotPushJobId: Long? = null
        protected set

    @Column(name = "eta_snapshot_evaluated_at")
    @Comment("출발 전환 시 동결한 ETA 계산 시각")
    var etaSnapshotEvaluatedAt: Instant? = null
        protected set

    @Column(name = "eta_snapshot_recommended_departure_at")
    @Comment("출발 전환 시 동결한 추천 출발 시각")
    var etaSnapshotRecommendedDepartureAt: Instant? = null
        protected set

    @Column(name = "eta_snapshot_predicted_arrival_at")
    @Comment("출발 전환 시 동결한 목적지 도착 예측 시각")
    var etaSnapshotPredictedArrivalAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_snapshot_source", length = 30)
    @Comment("출발 전환 시 동결한 ETA 출처")
    var etaSnapshotSource: TrafficSource? = null
        protected set

    @Column(name = "eta_snapshot_stale")
    @Comment("출발 전환 시 동결한 ETA stale 여부")
    var etaSnapshotStale: Boolean? = null
        protected set

    @Column(name = "eta_snapshot_travel_minutes")
    @Comment("출발 전환 시 동결한 ETA 이동시간")
    var etaSnapshotTravelMinutes: Int? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_snapshot_prediction_basis", length = 40)
    @Comment("절대 provider 도착시각 또는 실제 출발 기준 이동시간 예측")
    var etaSnapshotPredictionBasis: EtaPredictionBasis? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_snapshot_travel_mode", length = 20)
    @Comment("출발 전환 시 동결한 이동 수단")
    var etaSnapshotTravelMode: ScheduleTravelMode? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_snapshot_provider_id", length = 30)
    @Comment("낮은 cardinality의 ETA provider 식별자")
    var etaSnapshotProviderId: EtaProviderId? = null
        protected set

    @Column(name = "eta_snapshot_target_arrival_at")
    @Comment("출발 전환 시 동결한 일정 목표 도착 시각")
    var etaSnapshotTargetArrivalAt: Instant? = null
        protected set

    @Column(name = "eta_snapshot_on_time_arrival_possible")
    @Comment("동결된 예측이 목표 도착시각 이내인지 여부")
    var etaSnapshotOnTimeArrivalPossible: Boolean? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_snapshot_algorithm_version", length = 40)
    @Comment("동결된 ETA 계산 규칙의 낮은 cardinality 버전")
    var etaSnapshotAlgorithmVersion: EtaAlgorithmVersion? = null
        protected set

    @Column(name = "eta_snapshot_provider_fetched_at")
    @Comment("동결된 provider 응답 취득 시각; fallback은 null")
    var etaSnapshotProviderFetchedAt: Instant? = null
        protected set

    @Column(name = "eta_observation_exposed_at")
    @Comment("실제 도착 기록 UI가 처음 노출된 서버 수신 시각")
    var etaObservationExposedAt: Instant? = null
        protected set

    @Column(name = "eta_observation_exposed_client_app_version", length = 64)
    @Comment("최초 도착 기록 UI 노출 클라이언트 앱 버전")
    var etaObservationExposedClientAppVersion: String? = null
        protected set

    @Column(name = "eta_observation_exposed_client_build_version", length = 64)
    @Comment("최초 도착 기록 UI 노출 클라이언트 빌드 버전")
    var etaObservationExposedClientBuildVersion: String? = null
        protected set

    @Column(name = "eta_observation_exposed_ux_variant", length = 64)
    @Comment("최초 도착 기록 UI 노출 UX variant")
    var etaObservationExposedUxVariant: String? = null
        protected set

    @Column(name = "eta_observation_prompted_at")
    @Comment("실제 도착 기록 확인창을 처음 연 서버 수신 시각")
    var etaObservationPromptedAt: Instant? = null
        protected set

    @Column(name = "eta_observation_prompted_client_app_version", length = 64)
    @Comment("최초 도착 기록 확인창 클라이언트 앱 버전")
    var etaObservationPromptedClientAppVersion: String? = null
        protected set

    @Column(name = "eta_observation_prompted_client_build_version", length = 64)
    @Comment("최초 도착 기록 확인창 클라이언트 빌드 버전")
    var etaObservationPromptedClientBuildVersion: String? = null
        protected set

    @Column(name = "eta_observation_prompted_ux_variant", length = 64)
    @Comment("최초 도착 기록 확인창 UX variant")
    var etaObservationPromptedUxVariant: String? = null
        protected set

    @Column(name = "eta_observation_responded_at")
    @Comment("실제 도착 관측이 처음 영속 저장된 서버 시각")
    var etaObservationRespondedAt: Instant? = null
        protected set

    /**
     * 최초 출발 시각만 기록하고 이번 호출이 실제 상태 전환이었는지 반환한다.
     *
     * 서비스가 이 반환값으로 푸시 이벤트 발행 여부를 결정한다. 일정 row 비관적 락 안에서
     * 호출되므로 같은 참가자의 동시 요청 중 정확히 한 요청만 true를 받고, 나머지는 저장된
     * 최초 시각을 유지한 채 false를 받는다.
     */
    fun keepFirstDeparture(now: Instant): Boolean {
        if (departedAt != null) return false

        departedAt = now
        return true
    }

    fun keepFirstEtaObservationExposure(
        now: Instant,
        clientAppVersion: String?,
        clientBuildVersion: String?,
        uxVariant: String?,
    ): Boolean {
        if (departedAt == null || etaObservationExposedAt != null) return false
        etaObservationExposedAt = now
        etaObservationExposedClientAppVersion = clientAppVersion
        etaObservationExposedClientBuildVersion = clientBuildVersion
        etaObservationExposedUxVariant = uxVariant
        return true
    }

    fun keepFirstEtaObservationPrompt(
        now: Instant,
        clientAppVersion: String?,
        clientBuildVersion: String?,
        uxVariant: String?,
    ): Boolean {
        if (departedAt == null || etaObservationPromptedAt != null) return false
        etaObservationPromptedAt = now
        etaObservationPromptedClientAppVersion = clientAppVersion
        etaObservationPromptedClientBuildVersion = clientBuildVersion
        etaObservationPromptedUxVariant = uxVariant
        return true
    }

    fun keepFirstEtaObservationResponse(now: Instant): Boolean {
        if (departedAt == null || etaObservationRespondedAt != null) return false
        etaObservationRespondedAt = now
        return true
    }

    /**
     * 최초 출발 전환과 같은 transaction에서 마지막 ETA를 불변 snapshot으로 동결한다.
     *
     * provider가 절대 도착시각을 주지 않는 도로 ETA는 실제 출발시각에 이동시간을 더해
     * duration 예측으로 고정한다. 이후 push job cancel/reset이 원본을 지워도 이 값은 바뀌지
     * 않는다. 필수 provenance가 하나라도 없는 legacy/incomplete job은 snapshot을 만들지 않는다.
     */
    fun freezeEtaSnapshot(
        job: SchedulePushJob,
        fallbackTravelMode: ScheduleTravelMode? = null,
    ): Boolean {
        if (etaSnapshotPredictionBasis != null) return false
        require(job.scheduleId == scheduleId && job.memberId == memberId) {
            "출발 ETA snapshot의 일정/회원 경계가 일치해야 합니다."
        }
        val departedAt = departedAt ?: return false
        val evaluatedAt = job.lastCheckedAt ?: return false
        val recommendedDepartureAt = job.lastRecommendedDepartureAt ?: return false
        val travelMinutes = job.lastTravelMinutes?.takeIf { it > 0 } ?: return false
        val source = job.lastEtaSource ?: return false
        val travelMode = job.lastEtaTravelMode ?: fallbackTravelMode ?: return false
        val algorithmVersion = job.lastEtaAlgorithmVersion
            ?: EtaAlgorithmVersion.infer(source, travelMode)
        val absolutePrediction = job.lastPredictedArrivalAt
        val basis = if (absolutePrediction != null) {
            EtaPredictionBasis.PROVIDER_ABSOLUTE
        } else {
            EtaPredictionBasis.DEPARTURE_ANCHORED_DURATION
        }

        etaSnapshotPushJobId = job.id
        etaSnapshotEvaluatedAt = evaluatedAt
        etaSnapshotRecommendedDepartureAt = recommendedDepartureAt
        val predictedArrivalAt = absolutePrediction
            ?: departedAt.plusSeconds(travelMinutes.toLong() * 60L)
        etaSnapshotPredictedArrivalAt = predictedArrivalAt
        etaSnapshotSource = source
        etaSnapshotStale = job.lastEtaStale ?: true
        etaSnapshotTravelMinutes = travelMinutes
        etaSnapshotPredictionBasis = basis
        etaSnapshotTravelMode = travelMode
        etaSnapshotProviderId = EtaProviderId.infer(source, travelMode)
        etaSnapshotTargetArrivalAt = job.scheduleAt
        etaSnapshotOnTimeArrivalPossible = !predictedArrivalAt.isAfter(job.scheduleAt)
        etaSnapshotAlgorithmVersion = algorithmVersion
        etaSnapshotProviderFetchedAt = job.lastEtaProviderFetchedAt
        return true
    }
}
