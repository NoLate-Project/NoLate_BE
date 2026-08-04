package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.service.findActiveNotificationRecipientForUpdate
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.domain.withPushAccountBinding
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_SCHEMA_VERSION
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DEFAULT_DEPARTURE_ALARM_TITLE
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.domain.PageRequest
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 클라이언트가 적용할 출발 알람 한 건의 완전한 desired-state 명령이다.
 *
 * FCM data와 snapshot API는 숫자/시각까지 모두 문자열로 공유한다. 앱은 alarmId별 가장 큰
 * generation만 적용하므로 지연된 UPSERT가 최신 CANCEL tombstone을 되돌릴 수 없다.
 */
data class DepartureAlarmSyncCommand(
    val stateId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val alarmId: String,
    val generation: Long,
    val operation: DepartureAlarmSyncOperation,
    val triggerAt: Instant?,
    val title: String?,
    val snoozeMinutes: Int?,
    val fingerprint: String,
    val validationRevision: Long = 0,
    val alarmPlanSchemaVersion: String? = null,
    val alarmOccurrencesJson: String? = null,
) {
    fun toClientData(): Map<String, String> {
        val common = linkedMapOf(
            "type" to DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE,
            "alarmSchemaVersion" to DEPARTURE_ALARM_SYNC_SCHEMA_VERSION,
            "recipientMemberId" to memberId.toString(),
            "alarmOperation" to operation.name,
            "alarmId" to alarmId,
            "scheduleId" to scheduleId.toString(),
            "alarmGeneration" to generation.toString(),
            "alarmValidationRevision" to validationRevision.toString(),
        )
        if (operation == DepartureAlarmSyncOperation.CANCEL) {
            common["alarmPlanSchemaVersion"] = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
        }
        if (operation == DepartureAlarmSyncOperation.UPSERT) {
            common["alarmTriggerAt"] = requireNotNull(triggerAt).toString()
            common["alarmTitle"] = title?.takeIf(String::isNotBlank)
                ?: DEFAULT_DEPARTURE_ALARM_TITLE
            common["snoozeMinutes"] = requireNotNull(snoozeMinutes).toString()
            if (alarmPlanSchemaVersion != null || alarmOccurrencesJson != null) {
                check(alarmPlanSchemaVersion == DEPARTURE_ALARM_PLAN_SCHEMA_VERSION)
                common["alarmPlanSchemaVersion"] = alarmPlanSchemaVersion
                common["alarmOccurrencesJson"] = requireNotNull(alarmOccurrencesJson)
            }
        }
        val providerBoundData = (common + mapOf(
            "alarmSyncStateId" to stateId.toString(),
            "alarmCommandFingerprint" to fingerprint,
        )).withPushAccountBinding(
            logicalEventKey = MAX_LENGTH_DETERMINISTIC_LOGICAL_EVENT_KEY,
            recipientMemberId = memberId,
        )
        require(
            DEPARTURE_ALARM_PAYLOAD_MAPPER.writeValueAsBytes(
                mapOf("data" to providerBoundData)
            ).size <= MAX_DEPARTURE_ALARM_PROVIDER_JSON_BYTES
        ) { "출발 알람 client data가 FCM 안전 크기를 초과했습니다." }
        return common
    }

    fun toOutboxData(): Map<String, String> =
        toClientData() + mapOf(
            "alarmSyncStateId" to stateId.toString(),
            "alarmCommandFingerprint" to fingerprint,
        )
}

data class DepartureAlarmSyncStateChangedEvent(
    val command: DepartureAlarmSyncCommand,
)

