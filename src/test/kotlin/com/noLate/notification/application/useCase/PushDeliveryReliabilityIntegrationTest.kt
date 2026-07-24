package com.noLate.notification.application.useCase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationWriter
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDeliveryWriter
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.PushEventOutboxWriter
import com.noLate.notification.application.service.deliveryDeviceKey
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@DataJpaTest
@Import(
    NotificationTokenService::class,
    NotificationTokenWriter::class,
    PushSendHistoryService::class,
    AppNotificationService::class,
    AppNotificationWriter::class,
    PushDeliveryService::class,
    PushDeliveryWriter::class,
    PushEventOutboxService::class,
    PushEventOutboxWriter::class,
    NotificationUseCase::class,
    PushDeliveryReliabilityTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:push-delivery-reliability;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PushDeliveryReliabilityIntegrationTest @Autowired constructor(
    private val notificationUseCase: NotificationUseCase,
    private val tokenService: NotificationTokenService,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val inboxRepository: AppNotificationRepository,
    private val historyRepository: PushSendHistoryRepository,
    private val pushClient: RecordingReliabilityPushClient,
    private val transactionManager: PlatformTransactionManager,
    private val appNotificationService: AppNotificationService,
    private val pushDeliveryService: PushDeliveryService,
    private val pushSendHistoryService: PushSendHistoryService,
    private val pushEventOutboxService: PushEventOutboxService,
) {

    @BeforeEach
    fun resetProvider() {
        pushClient.reset()
        deliveryRepository.deleteAll()
        historyRepository.deleteAll()
        inboxRepository.deleteAll()
        tokenRepository.deleteAll()
    }

    @Test
    fun `같은 inbox 이벤트를 반복 실행해도 성공 기기에는 다시 보내지 않는다`() {
        val memberId = 501L
        register(memberId, "device-1", "success-token")

        val first = send(memberId, "repeat-event")
        val second = send(memberId, "repeat-event")

        assertEquals(1, first.sentCount)
        assertEquals(0, second.attemptedCount)
        assertEquals(1, second.alreadyDeliveredCount)
        assertEquals(1, pushClient.attempts("success-token"))
        assertEquals(1, inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).size)
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(memberId).single().status)
    }

    @Test
    fun `두 기기 중 하나가 실패하면 성공 기기는 유지하고 실패 기기만 재시도한다`() {
        val memberId = 502L
        register(memberId, "device-1", "stable-token")
        register(memberId, "device-2", "retry-token")
        pushClient.failOnce("retry-token")

        val first = send(memberId, "partial-event")
        assertEquals(1, first.sentCount)
        assertEquals(1, first.failedCount)
        assertEquals(
            setOf(PushDeliveryStatus.SUCCESS, PushDeliveryStatus.FAILED),
            deliveries(memberId).map { it.status }.toSet(),
        )
        val failedDelivery = deliveries(memberId).single { it.status == PushDeliveryStatus.FAILED }
        assertFalse(failedDelivery.errorMessage.orEmpty().contains("retry-token"))
        val failedHistory = historyRepository.findAll().single { it.status == PushSendStatus.FAILED }
        assertFalse(failedHistory.errorMessage.orEmpty().contains("retry-token"))

        val second = send(memberId, "partial-event")

        assertEquals(1, second.sentCount)
        assertEquals(1, second.alreadyDeliveredCount)
        assertEquals(1, pushClient.attempts("stable-token"))
        assertEquals(2, pushClient.attempts("retry-token"))
        assertEquals(
            listOf(1, 2),
            deliveries(memberId).sortedBy { it.deviceFingerprint }.map { it.attemptCount },
        )
        assertEquals(setOf(PushDeliveryStatus.SUCCESS), deliveries(memberId).map { it.status }.toSet())
    }

    @Test
    fun `manifest 생성 후 첫 claim 전에 종료되어도 모든 PENDING 기기를 다음 실행이 보낸다`() {
        val memberId = 507L
        register(memberId, "device-1", "pending-token-1")
        register(memberId, "device-2", "pending-token-2")
        val data = pushData()
        val inbox = appNotificationService.recordWithResult(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = data,
            deduplicationKey = "manifest-before-claim-event",
        )
        val eventKey = inbox.notification.logicalEventKey

        pushDeliveryService.prepareManifest(
            memberId = memberId,
            eventKey = eventKey,
            tokens = tokenRepository.findAllByMemberId(memberId),
            data = data,
        )

        val pending = deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(memberId, eventKey)
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == PushDeliveryStatus.PENDING })
        assertTrue(pending.all { it.attemptCount == 0 && it.firstAttemptedAt == null })

        val resumed = send(memberId, "manifest-before-claim-event")

        assertEquals(2, resumed.sentCount)
        assertEquals(2, resumed.attemptedCount)
        assertEquals(1, pushClient.attempts("pending-token-1"))
        assertEquals(1, pushClient.attempts("pending-token-2"))
        assertTrue(deliveries(memberId).all { it.status == PushDeliveryStatus.SUCCESS })
    }

    @Test
    fun `inbox 생성 직후 종료되어 manifest가 없어도 다음 실행이 전체 manifest를 복구한다`() {
        val memberId = 510L
        register(memberId, "device-1", "inbox-crash-token-1")
        register(memberId, "device-2", "inbox-crash-token-2")
        val inbox = appNotificationService.recordWithResult(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = pushData(),
            deduplicationKey = "inbox-before-manifest-event",
        ).notification
        assertTrue(
            deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                memberId,
                inbox.logicalEventKey,
            ).isEmpty()
        )

        val resumed = send(memberId, "inbox-before-manifest-event")

        assertEquals(2, resumed.sentCount)
        assertEquals(2, deliveries(memberId).size)
        assertTrue(deliveries(memberId).all { it.status == PushDeliveryStatus.SUCCESS })
    }

    @Test
    fun `manifest 뒤 token 소유권이 이동하면 stale recipient에게 provider 호출하지 않는다`() {
        val memberId = 511L
        val nextOwner = 512L
        val rawToken = "ownership-transfer-token"
        register(memberId, "old-device", rawToken)
        val staleToken = tokenRepository.findAllByMemberId(memberId).single()
        val prepared = pushEventOutboxService.prepare(
            memberId = memberId,
            title = "소유권 경합",
            body = "이전 회원에게 보내면 안 됩니다.",
            data = pushData(),
            deduplicationKey = "ownership-transfer-event",
            persistInInbox = true,
            tokens = listOf(staleToken),
            fence = null,
        )

        register(nextOwner, "new-device", rawToken)
        val claim = pushDeliveryService.claim(
            memberId = memberId,
            eventKey = prepared.logicalEventKey,
            token = staleToken,
        )

        assertEquals(
            com.noLate.notification.application.service.PushDeliveryClaimOutcome.SUPERSEDED,
            claim.outcome,
        )
        assertEquals(0, pushClient.attempts(rawToken))
        assertEquals(
            PushDeliveryStatus.SUPERSEDED,
            deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                memberId,
                prepared.logicalEventKey,
            ).single().status,
        )
        assertEquals(nextOwner, tokenRepository.findAll().single().memberId)
    }

    @Test
    fun `invalid 응답 사이 token 소유권이 이동하면 새 소유자의 row를 삭제하지 않는다`() {
        val oldOwner = 513L
        val newOwner = 514L
        val rawToken = "invalid-race-token"
        register(oldOwner, "old-device", rawToken)
        pushClient.invalidateWithCallback(rawToken) {
            register(newOwner, "new-device", rawToken)
        }

        val result = send(oldOwner, "invalid-race-event")

        assertEquals(1, result.failedCount)
        assertEquals(0, result.removedTokenCount)
        val current = tokenRepository.findAll().single()
        assertEquals(newOwner, current.memberId)
        assertEquals(rawToken, current.token)
        assertEquals(PushDeliveryStatus.INVALID_TOKEN, deliveries(oldOwner).single().status)
    }

    @Test
    fun `첫 기기 호출 뒤 종료되면 DISPATCHING은 억제하고 남은 PENDING 기기만 보낸다`() {
        val memberId = 508L
        val firstToken = "accepted-first-token"
        val secondToken = "not-yet-attempted-token"
        register(memberId, "device-1", firstToken)
        register(memberId, "device-2", secondToken)
        pushClient.crashAfterAcceptedOnce(firstToken)

        assertThrows(SimulatedProcessExit::class.java) {
            send(memberId, "between-devices-event")
        }
        assertEquals(
            setOf(PushDeliveryStatus.DISPATCHING, PushDeliveryStatus.PENDING),
            deliveries(memberId).map { it.status }.toSet(),
        )
        assertEquals(1, pushClient.attempts(firstToken))
        assertEquals(0, pushClient.attempts(secondToken))

        val resumed = send(memberId, "between-devices-event")

        assertEquals(1, resumed.sentCount)
        assertEquals(1, resumed.ambiguousCount)
        assertEquals(1, resumed.attemptedCount)
        assertEquals(1, pushClient.attempts(firstToken))
        assertEquals(1, pushClient.attempts(secondToken))
        assertEquals(
            setOf(PushDeliveryStatus.DISPATCHING, PushDeliveryStatus.SUCCESS),
            deliveries(memberId).map { it.status }.toSet(),
        )
    }

    @Test
    fun `같은 실제 deviceId의 중복 token row는 provider를 한 번만 호출한다`() {
        val memberId = 509L
        register(memberId, "same-device", "legacy-token-a")
        val persisted = tokenRepository.findAllByMemberId(memberId).single()
        val duplicateTokenService = mock<NotificationTokenService>()
        whenever(duplicateTokenService.getTokensByMember(memberId)).thenReturn(
            listOf(persisted, persisted)
        )
        val useCase = NotificationUseCase(
            notificationTokenService = duplicateTokenService,
            pushClient = pushClient,
            pushSendHistoryService = pushSendHistoryService,
            appNotificationService = appNotificationService,
            pushDeliveryService = pushDeliveryService,
        )

        val result = useCase.sendToMember(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = pushData(),
            inboxDeduplicationKey = "duplicate-device-event",
        )

        assertEquals(2, result.requestedCount)
        assertEquals(1, result.attemptedCount)
        assertEquals(1, result.sentCount)
        assertEquals(1, result.alreadyDeliveredCount)
        assertEquals(1, pushClient.attempts("legacy-token-a"))
        assertEquals(1, deliveries(memberId).size)
    }

    @Test
    fun `platform 변경은 동일 member device의 delivery identity를 바꾸지 않는다`() {
        val memberId = 515L
        val android = NotificationDeviceToken(
            memberId = memberId,
            deviceId = "mutable-platform-device",
            platform = PushPlatform.UNKNOWN,
            token = "platform-token-before",
        )
        val ios = NotificationDeviceToken(
            memberId = memberId,
            deviceId = "mutable-platform-device",
            platform = PushPlatform.IOS,
            token = "platform-token-after",
        )

        assertEquals(
            android.deliveryDeviceKey(memberId),
            ios.deliveryDeviceKey(memberId),
        )
    }

    @Test
    fun `provider 수락 직후 로컬 기록 전 종료되면 DISPATCHING 경계가 재전송을 막는다`() {
        val memberId = 503L
        val token = "accepted-before-crash-token"
        register(memberId, "device-1", token)
        pushClient.crashAfterAcceptedOnce(token)

        assertThrows(SimulatedProcessExit::class.java) {
            send(memberId, "crash-window-event")
        }

        val afterCrash = deliveries(memberId).single()
        assertEquals(PushDeliveryStatus.DISPATCHING, afterCrash.status)
        assertEquals(1, pushClient.attempts(token))

        val retry = send(memberId, "crash-window-event")

        assertEquals(0, retry.attemptedCount)
        assertEquals(1, retry.ambiguousCount)
        assertEquals(1, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveries(memberId).single().status)
    }

    @Test
    fun `provider 성공 후 호출자 transaction이 rollback되어도 성공 기기를 다시 보내지 않는다`() {
        val memberId = 505L
        val token = "outer-rollback-token"
        register(memberId, "device-1", token)

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            val first = send(memberId, "outer-rollback-event")
            assertEquals(1, first.sentCount)
            transaction.setRollbackOnly()
        }

        val retry = send(memberId, "outer-rollback-event")

        assertEquals(0, retry.attemptedCount)
        assertEquals(1, retry.alreadyDeliveredCount)
        assertEquals(1, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(memberId).single().status)
    }

    @Test
    fun `provider 수락 여부가 모호한 예외는 UNKNOWN으로 남기고 자동 재전송하지 않는다`() {
        val memberId = 506L
        val token = "unknown-outcome-sensitive-token"
        register(memberId, "device-1", token)
        pushClient.unknownAfterAcceptedOnce(token)

        val first = send(memberId, "unknown-outcome-event")
        val second = send(memberId, "unknown-outcome-event")

        assertEquals(1, first.ambiguousCount)
        assertEquals(0, first.failedCount)
        assertEquals(0, second.attemptedCount)
        assertEquals(1, second.ambiguousCount)
        assertEquals(1, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveries(memberId).single().status)
        val history = historyRepository.findAll().single()
        assertEquals(PushSendStatus.UNKNOWN, history.status)
        assertFalse(history.errorMessage.orEmpty().contains(token))
    }

    @Test
    fun `무효 토큰은 제거하고 원문 토큰 없이 결과를 관측한다`() {
        val memberId = 504L
        val token = "sensitive-invalid-token"
        register(memberId, "device-1", token)
        pushClient.invalidate(token)

        val result = send(memberId, "invalid-token-event")

        assertEquals(1, result.failedCount)
        assertEquals(1, result.removedTokenCount)
        assertEquals(1, result.invalidTokenCount)
        assertEquals(1, result.durablyHandledCount)
        assertEquals(0, tokenRepository.findAllByMemberId(memberId).size)
        val delivery = deliveries(memberId).single()
        assertEquals(PushDeliveryStatus.INVALID_TOKEN, delivery.status)
        assertFalse(delivery.errorMessage.orEmpty().contains(token))
        val history = historyRepository.findAll().single()
        assertEquals(PushSendStatus.INVALID_TOKEN, history.status)
        assertFalse(history.errorMessage.orEmpty().contains(token))
    }

    private fun send(memberId: Long, key: String): NotificationSendResult =
        notificationUseCase.sendToMember(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = pushData(),
            inboxDeduplicationKey = key,
        )

    private fun pushData() = mapOf(
        "type" to "SCHEDULE_DEPARTURE_REMINDER",
        "scheduleId" to "7001",
    )

    private fun register(memberId: Long, deviceId: String, token: String) {
        tokenService.registerToken(
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = token,
        )
    }

    private fun deliveries(memberId: Long) =
        inboxRepository.findAllByMemberIdOrderByIdDesc(memberId)
            .single()
            .logicalEventKey
            .let { deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(memberId, it) }
}

