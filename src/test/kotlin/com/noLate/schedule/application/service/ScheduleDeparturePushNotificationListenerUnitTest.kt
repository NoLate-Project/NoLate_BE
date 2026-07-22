package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleDeparturePushNotificationListenerUnitTest {

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Test
    fun `first departure event notifies every other active participant with schedule detail payload`() {
        val listener = ScheduleDeparturePushNotificationListener(notificationUseCase)
        whenever(
            notificationUseCase.sendToMembers(
                eq(listOf(1L, 3L, 4L)),
                eq("참가자 출발"),
                eq("민수님이 '팀 회의' 일정으로 출발했어요."),
                any(),
                eq("schedule-participant-departed:10:2"),
                eq(true),
            )
        ).thenReturn(NotificationSendResult(requestedCount = 3, sentCount = 3))

        listener.onParticipantDeparted(
            ScheduleParticipantDepartedEvent(
                scheduleId = 10L,
                scheduleTitle = "팀 회의",
                departedMemberId = 2L,
                departedMemberLabel = "민수",
                recipientMemberIds = listOf(1L, 3L, 4L),
            )
        )

        verify(notificationUseCase).sendToMembers(
            memberIds = eq(listOf(1L, 3L, 4L)),
            title = eq("참가자 출발"),
            body = eq("민수님이 '팀 회의' 일정으로 출발했어요."),
            data = check {
                assertEquals("SCHEDULE_PARTICIPANT_DEPARTED", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("2", it["departedMemberId"])
            },
            inboxDeduplicationKey = eq("schedule-participant-departed:10:2"),
            persistInInbox = eq(true),
        )
    }

    @Test
    fun `departure event with no other participants does not invoke push infrastructure`() {
        val listener = ScheduleDeparturePushNotificationListener(notificationUseCase)

        listener.onParticipantDeparted(
            ScheduleParticipantDepartedEvent(
                scheduleId = 10L,
                scheduleTitle = "개인 일정",
                departedMemberId = 1L,
                departedMemberLabel = "나",
                recipientMemberIds = emptyList(),
            )
        )

        verifyNoInteractions(notificationUseCase)
    }

    @Test
    fun `push provider failure after departure commit does not escape listener`() {
        val listener = ScheduleDeparturePushNotificationListener(notificationUseCase)
        whenever(notificationUseCase.sendToMembers(any(), any(), any(), any(), any(), eq(true)))
            .thenThrow(IllegalStateException("provider unavailable"))

        assertDoesNotThrow {
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
}
