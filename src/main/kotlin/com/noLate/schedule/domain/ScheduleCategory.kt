package com.noLate.schedule.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.Comment

@Entity
@Table(
    name = "schedule_categories",
    indexes = [
        Index(
            name = "idx_schedule_categories_calendar_deleted_sort",
            columnList = "calendar_id,deleted,sort_order",
        ),
    ],
)
@Comment("개인 또는 공유 캘린더별 일정 카테고리")
class ScheduleCategory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 카테고리 PK")
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    @Comment("개인 카테고리 소유자 또는 공유 캘린더 카테고리 생성 회원 id")
    var memberId: Long = 0,

    @Column(name = "calendar_id")
    @Comment("공유 캘린더 소속 id. null이면 개인 캘린더 카테고리")
    var calendarId: Long? = null,

    @Column(nullable = false, length = 80)
    @Comment("사용자가 지정한 카테고리 이름")
    var title: String = "",

    @Column(nullable = false, length = 32)
    @Comment("UI 표시 색상")
    var color: String = "#5A96FF",

    @Column(name = "icon_key", length = 40)
    @Comment("UI 아이콘 키")
    var iconKey: String? = null,

    @Column(name = "sort_order", nullable = false)
    @Comment("사용자 지정 정렬 순서")
    var sortOrder: Int = 0,
) : BaseEntity() {

    fun update(
        title: String,
        color: String,
        iconKey: String?,
        sortOrder: Int,
    ) {
        this.title = title
        this.color = color
        this.iconKey = iconKey
        this.sortOrder = sortOrder
    }

    fun moveToCalendar(calendarId: Long, sortOrder: Int) {
        this.calendarId = calendarId
        this.sortOrder = sortOrder
    }

    fun toDto(): ScheduleCategorySettingDto {
        return ScheduleCategorySettingDto(
            id = id?.toString(),
            title = title,
            color = color,
            iconKey = iconKey,
            sortOrder = sortOrder,
            ownerMemberId = memberId,
            calendarId = calendarId,
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }
}

data class ScheduleCategorySettingDto(
    val id: String? = null,
    val title: String,
    val color: String,
    val iconKey: String? = null,
    val sortOrder: Int,
    val ownerMemberId: Long? = null,
    val calendarId: Long? = null,
    val shared: Boolean = false,
    val sharePermission: ScheduleSharePermission? = null,
    val updatedAt: String? = null,
)
