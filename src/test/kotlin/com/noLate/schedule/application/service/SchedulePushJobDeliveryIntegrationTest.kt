package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationWriter
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDeliveryWriter
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.PushSendHistoryRepository
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
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@DataJpaTest
@Import(
    SchedulePushJobWorker::class,
    SchedulePushJobCoordinator::class,
    PeriodicPushPolicy::class,
    DepartureReminderPolicy::class,
    TrafficChangePolicy::class,
    NotificationTokenService::class,
    PushSendHistoryService::class,
    AppNotificationService::class,
    AppNotificationWriter::class,
    PushDeliveryService::class,
    PushDeliveryWriter::class,
    NotificationUseCase::class,
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
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePushJobDeliveryIntegrationTest @Autowired constructor(
    private val worker: SchedulePushJobWorker,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val tokenService: NotificationTokenService,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val inboxRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val historyRepository: PushSendHistoryRepository,
    private val pushClient: WorkerDeliveryPushClient,
) {
    private val now = Instant.parse("2026-07-24T03:00:00Z")

    @BeforeEach
    fun clean() {
        pushClient.reset()
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
            mapOf("stable-device" to 1, "retry-device" to 2),
            deliveries(schedule.memberId).associate { it.deviceId to it.attemptCount },
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
            .flatMap { inbox ->
                deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                    memberId,
                    "inbox:${inbox.id}",
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
    fun workerDeliveryTrafficClient(): TrafficClient =
        object : TrafficClient {
            override fun getTravelMinutes(request: TrafficRequest): Int = 45
        }

    @Bean
    fun workerDeliveryPushClient(): WorkerDeliveryPushClient = WorkerDeliveryPushClient()
}

class WorkerDeliveryPushClient : PushClient {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val failOnce = ConcurrentHashMap.newKeySet<String>()

    fun reset() {
        attempts.clear()
        failOnce.clear()
    }

    fun failOnce(token: String) {
        failOnce += token
    }

    fun attempts(token: String): Int = attempts[token]?.get() ?: 0

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        val attempt = attempts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        if (failOnce.remove(token)) {
            throw ConfirmedPushDeliveryException("provider explicitly rejected")
        }
        return PushSendResult("worker-message-$attempt")
    }
}
