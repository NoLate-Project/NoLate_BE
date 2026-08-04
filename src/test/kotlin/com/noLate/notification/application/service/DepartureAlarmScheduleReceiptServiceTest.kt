package com.noLate.notification.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmScheduleReceipt
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.DepartureAlarmScheduleReceiptRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import com.noLate.schedule.domain.DepartureAlarmSyncState
import com.noLate.schedule.domain.DEPARTURE_ALARM_PLAN_SCHEMA_VERSION
import com.noLate.schedule.domain.DepartureAlarmPlanCodec
import com.noLate.schedule.application.service.DepartureAlarmPlanFactory
import com.noLate.schedule.infrastructure.DepartureAlarmSyncStateRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class DepartureAlarmScheduleReceiptServiceTest {
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var deviceTokenRepository: NotificationDeviceTokenRepository
    @Mock lateinit var syncStateRepository: DepartureAlarmSyncStateRepository
    @Mock lateinit var receiptRepository: DepartureAlarmScheduleReceiptRepository
    @Mock lateinit var scheduleRepository: ScheduleRepository

    @Test
    fun `current snapshot upsert success becomes a device-bound scheduled denominator`() {
        activeScope()
        whenever(receiptRepository.findByMemberIdAndClientReceiptId(MEMBER_ID, RECEIPT_ID))
            .thenReturn(null)
        whenever(receiptRepository.save(any<DepartureAlarmScheduleReceipt>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmScheduleReceipt }

        val result = record()

        assertTrue(result.recorded)
        assertEquals(DepartureAlarmScheduleOutcome.SCHEDULED, result.outcome)
        assertEquals(DepartureAlarmGenerationRelation.CURRENT, result.generationRelation)
        val saved = argumentCaptor<DepartureAlarmScheduleReceipt>().also {
            verify(receiptRepository).save(it.capture())
        }.firstValue
        assertEquals(DEVICE_FINGERPRINT, saved.deviceFingerprint)
        assertEquals(DepartureAlarmScheduleSource.SNAPSHOT, saved.source)
        assertEquals(NOW, saved.serverRecordedAt)
    }

    @Test
    fun `same receipt retry is idempotent while a collision is rejected`() {
        activeScope()
        whenever(receiptRepository.findByMemberIdAndClientReceiptId(MEMBER_ID, RECEIPT_ID))
            .thenReturn(existingReceipt())

        val duplicate = record()
        assertFalse(duplicate.recorded)
        verify(receiptRepository, never()).save(any())

        val collision = assertFailsWith<BusinessException> {
            record(occurredAt = OCCURRED_AT.plusSeconds(1))
        }
        assertEquals(ErrorCode.INVALID_INPUT, collision.errorCode)
    }

    @Test
    fun `already applied native replay still recovers a scheduled denominator`() {
        activeScope()
        whenever(receiptRepository.findByMemberIdAndClientReceiptId(MEMBER_ID, RECEIPT_ID))
            .thenReturn(null)
        whenever(receiptRepository.save(any<DepartureAlarmScheduleReceipt>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmScheduleReceipt }

        val result = service().record(
            memberId = MEMBER_ID,
            receiptId = RECEIPT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            operation = DepartureAlarmSyncOperation.UPSERT,
            triggerAt = TRIGGER_AT,
            outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
            applied = false,
            scheduled = true,
            platform = PushPlatform.ANDROID,
            deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
            source = DepartureAlarmScheduleSource.SNAPSHOT,
            reason = "ALREADY_APPLIED",
            occurredAt = OCCURRED_AT,
            deviceId = DEVICE_ID,
        )

        assertTrue(result.recorded)
        val saved = argumentCaptor<DepartureAlarmScheduleReceipt>().also {
            verify(receiptRepository).save(it.capture())
        }.firstValue
        assertFalse(saved.applied)
        assertTrue(saved.scheduled)
        assertEquals("ALREADY_APPLIED", saved.failureReason)
    }

    @Test
    fun `v2 occurrence receipt freezes exact ownership sequence and millisecond trigger`() {
        activeScope()
        val plan = DepartureAlarmPlanFactory().create(
            memberId = MEMBER_ID,
            scheduleId = SCHEDULE_ID,
            recommendedDepartureAt = TRIGGER_AT.plusNanos(999_999),
            scheduleTitle = "출발",
        )
        val occurrence = plan.occurrence("M15")!!
        val state = DepartureAlarmSyncState.createUpsert(
            memberId = MEMBER_ID,
            scheduleId = SCHEDULE_ID,
            triggerAt = plan.departureOccurrence().triggerInstant(),
            title = plan.departureOccurrence().title,
            snoozeMinutes = 5,
            alarmPlanSchemaVersion = DEPARTURE_ALARM_PLAN_SCHEMA_VERSION,
            alarmOccurrencesJson = DepartureAlarmPlanCodec.encode(plan),
        )
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state)
        whenever(receiptRepository.findByMemberIdAndClientReceiptId(MEMBER_ID, RECEIPT_ID))
            .thenReturn(null)
        whenever(receiptRepository.save(any<DepartureAlarmScheduleReceipt>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmScheduleReceipt }

        val result = service().record(
            memberId = MEMBER_ID,
            receiptId = RECEIPT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            operation = DepartureAlarmSyncOperation.UPSERT,
            triggerAt = occurrence.triggerInstant().plusNanos(999_999),
            outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
            applied = true,
            scheduled = true,
            platform = PushPlatform.ANDROID,
            deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
            source = DepartureAlarmScheduleSource.PUSH,
            reason = null,
            occurredAt = OCCURRED_AT.plusNanos(999_999),
            deviceId = DEVICE_ID,
            occurrenceId = "M15",
            mutationSequence = 7,
        )

        assertTrue(result.recorded)
        val saved = argumentCaptor<DepartureAlarmScheduleReceipt>().also {
            verify(receiptRepository).save(it.capture())
        }.firstValue
        assertEquals(71L, saved.deviceTokenId)
        assertEquals(0L, saved.tokenOwnershipVersion)
        assertEquals("M15", saved.occurrenceId)
        assertEquals(7L, saved.mutationSequence)
        assertEquals(occurrence.triggerInstant(), saved.triggerAt)
        assertEquals(OCCURRED_AT, saved.clientOccurredAt)
    }

    @Test
    fun `native failure is retained with a bounded reason but impossible outcome shapes fail`() {
        activeScope()
        whenever(receiptRepository.findByMemberIdAndClientReceiptId(MEMBER_ID, RECEIPT_ID))
            .thenReturn(null)
        whenever(receiptRepository.save(any<DepartureAlarmScheduleReceipt>()))
            .thenAnswer { it.arguments.single() as DepartureAlarmScheduleReceipt }

        service().record(
            memberId = MEMBER_ID,
            receiptId = RECEIPT_ID,
            alarmId = ALARM_ID,
            scheduleId = SCHEDULE_ID,
            generation = 0,
            recipientMemberId = MEMBER_ID,
            operation = DepartureAlarmSyncOperation.UPSERT,
            triggerAt = TRIGGER_AT,
            outcome = DepartureAlarmScheduleOutcome.FAILED,
            applied = false,
            scheduled = false,
            platform = PushPlatform.ANDROID,
            deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
            source = DepartureAlarmScheduleSource.PUSH,
            reason = "exact alarm permission denied!",
            occurredAt = OCCURRED_AT,
            deviceId = DEVICE_ID,
        )
        val saved = argumentCaptor<DepartureAlarmScheduleReceipt>().also {
            verify(receiptRepository).save(it.capture())
        }.firstValue
        assertEquals("EXACT_ALARM_PERMISSION_DENIED", saved.failureReason)

        val invalid = assertFailsWith<BusinessException> {
            record(
                outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
                applied = false,
                scheduled = false,
            )
        }
        assertEquals(ErrorCode.INVALID_INPUT, invalid.errorCode)
    }

    @Test
    fun `unregistered or wrong-platform devices deleted schedules and mismatched current commands fail closed`() {
        activeScope()
        whenever(
            deviceTokenRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
            )
        ).thenReturn(emptyList())
        assertEquals(
            ErrorCode.NOTIFICATION_NOT_FOUND,
            assertFailsWith<BusinessException> { record() }.errorCode,
        )

        activeScope()
        whenever(
            deviceTokenRepository.findAllByMemberIdAndDeviceFingerprintForUpdate(
                MEMBER_ID,
                DEVICE_FINGERPRINT,
            )
        ).thenReturn(
            listOf(
                NotificationDeviceToken(
                    id = 71L,
                    memberId = MEMBER_ID,
                    deviceId = DEVICE_ID,
                    platform = PushPlatform.IOS,
                    token = "ios-token-17",
                )
            )
        )
        assertEquals(
            ErrorCode.NOTIFICATION_NOT_FOUND,
            assertFailsWith<BusinessException> { record() }.errorCode,
        )

        activeScope()
        whenever(scheduleRepository.existsByIdAndDeletedFalse(SCHEDULE_ID)).thenReturn(false)
        assertEquals(
            ErrorCode.NOTIFICATION_NOT_FOUND,
            assertFailsWith<BusinessException> { record() }.errorCode,
        )

        activeScope()
        assertEquals(
            ErrorCode.NOTIFICATION_NOT_FOUND,
            assertFailsWith<BusinessException> {
                record(triggerAt = TRIGGER_AT.plusSeconds(60))
            }.errorCode,
        )
        verify(receiptRepository, never()).save(any())
    }

    private fun activeScope() {
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
                    id = 71L,
                    memberId = MEMBER_ID,
                    deviceId = DEVICE_ID,
                    platform = PushPlatform.ANDROID,
                    token = "token-17",
                )
            )
        )
        whenever(syncStateRepository.findByMemberIdAndScheduleIdForUpdate(MEMBER_ID, SCHEDULE_ID))
            .thenReturn(state())
        whenever(scheduleRepository.existsByIdAndDeletedFalse(SCHEDULE_ID)).thenReturn(true)
    }

    private fun record(
        triggerAt: Instant? = TRIGGER_AT,
        outcome: DepartureAlarmScheduleOutcome = DepartureAlarmScheduleOutcome.SCHEDULED,
        applied: Boolean = true,
        scheduled: Boolean = true,
        occurredAt: Instant = OCCURRED_AT,
    ) = service().record(
        memberId = MEMBER_ID,
        receiptId = RECEIPT_ID,
        alarmId = ALARM_ID,
        scheduleId = SCHEDULE_ID,
        generation = 0,
        recipientMemberId = MEMBER_ID,
        operation = DepartureAlarmSyncOperation.UPSERT,
        triggerAt = triggerAt,
        outcome = outcome,
        applied = applied,
        scheduled = scheduled,
        platform = PushPlatform.ANDROID,
        deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
        source = DepartureAlarmScheduleSource.SNAPSHOT,
        reason = null,
        occurredAt = occurredAt,
        deviceId = DEVICE_ID,
    )

    private fun service() = DepartureAlarmScheduleReceiptService(
        memberRepository = memberRepository,
        deviceTokenRepository = deviceTokenRepository,
        syncStateRepository = syncStateRepository,
        receiptRepository = receiptRepository,
        scheduleRepository = scheduleRepository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun state() = DepartureAlarmSyncState.createUpsert(
        memberId = MEMBER_ID,
        scheduleId = SCHEDULE_ID,
        triggerAt = TRIGGER_AT,
        title = "출발",
        snoozeMinutes = 5,
    )

    private fun existingReceipt() = DepartureAlarmScheduleReceipt(
        id = 1,
        memberId = MEMBER_ID,
        clientReceiptId = RECEIPT_ID,
        deviceFingerprint = DEVICE_FINGERPRINT,
        deviceTokenId = 71L,
        tokenOwnershipVersion = 0,
        commandReceiptKey = "a".repeat(64),
        alarmId = ALARM_ID,
        scheduleId = SCHEDULE_ID,
        generation = 0,
        desiredGenerationAtReceipt = 0,
        desiredOperationAtReceipt = DepartureAlarmSyncOperation.UPSERT,
        generationRelation = DepartureAlarmGenerationRelation.CURRENT,
        operation = DepartureAlarmSyncOperation.UPSERT,
        triggerAt = TRIGGER_AT,
        outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
        applied = true,
        scheduled = true,
        platform = PushPlatform.ANDROID,
        deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
        source = DepartureAlarmScheduleSource.SNAPSHOT,
        failureReason = null,
        clientOccurredAt = OCCURRED_AT,
        serverRecordedAt = NOW.minusSeconds(10),
    )

    private companion object {
        const val MEMBER_ID = 17L
        const val SCHEDULE_ID = 41L
        const val ALARM_ID = "schedule:41:member:17"
        const val RECEIPT_ID = "550e8400-e29b-41d4-a716-446655440100"
        const val DEVICE_ID = "installation-17"
        val DEVICE_FINGERPRINT = OpaquePushIdentifier.fingerprint(DEVICE_ID)
        val NOW: Instant = Instant.parse("2026-08-01T03:00:00Z")
        val OCCURRED_AT: Instant = NOW.minusSeconds(5)
        val TRIGGER_AT: Instant = Instant.parse("2026-08-01T04:00:00Z")
    }
}
