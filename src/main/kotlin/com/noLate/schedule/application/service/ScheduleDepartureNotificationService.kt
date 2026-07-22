package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 일정 오너가 아직 출발하지 않은 특정 참가자에게 출발 확인 푸시를 보내는 서비스다.
 *
 * 일정 편집 권한과 사람을 재촉하는 권한은 성격이 다르므로 EDITOR까지 암묵적으로 확장하지
 * 않고 실제 오너만 허용한다. 대상 역시 현재 활성 직접 공유 또는 활성 카테고리 공유에 포함된
 * 회원이어야 하며, 이미 출발한 회원에게는 중복 알림을 보내지 않는다.
 */
@Service
class ScheduleDepartureNotificationService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val notificationUseCase: NotificationUseCase,
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
) {

    @Transactional
    fun sendDepartureNudge(
        ownerMemberId: Long,
        scheduleId: Long,
        targetMemberId: Long,
    ): NotificationSendResult {
        // 소유하지 않은 일정도 "존재하지만 권한 없음"으로 노출하지 않도록 기존 공유 API와
        // 동일하게 SCHEDULE_NOT_FOUND로 응답한다.
        val schedule = scheduleRepository.findOwnedScheduleDetail(scheduleId, ownerMemberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_NOT_FOUND)

        if (targetMemberId == ownerMemberId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "오너 본인에게 출발 확인 알림을 보낼 수 없습니다.")
        }

        val isTravelParticipant = scheduleAccessPolicy
            ?.travelMemberIds(schedule)
            ?.contains(targetMemberId)
            ?: isLegacyParticipant(schedule, targetMemberId)

        if (!isTravelParticipant) {
            throw BusinessException(
                ErrorCode.SCHEDULE_SHARE_NOT_FOUND,
                "현재 공유된 참가자에게만 출발 확인 알림을 보낼 수 있습니다.",
            )
        }

        val alreadyDeparted = departureStatusRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(scheduleId, targetMemberId)
            ?.departedAt != null
        if (alreadyDeparted) {
            throw BusinessException(ErrorCode.INVALID_STATE, "이미 출발한 참가자입니다.")
        }

        return notificationUseCase.sendToMember(
            memberId = targetMemberId,
            title = "출발 확인 요청",
            body = "'${schedule.title}' 일정의 출발 여부를 알려주세요.",
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_NUDGE",
                "scheduleId" to scheduleId.toString(),
                "requestedByMemberId" to ownerMemberId.toString(),
            ),
        )
    }

    private fun isLegacyParticipant(
        schedule: com.noLate.schedule.domain.Schedule,
        targetMemberId: Long,
    ): Boolean {
        val directShareIsActive = scheduleShareRepository
            .findByScheduleIdAndTargetMemberId(requireNotNull(schedule.id), targetMemberId)
            ?.let { !it.deleted && it.status == ScheduleShareStatus.ACTIVE }
            ?: false
        if (directShareIsActive) return true

        val categoryId = schedule.categoryId ?: schedule.categorySnapshot?.categoryId?.toLongOrNull()
        return categoryId
            ?.let { categoryShareRepository.findByCategoryIdAndTargetMemberId(it, targetMemberId) }
            ?.let { !it.deleted && it.status == ScheduleShareStatus.ACTIVE }
            ?: false
    }
}
