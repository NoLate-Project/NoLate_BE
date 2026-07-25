package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushOutboxDispatchWorker
import com.noLate.notification.application.service.PushTokenProviderLeaseObserver
import com.noLate.notification.domain.PushDeliveryStatus
import com.noLate.notification.domain.PushOutboxDispatchStatus
import com.noLate.notification.domain.PushPlatform
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.notification.infrastructure.PushDeliveryRepository
import com.noLate.notification.infrastructure.NotificationDeviceTokenRepository
import com.noLate.schedule.application.useCase.ScheduleUseCase
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleRouteSetupReminder
import com.noLate.schedule.domain.ScheduleRouteSetupReminderStatus
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.jdbc.core.JdbcTemplate
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(classes = [com.noLate.NoLateApplication::class, AncillaryFreshnessTestConfig::class])
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ancillary-push-source-freshness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "schedule.sharing.enabled=true",
        "notification.push-outbox.enabled=true",
        "notification.push-outbox.batch-size=1",
        "notification.push-outbox.max-attempts=3",
        "notification.push-outbox.retry-delay-seconds=1",
        "firebase.enabled=false",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleAncillaryPushSourceFreshnessIntegrationTest @Autowired constructor(
    private val routeDispatchWriter: ScheduleRouteSetupReminderDispatchWriter,
    private val departureNotificationService: ScheduleDepartureNotificationService,
    private val scheduleUseCase: ScheduleUseCase,
    private val scheduleService: ScheduleService,
    private val outboxWorker: PushOutboxDispatchWorker,
    private val tokenService: NotificationTokenService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val shareRepository: ScheduleShareRepository,
    private val routeMarkerRepository: ScheduleRouteSetupReminderRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val sourceRepository: AppNotificationRepository,
    private val deliveryRepository: PushDeliveryRepository,
    private val tokenRepository: NotificationDeviceTokenRepository,
    private val pushClient: AncillaryFreshnessPushClient,
    private val providerLeaseObserver: AncillaryFreshnessProviderLeaseObserver,
    private val routeDispatchObserver: AncillaryFreshnessRouteDispatchObserver,
    private val objectMapper: ObjectMapper,
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @BeforeEach
    fun clean() {
        providerLeaseObserver.reset()
        routeDispatchObserver.reset()
        pushClient.reset()
        deliveryRepository.deleteAll()
        sourceRepository.deleteAll()
        tokenRepository.deleteAll()
        departureStatusRepository.deleteAll()
        routeMarkerRepository.deleteAll()
        travelPlanRepository.deleteAll()
        pushJobRepository.deleteAll()
        shareRepository.deleteAll()
        scheduleRepository.deleteAll()
        calendarMemberRepository.deleteAll()
        calendarRepository.deleteAll()
        categoryRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `route source claimed before a complete plan is superseded at provider lease`() {
        val fixture = routeFixture("route-complete")
        enqueueRouteSource(fixture)
        val result = runClaimBlocked {
            travelPlanRepository.saveAndFlush(
                ScheduleTravelPlan(
                    scheduleId = fixture.scheduleId,
                    memberId = fixture.targetMemberId,
                    travelMinutes = 25,
                    travelMode = ScheduleTravelMode.TRANSIT,
                    originName = "집",
                    originLat = 37.50,
                    originLng = 127.00,
                    routeJson = "{}",
                    scheduleFingerprint = fixture.fingerprint,
                )
            )
        }

        assertEquals(1, result)
        assertSupersededWithoutProvider(fixture.targetMemberId, fixture.token)
    }

    @Test
    fun `route source keeps its frozen fingerprint and rejects a meaningful schedule edit`() {
        val fixture = routeFixture("route-edit")
        enqueueRouteSource(fixture)
        val result = runClaimBlocked {
            val schedule = scheduleRepository.findById(fixture.scheduleId).orElseThrow()
            schedule.startAt = schedule.startAt.plusSeconds(900)
            schedule.endAt = schedule.endAt.plusSeconds(900)
            scheduleRepository.saveAndFlush(schedule)
            assertTrue(
                ScheduleTravelPlanFingerprint.calculate(schedule) != fixture.fingerprint,
            )
        }

        assertEquals(1, result)
        assertSupersededWithoutProvider(fixture.targetMemberId, fixture.token)
    }

    @Test
    fun `departure nudge claimed before target departure is superseded at provider lease`() {
        val fixture = routeFixture("nudge-departed")
        departureNotificationService.sendDepartureNudge(
            ownerMemberId = fixture.ownerMemberId,
            scheduleId = fixture.scheduleId,
            targetMemberId = fixture.targetMemberId,
            presentedSessionGeneration = 0L,
        )
        val result = runClaimBlocked {
            departureStatusRepository.saveAndFlush(
                ScheduleDepartureStatus(
                    scheduleId = fixture.scheduleId,
                    memberId = fixture.targetMemberId,
                    departedAt = NOW,
                )
            )
        }

        assertEquals(1, result)
        assertSupersededWithoutProvider(fixture.targetMemberId, fixture.token)
    }

    @Test
    fun `route source missing its frozen fingerprint is rejected before provider`() {
        val fixture = routeFixture("route-malformed")
        enqueueRouteSource(fixture)
        rewriteCanonicalData(fixture.targetMemberId) {
            remove("routeSetupScheduleFingerprint")
        }

        assertEquals(1, outboxWorker.runDueEvents(NOW))
        assertSupersededWithoutProvider(fixture.targetMemberId, fixture.token, expectedAttemptCount = 0)
    }

    @Test
    fun `nudge source whose frozen requester mismatches its event identity is rejected`() {
        val fixture = routeFixture("nudge-malformed")
        departureNotificationService.sendDepartureNudge(
            ownerMemberId = fixture.ownerMemberId,
            scheduleId = fixture.scheduleId,
            targetMemberId = fixture.targetMemberId,
            presentedSessionGeneration = 0L,
        )
        rewriteCanonicalData(fixture.targetMemberId) {
            this["requestedByMemberId"] = (fixture.ownerMemberId + 1).toString()
        }

        assertEquals(1, outboxWorker.runDueEvents(NOW))
        assertSupersededWithoutProvider(fixture.targetMemberId, fixture.token, expectedAttemptCount = 0)
    }

    @Test
    fun `route candidate read before plan completion rechecks after member lock and creates no outbox`() {
        val fixture = routeFixture("route-candidate-plan-race")
        val marker = routeMarkerRepository.saveAndFlush(
            ScheduleRouteSetupReminder(
                scheduleId = fixture.scheduleId,
                memberId = fixture.targetMemberId,
                scheduleFingerprint = fixture.fingerprint,
                nextAttemptAt = NOW,
            )
        )
        val gate = routeDispatchObserver.arm()
        val result = AtomicReference<RouteSetupOutboxEnqueueOutcome>()
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            runCatching { routeDispatchWriter.enqueueNext(NOW) }
                .onSuccess(result::set)
                .onFailure(failure::set)
        }
        try {
            assertTrue(gate.afterCandidateRead.await(10, TimeUnit.SECONDS))
            transactions.executeWithoutResult {
                memberRepository.findByIdForUpdate(fixture.targetMemberId)
                    ?: error("fixture member disappeared")
                travelPlanRepository.saveAndFlush(
                    ScheduleTravelPlan(
                        scheduleId = fixture.scheduleId,
                        memberId = fixture.targetMemberId,
                        travelMinutes = 25,
                        travelMode = ScheduleTravelMode.TRANSIT,
                        originName = "집",
                        originLat = 37.50,
                        originLng = 127.00,
                        routeJson = "{}",
                        scheduleFingerprint = fixture.fingerprint,
                    )
                )
            }
            gate.allowMemberLock.countDown()
            future.get(10, TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError(it) }
        } finally {
            gate.allowMemberLock.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertEquals(RouteSetupOutboxEnqueueOutcome.SKIPPED, result.get())
        assertTrue(sourceRepository.findAllByMemberIdOrderByIdDesc(fixture.targetMemberId).isEmpty())
        assertEquals(
            ScheduleRouteSetupReminderStatus.CANCELLED,
            routeMarkerRepository.findById(requireNotNull(marker.id)).orElseThrow().status,
        )
        assertEquals(0, pushClient.attempts(fixture.token))
    }

    @Test
    fun `calendar detach cleans calendar-only travel state and pending provider work`() {
        val owner = memberRepository.saveAndFlush(member("calendar-detach-owner"))
        val target = memberRepository.saveAndFlush(member("calendar-detach-target"))
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = ownerId,
                title = "업무",
                color = "#123456",
            )
        )
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(
                ownerMemberId = ownerId,
                title = "이동 공유",
                defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )
        val calendarId = requireNotNull(calendar.id)
        calendarMemberRepository.saveAllAndFlush(
            listOf(
                ScheduleCalendarMember(
                    calendarId = calendarId,
                    memberId = ownerId,
                    role = ScheduleCalendarRole.OWNER,
                ),
                ScheduleCalendarMember(
                    calendarId = calendarId,
                    memberId = targetId,
                    role = ScheduleCalendarRole.VIEWER,
                ),
            )
        )
        val schedule = Schedule(
            memberId = ownerId,
            calendarId = calendarId,
            title = "calendar detach",
            startAt = NOW.plusSeconds(86_400),
            endAt = NOW.plusSeconds(90_000),
            scheduleType = ScheduleType.ROUTE,
        ).apply {
            updateCategorySnapshot(
                categoryId = requireNotNull(category.id).toString(),
                title = category.title,
                color = category.color,
            )
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
                destinationAddress = "서울",
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
        val plan = travelPlanRepository.saveAndFlush(
            ScheduleTravelPlan(
                scheduleId = scheduleId,
                memberId = targetId,
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
                scheduleFingerprint = fingerprint,
            )
        )
        val job = pushJobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = targetId,
                scheduleId = scheduleId,
                scheduleAt = savedSchedule.startAt,
                departureAt = savedSchedule.startAt.minusSeconds(1_800),
                monitorStartAt = savedSchedule.startAt.minusSeconds(3_600),
                intervalMinutes = 20,
            )
        )
        val marker = routeMarkerRepository.saveAndFlush(
            ScheduleRouteSetupReminder(
                scheduleId = scheduleId,
                memberId = targetId,
                scheduleFingerprint = fingerprint,
                nextAttemptAt = NOW,
            )
        )
        val token = "calendar-detach-token"
        tokenService.registerToken(
            memberId = targetId,
            deviceId = "calendar-detach-device",
            platform = PushPlatform.ANDROID,
            token = token,
            accessTokenIssuedAt = NOW.minusSeconds(60),
            accessTokenSessionGeneration = 0L,
        )
        assertEquals(RouteSetupOutboxEnqueueOutcome.ENQUEUED, routeDispatchWriter.enqueueNext(NOW))
        assertEquals(1, sourceRepository.findAllByMemberIdOrderByIdDesc(targetId).size)
        assertEquals(1, deliveryRepository.findAll().count { it.memberId == targetId })

        val current = scheduleService.getScheduleDetail(ownerId, scheduleId)
        val dispatchCount = runClaimBlocked {
            scheduleUseCase.updateSchedule(
                memberId = ownerId,
                scheduleId = scheduleId,
                scheduleDto = current.copy(calendarId = null),
                presentedSessionGeneration = 0L,
            )
        }

        assertEquals(1, dispatchCount)
        assertEquals(null, scheduleRepository.findById(scheduleId).orElseThrow().calendarId)
        assertTrue(travelPlanRepository.findById(requireNotNull(plan.id)).orElseThrow().deleted)
        assertEquals(
            SchedulePushJobStatus.CANCELED,
            pushJobRepository.findById(requireNotNull(job.id)).orElseThrow().status,
        )
        assertEquals(
            ScheduleRouteSetupReminderStatus.CANCELLED,
            routeMarkerRepository.findById(requireNotNull(marker.id)).orElseThrow().status,
        )
        assertTrue(sourceRepository.findAllByMemberIdOrderByIdDesc(targetId).isEmpty())
        assertTrue(deliveryRepository.findAll().none { it.memberId == targetId })
        assertEquals(0, outboxWorker.runDueEvents(NOW))
        assertEquals(0, pushClient.attempts(token))
    }

    private fun enqueueRouteSource(fixture: RouteFixture) {
        routeMarkerRepository.saveAndFlush(
            ScheduleRouteSetupReminder(
                scheduleId = fixture.scheduleId,
                memberId = fixture.targetMemberId,
                scheduleFingerprint = fixture.fingerprint,
                nextAttemptAt = NOW,
            )
        )
        assertEquals(RouteSetupOutboxEnqueueOutcome.ENQUEUED, routeDispatchWriter.enqueueNext(NOW))
        val source = sourceRepository.findAllByMemberIdOrderByIdDesc(fixture.targetMemberId).single()
        assertTrue(source.dataJson.contains("\"routeSetupReminderId\""))
        assertTrue(source.dataJson.contains(fixture.fingerprint))
    }

    private fun runClaimBlocked(mutation: () -> Unit): Int {
        val gate = providerLeaseObserver.arm()
        val result = AtomicReference<Int>()
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            runCatching { outboxWorker.runDueEvents(NOW) }
                .onSuccess(result::set)
                .onFailure(failure::set)
        }
        try {
            assertTrue(gate.beforeLease.await(10, TimeUnit.SECONDS))
            transactions.executeWithoutResult { mutation() }
            gate.allowLease.countDown()
            future.get(10, TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError(it) }
            return requireNotNull(result.get())
        } finally {
            gate.allowLease.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun rewriteCanonicalData(
        memberId: Long,
        rewrite: MutableMap<String, String>.() -> Unit,
    ) {
        val source = sourceRepository.findAllByMemberIdOrderByIdDesc(memberId).single()
        val mapType = objectMapper.typeFactory.constructMapType(
            LinkedHashMap::class.java,
            String::class.java,
            String::class.java,
        )
        val data = objectMapper.readValue<MutableMap<String, String>>(source.dataJson, mapType)
        data.rewrite()
        assertEquals(
            1,
            jdbcTemplate.update(
                "UPDATE app_notifications SET data_json = ? WHERE id = ?",
                objectMapper.writeValueAsString(data),
                requireNotNull(source.id),
            ),
        )
    }

    private fun assertSupersededWithoutProvider(
        memberId: Long,
        token: String,
        expectedAttemptCount: Int = 1,
    ) {
        assertEquals(0, pushClient.attempts(token))
        val source = sourceRepository.findAllByMemberIdOrderByIdDesc(memberId).single()
        assertEquals(PushOutboxDispatchStatus.COMPLETED, source.dispatchStatus)
        val delivery = deliveryRepository
            .findAllByMemberIdAndEventKeyOrderByIdAsc(memberId, source.logicalEventKey)
            .single()
        assertEquals(PushDeliveryStatus.SUPERSEDED, delivery.status)
        assertEquals(expectedAttemptCount, delivery.attemptCount)
    }

    private fun routeFixture(key: String): RouteFixture {
        val owner = memberRepository.saveAndFlush(member("$key-owner"))
        val target = memberRepository.saveAndFlush(member("$key-target"))
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = key,
                startAt = NOW.plusSeconds(86_400),
                endAt = NOW.plusSeconds(90_000),
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
                    destinationAddress = "서울",
                    destinationLat = 37.55,
                    destinationLng = 126.97,
                    routeJson = null,
                    notificationEnabled = false,
                    notificationLeadMinutes = null,
                    notificationIntervalMinutes = null,
                )
            }
        )
        val scheduleId = requireNotNull(schedule.id)
        shareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = scheduleId,
                ownerMemberId = ownerId,
                targetMemberId = targetId,
                permission = ScheduleSharePermission.VIEWER,
                contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )
        val token = "$key-token"
        tokenService.registerToken(
            memberId = targetId,
            deviceId = "$key-device",
            platform = PushPlatform.ANDROID,
            token = token,
            accessTokenIssuedAt = NOW.minusSeconds(60),
            accessTokenSessionGeneration = 0L,
        )
        return RouteFixture(
            ownerMemberId = ownerId,
            targetMemberId = targetId,
            scheduleId = scheduleId,
            fingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
            token = token,
        )
    }

    private fun member(key: String) = Member(
        name = key,
        password = "Password1!",
        email = "$key@nolate.test",
        loginType = LoginType.COMMON,
        sessionGeneration = 0L,
    )

    private data class RouteFixture(
        val ownerMemberId: Long,
        val targetMemberId: Long,
        val scheduleId: Long,
        val fingerprint: String,
        val token: String,
    )

    companion object {
        private val NOW = Instant.parse("2026-07-25T03:00:00Z")
    }
}

