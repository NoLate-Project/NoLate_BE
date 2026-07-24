package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.application.service.AccountCleanupService
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.InvalidPushTokenException
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
import com.noLate.notification.application.service.PushOutboxDispatchCoordinator
import com.noLate.notification.application.service.PushOutboxDispatchWorker
import com.noLate.notification.application.service.PushOutboxDispatchWriter
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushManifestState
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.support.registerAuthenticatedPushToken
import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.domain.ScheduleShareResourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DataJpaTest
@Import(
    PushEventOutboxService::class,
    PushEventOutboxWriter::class,
    NotificationTokenService::class,
    NotificationTokenWriter::class,
    PushSendHistoryService::class,
    AppNotificationService::class,
    AppNotificationWriter::class,
    PushDeliveryService::class,
    PushDeliveryWriter::class,
    NotificationUseCase::class,
    PushOutboxDispatchCoordinator::class,
    PushOutboxDispatchWriter::class,
    PushOutboxDispatchWorker::class,
    AccountCleanupService::class,
    ScheduleSharePushNotificationListener::class,
    ScheduleDeparturePushNotificationListener::class,
    DurableScheduleNotificationOutboxTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:durable-schedule-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "notification.push-outbox.enabled=true",
        "notification.push-outbox.batch-size=10",
        "notification.push-outbox.max-attempts=3",
        "notification.push-outbox.retry-delay-seconds=1",
        "notification.push-outbox.processing-timeout-seconds=600",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DurableScheduleNotificationOutboxIntegrationTest @Autowired constructor(
    private val publisher: ApplicationEventPublisher,
    private val transactionManager: PlatformTransactionManager,
    private val notificationRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val historyRepository: PushSendHistoryRepository,
    private val tokenService: NotificationTokenService,
    private val shareListener: ScheduleSharePushNotificationListener,
    private val dispatchWorker: PushOutboxDispatchWorker,
    private val notificationUseCase: NotificationUseCase,
    private val dispatchCoordinator: PushOutboxDispatchCoordinator,
    private val pushClient: DurableOutboxRecordingPushClient,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val accountCleanupService: AccountCleanupService,
    private val memberRepository: MemberRepository,
) {
    @BeforeEach
    fun clean() {
        pushClient.reset()
        deliveryRepository.deleteAll()
        historyRepository.deleteAll()
        notificationRepository.deleteAll()
        tokenRepository.deleteAll()
        listOf(71L, 73L, 74L, 75L, 85L).forEach {
            ensureActivePushMember(jdbcTemplate, it)
        }
    }

    @Test
    fun `share business commit durably leaves a frozen pending event before any dispatch`() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            publisher.publishEvent(
                ScheduleShareGrantedEvent(
                    targetMemberId = 71,
                    resourceType = ScheduleShareResourceType.SCHEDULE,
                    resourceId = 901,
                    resourceTitle = "팀 회의",
                    notificationEventId = "durable-share-source",
                )
            )
        }

        val notification = notificationRepository.findAll().single()
        assertEquals(PushManifestState.FROZEN, notification.manifestState)
        assertEquals(0, notification.manifestRecipientCount)
        assertEquals(PushOutboxDispatchStatus.PENDING, notification.dispatchStatus)
        assertNotNull(notification.nextDispatchAt)
        assertTrue(
            deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                notification.memberId,
                notification.logicalEventKey,
            ).isEmpty()
        )
        val data = objectMapper.readValue(notification.dataJson, Map::class.java)
        assertEquals(notification.logicalEventKey, data["logicalEventKey"])
        assertEquals("71", data["recipientMemberId"])
    }

    @Test
    fun `business transaction이 listener outbox 저장 뒤 rollback되면 event manifest provider 모두 0이다`() {
        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            shareListener.onShareGranted(
                ScheduleShareGrantedEvent(
                    targetMemberId = 75,
                    resourceType = ScheduleShareResourceType.SCHEDULE,
                    resourceId = 903,
                    resourceTitle = "rollback share",
                    notificationEventId = "rollback-after-listener-write",
                )
            )
            assertEquals(1L, notificationRepository.count())
            transaction.setRollbackOnly()
        }

        assertTrue(notificationRepository.findAll().isEmpty())
        assertTrue(deliveryRepository.findAll().isEmpty())
        assertTrue(historyRepository.findAll().isEmpty())
        assertEquals(0, pushClient.totalAttempts())
    }

    @Test
    fun `participant departure commit stores one frozen event per distinct recipient`() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            publisher.publishEvent(
                ScheduleParticipantDepartedEvent(
                    scheduleId = 902,
                    scheduleTitle = "함께 이동",
                    departedMemberId = 72,
                    departedMemberLabel = "민수",
                    recipientMemberIds = listOf(73, 74, 73),
                )
            )
        }

        val notifications = notificationRepository.findAll().sortedBy { it.memberId }
        assertEquals(listOf(73L, 74L), notifications.map { it.memberId })
        assertTrue(notifications.all { it.manifestState == PushManifestState.FROZEN })
        assertTrue(notifications.all { it.dispatchStatus == PushOutboxDispatchStatus.PENDING })
        assertTrue(
            notifications.all {
                val data = objectMapper.readValue(it.dataJson, Map::class.java)
                data["logicalEventKey"] == it.logicalEventKey &&
                    data["recipientMemberId"] == it.memberId.toString()
            }
        )
    }

    @Test
    fun `commit 뒤 listener dispatch 전에 종료되어도 restart drainer가 frozen event를 보낸다`() {
        register(81L, "restart-device", "restart-token")
        publishShare(81L, "listener-before-dispatch-crash")

        val pending = notificationRepository.findAll().single()
        assertEquals(PushOutboxDispatchStatus.PENDING, pending.dispatchStatus)
        assertEquals(PushDeliveryStatus.PENDING, deliveryRepository.findAll().single().status)
        assertEquals(0, pushClient.attempts("restart-token"))

        assertEquals(1, dispatchWorker.runDueEvents(NOW))

        assertEquals(1, pushClient.attempts("restart-token"))
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findAll().single().dispatchStatus,
        )
        assertEquals(PushDeliveryStatus.SUCCESS, deliveryRepository.findAll().single().status)
        val call = pushClient.calls("restart-token").single()
        assertEquals(pending.logicalEventKey, call.data["logicalEventKey"])
        assertEquals("81", call.data["recipientMemberId"])
    }

    @Test
    fun `confirmed failure는 같은 frozen delivery만 retry해 성공한다`() {
        register(82L, "retry-device", "durable-retry-token")
        pushClient.failOnce("durable-retry-token")
        publishShare(82L, "confirmed-failure-retry")

        assertEquals(1, dispatchWorker.runDueEvents(NOW))
        assertEquals(PushDeliveryStatus.FAILED, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
            notificationRepository.findAll().single().dispatchStatus,
        )

        assertEquals(1, dispatchWorker.runDueEvents(NOW.plusSeconds(1)))

        assertEquals(2, pushClient.attempts("durable-retry-token"))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findAll().single().dispatchStatus,
        )
    }

    @Test
    fun `permanent invalid token은 terminal이고 restart에서도 다시 호출하지 않는다`() {
        register(83L, "invalid-device", "durable-invalid-token")
        pushClient.invalidate("durable-invalid-token")
        publishShare(83L, "permanent-invalid")

        assertEquals(1, dispatchWorker.runDueEvents(NOW))
        assertEquals(PushDeliveryStatus.INVALID_TOKEN, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findAll().single().dispatchStatus,
        )
        assertTrue(tokenRepository.findAllByMemberId(83L).isEmpty())

        assertEquals(0, dispatchWorker.runDueEvents(NOW.plusSeconds(1)))
        assertEquals(1, pushClient.attempts("durable-invalid-token"))
    }

    @Test
    fun `두 drainer instance의 full dispatch 경합도 provider 호출은 한 번이다`() {
        register(84L, "race-device", "durable-race-token")
        publishShare(84L, "multi-instance-full-dispatch")
        val firstWorker = newWorker()
        val secondWorker = newWorker()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(2)

        listOf(firstWorker, secondWorker).forEach { worker ->
            executor.submit {
                ready.countDown()
                start.await()
                runCatching { worker.runDueEvents(NOW) }
                    .onSuccess(results::add)
                    .onFailure(failures::add)
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, results.sum())
        assertEquals(1, pushClient.attempts("durable-race-token"))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findAll().single().dispatchStatus,
        )
    }

    @Test
    fun `provider가 lease timeout 뒤 확정 실패해도 stale completion을 다시 열어 실패 기기만 재시도한다`() {
        val memberId = 86L
        val token = "late-confirmed-failure-token"
        register(memberId, "late-failure-device", token)
        publishShare(memberId, "late-confirmed-failure-after-reclaim")
        val providerGate = pushClient.blockThenFailOnce(token)
        val firstWorker = newWorker()
        val replacementWorker = newWorker()
        val firstResult = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching { firstWorker.runDueEvents(NOW) }
                .onSuccess(firstResult::add)
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.PROCESSING,
            notificationRepository.findAll().single().dispatchStatus,
        )

        // The replacement owner observes DISPATCHING as ambiguous and may close its stale view.
        assertEquals(1, replacementWorker.runDueEvents(NOW.plusSeconds(601)))
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findAll().single().dispatchStatus,
        )

        // The original provider call is now known not to have been accepted. Delivery FAILED and
        // outbox PENDING are committed together, invalidating either old lease completion order.
        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(listOf(1), firstResult.toList())
        assertEquals(PushDeliveryStatus.FAILED, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
            notificationRepository.findAll().single().dispatchStatus,
        )

        assertEquals(1, newWorker().runDueEvents(NOW.plusSeconds(602)))
        assertEquals(2, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findAll().single().dispatchStatus,
        )
    }

    @Test
    fun `schedule source가 ambiguous로 회차를 끝낸 뒤 late confirmed failure도 safety outbox가 같은 event를 재시도한다`() {
        val memberId = 87L
        val token = "schedule-late-confirmed-token"
        register(memberId, "schedule-late-device", token)
        val providerGate = pushClient.blockThenFailOnce(token)
        val firstResults = ConcurrentLinkedQueue<com.noLate.notification.application.useCase.NotificationSendResult>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()
        val deduplicationKey = "schedule-push-job:late-provider:g0:c0"

        executor.submit {
            runCatching {
                notificationUseCase.sendToMember(
                    memberId = memberId,
                    title = "출발 시간 안내",
                    body = "지금 출발하세요.",
                    data = mapOf(
                        "type" to "SCHEDULE_DEPARTURE_REMINDER",
                        "scheduleId" to "777",
                        "departureReminderDecision" to "DEPART_NOW",
                    ),
                    inboxDeduplicationKey = deduplicationKey,
                )
            }.onSuccess(firstResults::add)
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.NOT_REQUIRED,
            notificationRepository.findAll().single().dispatchStatus,
        )

        // This is the replacement schedule worker's persisted-event view after its source lease
        // timed out: DISPATCHING is ambiguous and it makes no provider call.
        val replacementView = notificationUseCase.sendToMember(
            memberId = memberId,
            title = "현재 재계산 payload",
            body = "현재 의미는 사용하지 않습니다.",
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_REMINDER",
                "scheduleId" to "777",
                "departureReminderDecision" to "AFTER_DEPARTURE_3",
            ),
            inboxDeduplicationKey = deduplicationKey,
        )
        assertEquals(1, replacementView.ambiguousCount)
        assertEquals(1, pushClient.attempts(token))

        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, firstResults.single().retryableFailedCount)
        assertEquals(PushDeliveryStatus.FAILED, deliveryRepository.findAll().single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
            notificationRepository.findAll().single().dispatchStatus,
        )

        assertEquals(1, newWorker().runDueEvents(NOW.plusSeconds(1)))
        assertEquals(2, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveryRepository.findAll().single().status)
        val calls = pushClient.calls(token)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.title == "출발 시간 안내" })
        assertTrue(calls.all { it.data["departureReminderDecision"] == "DEPART_NOW" })
    }

    @Test
    fun `zero-device completed event는 late registration과 listener replay로 확장되지 않는다`() {
        publishShare(85L, "zero-device-replay")
        assertEquals(1, dispatchWorker.runDueEvents(NOW))
        register(85L, "late-device", "late-durable-token")

        publishShare(85L, "zero-device-replay")
        assertEquals(0, dispatchWorker.runDueEvents(NOW.plusSeconds(1)))

        assertEquals(0, pushClient.attempts("late-durable-token"))
        assertTrue(deliveryRepository.findAll().isEmpty())
        val event = notificationRepository.findAll().single()
        assertEquals(0, event.manifestRecipientCount)
        assertEquals(PushOutboxDispatchStatus.COMPLETED, event.dispatchStatus)
    }

    @ParameterizedTest
    @EnumSource(DurableLateProviderOutcome::class)
    fun `provider result가 탈퇴 뒤 늦게 돌아와도 notification rows를 재생성하지 않는다`(
        outcome: DurableLateProviderOutcome,
    ) {
        val memberId = 9_100L + outcome.ordinal
        val token = "withdrawal-late-${outcome.name.lowercase()}-token"
        register(memberId, "withdrawal-late-${outcome.name.lowercase()}-device", token)
        publishShare(memberId, "withdrawal-late-${outcome.name.lowercase()}")
        val providerGate = pushClient.blockThen(token, outcome)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching { dispatchWorker.runDueEvents(NOW) }
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveryRepository.findAll().single().status)

        accountCleanupService.withdraw(memberRepository.findById(memberId).orElseThrow())
        assertNotificationRowsAbsent(memberId)

        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, pushClient.attempts(token))
        assertNotificationRowsAbsent(memberId)
        assertEquals(0, dispatchWorker.runDueEvents(NOW.plusSeconds(1)))
    }

    @Test
    fun `withdrawal first prevents durable source manifest and provider creation`() {
        val memberId = 9_110L
        val token = "withdrawal-first-outbox-token"
        register(memberId, "withdrawal-first-outbox-device", token)
        accountCleanupService.withdraw(memberRepository.findById(memberId).orElseThrow())

        publishShare(memberId, "withdrawal-first-outbox")

        assertNotificationRowsAbsent(memberId)
        assertEquals(0, pushClient.attempts(token))
        assertEquals(0, dispatchWorker.runDueEvents(NOW))
    }

    @Test
    fun `zero-device source prepared before withdrawal cannot recreate no-token history`() {
        val memberId = 9_111L
        ensureActivePushMember(jdbcTemplate, memberId)
        publishShare(memberId, "zero-device-withdrawal-window")
        assertEquals(1, notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).size)

        accountCleanupService.withdraw(memberRepository.findById(memberId).orElseThrow())

        assertNotificationRowsAbsent(memberId)
        assertEquals(0, dispatchWorker.runDueEvents(NOW))
        assertNotificationRowsAbsent(memberId)
    }

    private fun assertNotificationRowsAbsent(memberId: Long) {
        assertTrue(notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).isEmpty())
        assertTrue(deliveryRepository.findAll().none { it.memberId == memberId })
        assertTrue(historyRepository.findAll().none { it.memberId == memberId })
        assertTrue(tokenRepository.findAllByMemberId(memberId).isEmpty())
    }

    private fun register(memberId: Long, deviceId: String, token: String) {
        registerAuthenticatedPushToken(
            jdbcTemplate = jdbcTemplate,
            tokenService = tokenService,
            memberId = memberId,
            deviceId = deviceId,
            platform = PushPlatform.ANDROID,
            token = token,
        )
    }

    private fun publishShare(memberId: Long, eventId: String) {
        ensureActivePushMember(jdbcTemplate, memberId)
        TransactionTemplate(transactionManager).executeWithoutResult {
            publisher.publishEvent(
                ScheduleShareGrantedEvent(
                    targetMemberId = memberId,
                    resourceType = ScheduleShareResourceType.SCHEDULE,
                    resourceId = 999L,
                    resourceTitle = "durable event",
                    notificationEventId = eventId,
                )
            )
        }
    }

    private fun newWorker(): PushOutboxDispatchWorker =
        PushOutboxDispatchWorker(
            notificationUseCase = notificationUseCase,
            coordinator = dispatchCoordinator,
            clock = clock,
            enabled = true,
            batchSize = 1,
            maxAttempts = 3,
            retryDelaySeconds = 1,
            processingTimeoutSeconds = 600,
        )

    companion object {
        private val NOW = Instant.parse("2026-07-24T06:00:00Z")
    }
}

