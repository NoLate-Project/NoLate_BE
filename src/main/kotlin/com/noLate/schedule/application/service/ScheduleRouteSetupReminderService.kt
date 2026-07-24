package com.noLate.schedule.application.service

import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.findActiveNotificationRecipientForUpdate
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 유일키 충돌이 난 insert 트랜잭션을 호출자의 조회 트랜잭션과 분리한다. */
@Service
class ScheduleRouteSetupReminderWriter(
    private val repository: ScheduleRouteSetupReminderRepository,
    private val memberRepository: MemberRepository,
    private val insertValidator: ScheduleRouteSetupReminderInsertValidator? = null,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(reminder: ScheduleRouteSetupReminder): ScheduleRouteSetupReminder? {
        memberRepository.findActiveNotificationRecipientForUpdate(reminder.memberId) ?: return null
        if (insertValidator?.canInsert(reminder) == false) return null
        return repository.saveAndFlush(reminder)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(scheduleId: Long, memberId: Long, fingerprint: String): ScheduleRouteSetupReminder? =
        repository.findByScheduleIdAndMemberIdAndScheduleFingerprint(scheduleId, memberId, fingerprint)
}

/**
 * Scanner results are advisory. Marker creation re-reads the active schedule, immutable
 * fingerprint and recipient travel grant while holding the recipient member row. Category revoke,
 * category delete and owner withdrawal use that same member-first boundary, so cleanup-first can
 * never be followed by a recreated PENDING marker.
 */
@Service
class ScheduleRouteSetupReminderInsertValidator(
    private val scheduleRepository: ScheduleRepository,
    private val accessPolicy: ScheduleAccessPolicy,
) {
    fun canInsert(reminder: ScheduleRouteSetupReminder): Boolean {
        val schedule = scheduleRepository.findById(reminder.scheduleId)
            .orElse(null)
            ?.takeUnless { it.deleted }
            ?: return false
        if (ScheduleTravelPlanFingerprint.calculate(schedule) != reminder.scheduleFingerprint) {
            return false
        }
        val access = accessPolicy.resolve(reminder.memberId, schedule)
        return access.travelEnabled &&
            accessPolicy.routeReminderEnabled(reminder.memberId, schedule)
    }
}

/**
 * 동시 스캐너가 같은 회원·일정 지문을 발견해도 유일키 충돌을 정상적인 "이미 등록됨"으로
 * 흡수한다. 실패한 REQUIRES_NEW insert가 끝난 뒤 반환하므로 상위 트랜잭션은 rollback-only가
 * 되지 않는다.
 */
@Service
class ScheduleRouteSetupReminderRegistrar(
    private val writer: ScheduleRouteSetupReminderWriter,
) {
    fun register(scheduleId: Long, memberId: Long, fingerprint: String, now: Instant): Boolean {
        if (writer.find(scheduleId, memberId, fingerprint) != null) return false
        return try {
            writer.insert(
                ScheduleRouteSetupReminder(
                    scheduleId = scheduleId,
                    memberId = memberId,
                    scheduleFingerprint = fingerprint,
                    nextAttemptAt = now,
                )
            ) != null
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }
}

internal enum class RouteSetupOutboxEnqueueOutcome {
    ENQUEUED,
    SKIPPED,
    NONE,
}

fun interface ScheduleRouteSetupReminderDispatchObserver {
    fun afterCandidateRead(reminderId: Long)
}

/**
 * One marker is linearized in a short transaction:
 * member -> marker -> immutable outbox/manifest.
 *
 * Provider I/O is intentionally absent. The common PushOutboxDispatchWorker sends only after this
 * transaction commits, so a slow provider cannot hold route marker or business locks.
 */
@Service
class ScheduleRouteSetupReminderDispatchWriter(
    private val reminderRepository: ScheduleRouteSetupReminderRepository,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val accessPolicy: ScheduleAccessPolicy,
    private val reminderPolicy: RouteSetupReminderPolicy,
    private val pushEventOutboxService: PushEventOutboxService,
    private val dispatchObserver: ScheduleRouteSetupReminderDispatchObserver? = null,
) {
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED,
    )
    internal fun enqueueNext(now: Instant): RouteSetupOutboxEnqueueOutcome {
        val candidate = reminderRepository.findDueCandidates(
            status = ScheduleRouteSetupReminderStatus.PENDING,
            now = now,
            pageable = PageRequest.of(0, 1),
        ).singleOrNull() ?: return RouteSetupOutboxEnqueueOutcome.NONE
        dispatchObserver?.afterCandidateRead(requireNotNull(candidate.id))

        val recipientActive =
            memberRepository.findActiveNotificationRecipientForUpdate(candidate.memberId) != null
        val marker = reminderRepository.findByIdForUpdate(candidate.id)
            ?: return RouteSetupOutboxEnqueueOutcome.SKIPPED
        if (marker.status != ScheduleRouteSetupReminderStatus.PENDING ||
            marker.nextAttemptAt.isAfter(now)
        ) {
            return RouteSetupOutboxEnqueueOutcome.SKIPPED
        }
        if (!recipientActive) {
            marker.cancel()
            return RouteSetupOutboxEnqueueOutcome.SKIPPED
        }

        val schedule = scheduleRepository.findById(marker.scheduleId).orElse(null)
        if (!isStillRequired(marker, schedule, now)) {
            marker.cancel()
            return RouteSetupOutboxEnqueueOutcome.SKIPPED
        }
        val validSchedule = requireNotNull(schedule)
        val scheduleId = requireNotNull(validSchedule.id)
        val memberId = marker.memberId
        pushEventOutboxService.enqueueDurable(
            memberId = memberId,
            title = "경로를 설정해주세요",
            body = "'${validSchedule.title}' 일정이 3일 안에 시작돼요. 내 출발 경로를 확인해주세요.",
            data = mapOf(
                "type" to "ROUTE_SETUP_REMINDER",
                "scheduleId" to scheduleId.toString(),
                "scheduleIds" to scheduleId.toString(),
                "count" to "1",
                "routeSetupReminderId" to requireNotNull(marker.id).toString(),
                "routeSetupScheduleFingerprint" to marker.scheduleFingerprint,
            ),
            deduplicationKey = "route-setup:$memberId:marker:${requireNotNull(marker.id)}",
        )
        // SENT means the durable logical outbox is committed with this marker transaction.
        // Provider delivery is tracked independently by app_notifications/push_deliveries.
        marker.markSent(now)
        return RouteSetupOutboxEnqueueOutcome.ENQUEUED
    }

    private fun isStillRequired(
        reminder: ScheduleRouteSetupReminder,
        schedule: Schedule?,
        now: Instant,
    ): Boolean {
        if (schedule == null || schedule.deleted || schedule.id == null) return false
        if (ScheduleTravelPlanFingerprint.calculate(schedule) != reminder.scheduleFingerprint) return false
        val access = accessPolicy.resolve(reminder.memberId, schedule)
        if (!access.travelEnabled || !accessPolicy.routeReminderEnabled(reminder.memberId, schedule)) return false
        val plan = travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(
            requireNotNull(schedule.id),
            reminder.memberId,
        )
        return if (reminder.memberId == schedule.memberId) {
            reminderPolicy.requiresOwnerSetup(schedule, true, plan, now)
        } else {
            reminderPolicy.requiresSetup(schedule, true, plan, now)
        }
    }
}

@Service
class ScheduleRouteSetupReminderService(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val registrar: ScheduleRouteSetupReminderRegistrar,
    private val accessPolicy: ScheduleAccessPolicy,
    private val reminderPolicy: RouteSetupReminderPolicy,
    private val dispatchWriter: ScheduleRouteSetupReminderDispatchWriter,
    @Value("\${schedule.route-setup-reminder.batch-size:50}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * D-3 구간의 경로 일정만 읽는다. 일정 수가 많아도 access policy가 도출한 이동 멤버만
     * marker 후보가 되며, 이미 준비된 개인 계획은 등록하지 않는다.
     */
    @Transactional(readOnly = true)
    fun scan(now: Instant): Int {
        var createdCount = 0
        val candidates = scheduleRepository.findRouteSetupReminderCandidates(
            fromAt = now,
            toAt = now.plus(RouteSetupReminderPolicy.REMINDER_WINDOW),
        )
        val reminderMembersByScheduleId = accessPolicy.routeReminderMemberIdsAll(candidates)
        candidates.forEach { schedule ->
            val scheduleId = requireNotNull(schedule.id)
            val plansByMemberId = travelPlanRepository.findAllByScheduleIdAndDeletedFalse(scheduleId)
                .associateBy { it.memberId }
            val fingerprint = ScheduleTravelPlanFingerprint.calculate(schedule)
            reminderMembersByScheduleId[scheduleId].orEmpty().forEach { memberId ->
                val plan = plansByMemberId[memberId]
                val requiresSetup = if (memberId == schedule.memberId) {
                    reminderPolicy.requiresOwnerSetup(schedule, true, plan, now)
                } else {
                    reminderPolicy.requiresSetup(schedule, true, plan, now)
                }
                if (requiresSetup && registrar.register(scheduleId, memberId, fingerprint, now)) {
                    createdCount += 1
                }
            }
        }
        return createdCount
    }

    /**
     * 한 번에 최대 batchSize marker를 각각 독립된 짧은 transaction으로 durable enqueue한다.
     * 실제 provider 호출은 공용 outbox drainer가 transaction 밖에서 수행한다.
     */
    fun dispatch(now: Instant): Int {
        var enqueued = 0
        repeat(batchSize.coerceIn(1, 200)) {
            val outcome = try {
                dispatchWriter.enqueueNext(now)
            } catch (error: Exception) {
                log.warn(
                    "Route setup reminder enqueue failed. errorCode={}",
                    error.javaClass.simpleName,
                )
                return enqueued
            }
            when (outcome) {
                RouteSetupOutboxEnqueueOutcome.ENQUEUED -> enqueued += 1
                RouteSetupOutboxEnqueueOutcome.SKIPPED -> Unit
                RouteSetupOutboxEnqueueOutcome.NONE -> return enqueued
            }
        }
        return enqueued
    }
}

@Component
class ScheduleRouteSetupReminderWorker(
    private val service: ScheduleRouteSetupReminderService,
    private val clock: Clock,
    @Value("\${schedule.route-setup-reminder.enabled:true}") private val enabled: Boolean,
) {
    @Scheduled(fixedDelayString = "\${schedule.route-setup-reminder.fixed-delay-ms:300000}")
    fun run() {
        if (!enabled) return
        val now = Instant.now(clock)
        service.scan(now)
        service.dispatch(now)
    }
}
