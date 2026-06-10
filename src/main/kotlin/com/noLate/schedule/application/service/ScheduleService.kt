package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import jakarta.transaction.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val objectMapper: ObjectMapper,
    private val subscriptionPolicyService: SubscriptionPolicyService,
) {
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

    @Transactional
    fun addSchedule(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val entity = Schedule(memberId = memberId)
        entity.applyDto(memberId, scheduleDto)
        return scheduleRepository.save(entity).toDto()
    }

    @Transactional
    fun updateSchedule(memberId: Long, scheduleId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val entity = findActive(memberId, scheduleId)
        entity.applyDto(memberId, scheduleDto)
        return scheduleRepository.save(entity).toDto()
    }

    @Transactional
    fun deleteSchedule(memberId: Long, scheduleId: Long) {
        val entity = findActive(memberId, scheduleId)
        entity.softDelete()
        scheduleRepository.save(entity)
    }

    @Transactional
    fun getScheduleList(memberId: Long): List<ScheduleDto> {
        return scheduleRepository.findScheduleList(memberId)
            .map { it.toDto() }
    }

    @Transactional
    fun getScheduleDetail(memberId: Long, scheduleId: Long): ScheduleDto {
        return findActive(memberId, scheduleId).toDto()
    }

    @Transactional
    fun getCalendarScheduleList(memberId: Long, startAt: String, endAt: String): List<ScheduleDto> {
        val rangeStart = parseInstant(startAt, "startAt")
        val rangeEnd = parseInstant(endAt, "endAt")
        if (rangeEnd.isBefore(rangeStart)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "endAt must be after startAt.")
        }

        return scheduleRepository
            .findOverlappingScheduleList(
                memberId = memberId,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
            )
            .map { it.toDto() }
    }

    @Transactional
    fun getDailyScheduleList(memberId: Long, date: String): List<ScheduleDto> {
        val dayStart = parseDate(date, "date").atStartOfDay(seoulZone).toInstant()
        val dayEnd = dayStart.plus(1, ChronoUnit.DAYS).minusNanos(1)

        return scheduleRepository
            .findOverlappingScheduleList(
                memberId = memberId,
                rangeStart = dayStart,
                rangeEnd = dayEnd,
            )
            .map { it.toDto() }
    }

    @Transactional
    fun getUpcomingScheduleList(memberId: Long, fromAt: String?, limit: Int?): List<ScheduleDto> {
        val normalizedFromAt = fromAt?.let { parseInstant(it, "fromAt") } ?: Instant.now()
        val normalizedLimit = (limit ?: 20).coerceIn(1, 100)

        return scheduleRepository
            .findUpcomingScheduleList(
                memberId = memberId,
                fromAt = normalizedFromAt,
                pageable = PageRequest.of(0, normalizedLimit),
            )
            .map { it.toDto() }
    }

    @Transactional
    fun searchScheduleList(
        memberId: Long,
        keyword: String?,
        categoryId: String?,
        startAt: String?,
        endAt: String?,
    ): List<ScheduleDto> {
        return scheduleRepository.searchScheduleList(
            memberId = memberId,
            keyword = keyword?.takeIf { it.isNotBlank() },
            categoryId = categoryId?.takeIf { it.isNotBlank() },
            rangeStart = startAt?.let { parseInstant(it, "startAt") },
            rangeEnd = endAt?.let { parseInstant(it, "endAt") },
        ).map { it.toDto() }
    }

    @Transactional
    fun getDepartureReadyScheduleList(memberId: Long, fromAt: String?, toAt: String?): List<ScheduleDto> {
        val normalizedFromAt = fromAt?.let { parseInstant(it, "fromAt") } ?: Instant.now()
        val normalizedToAt = toAt?.let { parseInstant(it, "toAt") } ?: normalizedFromAt.plus(1, ChronoUnit.DAYS)
        if (normalizedToAt.isBefore(normalizedFromAt)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "toAt must be after fromAt.")
        }

        return scheduleRepository.findDepartureReadyScheduleList(
            memberId = memberId,
            fromAt = normalizedFromAt,
            toAt = normalizedToAt,
        ).map { it.toDto() }
    }

    private fun findActive(memberId: Long, scheduleId: Long): Schedule {
        return scheduleRepository.findScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    private fun Schedule.applyDto(memberId: Long, scheduleDto: ScheduleDto) {
        title = requireText(scheduleDto.title, "title")
        startAt = parseInstant(scheduleDto.startAt, "startAt")
        hasEndTime = scheduleDto.hasEndTime ?: (scheduleDto.endAt != null)
        endAt = if (hasEndTime) {
            parseInstant(scheduleDto.endAt, "endAt")
        } else {
            startAt
        }
        if (hasEndTime && !endAt.isAfter(startAt)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "endAt must be after startAt.")
        }

        allDay = scheduleDto.allDay ?: false
        notes = scheduleDto.notes?.takeIf { it.isNotBlank() }

        val wasNotificationEnabled = route?.notificationEnabled == true
        val notificationEnabled = scheduleDto.notificationEnabled ?: wasNotificationEnabled
        val policy = if (notificationEnabled) subscriptionPolicyService.getPolicy(memberId) else null
        val notificationLeadMinutes = if (notificationEnabled) {
            scheduleDto.notificationLeadMinutes
                ?: route?.notificationLeadMinutes
                ?: requireNotNull(policy).maxNotificationLeadMinutes
        } else {
            null
        }
        val notificationIntervalMinutes = if (notificationEnabled) {
            scheduleDto.notificationIntervalMinutes
                ?: route?.notificationIntervalMinutes
                ?: requireNotNull(policy).minNotificationIntervalMinutes
        } else {
            null
        }

        if (
            notificationEnabled &&
            (
                scheduleDto.origin?.lat == null ||
                    scheduleDto.origin.lng == null ||
                    scheduleDto.destination?.lat == null ||
                    scheduleDto.destination.lng == null ||
                    scheduleDto.travelMode == null
                )
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "실시간 출발 알림을 사용하려면 출발지, 도착지와 이동 경로가 필요합니다.",
            )
        }

        subscriptionPolicyService.validateNotificationSettings(
            memberId = memberId,
            notificationEnabled = notificationEnabled,
            leadMinutes = notificationLeadMinutes,
            intervalMinutes = notificationIntervalMinutes,
            consumesNewQuota = notificationEnabled && !wasNotificationEnabled,
        )

        updateCategorySnapshot(
            categoryId = requireText(scheduleDto.category.id, "category.id"),
            title = requireText(scheduleDto.category.title, "category.title"),
            color = requireText(scheduleDto.category.color, "category.color"),
        )

        updateRoute(
            travelMinutes = scheduleDto.travelMinutes,
            departAt = scheduleDto.departAt?.let { parseInstant(it, "departAt") },
            travelMode = scheduleDto.travelMode,
            locationName = scheduleDto.locationName?.takeIf { it.isNotBlank() },
            originName = scheduleDto.origin?.name?.takeIf { it.isNotBlank() },
            originAddress = scheduleDto.origin?.address?.takeIf { it.isNotBlank() },
            originLat = scheduleDto.origin?.lat,
            originLng = scheduleDto.origin?.lng,
            destinationName = scheduleDto.destination?.name?.takeIf { it.isNotBlank() },
            destinationAddress = scheduleDto.destination?.address?.takeIf { it.isNotBlank() },
            destinationLat = scheduleDto.destination?.lat,
            destinationLng = scheduleDto.destination?.lng,
            routeJson = scheduleDto.route?.let { objectMapper.writeValueAsString(it) },
            notificationEnabled = notificationEnabled,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
        )
    }

    private fun Schedule.toDto(): ScheduleDto {
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
            travelMode = routeInfo?.travelMode,
            origin = routeInfo?.let { toPlace(it.originName, it.originAddress, it.originLat, it.originLng) },
            destination = routeInfo?.let {
                toPlace(it.destinationName, it.destinationAddress, it.destinationLat, it.destinationLng)
            },
            locationName = routeInfo?.locationName,
            category = ScheduleCategoryDto(
                id = category?.categoryId,
                title = category?.title,
                color = category?.color,
            ),
            notes = notes,
            route = parseRoute(routeInfo?.routeJson),
            notificationEnabled = routeInfo?.notificationEnabled ?: false,
            notificationLeadMinutes = routeInfo?.notificationLeadMinutes,
            notificationIntervalMinutes = routeInfo?.notificationIntervalMinutes,
            updatedAt = (updateDt ?: updatedAt)?.atZone(seoulZone)?.toInstant()?.toString(),
        )
    }

    private fun toPlace(name: String?, address: String?, lat: Double?, lng: Double?): SchedulePlaceDto? {
        if (name == null && address == null && lat == null && lng == null) return null
        return SchedulePlaceDto(
            name = name,
            address = address,
            lat = lat,
            lng = lng,
        )
    }

    private fun parseRoute(routeJson: String?): JsonNode? {
        if (routeJson.isNullOrBlank()) return null
        return objectMapper.readTree(routeJson)
    }

    private fun parseInstant(value: String?, fieldName: String): Instant {
        val raw = requireText(value, fieldName)
        return runCatching { Instant.parse(raw) }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
            .recoverCatching { LocalDateTime.parse(raw).atZone(seoulZone).toInstant() }
            .getOrElse {
                throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName must be an ISO date-time.")
            }
    }

    private fun parseDate(value: String?, fieldName: String): LocalDate {
        val raw = requireText(value, fieldName)
        return runCatching { LocalDate.parse(raw) }
            .getOrElse {
                throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName must be ISO date.")
            }
    }

    private fun requireText(value: String?, fieldName: String): String {
        val text = value?.trim()
        if (text.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName is required.")
        }
        return text
    }
}
