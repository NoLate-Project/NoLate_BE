package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleAlertMode
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
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
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
    private val entityManager: EntityManager,
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
    private val routeSetupReminderPolicy: RouteSetupReminderPolicy? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * 현재 로그인 사용자의 계획만 생성하거나 교체한다.
     *
     * 권한 없는 사용자가 임의 schedule row를 잠그지 못하도록 visibility를 먼저 확인한다.
     * 최초 저장 시에는 잠글 plan row가 아직 없으므로 그다음 항상 존재하는 schedule row를
     * 비관적 잠금하고, route까지 current read로 refresh한다. 이 refresh가 선행 visibility
     * 조회의 MySQL REPEATABLE READ snapshot에 의한 좌표/경로 덮어쓰기를 막는다.
     * `(schedule_id, member_id)` 유일키는 마지막 방어선으로 유지한다.
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
        refreshLockedSchedule(schedule)
        val access = scheduleAccessPolicy?.resolve(memberId, schedule)
        access?.let {
            if (!access.canView) {
                throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
            }
            if (!access.travelEnabled) {
                throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
            }
        }
        supplementCommonDestinationCoordinates(
            memberId = memberId,
            schedule = schedule,
            command = command,
            canEditCommonDestination = access?.canEdit ?: canViewAllTravelPlans(memberId, schedule),
        )

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
            travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)?.softDelete()
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
            destinationName = scheduleDto.destination?.name,
            destinationAddress = scheduleDto.destination?.address,
            destinationLat = scheduleDto.destination?.lat,
            destinationLng = scheduleDto.destination?.lng,
            routeJson = scheduleDto.route?.toString(),
            notificationEnabled = scheduleDto.notificationEnabled == true,
            notificationLeadMinutes = scheduleDto.notificationLeadMinutes,
            notificationIntervalMinutes = scheduleDto.notificationIntervalMinutes,
            alertMode = scheduleDto.alertMode,
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

    @Transactional
    fun findNotificationEnabledMemberIds(scheduleId: Long): Set<Long> =
        travelPlanRepository.findNotificationEnabledMemberIdsByScheduleId(scheduleId).toSet()

    @Transactional
    fun findActiveCalendarAudienceMemberIds(calendarId: Long): Set<Long> =
        scheduleAccessPolicy
            ?.activeCalendarMemberIds(calendarId)
            .orEmpty()
            .toSet()

    /**
     * schedule row lock을 얻은 뒤 다시 읽은 알림 대상은 편집이 job gap을 잡기 전에 잠근
     * member 집합의 부분집합이어야 한다. 새 대상이 보이면 gap 뒤에서 member lock을 추가해
     * 전역 lock order를 뒤집지 않고 transaction 전체를 롤백한다.
     */
    @Transactional
    fun requireNotificationMembersWithinFence(
        scheduleId: Long,
        lockedMemberIds: Set<Long>,
    ) {
        val currentMemberIds =
            travelPlanRepository.findNotificationEnabledMemberIdsByScheduleId(scheduleId)
        if (currentMemberIds.any { it !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Schedule notification participants changed while the edit fence was being acquired.",
            )
        }
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
        scheduleAccessPolicy?.resolve(requesterMemberId, schedule)?.let { access ->
            if (!access.travelEnabled) {
                throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
            }
        }
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
        scheduleAccessPolicy?.resolve(requesterMemberId, schedule)?.let { access ->
            if (!access.travelEnabled) {
                return ScheduleTravelPlanOverviewDto(
                    canViewAllTravelPlans = false,
                    myTravelPlan = null,
                    participants = emptyList(),
                )
            }
        }
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
        access: ScheduleAccessDecision? = null,
    ): ScheduleDto {
        val resolvedAccess = access ?: scheduleAccessPolicy?.resolve(memberId, schedule)
        val canManage = resolvedAccess?.canViewAllTravelPlans ?: canViewAllTravelPlans(memberId, schedule)
        if (memberId != schedule.memberId && resolvedAccess?.travelEnabled == false) {
            return base.copy(
                travelMinutes = null,
                departAt = null,
                departedAt = null,
                travelMode = null,
                origin = null,
                routeSetupRequired = false,
                route = null,
                notificationEnabled = false,
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
                alertMode = ScheduleAlertMode.STANDARD,
                myTravelPlan = null,
                travelPlanStatus = null,
                travelPlanParticipants = emptyList(),
            )
        }
        if (plan != null) {
            val dto = plan.toDto(schedule, canManage)
            return base.copy(
                travelMinutes = dto.travelMinutes,
                departAt = dto.departAt,
                departedAt = base.departedAt.takeIf { memberId == schedule.memberId },
                travelMode = dto.travelMode,
                origin = dto.origin,
                routeSetupRequired = routeSetupReminderPolicy?.requiresSetup(
                    schedule = schedule,
                    travelEnabled = resolvedAccess?.travelEnabled ?: true,
                    plan = plan,
                    now = Instant.now(clock),
                ) ?: (dto.status != ScheduleTravelPlanStatus.READY),
                route = dto.route,
                notificationEnabled = dto.notificationEnabled,
                notificationLeadMinutes = dto.notificationLeadMinutes,
                notificationIntervalMinutes = dto.notificationIntervalMinutes,
                alertMode = dto.alertMode,
                myTravelPlan = dto,
                travelPlanStatus = dto.status,
            )
        }

        if (memberId == schedule.memberId) {
            val legacy = schedule.route?.let { legacyOwnerPlanDto(schedule, canManage) }
            return base.copy(
                routeSetupRequired = routeSetupReminderPolicy?.requiresOwnerSetup(
                    schedule = schedule,
                    travelEnabled = true,
                    ownerPlan = plan,
                    now = Instant.now(clock),
                ) ?: base.routeSetupRequired,
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
            routeSetupRequired = routeSetupReminderPolicy?.requiresSetup(
                schedule = schedule,
                travelEnabled = resolvedAccess?.travelEnabled ?: true,
                plan = null,
                now = Instant.now(clock),
            ) ?: true,
            route = null,
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
            alertMode = ScheduleAlertMode.STANDARD,
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
        val existing = travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, memberId)
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
        val alertMode = command.alertMode
            ?: existing?.takeUnless { it.deleted }?.alertMode
            ?: ScheduleAlertMode.STANDARD
        plan.replace(
            command = command,
            scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
            departAt = departAt,
            routeJson = routeJson,
            notificationLeadMinutes = normalizedNotification.leadMinutes,
            notificationIntervalMinutes = normalizedNotification.intervalMinutes,
            alertMode = alertMode,
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
                throw BusinessException(ErrorCode.INVALID_INPUT, "이동 시간은 1분 이상 24시간 이하로 입력해 주세요.")
            }
        }
        ScheduleCoordinateValidator.validateOptional(
            fieldLabel = "출발지",
            lat = command.originLat,
            lng = command.originLng,
        )
        val destination = schedule.route
        ScheduleCoordinateValidator.validateOptional(
            fieldLabel = "공통 도착지",
            lat = destination?.destinationLat,
            lng = destination?.destinationLng,
        )
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

    /**
     * 빠른 일정은 공통 목적지 이름만 먼저 저장할 수 있다. 이후 오너 또는 에디터가 실제 경로를
     * 선택하면 개인 계획 요청에 포함된 동일 목적지 좌표로 비어 있는 공통 좌표만 보강한다.
     *
     * 공통 목적지의 이름과 주소는 이 경계에서 절대 변경하지 않는다. 기존 의미와 일치하는
     * 식별자가 없거나, 권한 없는 참가자가 공유 좌표를 쓰려는 요청은 거부한다. schedule row는
     * 호출자가 이미 비관적 잠금했으므로 동시에 들어온 보강도 한 좌표 쌍으로 직렬화된다.
     */
    private fun supplementCommonDestinationCoordinates(
        memberId: Long,
        schedule: Schedule,
        command: ScheduleTravelPlanUpsertCommand,
        canEditCommonDestination: Boolean,
    ) {
        if (!command.hasDestinationCandidate()) return

        val destination = schedule.route
            ?: throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "좌표를 보강할 기존 공통 도착지가 없습니다.",
            )
        val candidateCoordinates = command.requireValidDestinationCoordinates()
        val hasLatitude = destination.destinationLat != null
        val hasLongitude = destination.destinationLng != null
        // 이미 확정된 공통 좌표는 절대 덮어쓰지 않는다. 다만 새 클라이언트가 목적지 좌표를
        // 보냈다면 개인 routeJson/travelMinutes가 전혀 다른 장소 기준으로 섞이지 않도록
        // provider 표기명이 아니라 실제 좌표 간 거리를 검증한다.
        if (hasLatitude && hasLongitude) {
            val distanceMeters = haversineMeters(
                firstLat = requireNotNull(destination.destinationLat),
                firstLng = requireNotNull(destination.destinationLng),
                secondLat = candidateCoordinates.lat,
                secondLng = candidateCoordinates.lng,
            )
            if (!distanceMeters.isFinite() || distanceMeters > DESTINATION_MATCH_RADIUS_METERS) {
                throw BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "선택한 도착지가 일정의 공통 도착지에서 너무 멉니다. 일정을 다시 확인해 주세요.",
                )
            }
            return
        }
        if (hasLatitude != hasLongitude) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "공통 도착지 좌표가 부분적으로만 저장되어 있습니다. 일정 편집에서 장소를 다시 저장해 주세요.",
            )
        }
        if (!canEditCommonDestination) {
            throw BusinessException(
                ErrorCode.FORBIDDEN,
                "공통 도착지 좌표를 보강할 권한이 없습니다.",
            )
        }

        if (
            !ScheduleDestinationIdentity.matches(
                firstName = destination.destinationName,
                firstAddress = destination.destinationAddress,
                secondName = command.destinationName,
                secondAddress = command.destinationAddress,
            )
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "선택한 도착지가 일정의 공통 도착지와 일치하지 않습니다.",
            )
        }

        val scheduleId = requireNotNull(schedule.id)
        val hasAnotherActivePlan = travelPlanRepository
            .findAllActiveForScheduleUpdate(scheduleId)
            .any { it.memberId != memberId }
        if (hasAnotherActivePlan) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "다른 참가자의 이동 계획이 있어 공통 도착지를 여기서 보강할 수 없습니다. 일정 편집을 이용해 주세요.",
            )
        }

        destination.destinationLat = candidateCoordinates.lat
        destination.destinationLng = candidateCoordinates.lng
    }

    private fun ScheduleTravelPlanUpsertCommand.hasDestinationCandidate(): Boolean =
        destinationName != null || destinationAddress != null ||
            destinationLat != null || destinationLng != null

    private fun ScheduleTravelPlanUpsertCommand.requireValidDestinationCoordinates(): DestinationCoordinates {
        val coordinates = ScheduleCoordinateValidator.validateOptional(
            fieldLabel = "공통 도착지",
            lat = destinationLat,
            lng = destinationLng,
        ) ?: throw BusinessException(
            ErrorCode.INVALID_INPUT,
            "공통 도착지 좌표는 유효한 위도와 경도를 함께 입력해야 합니다.",
        )
        return DestinationCoordinates(lat = coordinates.lat, lng = coordinates.lng)
    }

    private fun haversineMeters(
        firstLat: Double,
        firstLng: Double,
        secondLat: Double,
        secondLng: Double,
    ): Double {
        val latitudeDelta = Math.toRadians(secondLat - firstLat)
        val longitudeDelta = Math.toRadians(secondLng - firstLng)
        val firstLatitude = Math.toRadians(firstLat)
        val secondLatitude = Math.toRadians(secondLat)
        val haversine =
            kotlin.math.sin(latitudeDelta / 2).let { it * it } +
                kotlin.math.cos(firstLatitude) * kotlin.math.cos(secondLatitude) *
                kotlin.math.sin(longitudeDelta / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * kotlin.math.asin(
            kotlin.math.sqrt(haversine.coerceIn(0.0, 1.0))
        )
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

    /**
     * `findVisibleSchedule` 같은 선행 consistent read나 상위 transaction의 persistence context에
     * 오래된 Schedule이 남아 있어도, schedule lock을 획득한 시점의 committed 원본으로
     * 되돌린다. route는 별도 1:1 테이블이므로 명시적으로 함께 잠그고 refresh한다.
     */
    private fun refreshLockedSchedule(schedule: Schedule) {
        entityManager.refresh(schedule, LockModeType.PESSIMISTIC_WRITE)
        schedule.route?.let { route ->
            entityManager.refresh(route, LockModeType.PESSIMISTIC_WRITE)
        }
    }

    private fun findVisibleSchedule(memberId: Long, scheduleId: Long): Schedule {
        val schedule = if (scheduleAccessPolicy?.isSharingDisabled() == true) {
            // Returning an empty participant envelope after a dormant grant matched would still
            // disclose that another member's schedule exists. Select the owner-only query first.
            scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
        } else {
            scheduleRepository.findScheduleDetail(scheduleId, memberId)
        } ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        val access = scheduleAccessPolicy?.resolve(memberId, schedule)
        if (access != null && !access.canView) {
            throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        }
        return schedule
    }

    private fun canViewAllTravelPlans(memberId: Long, schedule: Schedule): Boolean {
        scheduleAccessPolicy?.let { return it.resolve(memberId, schedule).canViewAllTravelPlans }
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
        scheduleAccessPolicy?.let { return it.travelMemberIds(schedule) }
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
            alertMode = alertMode,
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
            alertMode = route.alertMode,
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
                throw BusinessException(ErrorCode.INVALID_INPUT, "저장할 경로 정보를 확인하지 못했어요. 경로를 다시 선택해 주세요.")
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
            dto.notificationEnabled == true || dto.alertMode == ScheduleAlertMode.ALARM
}

private data class NormalizedNotification(
    val leadMinutes: Int?,
    val intervalMinutes: Int?,
)

private data class DestinationCoordinates(
    val lat: Double,
    val lng: Double,
)

private const val DESTINATION_MATCH_RADIUS_METERS = 500.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
