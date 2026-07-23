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

enum class ScheduleRouteSetupReminderStatus {
    PENDING,
    SENT,
    CANCELLED,
    FAILED,
}

/**
 * 회원·일정·일정지문별 D-3 경로 미설정 알림 처리 상태다.
 *
 * 일정 시각이나 공통 목적지가 바뀌면 새 fingerprint로 다시 알려야 하지만, 같은 조건을 여러
 * worker가 동시에 스캔한 경우에는 한 번만 알려야 한다. 세 컬럼 유일키가 그 경계를 표현한다.
 */
@Entity
@Table(
    name = "schedule_route_setup_reminders",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_route_setup_reminders_schedule_member_fingerprint",
            columnNames = ["schedule_id", "member_id", "schedule_fingerprint"],
        ),
    ],
    indexes = [
        Index(name = "idx_route_setup_reminders_dispatch", columnList = "status,next_attempt_at,id"),
        Index(name = "idx_route_setup_reminders_member", columnList = "member_id,id"),
    ],
)
@Comment("D-3 개인 경로 미설정 알림 처리 상태")
class ScheduleRouteSetupReminder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "schedule_id", nullable = false)
    var scheduleId: Long = 0L,

    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0L,

    @Column(name = "schedule_fingerprint", nullable = false, length = 64)
    var scheduleFingerprint: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ScheduleRouteSetupReminderStatus = ScheduleRouteSetupReminderStatus.PENDING,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.EPOCH,

    @Column(name = "sent_at")
    var sentAt: Instant? = null,

    @Column(name = "last_error", length = 500)
    var lastError: String? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0L,
) : BaseEntity() {

    fun markSent(at: Instant) {
        status = ScheduleRouteSetupReminderStatus.SENT
        sentAt = at
        lastError = null
    }

    fun cancel() {
        status = ScheduleRouteSetupReminderStatus.CANCELLED
        lastError = null
    }

    fun retryOrFail(now: Instant, reason: String, maxAttempts: Int, retryDelaySeconds: Long) {
        attempts += 1
        lastError = reason.take(500)
        if (attempts >= maxAttempts) {
            status = ScheduleRouteSetupReminderStatus.FAILED
        } else {
            status = ScheduleRouteSetupReminderStatus.PENDING
            nextAttemptAt = now.plusSeconds(retryDelaySeconds)
        }
    }
}