@TestConfiguration
class AncillaryFreshnessTestConfig {
    @Bean
    @Primary
    fun ancillaryFreshnessClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneOffset.UTC)

    @Bean
    @Primary
    fun ancillaryFreshnessPushClient(): AncillaryFreshnessPushClient =
        AncillaryFreshnessPushClient()

    @Bean
    fun ancillaryFreshnessProviderLeaseObserver(): AncillaryFreshnessProviderLeaseObserver =
        AncillaryFreshnessProviderLeaseObserver()

    @Bean
    fun ancillaryFreshnessRouteDispatchObserver(): AncillaryFreshnessRouteDispatchObserver =
        AncillaryFreshnessRouteDispatchObserver()
}

class AncillaryFreshnessPushClient : PushClient {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    fun reset() = attempts.clear()

    fun attempts(token: String): Int = attempts[token]?.get() ?: 0

    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        val attempt = attempts.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
        return PushSendResult("ancillary-message-$attempt")
    }
}

class AncillaryFreshnessProviderLeaseObserver : PushTokenProviderLeaseObserver {
    private val armed = AtomicReference<AncillaryFreshnessProviderLeaseGate?>()

    fun arm(): AncillaryFreshnessProviderLeaseGate =
        AncillaryFreshnessProviderLeaseGate().also {
            check(armed.compareAndSet(null, it))
        }

