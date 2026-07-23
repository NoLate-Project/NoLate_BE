package com.noLate.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
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
        )
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
) {
    val isRead: Boolean
        get() = readAt != null

    /** 이미 읽은 알림의 최초 확인 시각은 덮어쓰지 않는다. */
    fun markRead(at: Instant): Boolean {
        if (readAt != null) return false
        readAt = at
        return true
    }

    protected constructor() : this(
        memberId = 0L,
        type = "GENERAL",
        title = "",
        body = "",
        dataJson = "{}",
        createdAt = Instant.EPOCH,
    )
}
