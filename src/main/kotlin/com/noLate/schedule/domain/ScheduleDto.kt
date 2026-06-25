package com.noLate.schedule.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class ScheduleCategoryDto(
    val id: String? = null,
    val title: String? = null,
    val color: String? = null,
)

data class SchedulePlaceDto(
    val name: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class ScheduleDto(
    val id: Long? = null,
    val title: String,
    val startAt: String,
    val endAt: String? = null,
    val hasEndTime: Boolean? = null,
    val allDay: Boolean? = null,
    val travelMinutes: Int? = null,
    val departAt: String? = null,
    val departedAt: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val origin: SchedulePlaceDto? = null,
    val destination: SchedulePlaceDto? = null,
    val locationName: String? = null,
    val category: ScheduleCategoryDto,
    val notes: String? = null,
    val route: JsonNode? = null,
    val notificationEnabled: Boolean? = null,
    val notificationLeadMinutes: Int? = null,
    val notificationIntervalMinutes: Int? = null,
    val updatedAt: String? = null,
) {

    /**
     * ScheduleDto의 값을 Schedule Entity로 변환한다.
     *
     * memberId는 DTO에 없기 때문에 외부에서 주입받는다.
     */
    fun toEntity(memberId: Long): Schedule {
        val parsedStartAt = parseInstant(startAt)
        val parsedHasEndTime = hasEndTime ?: (endAt != null)

        val parsedEndAt = if (parsedHasEndTime) {
            parseInstant(requireNotNull(endAt) { "endAt is required." })
        } else {
            parsedStartAt
        }

        val schedule = Schedule(
            id = id,
            memberId = memberId,
            title = title,
            startAt = parsedStartAt,
            endAt = parsedEndAt,
            hasEndTime = parsedHasEndTime,
            allDay = allDay ?: false,
            notes = notes?.takeIf { it.isNotBlank() },
        )

        schedule.updateCategorySnapshot(
            categoryId = requireNotNull(category.id) { "category.id is required." },
            title = requireNotNull(category.title) { "category.title is required." },
            color = requireNotNull(category.color) { "category.color is required." },
        )

        schedule.updateRoute(
            travelMinutes = travelMinutes,
            departAt = departAt?.let { parseInstant(it) },
            departedAt = departedAt?.let { parseInstant(it) },
            travelMode = travelMode,
            locationName = locationName?.takeIf { it.isNotBlank() },
            originName = origin?.name?.takeIf { it.isNotBlank() },
            originAddress = origin?.address?.takeIf { it.isNotBlank() },
            originLat = origin?.lat,
            originLng = origin?.lng,
            destinationName = destination?.name?.takeIf { it.isNotBlank() },
            destinationAddress = destination?.address?.takeIf { it.isNotBlank() },
            destinationLat = destination?.lat,
            destinationLng = destination?.lng,
            routeJson = route?.toString(),
            notificationEnabled = notificationEnabled ?: false,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
        )

        return schedule
    }

    companion object {
        private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

        fun fromEntity(
            schedule: Schedule,
            objectMapper: ObjectMapper,
            zoneId: ZoneId = seoulZone,
        ): ScheduleDto {
            val category = schedule.categorySnapshot
            val routeInfo = schedule.route

            return ScheduleDto(
                id = schedule.id,
                title = schedule.title,
                startAt = schedule.startAt.toString(),
                endAt = schedule.endAt.toString(),
                hasEndTime = schedule.hasEndTime,
                allDay = schedule.allDay,
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
                notes = schedule.notes,
                route = parseRoute(objectMapper, routeInfo?.routeJson),
                notificationEnabled = routeInfo?.notificationEnabled ?: false,
                notificationLeadMinutes = routeInfo?.notificationLeadMinutes,
                notificationIntervalMinutes = routeInfo?.notificationIntervalMinutes,
                updatedAt = (schedule.updateDt ?: schedule.updatedAt)
                    ?.atZone(zoneId)
                    ?.toInstant()
                    ?.toString(),
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

        private fun parseInstant(value: String): Instant {
            return runCatching { Instant.parse(value) }
                .recoverCatching { OffsetDateTime.parse(value).toInstant() }
                .recoverCatching { LocalDateTime.parse(value).atZone(seoulZone).toInstant() }
                .getOrElse {
                    throw IllegalArgumentException("날짜 형식이 올바르지 않습니다. value=$value")
                }
        }
    }
}
