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

enum class ScheduleShareResourceType {
    SCHEDULE,
    CATEGORY,
    CALENDAR,
}

enum class ScheduleShareInvitationStatus {
    PENDING,
    ACCEPTED,
    EXPIRED,
    REVOKED,
}

data class ScheduleShareInvitationDto(
    val id: String,
    val resourceType: ScheduleShareResourceType,
    val resourceId: String,
    val ownerMemberId: Long,
    val permission: ScheduleSharePermission,
    val contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
    val status: ScheduleShareInvitationStatus,
    val expiresAt: String,
    val maxAcceptCount: Int,
    val acceptedCount: Int,
    val token: String? = null,
    val acceptPath: String? = null,
    val acceptedMemberId: Long? = null,
    val acceptedAt: String? = null,
)

data class ScheduleShareInvitationAcceptDto(
    val invitation: ScheduleShareInvitationDto,
    val share: ScheduleShareDto,
    val calendarMembership: ScheduleCalendarMemberDto? = null,
)

@Entity
@Table(
    name = "schedule_share_invitations",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_share_invitations_token_hash",
            columnNames = ["token_hash"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_share_invitations_owner_resource", columnList = "owner_member_id,resource_type,resource_id"),
        Index(name = "idx_schedule_share_invitations_status_expires", columnList = "status,expires_at"),
    ],
)
@Comment("일정/카테고리/공유 캘린더 링크 초대")
class ScheduleShareInvitation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("공유 초대 PK")
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    @Comment("초대 대상 리소스 종류")
    var resourceType: ScheduleShareResourceType = ScheduleShareResourceType.SCHEDULE,

    @Column(name = "resource_id", nullable = false)
    @Comment("초대 대상 일정 또는 카테고리 id")
    var resourceId: Long = 0L,

    @Column(name = "owner_member_id", nullable = false)
    @Comment("초대 생성자이자 리소스 소유자 회원 id")
    var ownerMemberId: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Comment("수락 시 부여할 공유 권한")
    var permission: ScheduleSharePermission = ScheduleSharePermission.VIEWER,

    @Enumerated(EnumType.STRING)
    @Column(name = "content_mode", nullable = false, length = 30)
    @Comment("수락 시 부여할 일정/이동 공유 범위")
    var contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,

    @Column(name = "token_hash", nullable = false, length = 128)
    @Comment("초대 링크 토큰의 SHA-256 해시. 원본 토큰은 저장하지 않는다.")
    var tokenHash: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Comment("초대 상태")
    var status: ScheduleShareInvitationStatus = ScheduleShareInvitationStatus.PENDING,

    @Column(name = "expires_at", nullable = false)
    @Comment("초대 링크 만료 시각")
    var expiresAt: Instant = Instant.EPOCH,

    @Column(name = "max_accept_count", nullable = false)
    @Comment("이 링크로 허용되는 최대 수락 횟수")
    var maxAcceptCount: Int = 1,

    @Column(name = "accepted_count", nullable = false)
    @Comment("현재까지 수락된 횟수")
    var acceptedCount: Int = 0,

    @Column(name = "accepted_member_id")
    @Comment("단일 수락 링크에서 마지막으로 수락한 회원 id")
    var acceptedMemberId: Long? = null,

    @Column(name = "accepted_at")
    @Comment("마지막 수락 시각")
    var acceptedAt: Instant? = null,

    /**
     * 링크 수락은 같은 token_hash row에 여러 요청이 몰리는 경합이다.
     * 서비스가 PESSIMISTIC_WRITE로 row를 잠가 직렬화하지만, JPA 변경 충돌을 감지할 수 있게
     * version도 둔다. 이중 방어를 유지하면 DB 락 정책 차이에 흔들릴 가능성이 줄어든다.
     */
    @Version
    @Column(nullable = false)
    var version: Long = 0L,
) : BaseEntity() {

    fun markExpired() {
        status = ScheduleShareInvitationStatus.EXPIRED
    }

    fun accept(memberId: Long, acceptedAt: Instant) {
        acceptedCount += 1
        acceptedMemberId = memberId
        this.acceptedAt = acceptedAt
        if (acceptedCount >= maxAcceptCount) {
            status = ScheduleShareInvitationStatus.ACCEPTED
        }
    }

    fun revoke() {
        status = ScheduleShareInvitationStatus.REVOKED
    }

    /**
     * 만료 배치가 DB 상태를 EXPIRED로 영속화하기 전에도 API가 링크를 활성 상태로
     * 노출하지 않도록 현재 시각을 기준으로 유효 상태를 계산한다.
     */
    fun effectiveStatus(now: Instant): ScheduleShareInvitationStatus {
        if (status == ScheduleShareInvitationStatus.PENDING && !now.isBefore(expiresAt)) {
            return ScheduleShareInvitationStatus.EXPIRED
        }
        return status
    }

    fun toDto(token: String? = null, effectiveAt: Instant? = null): ScheduleShareInvitationDto {
        val acceptPath = token?.let { "/api/share-invitations/$it/accept" }
        return ScheduleShareInvitationDto(
            id = requireNotNull(id).toString(),
            resourceType = resourceType,
            resourceId = resourceId.toString(),
            ownerMemberId = ownerMemberId,
            permission = permission,
            contentMode = contentMode,
            status = effectiveAt?.let(::effectiveStatus) ?: status,
            expiresAt = expiresAt.toString(),
            maxAcceptCount = maxAcceptCount,
            acceptedCount = acceptedCount,
            token = token,
            acceptPath = acceptPath,
            acceptedMemberId = acceptedMemberId,
            acceptedAt = acceptedAt?.toString(),
        )
    }
}
