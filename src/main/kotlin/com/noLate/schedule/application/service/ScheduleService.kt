package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.application.cache.ScheduleCalendarCacheAudienceResolver
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.application.cache.ScheduleCalendarCacheScope
import com.noLate.schedule.application.cache.ScheduleCalendarCacheService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportResultDto
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.subscription.application.SubscriptionPolicyService
import jakarta.transaction.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
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
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
    private val calendarRepository: ScheduleCalendarRepository? = null,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository? = null,
    private val calendarCacheService: ScheduleCalendarCacheService? = null,
    private val calendarCacheAudienceResolver: ScheduleCalendarCacheAudienceResolver? = null,
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { _ -> },
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
    transactionManager: PlatformTransactionManager? = null,
) {
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")
    private val calendarReadTransaction = transactionManager?.let { manager ->
        TransactionTemplate(manager).apply {
            isReadOnly = true
        }
    }

    init {
        check(calendarCacheService == null || calendarReadTransaction != null) {
            "Calendar cache coordination requires a transaction manager for short DB loads."
        }
    }

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
        val authorizedDto = withAuthorizedCalendar(
            memberId = memberId,
            scheduleDto = withAuthorizedCategory(memberId, scheduleDto),
            existingCalendarId = null,
        )
        val routeNormalizedDto = normalizeRouteSetupDto(authorizedDto, existingSchedule = null)
        validateScheduleCoordinates(routeNormalizedDto)
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
        publishCalendarCacheInvalidation(
            memberIds = cacheAudience(savedEntity) + memberId,
            reason = "schedule-created",
        )

        return savedEntity.toDto(objectMapper)
    }

    @Transactional
    fun updateSchedule(
        memberId: Long,
        scheduleId: Long,
        scheduleDto: ScheduleDto,
    ): ScheduleDto {
        val existingSchedule = findEditableActive(memberId, scheduleId)
        val previousAudience = cacheAudience(existingSchedule)
        val authorizedDto = withAuthorizedCalendar(
            memberId = memberId,
            scheduleDto = withAuthorizedCategory(memberId, scheduleDto, existingSchedule),
            existingCalendarId = existingSchedule.calendarId,
        )
        val destinationSafeDto = preserveConfirmedDestinationCoordinates(
            scheduleDto = authorizedDto,
            existingSchedule = existingSchedule,
        )

        val routeNormalizedDto = normalizeRouteSetupDto(
            destinationSafeDto.copy(id = scheduleId),
            existingSchedule,
        )
        validateScheduleCoordinates(routeNormalizedDto)
        val normalizedDto = normalizeNotificationDto(
            memberId = memberId,
            scheduleDto = routeNormalizedDto,
            existingSchedule = existingSchedule,
        )
        validateScheduleRange(normalizedDto)

        applyDto(existingSchedule, normalizedDto)
        val savedEntity = scheduleRepository.save(existingSchedule)
        publishCalendarCacheInvalidation(
            memberIds = previousAudience + cacheAudience(savedEntity),
            reason = "schedule-updated",
        )

        // Mutation 후 owner mirror와 push job은 반드시 영속 route 원본으로 동기화한다.
        // 요청자별 travel plan 개인화는 UseCase가 owner 동기화 뒤 조회 응답에서 적용한다.
        return savedEntity.toDto(objectMapper)
    }

    /**
     * notification edit fence가 member와 push-job/gap을 잠근 다음 schedule row를 잠근다.
     * 실제 mutation은 같은 outer transaction의 [updateSchedule]/[deleteSchedule]에서
     * 권한과 active 상태를 다시 검증한다.
     */
    @Transactional
    fun lockForNotificationEdit(memberId: Long, scheduleId: Long) {
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        val policy = scheduleAccessPolicy
        if (policy == null) {
            if (schedule.memberId != memberId) {
                throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
            }
        } else if (!policy.resolve(memberId, schedule).canEdit) {
            throw BusinessException(ErrorCode.FORBIDDEN, "일정을 수정할 권한이 없습니다.")
        }
    }

    @Transactional
    fun deleteSchedule(memberId: Long, scheduleId: Long) {
        val entity = findOwnedActive(memberId, scheduleId)
        val affectedMemberIds = cacheAudience(entity) + memberId
        // 삭제한 외부 일정은 사용자가 나중에 의도적으로 다시 가져올 수 있어야 한다.
        entity.externalSourceKey = null
        entity.softDelete()
        scheduleRepository.save(entity)
        publishCalendarCacheInvalidation(affectedMemberIds, "schedule-deleted")
    }

    @Transactional
    fun markDeparted(memberId: Long, scheduleId: Long): ScheduleDto {
        val entity = findOwnedActive(memberId, scheduleId)
        val affectedMemberIds = cacheAudience(entity) + memberId
        // 출발 완료는 알림 액션에서 중복 호출될 수 있으므로 최초 완료 시각을 보존한다.
        // 경로 정보는 남겨 두고 해당 일정의 남은 실시간 알림만 비활성화한다.
        entity.route?.departedAt = entity.route?.departedAt ?: Instant.now()
        entity.route?.notificationEnabled = false
        entity.route?.notificationLeadMinutes = null
        entity.route?.notificationIntervalMinutes = null

        val saved = scheduleRepository.save(entity)
        publishCalendarCacheInvalidation(affectedMemberIds, "schedule-departed")
        return saved.toDto(objectMapper)
    }

    @Transactional
    fun getScheduleList(memberId: Long): List<ScheduleDto> {
        val schedules = if (sharingAvailabilityPolicy.enabled) {
            scheduleRepository.findScheduleList(memberId)
        } else {
            scheduleRepository.findOwnedScheduleList(memberId)
        }
        return toVisibleDtos(memberId, schedules)
    }

    @Transactional
    fun getScheduleDetail(memberId: Long, scheduleId: Long): ScheduleDto {
        return toVisibleDtos(memberId, listOf(findActive(memberId, scheduleId))).singleOrNull()
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    fun getCalendarScheduleList(memberId: Long, startAt: String, endAt: String): List<ScheduleDto> {
        val rangeStart = parseInstant(startAt, "startAt")
        val rangeEnd = parseInstant(endAt, "endAt")

        if (rangeEnd.isBefore(rangeStart)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "endAt must be after startAt.")
        }
        if (Duration.between(rangeStart, rangeEnd) > MAX_CALENDAR_RANGE) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "Calendar schedule range must not exceed $MAX_CALENDAR_RANGE_DAYS days.",
            )
        }

        val loader = { loadStart: Instant, loadEnd: Instant ->
            executeCalendarRead {
                toVisibleDtos(
                    memberId,
                    if (sharingAvailabilityPolicy.enabled) {
                        scheduleRepository.findOverlappingScheduleList(
                            memberId = memberId,
                            rangeStart = loadStart,
                            rangeEnd = loadEnd,
                        )
                    } else {
                        scheduleRepository.findOwnedOverlappingScheduleList(
                            memberId = memberId,
                            rangeStart = loadStart,
                            rangeEnd = loadEnd,
                        )
                    },
                )
            }
        }
        // owner-only와 공유 포함 DTO는 Redis namespace가 다르다. 기능을 끈 인스턴스도
        // 공유 결과를 재노출하지 않으면서 동일한 월 캐시를 안전하게 사용할 수 있다.
        return calendarCacheService?.getOrLoad(
            memberId = memberId,
            scope = calendarCacheScope(),
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            loader = loader,
        ) ?: loader(rangeStart, rangeEnd)
    }

    fun getCalendarCacheRevision(memberId: Long): Long =
        calendarCacheService?.currentRevision(memberId, calendarCacheScope())
            ?: calendarCacheScope().clientRevision(0L)

    /**
     * Cache coordination and follower waits must stay outside a DB transaction. Otherwise a cold
     * miss burst can occupy the whole Hikari pool while every follower waits for the same leader,
     * and MySQL repeatable-read can hide a revision committed during the leader load. Only the
     * repository/entity projection itself needs one short read-only transaction.
     */
    private fun <T : Any> executeCalendarRead(block: () -> T): T {
        val template = checkNotNull(calendarReadTransaction) {
            "Calendar schedule reads require a transaction manager."
        }
        return requireNotNull(template.execute { block() })
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
            if (sharingAvailabilityPolicy.enabled) {
                scheduleRepository.findOverlappingScheduleList(
                    memberId = memberId,
                    rangeStart = dayStart,
                    rangeEnd = dayEnd,
                )
            } else {
                scheduleRepository.findOwnedOverlappingScheduleList(
                    memberId = memberId,
                    rangeStart = dayStart,
                    rangeEnd = dayEnd,
                )
            },
        )
    }

    @Transactional
    fun getUpcomingScheduleList(memberId: Long, fromAt: String?, limit: Int?): List<ScheduleDto> {
        val normalizedFromAt = fromAt?.let { parseInstant(it, "fromAt") } ?: Instant.now()
        val normalizedLimit = (limit ?: 20).coerceIn(1, 100)

        return toVisibleDtos(
            memberId,
            if (sharingAvailabilityPolicy.enabled) {
                scheduleRepository.findUpcomingScheduleList(
                    memberId = memberId,
                    fromAt = normalizedFromAt,
                    pageable = PageRequest.of(0, normalizedLimit),
                )
            } else {
                scheduleRepository.findOwnedUpcomingScheduleList(
                    memberId = memberId,
                    fromAt = normalizedFromAt,
                    pageable = PageRequest.of(0, normalizedLimit),
                )
            },
        )
    }

    @Transactional
    fun searchScheduleList(
        memberId: Long,
        keyword: String?,
        categoryId: String?,
        startAt: String?,
        endAt: String?,
        limit: Int? = null,
    ): List<ScheduleDto> {
        val normalizedKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        if (
            normalizedKeyword != null &&
            normalizedKeyword.codePointCount(0, normalizedKeyword.length) < MIN_SEARCH_KEYWORD_LENGTH
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "Search keyword must be at least $MIN_SEARCH_KEYWORD_LENGTH characters.",
            )
        }
        // A missing/blank keyword deliberately remains compatible with category/date-only searches.
        // The cap is enforced here as well as in the DB query so every caller receives the same policy.
        val normalizedLimit = (limit ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
        val pageable = PageRequest.of(0, normalizedLimit)

        return toVisibleDtos(
            memberId,
            if (sharingAvailabilityPolicy.enabled) {
                scheduleRepository.searchScheduleList(
                    memberId = memberId,
                    keyword = normalizedKeyword,
                    categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
                    rangeStart = startAt?.let { parseInstant(it, "startAt") },
                    rangeEnd = endAt?.let { parseInstant(it, "endAt") },
                    pageable = pageable,
                )
            } else {
                scheduleRepository.searchOwnedScheduleList(
                    memberId = memberId,
                    keyword = normalizedKeyword,
                    categoryId = categoryId?.trim()?.takeIf { it.isNotEmpty() },
                    rangeStart = startAt?.let { parseInstant(it, "startAt") },
                    rangeEnd = endAt?.let { parseInstant(it, "endAt") },
                    pageable = pageable,
                )
            },
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
            if (sharingAvailabilityPolicy.enabled) {
                scheduleRepository.findDepartureReadyScheduleList(
                    memberId = memberId,
                    fromAt = normalizedFromAt,
                    toAt = normalizedToAt,
                )
            } else {
                scheduleRepository.findOwnedDepartureReadyScheduleList(
                    memberId = memberId,
                    fromAt = normalizedFromAt,
                    toAt = normalizedToAt,
                )
            },
        )
    }

    /**
     * Adds member-specific permission metadata after the repository has already
     * enforced visibility. The fields are response-only hints: write
     * authorization continues to use the share tables in service methods.
     */
    private fun toVisibleDtos(memberId: Long, schedules: List<Schedule>): List<ScheduleDto> {
        if (schedules.isEmpty()) return emptyList()

        val ownerScopedSchedules = if (sharingAvailabilityPolicy.enabled) {
            schedules
        } else {
            schedules.filter { it.memberId == memberId }
        }
        if (ownerScopedSchedules.isEmpty()) return emptyList()

        // The repository has already enforced visibility, and an all-owned result
        // needs no share/category/calendar grant lookup. This is the common calendar
        // path and avoids four fixed DB reads on each cold monthly cache fill.
        val allSchedulesOwned = ownerScopedSchedules.all { it.memberId == memberId }
        val accessByScheduleId = if (allSchedulesOwned) {
            if (scheduleAccessPolicy == null) {
                emptyMap()
            } else {
                ownerScopedSchedules.mapNotNull { schedule ->
                    schedule.id?.let { scheduleId ->
                        scheduleId to ownerAccessDecision(schedule)
                    }
                }.toMap()
            }
        } else {
            scheduleAccessPolicy
                ?.resolveAll(memberId, ownerScopedSchedules)
                .orEmpty()
        }
        val visibleSchedules = if (scheduleAccessPolicy == null || allSchedulesOwned) {
            ownerScopedSchedules
        } else {
            ownerScopedSchedules.filter { schedule ->
                schedule.id?.let(accessByScheduleId::get)?.canView == true
            }
        }
        if (visibleSchedules.isEmpty()) return emptyList()

        val scheduleIds = visibleSchedules.mapNotNull { it.id }
        val myPlans = scheduleTravelPlanService?.loadMyPlans(memberId, scheduleIds).orEmpty()
        fun personalizedDto(schedule: Schedule): ScheduleDto {
            val base = schedule.toDto(objectMapper)
            val access = schedule.id?.let(accessByScheduleId::get)
            val received = schedule.memberId != memberId
            val personalized = scheduleTravelPlanService?.personalizeScheduleDto(
                memberId = memberId,
                schedule = schedule,
                base = base,
                plan = schedule.id?.let(myPlans::get),
                access = access,
            ) ?: base
            return personalized.copy(
                sharePermission = access?.effectivePermission.takeIf { received },
                shareContentMode = access?.effectiveContentMode.takeIf { received },
                travelCollaborationEnabled = access?.travelEnabled,
                canViewAllTravelPlans = access?.canViewAllTravelPlans,
                category = if (received) {
                    personalized.category.copy(
                        shared = access?.categoryPermission != null,
                        sharePermission = access?.categoryPermission,
                    )
                } else {
                    personalized.category
                },
            )
        }

        val receivedSchedules = visibleSchedules.filter { it.memberId != memberId }
        if (receivedSchedules.isEmpty()) return visibleSchedules.map(::personalizedDto)

        if (scheduleAccessPolicy != null) {
            return visibleSchedules.map(::personalizedDto)
        }

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

        return visibleSchedules.map { schedule ->
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

    /**
     * Mirrors the central policy's owner result without touching any share repository.
     * Owner visibility is already established by the schedule row itself.
     */
    private fun ownerAccessDecision(schedule: Schedule): ScheduleAccessDecision {
        val travelEnabled = schedule.scheduleType == ScheduleType.ROUTE ||
            schedule.route != null ||
            schedule.routeSetupRequired
        return ScheduleAccessDecision(
            canView = true,
            canEdit = true,
            travelEnabled = travelEnabled,
            canViewAllTravelPlans = true,
            effectivePermission = ScheduleSharePermission.OWNER,
            effectiveContentMode = if (travelEnabled) {
                ScheduleShareContentMode.SCHEDULE_AND_TRAVEL
            } else {
                ScheduleShareContentMode.SCHEDULE_ONLY
            },
            calendarRole = ScheduleCalendarRole.OWNER,
        )
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

    private fun publishCalendarCacheInvalidation(memberIds: Collection<Long>, reason: String) {
        if (memberIds.isEmpty()) return
        eventPublisher.publishEvent(
            ScheduleCalendarCacheInvalidationEvent(
                memberIds = memberIds.toSet(),
                reason = reason,
            )
        )
    }

    private fun cacheAudience(schedule: Schedule): Set<Long> =
        if (sharingAvailabilityPolicy.enabled) {
            calendarCacheAudienceResolver?.resolve(schedule).orEmpty()
        } else {
            setOf(schedule.memberId)
        }

    private fun calendarCacheScope(): ScheduleCalendarCacheScope =
        ScheduleCalendarCacheScope.fromSharingEnabled(sharingAvailabilityPolicy.enabled)

    private fun findActive(memberId: Long, scheduleId: Long): Schedule {
        val schedule = if (sharingAvailabilityPolicy.enabled) {
            scheduleRepository.findScheduleDetail(scheduleId, memberId)
        } else {
            scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
        }
        return schedule
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    private fun findOwnedActive(memberId: Long, scheduleId: Long): Schedule {
        return scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
    }

    private fun findEditableActive(memberId: Long, scheduleId: Long): Schedule {
        if (!sharingAvailabilityPolicy.enabled) return findOwnedActive(memberId, scheduleId)

        val policy = scheduleAccessPolicy ?: return findOwnedActive(memberId, scheduleId)
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        if (!policy.resolve(memberId, schedule).canEdit) {
            throw BusinessException(ErrorCode.FORBIDDEN, "일정을 수정할 권한이 없습니다.")
        }
        return schedule
    }

    /**
     * 수정은 schedule row를 먼저 잠근 뒤 이 함수에 들어온다. 원본과 대상 캘린더를 id 오름차순으로
     * 잠그므로 서로 반대 방향의 일정 이동, 캘린더 보관, 강퇴가 겹쳐도 lock 순서가 뒤집히지 않는다.
     * 대상 상태와 멤버십은 잠금을 얻은 뒤 검사해 보관/강퇴 직전의 오래된 읽기로 저장하지 않는다.
     */
    private fun withAuthorizedCalendar(
        memberId: Long,
        scheduleDto: ScheduleDto,
        existingCalendarId: Long?,
    ): ScheduleDto {
        if (!sharingAvailabilityPolicy.enabled) {
            val requestedCalendarId = scheduleDto.calendarId
            if (requestedCalendarId == null || requestedCalendarId == existingCalendarId) {
                return scheduleDto
            }
            sharingAvailabilityPolicy.requireEnabled()
        }

        val calendars = calendarRepository ?: return scheduleDto
        val memberships = calendarMemberRepository ?: return scheduleDto

        val calendarIds = listOfNotNull(existingCalendarId, scheduleDto.calendarId)
            .distinct()
            .sorted()
        if (calendarIds.isEmpty()) return scheduleDto
        val lockedById = calendars.findAllForUpdate(calendarIds)
            .associateBy { requireNotNull(it.id) }

        val calendarId = scheduleDto.calendarId ?: return scheduleDto
        lockedById[calendarId]
            ?.takeIf { !it.deleted && it.status == ScheduleCalendarStatus.ACTIVE }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        val membership = memberships.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
            calendarId,
            memberId,
            ScheduleCalendarMemberStatus.ACTIVE,
        ) ?: throw BusinessException(ErrorCode.FORBIDDEN, "공유 캘린더 멤버가 아닙니다.")
        if (membership.role !in setOf(ScheduleCalendarRole.OWNER, ScheduleCalendarRole.EDITOR)) {
            throw BusinessException(ErrorCode.FORBIDDEN, "공유 캘린더에 일정을 저장할 권한이 없습니다.")
        }
        return scheduleDto
    }

    private fun withAuthorizedCategory(
        memberId: Long,
        scheduleDto: ScheduleDto,
        existingSchedule: Schedule? = null,
    ): ScheduleDto {
        // 단위 테스트에서 직접 생성한 legacy 인스턴스만 fallback을 허용한다. Spring 운영 bean에는
        // 두 repository가 항상 주입되어 client category snapshot을 신뢰하지 않는다.
        val categories = categoryRepository ?: return scheduleDto
        val shares = categoryShareRepository ?: return scheduleDto
        val categoryId = scheduleDto.category.id?.toLongOrNull()
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "일정을 저장할 카테고리를 선택해 주세요.")
        val existingSnapshot = existingSchedule?.categorySnapshot
        val existingCategoryId = existingSchedule?.categoryId
            ?: existingSnapshot?.categoryId?.toLongOrNull()
        if (existingSnapshot != null && existingCategoryId == categoryId) {
            return scheduleDto.copy(
                category = ScheduleCategoryDto(
                    id = existingSnapshot.categoryId,
                    title = existingSnapshot.title,
                    color = existingSnapshot.color,
                )
            )
        }
        val category = categories.findById(categoryId).orElse(null)
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)

        if (!sharingAvailabilityPolicy.enabled && category.memberId != memberId) {
            sharingAvailabilityPolicy.requireEnabled()
        }

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
        schedule.calendarId = source.calendarId
        schedule.scheduleType = source.scheduleType
        schedule.calendarContentModeOverride = source.calendarContentModeOverride
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
            alertMode = route?.alertMode ?: ScheduleAlertMode.STANDARD,
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
     * 이름만 있던 공통 도착지는 개인 경로 저장 시 서버에서 좌표가 보강될 수 있다.
     * 보강 전 일정을 열어 둔 클라이언트가 이후 제목 등을 수정하면 도착지 이름은 같지만
     * 좌표는 null인 요청을 보내게 된다. 이 경우에만 현재 서버 좌표를 병합해 나중에 도착한
     * 정상 일정 수정이 확정된 좌표를 지우지 못하게 한다. 다른 장소로 바꾼 요청이나 좌표를
     * 명시한 요청은 그대로 적용한다.
     */
    private fun preserveConfirmedDestinationCoordinates(
        scheduleDto: ScheduleDto,
        existingSchedule: Schedule,
    ): ScheduleDto {
        val requested = scheduleDto.destination ?: return scheduleDto
        if (requested.lat != null || requested.lng != null) return scheduleDto

        val current = existingSchedule.route ?: return scheduleDto
        val currentLat = current.destinationLat ?: return scheduleDto
        val currentLng = current.destinationLng ?: return scheduleDto
        if (
            !ScheduleDestinationIdentity.matches(
                firstName = current.destinationName,
                firstAddress = current.destinationAddress,
                secondName = requested.name,
                secondAddress = requested.address,
            )
        ) {
            return scheduleDto
        }

        return scheduleDto.copy(
            destination = requested.copy(lat = currentLat, lng = currentLng),
        )
    }

    private fun validateScheduleCoordinates(scheduleDto: ScheduleDto) {
        ScheduleCoordinateValidator.validateOptional(
            fieldLabel = "출발지",
            lat = scheduleDto.origin?.lat,
            lng = scheduleDto.origin?.lng,
        )
        ScheduleCoordinateValidator.validateOptional(
            fieldLabel = "도착지",
            lat = scheduleDto.destination?.lat,
            lng = scheduleDto.destination?.lng,
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
        val alertMode = if (
            existingSchedule == null ||
            existingSchedule.memberId == memberId
        ) {
            scheduleDto.alertMode
                ?: existingSchedule?.route?.alertMode
                ?: ScheduleAlertMode.STANDARD
        } else {
            // 알람 강도는 회원별 설정이다. 공유 편집 화면의 개인화 DTO가 owner route의
            // 알람 선호를 덮지 않으며, 편집자 자신의 값은 travel-plan API로 저장한다.
            existingSchedule.route?.alertMode ?: ScheduleAlertMode.STANDARD
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
            alertMode = alertMode,
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
        val requestedUserNotes = extractLegacyImportUserNotes(scheduleDto.notes, source.provider)
            ?: scheduleDto.notes?.trim().orEmpty()

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
                candidate.externalSourceKey == null &&
                    extractLegacyImportUserNotes(candidate.notes, source.provider) == requestedUserNotes
            }
    }

    /**
     * 예전 앱이 사용자 메모 뒤에 붙이던 가져오기 출처 문구를 분리한다.
     * 새 앱은 출처를 외부 원본 키로만 저장하므로 메모에 시스템 문구를 추가하지 않는다.
     */
    private fun extractLegacyImportUserNotes(
        notes: String?,
        provider: ScheduleImportProvider,
    ): String? {
        val normalizedNotes = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sourceLine = when (provider) {
            ScheduleImportProvider.APPLE_DEVICE -> "Apple 캘린더에서 가져온 일정"
            ScheduleImportProvider.ANDROID_DEVICE -> "Android 캘린더에서 가져온 일정"
            ScheduleImportProvider.GOOGLE -> "Google Calendar에서 가져온 일정"
        }
        val marker = "$sourceLine\n\n원본 캘린더: "
        val markerIndex = normalizedNotes.lastIndexOf(marker)
        if (markerIndex < 0) return null
        if (markerIndex > 0 && !normalizedNotes.substring(0, markerIndex).endsWith("\n\n")) return null

        val calendarTitle = normalizedNotes.substring(markerIndex + marker.length).trim()
        if (calendarTitle.isEmpty() || calendarTitle.contains('\n')) return null

        return normalizedNotes.substring(0, markerIndex).trim()
    }

    private fun requireSourceText(value: String?, fieldName: String): String {
        val text = requireText(value, fieldName)
        if (text.length > MAX_EXTERNAL_SOURCE_VALUE_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName is too long.")
        }
        return text
    }

    companion object {
        private const val MAX_CALENDAR_RANGE_DAYS = 190L
        private val MAX_CALENDAR_RANGE: Duration = Duration.ofDays(MAX_CALENDAR_RANGE_DAYS)
        private const val MIN_SEARCH_KEYWORD_LENGTH = 2
        private const val DEFAULT_SEARCH_LIMIT = 20
        private const val MAX_SEARCH_LIMIT = 50
        private const val MAX_EXTERNAL_SOURCE_VALUE_LENGTH = 1_024
    }
}