@Service
class DepartureAlarmSyncService(
    private val repository: DepartureAlarmSyncStateRepository,
    private val memberRepository: MemberRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${schedule.push.departure-snooze-minutes:5}")
    private val snoozeMinutes: Int = 5,
    private val alarmPlanFactory: DepartureAlarmPlanFactory = DepartureAlarmPlanFactory(),
    @Value("\${schedule.push.departure-alarm-revalidation-hours:12}")
    private val revalidationHours: Long = 12,
    @Value("\${schedule.push.departure-alarm-coverage-receipt-ttl-hours:24}")
    private val coverageReceiptTtlHours: Long = 24,
    @Value("\${schedule.push.departure-alarm-revalidation-min-lead-minutes:30}")
    private val revalidationMinLeadMinutes: Long = 30,
) {
    init {
        require(revalidationHours >= 1 && revalidationHours * 2 <= coverageReceiptTtlHours) {
            "Departure alarm revalidation must run at least twice within the coverage receipt TTL."
        }
        require(revalidationMinLeadMinutes >= 15)
    }
    /**
     * 명시적으로 저장된 알림 설정을 기기 알람 desired state로 투영한다.
     *
     * 이미 지난 시각은 즉시 울릴 UPSERT로 바꾸지 않는다. 과거의 미래 알람이 남아 있으면
     * CANCEL을 발행하고, 처음부터 알람이 없었다면 tombstone을 불필요하게 만들지 않는다.
     */
    @Transactional
    fun synchronizeConfigured(
        memberId: Long,
        scheduleId: Long,
        notificationEnabled: Boolean,
        alertMode: ScheduleAlertMode,
        triggerAt: Instant?,
        scheduleTitle: String?,
    ): DepartureAlarmSyncCommand? {
        if (!lockActiveMember(memberId)) return null
        val now = Instant.now(clock)
        if (
            notificationEnabled &&
            alertMode == ScheduleAlertMode.ALARM &&
            triggerAt != null &&
            triggerAt.isAfter(now)
        ) {
            return upsert(memberId, scheduleId, triggerAt, scheduleTitle)
        }
        return cancelLocked(memberId, scheduleId)
    }

    /**
     * 자동 ETA는 미래 알람만 갱신한다. 이미 울렸을 수 있는 세대는 로컬 snooze/dismiss를
     * 덮어쓰지 않고 grace cleanup에 맡기되, 아직 미래인 잘못된 알람은 즉시 취소한다.
     *
     * CANCEL tombstone은 기본적으로 되살리지 않는다. 예외 플래그는 worker가 직전 persisted
     * ETA 상태가 환승 실패였고 현재 정상으로 회복됐음을 확인한 경우에만 전달한다. 따라서
     * 사용자 설정 비활성화·terminal 취소와 로컬 dismiss는 자동 ETA가 임의로 재개하지 않는다.
     */
    @Transactional
    fun synchronizeAutomaticEta(
        memberId: Long,
        scheduleId: Long,
        notificationEnabled: Boolean,
        alertMode: ScheduleAlertMode,
        recommendedDepartureAt: Instant,
        scheduleTitle: String?,
        resumeCanceledAfterTransitTransferFailure: Boolean = false,
    ): DepartureAlarmSyncCommand? {
        if (!lockActiveMember(memberId)) return null
        if (!notificationEnabled || alertMode != ScheduleAlertMode.ALARM) {
            return cancelLocked(memberId, scheduleId)
        }

        val now = Instant.now(clock)
        val existing = repository.findByMemberIdAndScheduleIdForUpdate(memberId, scheduleId)
        if (recommendedDepartureAt.isAfter(now)) {
            if (existing == null) {
                return upsert(
                    memberId = memberId,
                    scheduleId = scheduleId,
                    triggerAt = recommendedDepartureAt,
                    scheduleTitle = scheduleTitle,
                )
            }
            // Once a generation has reached its trigger, an automatic ETA refresh cannot know
            // whether the user snoozed or dismissed it locally. Only an explicit user/configuration
            // mutation may open a newer generation from that point.
            if (existing.operation != DepartureAlarmSyncOperation.UPSERT) {
                return if (
                    existing.operation == DepartureAlarmSyncOperation.CANCEL &&
                    resumeCanceledAfterTransitTransferFailure
                ) {
                    transition(existing) {
                        applyPlanUpsert(it, recommendedDepartureAt, scheduleTitle)
                    }
                } else {
                    null
                }
            }
            if (existing.triggerAt?.isAfter(now) != true) {
                return null
            }
            return transition(existing) {
                applyPlanUpsert(it, recommendedDepartureAt, scheduleTitle)
            }
        }

        if (existing == null) return null
        return if (
            existing.operation == DepartureAlarmSyncOperation.UPSERT &&
            existing.triggerAt?.isAfter(now) == true
        ) {
            transition(existing) { it.cancel() }
        } else {
            null
        }
    }

    /** 서버의 명시적 snooze 액션은 새 세대로 다시 예약한다. */
    @Transactional
    fun snooze(
        memberId: Long,
        scheduleId: Long,
        snoozedUntil: Instant?,
        scheduleTitle: String?,
    ): DepartureAlarmSyncCommand? {
        if (!lockActiveMember(memberId)) return null
        if (snoozedUntil == null || !snoozedUntil.isAfter(Instant.now(clock))) {
            return null
        }
        val existing = repository.findByMemberIdAndScheduleIdForUpdate(memberId, scheduleId)
            ?: return null
        if (existing.operation != DepartureAlarmSyncOperation.UPSERT) return null
        return transition(existing) {
            it.upsert(
                triggerAt = snoozedUntil,
                title = scheduleTitle ?: it.title,
                snoozeMinutes = snoozeMinutes,
            )
        }
    }

    /** 출발·삭제·회수·비활성화 같은 명시적 terminal 전이는 항상 tombstone으로 수렴한다. */
    @Transactional
    fun cancel(memberId: Long, scheduleId: Long): DepartureAlarmSyncCommand? {
        if (!lockActiveMember(memberId)) return null
        return cancelLocked(memberId, scheduleId)
    }

    private fun cancelLocked(memberId: Long, scheduleId: Long): DepartureAlarmSyncCommand? {
        val existing = repository.findByMemberIdAndScheduleIdForUpdate(memberId, scheduleId)
            ?: return null
        return transition(existing) { it.cancel() }
    }

    @Transactional
    fun cancelAllForSchedule(scheduleId: Long): List<DepartureAlarmSyncCommand> {
        val previewMemberIds = findMemberIdsForSchedule(scheduleId)
        if (previewMemberIds.isEmpty()) return emptyList()
        val activeMemberIds = memberRepository.findAllByIdsForUpdate(previewMemberIds)
            .filterNot { it.deleted }
            .mapNotNull { it.id }
            .toSet()
        val currentStates = repository.findAllByScheduleIdOrderByMemberIdAsc(scheduleId)
        if (currentStates.any { it.memberId !in previewMemberIds }) {
            throw ConcurrencyFailureException(
                "Departure alarm recipients changed while the schedule cancel fence was acquired.",
            )
        }
        return currentStates
            .filter { it.memberId in activeMemberIds }
            .mapNotNull { state ->
                val locked = repository.findByMemberIdAndScheduleIdForUpdate(
                    state.memberId,
                    state.scheduleId,
                ) ?: return@mapNotNull null
                transition(locked) { it.cancel() }
            }
    }

    @Transactional
    fun cancelForMembers(
        scheduleId: Long,
        memberIds: Collection<Long>,
    ): List<DepartureAlarmSyncCommand> {
        val normalized = memberIds.distinct().sorted()
        if (normalized.isEmpty()) return emptyList()
        val activeMemberIds = memberRepository.findAllByIdsForUpdate(normalized)
            .filterNot { it.deleted }
            .mapNotNull { it.id }
            .toSet()
        return normalized.filter(activeMemberIds::contains).mapNotNull { memberId ->
            cancelLocked(memberId, scheduleId)
        }
    }

    @Transactional(readOnly = true)
    fun snapshot(memberId: Long): List<DepartureAlarmSyncCommand> =
        repository.findAllByMemberIdOrderByScheduleIdAsc(memberId).map(::toCommand)

    @Transactional(readOnly = true)
    fun findMemberIdsForSchedule(scheduleId: Long): List<Long> =
        repository.findAllByScheduleIdOrderByMemberIdAsc(scheduleId)
            .map(DepartureAlarmSyncState::memberId)
            .distinct()
            .sorted()

    @Transactional(readOnly = true)
    fun findState(stateId: Long): DepartureAlarmSyncState? =
        repository.findById(stateId).orElse(null)

    private fun lockActiveMember(memberId: Long): Boolean =
        memberRepository.findActiveNotificationRecipientForUpdate(memberId) != null

    private fun upsert(
        memberId: Long,
        scheduleId: Long,
        triggerAt: Instant,
        scheduleTitle: String?,
    ): DepartureAlarmSyncCommand? {
        val existing = repository.findByMemberIdAndScheduleIdForUpdate(memberId, scheduleId)
        if (existing != null) {
            return transition(existing) {
                applyPlanUpsert(it, triggerAt, scheduleTitle)
            }
        }

        val now = Instant.now(clock)
        val plan = alarmPlanFactory.create(memberId, scheduleId, triggerAt, scheduleTitle)
        val occurrencesJson = DepartureAlarmPlanCodec.encode(plan)
        val departureOccurrence = plan.departureOccurrence()

        val created = repository.saveAndFlush(
            DepartureAlarmSyncState.createUpsert(
                memberId = memberId,
                scheduleId = scheduleId,
                triggerAt = departureOccurrence.triggerInstant(),
                title = departureOccurrence.title,
                snoozeMinutes = snoozeMinutes,
                alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
                alarmOccurrencesJson = occurrencesJson,
                validationRequestedAt = now,
            )
        )
        return publish(created)
    }

    private fun applyPlanUpsert(
        state: DepartureAlarmSyncState,
        triggerAt: Instant,
        scheduleTitle: String?,
    ): Boolean {
        val plan = alarmPlanFactory.create(
            memberId = state.memberId,
            scheduleId = state.scheduleId,
            recommendedDepartureAt = triggerAt,
            scheduleTitle = scheduleTitle,
        )
        val changed = state.upsert(
            triggerAt = plan.departureOccurrence().triggerInstant(),
            title = plan.departureOccurrence().title,
            snoozeMinutes = snoozeMinutes,
            alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
        )
        val now = Instant.now(clock)
        if (changed) {
            state.recordValidationRequested(now)
            return true
        }
        val refreshCutoff = now.minus(revalidationHours, ChronoUnit.HOURS)
        return if (
            state.validationRequestedAt?.isAfter(refreshCutoff) != true &&
            hasRevalidationDeliveryLead(state, now, revalidationMinLeadMinutes)
        ) {
            state.reissueValidation(now)
        } else {
            false
        }
    }

    private fun transition(
        state: DepartureAlarmSyncState,
        mutation: (DepartureAlarmSyncState) -> Boolean,
    ): DepartureAlarmSyncCommand? {
        if (!mutation(state)) return null
        repository.saveAndFlush(state)
        return publish(state)
    }

    private fun publish(state: DepartureAlarmSyncState): DepartureAlarmSyncCommand {
        val command = toCommand(state)
        eventPublisher.publishEvent(DepartureAlarmSyncStateChangedEvent(command))
        return command
    }

    private fun toCommand(state: DepartureAlarmSyncState): DepartureAlarmSyncCommand =
        DepartureAlarmSyncCommand(
            stateId = requireNotNull(state.id),
            memberId = state.memberId,
            scheduleId = state.scheduleId,
            alarmId = state.alarmId,
            generation = state.generation,
            operation = state.operation,
            triggerAt = state.triggerAt,
            title = state.title,
            snoozeMinutes = state.snoozeMinutes,
            fingerprint = state.commandFingerprint,
            validationRevision = state.validationRevision,
            alarmPlanSchemaVersion = state.alarmPlanSchemaVersion,
            alarmOccurrencesJson = state.alarmOccurrencesJson,
        )
}

