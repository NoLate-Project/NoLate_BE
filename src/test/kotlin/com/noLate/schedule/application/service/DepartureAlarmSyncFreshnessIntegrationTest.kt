package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.service.FrozenPushSource
import com.noLate.notification.domain.withPushAccountBinding
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
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
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.noLate.notification.support.ensureActivePushMember

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
    private val jdbcTemplate: JdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `nanosecond trigger remains fresh after provider millisecond canonicalization and database round trip`() {
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
            validationRevision = state.validationRevision,
            alarmPlanSchemaVersion = state.alarmPlanSchemaVersion,
            alarmOccurrencesJson = state.alarmOccurrencesJson,
        )
        val logicalEventKey = "key:alarm-freshness"
        val data = command.toOutboxData()
            .withPushAccountBinding(logicalEventKey, command.memberId)
        entityManager.flush()
        entityManager.clear()

        val reloaded = stateRepository.findById(command.stateId).orElseThrow()
        assertThat(reloaded.triggerAt)
            .isEqualTo(Instant.parse("2026-07-29T03:30:00.123Z"))

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

    @Test
    fun `orphan candidates cannot starve an active alarm revalidation page`() {
        val now = Instant.parse("2026-08-04T03:00:00Z")
        val oldValidation = now.minusSeconds(13 * 60 * 60L)
        val departureAt = now.plusSeconds(2 * 24 * 60 * 60L)
        val orphanStates = (1L..100L).map { offset ->
            planState(
                memberId = 10_000L + offset,
                scheduleId = 20_000L + offset,
                departureAt = departureAt,
                validationRequestedAt = oldValidation,
            )
        }
        ensureActivePushMember(jdbcTemplate, 7L)
        val active = planState(
            memberId = 7L,
            scheduleId = 41L,
            departureAt = departureAt,
            validationRequestedAt = oldValidation,
        )
        stateRepository.saveAllAndFlush(orphanStates + active)

        val candidates = stateRepository.findValidationRefreshCandidateIds(
            operation = DepartureAlarmSyncOperation.UPSERT,
            planSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            now = now,
            cutoff = now.minusSeconds(12 * 60 * 60L),
            pageable = PageRequest.of(0, 100),
        )

        assertThat(candidates).containsExactly(requireNotNull(active.id))
    }

    @Test
    fun `frozen legacy revision zero command is compatible until a newer validation revision exists`() {
        val now = Instant.parse("2026-08-04T03:00:00Z")
        val state = stateRepository.saveAndFlush(
            planState(
                memberId = 7L,
                scheduleId = 41L,
                departureAt = now.plusSeconds(2 * 24 * 60 * 60L),
                validationRequestedAt = now,
            )
        )
        val command = command(state)
        val logicalEventKey = "key:legacy-revision-zero"
        val legacyData = command.toOutboxData()
            .minus("alarmValidationRevision")
            .withPushAccountBinding(logicalEventKey, command.memberId)
        val legacySource = source(
            command = command,
            logicalEventKey = logicalEventKey,
            data = legacyData,
            legacyRevision = true,
        )

        assertThat(validator().isFresh(legacySource)).isTrue()

        state.reissueValidation(now.plusSeconds(1))
        stateRepository.saveAndFlush(state)

        assertThat(validator().isFresh(legacySource)).isFalse()
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
            validationRevision = state.validationRevision,
            alarmPlanSchemaVersion = state.alarmPlanSchemaVersion,
            alarmOccurrencesJson = state.alarmOccurrencesJson,
        )

    private fun planState(
        memberId: Long,
        scheduleId: Long,
        departureAt: Instant,
        validationRequestedAt: Instant,
    ): DepartureAlarmSyncState {
        val plan = DepartureAlarmPlanFactory().create(
            memberId = memberId,
            scheduleId = scheduleId,
            recommendedDepartureAt = departureAt,
            scheduleTitle = "회의",
        )
        return DepartureAlarmSyncState.createUpsert(
            memberId = memberId,
            scheduleId = scheduleId,
            triggerAt = plan.departureOccurrence().triggerInstant(),
            title = plan.departureOccurrence().title,
            snoozeMinutes = 5,
            alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
            validationRequestedAt = validationRequestedAt,
        )
    }

    private fun source(
        command: DepartureAlarmSyncCommand,
        logicalEventKey: String,
        data: Map<String, String>,
        legacyRevision: Boolean = false,
    ): FrozenPushSource =
        FrozenPushSource(
            memberId = command.memberId,
            logicalEventKey = logicalEventKey,
            deduplicationKey = if (legacyRevision) {
                "departure-alarm-sync:${command.stateId}:g${command.generation}:" +
                    command.operation.name
            } else {
                "departure-alarm-sync:${command.stateId}:g${command.generation}:" +
                    "v${command.validationRevision}:${command.operation.name}"
            },
            canonicalDataJson = mapper.writeValueAsString(data),
            payloadType = DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE,
            scheduleId = command.scheduleId,
            categoryId = null,
            calendarId = null,
        )
}
