package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushEventOutboxService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleDeparturePushNotificationListenerUnitTest {

    @Mock
    lateinit var pushEventOutboxService: PushEventOutboxService

    @Test
    fun `first departure event durably enqueues one immutable event per distinct recipient`() {
        val listener = listenerWithSuccessfulEnqueue()

        listener.onParticipantDeparted(
            ScheduleParticipantDepartedEvent(
                scheduleId = 10L,
                scheduleTitle = "팀 회의",
                departedMemberId = 2L,
                departedMemberLabel = "민수",
                recipientMemberIds = listOf(1L, 3L, 1L),
            )
        )

        verify(pushEventOutboxService, times(2)).enqueueDurable(
            memberId = any(),
            title = eq("참가자 출발"),
            body = eq("민수님이 '팀 회의' 일정으로 출발했어요."),
            data = check {
                assertEquals("SCHEDULE_PARTICIPANT_DEPARTED", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("2", it["departedMemberId"])
            },
            deduplicationKey = eq("schedule-participant-departed:10:2"),
        )
        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(1L),
            title = any(),
            body = any(),
            data = any(),
            deduplicationKey = any(),
        )
        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(3L),
            title = any(),
            body = any(),
            data = any(),
            deduplicationKey = any(),
        )
    }

    @Test
    fun `departure event with no other participants does not create an outbox event`() {
        val listener = ScheduleDeparturePushNotificationListener(pushEventOutboxService)

        listener.onParticipantDeparted(
            ScheduleParticipantDepartedEvent(
                scheduleId = 10L,
                scheduleTitle = "개인 일정",
                departedMemberId = 1L,
                departedMemberLabel = "나",
                recipientMemberIds = emptyList(),
            )
        )

        verifyNoInteractions(pushEventOutboxService)
    }

    @Test
    fun `outbox persistence failure escapes so departure mutation can roll back`() {
        val listener = ScheduleDeparturePushNotificationListener(pushEventOutboxService)
        whenever(
            pushEventOutboxService.enqueueDurable(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenThrow(IllegalStateException("outbox unavailable"))

        assertThrows(IllegalStateException::class.java) {
            listener.onParticipantDeparted(
                ScheduleParticipantDepartedEvent(
                    scheduleId = 10L,
                    scheduleTitle = "팀 회의",
                    departedMemberId = 2L,
                    departedMemberLabel = "민수",
                    recipientMemberIds = listOf(1L),
                )
            )
        }
    }

    private fun listenerWithSuccessfulEnqueue(): ScheduleDeparturePushNotificationListener {
        whenever(
            pushEventOutboxService.enqueueDurable(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenReturn(
            PreparedPushEvent(
                snapshot = null,
                logicalEventKey = "event:test",
                deliveryIds = emptyList(),
                manifestRecipientCount = 0,
                inboxCreated = true,
                fenceAccepted = true,
            )
        )
        return ScheduleDeparturePushNotificationListener(pushEventOutboxService)
    }
}
