package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 일정 오너가 아직 출발하지 않은 특정 참가자에게 출발 확인 푸시를 보내는 서비스다.
 *
 * 일정 편집 권한과 사람을 재촉하는 권한은 성격이 다르므로 EDITOR까지 암묵적으로 확장하지
 * 않고 실제 오너만 허용한다. 대상 역시 현재 활성 직접 공유 또는 활성 카테고리 공유에 포함된
 * 회원이어야 하며, 이미 출발한 회원에게는 중복 알림을 보내지 않는다.
 */
@Service
class ScheduleDepartureNotificationService(
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val pushEventOutboxService: PushEventOutboxService,
    private val sharingAvailability: ScheduleSharingAvailabilityPolicy,
    private val scheduleAccessPolicy: ScheduleAccessPolicy? = null,
) {

    @Transactional
    fun sendDepartureNudge(
        ownerMemberId: Long,
        scheduleId: Long,
        targetMemberId: Long,
        presentedSessionGeneration: Long,
    ): NotificationSendResult {
        sharingAvailability.requireEnabled()
        val lockedById = setOf(ownerMemberId, targetMemberId)
            .sorted()
            .associateWith(memberRepository::findByIdForUpdate)
        val actor = lockedById[ownerMemberId]
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료되었거나 존재하지 않는 로그인 세션입니다.")
        if (actor.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
        lockedById[targetMemberId]
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

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

        val prepared = pushEventOutboxService.enqueueDurable(
            memberId = targetMemberId,
            title = "출발 확인 요청",
            body = "'${schedule.title}' 일정의 출발 여부를 알려주세요.",
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_NUDGE",
                "scheduleId" to scheduleId.toString(),
                "requestedByMemberId" to ownerMemberId.toString(),
            ),
            // nudge endpoint는 한 번 호출할 때마다 새 사용자 이벤트다. UUID는 재호출을
            // 합치지 않으면서도 outbox writer 내부의 member-scoped dedupe 경계를 제공한다.
            deduplicationKey =
                "schedule-departure-nudge:$scheduleId:$ownerMemberId:$targetMemberId:${UUID.randomUUID()}",
        )
        return prepared.toQueuedSendResult()
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

/**
 * API 성공은 provider 전달 완료가 아니라 immutable outbox 접수를 뜻한다.
 *
 * requestedCount는 최초 transaction에서 동결된 기기 수를 유지하되 provider I/O는 별도
 * drainer가 맡으므로 attemptedCount/sentCount는 0이다. 이를 성공으로 부풀리면 실제
 * confirmed delivery 지표와 섞이므로 enqueue 시점에는 갱신하지 않는다.
 */
private fun PreparedPushEvent.toQueuedSendResult(): NotificationSendResult =
    NotificationSendResult(
        requestedCount = manifestRecipientCount,
        eventSnapshot = snapshot,
        inboxDeduplicated = !inboxCreated,
        fenceRejected = !fenceAccepted,
        recipientInactive = !recipientActive,
    )
