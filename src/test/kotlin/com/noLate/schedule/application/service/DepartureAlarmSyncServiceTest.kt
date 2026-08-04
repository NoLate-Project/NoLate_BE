package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.notification.domain.withPushAccountBinding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class DepartureAlarmSyncServiceTest {
    @Mock
    lateinit var repository: DepartureAlarmSyncStateRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private val now = Instant.parse("2026-07-29T03:00:00Z")
    private lateinit var service: DepartureAlarmSyncService

    @BeforeEach
    fun setUp() {
        service = DepartureAlarmSyncService(
            repository = repository,
            memberRepository = memberRepository,
            eventPublisher = eventPublisher,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            snoozeMinutes = 5,
        )
        lenient().whenever(memberRepository.findByIdForUpdate(7L)).thenReturn(Member(id = 7L))
        lenient().whenever(repository.saveAndFlush(any<DepartureAlarmSyncState>()))
            .thenAnswer { it.getArgument(0) }
    }

    @Test
    fun `past configured departure never creates an immediate alarm`() {
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(null)

        val result = service.synchronizeConfigured(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            triggerAt = now.minusSeconds(1),
            scheduleTitle = "회의",
        )

        assertThat(result).isNull()
        verify(repository, never()).saveAndFlush(any<DepartureAlarmSyncState>())
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `past automatic ETA cancels a wrongly pending future alarm`() {
        val state = state(triggerAt = now.plusSeconds(600))
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        val result = service.synchronizeAutomaticEta(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = now.minusSeconds(1),
            scheduleTitle = "회의",
        )

        assertThat(result?.operation).isEqualTo(DepartureAlarmSyncOperation.CANCEL)
        assertThat(result?.generation).isEqualTo(1L)
        assertThat(result?.validationRevision).isZero()
        assertThat(state.generation).isEqualTo(1L)
        assertThat(state.validationRevision).isZero()
        assertThat(state.triggerAt).isNull()
    }

    @Test
    fun `automatic ETA after trigger does not overwrite local snooze or dismiss`() {
        val state = state(triggerAt = now.minusSeconds(1))
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        val result = service.synchronizeAutomaticEta(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = now.plusSeconds(600),
            scheduleTitle = "새 ETA",
        )

        assertThat(result).isNull()
        assertThat(state.generation).isZero()
        assertThat(state.validationRevision).isZero()
        assertThat(state.triggerAt).isEqualTo(now.minusSeconds(1))
        verify(repository, never()).saveAndFlush(any<DepartureAlarmSyncState>())
    }

    @Test
    fun `환승 실패는 미래 알람을 취소하고 반복은 멱등이며 정상 회복만 새 세대로 재예약한다`() {
        val state = state(triggerAt = now.plusSeconds(600))
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        val canceled = service.synchronizeAutomaticEta(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = false,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = now.plusSeconds(600),
            scheduleTitle = "회의",
        )
        val repeated = service.synchronizeAutomaticEta(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = false,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = now.plusSeconds(600),
            scheduleTitle = "회의",
        )
        val recovered = service.synchronizeAutomaticEta(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = now.plusSeconds(900),
            scheduleTitle = "회복 ETA",
            resumeCanceledAfterTransitTransferFailure = true,
        )

        assertThat(canceled?.operation).isEqualTo(DepartureAlarmSyncOperation.CANCEL)
        assertThat(canceled?.generation).isEqualTo(1L)
        assertThat(repeated).isNull()
        assertThat(recovered?.operation).isEqualTo(DepartureAlarmSyncOperation.UPSERT)
        assertThat(recovered?.generation).isEqualTo(2L)
        assertThat(recovered?.triggerAt).isEqualTo(now.plusSeconds(900))
    }

    @Test
    fun `일반 CANCEL은 환승 회복 플래그 없이는 자동 ETA가 되살리지 않는다`() {
        val state = state(triggerAt = now.plusSeconds(600)).also { it.cancel() }
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        val result = service.synchronizeAutomaticEta(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            recommendedDepartureAt = now.plusSeconds(900),
            scheduleTitle = "회의",
        )

        assertThat(result).isNull()
        assertThat(state.operation).isEqualTo(DepartureAlarmSyncOperation.CANCEL)
        assertThat(state.generation).isEqualTo(1L)
    }

    @Test
    fun `mutation lock order is member before alarm state`() {
        val state = state(triggerAt = now.plusSeconds(600))
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        service.cancel(7L, 41L)

        inOrder(memberRepository, repository) {
            verify(memberRepository).findByIdForUpdate(7L)
            verify(repository).findByMemberIdAndScheduleIdForUpdate(7L, 41L)
        }
    }

    @Test
    fun `stale validation for a distant unchanged plan is reissued as a new generation`() {
        val departureAt = now.plusSeconds(2 * 24 * 60 * 60L)
        val state = planState(
            departureAt = departureAt,
            validationRequestedAt = now.minusSeconds(13 * 60 * 60L),
        )
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        val result = service.synchronizeConfigured(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            triggerAt = departureAt,
            scheduleTitle = "회의",
        )

        assertThat(result?.generation).isZero()
        assertThat(result?.validationRevision).isEqualTo(1L)
        assertThat(state.generation).isZero()
        assertThat(state.validationRevision).isEqualTo(1L)
        assertThat(state.validationRequestedAt).isEqualTo(now)
        verify(repository).saveAndFlush(state)
        verify(eventPublisher).publishEvent(any<DepartureAlarmSyncStateChangedEvent>())
    }

    @Test
    fun `stale validation is not reissued when the next occurrence is inside delivery safety lead`() {
        val departureAt = now.plusSeconds(20 * 60L)
        val previousValidation = now.minusSeconds(13 * 60 * 60L)
        val state = planState(
            departureAt = departureAt,
            validationRequestedAt = previousValidation,
        )
        whenever(repository.findByMemberIdAndScheduleIdForUpdate(7L, 41L)).thenReturn(state)

        val result = service.synchronizeConfigured(
            memberId = 7L,
            scheduleId = 41L,
            notificationEnabled = true,
            alertMode = ScheduleAlertMode.ALARM,
            triggerAt = departureAt,
            scheduleTitle = "회의",
        )

        assertThat(result).isNull()
        assertThat(state.generation).isZero()
        assertThat(state.validationRequestedAt).isEqualTo(previousValidation)
        verify(repository, never()).saveAndFlush(state)
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `client command serializes every identifier time and generation as strings`() {
        val state = state(triggerAt = now.plusSeconds(600))
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

        assertThat(command.toClientData()).containsAllEntriesOf(
            mapOf(
                "type" to "DEPARTURE_ALARM_SYNC",
                "alarmSchemaVersion" to "1",
                "recipientMemberId" to "7",
                "alarmOperation" to "UPSERT",
                "alarmId" to "schedule:41:member:7",
                "scheduleId" to "41",
                "alarmGeneration" to "0",
                "alarmValidationRevision" to "0",
                "alarmTriggerAt" to "2026-07-29T03:10:00Z",
                "alarmTitle" to "회의",
                "snoozeMinutes" to "5",
            )
        )
    }

    @Test
    fun `maximum Korean schedule title keeps the exact provider-bound plan below safety budget`() {
        val memberId = Long.MAX_VALUE
        val scheduleId = Long.MAX_VALUE
        val plan = DepartureAlarmPlanFactory().create(
            memberId = memberId,
            scheduleId = scheduleId,
            recommendedDepartureAt = Instant.parse("2099-12-31T23:59:59.999999999Z"),
            scheduleTitle = "가".repeat(100),
        )
        val command = DepartureAlarmSyncCommand(
            stateId = Long.MAX_VALUE,
            memberId = memberId,
            scheduleId = scheduleId,
            alarmId = "schedule:$scheduleId:member:$memberId",
            generation = 9_007_199_254_740_991L,
            operation = DepartureAlarmSyncOperation.UPSERT,
            triggerAt = plan.departureOccurrence().triggerInstant(),
            title = plan.departureOccurrence().title,
            snoozeMinutes = 60,
            fingerprint = "f".repeat(64),
            validationRevision = 9_007_199_254_740_991L,
            alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
        )
        val providerData = command.toOutboxData().withPushAccountBinding(
            logicalEventKey = "key:" + "f".repeat(64),
            recipientMemberId = memberId,
        )

        assertThat(
            jacksonObjectMapper().writeValueAsBytes(mapOf("data" to providerData)).size
        ).isLessThanOrEqualTo(3_200)
        assertThat(plan.occurrences).hasSize(4)
    }

    @Test
    fun `snapshot returns the current member desired state without taking mutation locks`() {
        val state = state(triggerAt = now.plusSeconds(600))
        whenever(repository.findAllByMemberIdOrderByScheduleIdAsc(7L))
            .thenReturn(listOf(state))

        val result = service.snapshot(7L)

        assertThat(result).hasSize(1)
        val command = result.single()
        assertThat(command.stateId).isEqualTo(99L)
        assertThat(command.memberId).isEqualTo(7L)
        assertThat(command.scheduleId).isEqualTo(41L)
        assertThat(command.operation).isEqualTo(DepartureAlarmSyncOperation.UPSERT)
        assertThat(command.toClientData()["alarmGeneration"]).isEqualTo("0")
        verify(repository).findAllByMemberIdOrderByScheduleIdAsc(7L)
        verify(memberRepository, never()).findByIdForUpdate(any())
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    private fun state(triggerAt: Instant): DepartureAlarmSyncState =
        DepartureAlarmSyncState.createUpsert(
            memberId = 7L,
            scheduleId = 41L,
            triggerAt = triggerAt,
            title = "회의",
            snoozeMinutes = 5,
        ).also {
            DepartureAlarmSyncState::class.java.getDeclaredField("id").apply {
                isAccessible = true
                set(it, 99L)
            }
        }

    private fun planState(
        departureAt: Instant,
        validationRequestedAt: Instant,
    ): DepartureAlarmSyncState {
        val plan = DepartureAlarmPlanFactory().create(
            memberId = 7L,
            scheduleId = 41L,
            recommendedDepartureAt = departureAt,
            scheduleTitle = "회의",
        )
        return DepartureAlarmSyncState.createUpsert(
            memberId = 7L,
            scheduleId = 41L,
            triggerAt = plan.departureOccurrence().triggerInstant(),
            title = plan.departureOccurrence().title,
            snoozeMinutes = 5,
            alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
            validationRequestedAt = validationRequestedAt,
        ).also {
            DepartureAlarmSyncState::class.java.getDeclaredField("id").apply {
                isAccessible = true
                set(it, 99L)
            }
        }
    }
}
