package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.infrastructure.AppNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class AppNotificationVisibilityUnitTest {

    @Mock lateinit var repository: AppNotificationRepository
    @Mock lateinit var writer: AppNotificationWriter
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var authorizationValidator: PushRecipientAuthorizationValidator

    private lateinit var service: AppNotificationService

    @BeforeEach
    fun setUp() {
        service = AppNotificationService(
            repository = repository,
            writer = writer,
            memberRepository = memberRepository,
            objectMapper = ObjectMapper(),
            clock = CLOCK,
            recipientAuthorizationValidator = authorizationValidator,
        )
        whenever(
            authorizationValidator.canDispatch(
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
            )
        ).thenReturn(true)
    }

    @Test
    fun `inbox and unread count hide dormant share and unauthorized resource rows`() {
        val dormantShare = notification(
            id = 30L,
            type = "SCHEDULE_SHARE_RECEIVED",
            title = "private dormant share",
        )
        val revokedResource = notification(
            id = 20L,
            type = "SCHEDULE_DEPARTURE_REMINDER",
            scheduleId = 99L,
            title = "private revoked schedule",
        )
        val ordinary = notification(
            id = 10L,
            type = "GENERAL",
            title = "ordinary notification",
        )
        val candidates = listOf(dormantShare, revokedResource, ordinary)
        whenever(repository.findAllByMemberIdOrderByIdDesc(any(), any<Pageable>()))
            .thenReturn(candidates)
        whenever(repository.findAllByMemberIdAndReadAtIsNullOrderByIdDesc(any(), any<Pageable>()))
            .thenReturn(candidates)
        reject(dormantShare)
        reject(revokedResource)

        val page = service.getInbox(
            memberId = MEMBER_ID,
            cursorId = null,
            limit = 10,
            unreadOnly = false,
        )

        assertEquals(listOf("ordinary notification"), page.items.map { it.title })
        assertEquals(1L, page.unreadCount)
        assertEquals(1L, service.getUnreadCount(MEMBER_ID))
    }

    @Test
    fun `direct read of hidden share row is not found and does not mutate dormant data`() {
        val hidden = notification(
            id = 40L,
            type = "CALENDAR_SHARE_RECEIVED",
            calendarId = 77L,
            title = "private calendar",
        )
        currentSession()
        whenever(repository.findByIdAndMemberId(40L, MEMBER_ID)).thenReturn(hidden)
        reject(hidden)

        val failure = assertThrows(BusinessException::class.java) {
            service.markRead(
                memberId = MEMBER_ID,
                notificationId = 40L,
                presentedSessionGeneration = SESSION_GENERATION,
            )
        }

        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, failure.errorCode)
        assertNull(hidden.readAt)
        verify(repository, never()).save(hidden)
    }

    @Test
    fun `read all changes only currently visible notifications`() {
        val hidden = notification(
            id = 50L,
            type = "SCHEDULE_PARTICIPANT_DEPARTED",
            scheduleId = 88L,
            title = "private participant",
        )
        val ordinary = notification(
            id = 40L,
            type = "GENERAL",
            title = "ordinary notification",
        )
        currentSession()
        whenever(repository.findAllByMemberIdAndReadAtIsNullOrderByIdDesc(any(), any<Pageable>()))
            .thenReturn(listOf(hidden, ordinary))
        reject(hidden)

        val updated = service.markAllRead(MEMBER_ID, SESSION_GENERATION)

        assertEquals(1, updated)
        assertNull(hidden.readAt)
        assertEquals(NOW, ordinary.readAt)
    }

    private fun currentSession() {
        whenever(memberRepository.findByIdForUpdate(MEMBER_ID)).thenReturn(
            Member(
                id = MEMBER_ID,
                name = "member",
                email = "member@example.com",
                password = "Password1!",
                sessionGeneration = SESSION_GENERATION,
            )
        )
    }

    private fun reject(notification: AppNotification) {
        whenever(
            authorizationValidator.canDispatch(
                memberId = MEMBER_ID,
                scheduleId = notification.scheduleId,
                categoryId = notification.categoryId,
                payloadType = notification.type,
                calendarId = notification.calendarId,
            )
        ).thenReturn(false)
    }

    private fun notification(
        id: Long,
        type: String,
        title: String,
        scheduleId: Long? = null,
        categoryId: Long? = null,
        calendarId: Long? = null,
    ) = AppNotification(
        id = id,
        memberId = MEMBER_ID,
        type = type,
        scheduleId = scheduleId,
        categoryId = categoryId,
        calendarId = calendarId,
        title = title,
        body = "private body",
        dataJson = "{}",
        createdAt = NOW,
    )

    private companion object {
        const val MEMBER_ID = 7L
        const val SESSION_GENERATION = 3L
        val NOW: Instant = Instant.parse("2026-07-25T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
