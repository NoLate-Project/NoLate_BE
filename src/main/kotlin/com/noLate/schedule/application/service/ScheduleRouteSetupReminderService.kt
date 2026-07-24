package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationUseCase
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
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 유일키 충돌이 난 insert 트랜잭션을 호출자의 조회 트랜잭션과 분리한다. */
@Service
class ScheduleRouteSetupReminderWriter(
    private val repository: ScheduleRouteSetupReminderRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(reminder: ScheduleRouteSetupReminder): ScheduleRouteSetupReminder =
        repository.saveAndFlush(reminder)

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(scheduleId: Long, memberId: Long, fingerprint: String): ScheduleRouteSetupReminder? =
        repository.findByScheduleIdAndMemberIdAndScheduleFingerprint(scheduleId, memberId, fingerprint)
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
            )
            true
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }
}

@Service
class ScheduleRouteSetupReminderService(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val reminderRepository: ScheduleRouteSetupReminderRepository,
    private val registrar: ScheduleRouteSetupReminderRegistrar,
    private val accessPolicy: ScheduleAccessPolicy,
    private val reminderPolicy: RouteSetupReminderPolicy,
    private val notificationUseCase: NotificationUseCase,
    @Value("\${schedule.route-setup-reminder.batch-size:50}") private val batchSize: Int,
    @Value("\${schedule.route-setup-reminder.max-attempts:3}") private val maxAttempts: Int,
    @Value("\${schedule.route-setup-reminder.retry-delay-seconds:300}") private val retryDelaySeconds: Long,
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
     * due marker를 비관적 락으로 선점한 뒤 marker별 immutable event로 발송한다.
     *
     * 여러 marker를 현재 batch 구성으로 묶으면 A의 부분 실패 뒤 B가 새로 due된 재시도에서
     * event key가 A -> A+B로 바뀌어 A의 성공 기기까지 다시 호출될 수 있다. marker PK 하나를
     * logical event 하나로 고정해 retry batch가 달라져도 payload와 per-device 경계를 보존한다.
     * 발송 직전에는 공유 회수, 캘린더 알림 opt-out, 경로 저장, 일정 변경을 다시 확인한다.
     */
    @Transactional
    fun dispatch(now: Instant): Int {
        val locked = reminderRepository.findDueForUpdate(
            status = ScheduleRouteSetupReminderStatus.PENDING,
            now = now,
            pageable = PageRequest.of(0, batchSize.coerceIn(1, 200)),
        )
        val valid = locked.mapNotNull { reminder ->
            val schedule = scheduleRepository.findById(reminder.scheduleId).orElse(null)
            if (!isStillRequired(reminder, schedule, now)) {
                reminder.cancel()
                null
            } else {
                reminder to requireNotNull(schedule)
            }
        }

        var sentEvents = 0
        valid.sortedBy { requireNotNull(it.first.id) }.forEach { (reminder, schedule) ->
            val memberId = reminder.memberId
            try {
                val title = "경로를 설정해주세요"
                val body =
                    "'${schedule.title}' 일정이 3일 안에 시작돼요. 내 출발 경로를 확인해주세요."
                val scheduleId = requireNotNull(schedule.id)
                val deduplicationKey = dispatchDeduplicationKey(memberId, reminder)
                val payload = mapOf(
                    "type" to "ROUTE_SETUP_REMINDER",
                    "scheduleId" to scheduleId.toString(),
                    "scheduleIds" to scheduleId.toString(),
                    "count" to "1",
                )

                // marker PK의 deterministic key를 inbox와 기기별 delivery 경계에 함께
                // 사용한다. 재시도 batch에 다른 marker가 합류해도 이 event는 변하지 않는다.
                val result = notificationUseCase.sendToMember(
                    memberId = memberId,
                    title = title,
                    body = body,
                    data = payload,
                    inboxDeduplicationKey = deduplicationKey,
                )
                if (result.retryableFailedCount > 0) {
                    throw RouteSetupPushDispatchException(
                        "경로 미설정 push 재시도 필요: requested=${result.requestedCount}, " +
                            "retryableFailed=${result.retryableFailedCount}"
                    )
                }
                if (result.requestedCount > 0 && result.durablyHandledCount == 0) {
                    throw RouteSetupPushDispatchException(
                        "경로 미설정 push 발송 실패: requested=${result.requestedCount}, failed=${result.failedCount}"
                    )
                }
                reminder.markSent(now)
                sentEvents += 1
            } catch (error: Exception) {
                val failureReason = when (error) {
                    is RouteSetupPushDispatchException -> requireNotNull(error.message)
                    else -> error.javaClass.simpleName
                }
                reminder.retryOrFail(
                    now = now,
                    reason = failureReason,
                    maxAttempts = maxAttempts.coerceAtLeast(1),
                    retryDelaySeconds = retryDelaySeconds.coerceAtLeast(1),
                )
                log.warn(
                    "Route setup reminder dispatch failed. memberId={}, scheduleId={}, markerId={}, errorCode={}",
                    memberId,
                    reminder.scheduleId,
                    reminder.id,
                    error.javaClass.simpleName,
                )
            }
        }
        return sentEvents
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

    private fun dispatchDeduplicationKey(
        memberId: Long,
        reminder: ScheduleRouteSetupReminder,
    ): String =
        "route-setup:$memberId:marker:${requireNotNull(reminder.id)}"
}

private class RouteSetupPushDispatchException(message: String) : RuntimeException(message)

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
