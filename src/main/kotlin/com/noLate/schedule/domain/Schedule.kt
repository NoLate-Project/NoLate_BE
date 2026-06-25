package com.noLate.schedule.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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

@Entity
@Table(name = "schedules")
@Comment("일정 핵심 정보를 저장하는 테이블")
class Schedule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("일정 PK")
    var id: Long? = null,

    @Column(name = "member_id", nullable = false)
    @Comment("일정을 소유한 회원 id")
    var memberId: Long = 0,

    @Column(nullable = false, length = 120)
    @Comment("일정 제목")
    var title: String = "",

    @Column(name = "start_at", nullable = false)
    @Comment("일정 시작 시각")
    var startAt: Instant = Instant.EPOCH,

    @Column(name = "end_at", nullable = false)
    @Comment("일정 종료 시각")
    var endAt: Instant = Instant.EPOCH,

    @Column(
        name = "has_end_time",
        nullable = false,
        columnDefinition = "boolean default true",
    )
    @Comment("종료 시각 입력 여부")
    var hasEndTime: Boolean = true,

    @Column(name = "all_day", nullable = false)
    @Comment("종일 일정 여부")
    var allDay: Boolean = false,

    @Lob
    @Column(columnDefinition = "TEXT")
    @Comment("일정 메모")
    var notes: String? = null,

    @OneToOne(
        mappedBy = "schedule",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    var categorySnapshot: ScheduleCategorySnapshot? = null,

    @OneToOne(
        mappedBy = "schedule",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    var route: ScheduleRoute? = null,
) : BaseEntity() {

    /**
     * 일정에 연결된 카테고리 스냅샷을 생성하거나 갱신한다.
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
     */
    fun updateRoute(
        travelMinutes: Int?,
        departAt: Instant?,
        departedAt: Instant?,
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
            departedAt == null &&
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
        next.departedAt = departedAt
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

    /**
     * Schedule Entity를 ScheduleDto로 변환한다.
     */
    fun toDto(objectMapper: ObjectMapper): ScheduleDto {
        val category = categorySnapshot
        val routeInfo = route

        return ScheduleDto(
            id = id,
            title = title,
            startAt = startAt.toString(),
            endAt = endAt.toString(),
            hasEndTime = hasEndTime,
            allDay = allDay,
            travelMinutes = routeInfo?.travelMinutes,
            departAt = routeInfo?.departAt?.toString(),
            departedAt = routeInfo?.departedAt?.toString(),
            travelMode = routeInfo?.travelMode,
            origin = routeInfo?.let {
                toPlace(
                    name = it.originName,
                    address = it.originAddress,
                    lat = it.originLat,
                    lng = it.originLng,
                )
            },
            destination = routeInfo?.let {
                toPlace(
                    name = it.destinationName,
                    address = it.destinationAddress,
                    lat = it.destinationLat,
                    lng = it.destinationLng,
                )
            },
            locationName = routeInfo?.locationName,
            category = ScheduleCategoryDto(
                id = category?.categoryId,
                title = category?.title,
                color = category?.color,
            ),
            notes = notes,
            route = parseRoute(objectMapper, routeInfo?.routeJson),
            notificationEnabled = routeInfo?.notificationEnabled ?: false,
            notificationLeadMinutes = routeInfo?.notificationLeadMinutes,
            notificationIntervalMinutes = routeInfo?.notificationIntervalMinutes,
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }

    private fun toPlace(
        name: String?,
        address: String?,
        lat: Double?,
        lng: Double?,
    ): SchedulePlaceDto? {
        if (name == null && address == null && lat == null && lng == null) {
            return null
        }

        return SchedulePlaceDto(
            name = name,
            address = address,
            lat = lat,
            lng = lng,
        )
    }

    private fun parseRoute(
        objectMapper: ObjectMapper,
        routeJson: String?,
    ): JsonNode? {
        if (routeJson.isNullOrBlank()) {
            return null
        }

        return objectMapper.readTree(routeJson)
    }
}
