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
import jakarta.persistence.Version
import org.hibernate.annotations.Comment
import java.time.Instant

@Entity
@Table(
    name = "schedule_push_job",
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

    @Column(name = "last_checked_at")
    @Comment("마지막 교통상황 체크 실행 시간")
    var lastCheckedAt: Instant? = null
        protected set

    @Column(name = "last_pushed_at")
    @Comment("마지막 푸시 발송 시간")
    var lastPushedAt: Instant? = null
        protected set

    @Column(name = "check_count", nullable = false)
    @Comment("교통상황 체크 횟수")
    var checkCount: Int = 0
        protected set

    @Column(name = "retry_count", nullable = false)
    @Comment("실패 또는 재시도 횟수")
    var retryCount: Int = 0
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
    fun startProcessing(workerId: String) {
        status = SchedulePushJobStatus.PROCESSING
        lockedBy = workerId
        lockedAt = Instant.now()
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

    /**
     * 교통상황 체크 후 이동시간, 추천 출발 시간, 푸시 발송 여부를 반영한다.
     */
    fun finishCheck(
        travelMinutes: Int,
        recommendedDepartureAt: Instant,
        pushSent: Boolean,
        notifiedDepartureAt: Instant?,
        nextCheckAt: Instant?,
        completeAfterCheck: Boolean,
        now: Instant = Instant.now()
    ) {
        lastTravelMinutes = travelMinutes
        lastRecommendedDepartureAt = recommendedDepartureAt
        lastCheckedAt = now
        checkCount += 1

        if (pushSent) {
            lastPushedAt = now
            lastNotifiedDepartureAt = notifiedDepartureAt
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

    /**
     * 일정 시간, 출발 시간, 모니터링 시작 시간, 체크 간격이 변경되었을 때 작업 정보를 갱신한다.
     */
    fun changeSchedule(
        scheduleAt: Instant,
        departureAt: Instant,
        monitorStartAt: Instant,
        intervalMinutes: Int
    ) {
        validateScheduleTime(
            scheduleAt = scheduleAt,
            departureAt = departureAt,
            monitorStartAt = monitorStartAt
        )
        validateInterval(intervalMinutes)

        this.scheduleAt = scheduleAt
        this.departureAt = departureAt
        this.monitorStartAt = monitorStartAt
        this.intervalMinutes = intervalMinutes
        this.nextCheckAt = monitorStartAt
        this.status = SchedulePushJobStatus.ACTIVE
        this.lastTravelMinutes = null
        this.lastRecommendedDepartureAt = null
        this.lastNotifiedDepartureAt = null
        this.lastCheckedAt = null
        this.lastPushedAt = null
        this.checkCount = 0
        this.retryCount = 0
        this.failureReason = null

        clearLock()
    }

    /**
     * 현재 시간이 일정 시간을 지났는지 확인한다.
     */
    fun isExpired(now: Instant): Boolean {
        return !now.isBefore(scheduleAt)
    }

    private fun clearLock() {
        lockedBy = null
        lockedAt = null
    }

    companion object {
        private val ALLOWED_INTERVALS = setOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)

        /**
         * SchedulePushJob을 생성한다.
         */
        fun create(
            memberId: Long,
            scheduleId: Long,
            scheduleAt: Instant,
            departureAt: Instant,
            monitorStartAt: Instant,
            intervalMinutes: Int
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
