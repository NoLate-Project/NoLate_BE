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
import java.time.Duration
import java.time.Instant

@Entity
@Table(
    name = "schedule_push_job",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_push_job_schedule_member",
            columnNames = ["schedule_id", "member_id"],
        )
    ],
    indexes = [
        Index(
            name = "idx_schedule_push_job_status_next_check_at",
            columnList = "status, next_check_at"
        ),
        Index(
            name = "idx_schedule_push_job_member_id",
            columnList = "member_id"
        ),
        Index(
            name = "idx_schedule_push_job_schedule_id",
            columnList = "schedule_id"
        )
    ]
)
@Comment("일정 푸시 작업")
class SchedulePushJob protected constructor() : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    @Comment("푸시 작업 PK")
    var id: Long? = null
        protected set

    @Version
    @Column(name = "version")
    @Comment("낙관적 락 버전")
    var version: Long? = null
        protected set

    @Column(name = "member_id", nullable = false)
    @Comment("회원 PK")
    var memberId: Long = 0L
        protected set

    @Column(name = "schedule_id", nullable = false)
    @Comment("일정 PK")
    var scheduleId: Long = 0L
        protected set

    @Column(name = "schedule_at", nullable = false)
    @Comment("실제 일정 시간")
    lateinit var scheduleAt: Instant
        protected set

    @Column(name = "departure_at", nullable = false)
    @Comment("최초 계산된 출발 권장 시간")
    lateinit var departureAt: Instant
        protected set

    @Column(name = "monitor_start_at", nullable = false)
    @Comment("교통상황 모니터링 시작 시간")
    lateinit var monitorStartAt: Instant
        protected set

    @Column(name = "interval_minutes", nullable = false)
    @Comment("교통상황 체크 간격")
    var intervalMinutes: Int = 20
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Comment("푸시 작업 상태")
    var status: SchedulePushJobStatus = SchedulePushJobStatus.ACTIVE
        protected set

    @Column(name = "next_check_at", nullable = false)
    @Comment("다음 교통상황 체크 시간")
    lateinit var nextCheckAt: Instant
        protected set

    @Column(name = "last_travel_minutes")
    @Comment("마지막 조회 이동 시간")
    var lastTravelMinutes: Int? = null
        protected set

    @Column(name = "last_recommended_departure_at")
    @Comment("마지막 교통상황 기준 추천 출발 시간")
    var lastRecommendedDepartureAt: Instant? = null
        protected set

    @Column(name = "last_notified_departure_at")
    @Comment("마지막으로 사용자에게 푸시 안내한 추천 출발 시간")
    var lastNotifiedDepartureAt: Instant? = null
        protected set

    @Column(name = "last_reminder_boundary_at")
    @Comment("마지막으로 발송한 5분 단위 리마인드 경계 시간")
    var lastReminderBoundaryAt: Instant? = null
        protected set

    @Column(name = "last_handled_departure_at")
    @Comment("확인 성공 또는 ambiguous terminal이 처리한 마지막 추천 출발 시각")
    var lastHandledDepartureAt: Instant? = null
        protected set

    @Column(name = "last_handled_reminder_boundary_at")
    @Comment("확인 성공 또는 ambiguous terminal이 처리한 마지막 reminder 경계")
    var lastHandledReminderBoundaryAt: Instant? = null
        protected set

    @Column(name = "last_checked_at")
    @Comment("마지막 교통상황 체크 실행 시간")
    var lastCheckedAt: Instant? = null
        protected set

    @Column(name = "last_live_fetched_at")
    @Comment("마지막 실시간 provider 응답 취득 시간")
    var lastLiveFetchedAt: Instant? = null
        protected set

    @Column(name = "last_eta_provider_fetched_at")
    @Comment("마지막 ETA provider 응답 취득 시각; live comparator와 분리")
    var lastEtaProviderFetchedAt: Instant? = null
        protected set

    @Column(name = "last_live_travel_minutes")
    @Comment("마지막 신뢰 가능한 실시간 provider 이동 시간")
    var lastLiveTravelMinutes: Int? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "last_eta_source", length = 30)
    @Comment("마지막 ETA 출처")
    var lastEtaSource: TrafficSource? = null
        protected set

    @Column(name = "last_eta_stale")
    @Comment("마지막 ETA가 저장 fallback인지 여부")
    var lastEtaStale: Boolean? = null
        protected set

    @Column(name = "last_eta_failure_reason", length = 500)
    @Comment("마지막 ETA fallback 안정 reason code와 안전 메시지")
    var lastEtaFailureReason: String? = null
        protected set

    @Column(name = "last_predicted_arrival_at")
    @Comment("마지막 provider/overlay가 예측한 목적지 도착 시각")
    var lastPredictedArrivalAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "last_eta_travel_mode", length = 20)
    @Comment("마지막 ETA snapshot의 이동 수단")
    var lastEtaTravelMode: ScheduleTravelMode? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "last_eta_algorithm_version", length = 40)
    @Comment("마지막 ETA 계산 규칙의 낮은 cardinality 버전")
    var lastEtaAlgorithmVersion: EtaAlgorithmVersion? = null
        protected set

    @Column(name = "last_eta_route_fingerprint", length = 64)
    @Comment("마지막 ETA snapshot을 계산한 회원 경로 지문")
    var lastEtaRouteFingerprint: String? = null
        protected set

    @Column(name = "last_traffic_change_minutes")
    @Comment("비교 가능한 live-to-live ETA의 마지막 변경량(분)")
    var lastTrafficChangeMinutes: Int? = null
        protected set

    @Column(name = "last_changed_at")
    @Comment("비교 가능한 live-to-live ETA가 마지막으로 변경된 확인 시각")
    var lastChangedAt: Instant? = null
        protected set

    @Column(name = "last_pushed_at")
    @Comment("마지막 푸시 발송 시간")
    var lastPushedAt: Instant? = null
        protected set

    @Column(name = "departure_notice_sent_at")
    @Comment("첫 지금 출발 알림 발송 시간")
    var departureNoticeSentAt: Instant? = null
        protected set

    @Column(name = "handled_departure_notice_at")
    @Comment("확인 성공 또는 ambiguous terminal의 최초 DEPART_NOW 논리 처리 시각")
    var handledDepartureNoticeAt: Instant? = null
        protected set

    @Column(name = "last_departure_reminder_stage", length = 40)
    @Comment("마지막으로 처리한 출발 후속 알림 단계")
    var lastDepartureReminderStage: String? = null
        protected set

    @Column(name = "last_departure_reminder_boundary_at")
    @Comment("마지막으로 처리한 출발 후속 알림 경계 시각")
    var lastDepartureReminderBoundaryAt: Instant? = null
        protected set

    @Column(name = "last_handled_departure_reminder_stage", length = 40)
    @Comment("확인 성공 또는 ambiguous terminal의 마지막 논리 reminder 단계")
    var lastHandledDepartureReminderStage: String? = null
        protected set

    @Column(name = "last_handled_departure_reminder_boundary_at")
    @Comment("확인 성공 또는 ambiguous terminal의 마지막 논리 reminder 경계")
    var lastHandledDepartureReminderBoundaryAt: Instant? = null
        protected set

    @Column(name = "last_uncertain_at")
    @Comment("가장 최근 ambiguous terminal 처리 시각")
    var lastUncertainAt: Instant? = null
        protected set

    @Column(name = "snoozed_until")
    @Comment("사용자가 다시 알림을 요청한 시각")
    var snoozedUntil: Instant? = null
        protected set

    @Column(name = "check_count", nullable = false)
    @Comment("교통상황 체크 횟수")
    var checkCount: Int = 0
        protected set

    @Column(name = "retry_count", nullable = false)
    @Comment("실패 또는 재시도 횟수")
    var retryCount: Int = 0
        protected set

    @Column(name = "notification_generation", nullable = false)
    @Comment("일정 의미 변경 시 증가하는 알림 이벤트 세대")
    var notificationGeneration: Long = 0
        protected set

    @Column(name = "notification_input_fingerprint", nullable = false, length = 64)
    @Comment("알림 의미 입력의 결정적 SHA-256 지문")
    var notificationInputFingerprint: String = ""
        protected set

    @Column(name = "locked_by", length = 100)
    @Comment("작업을 선점한 Worker 식별자")
    var lockedBy: String? = null
        protected set

    @Column(name = "locked_at")
    @Comment("작업 선점 시간")
    var lockedAt: Instant? = null
        protected set

    @Column(name = "failure_reason", length = 500)
    @Comment("마지막 실패 사유")
    var failureReason: String? = null
        protected set

    /**
     * Scheduler 또는 Worker가 작업을 처리하기 시작할 때 호출한다.
     */
    fun startProcessing(workerId: String, now: Instant = Instant.now()) {
        status = SchedulePushJobStatus.PROCESSING
        lockedBy = workerId
        lockedAt = now
    }

    fun refreshLease(workerId: String, now: Instant) {
        check(status == SchedulePushJobStatus.PROCESSING && lockedBy == workerId) {
            "현재 lease owner만 heartbeat를 갱신할 수 있습니다."
        }
        lockedAt = now
    }

    /**
     * 작업을 정상 완료 상태로 변경한다.
     */
    fun complete() {
        status = SchedulePushJobStatus.COMPLETED
        clearLock()
    }

    /**
     * 일정 삭제, 일정 취소, 푸시 비활성화 시 작업을 취소 상태로 변경한다.
     */
    fun cancel() {
        status = SchedulePushJobStatus.CANCELED
        clearLiveComparatorChain()
        lastEtaRouteFingerprint = null
        lastPredictedArrivalAt = null
        lastEtaProviderFetchedAt = null
        lastEtaAlgorithmVersion = null
        clearLock()
    }

    /**
     * 복구가 어려운 실패 상태로 변경한다.
     */
    fun fail(reason: String) {
        status = SchedulePushJobStatus.FAILED
        failureReason = reason
        retryCount += 1
        clearLock()
    }

    /**
     * 일시적인 실패가 발생했을 때 다음 처리 시간을 다시 예약한다.
     */
    fun retryLater(reason: String, nextCheckAt: Instant) {
        status = SchedulePushJobStatus.ACTIVE
        failureReason = reason
        retryCount += 1
        this.nextCheckAt = nextCheckAt
        clearLock()
    }

    fun recoverProcessingTimeout(reason: String, nextCheckAt: Instant) {
        status = SchedulePushJobStatus.ACTIVE
        failureReason = reason
        retryCount += 1
        this.nextCheckAt = nextCheckAt
        clearLock()
    }

    /**
     * 회차가 일부 기기의 확인된 실패 때문에 끝나지 않아도 이미 확인된 성공은 운영 지표에 남긴다.
     * reminder stage와 check count는 모든 재시도 가능 기기가 terminal이 될 때까지 건드리지 않는다.
     */
    fun recordConfirmedPush(at: Instant) {
        lastPushedAt = listOfNotNull(lastPushedAt, at).maxOrNull()
    }

    /**
     * Source lease가 ambiguous로 회차를 닫은 뒤 safety outbox가 같은 immutable event의
     * 확정 성공을 얻었을 때 confirmed 지표만 멱등 보정한다.
     *
     * check/status/lease는 되감지 않는다. 바로 다음 회차까지만 event 의미 필드를 보정하고,
     * 그보다 뒤의 회차가 이미 진행됐다면 최신 의미를 덮지 않도록 성공 시각만 남긴다.
     */
    fun reconcileLateConfirmedPush(
        eventCheckCount: Int,
        confirmedAt: Instant,
        notifiedDepartureAt: Instant?,
        reminderBoundaryAt: Instant?,
        departureReminderStage: ScheduleDepartureReminderStage?,
    ) {
        if (eventCheckCount < 0 || checkCount < eventCheckCount) return
        recordConfirmedPush(confirmedAt)
        if (checkCount > eventCheckCount + 1) return

        if (notifiedDepartureAt != null) {
            lastNotifiedDepartureAt = notifiedDepartureAt
            lastHandledDepartureAt = notifiedDepartureAt
        }
        if (reminderBoundaryAt != null) {
            lastReminderBoundaryAt = reminderBoundaryAt
            lastHandledReminderBoundaryAt = reminderBoundaryAt
        }
        if (departureReminderStage == null) return

        val boundaryAt = when {
            lastHandledDepartureReminderStage == departureReminderStage.name ->
                lastHandledDepartureReminderBoundaryAt

            departureReminderStage == ScheduleDepartureReminderStage.DEPART_NOW ->
                notifiedDepartureAt

            departureReminderStage == ScheduleDepartureReminderStage.AFTER_DEPARTURE_3 ->
                (handledDepartureNoticeAt ?: departureNoticeSentAt)?.plusSeconds(3 * 60)

            departureReminderStage == ScheduleDepartureReminderStage.AFTER_DEPARTURE_7 ->
                (handledDepartureNoticeAt ?: departureNoticeSentAt)?.plusSeconds(7 * 60)

            departureReminderStage == ScheduleDepartureReminderStage.BEFORE_SCHEDULE_3 ->
                scheduleAt.minusSeconds(3 * 60)

            departureReminderStage == ScheduleDepartureReminderStage.BEFORE_SCHEDULE_1 ->
                scheduleAt.minusSeconds(60)

            else -> null
        } ?: return
        val currentConfirmedBoundary = lastDepartureReminderBoundaryAt
        if (currentConfirmedBoundary == null || !boundaryAt.isBefore(currentConfirmedBoundary)) {
            lastDepartureReminderStage = departureReminderStage.name
            lastDepartureReminderBoundaryAt = boundaryAt
        }
        if (departureReminderStage == ScheduleDepartureReminderStage.DEPART_NOW) {
            if (departureNoticeSentAt == null) {
                departureNoticeSentAt = confirmedAt
            }
            if (handledDepartureNoticeAt == null) {
                handledDepartureNoticeAt = confirmedAt
            }
        }
    }

    /**
     * 교통상황 체크 후 이동시간, 추천 출발 시간, 푸시 발송 여부를 반영한다.
     */
    fun recordEtaEvaluationBeforeRetry(
        travelMinutes: Int,
        recommendedDepartureAt: Instant,
        etaSource: TrafficSource,
        liveFetchedAt: Instant?,
        etaStale: Boolean,
        etaFailureReason: String?,
        predictedArrivalAt: Instant?,
        etaTravelMode: ScheduleTravelMode?,
        etaRouteFingerprint: String?,
        liveComparatorMaxAgeMinutes: Long = DEFAULT_LIVE_COMPARATOR_MAX_AGE_MINUTES,
        now: Instant = Instant.now(),
    ) {
        applyEtaEvaluation(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            etaSource = etaSource,
            liveFetchedAt = liveFetchedAt,
            etaStale = etaStale,
            etaFailureReason = etaFailureReason,
            predictedArrivalAt = predictedArrivalAt,
            etaTravelMode = etaTravelMode,
            etaRouteFingerprint = etaRouteFingerprint,
            liveComparatorMaxAgeMinutes = liveComparatorMaxAgeMinutes,
            now = now,
        )
    }

    fun finishCheck(
        travelMinutes: Int,
        recommendedDepartureAt: Instant,
        pushSent: Boolean,
        notifiedDepartureAt: Instant?,
        pushConfirmed: Boolean = pushSent,
        pushConfirmedAt: Instant? = null,
        pushUncertain: Boolean = false,
        reminderBoundaryAt: Instant? = null,
        departureReminderStage: ScheduleDepartureReminderStage? = null,
        departureReminderBoundaryAt: Instant? = null,
        clearSnooze: Boolean = false,
        nextCheckAt: Instant?,
        completeAfterCheck: Boolean,
        etaSource: TrafficSource = TrafficSource.SAVED_FALLBACK,
        liveFetchedAt: Instant? = null,
        etaStale: Boolean = true,
        etaFailureReason: String? = null,
        predictedArrivalAt: Instant? = null,
        etaTravelMode: ScheduleTravelMode? = null,
        etaRouteFingerprint: String? = null,
        liveComparatorMaxAgeMinutes: Long = DEFAULT_LIVE_COMPARATOR_MAX_AGE_MINUTES,
        now: Instant = Instant.now()
    ) {
        applyEtaEvaluation(
            travelMinutes = travelMinutes,
            recommendedDepartureAt = recommendedDepartureAt,
            etaSource = etaSource,
            liveFetchedAt = liveFetchedAt,
            etaStale = etaStale,
            etaFailureReason = etaFailureReason,
            predictedArrivalAt = predictedArrivalAt,
            etaTravelMode = etaTravelMode,
            etaRouteFingerprint = etaRouteFingerprint,
            liveComparatorMaxAgeMinutes = liveComparatorMaxAgeMinutes,
            now = now,
        )
        checkCount += 1
        retryCount = 0
        failureReason = null

        val notificationHandled = pushConfirmed || pushUncertain
        if (notificationHandled) {
            lastHandledDepartureAt = notifiedDepartureAt
            if (reminderBoundaryAt != null) {
                lastHandledReminderBoundaryAt = reminderBoundaryAt
            }
        }
        if (pushConfirmed) {
            lastNotifiedDepartureAt = notifiedDepartureAt
            if (reminderBoundaryAt != null) {
                lastReminderBoundaryAt = reminderBoundaryAt
            }
            val confirmedAt = pushConfirmedAt ?: now
            lastPushedAt = listOfNotNull(lastPushedAt, confirmedAt).maxOrNull()
        }
        if (pushUncertain) {
            lastUncertainAt = now
        }

        if (departureReminderStage != null && notificationHandled) {
            val boundaryAt = requireNotNull(departureReminderBoundaryAt) {
                "출발 후속 알림 단계에는 경계 시각이 필요합니다."
            }
            lastHandledDepartureReminderStage = departureReminderStage.name
            lastHandledDepartureReminderBoundaryAt = boundaryAt
            if (
                departureReminderStage == ScheduleDepartureReminderStage.DEPART_NOW &&
                handledDepartureNoticeAt == null
            ) {
                handledDepartureNoticeAt = if (pushConfirmed) {
                    pushConfirmedAt ?: now
                } else {
                    now
                }
            }
            if (pushConfirmed) {
                lastDepartureReminderStage = departureReminderStage.name
                lastDepartureReminderBoundaryAt = boundaryAt
            }
            if (
                pushConfirmed &&
                departureReminderStage == ScheduleDepartureReminderStage.DEPART_NOW &&
                departureNoticeSentAt == null
            ) {
                // 후속 +3/+7분 알림은 실제로 사용자에게 처음 출발을 재촉한 시각을 기준으로 삼는다.
                departureNoticeSentAt = pushConfirmedAt ?: now
            }
        }

        if (clearSnooze) {
            snoozedUntil = null
        }

        if (completeAfterCheck) {
            complete()
            return
        }

        this.nextCheckAt = requireNotNull(nextCheckAt) {
            "계속 처리할 작업에는 다음 체크 시각이 필요합니다."
        }
        status = SchedulePushJobStatus.ACTIVE
        clearLock()
    }

    private fun applyEtaEvaluation(
        travelMinutes: Int,
        recommendedDepartureAt: Instant,
        etaSource: TrafficSource,
        liveFetchedAt: Instant?,
        etaStale: Boolean,
        etaFailureReason: String?,
        predictedArrivalAt: Instant?,
        etaTravelMode: ScheduleTravelMode?,
        etaRouteFingerprint: String?,
        liveComparatorMaxAgeMinutes: Long,
        now: Instant,
    ) {
        if (etaSource in PROVIDER_ETA_SOURCES) {
            requireNotNull(liveFetchedAt) {
                "provider ETA에는 provider 취득 시각이 필요합니다."
            }
        }
        val onTimeUnavailableDiagnostic =
            etaStale &&
                etaFailureReason?.startsWith("TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE:") == true
        require(
            predictedArrivalAt == null ||
                !predictedArrivalAt.isAfter(scheduleAt) ||
                onTimeUnavailableDiagnostic
        ) {
            "목표 도착시각을 넘긴 여정은 정시 도착 불가 진단으로만 저장할 수 있습니다."
        }
        require(predictedArrivalAt == null || !predictedArrivalAt.isBefore(recommendedDepartureAt)) {
            "예측 도착시각은 추천 출발시각보다 빠를 수 없습니다."
        }
        if (etaRouteFingerprint != null && etaRouteFingerprint != lastEtaRouteFingerprint) {
            clearLiveComparatorChain()
        }
        val evaluatedAt = maxOf(now, liveFetchedAt ?: now)
        val comparableLiveTravelMinutes = liveFetchedAt
            ?.takeIf { etaSource == TrafficSource.LIVE_PROVIDER }
            ?.let {
                comparableLiveTravelMinutes(
                    currentLiveFetchedAt = it,
                    maxAgeMinutes = liveComparatorMaxAgeMinutes,
                    routeFingerprint = etaRouteFingerprint,
                )
            }
        comparableLiveTravelMinutes?.let { previousTravelMinutes ->
            if (previousTravelMinutes != travelMinutes) {
                lastTrafficChangeMinutes = travelMinutes - previousTravelMinutes
                lastChangedAt = evaluatedAt
            }
        }
        lastTravelMinutes = travelMinutes
        lastRecommendedDepartureAt = recommendedDepartureAt
        lastCheckedAt = evaluatedAt
        if (etaSource == TrafficSource.LIVE_PROVIDER) {
            lastLiveTravelMinutes = travelMinutes
            lastLiveFetchedAt = liveFetchedAt
        }
        lastEtaSource = etaSource
        lastEtaStale = etaStale
        lastEtaFailureReason = etaFailureReason
        lastPredictedArrivalAt = predictedArrivalAt
        lastEtaTravelMode = etaTravelMode
        lastEtaProviderFetchedAt = liveFetchedAt
            ?.takeIf { etaSource in PROVIDER_ETA_SOURCES }
        lastEtaAlgorithmVersion = EtaAlgorithmVersion.infer(etaSource, etaTravelMode)
        lastEtaRouteFingerprint = etaRouteFingerprint
    }

    /**
     * 일정 시간, 출발 시간, 모니터링 시작 시간, 체크 간격이 변경되었을 때 작업 정보를 갱신한다.
     */
    fun changeSchedule(
        scheduleAt: Instant,
        departureAt: Instant,
        monitorStartAt: Instant,
        intervalMinutes: Int,
        notificationInputFingerprint: String = ScheduleNotificationInputFingerprint.legacy(
            memberId = memberId,
            scheduleId = scheduleId,
            scheduleAt = scheduleAt,
            departureAt = departureAt,
            monitorStartAt = monitorStartAt,
            intervalMinutes = intervalMinutes,
        ),
    ): Boolean {
        validateScheduleTime(
            scheduleAt = scheduleAt,
            departureAt = departureAt,
            monitorStartAt = monitorStartAt
        )
        validateInterval(intervalMinutes)

        if (
            this.notificationInputFingerprint == notificationInputFingerprint &&
            status != SchedulePushJobStatus.CANCELED
        ) {
            return false
        }

        this.scheduleAt = scheduleAt
        this.departureAt = departureAt
        this.monitorStartAt = monitorStartAt
        this.intervalMinutes = intervalMinutes
        this.notificationGeneration += 1
        this.notificationInputFingerprint = notificationInputFingerprint
        this.nextCheckAt = monitorStartAt
        this.status = SchedulePushJobStatus.ACTIVE
        this.lastTravelMinutes = null
        this.lastRecommendedDepartureAt = null
        this.lastNotifiedDepartureAt = null
        this.lastReminderBoundaryAt = null
        this.lastHandledDepartureAt = null
        this.lastHandledReminderBoundaryAt = null
        this.lastCheckedAt = null
        this.lastLiveFetchedAt = null
        this.lastLiveTravelMinutes = null
        this.lastEtaSource = null
        this.lastEtaStale = null
        this.lastEtaFailureReason = null
        this.lastPredictedArrivalAt = null
        this.lastEtaTravelMode = null
        this.lastEtaProviderFetchedAt = null
        this.lastEtaAlgorithmVersion = null
        this.lastEtaRouteFingerprint = null
        this.lastTrafficChangeMinutes = null
        this.lastChangedAt = null
        this.lastPushedAt = null
        this.departureNoticeSentAt = null
        this.handledDepartureNoticeAt = null
        this.lastDepartureReminderStage = null
        this.lastDepartureReminderBoundaryAt = null
        this.lastHandledDepartureReminderStage = null
        this.lastHandledDepartureReminderBoundaryAt = null
        this.lastUncertainAt = null
        this.snoozedUntil = null
        this.checkCount = 0
        this.retryCount = 0
        this.failureReason = null

        clearLock()
        return true
    }

    /**
     * 사용자가 "5분 뒤 다시 알림"을 선택하면 현재 frozen event generation을 폐기한다.
     *
     * 이미 provider에 전달된 성공은 보존하지만, in-flight/FAILED 기기의 old pre-snooze
     * payload는 persisted safety outbox가 generation mismatch로 terminal 처리한다.
     */
    fun snoozeUntil(nextCheckAt: Instant) {
        require(nextCheckAt.isBefore(scheduleAt)) {
            "다시 알림 시각은 일정 시작 전이어야 합니다."
        }

        status = SchedulePushJobStatus.ACTIVE
        notificationGeneration = Math.addExact(notificationGeneration, 1)
        checkCount = 0
        this.nextCheckAt = nextCheckAt
        this.snoozedUntil = nextCheckAt
        failureReason = null
        retryCount = 0
        clearLock()
    }

    /**
     * 현재 시간이 일정 시간을 지났는지 확인한다.
     */
    fun isExpired(now: Instant): Boolean {
        return !now.isBefore(scheduleAt)
    }

    fun isPastDeliveryWindow(now: Instant, graceMinutes: Long): Boolean {
        require(graceMinutes >= 0) { "graceMinutes는 0 이상이어야 합니다." }
        return now.isAfter(scheduleAt.plusSeconds(graceMinutes * 60))
    }

    fun comparableLiveTravelMinutes(
        currentLiveFetchedAt: Instant,
        maxAgeMinutes: Long,
        routeFingerprint: String? = null,
    ): Int? {
        require(maxAgeMinutes in 1..MAX_LIVE_COMPARATOR_AGE_MINUTES) {
            "live comparator freshness는 1~$MAX_LIVE_COMPARATOR_AGE_MINUTES 사이여야 합니다."
        }
        if (routeFingerprint != null && routeFingerprint != lastEtaRouteFingerprint) return null
        val baselineMinutes = lastLiveTravelMinutes ?: return null
        val baselineFetchedAt = lastLiveFetchedAt ?: return null
        val age = Duration.between(baselineFetchedAt, currentLiveFetchedAt)
        if (age.isNegative || age > Duration.ofMinutes(maxAgeMinutes)) return null
        return baselineMinutes
    }

    private fun clearLiveComparatorChain() {
        lastLiveFetchedAt = null
        lastLiveTravelMinutes = null
        lastTrafficChangeMinutes = null
        lastChangedAt = null
    }

    private fun clearLock() {
        lockedBy = null
        lockedAt = null
    }

    companion object {
        private val PROVIDER_ETA_SOURCES =
            setOf(TrafficSource.LIVE_PROVIDER, TrafficSource.TIMETABLE_PROVIDER)
        private val ALLOWED_INTERVALS = setOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)
        const val DEFAULT_LIVE_COMPARATOR_MAX_AGE_MINUTES = 60L
        const val MAX_LIVE_COMPARATOR_AGE_MINUTES = 10_080L

        /**
         * SchedulePushJob을 생성한다.
         */
        fun create(
            memberId: Long,
            scheduleId: Long,
            scheduleAt: Instant,
            departureAt: Instant,
            monitorStartAt: Instant,
            intervalMinutes: Int,
            notificationInputFingerprint: String = ScheduleNotificationInputFingerprint.legacy(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = scheduleAt,
                departureAt = departureAt,
                monitorStartAt = monitorStartAt,
                intervalMinutes = intervalMinutes,
            ),
        ): SchedulePushJob {
            require(memberId > 0) {
                "memberId는 0보다 커야 합니다. memberId=$memberId"
            }

            require(scheduleId > 0) {
                "scheduleId는 0보다 커야 합니다. scheduleId=$scheduleId"
            }

            validateScheduleTime(
                scheduleAt = scheduleAt,
                departureAt = departureAt,
                monitorStartAt = monitorStartAt
            )
            validateInterval(intervalMinutes)

            return SchedulePushJob().apply {
                this.memberId = memberId
                this.scheduleId = scheduleId
                this.scheduleAt = scheduleAt
                this.departureAt = departureAt
                this.monitorStartAt = monitorStartAt
                this.intervalMinutes = intervalMinutes
                this.status = SchedulePushJobStatus.ACTIVE
                this.nextCheckAt = monitorStartAt
                this.checkCount = 0
                this.retryCount = 0
                this.notificationGeneration = 0
                this.notificationInputFingerprint = notificationInputFingerprint
            }
        }

        private fun validateInterval(intervalMinutes: Int) {
            require(intervalMinutes in ALLOWED_INTERVALS) {
                "지원하지 않는 푸시 간격입니다. intervalMinutes=$intervalMinutes"
            }
        }

        private fun validateScheduleTime(
            scheduleAt: Instant,
            departureAt: Instant,
            monitorStartAt: Instant
        ) {
            require(!departureAt.isAfter(scheduleAt)) {
                "출발 시간은 일정 시간보다 늦을 수 없습니다."
            }

            require(!monitorStartAt.isAfter(departureAt)) {
                "모니터링 시작 시간은 출발 시간보다 늦을 수 없습니다."
            }
        }
    }
}
