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
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.sharing.application.SharingBlockPolicy
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
                (fence.expectedCheckCount == null || job.checkCount == fence.expectedCheckCount) &&
                (fence.sourceExpiresAt == null || Instant.now(clock).isBefore(fence.sourceExpiresAt)) &&
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
 *
 * FE에서 공유 화면만 숨겨도 이미 동결된 delivery는 남아 있으므로 충분하지 않다. 전역 off는
 * 여기서도 다시 확인해 공유 row를 삭제하지 않고 dormant 상태로 보존하면서, 배포 전에 큐에
 * 들어온 공유 알림을 provider 호출 없이 SUPERSEDED로 수렴시킨다.
 */
@Service
class SchedulePushRecipientAccessValidator(
    private val scheduleRepository: ScheduleRepository,
    private val accessPolicy: ScheduleAccessPolicy,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val departureAlarmSyncStateRepository: DepartureAlarmSyncStateRepository,
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
    private val sharingBlockPolicy: SharingBlockPolicy,
) : PushRecipientAuthorizationValidator {
    fun canDispatch(memberId: Long, scheduleId: Long): Boolean {
        return canDispatch(
            memberId = memberId,
            scheduleId = scheduleId,
            categoryId = null,
            payloadType = SchedulePushPayloadAccessPolicy.SCHEDULE_PUSH_PAYLOAD_TYPE,
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
        if (payloadType == DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE) {
            return canDispatchDepartureAlarmSync(memberId, scheduleId)
        }
        if (!sharingAvailabilityPolicy.enabled) {
            return canDispatchWhileSharingDisabled(
                memberId = memberId,
                scheduleId = scheduleId,
                categoryId = categoryId,
                payloadType = payloadType,
                calendarId = calendarId,
            )
        }
        if (payloadType == CALENDAR_SHARE_RECEIVED_PAYLOAD_TYPE) {
            return calendarId?.let { canDispatchCalendar(memberId, it) } ?: false
        }
        if (scheduleId != null) {
            return canDispatchSchedule(memberId, scheduleId, payloadType)
        }
        if (categoryId != null) {
            return canDispatchCategory(memberId, categoryId)
        }
        if (calendarId != null) {
            return canDispatchCalendar(memberId, calendarId)
        }
        return true
    }

    /**
     * A CANCEL tombstone must survive schedule deletion and grant revocation so a device can remove
     * an already-installed alarm. UPSERT is different: the desired-state row is durable and can
     * outlive its authorization, so the provider fence rechecks current travel access before the
     * command leaves the server.
     */
    private fun canDispatchDepartureAlarmSync(memberId: Long, scheduleId: Long?): Boolean {
        val frozenScheduleId = scheduleId ?: return false
        val state = departureAlarmSyncStateRepository
            .findByMemberIdAndScheduleId(memberId, frozenScheduleId)
            ?: return false
        if (state.operation == DepartureAlarmSyncOperation.CANCEL) return true

        val schedule = scheduleRepository.findById(frozenScheduleId)
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?: return false
        return accessPolicy.resolve(memberId, schedule).travelEnabled
    }

    /**
     * 공유 기능 off는 기존 grant를 revoke/delete하는 상태가 아니다. 공유 전용 payload는
     * identity 유무와 관계없이 닫고, 일반 schedule/category/calendar 알림은 실제 소유자에게만
     * 허용해 개인 일정 ETA와 일반 알림을 계속 전달한다.
     */
    private fun canDispatchWhileSharingDisabled(
        memberId: Long,
        scheduleId: Long?,
        categoryId: Long?,
        payloadType: String?,
        calendarId: Long?,
    ): Boolean {
        if (payloadType in SHARING_ONLY_PAYLOAD_TYPES) return false
        // Legacy schedule alerts without a frozen schedule identity cannot be proven owner-bound.
        // Treating them as resource-free GENERAL would expose pre-deployment shared payloads.
        if (payloadType in SCHEDULE_ID_REQUIRED_PAYLOAD_TYPES && scheduleId == null) return false
        if (scheduleId != null) {
            val schedule = scheduleRepository.findById(scheduleId)
                .orElse(null)
                ?.takeUnless { it.deleted }
                ?: return false
            return accessPolicy.resolve(memberId, schedule).canManageAudience
        }
        if (categoryId != null) {
            val category = categoryRepository.findById(categoryId)
                .orElse(null)
                ?.takeUnless { it.deleted }
                ?: return false
            return if (category.calendarId == null) {
                category.memberId == memberId
            } else {
                calendarRepository.findByIdAndStatusAndDeletedFalse(
                    requireNotNull(category.calendarId),
                    ScheduleCalendarStatus.ACTIVE,
                )?.takeUnless { it.deleted }?.ownerMemberId == memberId
            }
        }
        if (calendarId != null) {
            return calendarRepository.findByIdAndStatusAndDeletedFalse(
                calendarId,
                ScheduleCalendarStatus.ACTIVE,
            )?.takeUnless { it.deleted }?.ownerMemberId == memberId
        }
        return true
    }

    private fun canDispatchCalendar(memberId: Long, calendarId: Long): Boolean {
        val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(
            calendarId,
            ScheduleCalendarStatus.ACTIVE,
        ) ?: return false
        if (calendar.deleted) return false
        if (sharingBlockPolicy.isInteractionBlocked(memberId, calendar.ownerMemberId)) return false
        return calendarMemberRepository
            .findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                calendarId,
                memberId,
                ScheduleCalendarMemberStatus.ACTIVE,
            ) != null
    }

    private fun canDispatchCategory(memberId: Long, categoryId: Long): Boolean {
        val category = categoryRepository.findById(categoryId)
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?: return false
        val calendarId = category.calendarId
        if (calendarId == null) {
            if (category.memberId == memberId) return true
            if (sharingBlockPolicy.isInteractionBlocked(memberId, category.memberId)) return false
        }
        if (calendarId != null) {
            val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(
                calendarId,
                ScheduleCalendarStatus.ACTIVE,
            )?.takeUnless { it.deleted } ?: return false
            // A calendar category can be created by an editor. The calendar owner, rather than the
            // category row's audit author, is the resource owner used by the sharing block fence.
            if (sharingBlockPolicy.isInteractionBlocked(memberId, calendar.ownerMemberId)) return false
            if (calendarMemberRepository
                    .findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                        calendarId,
                        memberId,
                        ScheduleCalendarMemberStatus.ACTIVE,
                    ) != null
            ) {
                return true
            }
        }
        return categoryShareRepository.findByCategoryIdAndTargetMemberId(categoryId, memberId)
            ?.takeIf { !it.deleted && it.status == ScheduleShareStatus.ACTIVE } != null
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
        val access = accessPolicy.resolve(memberId, schedule)
        return SchedulePushPayloadAccessPolicy.canDispatch(access, payloadType)
    }

    private companion object {
        const val CALENDAR_SHARE_RECEIVED_PAYLOAD_TYPE = "CALENDAR_SHARE_RECEIVED"
        val SHARING_ONLY_PAYLOAD_TYPES = setOf(
            "SCHEDULE_SHARE_RECEIVED",
            "CATEGORY_SHARE_RECEIVED",
            CALENDAR_SHARE_RECEIVED_PAYLOAD_TYPE,
            "SCHEDULE_PARTICIPANT_DEPARTED",
            "SCHEDULE_DEPARTURE_NUDGE",
        )
        val SCHEDULE_ID_REQUIRED_PAYLOAD_TYPES = setOf(
            SchedulePushPayloadAccessPolicy.SCHEDULE_PUSH_PAYLOAD_TYPE,
            "SCHEDULE_DETAIL",
            "SCHEDULE_TRAFFIC",
            "SCHEDULE_DEPARTURE_REMINDER",
            "ROUTE_SETUP_REMINDER",
        )
    }
}
