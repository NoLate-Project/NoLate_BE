package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationWriter
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDeliveryWriter
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.PushEventOutboxWriter
import com.noLate.notification.application.service.PushOutboxDispatchCoordinator
import com.noLate.notification.application.service.PushOutboxDispatchWorker
import com.noLate.notification.application.service.PushOutboxDispatchWriter
import com.noLate.notification.application.service.PushTokenProviderLeaseService
import com.noLate.notification.application.service.PushTokenProviderLeaseWriter
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.support.AllowAllPushRecipientAuthorizationTestConfig
import com.noLate.notification.support.registerAuthenticatedPushToken
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleEtaRouteFingerprint
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleEtaEvaluationHistoryRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 백엔드 작업 흐름 E2E: 실제 Worker/Coordinator/알림 outbox/Repository와 H2를 연결한다.
 * 교통·푸시 공급자와 출발 알람 동기화는 대역이며, 수신 권한은 테스트 설정으로 허용한다.
 * 실제 HTTP 서버·모바일 앱·FCM 수신 검증은 포함하지 않는다.
 */
@Tag("backend-e2e")
@DataJpaTest
@Import(
    SchedulePushJobWorker::class,
    SchedulePushJobCoordinator::class,
    PeriodicPushPolicy::class,
    DepartureReminderPolicy::class,
    TrafficChangePolicy::class,
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
    SchedulePushDispatchFenceValidator::class,
    NotificationUseCase::class,
    PushOutboxDispatchCoordinator::class,
    PushOutboxDispatchWriter::class,
    PushTokenProviderLeaseService::class,
    PushTokenProviderLeaseWriter::class,
    PushOutboxDispatchWorker::class,
    SchedulePersistedPushDispatchFenceFactory::class,
    SchedulePushOutboxConfirmedDeliveryReconciler::class,
    SchedulePushJobDeliveryTestConfig::class,
    AllowAllPushRecipientAuthorizationTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push-delivery;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.batch-size=10",
        "schedule.push.enabled=true",
        "schedule.push.retry-delay-minutes=1",
        "schedule.push.max-retry-count=3",
        "schedule.push.delivery-grace-minutes=10",
        "schedule.push.departure-alert-lead-minutes=15",
        "schedule.push.departure-reminder-interval-minutes=5",
        "schedule.push.departure-snooze-minutes=5",
        "schedule.push.processing-timeout-minutes=10",
        "notification.push-outbox.enabled=true",
        "notification.push-outbox.batch-size=10",
        "notification.push-outbox.max-attempts=3",
        "notification.push-outbox.retry-delay-seconds=1",
        "notification.push-outbox.processing-timeout-seconds=600",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePushJobDeliveryIntegrationTest @Autowired constructor(
    private val worker: SchedulePushJobWorker,
    private val outboxWorker: PushOutboxDispatchWorker,
    private val notificationUseCase: NotificationUseCase,
    private val outboxCoordinator: PushOutboxDispatchCoordinator,
    private val outboxConfirmedReconciler: SchedulePushOutboxConfirmedDeliveryReconciler,
    private val clock: Clock,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val tokenService: NotificationTokenService,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val inboxRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val historyRepository: PushSendHistoryRepository,
    private val pushClient: WorkerDeliveryPushClient,
    private val trafficClient: WorkerDeliveryTrafficClient,
    private val jdbcTemplate: JdbcTemplate,
    private val etaHistories: ScheduleEtaEvaluationHistoryRepository,
) {
    @MockitoBean
    private lateinit var departureAlarmSyncService: DepartureAlarmSyncService

    private val now = Instant.parse("2026-07-24T03:00:00Z")

    @BeforeEach
    fun clean() {
        pushClient.reset()
        trafficClient.reset()
        etaHistories.deleteAll()
        deliveryRepository.deleteAll()
        historyRepository.deleteAll()
        inboxRepository.deleteAll()
        tokenRepository.deleteAll()
        pushJobRepository.deleteAll()
        scheduleRepository.deleteAll()
    }

    @Test
    fun `early terminal alarm rollback leaves worker lease PROCESSING for stale recovery`() {
        val schedule = createDueSchedule(memberId = 800L)
        register(schedule.memberId, "terminal-alarm-device", "terminal-alarm-token")
        val pastScheduleAt = now.minus(11, ChronoUnit.MINUTES)
        val job = pushJobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = schedule.memberId,
                scheduleId = requireNotNull(schedule.id),
                scheduleAt = pastScheduleAt,
                departureAt = pastScheduleAt.minus(30, ChronoUnit.MINUTES),
                monitorStartAt = pastScheduleAt.minus(60, ChronoUnit.MINUTES),
                intervalMinutes = 20,
            )
        )
        whenever(departureAlarmSyncService.cancel(schedule.memberId, requireNotNull(schedule.id)))
            .thenThrow(IllegalStateException("alarm outbox unavailable"))

        assertEquals(1, worker.runDueJobs(now))

        val persisted = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(SchedulePushJobStatus.PROCESSING, persisted.status)
        assertEquals(0, persisted.retryCount)
        assertEquals(0, persisted.checkCount)
        assertTrue(!persisted.lockedBy.isNullOrBlank())
        assertEquals(0L, etaHistories.count())
        assertTrue(trafficClient.requests().isEmpty())
    }

    @Test
    fun `두 기기 부분 성공은 같은 Worker event를 유지해 실패 기기만 다음 실행에서 재시도한다`() {
        val schedule = createDueSchedule(memberId = 801L)
        val job = createDueJob(schedule)
        register(schedule.memberId, "stable-device", "stable-worker-token")
        register(schedule.memberId, "retry-device", "retry-worker-token")
        pushClient.failOnce("retry-worker-token")

        assertEquals(1, worker.runDueJobs(now))

        val afterPartial = requireNotNull(pushJobRepository.findById(job.id!!).orElse(null))
        assertEquals(SchedulePushJobStatus.ACTIVE, afterPartial.status)
        assertEquals(0, afterPartial.checkCount)
        assertEquals(1, afterPartial.retryCount)
        assertEquals(now.plus(1, ChronoUnit.MINUTES), afterPartial.nextCheckAt)
        assertEquals(now, afterPartial.lastPushedAt)
        val firstEvaluation = evaluations(schedule).single()
        assertEquals(job.id, firstEvaluation.jobId)
        assertEquals(TrafficSource.LIVE_PROVIDER, firstEvaluation.source)
        assertEquals(
            setOf(PushDeliveryStatus.SUCCESS, PushDeliveryStatus.FAILED),
            deliveries(schedule.memberId).map { it.status }.toSet(),
        )

        assertEquals(1, worker.runDueJobs(now.plus(1, ChronoUnit.MINUTES)))

        val afterRetry = requireNotNull(pushJobRepository.findById(job.id!!).orElse(null))
        assertEquals(1, afterRetry.checkCount)
        assertEquals(0, afterRetry.retryCount)
        assertEquals(1, pushClient.attempts("stable-worker-token"))
        assertEquals(2, pushClient.attempts("retry-worker-token"))
        assertTrue(deliveries(schedule.memberId).all { it.status == PushDeliveryStatus.SUCCESS })
        assertEquals(
            mapOf(
                OpaquePushIdentifier.fingerprint("stable-device") to 1,
                OpaquePushIdentifier.fingerprint("retry-device") to 2,
            ),
            deliveries(schedule.memberId).associate { it.deviceFingerprint to it.attemptCount },
        )
        assertEquals(1, inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId).size)
        // 같은 푸시 event/checkCount를 재시도해도 ETA를 재계산했다면 별도 평가로 남긴다.
        val evaluations = evaluations(schedule)
        assertEquals(2, evaluations.size)
        assertEquals(2, evaluations.map { it.evaluationId }.distinct().size)
        assertEquals(listOf(now.plusSeconds(60), now), evaluations.map { it.evaluatedAt })
        assertEquals(firstEvaluation.id, evaluations.last().id)
    }

    @Test
    fun `일정 수정으로 generation이 증가하면 check 0 새 알림을 Worker가 실제 전송한다`() {
        val schedule = createDueSchedule(memberId = 802L)
        val job = createDueJob(schedule)
        register(schedule.memberId, "edited-device", "edited-worker-token")

        assertEquals(1, worker.runDueJobs(now))
        assertEquals(1, pushClient.attempts("edited-worker-token"))

        val editedStartAt = schedule.startAt.minus(5, ChronoUnit.MINUTES)
        schedule.startAt = editedStartAt
        schedule.endAt = editedStartAt.plus(30, ChronoUnit.MINUTES)
        schedule.route?.apply {
            departAt = editedStartAt.minus(45, ChronoUnit.MINUTES)
        }
        scheduleRepository.saveAndFlush(schedule)

        val persistedJob = requireNotNull(pushJobRepository.findById(job.id!!).orElse(null))
        persistedJob.changeSchedule(
            scheduleAt = editedStartAt,
            departureAt = editedStartAt.minus(45, ChronoUnit.MINUTES),
            monitorStartAt = now.minus(1, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        pushJobRepository.saveAndFlush(persistedJob)

        assertEquals(1, persistedJob.notificationGeneration)
        assertEquals(0, persistedJob.checkCount)
        assertEquals(1, worker.runDueJobs(now))

        assertEquals(2, pushClient.attempts("edited-worker-token"))
        assertEquals(2, inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId).size)
        assertEquals(2, deliveries(schedule.memberId).size)
        assertTrue(deliveries(schedule.memberId).all { it.status == PushDeliveryStatus.SUCCESS })
    }

    @Test
    fun `부분 실패는 immutable payload를 재시도한 뒤 같은 decision의 최신 ETA correction을 보낸다`() {
        val schedule = createDueSchedule(memberId = 803L)
        val job = seedCheckedJob(schedule, previousTravelMinutes = 30)
        register(schedule.memberId, "stable-device", "stable-snapshot-token")
        register(schedule.memberId, "retry-device", "retry-snapshot-token")
        trafficClient.respondWith(40, 25, 25)
        pushClient.failOnce("retry-snapshot-token")

        assertEquals(1, worker.runDueJobs(now))
        val retryAt = now.plus(1, ChronoUnit.MINUTES)
        assertEquals(1, worker.runDueJobs(retryAt))

        val afterImmutableRetry = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        // 알림은 동결된 A(40분)를 사용해도 이력에는 이번 계산 B(25분)를 보존한다.
        assertEquals(listOf(25, 40), evaluations(schedule).map { it.travelMinutes })
        assertEquals(40, afterImmutableRetry.lastTravelMinutes)
        assertEquals(retryAt.plusSeconds(1), afterImmutableRetry.nextCheckAt)
        assertTrue(afterImmutableRetry.semanticCatchUpPending)
        assertEquals(1, worker.runDueJobs(retryAt.plusSeconds(1)))

        val stableCalls = pushClient.calls("stable-snapshot-token")
        val retryCalls = pushClient.calls("retry-snapshot-token")
        assertEquals(2, stableCalls.size)
        assertEquals(3, retryCalls.size)
        assertEquals(retryCalls[0].title, retryCalls[1].title)
        assertEquals(retryCalls[0].body, retryCalls[1].body)
        assertEquals(retryCalls[0].data, retryCalls[1].data)
        assertEquals("40", retryCalls[1].data["travelMinutes"])
        assertEquals("10", retryCalls[1].data["trafficChangeMinutes"])
        assertEquals("803", retryCalls[1].data["recipientMemberId"])
        assertEquals("25", stableCalls[1].data["travelMinutes"])
        assertEquals(stableCalls[1].data, retryCalls[2].data)
        assertTrue(stableCalls[1].data["logicalEventKey"] != retryCalls[1].data["logicalEventKey"])
        assertEquals(
            inboxRepository.findAllByMemberIdOrderByIdDesc(803L).last().logicalEventKey,
            retryCalls[1].data["logicalEventKey"],
        )

        val completedEvent = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(3, completedEvent.checkCount)
        assertEquals(25, completedEvent.lastTravelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, completedEvent.lastEtaSource)
        assertEquals(false, completedEvent.lastEtaStale)
        assertEquals(25, completedEvent.lastLiveTravelMinutes)
        assertEquals(now, completedEvent.lastLiveFetchedAt)
        assertTrue(!completedEvent.semanticCatchUpPending)
    }

    @Test
    fun `ADVANCE event 재시도는 immutable payload를 끝낸 뒤 DEPART NOW correction을 보낸다`() {
        val schedule = createDueSchedule(memberId = 804L)
        val job = createDueJob(schedule)
        register(schedule.memberId, "stable-device", "stable-decision-token")
        register(schedule.memberId, "retry-device", "retry-decision-token")
        trafficClient.respondWith(50, 60, 60)
        pushClient.failOnce("retry-decision-token")

        worker.runDueJobs(now)
        val retryAt = now.plus(1, ChronoUnit.MINUTES)
        worker.runDueJobs(retryAt)

        val afterRetry = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, afterRetry.checkCount)
        assertEquals(retryAt.plusSeconds(1), afterRetry.nextCheckAt)
        worker.runDueJobs(retryAt.plusSeconds(1))

        val retryCalls = pushClient.calls("retry-decision-token")
        val stableCalls = pushClient.calls("stable-decision-token")
        assertEquals(3, retryCalls.size)
        assertEquals(2, stableCalls.size)
        assertEquals(retryCalls[0].data, retryCalls[1].data)
        assertEquals("ADVANCE_NOTICE", retryCalls[1].data["departureReminderDecision"])
        assertEquals("false", retryCalls[1].data["departNow"])
        assertEquals("DEPART_NOW", retryCalls[2].data["departureReminderDecision"])
        assertEquals("true", retryCalls[2].data["departNow"])

        val persisted = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(2, persisted.checkCount)
        assertEquals(now, persisted.lastNotifiedDepartureAt)
        assertEquals(retryAt.plusSeconds(1), persisted.departureNoticeSentAt)
    }

    @Test
    fun `stale schedule worker event TTL 뒤 late confirmed failure는 provider 재시도 없이 superseded 된다`() {
        val schedule = createDueSchedule(memberId = 805L)
        val job = createDueJob(schedule)
        val token = "late-schedule-worker-token"
        register(schedule.memberId, "late-schedule-worker-device", token)
        val providerGate = pushClient.blockThenFailOnce(token)
        val firstResults = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching { worker.runDueJobs(now) }
                .onSuccess(firstResults::add)
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveries(schedule.memberId).single().status)

        val replacementAt = now.plus(11, ChronoUnit.MINUTES)
        assertEquals(1, worker.runDueJobs(replacementAt))
        val afterReplacement = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, afterReplacement.checkCount)
        assertEquals(null, afterReplacement.lastPushedAt)
        assertEquals(null, afterReplacement.lastUncertainAt)
        assertEquals(
            PushOutboxDispatchStatus.NOT_REQUIRED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(listOf(1), firstResults.toList())
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )
        val reconciled = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, reconciled.checkCount)
        assertEquals(null, reconciled.lastUncertainAt)
        assertEquals(null, reconciled.lastPushedAt)
        assertEquals(1, pushClient.attempts(token))
    }

    @Test
    fun `stale schedule worker가 ambiguous로 전진한 뒤 late success는 provider 재호출 없이 confirmed 지표를 보정한다`() {
        val schedule = createDueSchedule(memberId = 806L)
        val job = createDueJob(schedule)
        val token = "late-schedule-success-token"
        register(schedule.memberId, "late-schedule-success-device", token)
        val providerGate = pushClient.blockThenSucceedOnce(token)
        val firstResults = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching { worker.runDueJobs(now) }
                .onSuccess(firstResults::add)
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))

        val replacementAt = now.plus(11, ChronoUnit.MINUTES)
        assertEquals(1, worker.runDueJobs(replacementAt))
        val afterReplacement = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, afterReplacement.checkCount)
        assertEquals(null, afterReplacement.lastPushedAt)
        assertEquals(null, afterReplacement.lastUncertainAt)

        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(listOf(1), firstResults.toList())
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        assertEquals(1, outboxWorker.runDueEvents(replacementAt.plusSeconds(1)))

        val reconciled = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, reconciled.checkCount)
        assertEquals(null, reconciled.lastUncertainAt)
        assertEquals(now, reconciled.lastPushedAt)
        assertEquals(1, pushClient.attempts(token))
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )
    }

    @Test
    fun `stale worker TTL이 끝난 old failure는 이후 의미 편집 전에도 superseded 된다`() {
        val schedule = createDueSchedule(memberId = 807L)
        val job = createDueJob(schedule)
        val token = "edited-before-safety-token"
        register(schedule.memberId, "edited-before-safety-device", token)
        val providerGate = pushClient.blockThenFailOnce(token)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching { worker.runDueJobs(now) }
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))
        val replacementAt = now.plus(11, ChronoUnit.MINUTES)
        assertEquals(1, worker.runDueJobs(replacementAt))
        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        val edited = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        val editedScheduleAt = edited.scheduleAt.plus(5, ChronoUnit.MINUTES)
        assertTrue(
            edited.changeSchedule(
                scheduleAt = editedScheduleAt,
                departureAt = editedScheduleAt.minus(45, ChronoUnit.MINUTES),
                monitorStartAt = now,
                intervalMinutes = edited.intervalMinutes,
            )
        )
        pushJobRepository.saveAndFlush(edited)
        assertEquals(1, edited.notificationGeneration)

        assertEquals(0, outboxWorker.runDueEvents(replacementAt.plusSeconds(1)))

        assertEquals(1, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUPERSEDED, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )
        val afterSafety = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, afterSafety.notificationGeneration)
        assertEquals(0, afterSafety.checkCount)
        assertEquals(null, afterSafety.lastPushedAt)
    }

    @Test
    fun `fallback 후 정상 복구까지 각 평가가 DB에 누적되고 다른 회원 조회에는 노출되지 않는다`() {
        val schedule = createDueSchedule(memberId = 890L)
        val job = createDueJob(schedule)
        register(schedule.memberId, "history-device", "history-token")
        trafficClient.respondWithResult(TrafficResult(
            travelMinutes = 45,
            source = TrafficSource.SAVED_FALLBACK,
            stale = true,
            failureReason = "PROVIDER_TIMEOUT: token=test-secret",
        ))

        assertEquals(1, worker.runDueJobs(now))
        val failed = evaluations(schedule).single()
        val afterFallback = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(TrafficSource.SAVED_FALLBACK, failed.source)
        assertTrue(failed.stale)
        assertEquals(TrafficFailureReasons.PROVIDER_TIMEOUT, failed.failureReason)
        assertEquals(45, failed.travelMinutes)
        assertEquals(now, failed.evaluatedAt)
        assertEquals(schedule.startAt, failed.targetArrivalAt)
        assertNull(failed.fetchedAt)
        assertNull(failed.recommendedDepartureAt)
        assertNull(failed.predictedArrivalAt)
        assertEquals(job.departureAt, afterFallback.lastRecommendedDepartureAt)

        // 아직 due가 아니면 API 재조회도 새 이력도 발생하지 않는다.
        assertEquals(0, worker.runDueJobs(now))
        assertEquals(1, trafficClient.requests().size)
        assertEquals(1, evaluations(schedule).size)

        val recoveredAt = afterFallback.nextCheckAt
        trafficClient.respondWithResult(TrafficResult(
            travelMinutes = 45,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = recoveredAt.plusSeconds(2),
            stale = false,
            recommendedDepartureAt = job.departureAt,
            predictedArrivalAt = schedule.startAt,
        ))
        assertEquals(1, worker.runDueJobs(recoveredAt))

        val stored = evaluations(schedule)
        assertEquals(2, stored.size)
        val recovered = stored.first()
        assertNotEquals(failed.evaluationId, recovered.evaluationId)
        assertEquals(failed.id, stored.last().id)
        assertEquals(TrafficFailureReasons.PROVIDER_TIMEOUT, stored.last().failureReason)
        assertEquals(TrafficSource.LIVE_PROVIDER, recovered.source)
        assertEquals(false, recovered.stale)
        assertNull(recovered.failureReason)
        assertEquals(recoveredAt, recovered.evaluatedAt)
        assertEquals(recoveredAt.plusSeconds(2), recovered.fetchedAt)
        assertEquals(job.departureAt, recovered.recommendedDepartureAt)
        assertEquals(schedule.startAt, recovered.predictedArrivalAt)
        assertEquals(2, trafficClient.requests().size)
        assertTrue(pushClient.attempts("history-token") > 0)
        assertTrue(etaHistories.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
            requireNotNull(schedule.id), 999_999L, PageRequest.of(0, 10),
        ).isEmpty)
    }

    @Test
    fun `저신뢰도 시간표의 늦은 도착 진단은 Worker 보정과 별개로 그대로 기록된다`() {
        val schedule = createDueSchedule(memberId = 891L, travelMode = ScheduleTravelMode.TRANSIT)
        val job = createDueJob(schedule)
        register(schedule.memberId, "late-device", "late-token")
        val departure = now.plusSeconds(600)
        val arrival = schedule.startAt.plusSeconds(1_200)
        trafficClient.respondWithResult(TrafficResult(
            travelMinutes = 70,
            source = TrafficSource.TIMETABLE_PROVIDER,
            fetchedAt = now.plusSeconds(2),
            stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            recommendedDepartureAt = departure,
            predictedArrivalAt = arrival,
        ))

        assertEquals(1, worker.runDueJobs(now))

        val history = evaluations(schedule).single()
        assertEquals(job.id, history.jobId)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, history.source)
        assertTrue(history.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE, history.failureReason)
        assertEquals(departure, history.recommendedDepartureAt)
        assertEquals(arrival, history.predictedArrivalAt)
        assertTrue(arrival.isAfter(history.targetArrivalAt))
    }

    @Test
    fun `결과 없는 예외는 이력을 만들지 않고 다음 성공 평가부터 기록한다`() {
        val schedule = createDueSchedule(memberId = 892L)
        val job = createDueJob(schedule)
        register(schedule.memberId, "exception-device", "exception-token")
        trafficClient.failNext()

        assertEquals(1, worker.runDueJobs(now))
        assertEquals(0L, etaHistories.count())
        assertEquals(0, pushClient.attempts("exception-token"))
        val afterException = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(SchedulePushJobStatus.ACTIVE, afterException.status)
        assertEquals(1, afterException.retryCount)

        assertEquals(1, worker.runDueJobs(afterException.nextCheckAt))
        assertEquals(2, trafficClient.requests().size)
        val history = evaluations(schedule).single()
        assertEquals(afterException.nextCheckAt, history.evaluatedAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, history.source)
    }

    private fun evaluations(schedule: Schedule) =
        etaHistories.findAllByScheduleIdAndMemberIdOrderByEvaluatedAtDescIdDesc(
            requireNotNull(schedule.id), schedule.memberId, PageRequest.of(0, 20),
        ).content

    private fun createDueSchedule(
        memberId: Long,
        travelMode: ScheduleTravelMode = ScheduleTravelMode.CAR,
    ): Schedule {
        val startAt = now.plus(60, ChronoUnit.MINUTES)
        return scheduleRepository.saveAndFlush(
            Schedule(
                memberId = memberId,
                title = "Worker delivery integration",
                startAt = startAt,
                endAt = startAt.plus(30, ChronoUnit.MINUTES),
            ).apply {
                updateRoute(
                    travelMinutes = 45,
                    departAt = startAt.minus(45, ChronoUnit.MINUTES),
                    departedAt = null,
                    travelMode = travelMode,
                    locationName = "destination",
                    originName = "origin",
                    originAddress = null,
                    originLat = 37.1,
                    originLng = 127.1,
                    destinationName = "destination",
                    destinationAddress = null,
                    destinationLat = 37.2,
                    destinationLng = 127.2,
                    routeJson = null,
                    notificationEnabled = true,
                    notificationLeadMinutes = 60,
                    notificationIntervalMinutes = 20,
                )
            }
        )
    }

    private fun createDueJob(schedule: Schedule): SchedulePushJob =
        pushJobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = schedule.memberId,
                scheduleId = requireNotNull(schedule.id),
                scheduleAt = schedule.startAt,
                departureAt = schedule.startAt.minus(45, ChronoUnit.MINUTES),
                monitorStartAt = now.minus(1, ChronoUnit.MINUTES),
                intervalMinutes = 20,
            )
        )

    private fun seedCheckedJob(
        schedule: Schedule,
        previousTravelMinutes: Int,
    ): SchedulePushJob {
        val job = SchedulePushJob.create(
            memberId = schedule.memberId,
            scheduleId = requireNotNull(schedule.id),
            scheduleAt = schedule.startAt,
            departureAt = schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES),
            monitorStartAt = now.minus(30, ChronoUnit.MINUTES),
            intervalMinutes = 20,
        )
        job.startProcessing("seed-worker", now.minus(20, ChronoUnit.MINUTES))
        job.finishCheck(
            travelMinutes = previousTravelMinutes,
            recommendedDepartureAt =
                schedule.startAt.minus(previousTravelMinutes.toLong(), ChronoUnit.MINUTES),
            pushSent = false,
            notifiedDepartureAt = null,
            nextCheckAt = now,
            completeAfterCheck = false,
            etaSource = TrafficSource.LIVE_PROVIDER,
            liveFetchedAt = now.minus(20, ChronoUnit.MINUTES),
            etaStale = false,
            etaRouteFingerprint = routeFingerprint(schedule),
            now = now.minus(20, ChronoUnit.MINUTES),
        )
        return pushJobRepository.saveAndFlush(job)
    }

    private fun routeFingerprint(schedule: Schedule): String {
        val route = requireNotNull(schedule.route)
        return ScheduleEtaRouteFingerprint.calculate(
            schedule = schedule,
            travelMinutes = route.travelMinutes,
            travelMode = route.travelMode,
            originLat = route.originLat,
            originLng = route.originLng,
            routeJson = route.routeJson,
        )
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

    private fun newOutboxWorker(): PushOutboxDispatchWorker =
        PushOutboxDispatchWorker(
            notificationUseCase = notificationUseCase,
            coordinator = outboxCoordinator,
            clock = clock,
            enabled = true,
            batchSize = 1,
            maxAttempts = 3,
            retryDelaySeconds = 1,
            processingTimeoutSeconds = 600,
            confirmedDeliveryReconcilers = listOf(outboxConfirmedReconciler),
        )

    private fun deliveries(memberId: Long) =
        inboxRepository.findAllByMemberIdOrderByIdDesc(memberId)
            .flatMap { inbox ->
                deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                    memberId,
                    inbox.logicalEventKey,
                )
            }
}