@TestConfiguration
class DurableScheduleNotificationOutboxTestConfig {
    @Bean
    @Primary
    fun durableOutboxClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T06:00:00Z"), ZoneOffset.UTC)

    @Bean
    fun durableOutboxObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean
    fun durableOutboxPushClient(): DurableOutboxRecordingPushClient =
        DurableOutboxRecordingPushClient()
}

data class DurableOutboxPushCall(
    val title: String,
    val body: String,
    val data: Map<String, String>,
)

class DurableOutboxRecordingPushClient : PushClient {
    private val attemptCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val recordedCalls = ConcurrentHashMap<String, MutableList<DurableOutboxPushCall>>()
    private val failOnce = ConcurrentHashMap.newKeySet<String>()
    private val invalid = ConcurrentHashMap.newKeySet<String>()
    private val blockingFailures = ConcurrentHashMap<String, DurableBlockingProviderFailure>()
    private val blockingOutcomes = ConcurrentHashMap<String, DurableBlockingProviderOutcome>()

    fun reset() {
        attemptCounts.clear()
        recordedCalls.clear()
        failOnce.clear()
        invalid.clear()
        blockingFailures.values.forEach { it.release.countDown() }
        blockingFailures.clear()
        blockingOutcomes.values.forEach { it.release.countDown() }
        blockingOutcomes.clear()
    }

