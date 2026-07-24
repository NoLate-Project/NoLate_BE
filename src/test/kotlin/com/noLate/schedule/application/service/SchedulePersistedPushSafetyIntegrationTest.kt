package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationWriter
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.application.service.PreparedPushEvent
import com.noLate.notification.application.service.PushDeliveryClaimOutcome
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDeliveryWriter
import com.noLate.notification.application.service.PushDispatchFence
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.PushEventOutboxWriter
import com.noLate.notification.application.service.PushOutboxDispatchCoordinator
import com.noLate.notification.application.service.PushOutboxDispatchWorker
import com.noLate.notification.application.service.PushOutboxDispatchWriter
import com.noLate.notification.application.service.PushTokenProviderLeaseService
import com.noLate.notification.application.service.PushTokenProviderLeaseWriter
import com.noLate.notification.application.service.PushTokenProviderLeaseObserver
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushDelivery
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.support.registerAuthenticatedPushToken
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Persisted schedule outbox의 safety fence를 실제 manifest/claim/source 전이까지 검증한다.
 *
 * Provider I/O는 짧은 claim transaction 밖에서 일어나며, 아래 테스트의 호출 횟수는
 * terminal 또는 deferred delivery가 provider 경계를 넘지 않았다는 증거다.
 */
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
    NotificationUseCase::class,
    PushOutboxDispatchCoordinator::class,
    PushOutboxDispatchWriter::class,
    PushTokenProviderLeaseService::class,
    PushTokenProviderLeaseWriter::class,
    PushOutboxDispatchWorker::class,
    SchedulePushDispatchFenceValidator::class,
    SchedulePersistedPushDispatchFenceFactory::class,
    SchedulePushOutboxConfirmedDeliveryReconciler::class,
    SchedulePersistedPushSafetyTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-persisted-push-safety;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "notification.push-outbox.enabled=true",
        "notification.push-outbox.batch-size=1",
        "notification.push-outbox.max-attempts=3",
        "notification.push-outbox.retry-delay-seconds=1",
        "notification.push-outbox.processing-timeout-seconds=600",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePersistedPushSafetyIntegrationTest @Autowired constructor(
    private val outboxWorker: PushOutboxDispatchWorker,
    private val outboxService: PushEventOutboxService,
    private val pushDeliveryService: PushDeliveryService,
    private val notificationUseCase: NotificationUseCase,
    private val jobRepository: SchedulePushJobRepository,
    private val inboxRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val historyRepository: PushSendHistoryRepository,
    private val memberRepository: MemberRepository,
    private val tokenService: NotificationTokenService,
    private val pushClient: SchedulePersistedPushSafetyClient,
    private val providerLeaseObserver: ScheduleFenceProviderLeaseObserver,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun clean() {
        pushClient.reset()
        providerLeaseObserver.reset()
        deliveryRepository.deleteAll()
        historyRepository.deleteAll()
        inboxRepository.deleteAll()
        tokenRepository.deleteAll()
        jobRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `PROCESSING safety event는 실패 예산 없이 연기되고 ACTIVE 복구 뒤 같은 delivery를 보낸다`() {
        val fixture = prepareSafetyEvent(
            memberId = 9_201L,
            tokens = listOf("deferred-token"),
        )
        val processing = fixture.job.apply {
            startProcessing("authoritative-schedule-worker", NOW)
        }
        jobRepository.saveAndFlush(processing)

        assertEquals(1, outboxWorker.runDueEvents(NOW))

        val deferredSource = source(fixture)
        val deferredDelivery = deliveries(fixture).single()
        assertEquals(PushOutboxDispatchStatus.PENDING, deferredSource.dispatchStatus)
        assertEquals(1, deferredSource.dispatchAttemptCount)
        assertEquals(0, deferredSource.dispatchFailureCount)
        assertEquals(NOW.plusSeconds(1), deferredSource.nextDispatchAt)
        assertEquals(PushDeliveryStatus.PENDING, deferredDelivery.status)
        assertEquals(0, deferredDelivery.attemptCount)
        assertEquals(0, pushClient.attempts("deferred-token"))

        transactions.executeWithoutResult {
            requireNotNull(jobRepository.findByIdForUpdate(requireNotNull(processing.id)))
                .recoverProcessingTimeout(
                    reason = "authoritative worker lease expired",
                    nextCheckAt = NOW.plusSeconds(1),
                )
        }
        assertEquals(
            SchedulePushJobStatus.ACTIVE,
            jobRepository.findById(requireNotNull(processing.id)).orElseThrow().status,
        )

        assertEquals(1, outboxWorker.runDueEvents(NOW.plusSeconds(1)))

        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
        assertEquals(0, source(fixture).dispatchFailureCount)
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(fixture).single().status)
        assertEquals(1, pushClient.attempts("deferred-token"))
    }

    @Test
    fun `claim 뒤 source worker가 PROCESSING이면 provider 전 claim을 되돌리고 실패 예산 없이 연기한다`() {
        val token = "provider-fence-deferred-token"
        val fixture = prepareSafetyEvent(
            memberId = 9_202L,
            tokens = listOf(token),
        )
        val gate = providerLeaseObserver.arm()
        val result = AtomicReference<Int?>()
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            runCatching { outboxWorker.runDueEvents(NOW) }
                .onSuccess(result::set)
                .onFailure(failure::set)
        }

        try {
            assertTrue(gate.beforeLease.await(10, TimeUnit.SECONDS))
            assertEquals(PushDeliveryStatus.DISPATCHING, deliveries(fixture).single().status)
            assertEquals(1, deliveries(fixture).single().attemptCount)

            transactions.executeWithoutResult {
                requireNotNull(jobRepository.findByIdForUpdate(requireNotNull(fixture.job.id)))
                    .startProcessing("authoritative-schedule-worker", NOW)
                jobRepository.flush()
            }

            gate.allowLease.countDown()
            future.get(10, TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(1, result.get())

            val deferredSource = source(fixture)
            val deferredDelivery = deliveries(fixture).single()
            assertEquals(PushOutboxDispatchStatus.PENDING, deferredSource.dispatchStatus)
            assertEquals(1, deferredSource.dispatchAttemptCount)
            assertEquals(0, deferredSource.dispatchFailureCount)
            assertEquals(NOW.plusSeconds(1), deferredSource.nextDispatchAt)
            assertEquals(PushDeliveryStatus.PENDING, deferredDelivery.status)
            assertEquals(0, deferredDelivery.attemptCount)
            assertEquals(0, pushClient.attempts(token))

            transactions.executeWithoutResult {
                requireNotNull(jobRepository.findByIdForUpdate(requireNotNull(fixture.job.id)))
                    .recoverProcessingTimeout(
                        reason = "authoritative worker lease expired",
                        nextCheckAt = NOW.plusSeconds(1),
                    )
                jobRepository.flush()
            }

            assertEquals(1, outboxWorker.runDueEvents(NOW.plusSeconds(1)))
            assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
            assertEquals(0, source(fixture).dispatchFailureCount)
            assertEquals(PushDeliveryStatus.SUCCESS, deliveries(fixture).single().status)
            assertEquals(1, deliveries(fixture).single().attemptCount)
            assertEquals(1, pushClient.attempts(token))
        } finally {
            gate.allowLease.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `delivery claim 뒤 schedule generation이 바뀌면 provider lease가 old payload를 차단한다`() {
        val memberId = 9_219L
        val token = "schedule-provider-fence-token"
        registerAuthenticatedPushToken(
            jdbcTemplate = jdbcTemplate,
            tokenService = tokenService,
            memberId = memberId,
            deviceId = "schedule-provider-fence-device",
            platform = PushPlatform.ANDROID,
            token = token,
        )
        val scheduleId = 109_219L
        val job = jobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = NOW.plusSeconds(3_600),
                departureAt = NOW.plusSeconds(1_800),
                monitorStartAt = NOW.minusSeconds(60),
                intervalMinutes = 20,
                notificationInputFingerprint = "old-fingerprint",
            ).apply {
                startProcessing("direct-provider-fence-worker", NOW)
            }
        )
        val jobId = requireNotNull(job.id)
        val fence = PushDispatchFence(
            jobId = jobId,
            workerId = "direct-provider-fence-worker",
            jobVersion = requireNotNull(job.version),
            notificationGeneration = job.notificationGeneration,
            notificationInputFingerprint = job.notificationInputFingerprint,
            expectedMemberId = memberId,
            expectedScheduleId = scheduleId,
        )
        val gate = providerLeaseObserver.arm()
        val completed = CountDownLatch(1)
        val result = AtomicReference<com.noLate.notification.application.useCase.NotificationSendResult?>()
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching {
                notificationUseCase.sendToMemberFenced(
                    memberId = memberId,
                    title = "old ETA title",
                    body = "old ETA body",
                    data = mapOf(
                        "type" to "SCHEDULE_DEPARTURE_REMINDER",
                        "scheduleId" to scheduleId.toString(),
                        "schedulePushJobId" to jobId.toString(),
                        "notificationGeneration" to "0",
                        "schedulePushCheckCount" to "0",
                        "notificationInputFingerprint" to "old-fingerprint",
                    ),
                    inboxDeduplicationKey = "schedule-push-job:$jobId:g0:c0",
                    dispatchFence = fence,
                )
            }.onSuccess(result::set).onFailure(failure::set)
            completed.countDown()
        }

        try {
            assertTrue(gate.beforeLease.await(10, TimeUnit.SECONDS))
            transactions.executeWithoutResult {
                val locked = requireNotNull(jobRepository.findByIdForUpdate(jobId))
                assertTrue(
                    locked.changeSchedule(
                        scheduleAt = NOW.plusSeconds(3_900),
                        departureAt = NOW.plusSeconds(2_100),
                        monitorStartAt = NOW.plusSeconds(300),
                        intervalMinutes = 20,
                        notificationInputFingerprint = "new-fingerprint",
                    )
                )
                jobRepository.flush()
            }

            gate.allowLease.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))

            assertEquals(null, failure.get())
            assertEquals(1, result.get()?.supersededCount)
            assertEquals(0, pushClient.attempts(token))
            assertEquals(PushDeliveryStatus.SUPERSEDED, deliveryRepository.findAll().single().status)
            assertEquals(
                PushOutboxDispatchStatus.COMPLETED,
                inboxRepository.findAll().single().dispatchStatus,
            )
            assertEquals(1L, jobRepository.findById(jobId).orElseThrow().notificationGeneration)
        } finally {
            gate.allowLease.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SchedulePushJobStatus::class,
        names = ["COMPLETED", "FAILED", "CANCELED"],
    )
    fun `terminal schedule source는 old persisted delivery를 재전송 없이 terminal 처리한다`(
        terminalStatus: SchedulePushJobStatus,
    ) {
        val token = "terminal-${terminalStatus.name.lowercase()}-token"
        val fixture = prepareSafetyEvent(
            memberId = 9_210L + terminalStatus.ordinal,
            tokens = listOf(token),
        )
        fixture.job.apply {
            when (terminalStatus) {
                SchedulePushJobStatus.COMPLETED -> complete()
                SchedulePushJobStatus.FAILED -> fail("terminal source")
                SchedulePushJobStatus.CANCELED -> cancel()
                else -> error("Unexpected test status: $terminalStatus")
            }
        }
        jobRepository.saveAndFlush(fixture.job)

        assertEquals(1, outboxWorker.runDueEvents(NOW))

        assertEquals(0, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(fixture).single().status)
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
        assertEquals(null, source(fixture).nextDispatchAt)
    }

    @Test
    fun `persisted identity mismatch는 provider 호출 없이 delivery와 source를 terminal 처리한다`() {
        val fixture = prepareSafetyEvent(
            memberId = 9_220L,
            tokens = listOf("identity-mismatch-token"),
        )
        assertTrue(
            fixture.job.changeSchedule(
                scheduleAt = fixture.job.scheduleAt.plus(1, ChronoUnit.MINUTES),
                departureAt = fixture.job.departureAt.plus(1, ChronoUnit.MINUTES),
                monitorStartAt = fixture.job.monitorStartAt.plus(1, ChronoUnit.MINUTES),
                intervalMinutes = fixture.job.intervalMinutes,
                notificationInputFingerprint = "f".repeat(64),
            )
        )
        jobRepository.saveAndFlush(fixture.job)

        assertEquals(1, outboxWorker.runDueEvents(NOW))

        assertEquals(0, pushClient.attempts("identity-mismatch-token"))
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(fixture).single().status)
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
    }

    @ParameterizedTest
    @EnumSource(
        value = SchedulePushJobStatus::class,
        names = ["COMPLETED", "FAILED", "CANCELED"],
    )
    fun `provider in flight 뒤 schedule source가 terminal이면 late failure가 source를 reopen하지 않는다`(
        terminalStatus: SchedulePushJobStatus,
    ) {
        val token = "late-terminal-${terminalStatus.name.lowercase()}-token"
        val fixture = prepareSafetyEvent(
            memberId = 9_225L + terminalStatus.ordinal,
            tokens = listOf(token),
        )
        val gate = pushClient.blockThenFail(token)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            runCatching { outboxWorker.runDueEvents(NOW) }.onFailure(failures::add)
        }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveries(fixture).single().status)

        transactions.executeWithoutResult {
            val current = requireNotNull(
                jobRepository.findByIdForUpdate(requireNotNull(fixture.job.id)),
            )
            when (terminalStatus) {
                SchedulePushJobStatus.COMPLETED -> current.complete()
                SchedulePushJobStatus.FAILED -> current.fail("terminal during provider I/O")
                SchedulePushJobStatus.CANCELED -> current.cancel()
                else -> error("Unexpected test status: $terminalStatus")
            }
        }
        gate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(fixture).single().status)
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
        assertEquals(null, source(fixture).nextDispatchAt)
        assertEquals(0, outboxWorker.runDueEvents(NOW.plusSeconds(1)))
    }

    @Test
    fun `D1 success D2 in flight 뒤 snooze하면 D2 late failure는 old event를 reopen하지 않는다`() {
        val successToken = "snooze-inflight-success-token"
        val blockedToken = "snooze-inflight-blocked-token"
        val fixture = prepareSafetyEvent(
            memberId = 9_229L,
            tokens = listOf(successToken, blockedToken),
        )
        val successTokenEntity = tokenRepository.findAllByMemberId(fixture.memberId)
            .single { it.token == successToken }
        val successDelivery = deliveries(fixture)
            .single { it.tokenFingerprint == successTokenEntity.tokenFingerprint }
        val successClaim = pushDeliveryService.claim(
            memberId = fixture.memberId,
            eventKey = fixture.prepared.logicalEventKey,
            deliveryId = requireNotNull(successDelivery.id),
            fence = fixture.safetyFence,
        )
        assertEquals(PushDeliveryClaimOutcome.SEND, successClaim.outcome)
        val snapshot = requireNotNull(fixture.prepared.snapshot)
        val accepted = pushClient.sendToToken(
            token = requireNotNull(successClaim.providerToken),
            title = snapshot.title,
            body = snapshot.body,
            data = snapshot.data,
        )
        assertTrue(
            pushDeliveryService.markSuccess(
                deliveryId = requireNotNull(successClaim.deliveryId),
                providerMessageId = accepted.messageId,
                fence = fixture.safetyFence,
            )
        )
        val gate = pushClient.blockThenFail(blockedToken)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            runCatching { outboxWorker.runDueEvents(NOW.plusSeconds(1)) }.onFailure(failures::add)
        }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(1, pushClient.attempts(successToken))
        assertEquals(1, pushClient.attempts(blockedToken))

        transactions.executeWithoutResult {
            requireNotNull(jobRepository.findByIdForUpdate(requireNotNull(fixture.job.id)))
                .snoozeUntil(NOW.plus(5, ChronoUnit.MINUTES))
        }
        assertEquals(
            1,
            jobRepository.findById(requireNotNull(fixture.job.id)).orElseThrow()
                .notificationGeneration,
        )

        gate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })

        val deliveriesByToken = tokenRepository.findAllByMemberId(fixture.memberId)
            .associate { token -> token.token to token.tokenFingerprint }
            .mapValues { (_, fingerprint) ->
                deliveries(fixture).single { it.tokenFingerprint == fingerprint }.status
            }
        assertEquals(PushDeliveryStatus.SUCCESS, deliveriesByToken.getValue(successToken))
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveriesByToken.getValue(blockedToken))
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
        assertEquals(0, outboxWorker.runDueEvents(NOW.plusSeconds(2)))
        assertEquals(1, pushClient.attempts(successToken))
        assertEquals(1, pushClient.attempts(blockedToken))
    }

    @Test
    fun `snooze는 generation을 올리고 old success는 유지하면서 pending 기기만 terminal 처리한다`() {
        val fixture = prepareSafetyEvent(
            memberId = 9_230L,
            tokens = listOf("snooze-success-token", "snooze-pending-token"),
        )
        val successToken = tokenRepository.findAllByMemberId(fixture.memberId)
            .single { it.token == "snooze-success-token" }
        val successDelivery = deliveries(fixture)
            .single { it.tokenFingerprint == successToken.tokenFingerprint }
        val claim = pushDeliveryService.claim(
            memberId = fixture.memberId,
            eventKey = fixture.prepared.logicalEventKey,
            deliveryId = requireNotNull(successDelivery.id),
            fence = fixture.safetyFence,
        )
        assertEquals(PushDeliveryClaimOutcome.SEND, claim.outcome)
        val snapshot = requireNotNull(fixture.prepared.snapshot)
        val accepted = pushClient.sendToToken(
            token = requireNotNull(claim.providerToken),
            title = snapshot.title,
            body = snapshot.body,
            data = snapshot.data,
        )
        assertTrue(
            pushDeliveryService.markSuccess(
                requireNotNull(claim.deliveryId),
                accepted.messageId,
                fixture.safetyFence,
            )
        )

        fixture.job.snoozeUntil(NOW.plus(5, ChronoUnit.MINUTES))
        jobRepository.saveAndFlush(fixture.job)
        assertEquals(1, fixture.job.notificationGeneration)
        assertEquals(0, fixture.job.checkCount)

        assertEquals(1, outboxWorker.runDueEvents(NOW.plusSeconds(1)))

        val byFingerprint = deliveries(fixture).associateBy { it.tokenFingerprint }
        assertEquals(
            PushDeliveryStatus.SUCCESS,
            byFingerprint.getValue(successToken.tokenFingerprint).status,
        )
        assertEquals(
            PushDeliveryStatus.SUPERSEDED,
            byFingerprint.values.single { it.tokenFingerprint != successToken.tokenFingerprint }.status,
        )
        assertEquals(1, pushClient.attempts("snooze-success-token"))
        assertEquals(0, pushClient.attempts("snooze-pending-token"))
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
    }

    @Test
    fun `혼합 manifest에서 최대 시도 delivery는 EXHAUSTED가 되고 저시도 delivery만 provider를 호출한다`() {
        val fixture = prepareSafetyEvent(
            memberId = 9_240L,
            tokens = listOf("maxed-token", "fresh-token"),
        )
        val maxedToken = tokenRepository.findAllByMemberId(fixture.memberId)
            .single { it.token == "maxed-token" }
        var maxedDelivery = deliveries(fixture)
            .single { it.tokenFingerprint == maxedToken.tokenFingerprint }
        repeat(3) { attempt ->
            maxedDelivery.beginDispatch(NOW.minusSeconds((3 - attempt).toLong()))
            assertTrue(
                maxedDelivery.markFailure(
                    NOW.minusSeconds((3 - attempt).toLong()),
                    "CONFIRMED_FAILURE",
                    "provider rejected attempt ${attempt + 1}",
                )
            )
            maxedDelivery = deliveryRepository.saveAndFlush(maxedDelivery)
        }
        assertEquals(PushDeliveryStatus.FAILED, maxedDelivery.status)
        assertEquals(3, maxedDelivery.attemptCount)

        assertEquals(1, outboxWorker.runDueEvents(NOW))

        val persisted = deliveries(fixture).associateBy { it.tokenFingerprint }
        assertEquals(PushDeliveryStatus.EXHAUSTED, persisted.getValue(maxedToken.tokenFingerprint).status)
        assertEquals(3, persisted.getValue(maxedToken.tokenFingerprint).attemptCount)
        assertEquals(
            PushDeliveryStatus.SUCCESS,
            persisted.values.single { it.tokenFingerprint != maxedToken.tokenFingerprint }.status,
        )
        assertEquals(0, pushClient.attempts("maxed-token"))
        assertEquals(1, pushClient.attempts("fresh-token"))
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source(fixture).dispatchStatus)
    }

    private fun prepareSafetyEvent(
        memberId: Long,
        tokens: List<String>,
    ): SafetyFixture {
        tokens.forEachIndexed { index, token ->
            registerAuthenticatedPushToken(
                jdbcTemplate = jdbcTemplate,
                tokenService = tokenService,
                memberId = memberId,
                deviceId = "safety-device-$memberId-$index",
                platform = PushPlatform.ANDROID,
                token = token,
            )
        }
        val scheduleId = memberId + 100_000L
        val job = jobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = memberId,
                scheduleId = scheduleId,
                scheduleAt = NOW.plus(60, ChronoUnit.MINUTES),
                departureAt = NOW.plus(30, ChronoUnit.MINUTES),
                monitorStartAt = NOW.minus(1, ChronoUnit.MINUTES),
                intervalMinutes = 20,
            )
        )
        val jobId = requireNotNull(job.id)
        val safetyFence = PushDispatchFence(
            jobId = jobId,
            workerId = "persisted-safety-fixture",
            jobVersion = requireNotNull(job.version),
            notificationGeneration = job.notificationGeneration,
            notificationInputFingerprint = job.notificationInputFingerprint,
            expectedMemberId = memberId,
            expectedScheduleId = scheduleId,
            requireWorkerLease = false,
        )
        val deduplicationKey =
            "schedule-push-job:$jobId:g${job.notificationGeneration}:c${job.checkCount}"
        val prepared = outboxService.prepare(
            memberId = memberId,
            title = "persisted safety title",
            body = "persisted safety body",
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_REMINDER",
                "scheduleId" to scheduleId.toString(),
                "schedulePushJobId" to jobId.toString(),
                "notificationGeneration" to job.notificationGeneration.toString(),
                "schedulePushCheckCount" to job.checkCount.toString(),
                "notificationInputFingerprint" to job.notificationInputFingerprint,
            ),
            deduplicationKey = deduplicationKey,
            persistInInbox = true,
            fence = safetyFence,
        )
        val source = requireNotNull(
            inboxRepository.findByMemberIdAndLogicalEventKey(
                memberId,
                prepared.logicalEventKey,
            )
        )
        assertTrue(source.scheduleConfirmedDeliveryReconciliation(NOW))
        inboxRepository.saveAndFlush(source)
        return SafetyFixture(
            memberId = memberId,
            job = job,
            safetyFence = safetyFence,
            prepared = prepared,
        )
    }

    private fun source(fixture: SafetyFixture) =
        requireNotNull(
            inboxRepository.findByMemberIdAndLogicalEventKey(
                fixture.memberId,
                fixture.prepared.logicalEventKey,
            )
        )

    private fun deliveries(fixture: SafetyFixture): List<PushDelivery> =
        deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
            fixture.memberId,
            fixture.prepared.logicalEventKey,
        )

    private data class SafetyFixture(
        val memberId: Long,
        val job: SchedulePushJob,
        val safetyFence: PushDispatchFence,
        val prepared: PreparedPushEvent,
    )

    companion object {
        private val NOW = Instant.parse("2026-07-24T03:00:00Z")
    }
}

