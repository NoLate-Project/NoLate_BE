package com.noLate.notification.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.DepartureAlarmFireEventResult
import com.noLate.notification.application.service.DepartureAlarmFireEventService
import com.noLate.notification.domain.DepartureAlarmFireTimingBasis
import com.noLate.notification.domain.DepartureAlarmGenerationRelation
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
class DepartureAlarmFireEventControllerUnitTest {
    @Mock lateinit var service: DepartureAlarmFireEventService

    @Test
    fun `authenticated native evidence is passed with the principal identity`() {
        val request = request()
        val expected = DepartureAlarmFireEventResult(
            recorded = true,
            eventId = request.eventId,
            generationRelation = DepartureAlarmGenerationRelation.CURRENT,
            scheduledFor = request.scheduledFor,
            occurredAt = request.occurredAt,
            timingBasis = DepartureAlarmFireTimingBasis.EXACT_CALLBACK,
            serverRecordedAt = NOW,
            fireDelaySeconds = 4,
        )
        whenever(
            service.record(
                memberId = 17,
                eventId = request.eventId,
                alarmId = request.alarmId,
                scheduleId = request.scheduleId,
                generation = request.generation,
                recipientMemberId = request.recipientMemberId,
                scheduledFor = request.scheduledFor,
                occurredAt = request.occurredAt,
                timingBasis = request.timingBasis,
                sourceTriggerAt = request.sourceTriggerAt,
                deviceId = request.deviceId,
            )
        ).thenReturn(expected)

        val response = DepartureAlarmFireEventController(service).record(
            principal = MemberPrincipal(17, "member@example.com", "member"),
            request = request,
        )

        assertTrue(response.success)
        assertEquals(expected, response.data)
        verify(service).record(
            memberId = 17,
            eventId = request.eventId,
            alarmId = request.alarmId,
            scheduleId = request.scheduleId,
            generation = request.generation,
            recipientMemberId = request.recipientMemberId,
            scheduledFor = request.scheduledFor,
            occurredAt = request.occurredAt,
            timingBasis = request.timingBasis,
            sourceTriggerAt = request.sourceTriggerAt,
            deviceId = request.deviceId,
        )
    }

    @Test
    fun `missing principal is rejected before native evidence access`() {
        val error = assertFailsWith<BusinessException> {
            DepartureAlarmFireEventController(service).record(null, request())
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        verifyNoInteractions(service)
    }

    private fun request() = DepartureAlarmFireEventRequest(
        eventId = "550e8400-e29b-41d4-a716-446655440000",
        alarmId = "schedule:41:member:17",
        scheduleId = 41,
        generation = 3,
        recipientMemberId = 17,
        scheduledFor = Instant.parse("2026-08-01T03:00:00Z"),
        sourceTriggerAt = Instant.parse("2026-08-01T02:55:00Z"),
        occurredAt = Instant.parse("2026-08-01T03:00:04Z"),
        timingBasis = DepartureAlarmFireTimingBasis.EXACT_CALLBACK,
        deviceId = "installation-17",
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T03:01:00Z")
    }
}
