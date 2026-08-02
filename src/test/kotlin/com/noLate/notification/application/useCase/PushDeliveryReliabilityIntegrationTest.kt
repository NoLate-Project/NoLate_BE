package com.noLate.notification.application.useCase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationWriter
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDeliveryWriter
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.PushEventOutboxWriter
import com.noLate.notification.application.service.PushTokenProviderLeaseObserver
import com.noLate.notification.application.service.PushTokenProviderLeaseService
import com.noLate.notification.application.service.PushTokenProviderLeaseWriter
import com.noLate.notification.application.service.deliveryDeviceKey
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.domain.NotificationDeviceToken
import com.noLate.notification.domain.AppNotification
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushLogicalEventKey
import com.noLate.notification.domain.PushManifestState
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.domain.withPushAccountBinding
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.support.registerAuthenticatedPushToken
import com.noLate.notification.support.ensureActivePushMember
import com.noLate.notification.support.AllowAllPushRecipientAuthorizationTestConfig
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.springframework.transaction.support.TransactionSynchronizationManager

@DataJpaTest
@Import(
    NotificationTokenService::class,
    NotificationTokenRetirementService::class,
    NotificationTokenWriter::class,
    PushSendHistoryService::class,
    AppNotificationService::class,
    AppNotificationWriter::class,
    PushDeliveryService::class,
    PushDeliveryWriter::class,
    PushEventOutboxService::class,
    PushEventOutboxWriter::class,
    PushTokenProviderLeaseService::class,
    PushTokenProviderLeaseWriter::class,
    NotificationUseCase::class,
    PushDeliveryReliabilityTestConfig::class,
    AllowAllPushRecipientAuthorizationTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:push-delivery-reliability;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "notification.push-token.dispatch-lease-poll-millis=200",
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
    private val providerLeaseObserver: BlockingPushTokenProviderLeaseObserver,
    private val transactionManager: PlatformTransactionManager,
    private val appNotificationService: AppNotificationService,
    private val pushDeliveryService: PushDeliveryService,
    private val pushSendHistoryService: PushSendHistoryService,
    private val pushEventOutboxService: PushEventOutboxService,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun resetProvider() {
        providerLeaseObserver.release()
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
        register(memberId, "device-late", "late-after-completion-token")
        val second = send(memberId, "repeat-event")

        assertEquals(1, first.sentCount)
        assertEquals(0, second.attemptedCount)
        assertEquals(1, second.alreadyDeliveredCount)
        assertEquals(1, pushClient.attempts("success-token"))
        assertEquals(0, pushClient.attempts("late-after-completion-token"))
        assertEquals(1, inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).size)
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(memberId).single().status)
    }

    @Test
    fun `token ACK capability is frozen into the per-device delivery manifest`() {
        val memberId = 530L
        register(
            memberId = memberId,
            deviceId = "ack-capable-manifest-device",
            token = "ack-capable-manifest-token",
            deliveryAckCapabilityVersion = 1,
        )

        val result = send(memberId, "ack-capability-manifest")

        assertEquals(1, result.sentCount)
        assertEquals(1, deliveries(memberId).single().deliveryAckCapabilityVersion)
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
    fun `같은 event 재시도는 현재 입력이 바뀌어도 최초 payload와 의미 결정을 유지한다`() {
        val memberId = 516L
        register(memberId, "device-a", "immutable-success-token")
        register(memberId, "device-b", "immutable-retry-token")
        pushClient.failOnce("immutable-retry-token")
        val originalData = mapOf(
            "type" to "SCHEDULE_DEPARTURE_REMINDER",
            "scheduleId" to "7001",
            "decision" to "ADVANCE_NOTICE",
            "stage" to "ADVANCE_NOTICE",
            "etaMinutes" to "40",
            "delayMinutes" to "10",
        )

        val first = notificationUseCase.sendToMember(
            memberId = memberId,
            title = "10분 일찍 출발하세요",
            body = "최초 ETA 40분",
            data = originalData,
            inboxDeduplicationKey = "immutable-payload-event",
        )
        val retried = notificationUseCase.sendToMember(
            memberId = memberId,
            title = "지금 출발하세요",
            body = "현재 ETA 25분",
            data = originalData + mapOf(
                "decision" to "DEPART_NOW",
                "stage" to "DEPART_NOW",
                "etaMinutes" to "25",
                "delayMinutes" to "0",
            ),
            inboxDeduplicationKey = "immutable-payload-event",
        )

        assertEquals(1, first.sentCount)
        assertEquals(1, first.retryableFailedCount)
        assertEquals(1, retried.sentCount)
        assertEquals(1, retried.alreadyDeliveredCount)
        val retryCalls = pushClient.calls("immutable-retry-token")
        assertEquals(2, retryCalls.size)
        assertTrue(retryCalls.all { it.title == "10분 일찍 출발하세요" })
        assertTrue(retryCalls.all { it.body == "최초 ETA 40분" })
        assertTrue(retryCalls.all { it.data["decision"] == "ADVANCE_NOTICE" })
        assertTrue(retryCalls.all { it.data["stage"] == "ADVANCE_NOTICE" })
        assertTrue(retryCalls.all { it.data["etaMinutes"] == "40" })
        assertTrue(retryCalls.all { it.data["delayMinutes"] == "10" })
        assertTrue(retryCalls.all { it.data["logicalEventKey"] != null })
        assertTrue(retryCalls.all { it.data["recipientMemberId"] == memberId.toString() })
    }

    @Test
    fun `frozen manifest는 삭제된 pending 기기를 terminal 처리하고 새 기기를 과거 event에 추가하지 않는다`() {
        val memberId = 517L
        register(memberId, "device-a", "manifest-success-token")
        register(memberId, "device-b", "manifest-pending-token")
        val prepared = pushEventOutboxService.prepare(
            memberId = memberId,
            title = "고정 manifest",
            body = "현재 수신자만",
            data = pushData(),
            deduplicationKey = "frozen-recipient-event",
            persistInInbox = true,
            fence = null,
        )
        val deliveryByToken = prepared.deliveryIds.associateBy { id ->
            deliveryRepository.findById(id).orElseThrow().tokenFingerprint
        }
        val successToken = tokenRepository.findAllByMemberId(memberId)
            .single { it.token == "manifest-success-token" }
        val successDeliveryId = requireNotNull(deliveryByToken[successToken.tokenFingerprint])
        val successClaim = pushDeliveryService.claim(
            memberId = memberId,
            eventKey = prepared.logicalEventKey,
            deliveryId = successDeliveryId,
        )
        val snapshot = requireNotNull(prepared.snapshot)
        val accepted = pushClient.sendToToken(
            token = requireNotNull(successClaim.providerToken),
            title = snapshot.title,
            body = snapshot.body,
            data = snapshot.data,
        )
        pushDeliveryService.markSuccess(successDeliveryId, accepted.messageId)

        tokenService.removeToken(memberId, "device-b")
        register(memberId, "device-c", "late-device-token")

        val resumed = notificationUseCase.redrivePersistedEvent(
            memberId,
            prepared.logicalEventKey,
        )

        assertEquals(0, resumed.attemptedCount)
        assertEquals(1, resumed.alreadyDeliveredCount)
        assertEquals(1, resumed.supersededCount)
        assertEquals(0, pushClient.attempts("manifest-pending-token"))
        assertEquals(0, pushClient.attempts("late-device-token"))
        assertEquals(2, deliveries(memberId).size)
        assertEquals(
            setOf(PushDeliveryStatus.SUCCESS, PushDeliveryStatus.SUPERSEDED),
            deliveries(memberId).map { it.status }.toSet(),
        )
    }

    @Test
    fun `완료된 zero-device event는 이후 등록 기기로 확장하지 않는다`() {
        val memberId = 518L

        val first = send(memberId, "zero-device-event")
        register(memberId, "device-after-event", "late-zero-device-token")
        val second = send(memberId, "zero-device-event")

        assertEquals(1, first.noDeviceEventCount)
        assertEquals(1, second.noDeviceEventCount)
        assertEquals(0, second.requestedCount)
        assertEquals(0, pushClient.attempts("late-zero-device-token"))
        assertTrue(deliveries(memberId).isEmpty())
    }

    @Test
    fun `비정상 committed OPEN 복구도 현재 기기를 과거 manifest에 붙이지 않는다`() {
        val memberId = 521L
        val dedupeKey = "committed-open-recovery"
        val eventKey = PushLogicalEventKey.deterministic(memberId, dedupeKey)
        val canonicalData = pushData().withPushAccountBinding(eventKey, memberId)
        inboxRepository.saveAndFlush(
            AppNotification(
                memberId = memberId,
                deduplicationKey = dedupeKey,
                logicalEventKey = eventKey,
                type = requireNotNull(canonicalData["type"]),
                scheduleId = canonicalData["scheduleId"]?.toLongOrNull(),
                title = "생성 중이던 이벤트",
                body = "현재 기기로 확장하면 안 됩니다.",
                dataJson = objectMapper.writeValueAsString(canonicalData),
                createdAt = Instant.parse("2026-07-24T03:00:00Z"),
                manifestState = PushManifestState.OPEN,
            )
        )
        register(memberId, "late-open-device", "late-open-token")

        val resumed = send(memberId, dedupeKey)

        assertEquals(1, resumed.noDeviceEventCount)
        assertEquals(0, resumed.requestedCount)
        assertEquals(0, pushClient.attempts("late-open-token"))
        val persisted = inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).single()
        assertEquals(PushManifestState.FROZEN, persisted.manifestState)
        assertEquals(0, persisted.manifestRecipientCount)
        assertTrue(deliveries(memberId).isEmpty())
    }

    @Test
    fun `manifest 생성 후 첫 claim 전에 종료되어도 모든 PENDING 기기를 다음 실행이 보낸다`() {
        val memberId = 507L
        register(memberId, "device-1", "pending-token-1")
        register(memberId, "device-2", "pending-token-2")
        val data = pushData()
        val prepared = pushEventOutboxService.prepare(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = data,
            deduplicationKey = "manifest-before-claim-event",
            persistInInbox = true,
            fence = null,
        )

        val pending = deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
            memberId,
            prepared.logicalEventKey,
        )
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
    fun `legacy inbox-only 이벤트는 다음 실행에서 현재 기기로 확장하지 않는다`() {
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

        assertEquals(0, resumed.sentCount)
        assertEquals(1, resumed.noDeviceEventCount)
        assertEquals(0, deliveries(memberId).size)
        assertEquals(0, pushClient.attempts("inbox-crash-token-1"))
        assertEquals(0, pushClient.attempts("inbox-crash-token-2"))
    }

    @Test
    fun `manifest 뒤 token 소유권이 이동하면 stale recipient에게 provider 호출하지 않는다`() {
        val memberId = 511L
        val nextOwner = 512L
        val rawToken = "ownership-transfer-token"
        register(memberId, "old-device", rawToken)
        val prepared = pushEventOutboxService.prepare(
            memberId = memberId,
            title = "소유권 경합",
            body = "이전 회원에게 보내면 안 됩니다.",
            data = pushData(),
            deduplicationKey = "ownership-transfer-event",
            persistInInbox = true,
            fence = null,
        )

        register(nextOwner, "new-device", rawToken)
        val claim = pushDeliveryService.claim(
            memberId = memberId,
            eventKey = prepared.logicalEventKey,
            deliveryId = prepared.deliveryIds.single(),
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
    fun `delivery claim 뒤 ownership transfer가 먼저 commit되면 provider를 호출하지 않고 terminal 처리한다`() {
        val previousOwner = 521L
        val currentOwner = 522L
        val deviceId = "claim-transfer-first-device"
        val previousToken = "claim-transfer-first-old-token"
        val currentToken = "claim-transfer-first-new-token"
        register(previousOwner, deviceId, previousToken)
        val gate = providerLeaseObserver.arm()
        val executor = Executors.newSingleThreadExecutor()
        val sendFuture = executor.submit<NotificationSendResult> {
            send(previousOwner, "claim-transfer-first-event")
        }

        try {
            assertTrue(gate.beforeLease.await(10, TimeUnit.SECONDS))
            register(currentOwner, deviceId, currentToken)
            gate.allowLease.countDown()
            val result = sendFuture.get(10, TimeUnit.SECONDS)

            assertEquals(0, result.attemptedCount)
            assertEquals(1, result.supersededCount)
            assertEquals(0, pushClient.attempts(previousToken))
            assertEquals(0, pushClient.attempts(currentToken))
            assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(previousOwner).single().status)
            val owner = tokenRepository.findAll().single()
            assertEquals(currentOwner, owner.memberId)
            assertEquals(currentToken, owner.token)
        } finally {
            gate.allowLease.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `provider lease가 먼저면 ownership transfer는 provider call 종료까지 기다린다`() {
        val previousOwner = 523L
        val currentOwner = 524L
        val deviceId = "provider-first-device"
        val previousToken = "provider-first-old-token"
        val currentToken = "provider-first-new-token"
        register(previousOwner, deviceId, previousToken)
        val providerGate = pushClient.block(previousToken)
        val executor = Executors.newFixedThreadPool(2)
        val sendFuture = executor.submit<NotificationSendResult> {
            send(previousOwner, "provider-first-event")
        }

        try {
            assertTrue(providerGate.entered.await(10, TimeUnit.SECONDS))
            val transferFuture = executor.submit {
                register(currentOwner, deviceId, currentToken)
            }
            assertThrows(TimeoutException::class.java) {
                transferFuture.get(300, TimeUnit.MILLISECONDS)
            }

            providerGate.release.countDown()
            val result = sendFuture.get(10, TimeUnit.SECONDS)
            transferFuture.get(10, TimeUnit.SECONDS)

            assertEquals(1, result.sentCount)
            assertEquals(1, pushClient.attempts(previousToken))
            assertEquals(PushDeliveryStatus.SUCCESS, deliveries(previousOwner).single().status)
            val owner = tokenRepository.findAll().single()
            assertEquals(currentOwner, owner.memberId)
            assertEquals(currentToken, owner.token)
        } finally {
            providerGate.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `provider lease 중 logout delete는 retirement로 남고 새 owner는 호출 종료 뒤 등록한다`() {
        val previousOwner = 525L
        val currentOwner = 526L
        val deviceId = "provider-delete-fence-device"
        val previousToken = "provider-delete-fence-old-token"
        val currentToken = "provider-delete-fence-new-token"
        register(previousOwner, deviceId, previousToken)
        val providerGate = pushClient.block(previousToken)
        val executor = Executors.newFixedThreadPool(2)
        val sendFuture = executor.submit<NotificationSendResult> {
            send(previousOwner, "provider-delete-fence-event")
        }

        try {
            assertTrue(providerGate.entered.await(10, TimeUnit.SECONDS))

            // logout/account cleanup의 device-token 단계와 같은 retirement 경계다. 활성
            // provider lease의 row identity를 유지해야 다른 account가 선점할 수 없다.
            tokenService.removeAllTokensByMember(previousOwner)
            val retired = tokenRepository.findAllByMemberId(previousOwner).single()
            assertTrue(retired.retirementRequested)
            assertTrue(retired.dispatchLeaseId != null)

            // retirement된 token은 새 event manifest에는 들어가지 않는다.
            val postLogout = send(previousOwner, "provider-delete-fence-new-event")
            assertEquals(1, postLogout.noDeviceEventCount)
            assertEquals(0, postLogout.requestedCount)

            val transferFuture = executor.submit {
                register(currentOwner, deviceId, currentToken)
            }
            assertThrows(TimeoutException::class.java) {
                transferFuture.get(300, TimeUnit.MILLISECONDS)
            }

            providerGate.release.countDown()
            val result = sendFuture.get(10, TimeUnit.SECONDS)
            transferFuture.get(10, TimeUnit.SECONDS)

            assertEquals(1, result.sentCount)
            assertEquals(1, pushClient.attempts(previousToken))
            assertEquals(0, pushClient.attempts(currentToken))
            assertTrue(tokenRepository.findAllByMemberId(previousOwner).isEmpty())
            val current = tokenRepository.findAllByMemberId(currentOwner).single()
            assertEquals(currentToken, current.token)
            assertFalse(current.retirementRequested)
        } finally {
            providerGate.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `같은 installation이 A에서 B로 이전되면 A delivery는 없고 B에게만 보낸다`() {
        val previousOwner = 519L
        val currentOwner = 520L
        val deviceId = "globally-transferred-device"
        register(previousOwner, deviceId, "previous-owner-token")
        register(currentOwner, deviceId, "current-owner-token")

        val previousResult = send(previousOwner, "previous-owner-after-transfer")
        val currentResult = send(currentOwner, "current-owner-after-transfer")

        assertTrue(tokenRepository.findAllByMemberId(previousOwner).isEmpty())
        assertEquals(1, tokenRepository.findAllByMemberId(currentOwner).size)
        assertEquals(1, previousResult.noDeviceEventCount)
        assertEquals(0, previousResult.requestedCount)
        assertTrue(deliveries(previousOwner).isEmpty())
        assertEquals(1, currentResult.sentCount)
        assertEquals(0, pushClient.attempts("previous-owner-token"))
        assertEquals(1, pushClient.attempts("current-owner-token"))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(currentOwner).single().status)
    }

    @Test
    fun `invalid 응답 사이 token 소유권이 이동하면 새 소유자의 row를 삭제하지 않는다`() {
        val oldOwner = 513L
        val newOwner = 514L
        val rawToken = "invalid-race-token"
        register(oldOwner, "old-device", rawToken)
        pushClient.invalidate(rawToken)
        val providerGate = pushClient.block(rawToken)
        val executor = Executors.newFixedThreadPool(2)
        val sendFuture = executor.submit<NotificationSendResult> {
            send(oldOwner, "invalid-race-event")
        }
        assertTrue(providerGate.entered.await(10, TimeUnit.SECONDS))
        val transferFuture = executor.submit {
            register(newOwner, "new-device", rawToken)
        }
        assertThrows(TimeoutException::class.java) {
            transferFuture.get(300, TimeUnit.MILLISECONDS)
        }
        providerGate.release.countDown()
        val result = sendFuture.get(10, TimeUnit.SECONDS)
        transferFuture.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(1, result.failedCount)
        assertEquals(1, result.removedTokenCount)
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
    fun `같은 실제 deviceId를 다시 등록하면 단일 row와 단일 delivery로 수렴한다`() {
        val memberId = 509L
        register(memberId, "same-device", "legacy-token-a")
        register(memberId, "same-device", "replacement-token-b")

        val result = notificationUseCase.sendToMember(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = pushData(),
            inboxDeduplicationKey = "duplicate-device-event",
        )

        assertEquals(1, tokenRepository.findAllByMemberId(memberId).size)
        assertEquals(1, result.requestedCount)
        assertEquals(1, result.attemptedCount)
        assertEquals(1, result.sentCount)
        assertEquals(0, pushClient.attempts("legacy-token-a"))
        assertEquals(1, pushClient.attempts("replacement-token-b"))
        assertEquals(1, deliveries(memberId).size)
    }

    @Test
    fun `platform 변경은 동일 member device의 delivery identity를 바꾸지 않는다`() {
        val android = NotificationDeviceToken(
            memberId = 515L,
            deviceId = "mutable-platform-device",
            platform = PushPlatform.UNKNOWN,
            token = "platform-token-before",
        )
        val ios = NotificationDeviceToken(
            memberId = 515L,
            deviceId = "mutable-platform-device",
            platform = PushPlatform.IOS,
            token = "platform-token-after",
        )

        assertEquals(
            android.deliveryDeviceKey(),
            ios.deliveryDeviceKey(),
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

    @Test
    fun `filter를 지난 test send는 새 session과 token 등록 뒤 outbox write 전에 거절된다`() {
        val memberId = 531L
        ensureActivePushMember(jdbcTemplate, memberId)
        val filterPassed = CountDownLatch(1)
        val resumeMutation = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            filterPassed.countDown()
            check(resumeMutation.await(10, TimeUnit.SECONDS))
            runCatching {
                notificationUseCase.sendAuthenticatedToMember(
                    memberId = memberId,
                    presentedSessionGeneration = 0L,
                    title = "stale private title",
                    body = "stale private body",
                    data = mapOf("type" to "TEST"),
                )
            }.onFailure(failure::set)
            completed.countDown()
        }

        try {
            assertTrue(filterPassed.await(10, TimeUnit.SECONDS))
            assertEquals(
                1,
                jdbcTemplate.update(
                    "UPDATE `member` SET session_generation = 1 WHERE id = ?",
                    memberId,
                ),
            )
            tokenService.registerToken(
                memberId = memberId,
                deviceId = "g2-test-send-device",
                platform = PushPlatform.ANDROID,
                token = "g2-test-send-token",
                accessTokenIssuedAt = Instant.parse("2026-07-24T03:00:00Z"),
                accessTokenSessionGeneration = 1L,
            )

            resumeMutation.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))

            val rejected = failure.get()
            assertTrue(rejected is BusinessException)
            assertEquals(ErrorCode.INVALID_TOKEN, (rejected as BusinessException).errorCode)
            assertTrue(inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).isEmpty())
            assertTrue(deliveryRepository.findAll().none { it.memberId == memberId })
            assertTrue(historyRepository.findAll().none { it.memberId == memberId })
            assertEquals(0, pushClient.attempts("g2-test-send-token"))
        } finally {
            resumeMutation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `test send provider lease는 claim 뒤 바뀐 session을 최종 검증한다`() {
        val memberId = 532L
        register(memberId, "session-provider-device", "session-provider-token")
        val gate = providerLeaseObserver.arm()
        val completed = CountDownLatch(1)
        val result = AtomicReference<NotificationSendResult?>()
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching {
                notificationUseCase.sendAuthenticatedToMember(
                    memberId = memberId,
                    presentedSessionGeneration = 0L,
                    title = "g1 private title",
                    body = "g1 private body",
                    data = mapOf("type" to "TEST"),
                )
            }.onSuccess(result::set).onFailure(failure::set)
            completed.countDown()
        }

        try {
            assertTrue(gate.beforeLease.await(10, TimeUnit.SECONDS))
            assertEquals(
                1,
                jdbcTemplate.update(
                    "UPDATE `member` SET session_generation = 1 WHERE id = ?",
                    memberId,
                ),
            )
            gate.allowLease.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))

            assertEquals(null, failure.get())
            assertEquals(1, result.get()?.supersededCount)
            assertEquals(0, pushClient.attempts("session-provider-token"))
            assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(memberId).single().status)
            assertEquals(
                com.noLate.notification.domain.PushOutboxDispatchStatus.COMPLETED,
                inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).single().dispatchStatus,
            )
        } finally {
            gate.allowLease.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `confirmed failure redrive도 source에 저장된 session generation을 복원한다`() {
        val memberId = 533L
        register(memberId, "session-redrive-device", "session-redrive-token")
        pushClient.failOnce("session-redrive-token")

        val first = notificationUseCase.sendAuthenticatedToMember(
            memberId = memberId,
            presentedSessionGeneration = 0L,
            title = "g1 retry title",
            body = "g1 retry body",
            data = mapOf("type" to "TEST"),
        )
        assertEquals(1, first.retryableFailedCount)
        val source = inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).single()
        assertTrue(source.deduplicationKey.orEmpty().startsWith("authenticated-push:v1:g0:"))

        assertEquals(
            1,
            jdbcTemplate.update(
                "UPDATE `member` SET session_generation = 1 WHERE id = ?",
                memberId,
            ),
        )
        val retried = notificationUseCase.redrivePersistedEvent(
            memberId = memberId,
            logicalEventKey = source.logicalEventKey,
        )

        assertEquals(1, retried.supersededCount)
        assertEquals(1, pushClient.attempts("session-redrive-token"))
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(memberId).single().status)
        assertEquals(
            com.noLate.notification.domain.PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(memberId).single().dispatchStatus,
        )
    }

    private fun send(memberId: Long, key: String): NotificationSendResult {
        ensureActivePushMember(jdbcTemplate, memberId)
        return notificationUseCase.sendToMember(
            memberId = memberId,
            title = "출발 시간 안내",
            body = "이동을 준비해주세요.",
            data = pushData(),
            inboxDeduplicationKey = key,
        )
    }

    private fun pushData() = mapOf(
        "type" to "SCHEDULE_DEPARTURE_REMINDER",
        "scheduleId" to "7001",
    )

    private fun register(
        memberId: Long,
        deviceId: String,
        token: String,
        deliveryAckCapabilityVersion: Int? = null,
    ) {
        registerAuthenticatedPushToken(
            jdbcTemplate = jdbcTemplate,
            tokenService = tokenService,
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = token,
            deliveryAckCapabilityVersion = deliveryAckCapabilityVersion,
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

    @Bean
    fun blockingPushTokenProviderLeaseObserver(): BlockingPushTokenProviderLeaseObserver =
        BlockingPushTokenProviderLeaseObserver()
}

class RecordingReliabilityPushClient : PushClient {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val recordedCalls = ConcurrentHashMap<String, MutableList<RecordedPushCall>>()
    private val failOnce = ConcurrentHashMap.newKeySet<String>()
    private val crashOnce = ConcurrentHashMap.newKeySet<String>()
    private val unknownOnce = ConcurrentHashMap.newKeySet<String>()
    private val invalid = ConcurrentHashMap.newKeySet<String>()
    private val blockingCalls = ConcurrentHashMap<String, ProviderCallGate>()

    fun reset() {
        attempts.clear()
        recordedCalls.clear()
        failOnce.clear()
        crashOnce.clear()
        unknownOnce.clear()
        invalid.clear()
        blockingCalls.values.forEach { it.release.countDown() }
        blockingCalls.clear()
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

    fun block(token: String): ProviderCallGate =
        ProviderCallGate().also { gate -> blockingCalls[token] = gate }

    fun attempts(token: String): Int = attempts[token]?.get() ?: 0

    fun calls(token: String): List<RecordedPushCall> =
        recordedCalls[token]?.toList().orEmpty()

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Push provider I/O must run outside a database transaction."
        }
        val attempt = attempts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        recordedCalls.computeIfAbsent(token) {
            java.util.Collections.synchronizedList(mutableListOf())
        }.add(RecordedPushCall(title, body, LinkedHashMap(data)))
        blockingCalls.remove(token)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(10, TimeUnit.SECONDS))
        }
        if (invalid.contains(token)) {
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

class BlockingPushTokenProviderLeaseObserver : PushTokenProviderLeaseObserver {
    private val armed = AtomicReference<ProviderLeaseGate?>()

    fun arm(): ProviderLeaseGate =
        ProviderLeaseGate().also { gate -> check(armed.compareAndSet(null, gate)) }

    fun release() {
        armed.getAndSet(null)?.allowLease?.countDown()
    }

    override fun beforeOwnershipLease(tokenId: Long) {
        val gate = armed.getAndSet(null) ?: return
        gate.beforeLease.countDown()
        check(gate.allowLease.await(10, TimeUnit.SECONDS))
    }
}

class ProviderLeaseGate(
    val beforeLease: CountDownLatch = CountDownLatch(1),
    val allowLease: CountDownLatch = CountDownLatch(1),
)

class ProviderCallGate(
    val entered: CountDownLatch = CountDownLatch(1),
    val release: CountDownLatch = CountDownLatch(1),
)

data class RecordedPushCall(
    val title: String,
    val body: String,
    val data: Map<String, String>,
)

class SimulatedProcessExit : Error("simulated process exit after provider acceptance")
