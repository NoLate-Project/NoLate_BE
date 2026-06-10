package com.noLate.schedule.domain

import com.noLate.global.common.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Comment
import java.time.Instant

/**
 * 회원이 등록한 일정의 핵심 정보를 저장하는 aggregate root.
 *
 * 일정 자체의 정체성과 시간 범위만 이 엔티티에 두고, 화면 색상용 카테고리 스냅샷과
 * NoLate 출발 알림에 필요한 이동/경로 정보는 1:1 하위 엔티티로 분리한다.
 */
@Entity
@Table(name = "schedules")
@Comment("일정 핵심 정보를 저장하는 테이블")
class Schedule(
    /** 일정 PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 PK")
    var id: Long? = null,

    /** 일정을 소유한 회원 id */
    @Column(name = "member_id", nullable = false)
    @Comment("일정을 소유한 회원 id")
    var memberId: Long = 0,

    /** 일정 제목 */
    @Column(nullable = false, length = 120)
    @Comment("일정 제목")
    var title: String = "",

    /** 일정 시작 시각 */
    @Column(name = "start_at", nullable = false)
    @Comment("일정 시작 시각")
    var startAt: Instant = Instant.EPOCH,

    /** 일정 종료 시각 */
    @Column(name = "end_at", nullable = false)
    @Comment("일정 종료 시각")
    var endAt: Instant = Instant.EPOCH,

    /** 사용자가 종료 시각을 명시했는지 여부 */
    @Column(
        name = "has_end_time",
        nullable = false,
        columnDefinition = "boolean default true",
    )
    @Comment("종료 시각 입력 여부")
    var hasEndTime: Boolean = true,

    /** 종일 일정 여부 */
    @Column(name = "all_day", nullable = false)
    @Comment("종일 일정 여부")
    var allDay: Boolean = false,

    /** 일정 메모 */
    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("일정 메모")
    var notes: String? = null,

    /** 일정 저장 시점의 카테고리 표시 정보 */
    @OneToOne(mappedBy = "schedule", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var categorySnapshot: ScheduleCategorySnapshot? = null,

    /** 출발 알림과 경로 표시에 필요한 이동 정보 */
    @OneToOne(mappedBy = "schedule", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var route: ScheduleRoute? = null,
) : BaseEntity() {

    /**
     * 일정에 연결된 카테고리 스냅샷을 생성하거나 갱신한다.
     * 카테고리 원본이 바뀌어도 저장된 일정 화면은 당시 선택 값을 유지한다.
     */
    fun updateCategorySnapshot(categoryId: String, title: String, color: String) {
        val next = categorySnapshot ?: ScheduleCategorySnapshot(schedule = this).also {
            categorySnapshot = it
        }

        next.schedule = this
        next.categoryId = categoryId
        next.title = title
        next.color = color
    }

    /**
     * 이동/경로 정보를 생성하거나 갱신한다.
     * 위치와 이동 정보가 모두 비어 있으면 하위 row를 제거해 일정 본문만 남긴다.
     */
    fun updateRoute(
        travelMinutes: Int?,
        departAt: Instant?,
        travelMode: ScheduleTravelMode?,
        locationName: String?,
        originName: String?,
        originAddress: String?,
        originLat: Double?,
        originLng: Double?,
        destinationName: String?,
        destinationAddress: String?,
        destinationLat: Double?,
        destinationLng: Double?,
        routeJson: String?,
        notificationEnabled: Boolean,
        notificationLeadMinutes: Int?,
        notificationIntervalMinutes: Int?,
    ) {
        if (
            travelMinutes == null &&
            departAt == null &&
            travelMode == null &&
            locationName == null &&
            originName == null &&
            originAddress == null &&
            originLat == null &&
            originLng == null &&
            destinationName == null &&
            destinationAddress == null &&
            destinationLat == null &&
            destinationLng == null &&
            routeJson == null &&
            !notificationEnabled
        ) {
            route = null
            return
        }

        val next = route ?: ScheduleRoute(schedule = this).also {
            route = it
        }

        next.schedule = this
        next.travelMinutes = travelMinutes
        next.departAt = departAt
        next.travelMode = travelMode
        next.locationName = locationName
        next.originName = originName
        next.originAddress = originAddress
        next.originLat = originLat
        next.originLng = originLng
        next.destinationName = destinationName
        next.destinationAddress = destinationAddress
        next.destinationLat = destinationLat
        next.destinationLng = destinationLng
        next.routeJson = routeJson
        next.notificationEnabled = notificationEnabled
        next.notificationLeadMinutes = notificationLeadMinutes
        next.notificationIntervalMinutes = notificationIntervalMinutes
    }
}
