package com.noLate.notification.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AppNotificationTest {

    @Test
    fun `읽음 처리는 최초 시각을 유지하며 멱등하게 동작한다`() {
        val notification = AppNotification(
            memberId = 1L,
            type = "SCHEDULE_PARTICIPANT_DEPARTED",
            title = "참가자 출발",
            body = "민수님이 출발했어요.",
            dataJson = "{}",
            createdAt = Instant.parse("2026-07-22T01:00:00Z"),
        )
        val firstReadAt = Instant.parse("2026-07-22T01:05:00Z")

        assertTrue(notification.markRead(firstReadAt))
        assertFalse(notification.markRead(Instant.parse("2026-07-22T01:10:00Z")))
        assertTrue(notification.isRead)
        assertEquals(firstReadAt, notification.readAt)
    }
}
