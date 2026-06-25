package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDto
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
        val normalizedDto = normalizeNotificationDto(
            memberId = memberId,
            scheduleDto = scheduleDto,
            existingSchedule = null,
        )
        validateScheduleRange(normalizedDto)

        val entity = normalizedDto.toEntity(memberId)

        val savedEntity = scheduleRepository.save(entity)

        return savedEntity.toDto(objectMapper)
    }

    @Transactional
    fun updateSchedule(memberId: Long, scheduleId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val existingSchedule = findActive(memberId, scheduleId)

        val normalizedDto = normalizeNotificationDto(
            memberId = memberId,
            scheduleDto = scheduleDto.copy(id = scheduleId),
            existingSchedule = existingSchedule,
        )
        validateScheduleRange(normalizedDto)

        applyDto(existingSchedule, normalizedDto)
        val savedEntity = scheduleRepository.save(existingSchedule)

        return savedEntity.toDto(objectMapper)
    }

    @Transactional
    fun deleteSchedule(memberId: Long, scheduleId: Long) {
        val entity = findActive(memberId, scheduleId)
        entity.softDelete()
        scheduleRepository.save(entity)
    }

    @Transactional
    fun markDeparted(memberId: Long, scheduleId: Long): ScheduleDto {
        val entity = findActive(memberId, scheduleId)
        entity.route?.notificationEnabled = false
        entity.route?.notificationLeadMinutes = null
        entity.route?.notificationIntervalMinutes = null

        return scheduleRepository.save(entity).toDto(objectMapper)
    }

    @Transactional
    fun getScheduleList(memberId: Long): List<ScheduleDto> {
        return scheduleRepository.findScheduleList(memberId)
            .map { it.toDto(objectMapper) }
    }

    @Transactional
    fun getScheduleDetail(memberId: Long, scheduleId: Long): ScheduleDto {
        return findActive(memberId, scheduleId).toDto(objectMapper)
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
            .map { it.toDto(objectMapper) }
    }

    @Transactional
    fun getDailyScheduleList(memberId: Long, date: String): List<ScheduleDto> {
        val dayStart = parseDate(date, "date")
            .atStartOfDay(seoulZone)
            .toInstant()

        val dayEnd = dayStart
            .plus(1, ChronoUnit.DAYS)
            .minusNanos(1)

        return scheduleRepository
            .findOverlappingScheduleList(
                memberId = memberId,
                rangeStart = dayStart,
                rangeEnd = dayEnd,
            )
            .map { it.toDto(objectMapper) }
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
            .map { it.toDto(objectMapper) }
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
        ).map { it.toDto(objectMapper) }
    }

    @Transactional
    fun getDepartureReadyScheduleList(memberId: Long, fromAt: String?, toAt: String?): List<ScheduleDto> {
        val normalizedFromAt = fromAt?.let { parseInstant(it, "fromAt") } ?: Instant.now()
        val normalizedToAt = toAt?.let { parseInstant(it, "toAt") }
            ?: normalizedFromAt.plus(1, ChronoUnit.DAYS)

        if (normalizedToAt.isBefore(normalizedFromAt)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "toAt must be after fromAt.")
        }

        return scheduleRepository.findDepartureReadyScheduleList(
            memberId = memberId,
            fromAt = normalizedFromAt,
            toAt = normalizedToAt,
        ).map { it.toDto(objectMapper) }
    }

    private fun findActive(memberId: Long, scheduleId: Long): Schedule {
        return scheduleRepository.findScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    private fun validateScheduleRange(scheduleDto: ScheduleDto) {
        if (scheduleDto.hasEndTime == false || scheduleDto.endAt == null) return

        val startAt = parseInstant(scheduleDto.startAt, "startAt")
        val endAt = parseInstant(scheduleDto.endAt, "endAt")
        if (!endAt.isAfter(startAt)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "endAt must be after startAt.")
        }
    }

    private fun applyDto(schedule: Schedule, scheduleDto: ScheduleDto) {
        val source = scheduleDto.toEntity(schedule.memberId)
        val category = requireNotNull(source.categorySnapshot)
        val route = source.route

        schedule.title = source.title
        schedule.startAt = source.startAt
        schedule.endAt = source.endAt
        schedule.hasEndTime = source.hasEndTime
        schedule.allDay = source.allDay
        schedule.notes = source.notes
        schedule.updateCategorySnapshot(
            categoryId = category.categoryId,
            title = category.title,
            color = category.color,
        )
        schedule.updateRoute(
            travelMinutes = route?.travelMinutes,
            departAt = route?.departAt,
            travelMode = route?.travelMode,
            locationName = route?.locationName,
            originName = route?.originName,
            originAddress = route?.originAddress,
            originLat = route?.originLat,
            originLng = route?.originLng,
            destinationName = route?.destinationName,
            destinationAddress = route?.destinationAddress,
            destinationLat = route?.destinationLat,
            destinationLng = route?.destinationLng,
            routeJson = route?.routeJson,
            notificationEnabled = route?.notificationEnabled ?: false,
            notificationLeadMinutes = route?.notificationLeadMinutes,
            notificationIntervalMinutes = route?.notificationIntervalMinutes,
        )
    }

    /**
     * 알림 관련 값들을 정책 기준으로 보정하고 검증한다.
     *
     * ScheduleDto.toEntity()는 단순 변환만 담당하고,
     * 구독 정책 검증은 Service에서 처리한다.
     */
    private fun normalizeNotificationDto(
        memberId: Long,
        scheduleDto: ScheduleDto,
        existingSchedule: Schedule?,
    ): ScheduleDto {
        val wasNotificationEnabled = existingSchedule?.route?.notificationEnabled == true
        val notificationEnabled = scheduleDto.notificationEnabled ?: wasNotificationEnabled

        val policy = if (notificationEnabled) {
            subscriptionPolicyService.getPolicy(memberId)
        } else {
            null
        }

        val notificationLeadMinutes = if (notificationEnabled) {
            scheduleDto.notificationLeadMinutes
                ?: existingSchedule?.route?.notificationLeadMinutes
                ?: requireNotNull(policy).maxNotificationLeadMinutes
        } else {
            null
        }

        val notificationIntervalMinutes = if (notificationEnabled) {
            scheduleDto.notificationIntervalMinutes
                ?: existingSchedule?.route?.notificationIntervalMinutes
                ?: requireNotNull(policy).minEtaRefreshIntervalMinutes
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

        return scheduleDto.copy(
            notificationEnabled = notificationEnabled,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
        )
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
