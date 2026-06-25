package com.noLate.schedule.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Comment

@Entity
@Table(name = "schedule_categories")
@Comment("사용자별 일정 카테고리")
class ScheduleCategory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 카테고리 PK")
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    @Comment("카테고리를 소유한 회원 id")
    var memberId: Long = 0,

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

    fun toDto(): ScheduleCategorySettingDto {
        return ScheduleCategorySettingDto(
            id = id?.toString(),
            title = title,
            color = color,
            iconKey = iconKey,
            sortOrder = sortOrder,
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
    val updatedAt: String? = null,
)
