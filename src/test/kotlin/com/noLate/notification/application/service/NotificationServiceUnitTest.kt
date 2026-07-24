// src/test/kotlin/com/swyp/notification/application/NotificationTokenServiceUnitTest.kt
package com.noLate.notification.application

import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class NotificationTokenServiceUnitTest {

    @Mock
    lateinit var notificationDeviceTokenRepository: NotificationDeviceTokenRepository

    private lateinit var notificationTokenService: NotificationTokenService

    @BeforeEach
    fun setUp() {
        notificationTokenService = NotificationTokenService(notificationDeviceTokenRepository)
    }

    @Test
    fun `deviceId가 있을 때 기존 엔티티가 존재하면 토큰과 플랫폼을 갱신한다`() {
        val memberId = 1L
        val deviceId = "device-1"

        val existing = NotificationDeviceToken(
            id = 10L,
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "old-token"
        )

        whenever(notificationDeviceTokenRepository.findAllByMemberIdAndDeviceId(memberId, deviceId))
            .thenReturn(listOf(existing))

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "new-token"
        )

        verify(notificationDeviceTokenRepository, times(1))
            .findAllByMemberIdAndDeviceId(memberId, deviceId)

        verify(notificationDeviceTokenRepository, times(1))
            .save(check {
                assertEquals(10L, it.id)
                assertEquals(memberId, it.memberId)
                assertEquals(deviceId, it.deviceId)
                assertEquals(PushPlatform.IOS, it.platform)
                assertEquals("new-token", it.token)
            })
    }

    @Test
    fun `deviceId가 있을 때 기존 엔티티가 없으면 새 엔티티를 저장한다`() {
        val memberId = 2L
        val deviceId = "device-2"

        whenever(notificationDeviceTokenRepository.findAllByMemberIdAndDeviceId(memberId, deviceId))
            .thenReturn(emptyList())

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "token-2"
        )

        verify(notificationDeviceTokenRepository, times(1))
            .findAllByMemberIdAndDeviceId(memberId, deviceId)

        verify(notificationDeviceTokenRepository, times(1))
            .save(check {
                assertEquals(memberId, it.memberId)
                assertEquals(deviceId, it.deviceId)
                assertEquals(PushPlatform.ANDROID, it.platform)
                assertEquals("token-2", it.token)
            })
    }

    @Test
    fun `deviceId가 null이고 같은 token이 없으면 새 엔티티를 저장한다`() {
        val memberId = 3L

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = null,
            platform = PushPlatform.WEB,
            token = "web-token"
        )

        verify(notificationDeviceTokenRepository, never())
            .findAllByMemberIdAndDeviceId(any(), any())

        verify(notificationDeviceTokenRepository, times(1))
            .save(check {
                assertEquals(memberId, it.memberId)
                assertEquals(null, it.deviceId)
                assertEquals(PushPlatform.WEB, it.platform)
                assertEquals("web-token", it.token)
            })
    }

    @Test
    fun `legacy 중복 token과 device row는 최신 member device 한 건으로 정리한다`() {
        val memberId = 7L
        val deviceId = "duplicate-device"
        val oldDeviceRow = NotificationDeviceToken(
            id = 10L,
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "old-token",
        )
        val newestDeviceRow = NotificationDeviceToken(
            id = 12L,
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "newer-token",
        )
        val conflictingTokenOwner = NotificationDeviceToken(
            id = 11L,
            memberId = 99L,
            deviceId = "other-device",
            platform = PushPlatform.IOS,
            token = "registered-token",
        )
        whenever(notificationDeviceTokenRepository.findAllByToken("registered-token"))
            .thenReturn(listOf(conflictingTokenOwner))
        whenever(notificationDeviceTokenRepository.findAllByDeviceId(deviceId))
            .thenReturn(listOf(oldDeviceRow, newestDeviceRow))
        whenever(notificationDeviceTokenRepository.findAllByMemberIdAndDeviceId(memberId, deviceId))
            .thenReturn(listOf(oldDeviceRow, newestDeviceRow))

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.WEB,
            token = "registered-token",
        )

        verify(notificationDeviceTokenRepository).deleteAll(
            check {
                assertEquals(setOf(10L, 11L), it.map(NotificationDeviceToken::id).toSet())
            }
        )
        verify(notificationDeviceTokenRepository).flush()
        verify(notificationDeviceTokenRepository).save(
            check {
                assertEquals(12L, it.id)
                assertEquals("registered-token", it.token)
                assertEquals(PushPlatform.WEB, it.platform)
            }
        )
    }

    @Test
    fun `removeToken은 해당 memberId와 deviceId에 해당하는 토큰만 삭제한다`() {
        val memberId = 4L
        val deviceId = "device-4"

        notificationTokenService.removeToken(memberId, deviceId)

        verify(notificationDeviceTokenRepository, times(1))
            .deleteByMemberIdAndDeviceId(memberId, deviceId)
    }

    @Test
    fun `removeAllTokensByMember는 해당 회원의 모든 토큰을 삭제한다`() {
        val memberId = 5L

        notificationTokenService.removeAllTokensByMember(memberId)

        verify(notificationDeviceTokenRepository, times(1))
            .deleteAllByMemberId(memberId)
    }

    @Test
    fun `removeTokenValue는 회원과 토큰 값으로 무효 토큰을 삭제한다`() {
        notificationTokenService.removeTokenValue(5L, "invalid-token")

        verify(notificationDeviceTokenRepository)
            .deleteByMemberIdAndToken(5L, "invalid-token")
    }

    @Test
    fun `getTokensByMember는 해당 회원의 토큰 목록을 반환한다`() {
        val memberId = 6L
        val tokens = listOf(
            NotificationDeviceToken(
                id = 1L,
                memberId = memberId,
                deviceId = "d1",
                platform = PushPlatform.ANDROID,
                token = "t1"
            ),
            NotificationDeviceToken(
                id = 2L,
                memberId = memberId,
                deviceId = "d2",
                platform = PushPlatform.IOS,
                token = "t2"
            )
        )

        whenever(notificationDeviceTokenRepository.findAllByMemberId(memberId))
            .thenReturn(tokens)

        val result = notificationTokenService.getTokensByMember(memberId)

        verify(notificationDeviceTokenRepository, times(1))
            .findAllByMemberId(memberId)

        assertEquals(2, result.size)
        assertEquals("t1", result[0].token)
        assertEquals("t2", result[1].token)
    }
}
