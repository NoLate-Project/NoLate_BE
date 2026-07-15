package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleDepartureParticipantDto
import com.noLate.schedule.domain.ScheduleDepartureParticipantRole
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import jakarta.transaction.Transactional
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
    private val clock: Clock = Clock.systemUTC(),
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
        scheduleRepository.findScheduleDetail(scheduleId = scheduleId, memberId = memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        scheduleRepository.findActiveForDepartureUpdate(scheduleId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        val status = departureStatusRepository.findActiveForUpdate(
            scheduleId = scheduleId,
            memberId = memberId,
        ) ?: ScheduleDepartureStatus(
            scheduleId = scheduleId,
            memberId = memberId,
        )

        status.keepFirstDeparture(Instant.now(clock))
        return departureStatusRepository.saveAndFlush(status)
    }

    @Transactional
    fun attachDepartureParticipants(currentMemberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val scheduleId = scheduleDto.id ?: return scheduleDto
        val ownerMemberId = scheduleDto.ownerMemberId ?: return scheduleDto
        val categoryId = scheduleDto.category.id?.toLongOrNull()

        val participantRoles = linkedMapOf<Long, ScheduleDepartureParticipantRole>()
        participantRoles[ownerMemberId] = ScheduleDepartureParticipantRole.OWNER

        scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(scheduleId, ScheduleShareStatus.ACTIVE)
            .forEach { share ->
                participantRoles.putIfAbsent(share.targetMemberId, ScheduleDepartureParticipantRole.SHARED)
            }

        if (categoryId != null) {
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(categoryId, ScheduleShareStatus.ACTIVE)
                .forEach { share ->
                    participantRoles.putIfAbsent(share.targetMemberId, ScheduleDepartureParticipantRole.SHARED)
                }
        }

        val statusesByMemberId = departureStatusRepository
            .findAllByScheduleIdAndDeletedFalse(scheduleId)
            .associateBy { it.memberId }

        val participants = participantRoles.map { (memberId, role) ->
            val statusDepartedAt = statusesByMemberId[memberId]?.departedAt?.toString()
            val departedAt = statusDepartedAt
                ?: scheduleDto.departedAt.takeIf { role == ScheduleDepartureParticipantRole.OWNER }

            ScheduleDepartureParticipantDto(
                memberId = memberId,
                email = memberRepository.findByIdAndDeletedFalse(memberId)?.email,
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
}