@Component
class DepartureAlarmSyncOutboxListener(
    private val pushEventOutboxService: PushEventOutboxService,
) {
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onStateChanged(event: DepartureAlarmSyncStateChangedEvent) {
        val command = event.command
        pushEventOutboxService.enqueueControl(
            memberId = command.memberId,
            title = "출발 알람 동기화",
            body = command.operation.name,
            data = command.toOutboxData(),
            deduplicationKey =
                "departure-alarm-sync:${command.stateId}:g${command.generation}:" +
                    "v${command.validationRevision}:${command.operation.name}",
        )
    }
}

/**
 * 알람이 울릴 기회를 보장한 뒤에만 오래된 UPSERT를 CANCEL tombstone으로 닫는다.
 */
@Component
class DepartureAlarmSyncExpiryScheduler(
    private val repository: DepartureAlarmSyncStateRepository,
    private val writer: DepartureAlarmSyncExpiryWriter,
    @Value("\${schedule.push.departure-alarm-expiry-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${schedule.push.departure-alarm-expiry-batch-size:100}")
    private val batchSize: Int = 100,
    @Value("\${schedule.push.departure-alarm-expiry-grace-minutes:10}")
    private val graceMinutes: Long = 10,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${schedule.push.departure-alarm-expiry-scan-ms:60000}")
    fun cleanupExpired() {
        if (!enabled) return

        val cutoff = Instant.now(clock).minus(graceMinutes.coerceAtLeast(1), ChronoUnit.MINUTES)
        repository.findExpiredUpsertIds(
            DepartureAlarmSyncOperation.UPSERT,
            cutoff,
            PageRequest.of(0, batchSize.coerceIn(1, 500)),
        ).forEach { writer.cancelExpired(it, cutoff) }
    }
}

