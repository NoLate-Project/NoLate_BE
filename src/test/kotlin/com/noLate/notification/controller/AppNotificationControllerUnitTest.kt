package com.noLate.notification.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.application.service.AppNotificationInboxPage
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.domain.AppNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class AppNotificationControllerUnitTest {

    @Mock
    lateinit var service: AppNotificationService

    private val objectMapper = ObjectMapper()
        .findAndRegisterModules()

    private val principal = MemberPrincipal(
        id = 17L,
        email = "member@example.com",
        name = "Member",
    )

    @Test
    fun `inbox response exposes navigation data and unread metadata`() {
        val controller = AppNotificationController(service, objectMapper)
        val createdAt = Instant.parse("2026-07-22T01:00:00Z")
        val notification = notification(
            id = 31L,
            dataJson = """{"type":"SCHEDULE_SHARE_RECEIVED","scheduleId":"55"}""",
            createdAt = createdAt,
        )
        whenever(service.getInbox(17L, 40L, 20, false)).thenReturn(
            AppNotificationInboxPage(
                items = listOf(notification),
                nextCursor = 31L,
                unreadCount = 3L,
            )
        )

        val response = controller.getInbox(
            principal = principal,
            cursorId = 40L,
            limit = 20,
            unreadOnly = false,
        )

        assertTrue(response.success)
        assertEquals(3L, response.data?.unreadCount)
        assertEquals(31L, response.data?.nextCursor)
        assertEquals(55L, response.data?.items?.single()?.scheduleId)
        assertEquals("55", response.data?.items?.single()?.data?.get("scheduleId"))
        assertFalse(response.data?.items?.single()?.read ?: true)
        assertEquals(createdAt, response.data?.items?.single()?.createdAt)
        verify(service).getInbox(17L, 40L, 20, false)
    }

    @Test
    fun `read endpoints always use authenticated member id`() {
        val controller = AppNotificationController(service, objectMapper)
        val readAt = Instant.parse("2026-07-22T01:05:00Z")
        whenever(service.getUnreadCount(17L)).thenReturn(4L)
        whenever(service.markRead(17L, 31L)).thenReturn(
            notification(id = 31L, readAt = readAt)
        )
        whenever(service.markAllRead(17L)).thenReturn(3)

        assertEquals(4L, controller.getUnreadCount(principal).data?.unreadCount)
        assertTrue(controller.markRead(principal, 31L).data?.read ?: false)
        assertEquals(readAt, controller.markRead(principal, 31L).data?.readAt)
        assertEquals(3, controller.markAllRead(principal).data?.updatedCount)

        verify(service).getUnreadCount(17L)
        verify(service, org.mockito.kotlin.times(2)).markRead(17L, 31L)
        verify(service).markAllRead(17L)
    }

    @Test
    fun `anonymous inbox request fails before service access`() {
        val controller = AppNotificationController(service, objectMapper)

        val error = assertThrows(BusinessException::class.java) {
            controller.getInbox(null, cursorId = null, limit = 30, unreadOnly = false)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        verifyNoInteractions(service)
    }

    private fun notification(
        id: Long,
        dataJson: String = """{"type":"SCHEDULE_REMINDER","scheduleId":"55"}""",
        createdAt: Instant = Instant.parse("2026-07-22T01:00:00Z"),
        readAt: Instant? = null,
    ) = AppNotification(
        id = id,
        memberId = 17L,
        type = "SCHEDULE_REMINDER",
        scheduleId = 55L,
        title = "곧 출발할 시간이에요",
        body = "경로를 확인해 주세요.",
        dataJson = dataJson,
        createdAt = createdAt,
        readAt = readAt,
    )
}
