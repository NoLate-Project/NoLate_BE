package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class PushSendHistoryServiceTest {

    @Mock
    lateinit var repository: PushSendHistoryRepository

    private lateinit var service: PushSendHistoryService

    @BeforeEach
    fun setUp() {
        service = PushSendHistoryService(
            repository = repository,
            objectMapper = ObjectMapper(),
            clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC),
        )
        whenever(repository.save(any<PushSendHistory>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `성공 이력은 일정 ID와 payload type, FCM message id를 저장한다`() {
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
            "trafficChangeMinutes" to "15",
        )

        val history = service.recordSuccess(
            memberId = 1L,
            token = token,
            title = "출발 시간 안내",
            body = "교통시간이 15분 늘었습니다.",
            data = data,
            fcmMessageId = "projects/nolate/messages/123",
        )

        assertEquals(1L, history.memberId)
        assertEquals(10L, history.deviceTokenId)
        assertEquals("android-emulator", history.deviceId)
        assertEquals(PushPlatform.ANDROID, history.platform)
        assertEquals(13L, history.scheduleId)
        assertEquals("SCHEDULE_TRAFFIC", history.payloadType)
        assertEquals(PushSendStatus.SUCCESS, history.status)
        assertEquals("projects/nolate/messages/123", history.fcmMessageId)
        assertEquals(Instant.parse("2026-06-18T00:00:00Z"), history.sentAt)
        assertEquals("""{"type":"SCHEDULE_TRAFFIC","scheduleId":"13","trafficChangeMinutes":"15"}""", history.dataJson)
        verify(repository).save(any<PushSendHistory>())
    }

    @Test
    fun `토큰이 없으면 NO_TOKEN 이력을 저장한다`() {
        val history = service.recordNoToken(
            memberId = 1L,
            title = "출발 시간 안내",
            body = "등록된 토큰 없음",
            data = mapOf("type" to "SCHEDULE_DEPARTURE_REMINDER", "scheduleId" to "13"),
        )

        assertEquals(PushSendStatus.NO_TOKEN, history.status)
        assertEquals(13L, history.scheduleId)
        assertEquals("SCHEDULE_DEPARTURE_REMINDER", history.payloadType)
        assertEquals("NO_TOKEN", history.errorCode)
    }
}
