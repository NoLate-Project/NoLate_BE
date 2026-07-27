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
import org.hibernate.annotations.Comment
import java.time.Instant

enum class ScheduleNotificationActionType(
    val keyPrefix: String,
) {
    DEPART_NOW("departNow"),
    SNOOZE("snooze"),
}

/**
 * 알림 action의 durable 완료 receipt.
 *
 * 원문 Idempotency-Key는 인증 정보나 사용자 입력을 포함할 수 있으므로 저장하지 않는다.
 * case-sensitive SHA-256 지문만 전역 unique로 저장해 같은 key를 다른 계정/일정/action에
 * 재사용하는 요청도 조용히 별도 mutation으로 처리되지 않게 한다.
 */
@Entity
@Table(
    name = "schedule_notification_action_receipts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_notification_action_key_fingerprint",
            columnNames = ["key_fingerprint"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_schedule_notification_action_scope",
            columnList = "member_id, schedule_id, action_type",
        ),
    ],
)
@Comment("일정 알림 action 멱등 완료 receipt")
class ScheduleNotificationActionReceipt(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "key_fingerprint", nullable = false, length = 64)
    val keyFingerprint: String,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 24)
    val actionType: ScheduleNotificationActionType,

    @Column(name = "result_departed_at")
    var resultDepartedAt: Instant? = null,

    @Column(name = "result_snoozed_until")
    var resultSnoozedUntil: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    protected constructor() : this(
        keyFingerprint = "",
        memberId = 0L,
        scheduleId = 0L,
        actionType = ScheduleNotificationActionType.SNOOZE,
        createdAt = Instant.EPOCH,
    )

    fun belongsTo(
        memberId: Long,
        scheduleId: Long,
        actionType: ScheduleNotificationActionType,
    ): Boolean =
        this.memberId == memberId &&
            this.scheduleId == scheduleId &&
            this.actionType == actionType

    fun complete(
        completedAt: Instant,
        departedAt: Instant? = null,
        snoozedUntil: Instant? = null,
    ) {
        resultDepartedAt = departedAt
        resultSnoozedUntil = snoozedUntil
        this.completedAt = completedAt
    }
}
