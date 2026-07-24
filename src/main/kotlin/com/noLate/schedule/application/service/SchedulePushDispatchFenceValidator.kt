package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushDispatchFenceDecision
import com.noLate.notification.application.service.PushDispatchFenceValidator
import com.noLate.notification.application.service.PushRecipientAuthorizationValidator
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

@Service
class SchedulePushDispatchFenceValidator(
    private val repository: SchedulePushJobRepository,
    private val clock: Clock,
    private val recipientAccessValidator: SchedulePushRecipientAccessValidator? = null,
) : PushDispatchFenceValidator {
    override fun validate(fence: PushDispatchFence): Boolean =
        evaluate(fence) == PushDispatchFenceDecision.ACCEPT

    override fun evaluate(fence: PushDispatchFence): PushDispatchFenceDecision {
        val job = repository.findByIdForUpdate(fence.jobId)
            ?: return PushDispatchFenceDecision.REJECT_TERMINAL
        val identityValid =
            job.notificationGeneration == fence.notificationGeneration &&
                job.notificationInputFingerprint == fence.notificationInputFingerprint &&
                (fence.expectedMemberId == null || job.memberId == fence.expectedMemberId) &&
                (fence.expectedScheduleId == null || job.scheduleId == fence.expectedScheduleId)
        if (!identityValid) {
            return PushDispatchFenceDecision.REJECT_TERMINAL
        }
        if (recipientAccessValidator?.canDispatch(job.memberId, job.scheduleId) == false) {
            return PushDispatchFenceDecision.REJECT_TERMINAL
        }

        if (!fence.requireWorkerLease) {
            return when (job.status) {
                SchedulePushJobStatus.ACTIVE -> PushDispatchFenceDecision.ACCEPT
                SchedulePushJobStatus.PROCESSING -> PushDispatchFenceDecision.RETRY_LATER
                SchedulePushJobStatus.COMPLETED,
                SchedulePushJobStatus.FAILED,
                SchedulePushJobStatus.CANCELED -> PushDispatchFenceDecision.REJECT_TERMINAL
            }
        }

        val leaseValid =
            job.status == SchedulePushJobStatus.PROCESSING &&
                job.lockedBy == fence.workerId &&
                job.version == fence.jobVersion
        if (leaseValid) {
            check(
                repository.heartbeatLeaseWithoutVersion(
                    fence.jobId,
                    Instant.now(clock),
                ) == 1
            ) {
                "Schedule push lease heartbeat target disappeared. jobId=${fence.jobId}"
            }
        }
        return if (leaseValid) {
            PushDispatchFenceDecision.ACCEPT
        } else {
            PushDispatchFenceDecision.REJECT_TERMINAL
        }
    }
}

/**
 * Provider 직전 claim transaction은 recipient member row를 먼저 잠근 뒤 이 검증을 실행한다.
 * category/schedule/calendar revoke도 같은 member-first 순서를 사용하므로 revoke가 먼저
 * linearize되면 과거 PROCESSING job/outbox는 provider 경계를 넘지 못하고 terminal 처리된다.
 * 반대로 claim이 먼저 member lock을 얻으면 논리 발송이 먼저인 계약이며 revoke가 기다린다.
 */
@Service
class SchedulePushRecipientAccessValidator(
    private val scheduleRepository: ScheduleRepository,
    private val accessPolicy: ScheduleAccessPolicy,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
) : PushRecipientAuthorizationValidator {
    fun canDispatch(memberId: Long, scheduleId: Long): Boolean {
        return canDispatch(
            memberId = memberId,
            scheduleId = scheduleId,
            categoryId = null,
            payloadType = SCHEDULE_PUSH_PAYLOAD_TYPE,
            calendarId = null,
        )
    }

    override fun canDispatch(
        memberId: Long,
        scheduleId: Long?,
        categoryId: Long?,
        payloadType: String?,
        calendarId: Long?,
    ): Boolean {
        if (payloadType == CALENDAR_SHARE_RECEIVED_PAYLOAD_TYPE) {
            return calendarId?.let { canDispatchCalendar(memberId, it) } ?: false
        }
        if (scheduleId != null) {
            return canDispatchSchedule(memberId, scheduleId, payloadType)
        }
        if (categoryId != null) {
            val category = categoryRepository.findById(categoryId)
                .orElse(null)
                ?.takeUnless { it.deleted }
                ?: return false
            if (category.memberId == memberId) return true
            return categoryShareRepository.findByCategoryIdAndTargetMemberId(categoryId, memberId)
                ?.takeIf { !it.deleted && it.status == ScheduleShareStatus.ACTIVE } != null
        }
        if (calendarId != null) {
            return canDispatchCalendar(memberId, calendarId)
        }
        return true
    }

    private fun canDispatchCalendar(memberId: Long, calendarId: Long): Boolean {
        val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(
            calendarId,
            ScheduleCalendarStatus.ACTIVE,
        ) ?: return false
        if (calendar.deleted) return false
        return calendarMemberRepository
            .findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                calendarId,
                memberId,
                ScheduleCalendarMemberStatus.ACTIVE,
            ) != null
    }

    private fun canDispatchSchedule(
        memberId: Long,
        scheduleId: Long,
        payloadType: String?,
    ): Boolean {
        val schedule = scheduleRepository.findById(scheduleId)
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?: return false
        if (schedule.memberId == memberId) return true
        val access = accessPolicy.resolve(memberId, schedule)
        return if (payloadType in TRAVEL_REQUIRED_PAYLOAD_TYPES) {
            access.travelEnabled
        } else {
            access.canView
        }
    }

    private companion object {
        const val SCHEDULE_PUSH_PAYLOAD_TYPE = "SCHEDULE_PUSH"
        const val CALENDAR_SHARE_RECEIVED_PAYLOAD_TYPE = "CALENDAR_SHARE_RECEIVED"
        val TRAVEL_REQUIRED_PAYLOAD_TYPES = setOf(
            SCHEDULE_PUSH_PAYLOAD_TYPE,
            "ROUTE_SETUP_REMINDER",
            "SCHEDULE_PARTICIPANT_DEPARTED",
            "SCHEDULE_DEPARTURE_NUDGE",
            "SCHEDULE_DEPARTURE_REMINDER",
            "SCHEDULE_TRAFFIC",
            "DEPARTURE_ADVANCE_NOTICE",
            "DEPARTURE_NOW",
            "DEPARTURE_REMINDER",
            "TRAFFIC_CHANGE",
        )
    }
}
