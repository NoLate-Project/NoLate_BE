package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDepartureParticipantDto
import com.noLate.schedule.domain.ScheduleDepartureParticipantRole
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

fun interface ScheduleDepartureFenceObserver {
    fun afterRecipientPreview(memberId: Long, scheduleId: Long)
}

data class ScheduleDepartureMemberFence(
    val memberId: Long,
    val scheduleId: Long,
    /** 최초 preview에서 동결한 알림 recipient. 이후 grant는 현재 action에 추가하지 않는다. */
    val frozenRecipientMemberIds: Set<Long>,
    /** 같은 transaction에서 실제로 잠기고 active였던 member 전체(actor 포함). */
    val activeLockedMemberIds: Set<Long>,
)

@Service
class ScheduleDepartureStatusService(
    private val scheduleRepository: ScheduleRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val memberRepository: MemberRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
    private val fenceObserver: ScheduleDepartureFenceObserver? = null,
) {
    /**
     * Security filter 이후 지연된 notification action의 account/session fence.
     *
     * depart-now는 한 이벤트에서 여러 recipient member를 다루므로 actor 하나를 먼저 잠그면
     * 서로 다른 참가자의 동시 요청이 actor(20) -> recipient(10), actor(10) -> recipient(20)
     * 순서로 교착될 수 있다. 잠금 없는 preview로 현재 recipient 집합을 찾은 뒤 항상 member id
     * 오름차순으로 잠그고, 그 잠금 아래 signed JWT generation을 다시 검증한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun lockNotificationActionMembers(
        memberId: Long,
        scheduleId: Long,
        presentedSessionGeneration: Long,
    ): ScheduleDepartureMemberFence {
        val visibleSchedule = requireVisibleTravelSchedule(memberId, scheduleId)
        val frozenRecipientMemberIds = notificationRecipientIds(visibleSchedule, memberId).toSet()
        val memberIds = (frozenRecipientMemberIds + memberId)
            .distinct()
            .sorted()
        fenceObserver?.afterRecipientPreview(memberId, scheduleId)
        val lockedMembers = memberRepository.findAllByIdsForUpdate(memberIds)
        val actor = lockedMembers.firstOrNull { it.id == memberId }
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료되었거나 존재하지 않는 로그인 세션입니다.",
            )
        if (actor.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료된 로그인 세션입니다.",
            )
        }
        return ScheduleDepartureMemberFence(
            memberId = memberId,
            scheduleId = scheduleId,
            frozenRecipientMemberIds = frozenRecipientMemberIds,
            activeLockedMemberIds = lockedMembers.asSequence()
                .filterNot { it.deleted }
                .mapNotNull { it.id }
                .toSet(),
        )
    }

    /**
     * 현재 로그인 사용자의 출발 완료 상태를 기록한다.
     *
     * 공유 일정에서는 출발 여부가 일정 전체가 아니라 참가자별 상태다. 그래서 먼저 현재
     * 사용자가 해당 일정에 접근 가능한지 확인하고, 그 다음 schedule row를 잠깐 잠근 뒤
     * (scheduleId, memberId) 상태 row를 생성/갱신한다. 같은 사용자의 중복 요청은 최초
     * departedAt만 유지한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun markDeparted(
        memberId: Long,
        scheduleId: Long,
        memberFence: ScheduleDepartureMemberFence? = null,
    ): ScheduleDepartureStatus {
        val visibleSchedule = requireVisibleTravelSchedule(memberId, scheduleId)

        val previewRecipients: Set<Long>
        val activeLockedMemberIds: Set<Long>
        if (memberFence != null) {
            check(memberFence.memberId == memberId && memberFence.scheduleId == scheduleId) {
                "Departure member fence identity does not match the requested action."
            }
            // The caller already owns these member locks in this transaction. Do not expand the
            // set from a second READ_COMMITTED preview: a newly granted lower member id could
            // otherwise reintroduce member-lock inversion.
            previewRecipients = memberFence.frozenRecipientMemberIds
            activeLockedMemberIds = memberFence.activeLockedMemberIds
        } else {
            // Non-action callers freeze and lock their own snapshot once.
            previewRecipients = notificationRecipientIds(visibleSchedule, memberId).toSet()
            activeLockedMemberIds = memberRepository.findAllByIdsForUpdate(
                (previewRecipients + memberId).distinct().sorted(),
            ).asSequence()
                .filterNot { it.deleted }
                .mapNotNull { it.id }
                .toSet()
        }
        if (memberId !in activeLockedMemberIds) {
            throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }

        val schedule = scheduleRepository.findActiveForDepartureUpdate(scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        val status = departureStatusRepository.findActiveForUpdate(
            scheduleId = scheduleId,
            memberId = memberId,
        ) ?: ScheduleDepartureStatus(
            scheduleId = scheduleId,
            memberId = memberId,
        )

        val firstDeparture = status.keepFirstDeparture(Instant.now(clock))
        val saved = departureStatusRepository.saveAndFlush(status)

        if (firstDeparture) {
            val stillEligibleRecipients = notificationRecipientIds(schedule, memberId)
            publishParticipantDeparted(
                schedule = schedule,
                departedMemberId = memberId,
                recipientMemberIds = previewRecipients
                    .filter { it in activeLockedMemberIds && it in stillEligibleRecipients },
            )
        }

        return saved
    }

    private fun requireVisibleTravelSchedule(
        memberId: Long,
        scheduleId: Long,
    ): com.noLate.schedule.domain.Schedule {
        val visibleSchedule = findVisibleSchedule(memberId, scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        scheduleAccessPolicy?.resolve(memberId, visibleSchedule)?.let { access ->
            if (!access.travelEnabled) {
                throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
            }
        }
        return visibleSchedule
    }

    /**
     * 첫 출발 전환을 다른 활성 참가자에게 알리는 transaction event를 만든다.
     *
     * 개별 일정 공유와 카테고리 공유가 겹칠 수 있으므로 LinkedHashSet으로 중복을 제거한다.
     * 출발한 본인은 수신 목록에서 제외한다. 이벤트에는 엔티티 대신 푸시에 필요한 불변값만
     * 넣어 BEFORE_COMMIT listener가 같은 transaction의 durable outbox로 안전하게 옮긴다.
     */
    private fun publishParticipantDeparted(
        schedule: com.noLate.schedule.domain.Schedule,
        departedMemberId: Long,
        recipientMemberIds: List<Long>,
    ) {
        val scheduleId = requireNotNull(schedule.id)

        val departedMember = memberRepository.findByIdAndDeletedFalse(departedMemberId)
        val departedMemberLabel = departedMember
            ?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: departedMember
                ?.email
                ?.substringBefore("@")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "참여자"

        eventPublisher.publishEvent(
            ScheduleParticipantDepartedEvent(
                scheduleId = scheduleId,
                scheduleTitle = schedule.title,
                departedMemberId = departedMemberId,
                departedMemberLabel = departedMemberLabel,
                recipientMemberIds = recipientMemberIds.toList(),
            )
        )
    }

    private fun notificationRecipientIds(
        schedule: com.noLate.schedule.domain.Schedule,
        departedMemberId: Long,
    ): List<Long> {
        val recipients = scheduleAccessPolicy
            ?.travelMemberIds(schedule)
            ?.toCollection(linkedSetOf())
            ?: legacyParticipantIds(schedule)
        recipients.remove(departedMemberId)
        return recipients.toList()
    }

    @Transactional
    fun attachDepartureParticipants(currentMemberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val scheduleId = scheduleDto.id ?: return scheduleDto
        val ownerMemberId = scheduleDto.ownerMemberId ?: return scheduleDto
        val categoryId = scheduleDto.category.id?.toLongOrNull()

        val schedule = findVisibleSchedule(currentMemberId, scheduleId)
        val access = schedule?.let { scheduleAccessPolicy?.resolve(currentMemberId, it) }
        if (scheduleAccessPolicy != null && (schedule == null || access?.travelEnabled != true)) {
            return scheduleDto.copy(myDepartedAt = null, departureParticipants = emptyList())
        }

        val participantRoles = linkedMapOf<Long, ScheduleDepartureParticipantRole>()
        val participantIds = if (schedule != null && scheduleAccessPolicy != null) {
            scheduleAccessPolicy.travelMemberIds(schedule)
        } else {
            legacyParticipantIds(scheduleId, ownerMemberId, categoryId).toList()
        }
        participantIds.forEach { participantMemberId ->
            participantRoles[participantMemberId] = if (participantMemberId == ownerMemberId) {
                ScheduleDepartureParticipantRole.OWNER
            } else {
                ScheduleDepartureParticipantRole.SHARED
            }
        }

        val statusesByMemberId = departureStatusRepository
            .findAllByScheduleIdAndDeletedFalse(scheduleId)
            .associateBy { it.memberId }

        val canManageParticipants = access?.canViewAllTravelPlans ?: (
            currentMemberId == ownerMemberId ||
                scheduleShareRepository.findByScheduleIdAndTargetMemberId(scheduleId, currentMemberId)
                    ?.let {
                        !it.deleted && it.status == ScheduleShareStatus.ACTIVE &&
                            it.permission == ScheduleSharePermission.EDITOR
                    } == true ||
                (categoryId != null && categoryShareRepository
                    .findByCategoryIdAndTargetMemberId(categoryId, currentMemberId)
                    ?.let {
                        !it.deleted && it.status == ScheduleShareStatus.ACTIVE &&
                            it.permission == ScheduleSharePermission.EDITOR
                    } == true)
            )

        val participants = participantRoles.map { (memberId, role) ->
            val statusDepartedAt = statusesByMemberId[memberId]?.departedAt?.toString()
            val departedAt = statusDepartedAt
                ?: scheduleDto.departedAt.takeIf { role == ScheduleDepartureParticipantRole.OWNER }

            ScheduleDepartureParticipantDto(
                memberId = memberId,
                email = if (canManageParticipants || memberId == currentMemberId) {
                    memberRepository.findByIdAndDeletedFalse(memberId)?.email
                } else {
                    null
                },
                role = role,
                departed = departedAt != null,
                departedAt = departedAt,
            )
        }

        val myDepartedAt = participants
            .firstOrNull { it.memberId == currentMemberId }
            ?.departedAt

        return scheduleDto.copy(
            myDepartedAt = myDepartedAt,
            departureParticipants = participants,
        )
    }

    /**
     * Off 상태에서는 broad share-aware native query 자체를 실행하지 않는다. 이후 canView
     * 재검증도 유지해 enabled 상태의 revoke/delete 경합에서 stale grant를 fail closed한다.
     */
    private fun findVisibleSchedule(memberId: Long, scheduleId: Long): Schedule? {
        val schedule = if (scheduleAccessPolicy?.isSharingDisabled() == true) {
            scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)
        } else {
            scheduleRepository.findScheduleDetail(scheduleId, memberId)
        } ?: return null
        val access = scheduleAccessPolicy?.resolve(memberId, schedule)
        return schedule.takeUnless { access != null && !access.canView }
    }

    private fun legacyParticipantIds(schedule: com.noLate.schedule.domain.Schedule): LinkedHashSet<Long> =
        legacyParticipantIds(
            scheduleId = requireNotNull(schedule.id),
            ownerMemberId = schedule.memberId,
            categoryId = schedule.categoryId ?: schedule.categorySnapshot?.categoryId?.toLongOrNull(),
        )

    /**
     * 직접 생성한 과거 단위 테스트와 점진 배포 중인 legacy wiring만을 위한 호환 계산이다.
     * 운영 Spring bean에는 [ScheduleAccessPolicy]가 주입되어 캘린더와 content mode까지 포함한
     * 중앙 계산을 사용한다.
     */
    private fun legacyParticipantIds(
        scheduleId: Long,
        ownerMemberId: Long,
        categoryId: Long?,
    ): LinkedHashSet<Long> {
        val memberIds = linkedSetOf(ownerMemberId)
        scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(scheduleId, ScheduleShareStatus.ACTIVE)
            .forEach { memberIds.add(it.targetMemberId) }
        categoryId
            ?.let {
                categoryShareRepository.findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                    it,
                    ScheduleShareStatus.ACTIVE,
                )
            }
            .orEmpty()
            .forEach { memberIds.add(it.targetMemberId) }
        return memberIds
    }
}
