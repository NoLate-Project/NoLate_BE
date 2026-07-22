package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportResultDto
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val objectMapper: ObjectMapper,
    private val subscriptionPolicyService: SubscriptionPolicyService,
    private val categoryRepository: ScheduleCategoryRepository? = null,
    private val categoryShareRepository: ScheduleCategoryShareRepository? = null,
    private val scheduleShareRepository: ScheduleShareRepository? = null,
    private val scheduleTravelPlanService: ScheduleTravelPlanService? = null,
) {
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

    @Transactional
    fun addSchedule(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        return saveNewSchedule(memberId, scheduleDto, externalSourceKey = null)
    }

    /**
     * 외부 캘린더 한 발생 건을 회원별로 한 번만 저장한다.
     *
     * 애플리케이션 조회는 일반적인 재시도를 빠르게 처리하고, DB 유일 인덱스는
     * 여러 기기에서 동시에 같은 원본을 보내는 경우에도 실제 중복 행을 막는다.
     */
    @Transactional
    fun importSchedule(
        memberId: Long,
        scheduleDto: ScheduleDto,
        source: ScheduleImportSource,
    ): ScheduleImportResultDto {
        val externalSourceKey = buildExternalSourceKey(source)
        val existing = scheduleRepository
            .findFirstByMemberIdAndExternalSourceKeyAndDeletedFalse(memberId, externalSourceKey)
        if (existing != null) {
            return ScheduleImportResultDto(
                schedule = existing.toDto(objectMapper),
                created = false,
            )
        }

        // 원본 키 저장 기능이 없던 앱에서 가져온 일정은 정확히 같은 가져오기 메모와
        // 시간까지 일치할 때만 원본 키를 연결한다. 일반 수동 일정을 제목만으로 합치지 않는다.
        val legacyImport = findLegacyImportedSchedule(memberId, scheduleDto, source)
        if (legacyImport != null) {
            legacyImport.externalSourceKey = externalSourceKey
            val claimed = scheduleRepository.save(legacyImport)
            return ScheduleImportResultDto(
                schedule = claimed.toDto(objectMapper),
                created = false,
            )
        }

        return ScheduleImportResultDto(
            schedule = saveNewSchedule(memberId, scheduleDto, externalSourceKey),
            created = true,
        )
    }

    private fun saveNewSchedule(
        memberId: Long,
        scheduleDto: ScheduleDto,
        externalSourceKey: String?,
    ): ScheduleDto {
        val authorizedDto = withAuthorizedCategory(memberId, scheduleDto)
        val routeNormalizedDto = normalizeRouteSetupDto(authorizedDto, existingSchedule = null)
        val normalizedDto = normalizeNotificationDto(
            memberId = memberId,
            scheduleDto = routeNormalizedDto,
            existingSchedule = null,
        )
        validateScheduleRange(normalizedDto)

        val entity = normalizedDto.toEntity(memberId).apply {
            this.externalSourceKey = externalSourceKey
        }

        val savedEntity = scheduleRepository.save(entity)

        return savedEntity.toDto(objectMapper)
    }

    @Transactional
    fun updateSchedule(memberId: Long, scheduleId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val existingSchedule = findOwnedActive(memberId, scheduleId)
        val authorizedDto = withAuthorizedCategory(memberId, scheduleDto)

        val routeNormalizedDto = normalizeRouteSetupDto(
            authorizedDto.copy(id = scheduleId),
            existingSchedule,
        )
        val normalizedDto = normalizeNotificationDto(
            memberId = memberId,
            scheduleDto = routeNormalizedDto,
            existingSchedule = existingSchedule,
        )
        validateScheduleRange(normalizedDto)

        applyDto(existingSchedule, normalizedDto)
        val savedEntity = scheduleRepository.save(existingSchedule)

        return savedEntity.toDto(objectMapper)
    }

    @Transactional
    fun deleteSchedule(memberId: Long, scheduleId: Long) {
        val entity = findOwnedActive(memberId, scheduleId)
        // 삭제한 외부 일정은 사용자가 나중에 의도적으로 다시 가져올 수 있어야 한다.
        entity.externalSourceKey = null
        entity.softDelete()
        scheduleRepository.save(entity)
    }

    @Transactional
    fun markDeparted(memberId: Long, scheduleId: Long): ScheduleDto {
        val entity = findOwnedActive(memberId, scheduleId)
        // 출발 완료는 알림 액션에서 중복 호출될 수 있으므로 최초 완료 시각을 보존한다.
        // 경로 정보는 남겨 두고 해당 일정의 남은 실시간 알림만 비활성화한다.
        entity.route?.departedAt = entity.route?.departedAt ?: Instant.now()
        entity.route?.notificationEnabled = false
        entity.route?.notificationLeadMinutes = null
        entity.route?.notificationIntervalMinutes = null

        return scheduleRepository.save(entity).toDto(objectMapper)
    }

    @Transactional
    fun getScheduleList(memberId: Long): List<ScheduleDto> {
        return toVisibleDtos(memberId, scheduleRepository.findScheduleList(memberId))
    }

    @Transactional
    fun getScheduleDetail(memberId: Long, scheduleId: Long): ScheduleDto {
        return toVisibleDtos(memberId, listOf(findActive(memberId, scheduleId))).single()
    }

    @Transactional
    fun getCalendarScheduleList(memberId: Long, startAt: String, endAt: String): List<ScheduleDto> {
        val rangeStart = parseInstant(startAt, "startAt")
        val rangeEnd = parseInstant(endAt, "endAt")

        if (rangeEnd.isBefore(rangeStart)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "endAt must be after startAt.")
        }

        return toVisibleDtos(
            memberId,
            scheduleRepository.findOverlappingScheduleList(
                memberId = memberId,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
            ),
        )
    }

    @Transactional
    fun getDailyScheduleList(memberId: Long, date: String): List<ScheduleDto> {
        val dayStart = parseDate(date, "date")
            .atStartOfDay(seoulZone)
            .toInstant()

        val dayEnd = dayStart
            .plus(1, ChronoUnit.DAYS)
            .minusNanos(1)

        return toVisibleDtos(
            memberId,
            scheduleRepository.findOverlappingScheduleList(
                memberId = memberId,
                rangeStart = dayStart,
                rangeEnd = dayEnd,
            ),
        )
    }

    @Transactional
    fun getUpcomingScheduleList(memberId: Long, fromAt: String?, limit: Int?): List<ScheduleDto> {
        val normalizedFromAt = fromAt?.let { parseInstant(it, "fromAt") } ?: Instant.now()
        val normalizedLimit = (limit ?: 20).coerceIn(1, 100)

        return toVisibleDtos(
            memberId,
            scheduleRepository.findUpcomingScheduleList(
                memberId = memberId,
                fromAt = normalizedFromAt,
                pageable = PageRequest.of(0, normalizedLimit),
            ),
        )
    }

    @Transactional
    fun searchScheduleList(
        memberId: Long,
        keyword: String?,
        categoryId: String?,
        startAt: String?,
        endAt: String?,
    ): List<ScheduleDto> {
        return toVisibleDtos(
            memberId,
            scheduleRepository.searchScheduleList(
                memberId = memberId,
                keyword = keyword?.takeIf { it.isNotBlank() },
                categoryId = categoryId?.takeIf { it.isNotBlank() },
                rangeStart = startAt?.let { parseInstant(it, "startAt") },
                rangeEnd = endAt?.let { parseInstant(it, "endAt") },
            ),
        )
    }

    @Transactional
    fun getDepartureReadyScheduleList(memberId: Long, fromAt: String?, toAt: String?): List<ScheduleDto> {
        val normalizedFromAt = fromAt?.let { parseInstant(it, "fromAt") } ?: Instant.now()
        val normalizedToAt = toAt?.let { parseInstant(it, "toAt") }
            ?: normalizedFromAt.plus(1, ChronoUnit.DAYS)

        if (normalizedToAt.isBefore(normalizedFromAt)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "toAt must be after fromAt.")
        }

        return toVisibleDtos(
            memberId,
            scheduleRepository.findDepartureReadyScheduleList(
                memberId = memberId,
                fromAt = normalizedFromAt,
                toAt = normalizedToAt,
            ),
        )
    }

    /**
     * Adds member-specific permission metadata after the repository has already
     * enforced visibility. The fields are response-only hints: write
     * authorization continues to use the share tables in service methods.
     */
    private fun toVisibleDtos(memberId: Long, schedules: List<Schedule>): List<ScheduleDto> {
        if (schedules.isEmpty()) return emptyList()

        val scheduleIds = schedules.mapNotNull { it.id }
        val myPlans = scheduleTravelPlanService?.loadMyPlans(memberId, scheduleIds).orEmpty()
        fun personalizedDto(schedule: Schedule): ScheduleDto {
            val base = schedule.toDto(objectMapper)
            return scheduleTravelPlanService?.personalizeScheduleDto(
                memberId = memberId,
                schedule = schedule,
                base = base,
                plan = schedule.id?.let(myPlans::get),
            ) ?: base
        }

        val receivedSchedules = schedules.filter { it.memberId != memberId }
        if (receivedSchedules.isEmpty()) return schedules.map(::personalizedDto)

        val directPermissionByScheduleId = scheduleShareRepository
            ?.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                targetMemberId = memberId,
                status = ScheduleShareStatus.ACTIVE,
            )
            ?.associate { it.scheduleId to it.permission }
            .orEmpty()
        val categoryPermissionByCategoryId = categoryShareRepository
            ?.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                targetMemberId = memberId,
                status = ScheduleShareStatus.ACTIVE,
            )
            ?.associate { it.categoryId to it.permission }
            .orEmpty()

        return schedules.map { schedule ->
            val dto = personalizedDto(schedule)
            if (schedule.memberId == memberId) return@map dto

            val categoryPermission = schedule.categorySnapshot?.categoryId
                ?.toLongOrNull()
                ?.let(categoryPermissionByCategoryId::get)
            val directPermission = schedule.id?.let(directPermissionByScheduleId::get)
            val effectivePermission = strongestPermission(directPermission, categoryPermission)
                // A visible non-owner schedule always has at least one active
                // share. VIEWER is the conservative fallback for legacy wiring.
                ?: ScheduleSharePermission.VIEWER

            dto.copy(
                sharePermission = effectivePermission,
                category = dto.category.copy(
                    shared = categoryPermission != null,
                    sharePermission = categoryPermission,
                ),
            )
        }
    }

    private fun strongestPermission(
        first: ScheduleSharePermission?,
        second: ScheduleSharePermission?,
    ): ScheduleSharePermission? = listOfNotNull(first, second).maxByOrNull {
        when (it) {
            ScheduleSharePermission.VIEWER -> 0
            ScheduleSharePermission.COMMENTER -> 1
            ScheduleSharePermission.EDITOR -> 2
            ScheduleSharePermission.OWNER -> 3
        }
    }

    private fun findActive(memberId: Long, scheduleId: Long): Schedule {
        return scheduleRepository.findScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    private fun findOwnedActive(memberId: Long, scheduleId: Long): Schedule {
        return scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    private fun withAuthorizedCategory(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        // 단위 테스트에서 직접 생성한 legacy 인스턴스만 fallback을 허용한다. Spring 운영 bean에는
        // 두 repository가 항상 주입되어 client category snapshot을 신뢰하지 않는다.
        val categories = categoryRepository ?: return scheduleDto
        val shares = categoryShareRepository ?: return scheduleDto
        val categoryId = scheduleDto.category.id?.toLongOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "유효한 category.id가 필요합니다.")
        val category = categories.findById(categoryId).orElse(null)
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        val writable = category.memberId == memberId || shares
            .findByCategoryIdAndTargetMemberId(categoryId, memberId)
            ?.let {
                !it.deleted && it.status == ScheduleShareStatus.ACTIVE &&
                    it.permission in setOf(ScheduleSharePermission.EDITOR, ScheduleSharePermission.OWNER)
            } == true
        if (!writable) throw BusinessException(ErrorCode.FORBIDDEN, "카테고리에 일정을 저장할 권한이 없습니다.")

        return scheduleDto.copy(
            category = ScheduleCategoryDto(
                id = categoryId.toString(),
                title = category.title,
                color = category.color,
            )
        )
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
        schedule.routeSetupRequired = source.routeSetupRequired
        schedule.updateCategorySnapshot(
            categoryId = category.categoryId,
            title = category.title,
            color = category.color,
        )
        schedule.updateRoute(
            travelMinutes = route?.travelMinutes,
            departAt = route?.departAt,
            departedAt = route?.departedAt,
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

    private fun hasConfiguredRoute(scheduleDto: ScheduleDto): Boolean =
        scheduleDto.route != null ||
            scheduleDto.travelMinutes != null

    private fun normalizeRouteSetupDto(
        scheduleDto: ScheduleDto,
        existingSchedule: Schedule?,
    ): ScheduleDto = scheduleDto.copy(
        routeSetupRequired = when {
            hasConfiguredRoute(scheduleDto) -> false
            scheduleDto.routeSetupRequired != null -> scheduleDto.routeSetupRequired
            else -> existingSchedule?.routeSetupRequired ?: false
        },
    )

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

    private fun buildExternalSourceKey(source: ScheduleImportSource): String {
        val calendarId = requireSourceText(source.calendarId, "source.calendarId")
        val eventId = requireSourceText(source.eventId, "source.eventId")
        val occurrenceStartAt = parseInstant(source.occurrenceStartAt, "source.occurrenceStartAt").toString()

        // 길이 접두사를 붙여 원본 id 안에 구분 문자가 있어도 동일한 조합으로 오인하지 않는다.
        val sourceParts = mutableListOf(
            source.provider.name,
            calendarId,
            eventId,
        )
        // Google eventId는 반복 일정의 각 발생 건까지 식별한다. 반면 EventKit과
        // Android CalendarContract는 반복 원본 id를 공유할 수 있어 발생 시각이 필요하다.
        if (source.provider != ScheduleImportProvider.GOOGLE) {
            sourceParts += occurrenceStartAt
        }
        val canonical = sourceParts.joinToString(separator = "") { value -> "${value.length}:$value" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))

        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun findLegacyImportedSchedule(
        memberId: Long,
        scheduleDto: ScheduleDto,
        source: ScheduleImportSource,
    ): Schedule? {
        val normalizedNotes = scheduleDto.notes?.takeIf { it.isNotBlank() } ?: return null
        if (!hasLegacyImportMarker(normalizedNotes, source.provider)) return null

        val startAt = parseInstant(scheduleDto.startAt, "startAt")
        val hasEndTime = scheduleDto.hasEndTime ?: (scheduleDto.endAt != null)
        val endAt = if (hasEndTime) {
            parseInstant(scheduleDto.endAt, "endAt")
        } else {
            startAt
        }

        return scheduleRepository
            .findAllByMemberIdAndTitleAndStartAtAndEndAtAndDeletedFalseOrderByIdAsc(
                memberId = memberId,
                title = scheduleDto.title,
                startAt = startAt,
                endAt = endAt,
            )
            .firstOrNull { candidate ->
                candidate.externalSourceKey == null && candidate.notes == normalizedNotes
            }
    }

    private fun hasLegacyImportMarker(notes: String, provider: ScheduleImportProvider): Boolean {
        val sourceLine = when (provider) {
            ScheduleImportProvider.APPLE_DEVICE -> "Apple 캘린더에서 가져온 일정"
            ScheduleImportProvider.ANDROID_DEVICE -> "Android 캘린더에서 가져온 일정"
            ScheduleImportProvider.GOOGLE -> "Google Calendar에서 가져온 일정"
        }
        val lines = notes.lineSequence().map(String::trim).filter(String::isNotBlank).toList()

        return sourceLine in lines && lines.any { line -> line.startsWith("원본 캘린더: ") }
    }

    private fun requireSourceText(value: String?, fieldName: String): String {
        val text = requireText(value, fieldName)
        if (text.length > MAX_EXTERNAL_SOURCE_VALUE_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName is too long.")
        }
        return text
    }

    companion object {
        private const val MAX_EXTERNAL_SOURCE_VALUE_LENGTH = 1_024
    }
}
