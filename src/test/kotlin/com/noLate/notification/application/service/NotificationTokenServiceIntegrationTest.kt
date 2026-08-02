package com.noLate.notification.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ExtendWith(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationTokenServiceIntegrationTest @Autowired constructor(
    private val notificationTokenService: NotificationTokenService,
    private val notificationDeviceTokenRepository: NotificationDeviceTokenRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun cleanTokens() {
        notificationDeviceTokenRepository.deleteAll()
    }

    @Test
    fun `deviceId 기반 등록시 기존 레코드가 있으면 갱신된다`() {
        // given
        val memberId = 900_001L
        val deviceId = "dev-1"

        // 첫 등록
        registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = "old-token"
        )

        assertEquals(1, notificationDeviceTokenRepository.findAllByMemberId(memberId).size)

        // when - 같은 deviceId로 새 토큰 등록 (플랫폼도 변경)
        registerToken(
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
        registerToken(
            memberId = memberId,
            deviceId = null,
            platform = PushPlatform.ANDROID,
            token = "token-1"
        )

        registerToken(
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
    fun `deviceId가 없어도 같은 token 재등록은 한 레코드로 수렴한다`() {
        val memberId = 900_009L

        registerToken(
            memberId = memberId,
            deviceId = null,
            platform = PushPlatform.ANDROID,
            token = "same-token-without-device",
        )
        registerToken(
            memberId = memberId,
            deviceId = null,
            platform = PushPlatform.IOS,
            token = "same-token-without-device",
        )

        val tokens = notificationDeviceTokenRepository.findAllByMemberId(memberId)
        assertEquals(1, tokens.size)
        assertEquals(PushPlatform.IOS, tokens.single().platform)
        assertEquals("same-token-without-device", tokens.single().token)
    }

    @Test
    fun `removeToken은 해당 memberId와 deviceId에 해당하는 토큰만 삭제한다`() {
        // given
        val memberId = 900_003L

        registerToken(
            memberId = memberId,
            deviceId = "dev-1",
            platform = PushPlatform.ANDROID,
            token = "t1"
        )
        registerToken(
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

        registerToken(
            memberId = memberId,
            deviceId = "d1",
            platform = PushPlatform.ANDROID,
            token = "t1"
        )
        registerToken(
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

        registerToken(
            memberId = memberId1,
            deviceId = "d1",
            platform = PushPlatform.ANDROID,
            token = "m1-t1"
        )
        registerToken(
            memberId = memberId1,
            deviceId = "d2",
            platform = PushPlatform.IOS,
            token = "m1-t2"
        )
        registerToken(
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
        val previousToken = "previous-account-token"
        val currentToken = "current-account-token"

        registerToken(
            memberId = previousMemberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = previousToken,
        )
        registerToken(
            memberId = currentMemberId,
            deviceId = deviceId,
            platform = PushPlatform.IOS,
            token = currentToken,
        )

        assertTrue(notificationDeviceTokenRepository.findAllByMemberId(previousMemberId).isEmpty())
        val currentTokens = notificationDeviceTokenRepository.findAllByMemberId(currentMemberId)
        assertEquals(1, currentTokens.size)
        assertEquals(currentToken, currentTokens.single().token)
        assertEquals(deviceId, currentTokens.single().deviceId)
        assertEquals(PushPlatform.IOS, currentTokens.single().platform)
    }

    @Test
    fun `동시 token 등록은 fingerprint unique 충돌 뒤 하나의 최종 소유권으로 수렴한다`() {
        val token = "Case-Sensitive-Concurrent-Token"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(2)

        listOf(900_101L, 900_102L).forEach { memberId ->
            executor.submit {
                ready.countDown()
                start.await()
                runCatching {
                    registerToken(
                        memberId = memberId,
                        deviceId = "device-$memberId",
                        platform = PushPlatform.ANDROID,
                        token = token,
                    )
                }.onFailure(failures::add)
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.javaClass.simpleName })
        val rows = notificationDeviceTokenRepository.findAll()
        assertEquals(1, rows.size)
        assertTrue(rows.single().memberId in setOf(900_101L, 900_102L))
        assertEquals(token, rows.single().token)
    }

    @Test
    fun `동일 device의 서로 다른 token 동시 등록도 전역 단일 owner로 수렴한다`() {
        val deviceId = "globally-owned-installation"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(2)

        listOf(
            900_201L to "account-a-token",
            900_202L to "account-b-token",
        ).forEach { (memberId, token) ->
            executor.submit {
                ready.countDown()
                start.await()
                runCatching {
                    registerToken(
                        memberId = memberId,
                        deviceId = deviceId,
                        platform = PushPlatform.ANDROID,
                        token = token,
                    )
                }.onFailure(failures::add)
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.javaClass.simpleName })
        val rows = notificationDeviceTokenRepository.findAll()
        assertEquals(1, rows.size)
        assertEquals(deviceId, rows.single().deviceId)
        assertTrue(rows.single().memberId in setOf(900_201L, 900_202L))
        assertTrue(rows.single().token in setOf("account-a-token", "account-b-token"))
    }

    @Test
    fun `old invalid response cannot delete device ownership transferred to another member`() {
        registerToken(
            memberId = 900_301L,
            deviceId = "transfer-before-invalid",
            platform = PushPlatform.ANDROID,
            token = "old-invalid-token",
        )
        val stale = notificationDeviceTokenRepository.findAllByMemberId(900_301L).single()

        registerToken(
            memberId = 900_302L,
            deviceId = "transfer-before-invalid",
            platform = PushPlatform.IOS,
            token = "new-valid-token",
        )

        val removed = notificationTokenService.removeTokenByOwnership(
            memberId = 900_301L,
            tokenId = requireNotNull(stale.id),
            tokenFingerprint = stale.tokenFingerprint,
            ownershipVersion = stale.ownershipVersion,
        )

        assertFalse(removed)
        assertTrue(notificationDeviceTokenRepository.findAllByMemberId(900_301L).isEmpty())
        assertEquals(
            "new-valid-token",
            notificationDeviceTokenRepository.findAllByMemberId(900_302L).single().token,
        )
    }

    @Test
    fun `registration write fails closed when authenticated target member does not exist`() {
        val error = assertThrows<BusinessException> {
            notificationTokenService.registerToken(
                memberId = 999_999L,
                deviceId = "missing-member-device",
                platform = PushPlatform.ANDROID,
                token = "missing-member-token",
                accessTokenIssuedAt = TEST_ISSUED_AT,
                accessTokenSessionGeneration = 0,
            )
        }

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode)
        assertTrue(notificationDeviceTokenRepository.findAll().isEmpty())
    }

    @Test
    fun `ACK capability v1 registration persists an explicit measurable client contract`() {
        val memberId = 900_401L

        registerToken(
            memberId = memberId,
            deviceId = "ack-capable-device",
            platform = PushPlatform.ANDROID,
            token = "ack-capable-token",
            deliveryAckCapabilityVersion = 1,
        )

        assertEquals(
            1,
            notificationDeviceTokenRepository.findAllByMemberId(memberId)
                .single()
                .deliveryAckCapabilityVersion,
        )
    }

    @Test
    fun `unsupported ACK capability version is rejected before persistence`() {
        val error = assertThrows<BusinessException> {
            notificationTokenService.registerToken(
                memberId = 900_402L,
                deviceId = "future-capability-device",
                platform = PushPlatform.IOS,
                token = "future-capability-token",
                accessTokenIssuedAt = TEST_ISSUED_AT,
                accessTokenSessionGeneration = 0,
                deliveryAckCapabilityVersion = 2,
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        assertTrue(notificationDeviceTokenRepository.findAll().isEmpty())
    }

    @Test
    fun `ACK capability registration requires a stable device identity`() {
        val error = assertThrows<BusinessException> {
            notificationTokenService.registerToken(
                memberId = 900_403L,
                deviceId = null,
                platform = PushPlatform.ANDROID,
                token = "capability-without-device-token",
                accessTokenIssuedAt = TEST_ISSUED_AT,
                accessTokenSessionGeneration = 0,
                deliveryAckCapabilityVersion = 1,
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        assertTrue(notificationDeviceTokenRepository.findAll().isEmpty())
    }

    private fun registerToken(
        memberId: Long,
        deviceId: String?,
        platform: PushPlatform,
        token: String,
        deliveryAckCapabilityVersion: Int? = null,
    ) {
        ensureActiveMember(memberId)
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = platform,
            token = token,
            accessTokenIssuedAt = TEST_ISSUED_AT,
            accessTokenSessionGeneration = 0,
            deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
        )
    }

    private fun ensureActiveMember(memberId: Long) {
        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `member` WHERE id = ?",
            Long::class.java,
            memberId,
        ) ?: 0L
        if (exists != 0L) return
        jdbcTemplate.update(
            """
            INSERT INTO `member` (
                id, name, password, email, login_type, subscription_plan,
                curation_completed, session_generation, deleted
            ) VALUES (?, ?, ?, ?, 'COMMON', 'FREE', FALSE, 0, FALSE)
            """.trimIndent(),
            memberId,
            "member-$memberId",
            "Password1!",
            "push-fixture-$memberId@example.com",
        )
    }

    private companion object {
        val TEST_ISSUED_AT: Instant = Instant.parse("2026-07-24T03:00:00Z")
    }
}
