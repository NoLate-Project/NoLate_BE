package com.noLate.notification.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.DepartureAlarmScheduleReceiptResult
import com.noLate.notification.application.service.DepartureAlarmScheduleReceiptService
import com.noLate.notification.domain.DepartureAlarmDeliveryMode
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
import com.noLate.notification.domain.DepartureAlarmScheduleOutcome
import com.noLate.notification.domain.DepartureAlarmScheduleSource
import com.noLate.notification.domain.PushPlatform
import com.noLate.schedule.domain.DepartureAlarmSyncOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class DepartureAlarmScheduleReceiptControllerUnitTest {
    @Mock lateinit var service: DepartureAlarmScheduleReceiptService

    @Test
    fun `authenticated native receipt is bound to the principal identity`() {
        val request = request()
        val expected = DepartureAlarmScheduleReceiptResult(
            recorded = true,
            receiptId = request.receiptId,
            outcome = request.outcome,
            generationRelation = DepartureAlarmGenerationRelation.CURRENT,
            serverRecordedAt = NOW,
        )
        whenever(
            service.record(
                memberId = 17,
                receiptId = request.receiptId,
                alarmId = request.alarmId,
                scheduleId = request.scheduleId,
                generation = request.generation,
                recipientMemberId = request.recipientMemberId,
                operation = request.operation,
                triggerAt = request.triggerAt,
                outcome = request.outcome,
                applied = request.applied,
                scheduled = request.scheduled,
                platform = request.platform,
                deliveryMode = request.deliveryMode,
                source = request.source,
                reason = request.reason,
                occurredAt = request.occurredAt,
                deviceId = request.deviceId,
            )
        ).thenReturn(expected)

        val response = DepartureAlarmScheduleReceiptController(service).record(
            MemberPrincipal(17, "member@example.com", "member"),
            request,
        )

        assertTrue(response.success)
        assertEquals(expected, response.data)
        verify(service).record(
            memberId = 17,
            receiptId = request.receiptId,
            alarmId = request.alarmId,
            scheduleId = request.scheduleId,
            generation = request.generation,
            recipientMemberId = request.recipientMemberId,
            operation = request.operation,
            triggerAt = request.triggerAt,
            outcome = request.outcome,
            applied = request.applied,
            scheduled = request.scheduled,
            platform = request.platform,
            deliveryMode = request.deliveryMode,
            source = request.source,
            reason = request.reason,
            occurredAt = request.occurredAt,
            deviceId = request.deviceId,
        )
    }

    @Test
    fun `missing principal is rejected before schedule receipt access`() {
        val error = assertFailsWith<BusinessException> {
            DepartureAlarmScheduleReceiptController(service).record(null, request())
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        verifyNoInteractions(service)
    }

    private fun request() = DepartureAlarmScheduleReceiptRequest(
        receiptId = "550e8400-e29b-41d4-a716-446655440100",
        alarmId = "schedule:41:member:17",
        scheduleId = 41,
        generation = 3,
        recipientMemberId = 17,
        operation = DepartureAlarmSyncOperation.UPSERT,
        triggerAt = Instant.parse("2026-08-01T03:00:00Z"),
        outcome = DepartureAlarmScheduleOutcome.SCHEDULED,
        applied = true,
        scheduled = true,
        platform = PushPlatform.ANDROID,
        deliveryMode = DepartureAlarmDeliveryMode.ANDROID_EXACT,
        source = DepartureAlarmScheduleSource.SNAPSHOT,
        reason = null,
        occurredAt = Instant.parse("2026-08-01T02:59:55Z"),
        deviceId = "installation-17",
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T03:00:01Z")
    }
}
