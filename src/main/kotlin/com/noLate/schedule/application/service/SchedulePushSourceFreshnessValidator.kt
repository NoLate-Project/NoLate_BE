package com.noLate.schedule.application.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.notification.application.service.FrozenPushSource
import com.noLate.notification.application.service.PushSourceFreshnessValidator
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_SCHEMA_VERSION
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DEFAULT_DEPARTURE_ALARM_TITLE
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.MAX_DEPARTURE_ALARM_GENERATION
import com.noLate.schedule.domain.MAX_DEPARTURE_ALARM_VALIDATION_REVISION
import com.noLate.schedule.domain.departureAlarmId
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 권한은 남아 있어도 목적이 사라진 schedule 보조 알림을 provider 직전에 차단한다.
 *
 * 호출자는 recipient member row를 이미 잠근다. 경로 계획 저장, 일정 의미 변경, 출발 완료도
 * 같은 member-first mutation fence를 사용하므로 mutation commit이 먼저면 아래 검증이 false,
 * provider lease가 먼저면 알림 발송이 먼저인 단일 순서로 수렴한다.
 */
@Service
class SchedulePushSourceFreshnessValidator(
    private val reminderRepository: ScheduleRouteSetupReminderRepository,
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val accessPolicy: ScheduleAccessPolicy,
    private val reminderPolicy: RouteSetupReminderPolicy,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val departureAlarmSyncStateRepository: DepartureAlarmSyncStateRepository,
) : PushSourceFreshnessValidator {

    override fun isFresh(source: FrozenPushSource): Boolean =
        when (source.payloadType) {
            ROUTE_SETUP_REMINDER -> routeSetupStillRequired(source)
            SCHEDULE_DEPARTURE_NUDGE -> targetHasNotDeparted(source)
            DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE -> departureAlarmCommandIsLatest(source)
            else -> true
        }

    private fun departureAlarmCommandIsLatest(source: FrozenPushSource): Boolean {
        val scheduleId = source.scheduleId ?: return false
        val data = canonicalData(source) ?: return false
        val stateId = data["alarmSyncStateId"]?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: return false
        val generation = data["alarmGeneration"]?.toLongOrNull()
            ?.takeIf { it in 0..MAX_DEPARTURE_ALARM_GENERATION }
            ?: return false
        val rawValidationRevision = data["alarmValidationRevision"]
        val validationRevision = if (rawValidationRevision == null) {
            // Rolling-deploy compatibility for frozen schema-v1 rows created before revision.
            0L
        } else {
            rawValidationRevision.toLongOrNull()
                ?.takeIf { it in 0..MAX_DEPARTURE_ALARM_VALIDATION_REVISION }
                ?: return false
        }
        val operation = data["alarmOperation"]
            ?.let { runCatching { DepartureAlarmSyncOperation.valueOf(it) }.getOrNull() }
            ?: return false
        val expectedDeduplicationKey = if (rawValidationRevision == null) {
            "departure-alarm-sync:$stateId:g$generation:${operation.name}"
        } else {
            "departure-alarm-sync:$stateId:g$generation:v$validationRevision:${operation.name}"
        }
        val state = departureAlarmSyncStateRepository.findById(stateId).orElse(null)
            ?: return false

        if (
            data["type"] != DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE ||
            data["alarmSchemaVersion"] != DEPARTURE_ALARM_SYNC_SCHEMA_VERSION ||
            data["logicalEventKey"] != source.logicalEventKey ||
            data["recipientMemberId"]?.toLongOrNull() != source.memberId ||
            data["scheduleId"]?.toLongOrNull() != scheduleId ||
            data["alarmId"] != departureAlarmId(source.memberId, scheduleId) ||
            data["alarmCommandFingerprint"] != state.commandFingerprint ||
            source.deduplicationKey != expectedDeduplicationKey ||
            state.id != stateId ||
            state.memberId != source.memberId ||
            state.scheduleId != scheduleId ||
            state.alarmId != data["alarmId"] ||
            state.generation != generation ||
            state.validationRevision != validationRevision ||
            state.operation != operation
        ) {
            return false
        }

        return when (operation) {
            DepartureAlarmSyncOperation.UPSERT ->
                data["alarmTriggerAt"] == state.triggerAt?.toString() &&
                    data["alarmTitle"] ==
                    (state.title?.takeIf(String::isNotBlank)
                        ?: DEFAULT_DEPARTURE_ALARM_TITLE) &&
                    data["snoozeMinutes"]?.toIntOrNull() == state.snoozeMinutes &&
                    when {
                        state.alarmPlanSchemaVersion == null && state.alarmOccurrencesJson == null ->
                            "alarmPlanSchemaVersion" !in data && "alarmOccurrencesJson" !in data

                        else ->
                            state.alarmPlanSchemaVersion == DEPARTURE_ALARM_PLAN_SCHEMA_VERSION &&
                                data["alarmPlanSchemaVersion"] == state.alarmPlanSchemaVersion &&
                                data["alarmOccurrencesJson"] == state.alarmOccurrencesJson
                    }

            DepartureAlarmSyncOperation.CANCEL ->
                state.triggerAt == null &&
                    state.title == null &&
                    state.snoozeMinutes == null &&
                    "alarmTriggerAt" !in data &&
                    "alarmTitle" !in data &&
                    "snoozeMinutes" !in data &&
                    data["alarmPlanSchemaVersion"] in
                        setOf(null, DEPARTURE_ALARM_PLAN_SCHEMA_VERSION) &&
                    "alarmOccurrencesJson" !in data
        }
    }

    private fun routeSetupStillRequired(source: FrozenPushSource): Boolean {
        val scheduleId = source.scheduleId ?: return false
        val markerId = routeSetupMarkerId(source) ?: return false
        val data = canonicalData(source) ?: return false
        if (
            data["logicalEventKey"] != source.logicalEventKey ||
            data["recipientMemberId"]?.toLongOrNull() != source.memberId ||
            data["scheduleId"]?.toLongOrNull() != scheduleId ||
            data["routeSetupReminderId"]?.toLongOrNull() != markerId
        ) {
            return false
        }
        val frozenFingerprint = data["routeSetupScheduleFingerprint"]
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val marker = reminderRepository.findById(markerId).orElse(null)
            ?.takeIf {
                it.status == ScheduleRouteSetupReminderStatus.SENT &&
                    it.memberId == source.memberId &&
                    it.scheduleId == scheduleId &&
                    it.scheduleFingerprint == frozenFingerprint
            }
            ?: return false
        /*
         * The marker id in the immutable deduplication key is the frozen source identity.
         * Its immutable scheduleFingerprint is deliberately used below rather than any value
         * recalculated while redriving the event.
         */
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)
            ?: return false
        if (ScheduleTravelPlanFingerprint.calculate(schedule) != marker.scheduleFingerprint) {
            return false
        }

        val access = accessPolicy.resolve(source.memberId, schedule)
        if (!access.travelEnabled || !accessPolicy.routeReminderEnabled(source.memberId, schedule)) {
            return false
        }
        val plan = travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(
            scheduleId,
            source.memberId,
        )
        val now = Instant.now(clock)
        return if (source.memberId == schedule.memberId) {
            reminderPolicy.requiresOwnerSetup(schedule, true, plan, now)
        } else {
            reminderPolicy.requiresSetup(schedule, true, plan, now)
        }
    }

    private fun routeSetupMarkerId(source: FrozenPushSource): Long? {
        val prefix = "route-setup:${source.memberId}:marker:"
        val raw = source.deduplicationKey
            ?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?: return null
        if (raw.isEmpty() || raw.any { !it.isDigit() }) return null
        return raw.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun targetHasNotDeparted(source: FrozenPushSource): Boolean {
        val identity = departureNudgeIdentity(source) ?: return false
        val data = canonicalData(source) ?: return false
        if (
            data["logicalEventKey"] != source.logicalEventKey ||
            data["recipientMemberId"]?.toLongOrNull() != source.memberId ||
            data["scheduleId"]?.toLongOrNull() != identity.scheduleId ||
            data["requestedByMemberId"]?.toLongOrNull() != identity.requesterMemberId
        ) {
            return false
        }
        val schedule = scheduleRepository.findActiveForTravelPlanUpdate(identity.scheduleId)
            ?: return false
        if (schedule.memberId != identity.requesterMemberId) return false
        if (!accessPolicy.resolve(source.memberId, schedule).travelEnabled) return false

        return departureStatusRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(identity.scheduleId, source.memberId)
            ?.departedAt == null
    }

    private fun departureNudgeIdentity(source: FrozenPushSource): DepartureNudgeIdentity? {
        val scheduleId = source.scheduleId ?: return null
        val raw = source.deduplicationKey ?: return null
        val match = DEPARTURE_NUDGE_KEY.matchEntire(raw) ?: return null
        val keyScheduleId = match.groupValues[1].toLongOrNull() ?: return null
        val requesterMemberId = match.groupValues[2].toLongOrNull() ?: return null
        val targetMemberId = match.groupValues[3].toLongOrNull() ?: return null
        if (
            keyScheduleId != scheduleId ||
            targetMemberId != source.memberId ||
            requesterMemberId <= 0 ||
            requesterMemberId == targetMemberId
        ) {
            return null
        }
        return DepartureNudgeIdentity(keyScheduleId, requesterMemberId)
    }

    private fun canonicalData(source: FrozenPushSource): Map<String, String>? =
        runCatching {
            objectMapper.readValue(
                source.canonicalDataJson,
                object : TypeReference<Map<String, String>>() {},
            )
        }.getOrNull()

    private companion object {
        const val ROUTE_SETUP_REMINDER = "ROUTE_SETUP_REMINDER"
        const val SCHEDULE_DEPARTURE_NUDGE = "SCHEDULE_DEPARTURE_NUDGE"
        val DEPARTURE_NUDGE_KEY =
            Regex("""^schedule-departure-nudge:(\d+):(\d+):(\d+):[A-Za-z0-9-]+$""")
    }
}

private data class DepartureNudgeIdentity(
    val scheduleId: Long,
    val requesterMemberId: Long,
)