@TestConfiguration
class SchedulePushJobDeliveryTestConfig {
    @Bean
    fun workerDeliveryClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T03:00:00Z"), ZoneOffset.UTC)

    @Bean
    fun workerDeliveryObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean
    fun workerDeliveryTrafficClient(): WorkerDeliveryTrafficClient =
        WorkerDeliveryTrafficClient()

    @Bean
    fun workerDeliveryPushClient(): WorkerDeliveryPushClient = WorkerDeliveryPushClient()
}

class WorkerDeliveryPushClient : PushClient {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val failOnce = ConcurrentHashMap.newKeySet<String>()
    private val calls = ConcurrentHashMap<String, CopyOnWriteArrayList<WorkerPushCall>>()
    private val blockingFailures = ConcurrentHashMap<String, WorkerBlockingProviderFailure>()
    private val blockingSuccesses = ConcurrentHashMap<String, WorkerBlockingProviderFailure>()

    fun reset() {
        attempts.clear()
        failOnce.clear()
        calls.clear()
        blockingFailures.values.forEach { it.release.countDown() }
        blockingFailures.clear()
        blockingSuccesses.values.forEach { it.release.countDown() }
        blockingSuccesses.clear()
    }

    fun failOnce(token: String) {
        failOnce += token
    }