@Service
class DepartureAlarmSyncExpiryWriter(
    private val repository: DepartureAlarmSyncStateRepository,
    private val memberRepository: MemberRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cancelExpired(stateId: Long, cutoff: Instant) {
        val candidate = repository.findById(stateId).orElse(null) ?: return
        if (memberRepository.findByIdForUpdate(candidate.memberId)?.deleted != false) return
        val state = repository.findByIdForUpdate(stateId) ?: return
        if (
            state.operation != DepartureAlarmSyncOperation.UPSERT ||
            state.triggerAt?.isAfter(cutoff) != false ||
            !state.cancel()
        ) {
            return
        }
        repository.saveAndFlush(state)
        eventPublisher.publishEvent(
            DepartureAlarmSyncStateChangedEvent(
                DepartureAlarmSyncCommand(
                    stateId = requireNotNull(state.id),
                    memberId = state.memberId,
                    scheduleId = state.scheduleId,
                    alarmId = state.alarmId,
                    generation = state.generation,
                    operation = state.operation,
                    triggerAt = state.triggerAt,
                    title = state.title,
                    snoozeMinutes = state.snoozeMinutes,
                    fingerprint = state.commandFingerprint,
                    validationRevision = state.validationRevision,
                    alarmPlanSchemaVersion = state.alarmPlanSchemaVersion,
                    alarmOccurrencesJson = state.alarmOccurrencesJson,
                )
            )
        )
    }
}

