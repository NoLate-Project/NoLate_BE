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

/**
 * 공유 권한의 등급이다.
 *
 * OWNER는 실제 공유 row로 부여하기보다는 응답/정책 모델에서 "소유자"를 표현하기 위한
 * 상위 권한으로 남겨 둔다. 현재 공유 API는 기존 소유자가 사라지는 소유권 이전까지는
 * 처리하지 않으므로 외부 요청으로 OWNER 권한을 만들 수 없게 서비스에서 막는다.
 */
enum class ScheduleSharePermission {
    VIEWER,
    COMMENTER,
    EDITOR,
    OWNER,
}

/**
 * 공유 row를 삭제하지 않고 상태를 바꾸는 이유는 두 가지다.
 *
 * 1. 같은 대상에게 다시 공유할 때 기존 row를 재활성화하면 유니크 제약을 유지할 수 있다.
 * 2. 추후 감사 로그 또는 초대 이력을 붙일 때 "과거에 공유했다가 해제됨"을 복원할 수 있다.
 */
enum class ScheduleShareStatus {
    ACTIVE,
    REVOKED,
}

data class ScheduleShareDto(
    val id: String,
    val resourceId: String,
    val ownerMemberId: Long,
    val targetMemberId: Long,
    val targetEmail: String? = null,
    val permission: ScheduleSharePermission,
    val contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
    val status: ScheduleShareStatus,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class ScheduleShareInboxDto(
    val pendingInvitations: List<ScheduleSharePendingInvitationDto> = emptyList(),
    val receivedShares: List<ScheduleShareInboxItemDto>,
)

data class ScheduleShareOutboxDto(
    val sharedResources: List<ScheduleShareOutboxItemDto>,
    val activeInvitations: List<ScheduleShareInvitationSummaryDto>,
)

data class ScheduleSharePendingInvitationDto(
    val id: String,
    val resourceType: ScheduleShareResourceType,
    val resourceId: String,
    val title: String,
    val color: String? = null,
    val ownerMemberId: Long,
    val ownerEmail: String? = null,
    val permission: ScheduleSharePermission,
    val contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
    val expiresAt: String,
)

data class ScheduleShareInboxItemDto(
    val shareId: String,
    val resourceType: ScheduleShareResourceType,
    val resourceId: String,
    val title: String,
    val color: String? = null,
    val ownerMemberId: Long,
    val ownerEmail: String? = null,
    val permission: ScheduleSharePermission,
    val contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
    val sharedAt: String? = null,
)

data class ScheduleShareOutboxItemDto(
    val resourceType: ScheduleShareResourceType,
    val resourceId: String,
    val title: String,
    val color: String? = null,
    val shareCount: Int,
    val shares: List<ScheduleShareDto>,
)

data class ScheduleShareInvitationSummaryDto(
    val id: String,
    val resourceType: ScheduleShareResourceType,
    val resourceId: String,
    val title: String,
    val color: String? = null,
    val permission: ScheduleSharePermission,
    val contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
    val status: ScheduleShareInvitationStatus,
    val expiresAt: String,
    val maxAcceptCount: Int,
    val acceptedCount: Int,
)

@Entity
@Table(
    name = "schedule_shares",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_shares_schedule_target",
            columnNames = ["schedule_id", "target_member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_shares_target_status", columnList = "target_member_id,status,deleted"),
        Index(name = "idx_schedule_shares_owner_schedule", columnList = "owner_member_id,schedule_id"),
    ],
)
@Comment("개별 일정 공유 권한")
class ScheduleShare(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 공유 PK")
    var id: Long? = null,

    @Column(name = "schedule_id", nullable = false)
    @Comment("공유되는 일정 id")
    var scheduleId: Long = 0L,

    @Column(name = "owner_member_id", nullable = false)
    @Comment("일정 소유자 회원 id")
    var ownerMemberId: Long = 0L,

    @Column(name = "target_member_id", nullable = false)
    @Comment("공유 대상 회원 id")
    var targetMemberId: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Comment("공유 권한")
    var permission: ScheduleSharePermission = ScheduleSharePermission.VIEWER,

    @Enumerated(EnumType.STRING)
    @Column(name = "content_mode", nullable = false, length = 30)
    @Comment("일정만 공유하거나 사용자별 이동 기능까지 허용")
    var contentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Comment("공유 상태")
    var status: ScheduleShareStatus = ScheduleShareStatus.ACTIVE,

    /**
     * 권한 변경과 공유 해제가 거의 동시에 들어오는 경우 마지막 커밋이 조용히 덮어쓰지
     * 않도록 낙관적 락 컬럼을 둔다. 생성 경합은 서비스에서 소유 리소스 비관적 락과
     * DB 유니크 제약으로 막고, 기존 row 변경 경합은 이 version이 감지한다.
     */
    @Version
    @Column(nullable = false)
    var version: Long = 0L,
) : BaseEntity() {

    fun activate(
        permission: ScheduleSharePermission,
        contentMode: ScheduleShareContentMode = this.contentMode,
    ) {
        this.permission = permission
        this.contentMode = contentMode
        this.status = ScheduleShareStatus.ACTIVE
    }

    fun revoke() {
        this.status = ScheduleShareStatus.REVOKED
    }

    fun toDto(targetEmail: String? = null): ScheduleShareDto {
        return ScheduleShareDto(
            id = requireNotNull(id).toString(),
            resourceId = scheduleId.toString(),
            ownerMemberId = ownerMemberId,
            targetMemberId = targetMemberId,
            targetEmail = targetEmail,
            permission = permission,
            contentMode = contentMode,
            status = status,
            createdAt = (createDt ?: createdAt)?.toString(),
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }
}
