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
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.util.UUID

/**
 * ETA 계산 결과 한 건을 보존하는 이력. 정상 결과와 fallback을 모두 같은 형식으로 기록한다.
 *
 * 이후 정상 복구가 과거 실패를 덮어쓰지 않도록 변경 메서드를 제공하지 않는다.
 * 이 값은 계산 당시의 결과이며, worker가 이전 값을 유지해 실제 알람에 반영한 시각과는 다르다.
 * 공급자 ID와 원천 관측시각은 현재 결과에 명시적으로 전달되지 않으므로 추정해 기록하지 않는다.
 */
@Entity
@Immutable
@Table(
    name = "schedule_eta_evaluation_history",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_eta_evaluation_id",
            columnNames = ["evaluation_id"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_eta_evaluation_schedule_member_time",
            columnList = "schedule_id, member_id, evaluated_at, id",
        ),
        Index(
            name = "idx_eta_evaluation_job_time",
            columnList = "push_job_id, evaluated_at, id",
        ),
        Index(
            name = "idx_eta_evaluation_time",
            columnList = "evaluated_at, id",
        ),
    ],
)
class ScheduleEtaEvaluationHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * 평가를 시작할 때 만든 UUID를 전달한다. 같은 평가의 저장 재시도에서는 재사용한다.
     * 푸시 재시도 중에는 checkCount가 같아도 새 ETA 평가가 발생하므로 이를 고유 키로 쓰지 않는다.
     */
    @Column(name = "evaluation_id", nullable = false, length = 36)
    var evaluationId: String,

    @Column(name = "push_job_id", nullable = false)
    var jobId: Long,

    @Column(name = "schedule_id", nullable = false)
    var scheduleId: Long,

    @Column(name = "member_id", nullable = false)
    var memberId: Long,

    /** 계산 요청의 기준 시각. 공급자의 데이터 시각이나 DB 저장 시각으로 대체하지 않는다. */
    @Column(name = "evaluated_at", nullable = false)
    var evaluatedAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_source", nullable = false, length = 30)
    var source: TrafficSource,

    /** LIVE/TIMETABLE 결과에도 저신뢰도 진단이 있으므로 source와 별도로 보존한다. */
    @Column(name = "eta_stale", nullable = false)
    var stale: Boolean,

    /** 저장 경계에서 정제한 실패 코드·안전한 메시지. 정상 결과는 null로 기록한다. */
    @Column(name = "failure_reason", length = 500)
    var failureReason: String?,

    @Column(name = "travel_minutes", nullable = false)
    var travelMinutes: Int,

    /** 계산하지 못한 시각은 null로 남긴다. 이전 성공값이나 알람에 적용한 시각으로 채우지 않는다. */
    @Column(name = "recommended_departure_at")
    var recommendedDepartureAt: Instant?,

    /** 정시 도착 불가 진단도 남겨야 하므로 목표 도착시각보다 늦은 예측을 허용한다. */
    @Column(name = "predicted_arrival_at")
    var predictedArrivalAt: Instant?,

    @Column(name = "target_arrival_at", nullable = false)
    var targetArrivalAt: Instant,

    /**
     * TrafficResult.fetchedAt 원값. 저장값만 쓴 fallback은 null이지만, 재조회 성공 후
     * 시각 근거 부족으로 채택하지 못한 진단은 취득 시각을 보존한다. 차량 원천 관측시각은 아니다.
     */
    @Column(name = "provider_fetched_at")
    var fetchedAt: Instant?,
) {
    init {
        require(UUID.fromString(evaluationId).toString() == evaluationId) {
            "evaluationId는 소문자 표준 UUID 문자열이어야 합니다."
        }
        require(jobId > 0 && scheduleId > 0 && memberId > 0) {
            "ETA 평가 이력에는 유효한 작업·일정·회원 ID가 필요합니다."
        }
        require(travelMinutes > 0) { "ETA 이동 시간은 0보다 커야 합니다." }
        val reason = failureReason
        require(reason == null || (reason.isNotBlank() && reason.length <= 500)) {
            "실패 사유는 null 또는 500자 이내의 비어 있지 않은 안전한 메시지여야 합니다."
        }
        val providerSource = source == TrafficSource.LIVE_PROVIDER ||
            source == TrafficSource.TIMETABLE_PROVIDER
        require(!providerSource || fetchedAt != null) {
            "공급자 결과에는 fetchedAt이 필요합니다."
        }
        require(providerSource || fetchedAt == null || (stale && !reason.isNullOrBlank())) {
            "fallback의 provider fetchedAt은 실패 사유가 있는 저신뢰도 조회 진단에만 기록합니다."
        }
        val departureAt = recommendedDepartureAt
        val arrivalAt = predictedArrivalAt
        require(
            departureAt == null || arrivalAt == null || !departureAt.isAfter(arrivalAt)
        ) { "추천 출발시각은 예측 도착시각보다 늦을 수 없습니다." }
    }


}
