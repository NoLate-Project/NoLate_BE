package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import com.noLate.notification.application.service.PushOutboxDispatchCoordinator
import com.noLate.notification.application.service.PushOutboxDispatchWorker
import com.noLate.notification.application.service.PushOutboxDispatchWriter
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
import com.noLate.notification.support.registerAuthenticatedPushToken
import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.service.policy.DepartureReminderPolicy
import com.noLate.schedule.application.service.policy.PeriodicPushPolicy
import com.noLate.schedule.application.service.policy.TrafficChangePolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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

@DataJpaTest
@Import(
    SchedulePushJobWorker::class,
    SchedulePushJobCoordinator::class,
    PeriodicPushPolicy::class,
    DepartureReminderPolicy::class,
    TrafficChangePolicy::class,
    NotificationTokenService::class,
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
    PushOutboxDispatchWorker::class,
    SchedulePersistedPushDispatchFenceFactory::class,
    SchedulePushOutboxConfirmedDeliveryReconciler::class,
    SchedulePushJobDeliveryTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push-delivery;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.batch-size=10",
        "schedule.push.retry-delay-minutes=5",
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
) {
    private val now = Instant.parse("2026-07-24T03:00:00Z")

    @BeforeEach
    fun clean() {
        pushClient.reset()
        trafficClient.reset()
        deliveryRepository.deleteAll()
        historyRepository.deleteAll()
        inboxRepository.deleteAll()
        tokenRepository.deleteAll()
        pushJobRepository.deleteAll()
        scheduleRepository.deleteAll()
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
        assertEquals(now.plus(5, ChronoUnit.MINUTES), afterPartial.nextCheckAt)
        assertEquals(now, afterPartial.lastPushedAt)
        assertEquals(
            setOf(PushDeliveryStatus.SUCCESS, PushDeliveryStatus.FAILED),
            deliveries(schedule.memberId).map { it.status }.toSet(),
        )

        assertEquals(1, worker.runDueJobs(now.plus(5, ChronoUnit.MINUTES)))

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
    fun `부분 실패 재시도는 ETA가 회복돼도 최초 immutable payload만 실패 기기에 보낸다`() {
        val schedule = createDueSchedule(memberId = 803L)
        val job = seedCheckedJob(schedule, previousTravelMinutes = 30)
        register(schedule.memberId, "stable-device", "stable-snapshot-token")
        register(schedule.memberId, "retry-device", "retry-snapshot-token")
        trafficClient.respondWith(40, 25)
        pushClient.failOnce("retry-snapshot-token")

        assertEquals(1, worker.runDueJobs(now))
        assertEquals(1, worker.runDueJobs(now.plus(5, ChronoUnit.MINUTES)))

        val stableCalls = pushClient.calls("stable-snapshot-token")
        val retryCalls = pushClient.calls("retry-snapshot-token")
        assertEquals(1, stableCalls.size)
        assertEquals(2, retryCalls.size)
        assertEquals(retryCalls[0].title, retryCalls[1].title)
        assertEquals(retryCalls[0].body, retryCalls[1].body)
        assertEquals(retryCalls[0].data, retryCalls[1].data)
        assertEquals("40", retryCalls[1].data["travelMinutes"])
        assertEquals("10", retryCalls[1].data["trafficChangeMinutes"])
        assertEquals("803", retryCalls[1].data["recipientMemberId"])
        assertEquals(
            inboxRepository.findAllByMemberIdOrderByIdDesc(803L).single().logicalEventKey,
            retryCalls[1].data["logicalEventKey"],
        )

        val completedEvent = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(2, completedEvent.checkCount)
        assertEquals(25, completedEvent.lastTravelMinutes)
    }

    @Test
    fun `ADVANCE event 재시도 중 DEPART NOW 경계를 넘어도 최초 decision payload를 유지한다`() {
        val schedule = createDueSchedule(memberId = 804L)
        val job = createDueJob(schedule)
        register(schedule.memberId, "stable-device", "stable-decision-token")
        register(schedule.memberId, "retry-device", "retry-decision-token")
        trafficClient.respondWith(50, 60)
        pushClient.failOnce("retry-decision-token")

        worker.runDueJobs(now)
        worker.runDueJobs(now.plus(5, ChronoUnit.MINUTES))

        val retryCalls = pushClient.calls("retry-decision-token")
        assertEquals(2, retryCalls.size)
        assertEquals(retryCalls[0].data, retryCalls[1].data)
        assertEquals("ADVANCE_NOTICE", retryCalls[1].data["departureReminderDecision"])
        assertEquals("false", retryCalls[1].data["departNow"])

        val persisted = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, persisted.checkCount)
        assertEquals(now.plus(5, ChronoUnit.MINUTES).plusSeconds(1), persisted.nextCheckAt)
        assertEquals(now.plus(10, ChronoUnit.MINUTES), persisted.lastNotifiedDepartureAt)
        assertEquals(null, persisted.departureNoticeSentAt)
    }

    @Test
    fun `stale schedule worker가 ambiguous로 전진한 뒤 late confirmed failure는 같은 event를 재시도하고 confirmed 지표를 보정한다`() {
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
        assertEquals(replacementAt, afterReplacement.lastUncertainAt)
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
        assertEquals(PushDeliveryStatus.FAILED, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        // The first safety-outbox attempt itself now stalls beyond its lease and is reclaimed.
        // Its replacement sees DISPATCHING as ambiguous and closes only that replacement view.
        val safetyProviderGate = pushClient.blockThenFailOnce(token)
        val safetyResults = ConcurrentLinkedQueue<Int>()
        val safetyExecutor = Executors.newSingleThreadExecutor()
        val staleSafetyWorker = newOutboxWorker()
        val replacementSafetyWorker = newOutboxWorker()
        safetyExecutor.submit {
            runCatching { staleSafetyWorker.runDueEvents(replacementAt.plusSeconds(1)) }
                .onSuccess(safetyResults::add)
                .onFailure(failures::add)
        }
        assertTrue(safetyProviderGate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(
            PushOutboxDispatchStatus.PROCESSING,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        assertEquals(1, replacementSafetyWorker.runDueEvents(replacementAt.plusSeconds(602)))
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        // The stale safety-outbox provider result is now a confirmed rejection. Its source lease
        // identity no longer matches attempt 1, so delivery FAILED and outbox PENDING reopen
        // atomically even though the replacement attempt already completed.
        safetyProviderGate.release.countDown()
        safetyExecutor.shutdown()
        assertTrue(safetyExecutor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(listOf(1), safetyResults.toList())
        assertEquals(PushDeliveryStatus.FAILED, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )

        // The third outbox attempt safely retries only the confirmed-failed device, then
        // reconciles confirmed schedule metrics before completing its still-owned source lease.
        assertEquals(1, newOutboxWorker().runDueEvents(replacementAt.plusSeconds(603)))

        val reconciled = pushJobRepository.findById(requireNotNull(job.id)).orElseThrow()
        assertEquals(1, reconciled.checkCount)
        assertEquals(replacementAt, reconciled.lastUncertainAt)
        assertEquals(now, reconciled.lastPushedAt)
        assertEquals(3, pushClient.attempts(token))
        assertEquals(PushDeliveryStatus.SUCCESS, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            inboxRepository.findAllByMemberIdOrderByIdDesc(schedule.memberId)
                .single()
                .dispatchStatus,
        )
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
        assertEquals(replacementAt, afterReplacement.lastUncertainAt)

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
        assertEquals(replacementAt, reconciled.lastUncertainAt)
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
    fun `schedule safety outbox보다 의미 편집이 먼저면 old generation 실패 기기는 provider 없이 superseded 된다`() {
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
        assertEquals(PushDeliveryStatus.FAILED, deliveries(schedule.memberId).single().status)
        assertEquals(
            PushOutboxDispatchStatus.PENDING,
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

        assertEquals(1, outboxWorker.runDueEvents(replacementAt.plusSeconds(1)))

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

    private fun createDueSchedule(memberId: Long): Schedule {
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
                    travelMode = ScheduleTravelMode.CAR,
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
            now = now.minus(20, ChronoUnit.MINUTES),
        )
        return pushJobRepository.saveAndFlush(job)
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
    private val responses = ConcurrentLinkedQueue<Int>()

    fun reset() {
        responses.clear()
    }

    fun respondWith(vararg minutes: Int) {
        responses.addAll(minutes.toList())
    }

    override fun getTravelMinutes(request: TrafficRequest): Int =
        responses.poll() ?: 45
}