@TestConfiguration
class PushDeliveryReliabilityTestConfig {
    @Bean
    fun reliabilityClock(): Clock = Clock.fixed(
        Instant.parse("2026-07-24T03:00:00Z"),
        ZoneOffset.UTC,
    )

    @Bean
    fun reliabilityObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean
    fun reliabilityPushClient(): RecordingReliabilityPushClient =
        RecordingReliabilityPushClient()
}

class RecordingReliabilityPushClient : PushClient {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val failOnce = ConcurrentHashMap.newKeySet<String>()
    private val crashOnce = ConcurrentHashMap.newKeySet<String>()
    private val unknownOnce = ConcurrentHashMap.newKeySet<String>()
    private val invalid = ConcurrentHashMap.newKeySet<String>()
    private val invalidCallbacks = ConcurrentHashMap<String, () -> Unit>()

    fun reset() {
        attempts.clear()
        failOnce.clear()
        crashOnce.clear()
        unknownOnce.clear()
        invalid.clear()
        invalidCallbacks.clear()
    }

    fun failOnce(token: String) {
        failOnce += token
    }

    fun crashAfterAcceptedOnce(token: String) {
        crashOnce += token
    }

    fun unknownAfterAcceptedOnce(token: String) {
        unknownOnce += token
    }

    fun invalidate(token: String) {
        invalid += token
    }

    fun invalidateWithCallback(token: String, callback: () -> Unit) {
        invalid += token
        invalidCallbacks[token] = callback
    }

    fun attempts(token: String): Int = attempts[token]?.get() ?: 0

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        val attempt = attempts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        if (invalid.contains(token)) {
            invalidCallbacks.remove(token)?.invoke()
            throw InvalidPushTokenException(token)
        }
        if (crashOnce.remove(token)) {
            // provider가 요청을 수락했지만 애플리케이션이 응답을 기록하기 전에 종료된 상황이다.
            throw SimulatedProcessExit()
        }
        if (unknownOnce.remove(token)) {
            throw IllegalStateException("transport failed after accepting token=$token")
        }
        if (failOnce.remove(token)) {
            throw ConfirmedPushDeliveryException("provider rejected token=$token")
        }
        return PushSendResult("message-$attempt")
    }
}

class SimulatedProcessExit : Error("simulated process exit after provider acceptance")