/** Periodically asks clients to re-validate long-lived native alarms before coverage evidence ages. */
@Component
class DepartureAlarmSyncRevalidationScheduler(
    private val repository: DepartureAlarmSyncStateRepository,
    private val writer: DepartureAlarmSyncRevalidationWriter,
    @Value("\${schedule.push.departure-alarm-revalidation-enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${schedule.push.departure-alarm-revalidation-hours:12}")
    private val revalidationHours: Long = 12,
    @Value("\${schedule.push.departure-alarm-revalidation-batch-size:100}")
    private val batchSize: Int = 100,
    private val clock: Clock,
) {
    init {
        require(revalidationHours >= 1)
    }

    @Scheduled(
        fixedDelayString =
            "\${schedule.push.departure-alarm-revalidation-scan-ms:300000}"
    )
    fun refreshDuePlans() {
        if (!enabled) return
        val now = Instant.now(clock)
        val cutoff = now.minus(revalidationHours, ChronoUnit.HOURS)
        repository.findValidationRefreshCandidateIds(
            operation = DepartureAlarmSyncOperation.UPSERT,
            planSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            now = now,
            cutoff = cutoff,
            pageable = PageRequest.of(0, batchSize.coerceIn(1, 500)),
        ).forEach { stateId -> writer.revalidate(stateId, now, cutoff) }
    }
}

