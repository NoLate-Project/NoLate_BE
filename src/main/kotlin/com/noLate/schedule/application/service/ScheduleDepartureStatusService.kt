package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
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
import jakarta.transaction.Transactional
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

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
) {

    /**
     * 현재 로그인 사용자의 출발 완료 상태를 기록한다.
     *
     * 공유 일정에서는 출발 여부가 일정 전체가 아니라 참가자별 상태다. 그래서 먼저 현재
     * 사용자가 해당 일정에 접근 가능한지 확인하고, 그 다음 schedule row를 잠깐 잠근 뒤
     * (scheduleId, memberId) 상태 row를 생성/갱신한다. 같은 사용자의 중복 요청은 최초
     * departedAt만 유지한다.
     */
    @Transactional
    fun markDeparted(memberId: Long, scheduleId: Long): ScheduleDepartureStatus {
        val visibleSchedule = scheduleRepository.findScheduleDetail(scheduleId = scheduleId, memberId = memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)
        scheduleAccessPolicy?.resolve(memberId, visibleSchedule)?.let { access ->
            if (!access.travelEnabled) {
                throw BusinessException(ErrorCode.FORBIDDEN, "이 일정은 이동 기능을 공유하지 않습니다.")
            }
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
            publishParticipantDeparted(schedule, memberId)
        }

        return saved
    }

    /**
     * 첫 출발 전환을 다른 활성 참가자에게 알리는 커밋 후 이벤트를 만든다.
     *
     * 개별 일정 공유와 카테고리 공유가 겹칠 수 있으므로 LinkedHashSet으로 중복을 제거한다.
     * 출발한 본인은 수신 목록에서 제외한다. 이벤트에는 엔티티 대신 푸시에 필요한 불변값만
     * 넣어 AFTER_COMMIT 리스너가 영속성 컨텍스트 밖에서도 안전하게 처리할 수 있게 한다.
     */
    private fun publishParticipantDeparted(schedule: com.noLate.schedule.domain.Schedule, departedMemberId: Long) {
        val scheduleId = requireNotNull(schedule.id)
        val recipientMemberIds = scheduleAccessPolicy
            ?.travelMemberIds(schedule)
            ?.toCollection(linkedSetOf())
            ?: legacyParticipantIds(schedule)

        recipientMemberIds.remove(departedMemberId)

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

    @Transactional
    fun attachDepartureParticipants(currentMemberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val scheduleId = scheduleDto.id ?: return scheduleDto
        val ownerMemberId = scheduleDto.ownerMemberId ?: return scheduleDto
        val categoryId = scheduleDto.category.id?.toLongOrNull()

        val schedule = scheduleRepository.findScheduleDetail(scheduleId, currentMemberId)
        val access = schedule?.let { scheduleAccessPolicy?.resolve(currentMemberId, it) }
        if (access?.travelEnabled == false) {
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
