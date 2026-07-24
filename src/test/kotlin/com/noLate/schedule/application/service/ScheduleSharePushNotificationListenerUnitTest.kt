package com.noLate.schedule.application.service

import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.schedule.domain.ScheduleShareResourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleSharePushNotificationListenerUnitTest {

    @Mock
    lateinit var pushEventOutboxService: PushEventOutboxService

    @Test
    fun `schedule share event durably enqueues a detail deep link payload`() {
        val listener = listenerWithSuccessfulEnqueue()

        listener.onShareGranted(
            ScheduleShareGrantedEvent(
                targetMemberId = 2L,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = 10L,
                resourceTitle = "팀 회의",
                notificationEventId = "schedule-share-event",
            )
        )

        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(2L),
            title = eq("새 일정 공유"),
            body = eq("'팀 회의' 일정이 공유됐어요."),
            data = check {
                assertEquals("SCHEDULE_SHARE_RECEIVED", it["type"])
                assertEquals("10", it["scheduleId"])
                assertEquals("SCHEDULE", it["resourceType"])
            },
            deduplicationKey = eq("share-granted:schedule-share-event"),
        )
    }

    @Test
    fun `category share event durably enqueues a category payload`() {
        val listener = listenerWithSuccessfulEnqueue()

        listener.onShareGranted(
            ScheduleShareGrantedEvent(
                targetMemberId = 3L,
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = 7L,
                resourceTitle = "가족",
                notificationEventId = "category-share-event",
            )
        )

        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(3L),
            title = eq("새 캘린더 공유"),
            body = eq("'가족' 캘린더가 공유됐어요."),
            data = check {
                assertEquals("CATEGORY_SHARE_RECEIVED", it["type"])
                assertEquals("7", it["categoryId"])
                assertEquals("CATEGORY", it["resourceType"])
            },
            deduplicationKey = eq("share-granted:category-share-event"),
        )
    }

    @Test
    fun `calendar share event durably enqueues a shared calendar payload`() {
        val listener = listenerWithSuccessfulEnqueue()

        listener.onShareGranted(
            ScheduleShareGrantedEvent(
                targetMemberId = 9L,
                resourceType = ScheduleShareResourceType.CALENDAR,
                resourceId = 77L,
                resourceTitle = "가족 이동",
                notificationEventId = "calendar-event",
            )
        )

        verify(pushEventOutboxService).enqueueDurable(
            memberId = eq(9L),
            title = eq("새 공유 캘린더"),
            body = eq("'가족 이동' 캘린더가 공유됐어요."),
            data = check {
                assertEquals("CALENDAR_SHARE_RECEIVED", it["type"])
                assertEquals("CALENDAR", it["resourceType"])
                assertEquals("77", it["calendarId"])
            },
            deduplicationKey = eq("share-granted:calendar-event"),
        )
    }

    @Test
    fun `outbox persistence failure escapes so the sharing transaction can roll back`() {
        val listener = ScheduleSharePushNotificationListener(pushEventOutboxService)
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

    private fun listenerWithSuccessfulEnqueue(): ScheduleSharePushNotificationListener {
        whenever(
            pushEventOutboxService.enqueueDurable(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        ).thenReturn(preparedEvent())
        return ScheduleSharePushNotificationListener(pushEventOutboxService)
    }

    private fun preparedEvent(): PreparedPushEvent =
        PreparedPushEvent(
            snapshot = null,
            logicalEventKey = "event:test",
            deliveryIds = emptyList(),
            manifestRecipientCount = 0,
            inboxCreated = true,
            fenceAccepted = true,
        )
}