@Service
class DepartureAlarmSyncRevalidationWriter(
    private val repository: DepartureAlarmSyncStateRepository,
    private val memberRepository: MemberRepository,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${schedule.push.departure-alarm-revalidation-min-lead-minutes:30}")
    private val revalidationMinLeadMinutes: Long = 30,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revalidate(stateId: Long, now: Instant, cutoff: Instant) {
        val candidate = repository.findById(stateId).orElse(null) ?: return
        if (memberRepository.findActiveNotificationRecipientForUpdate(candidate.memberId) == null) {
            return
        }
        val state = repository.findByIdForUpdate(stateId) ?: return
        if (
            state.operation != DepartureAlarmSyncOperation.UPSERT ||
            state.alarmPlanSchemaVersion != DEPARTURE_ALARM_PLAN_SCHEMA_VERSION ||
            state.alarmOccurrencesJson == null ||
            state.triggerAt?.isAfter(now) != true ||
            state.validationRequestedAt?.isAfter(cutoff) == true ||
            !hasRevalidationDeliveryLead(state, now, revalidationMinLeadMinutes)
        ) {
            return
        }
        state.reissueValidation(now)
        repository.saveAndFlush(state)
        eventPublisher.publishEvent(
            DepartureAlarmSyncStateChangedEvent(
                DepartureAlarmSyncCommand(
                    stateId = requireNotNull(state.id),
                    memberId = state.memberId,
                    scheduleId = state.scheduleId,
                    alarmId = state.alarmId,
                    generation = state.generation,
                    operation = state.operation,
                    triggerAt = state.triggerAt,
                    title = state.title,
                    snoozeMinutes = state.snoozeMinutes,
                    fingerprint = state.commandFingerprint,
                    validationRevision = state.validationRevision,
                    alarmPlanSchemaVersion = state.alarmPlanSchemaVersion,
                    alarmOccurrencesJson = state.alarmOccurrencesJson,
                )
            )
        )
    }
}

private fun hasRevalidationDeliveryLead(
    state: DepartureAlarmSyncState,
    now: Instant,
    minimumLeadMinutes: Long,
): Boolean {
    val encodedPlan = state.alarmOccurrencesJson ?: return false
    val nextOccurrenceAt = runCatching {
        DepartureAlarmPlanCodec.decode(encodedPlan).occurrences
            .map { it.triggerInstant() }
            .filter { it.isAfter(now) }
            .minOrNull()
    }.getOrNull() ?: return false
    return nextOccurrenceAt.isAfter(now.plus(minimumLeadMinutes, ChronoUnit.MINUTES))
}

private const val MAX_DEPARTURE_ALARM_PROVIDER_JSON_BYTES = 3_200
private const val MAX_LENGTH_DETERMINISTIC_LOGICAL_EVENT_KEY =
    "key:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
private val DEPARTURE_ALARM_PAYLOAD_MAPPER = jacksonObjectMapper()
