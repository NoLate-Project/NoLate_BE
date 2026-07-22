package com.noLate.schedule.application.service

import com.noLate.notification.application.useCase.NotificationSendResult
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.schedule.domain.ScheduleShareResourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleSharePushNotificationListenerUnitTest {

    @Mock
    lateinit var notificationUseCase: NotificationUseCase

    @Test
    fun `schedule share event sends a detail deep link payload to the target member`() {
        val listener = ScheduleSharePushNotificationListener(notificationUseCase)
        whenever(
            notificationUseCase.sendToMember(
                eq(2L),
                eq("새 일정 공유"),
                eq("'팀 회의' 일정이 공유됐어요."),
                org.mockito.kotlin.any(),
                eq("share-granted:schedule-share-event"),
                eq(true),
            )
        )
            .thenReturn(NotificationSendResult(sentCount = 1))

        listener.onShareGranted(
            ScheduleShareGrantedEvent(
                targetMemberId = 2L,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = 10L,
                resourceTitle = "팀 회의",
                notificationEventId = "schedule-share-event",
            )
        )

        verify(notificationUseCase).sendToMember(
            memberId = eq(2L),
            title = eq("새 일정 공유"),
            body = eq("'팀 회의' 일정이 공유됐어요."),
            data = check {
                assertEquals("SCHEDULE_SHARE_RECEIVED", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("SCHEDULE", it["resourceType"])
            },
            inboxDeduplicationKey = eq("share-granted:schedule-share-event"),
            persistInInbox = eq(true),
        )
    }

    @Test
    fun `category share event sends a share inbox payload to the target member`() {
        val listener = ScheduleSharePushNotificationListener(notificationUseCase)
        whenever(
            notificationUseCase.sendToMember(
                eq(3L),
                eq("새 캘린더 공유"),
                eq("'가족' 캘린더가 공유됐어요."),
                org.mockito.kotlin.any(),
                eq("share-granted:category-share-event"),
                eq(true),
            )
        )
            .thenReturn(NotificationSendResult(sentCount = 1))

        listener.onShareGranted(
            ScheduleShareGrantedEvent(
                targetMemberId = 3L,
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = 7L,
                resourceTitle = "가족",
                notificationEventId = "category-share-event",
            )
        )

        verify(notificationUseCase).sendToMember(
            memberId = eq(3L),
            title = eq("새 캘린더 공유"),
            body = eq("'가족' 캘린더가 공유됐어요."),
            data = check {
                assertEquals("CATEGORY_SHARE_RECEIVED", it["type"])
                assertEquals("7", it["categoryId"])
                assertEquals("CATEGORY", it["resourceType"])
            },
            inboxDeduplicationKey = eq("share-granted:category-share-event"),
            persistInInbox = eq(true),
        )
    }

    @Test
    fun `calendar share event sends a shared calendar payload to the target member`() {
        val listener = ScheduleSharePushNotificationListener(notificationUseCase)
        whenever(
            notificationUseCase.sendToMember(
                eq(9L),
                eq("새 공유 캘린더"),
                eq("'가족 이동' 캘린더가 공유됐어요."),
                org.mockito.kotlin.any(),
                eq("share-granted:calendar-event"),
                eq(true),
            )
        ).thenReturn(NotificationSendResult(sentCount = 1))

        listener.onShareGranted(
            ScheduleShareGrantedEvent(
                targetMemberId = 9L,
                resourceType = ScheduleShareResourceType.CALENDAR,
                resourceId = 77L,
                resourceTitle = "가족 이동",
                notificationEventId = "calendar-event",
            )
        )

        verify(notificationUseCase).sendToMember(
            memberId = eq(9L),
            title = eq("새 공유 캘린더"),
            body = eq("'가족 이동' 캘린더가 공유됐어요."),
            data = check {
                assertEquals("CALENDAR_SHARE_RECEIVED", it["type"])
                assertEquals("CALENDAR", it["resourceType"])
                assertEquals("77", it["calendarId"])
            },
            inboxDeduplicationKey = eq("share-granted:calendar-event"),
            persistInInbox = eq(true),
        )
    }

    @Test
    fun `push provider failure after commit does not escape the listener`() {
        val listener = ScheduleSharePushNotificationListener(notificationUseCase)
        whenever(
            notificationUseCase.sendToMember(
                eq(2L),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                eq(true),
            )
        )
            .thenThrow(IllegalStateException("provider unavailable"))

        assertDoesNotThrow {
            listener.onShareGranted(
                ScheduleShareGrantedEvent(
                    targetMemberId = 2L,
                    resourceType = ScheduleShareResourceType.SCHEDULE,
                    resourceId = 10L,
                    resourceTitle = "팀 회의",
                )
            )
        }
    }
}
