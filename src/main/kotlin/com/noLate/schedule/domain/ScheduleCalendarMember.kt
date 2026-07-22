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

enum class ScheduleCalendarRole {
    VIEWER,
    EDITOR,
    OWNER,
}

enum class ScheduleCalendarMemberStatus {
    ACTIVE,
    LEFT,
    REMOVED,
}

@Entity
@Table(
    name = "schedule_calendar_members",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_schedule_calendar_members_calendar_member",
            columnNames = ["calendar_id", "member_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_schedule_calendar_members_member_status", columnList = "member_id,status,deleted"),
        Index(name = "idx_schedule_calendar_members_calendar_status", columnList = "calendar_id,status,deleted"),
    ],
)
@Comment("공유 캘린더 멤버십")
class ScheduleCalendarMember(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("공유 캘린더 멤버십 PK")
    var id: Long? = null,

    @Column(name = "calendar_id", nullable = false)
    @Comment("공유 캘린더 id")
    var calendarId: Long = 0L,

    @Column(name = "member_id", nullable = false)
    @Comment("가입 회원 id")
    var memberId: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("캘린더 역할")
    var role: ScheduleCalendarRole = ScheduleCalendarRole.VIEWER,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Comment("멤버십 생명주기 상태")
    var status: ScheduleCalendarMemberStatus = ScheduleCalendarMemberStatus.ACTIVE,

    @Column(name = "route_reminder_enabled", nullable = false, columnDefinition = "boolean default true")
    @Comment("캘린더 경로 미설정 알림 수신 여부")
    var routeReminderEnabled: Boolean = true,

    @Version
    @Column(nullable = false)
    @Comment("역할 변경과 강퇴 경합 감지")
    var version: Long = 0L,
) : BaseEntity() {

    fun activate(role: ScheduleCalendarRole) {
        this.role = role
        status = ScheduleCalendarMemberStatus.ACTIVE
        deleted = false
        deletedAt = null
    }

    fun changeRole(role: ScheduleCalendarRole) {
        this.role = role
    }

    fun updateRouteReminder(enabled: Boolean) {
        routeReminderEnabled = enabled
    }

    fun leave() {
        status = ScheduleCalendarMemberStatus.LEFT
        softDelete()
    }

    fun remove() {
        status = ScheduleCalendarMemberStatus.REMOVED
        softDelete()
    }
}
