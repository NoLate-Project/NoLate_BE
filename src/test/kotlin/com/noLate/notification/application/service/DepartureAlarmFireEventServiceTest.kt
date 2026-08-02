package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.notification.domain.DepartureAlarmFireEvent
import com.noLate.notification.domain.DepartureAlarmFireTimingBasis
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.DepartureAlarmFireEventRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class DepartureAlarmFireEventServiceTest {
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var deviceTokenRepository: NotificationDeviceTokenRepository
    @Mock lateinit var syncStateRepository: DepartureAlarmSyncStateRepository
    @Mock lateinit var fireEventRepository: DepartureAlarmFireEventRepository
    @Mock lateinit var scheduleRepository: ScheduleRepository

    @Test
    fun `snapshot-origin native fire is authenticated fingerprinted and recorded once`() {
        val registry = SimpleMeterRegistry()
        activeMember()
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        whenever(fireEventRepository.findByMemberIdAndClientEventId(MEMBER_ID, EVENT_ID))
            .thenReturn(null)
        whenever(
            fireEventRepository.findByMemberIdAndDeviceFingerprintAndAlarmIdAndGenerationAndScheduledFor(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
                ALARM_ID,
                0,
                SCHEDULED_FOR,
            )
        ).thenReturn(null)
        whenever(fireEventRepository.save(any<DepartureAlarmFireEvent>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmFireEvent }

        val result = service(registry).record(
            memberId = MEMBER_ID,
            eventId = EVENT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            scheduledFor = SCHEDULED_FOR,
            sourceTriggerAt = SOURCE_TRIGGER_AT,
            occurredAt = SCHEDULED_FOR.plusSeconds(17),
            deviceId = DEVICE_ID,
        )

        assertTrue(result.recorded)
        assertEquals(17, result.fireDelaySeconds)
        assertEquals(DepartureAlarmGenerationRelation.CURRENT, result.generationRelation)
        val saved = argumentCaptor<DepartureAlarmFireEvent>().also {
            verify(fireEventRepository).save(it.capture())
        }.firstValue
        assertEquals(DEVICE_FINGERPRINT, saved.deviceFingerprint)
        assertEquals(SOURCE_TRIGGER_AT, saved.sourceTriggerAt)
        assertEquals(NOW, saved.serverRecordedAt)
        assertEquals(
            1.0,
            registry.get("nolate.push.client.acks")
                .tag("stage", "alarm_fired")
                .tag("outcome", "recorded")
                .counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("nolate.departure.alarm.fire.events")
                .tag("generation_relation", "current")
                .tag("timing_basis", "exact_callback")
                .tag("outcome", "recorded")
                .counter().count(),
        )
        assertEquals(
            17.0,
            registry.get("nolate.departure.alarm.fire.delay.seconds")
                .tag("generation_relation", "current")
                .tag("direction", "late")
                .summary().totalAmount(),
        )
    }

    @Test
    fun `observed AlarmKit alerting is evidence but never enters exact delay metrics`() {
        val registry = SimpleMeterRegistry()
        activeMember(platform = PushPlatform.IOS)
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        whenever(fireEventRepository.findByMemberIdAndClientEventId(MEMBER_ID, EVENT_ID))
            .thenReturn(null)
        whenever(
            fireEventRepository.findByMemberIdAndDeviceFingerprintAndAlarmIdAndGenerationAndScheduledFor(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
                ALARM_ID,
                0,
                SCHEDULED_FOR,
            )
        ).thenReturn(null)
        whenever(fireEventRepository.save(any<DepartureAlarmFireEvent>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmFireEvent }

        val result = service(registry).record(
            memberId = MEMBER_ID,
            eventId = EVENT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            scheduledFor = SCHEDULED_FOR,
            sourceTriggerAt = SOURCE_TRIGGER_AT,
            occurredAt = SCHEDULED_FOR.plusSeconds(45),
            timingBasis = DepartureAlarmFireTimingBasis.OBSERVED_ALERTING,
            deviceId = DEVICE_ID,
        )

        assertEquals(DepartureAlarmFireTimingBasis.OBSERVED_ALERTING, result.timingBasis)
        assertEquals(
            1.0,
            registry.get("nolate.departure.alarm.fire.events")
                .tag("generation_relation", "current")
                .tag("timing_basis", "observed_alerting")
                .tag("outcome", "recorded")
                .counter().count(),
        )
        assertEquals(
            0L,
            registry.get("nolate.departure.alarm.fire.delay.seconds")
                .tag("generation_relation", "current")
                .tag("direction", "late")
                .summary().count(),
        )
    }

    @Test
    fun `missing one-shot AlarmKit delivery is coverage evidence but not exact timing`() {
        val registry = SimpleMeterRegistry()
        activeMember(platform = PushPlatform.IOS)
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        whenever(fireEventRepository.findByMemberIdAndClientEventId(MEMBER_ID, EVENT_ID))
            .thenReturn(null)
        whenever(
            fireEventRepository.findByMemberIdAndDeviceFingerprintAndAlarmIdAndGenerationAndScheduledFor(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
                ALARM_ID,
                0,
                SCHEDULED_FOR,
            )
        ).thenReturn(null)
        whenever(fireEventRepository.save(any<DepartureAlarmFireEvent>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmFireEvent }

        val result = service(registry).record(
            memberId = MEMBER_ID,
            eventId = EVENT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            scheduledFor = SCHEDULED_FOR,
            sourceTriggerAt = SOURCE_TRIGGER_AT,
            occurredAt = SCHEDULED_FOR,
            timingBasis = DepartureAlarmFireTimingBasis.INFERRED_OS_DELIVERY,
            deviceId = DEVICE_ID,
        )

        assertEquals(DepartureAlarmFireTimingBasis.INFERRED_OS_DELIVERY, result.timingBasis)
        assertEquals(
            1.0,
            registry.get("nolate.departure.alarm.fire.events")
                .tag("generation_relation", "current")
                .tag("timing_basis", "inferred_os_delivery")
                .tag("outcome", "recorded")
                .counter().count(),
        )
        assertEquals(
            0L,
            registry.get("nolate.departure.alarm.fire.delay.seconds")
                .tag("generation_relation", "current")
                .tag("direction", "on_time")
                .summary().count(),
        )
    }

    @Test
    fun `fire timing basis must match the registered native platform`() {
        activeMember(platform = PushPlatform.IOS)

        val error = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                sourceTriggerAt = SOURCE_TRIGGER_AT,
                occurredAt = SCHEDULED_FOR,
                timingBasis = DepartureAlarmFireTimingBasis.EXACT_CALLBACK,
                deviceId = DEVICE_ID,
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verifyNoInteractions(syncStateRepository, fireEventRepository)
    }

    @Test
    fun `same native event retry returns the original receipt without a second insert`() {
        activeMember()
        val state = state()
        val existing = existingEvent()
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state)
        whenever(fireEventRepository.findByMemberIdAndClientEventId(MEMBER_ID, EVENT_ID))
            .thenReturn(existing)

        val result = service().record(
            memberId = MEMBER_ID,
            eventId = EVENT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            scheduledFor = SCHEDULED_FOR,
            sourceTriggerAt = SOURCE_TRIGGER_AT,
            occurredAt = SCHEDULED_FOR.plusSeconds(17),
            deviceId = DEVICE_ID,
        )

        assertFalse(result.recorded)
        assertEquals(existing.serverRecordedAt, result.serverRecordedAt)
        verify(fireEventRepository, never()).save(any())
    }

    @Test
    fun `an older generation that actually fired is preserved as stale evidence`() {
        activeMember()
        val current = state().apply {
            upsert(
                triggerAt = SOURCE_TRIGGER_AT.plusSeconds(60),
                title = "변경된 일정",
                snoozeMinutes = 5,
            )
        }
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(current)
        whenever(fireEventRepository.findByMemberIdAndClientEventId(MEMBER_ID, EVENT_ID))
            .thenReturn(null)
        whenever(
            fireEventRepository.findByMemberIdAndDeviceFingerprintAndAlarmIdAndGenerationAndScheduledFor(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
                ALARM_ID,
                0,
                SCHEDULED_FOR,
            )
        ).thenReturn(null)
        whenever(fireEventRepository.save(any<DepartureAlarmFireEvent>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmFireEvent }

        val result = service().record(
            memberId = MEMBER_ID,
            eventId = EVENT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            scheduledFor = SCHEDULED_FOR,
            sourceTriggerAt = SOURCE_TRIGGER_AT,
            occurredAt = SCHEDULED_FOR,
            deviceId = DEVICE_ID,
        )

        assertEquals(DepartureAlarmGenerationRelation.STALE, result.generationRelation)
        assertEquals(1, current.generation)
    }

    @Test
    fun `another recipient or impossible generation cannot forge fire evidence`() {
        val recipientError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = 99,
                scheduledFor = SCHEDULED_FOR,
                occurredAt = SCHEDULED_FOR,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, recipientError.errorCode)
        verifyNoInteractions(memberRepository, syncStateRepository, fireEventRepository)

        activeMember()
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        val generationError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 1,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                occurredAt = SCHEDULED_FOR,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, generationError.errorCode)
    }

    @Test
    fun `reusing a client event id for different facts is rejected`() {
        activeMember()
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        whenever(fireEventRepository.findByMemberIdAndClientEventId(MEMBER_ID, EVENT_ID))
            .thenReturn(existingEvent())

        val error = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                sourceTriggerAt = SOURCE_TRIGGER_AT,
                occurredAt = SCHEDULED_FOR.plusSeconds(18),
                deviceId = DEVICE_ID,
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
    }

    @Test
    fun `withdrawn member cannot recreate alarm evidence from an old authenticated callback`() {
        whenever(memberRepository.findByIdForUpdate(MEMBER_ID)).thenReturn(
            Member(
                id = MEMBER_ID,
                name = "deleted",
                password = "",
                email = "deleted-17@example.invalid",
            ).apply { softDelete() }
        )

        val error = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                occurredAt = SCHEDULED_FOR,
                deviceId = DEVICE_ID,
            )
        }

        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, error.errorCode)
        verifyNoInteractions(syncStateRepository, fireEventRepository)
    }

    @Test
    fun `unregistered device and deleted schedule cannot create fire evidence`() {
        activeMember()
        whenever(
            deviceTokenRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
            )
        ).thenReturn(emptyList())

        val deviceError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                sourceTriggerAt = SOURCE_TRIGGER_AT,
                occurredAt = SCHEDULED_FOR,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, deviceError.errorCode)

        activeMember()
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        whenever(scheduleRepository.existsByIdAndDeletedFalse(SCHEDULE_ID)).thenReturn(false)
        val deletedScheduleError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                sourceTriggerAt = SOURCE_TRIGGER_AT,
                occurredAt = SCHEDULED_FOR,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, deletedScheduleError.errorCode)
        verify(fireEventRepository, never()).save(any())
    }

    @Test
    fun `current cancel generation and implausible device timestamps are rejected`() {
        activeMember()
        val canceled = state().apply { cancel() }
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(canceled)

        val canceledError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = canceled.generation,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                occurredAt = SCHEDULED_FOR,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, canceledError.errorCode)

        val timestampError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = SCHEDULED_FOR,
                sourceTriggerAt = SOURCE_TRIGGER_AT,
                occurredAt = SCHEDULED_FOR.plusSeconds(24 * 60 * 60L + 1),
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.INVALID_INPUT, timestampError.errorCode)
        verify(fireEventRepository, never()).save(any())
    }

    @Test
    fun `historic or future fire reports cannot pollute the telemetry cohort`() {
        val historicScheduledFor = NOW.minusSeconds(30 * 24 * 60 * 60L + 1)
        val historicError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = historicScheduledFor,
                occurredAt = historicScheduledFor,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.INVALID_INPUT, historicError.errorCode)

        val futureScheduledFor = NOW.plusSeconds(5 * 60L + 1)
        val futureError = assertFailsWith<BusinessException> {
            service().record(
                memberId = MEMBER_ID,
                eventId = EVENT_ID,
                alarmId = ALARM_ID,
                scheduleId = SCHEDULE_ID,
                generation = 0,
                recipientMemberId = MEMBER_ID,
                scheduledFor = futureScheduledFor,
                occurredAt = futureScheduledFor,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals(ErrorCode.INVALID_INPUT, futureError.errorCode)
        verifyNoInteractions(memberRepository, syncStateRepository, fireEventRepository)
    }

    private fun service(registry: SimpleMeterRegistry = SimpleMeterRegistry()) =
        DepartureAlarmFireEventService(
            memberRepository = memberRepository,
            deviceTokenRepository = deviceTokenRepository,
            syncStateRepository = syncStateRepository,
            fireEventRepository = fireEventRepository,
            scheduleRepository = scheduleRepository,
            operationalMetrics = NoLateOperationalMetrics(registry),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun activeMember(platform: PushPlatform = PushPlatform.ANDROID) {
        whenever(memberRepository.findByIdForUpdate(MEMBER_ID)).thenReturn(
            Member(
                id = MEMBER_ID,
                name = "member",
                password = "Password1!",
                email = "member@example.com",
            )
        )
        whenever(
            deviceTokenRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
            )
        ).thenReturn(
            listOf(
                NotificationDeviceToken(
                    memberId = MEMBER_ID,
                    deviceId = DEVICE_ID,
                    platform = platform,
                    token = "token-17",
                )
            )
        )
        lenient().`when`(scheduleRepository.existsByIdAndDeletedFalse(SCHEDULE_ID)).thenReturn(true)
    }

    private fun state() = DepartureAlarmSyncState.createUpsert(
        memberId = MEMBER_ID,
        scheduleId = SCHEDULE_ID,
        triggerAt = SOURCE_TRIGGER_AT,
        title = "출발",
        snoozeMinutes = 5,
    )

    private fun existingEvent() = DepartureAlarmFireEvent(
        id = 1,
        memberId = MEMBER_ID,
        clientEventId = EVENT_ID,
        deviceFingerprint = DEVICE_FINGERPRINT,
        alarmId = ALARM_ID,
        scheduleId = SCHEDULE_ID,
        generation = 0,
        desiredGenerationAtReceipt = 0,
        desiredOperationAtReceipt = com.noLate.schedule.domain.DepartureAlarmSyncOperation.UPSERT,
        generationRelation = DepartureAlarmGenerationRelation.CURRENT,
        scheduledFor = SCHEDULED_FOR,
        sourceTriggerAt = SOURCE_TRIGGER_AT,
        clientOccurredAt = SCHEDULED_FOR.plusSeconds(17),
        fireDelaySeconds = 17,
        serverRecordedAt = NOW.minusSeconds(30),
    )

    private companion object {
        const val MEMBER_ID = 17L
        const val SCHEDULE_ID = 41L
        const val ALARM_ID = "schedule:41:member:17"
        const val EVENT_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val DEVICE_ID = "installation-17"
        val DEVICE_FINGERPRINT = OpaquePushIdentifier.fingerprint(DEVICE_ID)
        val SOURCE_TRIGGER_AT: Instant = Instant.parse("2026-08-01T03:00:00Z")
        val SCHEDULED_FOR: Instant = Instant.parse("2026-08-01T03:05:00Z")
        val NOW: Instant = Instant.parse("2026-08-01T03:06:00Z")
    }
}
