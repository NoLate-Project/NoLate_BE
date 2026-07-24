package com.noLate.schedule.application.service

import com.noLate.notification.application.service.AppNotificationSnapshot
import com.noLate.notification.application.service.PersistedPushDispatchFenceFactory
import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushOutboxConfirmedDeliveryReconciler
import com.noLate.schedule.application.service.policy.DepartureReminderDecision
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SchedulePersistedPushDispatchFenceFactory : PersistedPushDispatchFenceFactory {
    override fun create(snapshot: AppNotificationSnapshot): PushDispatchFence? {
        val deduplicationKey = snapshot.deduplicationKey ?: return null
        if (!deduplicationKey.startsWith(SCHEDULE_PUSH_EVENT_PREFIX)) return null

        val match = SCHEDULE_PUSH_EVENT_PATTERN.matchEntire(deduplicationKey)
        val jobId = match?.groupValues?.get(1)?.toLongOrNull() ?: INVALID_JOB_ID
        val generation = match?.groupValues?.get(2)?.toLongOrNull() ?: INVALID_GENERATION
        val checkCount = match?.groupValues?.get(3)?.toIntOrNull()
        val memberId = snapshot.data["recipientMemberId"]?.toLongOrNull()
        val scheduleId = snapshot.scheduleId ?: snapshot.data["scheduleId"]?.toLongOrNull()
        val persistedFingerprint = snapshot.data["notificationInputFingerprint"]
        val metadataMatches =
            snapshot.data["schedulePushJobId"]?.toLongOrNull() == jobId &&
                snapshot.data["notificationGeneration"]?.toLongOrNull() == generation &&
                snapshot.data["schedulePushCheckCount"]?.toIntOrNull() == checkCount &&
                memberId != null &&
                scheduleId != null &&
                !persistedFingerprint.isNullOrBlank()

        return PushDispatchFence(
            jobId = jobId,
            workerId = "schedule-safety-outbox",
            jobVersion = -1,
            notificationGeneration = generation,
            notificationInputFingerprint =
                persistedFingerprint.takeIf { metadataMatches } ?: INVALID_FINGERPRINT,
            expectedMemberId = memberId ?: INVALID_MEMBER_ID,
            expectedScheduleId = scheduleId ?: INVALID_SCHEDULE_ID,
            requireWorkerLease = false,
        )
    }
}

/**
 * Schedule source lease보다 늦게 확정된 safety-outbox 성공을 job의 confirmed 지표에 반영한다.
 *
 * event key 자체는 해시이므로 저장된 deduplication key의 job/generation/check identity를
 * 사용한다. 일정 편집으로 generation이 바뀌었거나 더 뒤의 check가 이미 진행된 경우에는
 * 현재 의미 상태를 과거 event로 덮지 않는다.
 */
@Service
class SchedulePushOutboxConfirmedDeliveryReconciler(
    private val repository: SchedulePushJobRepository,
) : PushOutboxConfirmedDeliveryReconciler {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun reconcile(snapshot: AppNotificationSnapshot, confirmedAt: Instant) {
        val identity = snapshot.deduplicationKey
            ?.let(SCHEDULE_PUSH_EVENT_PATTERN::matchEntire)
            ?.let {
                SchedulePushEventIdentity(
                    jobId = it.groupValues[1].toLongOrNull() ?: return,
                    generation = it.groupValues[2].toLongOrNull() ?: return,
                    checkCount = it.groupValues[3].toIntOrNull() ?: return,
                )
            }
            ?: return
        val job = repository.findByIdForUpdate(identity.jobId) ?: return
        val recipientMemberId = snapshot.data["recipientMemberId"]?.toLongOrNull() ?: return
        val scheduleId = snapshot.scheduleId
            ?: snapshot.data["scheduleId"]?.toLongOrNull()
            ?: return
        if (
            job.memberId != recipientMemberId ||
            job.scheduleId != scheduleId ||
            job.notificationGeneration != identity.generation
        ) {
            return
        }
        if (job.status == SchedulePushJobStatus.PROCESSING) {
            // The source worker still owns the authoritative detached transition. Updating its
            // optimistic version here would make a normal success lose that transition.
            throw SchedulePushSourceStillProcessingException()
        }

        val decision = snapshot.data["departureReminderDecision"]
            ?.let { runCatching { DepartureReminderDecision.valueOf(it) }.getOrNull() }
        val notifiedDepartureAt = snapshot.data["recommendedDepartureAt"]
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.takeIf { decision != null && decision != DepartureReminderDecision.NONE }
        val reminderBoundaryAt = snapshot.data["reminderBoundaryAt"]
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        job.reconcileLateConfirmedPush(
            eventCheckCount = identity.checkCount,
            confirmedAt = confirmedAt,
            notifiedDepartureAt = notifiedDepartureAt,
            reminderBoundaryAt = reminderBoundaryAt,
            departureReminderStage = decision?.stage,
        )
        repository.flush()
    }
}

private data class SchedulePushEventIdentity(
    val jobId: Long,
    val generation: Long,
    val checkCount: Int,
)

private val SCHEDULE_PUSH_EVENT_PATTERN =
    Regex("""^schedule-push-job:(\d+):g(\d+):c(\d+)$""")

private class SchedulePushSourceStillProcessingException :
    RuntimeException("Schedule push source transition is still processing.")

private const val SCHEDULE_PUSH_EVENT_PREFIX = "schedule-push-job:"
private const val INVALID_JOB_ID = -1L
private const val INVALID_GENERATION = -1L
private const val INVALID_MEMBER_ID = -1L
private const val INVALID_SCHEDULE_ID = -1L
private const val INVALID_FINGERPRINT = "invalid-persisted-schedule-fingerprint"
