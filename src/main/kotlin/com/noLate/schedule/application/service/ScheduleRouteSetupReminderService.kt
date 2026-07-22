package com.noLate.schedule.application.service

import com.noLate.notification.application.service.AppNotificationService
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    private val appNotificationService: AppNotificationService,
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
     * due marker를 비관적 락으로 선점한 뒤 회원별 한 알림으로 묶는다. 발송 직전에 공유 회수,
     * 캘린더 알림 opt-out, 경로 저장, 일정 변경을 다시 확인해 오래된 알림을 취소한다.
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

        var sentGroups = 0
        valid.groupBy { it.first.memberId }.forEach { (memberId, entries) ->
            val reminders = entries.map { it.first }
            val schedules = entries.map { it.second }
            try {
                val title = "경로를 설정해주세요"
                val body = if (schedules.size == 1) {
                    "'${schedules.single().title}' 일정이 3일 안에 시작돼요. 내 출발 경로를 확인해주세요."
                } else {
                    "3일 안에 시작하는 일정 ${schedules.size}개의 내 출발 경로를 확인해주세요."
                }
                val scheduleIds = schedules.map { requireNotNull(it.id) }
                val deduplicationKey = dispatchDeduplicationKey(memberId, reminders)
                val payload = mapOf(
                    "type" to "ROUTE_SETUP_REMINDER",
                    "scheduleId" to scheduleIds.first().toString(),
                    "scheduleIds" to scheduleIds.joinToString(","),
                    "count" to scheduleIds.size.toString(),
                )

                // 논리 알림은 push 토큰과 무관하게 먼저 한 번 저장한다. marker 재시도에서는
                // 같은 dedupe key가 기존 row로 수렴하므로 앱 알림함에 중복이 쌓이지 않는다.
                val recordResult = appNotificationService.recordWithResult(
                    memberId = memberId,
                    title = title,
                    body = body,
                    data = payload,
                    deduplicationKey = deduplicationKey,
                )
                // 알림함 row는 생겼는데 marker가 아직 attempt 0이면, 직전 worker가 push까지
                // 보낸 뒤 DB commit 전에 종료된 경우로 본다. 이때는 at-most-once를 택해 중복
                // push를 막는다. provider 실패를 확인해 attempts가 증가한 marker만 다시 보낸다.
                if (recordResult.created || reminders.any { it.attempts > 0 }) {
                    val result = notificationUseCase.sendToMember(
                        memberId = memberId,
                        title = title,
                        body = body,
                        data = payload,
                        persistInInbox = false,
                    )
                    if (result.requestedCount > 0 && result.sentCount == 0) {
                        throw IllegalStateException(
                            "경로 미설정 push 발송 실패: requested=${result.requestedCount}, failed=${result.failedCount}"
                        )
                    }
                }
                reminders.forEach { it.markSent(now) }
                sentGroups += 1
            } catch (error: Exception) {
                reminders.forEach {
                    it.retryOrFail(
                        now = now,
                        reason = error.message ?: error.javaClass.simpleName,
                        maxAttempts = maxAttempts.coerceAtLeast(1),
                        retryDelaySeconds = retryDelaySeconds.coerceAtLeast(1),
                    )
                }
                log.warn("Route setup reminder dispatch failed. memberId={}", memberId, error)
            }
        }
        return sentGroups
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
        reminders: List<ScheduleRouteSetupReminder>,
    ): String {
        val markerIds = reminders.map { requireNotNull(it.id) }.sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(markerIds.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "route-setup:$memberId:$digest"
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