@TestConfiguration
class SchedulePersistedPushSafetyTestConfig {
    @Bean
    fun schedulePersistedSafetyClock(): Clock = Clock.fixed(
        Instant.parse("2026-07-24T03:00:00Z"),
        ZoneOffset.UTC,
    )

    @Bean
    fun schedulePersistedSafetyObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean
    fun schedulePersistedSafetyPushClient(): SchedulePersistedPushSafetyClient =
        SchedulePersistedPushSafetyClient()

    @Bean
    fun scheduleFenceProviderLeaseObserver(): ScheduleFenceProviderLeaseObserver =
        ScheduleFenceProviderLeaseObserver()
}

class SchedulePersistedPushSafetyClient : PushClient {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val blockingFailures = ConcurrentHashMap<String, ScheduleSafetyBlockingFailure>()

    fun reset() {
        attempts.clear()
        blockingFailures.values.forEach { it.release.countDown() }
        blockingFailures.clear()
    }

    fun attempts(token: String): Int = attempts[token]?.get() ?: 0

    fun blockThenFail(token: String): ScheduleSafetyBlockingFailure =
        ScheduleSafetyBlockingFailure().also { blockingFailures[token] = it }

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        val attempt = attempts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        blockingFailures.remove(token)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(10, TimeUnit.SECONDS))
            throw com.noLate.notification.application.ConfirmedPushDeliveryException(
                "provider rejected",
            )
        }
        return PushSendResult("safety-message-$attempt")
    }
}

class ScheduleSafetyBlockingFailure {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
}

class ScheduleFenceProviderLeaseObserver : PushTokenProviderLeaseObserver {
    private val armed = AtomicReference<ScheduleFenceProviderLeaseGate?>()

    fun arm(): ScheduleFenceProviderLeaseGate =
        ScheduleFenceProviderLeaseGate().also { check(armed.compareAndSet(null, it)) }

    fun reset() {
        armed.getAndSet(null)?.allowLease?.countDown()
    }

    override fun beforeOwnershipLease(tokenId: Long) {
        val gate = armed.getAndSet(null) ?: return
        gate.beforeLease.countDown()
        check(gate.allowLease.await(10, TimeUnit.SECONDS))
    }
}

class ScheduleFenceProviderLeaseGate(
    val beforeLease: CountDownLatch = CountDownLatch(1),
    val allowLease: CountDownLatch = CountDownLatch(1),
)