    fun blockThenFailOnce(token: String): WorkerBlockingProviderFailure =
        WorkerBlockingProviderFailure().also { blockingFailures[token] = it }

    fun blockThenSucceedOnce(token: String): WorkerBlockingProviderFailure =
        WorkerBlockingProviderFailure().also { blockingSuccesses[token] = it }

    fun attempts(token: String): Int = attempts[token]?.get() ?: 0

    fun calls(token: String): List<WorkerPushCall> = calls[token]?.toList().orEmpty()

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        val attempt = attempts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        calls.computeIfAbsent(token) { CopyOnWriteArrayList() }
            .add(WorkerPushCall(title, body, LinkedHashMap(data)))
        blockingFailures.remove(token)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to release deterministic schedule provider failure."
            }
            throw ConfirmedPushDeliveryException("provider explicitly rejected")
        }
        blockingSuccesses.remove(token)?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to release deterministic schedule provider success."
            }
        }
        if (failOnce.remove(token)) {
            throw ConfirmedPushDeliveryException("provider explicitly rejected")
        }
        return PushSendResult("worker-message-$attempt")
    }
}

class WorkerBlockingProviderFailure {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
}

data class WorkerPushCall(
    val title: String,
    val body: String,
    val data: Map<String, String>,
)

class WorkerDeliveryTrafficClient : TrafficClient {
    private val responses = ConcurrentLinkedQueue<() -> TrafficResult>()
    private val calls = CopyOnWriteArrayList<TrafficRequest>()

    fun reset() {
        responses.clear()
        calls.clear()
    }

    fun respondWith(vararg minutes: Int) {
        minutes.forEach { value -> responses.add { live(value) } }
    }

    fun respondWithResult(vararg results: TrafficResult) {
        results.forEach { result -> responses.add { result } }
    }

    fun failNext() {
        responses.add { throw IllegalStateException("test provider unavailable") }
    }

    fun requests(): List<TrafficRequest> = calls.toList()

    override fun getTravelMinutes(request: TrafficRequest): TrafficResult {
        calls += request
        return responses.poll()?.invoke() ?: live(45)
    }

    private fun live(minutes: Int) =
        TrafficResult(
            travelMinutes = minutes,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = Instant.parse("2026-07-24T03:00:00Z"),
            stale = false,
        )
}
