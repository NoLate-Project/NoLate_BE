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

@Entity
@Table(
    name = "schedule_category_shares",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_category_shares_category_target",
            columnNames = ["category_id", "target_member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_category_shares_target_status", columnList = "target_member_id,status,deleted"),
        Index(name = "idx_schedule_category_shares_owner_category", columnList = "owner_member_id,category_id"),
    ],
)
@Comment("일정 카테고리 공유 권한")
class ScheduleCategoryShare(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 카테고리 공유 PK")
    var id: Long? = null,

    @Column(name = "category_id", nullable = false)
    @Comment("공유되는 일정 카테고리 id")
    var categoryId: Long = 0L,

    @Column(name = "owner_member_id", nullable = false)
    @Comment("카테고리 소유자 회원 id")
    var ownerMemberId: Long = 0L,

    @Column(name = "target_member_id", nullable = false)
    @Comment("공유 대상 회원 id")
    var targetMemberId: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Comment("공유 권한")
    var permission: ScheduleSharePermission = ScheduleSharePermission.VIEWER,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Comment("공유 상태")
    var status: ScheduleShareStatus = ScheduleShareStatus.ACTIVE,

    /**
     * 기존 카테고리 공유 권한을 동시에 수정할 때 lost update를 감지하기 위한 버전이다.
     * 생성 중복은 category row 비관적 락과 유니크 제약으로, 변경 충돌은 version으로 다룬다.
     */
    @Version
    @Column(nullable = false)
    var version: Long = 0L,
) : BaseEntity() {

    fun activate(permission: ScheduleSharePermission) {
        this.permission = permission
        this.status = ScheduleShareStatus.ACTIVE
    }

    fun revoke() {
        this.status = ScheduleShareStatus.REVOKED
    }

    fun toDto(targetEmail: String? = null): ScheduleShareDto {
        return ScheduleShareDto(
            id = requireNotNull(id).toString(),
            resourceId = categoryId.toString(),
            ownerMemberId = ownerMemberId,
            targetMemberId = targetMemberId,
            targetEmail = targetEmail,
            permission = permission,
            status = status,
            createdAt = (createDt ?: createdAt)?.toString(),
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }
}
