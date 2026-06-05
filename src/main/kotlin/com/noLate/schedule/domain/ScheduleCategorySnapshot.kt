package com.noLate.schedule.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Comment

/**
 * 일정 저장 시점의 카테고리 표시 정보를 보관하는 1:1 하위 엔티티.
 *
 * 현재는 별도 카테고리 테이블이 없고 프론트에서 선택한 카테고리 값을 일정에 함께 저장한다.
 * 그래서 일정 본문과 분리하되, 원본 카테고리 변경과 무관하게 화면 표시 값을 유지하는 스냅샷으로 다룬다.
 */
@Entity
@Table(name = "schedule_category_snapshots")
@Comment("일정별 카테고리 표시 스냅샷 테이블")
class ScheduleCategorySnapshot(
    /** 카테고리 스냅샷 PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("카테고리 스냅샷 PK")
    var id: Long? = null,

    /** 연결된 일정 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false, unique = true)
    @Comment("연결된 일정 id")
    var schedule: Schedule? = null,

    /** 프론트에서 선택한 카테고리 id */
    @Column(name = "category_id", nullable = false, length = 64)
    @Comment("프론트에서 선택한 카테고리 id")
    var categoryId: String = "",

    /** 카테고리 표시명 */
    @Column(nullable = false, length = 80)
    @Comment("카테고리 표시명")
    var title: String = "",

    /** 캘린더와 리스트에서 사용하는 색상 코드 */
    @Column(nullable = false, length = 32)
    @Comment("카테고리 색상 코드")
    var color: String = "",
)
