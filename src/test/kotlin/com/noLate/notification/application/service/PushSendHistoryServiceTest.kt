package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendHistory
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class PushSendHistoryServiceTest {

    @Mock
    lateinit var repository: PushSendHistoryRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var authorizationValidator: PushRecipientAuthorizationValidator

    private lateinit var service: PushSendHistoryService

    @BeforeEach
    fun setUp() {
        service = PushSendHistoryService(
            repository = repository,
            memberRepository = memberRepository,
            objectMapper = ObjectMapper(),
            clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC),
            recipientAuthorizationValidator = authorizationValidator,
        )
    }

    @Test
    fun `성공 이력은 일정 ID와 payload type, FCM message id를 저장한다`() {
        allowActiveRecipient()
        whenever(
            authorizationValidator.canDispatch(
                memberId = 1L,
                scheduleId = 13L,
                categoryId = 17L,
                payloadType = "SCHEDULE_TRAFFIC",
                calendarId = 19L,
            )
        ).thenReturn(true)
        whenever(repository.save(any<PushSendHistory>())).thenAnswer { it.arguments[0] }
        val token = NotificationDeviceToken(
            id = 10L,
            memberId = 1L,
            deviceId = "android-emulator",
            platform = PushPlatform.ANDROID,
            token = "fcm-token",
        )
        val data = mapOf(
            "type" to "SCHEDULE_TRAFFIC",
            "scheduleId" to "13",
            "categoryId" to "17",
            "calendarId" to "19",
            "logicalEventKey" to "logical:history-source",
            "trafficChangeMinutes" to "15",
        )

        val history = requireNotNull(service.recordSuccess(
            memberId = 1L,
            token = token,
            title = "출발 시간 안내",
            body = "교통시간이 15분 늘었습니다.",
            data = data,
            fcmMessageId = "projects/nolate/messages/123",
        ))

        assertEquals(1L, history.memberId)
        assertEquals(10L, history.deviceTokenId)
        assertEquals("android-emulator", history.deviceId)
        assertEquals(PushPlatform.ANDROID, history.platform)
        assertEquals(13L, history.scheduleId)
        assertEquals("logical:history-source", history.logicalEventKey)
        assertEquals(17L, history.categoryId)
        assertEquals(19L, history.calendarId)
        assertEquals("SCHEDULE_TRAFFIC", history.payloadType)
        assertEquals(PushSendStatus.SUCCESS, history.status)
        assertEquals("projects/nolate/messages/123", history.fcmMessageId)
        assertEquals(Instant.parse("2026-06-18T00:00:00Z"), history.sentAt)
        assertEquals(ObjectMapper().writeValueAsString(data), history.dataJson)
        verify(repository).save(any<PushSendHistory>())
    }

    @Test
    fun `토큰이 없으면 NO_TOKEN 이력을 저장한다`() {
        allowActiveRecipient()
        whenever(
            authorizationValidator.canDispatch(
                memberId = 1L,
                scheduleId = 13L,
                categoryId = null,
                payloadType = "SCHEDULE_DEPARTURE_REMINDER",
                calendarId = null,
            )
        ).thenReturn(true)
        whenever(repository.save(any<PushSendHistory>())).thenAnswer { it.arguments[0] }
        val history = requireNotNull(service.recordNoToken(
            memberId = 1L,
            title = "출발 시간 안내",
            body = "등록된 토큰 없음",
            data = mapOf("type" to "SCHEDULE_DEPARTURE_REMINDER", "scheduleId" to "13"),
        ))

        assertEquals(PushSendStatus.NO_TOKEN, history.status)
        assertEquals(13L, history.scheduleId)
        assertEquals("SCHEDULE_DEPARTURE_REMINDER", history.payloadType)
        assertEquals("NO_TOKEN", history.errorCode)
    }

    @Test
    fun `withdrawn recipient history write is a terminal no-op`() {
        whenever(memberRepository.findByIdForUpdate(1L)).thenReturn(
            Member(
                id = 1L,
                name = "withdrawn",
                password = "",
                email = "withdrawn@example.com",
            ).apply { softDelete() }
        )

        val history = service.recordNoToken(
            memberId = 1L,
            title = "PRIVATE TITLE",
            body = "PRIVATE BODY",
            data = mapOf("private" to "PRIVATE JSON"),
        )

        assertEquals(null, history)
        verify(repository, never()).save(any<PushSendHistory>())
    }

    @Test
    fun `history query hides dormant sharing payload and keeps ordinary notification`() {
        val hidden = history(
            id = 1L,
            payloadType = "SCHEDULE_SHARE_RECEIVED",
            title = "private shared title",
        )
        val ordinary = history(
            id = 2L,
            payloadType = "GENERAL",
            title = "ordinary title",
        )
        whenever(repository.findAllByMemberIdOrderBySentAtDesc(any(), any()))
            .thenReturn(listOf(hidden, ordinary))
        whenever(
            authorizationValidator.canDispatch(
                memberId = 1L,
                scheduleId = null,
                categoryId = null,
                payloadType = "SCHEDULE_SHARE_RECEIVED",
                calendarId = null,
            )
        ).thenReturn(false)
        whenever(
            authorizationValidator.canDispatch(
                memberId = 1L,
                scheduleId = null,
                categoryId = null,
                payloadType = "GENERAL",
                calendarId = null,
            )
        ).thenReturn(true)

        val result = service.getRecentByMember(1L, 10)

        assertEquals(listOf("ordinary title"), result.map { it.title })
    }

    private fun allowActiveRecipient() {
        whenever(memberRepository.findByIdForUpdate(1L)).thenReturn(
            Member(
                id = 1L,
                name = "active",
                password = "Password1!",
                email = "active@example.com",
            )
        )
    }

    private fun history(
        id: Long,
        payloadType: String,
        title: String,
    ) = PushSendHistory(
        id = id,
        memberId = 1L,
        payloadType = payloadType,
        title = title,
        body = "body",
        dataJson = "{}",
        status = PushSendStatus.SUCCESS,
        sentAt = Instant.parse("2026-06-18T00:00:00Z"),
    )
}
