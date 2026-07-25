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
import com.noLate.notification.application.service.NotificationTokenRetirementService
import com.noLate.notification.application.service.NotificationTokenWriter
import com.noLate.notification.application.service.PushDeliveryService
import com.noLate.notification.application.service.PushDeliveryWriter
import com.noLate.notification.application.service.PushEventOutboxService
import com.noLate.notification.application.service.PushEventOutboxWriter
import com.noLate.notification.application.service.PushOutboxDispatchCoordinator
import com.noLate.notification.application.service.PushOutboxDispatchWorker
import com.noLate.notification.application.service.PushOutboxDispatchWriter
import com.noLate.notification.application.service.PushRecipientAuthorizationValidator
import com.noLate.notification.application.service.PushTokenProviderLeaseService
import com.noLate.notification.application.service.PushTokenProviderLeaseWriter
import com.noLate.notification.application.service.PushTokenProviderLeaseOutcome
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
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
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
import org.springframework.mock.env.MockEnvironment
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
    NotificationTokenRetirementService::class,
    NotificationTokenWriter::class,
    PushSendHistoryService::class,
    AppNotificationService::class,
    AppNotificationWriter::class,
    PushDeliveryService::class,
    PushDeliveryWriter::class,
    NotificationUseCase::class,
    PushOutboxDispatchCoordinator::class,
    PushOutboxDispatchWriter::class,
    PushTokenProviderLeaseService::class,
    PushTokenProviderLeaseWriter::class,
    PushOutboxDispatchWorker::class,
    AccountCleanupService::class,
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
    RouteSetupReminderPolicy::class,
    SchedulePushSourceFreshnessValidator::class,
    ScheduleTravelAccessCleanupService::class,
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
        "schedule.sharing.enabled=true",
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
    private val pushEventOutboxService: PushEventOutboxService,
    private val dispatchWorker: PushOutboxDispatchWorker,
    private val notificationUseCase: NotificationUseCase,
    private val pushDeliveryService: PushDeliveryService,
    private val pushTokenProviderLeaseService: PushTokenProviderLeaseService,
    private val dispatchCoordinator: PushOutboxDispatchCoordinator,
    private val pushClient: DurableOutboxRecordingPushClient,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    private val accountCleanupService: AccountCleanupService,
    private val memberRepository: MemberRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val routeSetupReminderRepository: ScheduleRouteSetupReminderRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val travelAccessCleanupService: ScheduleTravelAccessCleanupService,
) {
    @BeforeEach
    fun clean() {
        pushClient.reset()
        deliveryRepository.deleteAll()
        historyRepository.deleteAll()
        notificationRepository.deleteAll()
        tokenRepository.deleteAll()
        departureStatusRepository.deleteAll()
        travelPlanRepository.deleteAll()
        routeSetupReminderRepository.deleteAll()
        scheduleShareRepository.deleteAll()
        scheduleRepository.deleteAll()
        calendarMemberRepository.deleteAll()
        calendarRepository.deleteAll()
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
    @EnumSource(CalendarPushGrantRevocation::class)
    fun `calendar share enqueue 뒤 membership 또는 calendar가 회수되면 redrive는 provider 없이 terminal이다`(
        revocation: CalendarPushGrantRevocation,
    ) {
        val memberId = 9_200L + revocation.ordinal
        val token = "calendar-revoked-${revocation.name.lowercase()}-token"
        register(memberId, "calendar-revoked-${revocation.name.lowercase()}-device", token)
        val calendarId = publishCalendarShare(memberId, "calendar-revoked-${revocation.name}")

        val source = notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).single()
        val delivery = deliveryRepository
            .findAllByMemberIdAndEventKeyOrderByIdAsc(memberId, source.logicalEventKey)
            .single()
        assertEquals(calendarId, source.calendarId)
        assertEquals(calendarId, delivery.calendarId)
        assertEquals(calendarId.toString(), objectMapper.readTree(source.dataJson)["calendarId"].asText())

        revokeCalendarGrant(calendarId, memberId, revocation)

        assertEquals(0, dispatchWorker.runDueEvents(NOW))
        assertEquals(0, pushClient.attempts(token))
        assertTrue(deliveryRepository.findById(requireNotNull(delivery.id)).isEmpty)
        assertTrue(notificationRepository.findById(requireNotNull(source.id)).isEmpty)
    }

    @Test
    fun `calendar membership이 delivery claim 뒤 회수되면 provider lease가 최종 차단한다`() {
        val memberId = 9_210L
        val token = "calendar-after-claim-token"
        register(memberId, "calendar-after-claim-device", token)
        val calendarId = publishCalendarShare(memberId, "calendar-after-claim")
        val source = notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).single()
        val delivery = deliveryRepository
            .findAllByMemberIdAndEventKeyOrderByIdAsc(memberId, source.logicalEventKey)
            .single()

        val claim = pushDeliveryService.claim(
            memberId = memberId,
            eventKey = source.logicalEventKey,
            deliveryId = requireNotNull(delivery.id),
        )
        assertEquals(
            com.noLate.notification.application.service.PushDeliveryClaimOutcome.SEND,
            claim.outcome,
        )
        // Deliberately bypass ScheduleTravelAccessCleanupService. This proves the provider-boundary
        // typed calendar authorization is an independent final fence after a delivery was claimed.
        TransactionTemplate(transactionManager).executeWithoutResult {
            val membership = requireNotNull(
                calendarMemberRepository.findForUpdate(calendarId, memberId)
            )
            membership.remove()
            calendarMemberRepository.saveAndFlush(membership)
        }

        val result = pushTokenProviderLeaseService.sendIfOwned(
            memberId = memberId,
            claim = claim,
            title = source.title,
            body = source.body,
            data = objectMapper.readValue(
                source.dataJson,
                objectMapper.typeFactory.constructMapType(
                    LinkedHashMap::class.java,
                    String::class.java,
                    String::class.java,
                ),
            ),
        )

        assertEquals(PushTokenProviderLeaseOutcome.SUPERSEDED, result.outcome)
        assertEquals(0, pushClient.attempts(token))
        assertEquals(
            PushDeliveryStatus.SUPERSEDED,
            deliveryRepository.findById(requireNotNull(delivery.id)).orElseThrow().status,
        )
        assertTrue(notificationRepository.findById(requireNotNull(source.id)).isPresent)
        assertEquals(1, dispatchWorker.runDueEvents(NOW))
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findById(requireNotNull(source.id)).orElseThrow().dispatchStatus,
        )
    }

    @ParameterizedTest
    @EnumSource(
        value = DurableLateProviderOutcome::class,
        names = ["SUCCESS", "CONFIRMED_FAILURE"],
    )
    fun `calendar provider 결과가 revoke cleanup 뒤 늦게 돌아와도 history를 재생성하지 않는다`(
        outcome: DurableLateProviderOutcome,
    ) {
        val memberId = 9_220L + outcome.ordinal
        val token = "calendar-late-${outcome.name.lowercase()}-token"
        register(memberId, "calendar-late-${outcome.name.lowercase()}-device", token)
        val calendarId = publishCalendarShare(memberId, "calendar-late-${outcome.name}")
        val providerGate = pushClient.blockThen(token, outcome)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            runCatching { dispatchWorker.runDueEvents(NOW) }
                .onFailure(failures::add)
        }
        assertTrue(providerGate.entered.await(5, TimeUnit.SECONDS))
        assertEquals(PushDeliveryStatus.DISPATCHING, deliveryRepository.findAll().single().status)

        // The provider request cannot be recalled, but cleanup wins the database linearization.
        // Late success/failure history must revalidate the typed calendar grant and remain a no-op.
        revokeCalendarGrant(calendarId, memberId, CalendarPushGrantRevocation.REMOVED)
        assertTrue(notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).isEmpty())
        assertTrue(deliveryRepository.findAll().none { it.memberId == memberId })
        assertTrue(historyRepository.findAll().none { it.memberId == memberId })

        providerGate.release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, pushClient.attempts(token))
        assertTrue(notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).isEmpty())
        assertTrue(deliveryRepository.findAll().none { it.memberId == memberId })
        assertTrue(historyRepository.findAll().none { it.memberId == memberId })
        assertEquals(0, dispatchWorker.runDueEvents(NOW.plusSeconds(1)))
    }

    @ParameterizedTest
    @EnumSource(RouteSetupPushInvalidation::class)
    fun `route setup enqueue 뒤 계획 완료 또는 의미 변경은 provider 없이 terminal이다`(
        invalidation: RouteSetupPushInvalidation,
    ) {
        val memberId = 9_300L + invalidation.ordinal
        val token = "route-setup-${invalidation.name.lowercase()}-token"
        register(memberId, "route-setup-${invalidation.name.lowercase()}-device", token)
        val fixture = enqueueRouteSetupReminder(memberId)

        TransactionTemplate(transactionManager).executeWithoutResult {
            when (invalidation) {
                RouteSetupPushInvalidation.PLAN_COMPLETED -> {
                    travelPlanRepository.saveAndFlush(
                        ScheduleTravelPlan(
                            scheduleId = fixture.scheduleId,
                            memberId = memberId,
                            travelMinutes = 25,
                            travelMode = ScheduleTravelMode.TRANSIT,
                            originName = "현재 출발지",
                            originLat = 37.50,
                            originLng = 127.00,
                            scheduleFingerprint = fixture.scheduleFingerprint,
                        )
                    )
                }

                RouteSetupPushInvalidation.SCHEDULE_EDITED -> {
                    val schedule = scheduleRepository.findById(fixture.scheduleId).orElseThrow()
                    schedule.startAt = schedule.startAt.plusSeconds(60)
                    scheduleRepository.saveAndFlush(schedule)
                }
            }
        }

        assertEquals(1, dispatchWorker.runDueEvents(NOW))
        assertEquals(0, pushClient.attempts(token))
        assertEquals(
            PushDeliveryStatus.SUPERSEDED,
            deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                memberId,
                fixture.logicalEventKey,
            ).single().status,
        )
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findByMemberIdAndLogicalEventKey(
                memberId,
                fixture.logicalEventKey,
            )?.dispatchStatus,
        )
    }

    @Test
    fun `departure nudge enqueue 뒤 target이 출발하면 drain은 provider를 호출하지 않는다`() {
        val memberId = 9_310L
        val requesterMemberId = 109_310L
        val token = "departed-target-nudge-token"
        ensureActivePushMember(jdbcTemplate, requesterMemberId)
        register(memberId, "departed-target-nudge-device", token)
        val scheduleId = requireNotNull(
            TransactionTemplate(transactionManager).execute {
                val schedule = scheduleRepository.saveAndFlush(
                    Schedule(
                        memberId = requesterMemberId,
                        title = "출발 확인 source freshness",
                        startAt = NOW.plusSeconds(3_600),
                        endAt = NOW.plusSeconds(7_200),
                        scheduleType = ScheduleType.ROUTE,
                    )
                )
                val persistedScheduleId = requireNotNull(schedule.id)
                scheduleShareRepository.saveAndFlush(
                    ScheduleShare(
                        scheduleId = persistedScheduleId,
                        ownerMemberId = requesterMemberId,
                        targetMemberId = memberId,
                        permission = ScheduleSharePermission.VIEWER,
                        contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
                    )
                )
                persistedScheduleId
            }
        )
        val prepared = requireNotNull(
            TransactionTemplate(transactionManager).execute {
                pushEventOutboxService.enqueueDurable(
                    memberId = memberId,
                    title = "출발 확인 요청",
                    body = "이미 출발한 대상에게는 도착하면 안 됩니다.",
                    data = mapOf(
                        "type" to "SCHEDULE_DEPARTURE_NUDGE",
                        "scheduleId" to scheduleId.toString(),
                        "requestedByMemberId" to requesterMemberId.toString(),
                    ),
                    deduplicationKey =
                        "schedule-departure-nudge:$scheduleId:$requesterMemberId:$memberId:test",
                )
            }
        )
        departureStatusRepository.saveAndFlush(
            ScheduleDepartureStatus(
                scheduleId = scheduleId,
                memberId = memberId,
                departedAt = NOW.minusSeconds(1),
            )
        )

        assertEquals(1, dispatchWorker.runDueEvents(NOW))
        assertEquals(0, pushClient.attempts(token))
        assertEquals(
            PushDeliveryStatus.SUPERSEDED,
            deliveryRepository.findAllByMemberIdAndEventKeyOrderByIdAsc(
                memberId,
                prepared.logicalEventKey,
            ).single().status,
        )
        assertEquals(
            PushOutboxDispatchStatus.COMPLETED,
            notificationRepository.findByMemberIdAndLogicalEventKey(
                memberId,
                prepared.logicalEventKey,
            )?.dispatchStatus,
        )
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
        assertNotificationRowsAbsentExceptRetiredProviderLease(memberId)

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

    private fun assertNotificationRowsAbsentExceptRetiredProviderLease(memberId: Long) {
        assertTrue(notificationRepository.findAllByMemberIdOrderByIdDesc(memberId).isEmpty())
        assertTrue(deliveryRepository.findAll().none { it.memberId == memberId })
        assertTrue(historyRepository.findAll().none { it.memberId == memberId })
        val retired = tokenRepository.findAllByMemberId(memberId).single()
        assertTrue(retired.retirementRequested)
        assertTrue(retired.dispatchLeaseId != null)
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

    private fun publishCalendarShare(memberId: Long, eventId: String): Long {
        val ownerMemberId = memberId + 100_000L
        ensureActivePushMember(jdbcTemplate, ownerMemberId)
        return requireNotNull(
            TransactionTemplate(transactionManager).execute {
                val calendar = calendarRepository.saveAndFlush(
                    ScheduleCalendar(
                        ownerMemberId = ownerMemberId,
                        title = "frozen shared calendar",
                    )
                )
                val calendarId = requireNotNull(calendar.id)
                calendarMemberRepository.saveAndFlush(
                    ScheduleCalendarMember(
                        calendarId = calendarId,
                        memberId = memberId,
                        role = ScheduleCalendarRole.VIEWER,
                    )
                )
                publisher.publishEvent(
                    ScheduleShareGrantedEvent(
                        targetMemberId = memberId,
                        resourceType = ScheduleShareResourceType.CALENDAR,
                        resourceId = calendarId,
                        resourceTitle = calendar.title,
                        notificationEventId = eventId,
                    )
                )
                calendarId
            }
        )
    }

    private fun enqueueRouteSetupReminder(memberId: Long): RouteSetupPushFixture =
        requireNotNull(
            TransactionTemplate(transactionManager).execute {
                val schedule = Schedule(
                    memberId = memberId,
                    title = "provider 전 경로 신선도",
                    startAt = NOW.plusSeconds(24 * 60 * 60),
                    endAt = NOW.plusSeconds(25 * 60 * 60),
                    scheduleType = ScheduleType.ROUTE,
                ).apply {
                    updateRoute(
                        travelMinutes = null,
                        departAt = null,
                        departedAt = null,
                        travelMode = null,
                        locationName = "목적지",
                        originName = null,
                        originAddress = null,
                        originLat = null,
                        originLng = null,
                        destinationName = "목적지",
                        destinationAddress = null,
                        destinationLat = 37.55,
                        destinationLng = 126.97,
                        routeJson = null,
                        notificationEnabled = false,
                        notificationLeadMinutes = null,
                        notificationIntervalMinutes = null,
                    )
                }
                val savedSchedule = scheduleRepository.saveAndFlush(schedule)
                val scheduleId = requireNotNull(savedSchedule.id)
                val fingerprint = ScheduleTravelPlanFingerprint.calculate(savedSchedule)
                val marker = routeSetupReminderRepository.saveAndFlush(
                    ScheduleRouteSetupReminder(
                        scheduleId = scheduleId,
                        memberId = memberId,
                        scheduleFingerprint = fingerprint,
                        nextAttemptAt = NOW,
                    )
                )
                marker.markSent(NOW)
                routeSetupReminderRepository.saveAndFlush(marker)
                val prepared = pushEventOutboxService.enqueueDurable(
                    memberId = memberId,
                    title = "경로를 설정해주세요",
                    body = "현재 일정에 필요한 경로를 설정해주세요.",
                    data = mapOf(
                        "type" to "ROUTE_SETUP_REMINDER",
                        "scheduleId" to scheduleId.toString(),
                        "scheduleIds" to scheduleId.toString(),
                        "count" to "1",
                        "routeSetupReminderId" to requireNotNull(marker.id).toString(),
                        "routeSetupScheduleFingerprint" to fingerprint,
                    ),
                    deduplicationKey =
                        "route-setup:$memberId:marker:${requireNotNull(marker.id)}",
                )
                RouteSetupPushFixture(
                    scheduleId = scheduleId,
                    scheduleFingerprint = fingerprint,
                    logicalEventKey = prepared.logicalEventKey,
                )
            }
        )

    private fun revokeCalendarGrant(
        calendarId: Long,
        memberId: Long,
        revocation: CalendarPushGrantRevocation,
    ) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            when (revocation) {
                CalendarPushGrantRevocation.REMOVED -> {
                    val membership = requireNotNull(
                        calendarMemberRepository.findForUpdate(calendarId, memberId)
                    )
                    membership.remove()
                    calendarMemberRepository.saveAndFlush(membership)
                }

                CalendarPushGrantRevocation.LEFT -> {
                    val membership = requireNotNull(
                        calendarMemberRepository.findForUpdate(calendarId, memberId)
                    )
                    membership.leave()
                    calendarMemberRepository.saveAndFlush(membership)
                }

                CalendarPushGrantRevocation.ARCHIVED -> {
                    val calendar = requireNotNull(calendarRepository.findActiveForUpdate(calendarId))
                    calendar.archive()
                    calendarRepository.saveAndFlush(calendar)
                }
            }
            travelAccessCleanupService.cancelRevokedForCalendar(calendarId, listOf(memberId))
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

    /**
     * Existing tests in this slice intentionally use synthetic schedule/category ids. Keep those
     * fixtures accepted while exercising the production shared-calendar validator verbatim.
     */
    @Bean
    fun durableOutboxRecipientAuthorizationValidator(
        scheduleRepository: ScheduleRepository,
        scheduleShareRepository: ScheduleShareRepository,
        categoryRepository: ScheduleCategoryRepository,
        categoryShareRepository: ScheduleCategoryShareRepository,
        calendarRepository: ScheduleCalendarRepository,
        calendarMemberRepository: ScheduleCalendarMemberRepository,
    ): PushRecipientAuthorizationValidator {
        val accessPolicy = ScheduleAccessPolicy(
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            calendarRepository = calendarRepository,
            calendarMemberRepository = calendarMemberRepository,
            categoryRepository = categoryRepository,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true")
            ),
        )
        val delegate = SchedulePushRecipientAccessValidator(
            scheduleRepository = scheduleRepository,
            accessPolicy = accessPolicy,
            categoryRepository = categoryRepository,
            categoryShareRepository = categoryShareRepository,
            calendarRepository = calendarRepository,
            calendarMemberRepository = calendarMemberRepository,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true")
            ),
        )
        return object : PushRecipientAuthorizationValidator {
            override fun canDispatch(
                memberId: Long,
                scheduleId: Long?,
                categoryId: Long?,
                payloadType: String?,
                calendarId: Long?,
            ): Boolean =
                if (calendarId != null || payloadType == "CALENDAR_SHARE_RECEIVED") {
                    delegate.canDispatch(
                        memberId = memberId,
                        scheduleId = scheduleId,
                        categoryId = categoryId,
                        payloadType = payloadType,
                        calendarId = calendarId,
                    )
                } else {
                    true
                }
        }
    }
}

enum class CalendarPushGrantRevocation {
    REMOVED,
    LEFT,
    ARCHIVED,
}

enum class RouteSetupPushInvalidation {
    PLAN_COMPLETED,
    SCHEDULE_EDITED,
}

data class RouteSetupPushFixture(
    val scheduleId: Long,
    val scheduleFingerprint: String,
    val logicalEventKey: String,
)

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