    fun reset() {
        armed.getAndSet(null)?.allowLease?.countDown()
    }

    override fun beforeOwnershipLease(tokenId: Long) {
        val gate = armed.getAndSet(null) ?: return
        gate.beforeLease.countDown()
        check(gate.allowLease.await(10, TimeUnit.SECONDS))
    }
}

class AncillaryFreshnessProviderLeaseGate(
    val beforeLease: CountDownLatch = CountDownLatch(1),
    val allowLease: CountDownLatch = CountDownLatch(1),
)

class AncillaryFreshnessRouteDispatchObserver : ScheduleRouteSetupReminderDispatchObserver {
    private val armed = AtomicReference<AncillaryFreshnessRouteDispatchGate?>()

    fun arm(): AncillaryFreshnessRouteDispatchGate =
        AncillaryFreshnessRouteDispatchGate().also {
            check(armed.compareAndSet(null, it))
        }

    fun reset() {
        armed.getAndSet(null)?.allowMemberLock?.countDown()
    }

    override fun afterCandidateRead(reminderId: Long) {
        val gate = armed.getAndSet(null) ?: return
        gate.afterCandidateRead.countDown()
        check(gate.allowMemberLock.await(10, TimeUnit.SECONDS))
    }
}

class AncillaryFreshnessRouteDispatchGate(
    val afterCandidateRead: CountDownLatch = CountDownLatch(1),
    val allowMemberLock: CountDownLatch = CountDownLatch(1),
)
