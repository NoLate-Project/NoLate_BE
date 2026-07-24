package com.noLate.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * 사용자가 앱 안에서 다시 확인할 수 있는 논리 알림이다.
 *
 * [PushSendHistory]는 같은 알림도 기기 수만큼 row가 생기고 FCM 오류까지 보관하는 운영
 * 기록이다. 사용자 알림함은 push 성공 여부와 분리해 회원당 한 번만 저장해야 하므로 별도
 * 엔티티로 유지한다. 이렇게 해야 토큰이 없거나 APNs 전달에 실패해도 놓친 알림이 남는다.
 */
@Entity
@Table(
    name = "app_notifications",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_app_notifications_member_deduplication",
            columnNames = ["member_id", "deduplication_key"],
        ),
        UniqueConstraint(
            name = "uk_app_notifications_member_logical_event",
            columnNames = ["member_id", "logical_event_key"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_app_notifications_member_id_id",
            columnList = "member_id, id",
        ),
        Index(
            name = "idx_app_notifications_member_read_at",
            columnList = "member_id, read_at",
        ),
    ],
)
@DynamicUpdate
class AppNotification(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    /**
     * 동일 이벤트의 worker 재시도와 동시 처리를 한 건으로 합치는 키다.
     * null이면 사용자가 여러 번 요청할 수 있는 출발 확인 알림처럼 매 호출을 새 알림으로 본다.
     */
    @Column(name = "deduplication_key", length = 180)
    val deduplicationKey: String? = null,

    @Column(name = "logical_event_key", nullable = false, length = 100)
    val logicalEventKey: String = PushLogicalEventKey.newEvent(),

    @Column(nullable = false, length = 80)
    val type: String,

    @Column(name = "schedule_id")
    val scheduleId: Long? = null,

    @Column(name = "category_id")
    val categoryId: Long? = null,

    @Column(nullable = false, length = 200)
    val title: String,

    @Column(nullable = false, length = 1000)
    val body: String,

    // MySQL/Hibernate 조합에서 @Lob String이 TINYTEXT로 추론되는 환경이 있다. 화면 이동
    // payload가 255 bytes를 넘더라도 손실되지 않도록 배포 DDL과 같은 LONGTEXT를 명시한다.
    @Column(name = "data_json", nullable = false, columnDefinition = "LONGTEXT")
    val dataJson: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "read_at")
    var readAt: Instant? = null,

    /**
     * INBOX_ONLY는 과거/명시적 inbox row라 provider 대상이 아니다. Push outbox 경로는
     * OPEN으로 만든 뒤 같은 transaction에서 정확한 delivery row 수와 함께 FROZEN으로 닫는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "manifest_state", nullable = false, length = 24)
    var manifestState: PushManifestState = PushManifestState.INBOX_ONLY,

    @Column(name = "manifest_recipient_count", nullable = false)
    var manifestRecipientCount: Int = 0,

    @Column(name = "manifest_frozen_at")
    var manifestFrozenAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "dispatch_status", nullable = false, length = 24)
    var dispatchStatus: PushOutboxDispatchStatus = PushOutboxDispatchStatus.NOT_REQUIRED,

    @Column(name = "dispatch_attempt_count", nullable = false)
    var dispatchAttemptCount: Int = 0,

    /** 실제 drainer 실패 예산. source job PROCESSING 같은 정상 deferral claim은 포함하지 않는다. */
    @Column(name = "dispatch_failure_count", nullable = false)
    var dispatchFailureCount: Int = 0,

    @Column(name = "next_dispatch_at")
    var nextDispatchAt: Instant? = null,

    @Column(name = "dispatch_locked_by", length = 100)
    var dispatchLockedBy: String? = null,

    @Column(name = "dispatch_locked_at")
    var dispatchLockedAt: Instant? = null,

    @Column(name = "dispatch_completed_at")
    var dispatchCompletedAt: Instant? = null,

    @Column(name = "dispatch_failure_reason", length = 500)
    var dispatchFailureReason: String? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {
    val isRead: Boolean
        get() = readAt != null

    /** 이미 읽은 알림의 최초 확인 시각은 덮어쓰지 않는다. */
    fun markRead(at: Instant): Boolean {
        if (readAt != null) return false
        readAt = at
        return true
    }

    fun freezeManifest(recipientCount: Int, at: Instant) {
        check(manifestState == PushManifestState.OPEN) {
            "Frozen or inbox-only push manifests cannot be changed."
        }
        require(recipientCount >= 0)
        manifestRecipientCount = recipientCount
        manifestFrozenAt = at
        manifestState = PushManifestState.FROZEN
    }

    fun enqueueForDispatch(at: Instant) {
        check(manifestState == PushManifestState.FROZEN)
        if (dispatchStatus == PushOutboxDispatchStatus.COMPLETED) return
        dispatchStatus = PushOutboxDispatchStatus.PENDING
        nextDispatchAt = at
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = null
    }

    /**
     * Provider가 요청을 확정적으로 거절한 delivery와 source event의 재시도 가능 상태를
     * 같은 transaction에서 묶는다.
     *
     * 이 전이는 기존 lease owner도 무효화한다. provider 호출이 lease timeout보다 오래
     * 걸린 사이 다른 worker가 event를 COMPLETED로 닫았더라도, 늦게 돌아온 확정 실패가
     * 다시 PENDING을 열어 frozen payload/delivery를 안전하게 redrive할 수 있다. 재시도
     * 한도는 lease reclaim 횟수가 아니라 실제 기기 provider attempt 수로 판단한다.
     */
    fun scheduleAfterConfirmedDeliveryFailure(
        nextAt: Instant,
        retryAllowed: Boolean,
        reason: String,
    ): Boolean {
        if (manifestState != PushManifestState.FROZEN) return false
        val previousStatus = dispatchStatus

        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = reason.take(500)
        if (!retryAllowed) {
            dispatchStatus = PushOutboxDispatchStatus.FAILED
            dispatchCompletedAt = nextAt
            nextDispatchAt = null
            return false
        }

        dispatchStatus = PushOutboxDispatchStatus.PENDING
        dispatchCompletedAt = null
        nextDispatchAt = if (previousStatus == PushOutboxDispatchStatus.PENDING) {
            listOfNotNull(nextDispatchAt, nextAt).minOrNull()
        } else {
            nextAt
        }
        return true
    }

    /**
     * A late confirmed failure must not resurrect an event whose authoritative schedule source is
     * terminal or whose generation/input identity changed. This invalidates any stale outbox lease
     * and converges the persisted safety source in the same transaction as the delivery.
     */
    fun completeSupersededDispatch(at: Instant, reason: String) {
        if (manifestState != PushManifestState.FROZEN) return
        dispatchStatus = PushOutboxDispatchStatus.COMPLETED
        dispatchCompletedAt = at
        nextDispatchAt = null
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = reason.take(500)
    }

    /**
     * Schedule source worker가 lease를 잃은 뒤 늦게 성공한 경우에도 confirmed 지표 보정이
     * 유실되지 않도록 ALREADY_SUCCESS redrive를 예약한다. provider는 다시 호출되지 않는다.
     */
    fun scheduleConfirmedDeliveryReconciliation(nextAt: Instant): Boolean {
        if (manifestState != PushManifestState.FROZEN) return false
        val scheduleMetricReconciliation =
            deduplicationKey.orEmpty().startsWith("schedule-push-job:")
        if (!scheduleMetricReconciliation && dispatchStatus != PushOutboxDispatchStatus.FAILED) {
            return false
        }
        dispatchStatus = PushOutboxDispatchStatus.PENDING
        nextDispatchAt = nextAt
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchCompletedAt = null
        dispatchFailureReason = "CONFIRMED_STATE_RECONCILIATION"
        return true
    }

    fun claimDispatch(workerId: String, at: Instant): Boolean {
        if (dispatchStatus != PushOutboxDispatchStatus.PENDING) return false
        dispatchStatus = PushOutboxDispatchStatus.PROCESSING
        dispatchAttemptCount += 1
        dispatchLockedBy = workerId.take(100)
        dispatchLockedAt = at
        return true
    }

    fun completeDispatch(workerId: String, at: Instant) {
        check(dispatchStatus == PushOutboxDispatchStatus.PROCESSING && dispatchLockedBy == workerId)
        dispatchStatus = PushOutboxDispatchStatus.COMPLETED
        dispatchCompletedAt = at
        nextDispatchAt = null
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = null
    }

    fun retryDispatch(workerId: String, nextAt: Instant, reason: String) {
        check(dispatchStatus == PushOutboxDispatchStatus.PROCESSING && dispatchLockedBy == workerId)
        dispatchStatus = PushOutboxDispatchStatus.PENDING
        dispatchFailureCount += 1
        nextDispatchAt = nextAt
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = reason.take(500)
    }

    fun failDispatch(workerId: String, at: Instant, reason: String) {
        check(dispatchStatus == PushOutboxDispatchStatus.PROCESSING && dispatchLockedBy == workerId)
        dispatchStatus = PushOutboxDispatchStatus.FAILED
        dispatchFailureCount += 1
        dispatchCompletedAt = at
        nextDispatchAt = null
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = reason.take(500)
    }

    /**
     * Authoritative source worker가 아직 PROCESSING인 경우의 정상 대기다. lease epoch인
     * dispatchAttemptCount는 계속 단조 증가하지만 실제 실패 예산은 소비하지 않는다.
     */
    fun deferDispatch(workerId: String, nextAt: Instant, reason: String) {
        check(dispatchStatus == PushOutboxDispatchStatus.PROCESSING && dispatchLockedBy == workerId)
        dispatchStatus = PushOutboxDispatchStatus.PENDING
        nextDispatchAt = nextAt
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = reason.take(500)
    }

    fun recoverStaleDispatch(staleBefore: Instant, nextAt: Instant): Boolean {
        if (dispatchStatus != PushOutboxDispatchStatus.PROCESSING) return false
        val locked = dispatchLockedAt ?: return false
        if (locked.isAfter(staleBefore)) return false
        dispatchStatus = PushOutboxDispatchStatus.PENDING
        nextDispatchAt = nextAt
        dispatchLockedBy = null
        dispatchLockedAt = null
        dispatchFailureReason = "Recovered stale PROCESSING outbox lease."
        return true
    }

    protected constructor() : this(
        memberId = 0L,
        logicalEventKey = "event:jpa-placeholder",
        type = "GENERAL",
        title = "",
        body = "",
        dataJson = "{}",
        createdAt = Instant.EPOCH,
    )
}

enum class PushManifestState {
    /** 일반 inbox 기록 또는 migration 이전 row. 과거 event를 새 기기로 확장하지 않는다. */
    INBOX_ONLY,
    /** Manifest snapshot을 아직 확정하지 않은 명시적 복구 가능 상태. */
    OPEN,
    /** recipient count와 delivery rows가 영구 고정된 상태. */
    FROZEN,
}

enum class PushOutboxDispatchStatus {
    /**
     * Schedule job 같은 소유 worker가 직접 redrive하는 event. 확정 provider 실패가
     * source lease보다 늦게 돌아오면 공용 outbox safety drainer가 PENDING으로 활성화된다.
     */
    NOT_REQUIRED,
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}
