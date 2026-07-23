package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDepartureParticipantRole
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanOverviewDto
import com.noLate.schedule.domain.ScheduleTravelPlanParticipantDto
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
class ScheduleTravelPlanService(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val memberRepository: MemberRepository,
    private val subscriptionPolicyService: SubscriptionPolicyService,
    private val objectMapper: ObjectMapper,
) {
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * 현재 로그인 사용자의 계획만 생성하거나 교체한다.
     *
     * 접근 가능 여부를 먼저 확인한 다음 schedule row를 비관적 잠금한다. 최초 저장 시에는 잠글
     * plan row가 아직 없기 때문에 이 순서가 필요하다. 잠금 안에서 기존 행을 다시 조회하고
     * `(schedule_id, member_id)` 유일키까지 적용해 중복 생성과 lost update를 함께 방지한다.
     */
    @Transactional
    fun upsertMyTravelPlan(
        memberId: Long,
        scheduleId: Long,
        command: ScheduleTravelPlanUpsertCommand,
    ): ScheduleTravelPlanDto {
        findVisibleSchedule(memberId, scheduleId)
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        return upsertLocked(
            memberId = memberId,
            schedule = schedule,
            command = command,
            validateSubscription = true,
            requireCompleteRoute = true,
        )
    }

    /**
     * 기존 오너 생성/수정 흐름을 유지하면서 새 개인 계획 테이블에도 같은 값을 기록한다.
     * 구독 검증은 ScheduleService가 이미 완료했으므로 이 호환 쓰기에서는 중복 quota 소비를
     * 일으키지 않는다. 경로가 전혀 없는 일반 일정은 불필요한 빈 plan row를 만들지 않는다.
     */
    @Transactional
    fun syncOwnerTravelPlan(memberId: Long, scheduleDto: ScheduleDto): ScheduleTravelPlanDto? {
        val scheduleId = scheduleDto.id ?: return null
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)
            ?.takeIf { it.memberId == memberId }
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        if (!hasPersonalRoute(scheduleDto)) {
            // 기존 평탄형 경로를 삭제한 경우 호환 row도 함께 비활성화해야 다음 조회에서
            // 삭제 전 개인 경로가 다시 일정 필드로 투영되지 않는다.
            travelPlanRepository.findByScheduleIdAndMemberId(scheduleId, memberId)?.softDelete()
            return null
        }

        val command = ScheduleTravelPlanUpsertCommand(
            travelMinutes = scheduleDto.travelMinutes,
            departAt = scheduleDto.departAt,
            travelMode = scheduleDto.travelMode,
            originName = scheduleDto.origin?.name,
            originAddress = scheduleDto.origin?.address,
            originLat = scheduleDto.origin?.lat,
            originLng = scheduleDto.origin?.lng,
            routeJson = scheduleDto.route?.toString(),
            notificationEnabled = scheduleDto.notificationEnabled == true,
            notificationLeadMinutes = scheduleDto.notificationLeadMinutes,
            notificationIntervalMinutes = scheduleDto.notificationIntervalMinutes,
        )
        return upsertLocked(
            memberId = memberId,
            schedule = schedule,
            command = command,
            validateSubscription = false,
            requireCompleteRoute = false,
        )
    }

    @Transactional
    fun disableNotification(memberId: Long, scheduleId: Long) {
        travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId, memberId)
            ?.disableNotification()
    }

    /**
     * 일정 변경으로 무효화된 알림 계획의 회원을 찾는다. 알림 설정 자체는 보존해 사용자가
     * 새 경로를 저장할 때 같은 설정으로 다시 등록할 수 있게 하고, 호출 유스케이스가 해당
     * PushJob만 취소한다.
     */
    @Transactional
    fun findStaleNotificationMemberIds(scheduleId: Long): Set<Long> {
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        return travelPlanRepository.findAllByScheduleIdAndDeletedFalse(scheduleId)
            .asSequence()
            .filter { it.notificationEnabled }
            .filterNot { ScheduleTravelPlanFingerprint.matches(it, schedule) }
            .map { it.memberId }
            .toSet()
    }

    /**
     * 본인 계획은 항상 조회할 수 있고, 다른 참가자의 전체 경로는 오너 또는 유효한 EDITOR만
     * 조회할 수 있다. 조회 권한은 수정 권한으로 확장되지 않으며 갱신 API는 항상 `my` 경로만
     * 노출한다.
     */
    @Transactional
    fun getTravelPlan(
        requesterMemberId: Long,
        scheduleId: Long,
        targetMemberId: Long,
    ): ScheduleTravelPlanDto {
        val schedule = findVisibleSchedule(requesterMemberId, scheduleId)
        val canManage = canViewAllTravelPlans(requesterMemberId, schedule)
        if (requesterMemberId != targetMemberId && !canManage) {
            throw BusinessException(ErrorCode.FORBIDDEN, "다른 참가자의 이동 계획을 볼 권한이 없습니다.")
        }

        if (targetMemberId !in participantIds(schedule)) {
            throw BusinessException(ErrorCode.SCHEDULE_TRAVEL_PLAN_NOT_FOUND)
        }

        val plan = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId, targetMemberId)
        return when {
            plan != null -> plan.toDto(schedule, canManage)
            targetMemberId == schedule.memberId && schedule.route != null -> legacyOwnerPlanDto(schedule, canManage)
            else -> throw BusinessException(ErrorCode.SCHEDULE_TRAVEL_PLAN_NOT_FOUND)
        }
    }

    /**
     * 상세 화면의 참가자 목록을 구성한다. 오너/EDITOR에게는 저장된 출발지와 경로 요약을,
     * 일반 참여자에게는 설정 여부만 제공한다. routeJson은 목록 응답에 포함하지 않고 참가자를
     * 눌렀을 때 단건 상세 API에서만 내려 응답 크기와 위치 정보 노출 범위를 줄인다.
     */
    @Transactional
    fun getOverview(requesterMemberId: Long, scheduleId: Long): ScheduleTravelPlanOverviewDto {
        val schedule = findVisibleSchedule(requesterMemberId, scheduleId)
        val canManage = canViewAllTravelPlans(requesterMemberId, schedule)
        val participantIds = participantIds(schedule)
        val plansByMemberId = travelPlanRepository.findAllByScheduleIdAndDeletedFalse(scheduleId)
            .filter { it.memberId in participantIds }
            .associateBy { it.memberId }
        val membersById = memberRepository.findAllById(participantIds).associateBy { requireNotNull(it.id) }

        val participants = participantIds.map { memberId ->
            val plan = plansByMemberId[memberId]
            val legacyOwner = memberId == schedule.memberId && plan == null && schedule.route != null
            val status = when {
                plan != null -> plan.statusFor(schedule)
                legacyOwner -> ScheduleTravelPlanStatus.READY
                else -> ScheduleTravelPlanStatus.NOT_CONFIGURED
            }
            val canViewDetails = canManage || requesterMemberId == memberId
            val originName = when {
                !canViewDetails -> null
                plan != null -> plan.originName
                legacyOwner -> schedule.route?.originName
                else -> null
            }
            val travelMode = when {
                !canViewDetails -> null
                plan != null -> plan.travelMode
                legacyOwner -> schedule.route?.travelMode
                else -> null
            }
            val travelMinutes = when {
                !canViewDetails -> null
                plan != null -> plan.travelMinutes
                legacyOwner -> schedule.route?.travelMinutes
                else -> null
            }
            val departAt = when {
                !canViewDetails -> null
                plan != null -> plan.departAt?.toString()
                legacyOwner -> schedule.route?.departAt?.toString()
                else -> null
            }

            ScheduleTravelPlanParticipantDto(
                memberId = memberId,
                email = membersById[memberId]?.email.takeIf { canManage || requesterMemberId == memberId },
                role = if (memberId == schedule.memberId) {
                    ScheduleDepartureParticipantRole.OWNER
                } else {
                    ScheduleDepartureParticipantRole.SHARED
                },
                status = status,
                canViewDetails = canViewDetails,
                originName = originName,
                travelMode = travelMode,
                travelMinutes = travelMinutes,
                departAt = departAt,
            )
        }

        val myPlan = plansByMemberId[requesterMemberId]?.toDto(schedule, canManage)
            ?: if (requesterMemberId == schedule.memberId && schedule.route != null) {
                legacyOwnerPlanDto(schedule, canManage)
            } else {
                null
            }

        return ScheduleTravelPlanOverviewDto(
            canViewAllTravelPlans = canManage,
            myTravelPlan = myPlan,
            participants = participants,
        )
    }

    fun loadMyPlans(memberId: Long, scheduleIds: Collection<Long>): Map<Long, ScheduleTravelPlan> {
        if (scheduleIds.isEmpty()) return emptyMap()
        return travelPlanRepository
            .findAllByMemberIdAndScheduleIdInAndDeletedFalse(memberId, scheduleIds)
            .associateBy { it.scheduleId }
    }

    /**
     * 기존 평탄형 ScheduleDto를 현재 사용자 관점으로 투영한다. 공유받은 사용자의 plan이 없으면
     * 오너의 origin/route/알림 값을 모두 제거하고 공통 destination만 유지한다. 이 호환 투영으로
     * 기존 FE 필드 계약을 보존하면서도 오너 경로가 공유 사용자에게 새어 나가지 않는다.
     */
    fun personalizeScheduleDto(
        memberId: Long,
        schedule: Schedule,
        base: ScheduleDto,
        plan: ScheduleTravelPlan?,
    ): ScheduleDto {
        val canManage = canViewAllTravelPlans(memberId, schedule)
        if (plan != null) {
            val dto = plan.toDto(schedule, canManage)
            return base.copy(
                travelMinutes = dto.travelMinutes,
                departAt = dto.departAt,
                departedAt = base.departedAt.takeIf { memberId == schedule.memberId },
                travelMode = dto.travelMode,
                origin = dto.origin,
                routeSetupRequired = dto.status != ScheduleTravelPlanStatus.READY,
                route = dto.route,
                notificationEnabled = dto.notificationEnabled,
                notificationLeadMinutes = dto.notificationLeadMinutes,
                notificationIntervalMinutes = dto.notificationIntervalMinutes,
                myTravelPlan = dto,
                travelPlanStatus = dto.status,
            )
        }

        if (memberId == schedule.memberId) {
            val legacy = schedule.route?.let { legacyOwnerPlanDto(schedule, canManage) }
            return base.copy(
                myTravelPlan = legacy,
                travelPlanStatus = legacy?.status ?: ScheduleTravelPlanStatus.NOT_CONFIGURED,
            )
        }

        return base.copy(
            travelMinutes = null,
            departAt = null,
            departedAt = null,
            travelMode = null,
            origin = null,
            routeSetupRequired = true,
            route = null,
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
            myTravelPlan = null,
            travelPlanStatus = ScheduleTravelPlanStatus.NOT_CONFIGURED,
        )
    }

    fun attachOverview(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val scheduleId = scheduleDto.id ?: return scheduleDto
        val overview = getOverview(memberId, scheduleId)
        return scheduleDto.copy(
            myTravelPlan = overview.myTravelPlan,
            travelPlanStatus = overview.myTravelPlan?.status ?: ScheduleTravelPlanStatus.NOT_CONFIGURED,
            canViewAllTravelPlans = overview.canViewAllTravelPlans,
            travelPlanParticipants = overview.participants,
        )
    }

    private fun upsertLocked(
        memberId: Long,
        schedule: Schedule,
        command: ScheduleTravelPlanUpsertCommand,
        validateSubscription: Boolean,
        requireCompleteRoute: Boolean,
    ): ScheduleTravelPlanDto {
        validateCommand(command, schedule, requireCompleteRoute)
        val scheduleId = requireNotNull(schedule.id)
        val existing = travelPlanRepository.findByScheduleIdAndMemberId(scheduleId, memberId)
        val wasNotificationEnabled = existing?.takeUnless { it.deleted }?.notificationEnabled == true
        val normalizedNotification = normalizeNotification(
            memberId = memberId,
            command = command,
            wasNotificationEnabled = wasNotificationEnabled,
            validateSubscription = validateSubscription,
        )
        val departAt = command.departAt?.let { parseInstant(it, "departAt") }
        val routeJson = normalizeRouteJson(command.routeJson)
        val plan = existing ?: ScheduleTravelPlan(scheduleId = scheduleId, memberId = memberId)
        plan.replace(
            command = command,
            scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
            departAt = departAt,
            routeJson = routeJson,
            notificationLeadMinutes = normalizedNotification.leadMinutes,
            notificationIntervalMinutes = normalizedNotification.intervalMinutes,
        )
        val saved = travelPlanRepository.saveAndFlush(plan)
        return saved.toDto(schedule, canViewAllTravelPlans(memberId, schedule))
    }

    private fun validateCommand(
        command: ScheduleTravelPlanUpsertCommand,
        schedule: Schedule,
        requireCompleteRoute: Boolean,
    ) {
        command.travelMinutes?.let {
            if (it !in 1..1_440) {
                throw BusinessException(ErrorCode.INVALID_INPUT, "travelMinutes는 1~1440분이어야 합니다.")
            }
        }
        if ((command.originLat == null) != (command.originLng == null)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "출발지 위도와 경도는 함께 입력해야 합니다.")
        }
        val destination = schedule.route
        if (
            requireCompleteRoute &&
            (
                command.originLat == null || command.originLng == null ||
                    destination?.destinationLat == null || destination.destinationLng == null ||
                    command.travelMode == null || command.travelMinutes == null
                )
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "개인 이동 계획에는 출발지, 공통 도착지, 이동 수단과 이동 시간이 필요합니다.",
            )
        }
        if (command.notificationEnabled) {
            if (
                command.originLat == null || command.originLng == null ||
                destination?.destinationLat == null || destination.destinationLng == null ||
                command.travelMode == null || command.travelMinutes == null
            ) {
                throw BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "개인 출발 알림에는 출발지, 공통 도착지, 이동 수단과 이동 시간이 필요합니다.",
                )
            }
        }
    }

    private fun normalizeNotification(
        memberId: Long,
        command: ScheduleTravelPlanUpsertCommand,
        wasNotificationEnabled: Boolean,
        validateSubscription: Boolean,
    ): NormalizedNotification {
        if (!command.notificationEnabled) return NormalizedNotification(null, null)
        if (!validateSubscription) {
            return NormalizedNotification(
                leadMinutes = command.notificationLeadMinutes,
                intervalMinutes = command.notificationIntervalMinutes,
            )
        }

        val policy = subscriptionPolicyService.getPolicy(memberId)
        val leadMinutes = command.notificationLeadMinutes ?: policy.maxNotificationLeadMinutes
        val intervalMinutes = command.notificationIntervalMinutes ?: policy.minEtaRefreshIntervalMinutes
        subscriptionPolicyService.validateNotificationSettings(
            memberId = memberId,
            notificationEnabled = true,
            leadMinutes = leadMinutes,
            intervalMinutes = intervalMinutes,
            consumesNewQuota = !wasNotificationEnabled,
        )
        return NormalizedNotification(leadMinutes, intervalMinutes)
    }

    private fun findVisibleSchedule(memberId: Long, scheduleId: Long): Schedule =
        scheduleRepository.findScheduleDetail(scheduleId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

    private fun canViewAllTravelPlans(memberId: Long, schedule: Schedule): Boolean {
        if (schedule.memberId == memberId) return true
        val scheduleId = requireNotNull(schedule.id)
        val direct = scheduleShareRepository.findByScheduleIdAndTargetMemberId(scheduleId, memberId)
            ?.takeIf { !it.deleted && it.status == ScheduleShareStatus.ACTIVE }
            ?.permission
        val categoryId = schedule.categoryId
            ?: schedule.categorySnapshot?.categoryId?.toLongOrNull()
        val category = categoryId
            ?.let { categoryShareRepository.findByCategoryIdAndTargetMemberId(it, memberId) }
            ?.takeIf { !it.deleted && it.status == ScheduleShareStatus.ACTIVE }
            ?.permission
        return strongestPermission(direct, category) in setOf(
            ScheduleSharePermission.OWNER,
            ScheduleSharePermission.EDITOR,
        )
    }

    private fun participantIds(schedule: Schedule): List<Long> {
        val ids = linkedSetOf(schedule.memberId)
        val scheduleId = requireNotNull(schedule.id)
        scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(scheduleId, ScheduleShareStatus.ACTIVE)
            .mapTo(ids) { it.targetMemberId }
        val categoryId = schedule.categoryId ?: schedule.categorySnapshot?.categoryId?.toLongOrNull()
        if (categoryId != null) {
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(categoryId, ScheduleShareStatus.ACTIVE)
                .mapTo(ids) { it.targetMemberId }
        }
        return ids.toList()
    }

    private fun strongestPermission(
        direct: ScheduleSharePermission?,
        category: ScheduleSharePermission?,
    ): ScheduleSharePermission? = listOfNotNull(direct, category).maxByOrNull {
        when (it) {
            ScheduleSharePermission.VIEWER -> 0
            ScheduleSharePermission.COMMENTER -> 1
            ScheduleSharePermission.EDITOR -> 2
            ScheduleSharePermission.OWNER -> 3
        }
    }

    private fun ScheduleTravelPlan.toDto(schedule: Schedule, canManage: Boolean): ScheduleTravelPlanDto {
        val destination = destination(schedule)
        return ScheduleTravelPlanDto(
            id = id,
            scheduleId = scheduleId,
            memberId = memberId,
            status = statusFor(schedule),
            canManageSchedule = canManage,
            travelMinutes = travelMinutes,
            departAt = departAt?.toString(),
            travelMode = travelMode,
            origin = place(originName, originAddress, originLat, originLng),
            destination = destination,
            route = parseRoute(routeJson),
            notificationEnabled = notificationEnabled,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
            updatedAt = (updateDt ?: updatedAt)?.toString(),
        )
    }

    private fun ScheduleTravelPlan.statusFor(schedule: Schedule): ScheduleTravelPlanStatus =
        if (ScheduleTravelPlanFingerprint.matches(this, schedule)) {
            ScheduleTravelPlanStatus.READY
        } else {
            ScheduleTravelPlanStatus.STALE
        }

    private fun legacyOwnerPlanDto(schedule: Schedule, canManage: Boolean): ScheduleTravelPlanDto {
        val route = requireNotNull(schedule.route)
        return ScheduleTravelPlanDto(
            scheduleId = requireNotNull(schedule.id),
            memberId = schedule.memberId,
            status = ScheduleTravelPlanStatus.READY,
            canManageSchedule = canManage,
            travelMinutes = route.travelMinutes,
            departAt = route.departAt?.toString(),
            travelMode = route.travelMode,
            origin = place(route.originName, route.originAddress, route.originLat, route.originLng),
            destination = destination(schedule),
            route = parseRoute(route.routeJson),
            notificationEnabled = route.notificationEnabled,
            notificationLeadMinutes = route.notificationLeadMinutes,
            notificationIntervalMinutes = route.notificationIntervalMinutes,
            updatedAt = (schedule.updateDt ?: schedule.updatedAt)?.toString(),
        )
    }

    private fun destination(schedule: Schedule): SchedulePlaceDto? = schedule.route?.let {
        place(it.destinationName, it.destinationAddress, it.destinationLat, it.destinationLng)
    }

    private fun place(name: String?, address: String?, lat: Double?, lng: Double?): SchedulePlaceDto? {
        if (name == null && address == null && lat == null && lng == null) return null
        return SchedulePlaceDto(name = name, address = address, lat = lat, lng = lng)
    }

    private fun parseRoute(routeJson: String?): JsonNode? {
        if (routeJson.isNullOrBlank()) return null
        return objectMapper.readTree(routeJson)
    }

    private fun normalizeRouteJson(routeJson: String?): String? {
        if (routeJson.isNullOrBlank()) return null
        return runCatching { objectMapper.readTree(routeJson).toString() }
            .getOrElse {
                throw BusinessException(ErrorCode.INVALID_INPUT, "route는 올바른 JSON이어야 합니다.")
            }
    }

    private fun parseInstant(value: String, fieldName: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atZone(seoulZone).toInstant() }
            .getOrElse {
                throw BusinessException(ErrorCode.INVALID_INPUT, "$fieldName must be an ISO date-time.")
            }

    private fun hasPersonalRoute(dto: ScheduleDto): Boolean =
        dto.origin != null || dto.route != null || dto.travelMinutes != null ||
            dto.travelMode != null || dto.notificationEnabled == true
}

private data class NormalizedNotification(
    val leadMinutes: Int?,
    val intervalMinutes: Int?,
)
