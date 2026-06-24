package com.noLate.notification.application

import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ExtendWith(SpringExtension::class)
@Transactional   // 각 테스트마다 롤백
class NotificationTokenServiceIntegrationTest @Autowired constructor(
    private val notificationTokenService: NotificationTokenService,
    private val notificationDeviceTokenRepository: NotificationDeviceTokenRepository
) {

    @Test
    fun `deviceId 기반 등록시 기존 레코드가 있으면 갱신된다`() {
        // given
        val memberId = 900_001L
        val deviceId = "dev-1"

        // 첫 등록
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "old-token"
        )

        assertEquals(1, notificationDeviceTokenRepository.findAllByMemberId(memberId).size)

        // when - 같은 deviceId로 새 토큰 등록 (플랫폼도 변경)
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = "new-token"
        )

        // then - 레코드 수는 그대로 1, 값만 갱신
        val all = notificationDeviceTokenRepository.findAllByMemberId(memberId)
        assertEquals(1, all.size)

        val saved = all.first()
        assertEquals(memberId, saved.memberId)
        assertEquals(deviceId, saved.deviceId)
        assertEquals(PushPlatform.IOS, saved.platform)
        assertEquals("new-token", saved.token)
    }

    @Test
    fun `deviceId 없이 여러 토큰을 등록하면 여러 레코드가 생성된다`() {
        // given
        val memberId = 900_002L

        // when
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = null,
            platform = PushPlatform.ANDROID,
            token = "token-1"
        )

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = null,
            platform = PushPlatform.IOS,
            token = "token-2"
        )

        // then
        val tokens = notificationDeviceTokenRepository.findAllByMemberId(memberId)
        assertEquals(2, tokens.size)

        val tokenValues = tokens.map { it.token }.toSet()
        assertTrue(tokenValues.contains("token-1"))
        assertTrue(tokenValues.contains("token-2"))
    }

    @Test
    fun `removeToken은 해당 memberId와 deviceId에 해당하는 토큰만 삭제한다`() {
        // given
        val memberId = 900_003L

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "dev-1",
            platform = PushPlatform.ANDROID,
            token = "t1"
        )
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "dev-2",
            platform = PushPlatform.IOS,
            token = "t2"
        )

        assertEquals(2, notificationDeviceTokenRepository.findAllByMemberId(memberId).size)

        // when - dev-1 삭제
        notificationTokenService.removeToken(memberId, "dev-1")

        // then
        val tokens = notificationDeviceTokenRepository.findAllByMemberId(memberId)
        assertEquals(1, tokens.size)
        assertEquals("dev-2", tokens.first().deviceId)
        assertEquals("t2", tokens.first().token)
    }

    @Test
    fun `removeAllTokensByMember는 해당 회원의 모든 토큰을 삭제한다`() {
        // given
        val memberId = 900_004L

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "d1",
            platform = PushPlatform.ANDROID,
            token = "t1"
        )
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "d2",
            platform = PushPlatform.IOS,
            token = "t2"
        )

        assertEquals(2, notificationDeviceTokenRepository.findAllByMemberId(memberId).size)

        // when
        notificationTokenService.removeAllTokensByMember(memberId)

        // then
        assertEquals(0, notificationDeviceTokenRepository.findAllByMemberId(memberId).size)
    }

    @Test
    fun `getTokensByMember는 해당 회원의 토큰만 조회한다`() {
        // given
        val memberId1 = 900_005L
        val memberId2 = 900_006L

        notificationTokenService.registerToken(
            memberId = memberId1,
            deviceId = "d1",
            platform = PushPlatform.ANDROID,
            token = "m1-t1"
        )
        notificationTokenService.registerToken(
            memberId = memberId1,
            deviceId = "d2",
            platform = PushPlatform.IOS,
            token = "m1-t2"
        )
        notificationTokenService.registerToken(
            memberId = memberId2,
            deviceId = "d3",
            platform = PushPlatform.ANDROID,
            token = "m2-t1"
        )

        // when
        val tokensForMember1 = notificationTokenService.getTokensByMember(memberId1)

        // then
        assertEquals(2, tokensForMember1.size)
        val tokenValues = tokensForMember1.map { it.token }.toSet()
        assertTrue(tokenValues.contains("m1-t1"))
        assertTrue(tokenValues.contains("m1-t2"))
    }

    @Test
    fun `같은 기기로 다른 회원이 로그인하면 토큰 소유권이 새 회원으로 이동한다`() {
        val previousMemberId = 900_007L
        val currentMemberId = 900_008L
        val deviceId = "shared-device"
        val token = "shared-token"

        notificationTokenService.registerToken(
            memberId = previousMemberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = token,
        )
        notificationTokenService.registerToken(
            memberId = currentMemberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = token,
        )

        assertTrue(notificationDeviceTokenRepository.findAllByMemberId(previousMemberId).isEmpty())
        val currentTokens = notificationDeviceTokenRepository.findAllByMemberId(currentMemberId)
        assertEquals(1, currentTokens.size)
        assertEquals(token, currentTokens.single().token)
        assertEquals(deviceId, currentTokens.single().deviceId)
    }
}
