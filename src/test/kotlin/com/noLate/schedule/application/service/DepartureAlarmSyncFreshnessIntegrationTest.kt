package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.service.FrozenPushSource
import com.noLate.notification.domain.withPushAccountBinding
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:departure-alarm-freshness;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ],
)
class DepartureAlarmSyncFreshnessIntegrationTest @Autowired constructor(
    private val stateRepository: DepartureAlarmSyncStateRepository,
    private val entityManager: EntityManager,
) {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `nanosecond trigger remains fresh after database microsecond round trip`() {
        val state = stateRepository.saveAndFlush(
            DepartureAlarmSyncState.createUpsert(
                memberId = 7L,
                scheduleId = 41L,
                triggerAt = Instant.parse("2026-07-29T03:30:00.123456789Z"),
                title = "회의",
                snoozeMinutes = 5,
            )
        )
        val command = DepartureAlarmSyncCommand(
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
        )
        val logicalEventKey = "key:alarm-freshness"
        val data = command.toOutboxData()
            .withPushAccountBinding(logicalEventKey, command.memberId)
        entityManager.flush()
        entityManager.clear()

        val reloaded = stateRepository.findById(command.stateId).orElseThrow()
        assertThat(reloaded.triggerAt)
            .isEqualTo(Instant.parse("2026-07-29T03:30:00.123456Z"))

        assertThat(validator().isFresh(source(command, logicalEventKey, data))).isTrue()
    }

    @Test
    fun `latest cancel tombstone stays fresh while delayed upsert is superseded without schedule lookup`() {
        val state = stateRepository.saveAndFlush(
            DepartureAlarmSyncState.createUpsert(
                memberId = 7L,
                scheduleId = 404L,
                triggerAt = Instant.parse("2026-07-29T03:30:00Z"),
                title = "삭제될 일정",
                snoozeMinutes = 5,
            )
        )
        val oldCommand = command(state)
        val oldData = oldCommand.toOutboxData()
            .withPushAccountBinding("key:old-upsert", oldCommand.memberId)

        assertThat(state.cancel()).isTrue()
        stateRepository.saveAndFlush(state)
        val cancelCommand = command(state)
        val cancelData = cancelCommand.toOutboxData()
            .withPushAccountBinding("key:latest-cancel", cancelCommand.memberId)
        entityManager.flush()
        entityManager.clear()

        val validator = validator()
        assertThat(
            validator.isFresh(source(oldCommand, "key:old-upsert", oldData))
        ).isFalse()
        assertThat(cancelCommand.operation).isEqualTo(DepartureAlarmSyncOperation.CANCEL)
        assertThat(
            validator.isFresh(source(cancelCommand, "key:latest-cancel", cancelData))
        ).isTrue()
    }

    private fun validator(): SchedulePushSourceFreshnessValidator =
        SchedulePushSourceFreshnessValidator(
            reminderRepository = mock<ScheduleRouteSetupReminderRepository>(),
            scheduleRepository = mock<ScheduleRepository>(),
            travelPlanRepository = mock<ScheduleTravelPlanRepository>(),
            departureStatusRepository = mock<ScheduleDepartureStatusRepository>(),
            accessPolicy = mock<ScheduleAccessPolicy>(),
            reminderPolicy = mock<RouteSetupReminderPolicy>(),
            objectMapper = mapper,
            clock = Clock.fixed(Instant.parse("2026-07-29T03:00:00Z"), ZoneOffset.UTC),
            departureAlarmSyncStateRepository = stateRepository,
        )

    private fun command(state: DepartureAlarmSyncState): DepartureAlarmSyncCommand =
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
        )

    private fun source(
        command: DepartureAlarmSyncCommand,
        logicalEventKey: String,
        data: Map<String, String>,
    ): FrozenPushSource =
        FrozenPushSource(
            memberId = command.memberId,
            logicalEventKey = logicalEventKey,
            deduplicationKey =
                "departure-alarm-sync:${command.stateId}:g${command.generation}:" +
                    command.operation.name,
            canonicalDataJson = mapper.writeValueAsString(data),
            payloadType = DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE,
            scheduleId = command.scheduleId,
            categoryId = null,
            calendarId = null,
        )
}