    fun failOnce(token: String) {
        failOnce += token
    }

    fun invalidate(token: String) {
        invalid += token
    }

    fun blockThenFailOnce(token: String): DurableBlockingProviderFailure =
        DurableBlockingProviderFailure().also { blockingFailures[token] = it }

    fun blockThen(
        token: String,
        outcome: DurableLateProviderOutcome,
    ): DurableBlockingProviderOutcome =
        DurableBlockingProviderOutcome(outcome).also { blockingOutcomes[token] = it }

    fun attempts(token: String): Int = attemptCounts[token]?.get() ?: 0

    fun totalAttempts(): Int = attemptCounts.values.sumOf(AtomicInteger::get)

    fun calls(token: String): List<DurableOutboxPushCall> =
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
        val attempt = attemptCounts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        recordedCalls.computeIfAbsent(token) {
            java.util.Collections.synchronizedList(mutableListOf())
        }.add(DurableOutboxPushCall(title, body, LinkedHashMap(data)))
        if (invalid.contains(token)) {
            throw InvalidPushTokenException(token)
        }
        blockingOutcomes.remove(token)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to release the deterministic provider result."
            }
            return when (gate.outcome) {
                DurableLateProviderOutcome.SUCCESS ->
                    PushSendResult("durable-late-$attempt")
                DurableLateProviderOutcome.CONFIRMED_FAILURE ->
                    throw ConfirmedPushDeliveryException("provider rejected")
                DurableLateProviderOutcome.INVALID_TOKEN ->
                    throw InvalidPushTokenException(token)
                DurableLateProviderOutcome.UNKNOWN ->
                    throw IllegalStateException("ambiguous provider transport")
            }
        }
        blockingFailures.remove(token)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to release the deterministic provider failure."
            }
            throw ConfirmedPushDeliveryException("provider rejected")
        }
        if (failOnce.remove(token)) {
            throw ConfirmedPushDeliveryException("provider rejected token=$token")
        }
        return PushSendResult("durable-$attempt")
    }
}

class DurableBlockingProviderFailure {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
}

enum class DurableLateProviderOutcome {
    SUCCESS,
    CONFIRMED_FAILURE,
    INVALID_TOKEN,
    UNKNOWN,
}

class DurableBlockingProviderOutcome(
    val outcome: DurableLateProviderOutcome,
) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
}
