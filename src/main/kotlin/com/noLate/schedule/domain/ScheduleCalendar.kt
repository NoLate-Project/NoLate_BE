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

enum class ScheduleCalendarStatus {
    ACTIVE,
    ARCHIVED,
}

/**
 * 일정 카테고리와 분리된 공유 접근 경계다.
 *
 * ownerMemberId는 목록/권한 조회를 빠르게 하고 소유권 이전을 한 행 잠금으로 직렬화하기 위해
 * 캘린더에도 보관한다. 서비스는 같은 트랜잭션에서 OWNER 멤버 row까지 함께 변경해 두 표현이
 * 어긋나지 않도록 유지한다.
 */
@Entity
@Table(
    name = "schedule_calendars",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_calendars_legacy_category",
            columnNames = ["legacy_category_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_calendars_owner_status", columnList = "owner_member_id,status,deleted"),
    ],
)
@Comment("일정 공유 캘린더")
class ScheduleCalendar(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("공유 캘린더 PK")
    var id: Long? = null,

    @Column(name = "owner_member_id", nullable = false)
    @Comment("현재 캘린더 소유 회원 id")
    var ownerMemberId: Long = 0L,

    @Column(name = "legacy_category_id")
    @Comment("기존 카테고리 공유 dual-read/backfill을 위한 임시 원본 category id")
    var legacyCategoryId: Long? = null,

    @Column(nullable = false, length = 80)
    @Comment("공유 캘린더 이름")
    var title: String = "",

    @Column(nullable = false, length = 32)
    @Comment("캘린더 표시 색상")
    var color: String = "#2F80FF",

    @Enumerated(EnumType.STRING)
    @Column(name = "default_content_mode", nullable = false, length = 30)
    @Comment("이 캘린더 일정의 기본 공유 콘텐츠 모드")
    var defaultContentMode: ScheduleShareContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("캘린더 생명주기 상태")
    var status: ScheduleCalendarStatus = ScheduleCalendarStatus.ACTIVE,

    @Version
    @Column(nullable = false)
    @Comment("설정 변경과 소유권 이전 lost update 감지")
    var version: Long = 0L,
) : BaseEntity() {

    fun updateSettings(
        title: String,
        color: String,
        defaultContentMode: ScheduleShareContentMode,
    ) {
        this.title = title
        this.color = color
        this.defaultContentMode = defaultContentMode
    }

    fun transferOwnership(memberId: Long) {
        ownerMemberId = memberId
    }

    fun archive() {
        status = ScheduleCalendarStatus.ARCHIVED
        softDelete()
    }
}
