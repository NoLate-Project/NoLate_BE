package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScheduleTravelPlanMySqlContainer(imageName: String) :
    MySQLContainer<ScheduleTravelPlanMySqlContainer>(imageName)

/**
 * H2로는 검증할 수 없는 InnoDB REPEATABLE READ current-read 경계를 고정한다.
 *
 * 서비스 호출 전에 동일 transaction의 persistence context에 name-only route를 의도적으로
 * 적재해 두므로, schedule row lock 뒤 schedule/route를 refresh하지 않으면 두 번째 writer가
 * 첫 번째 writer의 좌표를 덮어쓴다. active plan 테스트도 같은 snapshot에 보이지 않는 새 row를
 * 별도 transaction에서 commit해 locking read가 아니면 guard를 통과하도록 재현한다.
 */
@DataJpaTest
@Import(
    ScheduleTravelPlanService::class,
    ScheduleSharingAvailabilityPolicy::class,
    ScheduleTravelPlanMySqlTestConfig::class,
)
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = false)
@TestPropertySource(properties = ["schedule.sharing.enabled=true"])
class ScheduleTravelPlanMySqlConcurrencyIntegrationTest @Autowired constructor(
    private val service: ScheduleTravelPlanService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val transactionManager: PlatformTransactionManager,
) {

    @MockBean
    lateinit var subscriptionPolicyService: SubscriptionPolicyService

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `stale persistence contexts cannot overwrite the first committed common destination on MySQL`() {
        val fixture = createNameOnlyFixture()
        val candidates = listOf(
            DestinationCandidate(
                travelMinutes = 31,
                lat = 37.49812971,
                lng = 127.02868505,
            ),
            DestinationCandidate(
                travelMinutes = 47,
                lat = 37.498201,
                lng = 127.028793,
            ),
        )
        val preloaded = CountDownLatch(candidates.size)
        val startUpsert = CountDownLatch(1)
        val done = CountDownLatch(candidates.size)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val statusesByMinutes = ConcurrentHashMap<Int, ScheduleTravelPlanStatus>()
        val executor = Executors.newFixedThreadPool(candidates.size)

        candidates.forEach { candidate ->
            executor.submit {
                try {
                    repeatableReadTransaction().executeWithoutResult {
                        val staleSchedule = scheduleRepository.findScheduleDetail(
                            fixture.scheduleId,
                            fixture.ownerId,
                        ) ?: error("동시성 fixture 일정을 찾지 못했습니다.")
                        val staleRoute = requireNotNull(staleSchedule.route)
                        assertNull(staleRoute.destinationLat)
                        assertNull(staleRoute.destinationLng)

                        preloaded.countDown()
                        check(startUpsert.await(10, TimeUnit.SECONDS)) {
                            "두 MySQL transaction의 stale preload가 완료되지 않았습니다."
                        }

                        val result = service.upsertMyTravelPlan(
                            memberId = fixture.ownerId,
                            scheduleId = fixture.scheduleId,
                            command = command(candidate),
                        )
                        statusesByMinutes[candidate.travelMinutes] = result.status
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            assertTrue(preloaded.await(10, TimeUnit.SECONDS), "stale preload가 제한 시간 안에 끝나지 않았습니다.")
            startUpsert.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "동시 upsert가 제한 시간 안에 끝나지 않았습니다.")
        } finally {
            startUpsert.countDown()
            executor.shutdownNow()
        }

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(setOf(31, 47), statusesByMinutes.keys)
        assertTrue(statusesByMinutes.values.all { it == ScheduleTravelPlanStatus.READY })

        val persisted = readPersistedState(fixture)
        // 같은 회원 plan은 두 번째 writer의 travelMinutes로 교체된다. 따라서 반대편 후보가
        // schedule lock을 먼저 얻어 확정한 공통 좌표이며, 이후 writer가 이를 덮어쓰면 안 된다.
        val firstWriter = candidates.single { it.travelMinutes != persisted.travelMinutes }
        assertEquals(firstWriter.lat, persisted.destinationLat)
        assertEquals(firstWriter.lng, persisted.destinationLng)
        assertEquals(1, persisted.activePlanCount)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `active participant plan committed after snapshot blocks destination supplement on MySQL`() {
        val fixture = createNameOnlyFixture(withParticipant = true)
        val snapshotReady = CountDownLatch(1)
        val participantPlanCommitted = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            try {
                repeatableReadTransaction().executeWithoutResult {
                    val staleSchedule = scheduleRepository.findScheduleDetail(
                        fixture.scheduleId,
                        fixture.ownerId,
                    ) ?: error("active plan guard fixture 일정을 찾지 못했습니다.")
                    assertNull(requireNotNull(staleSchedule.route).destinationLat)
                    assertTrue(travelPlanRepository.findAllByScheduleIdAndDeletedFalse(fixture.scheduleId).isEmpty())

                    snapshotReady.countDown()
                    check(participantPlanCommitted.await(10, TimeUnit.SECONDS)) {
                        "참가자 plan commit을 기다리는 동안 제한 시간을 초과했습니다."
                    }

                    service.upsertMyTravelPlan(
                        memberId = fixture.ownerId,
                        scheduleId = fixture.scheduleId,
                        command = command(
                            DestinationCandidate(
                                travelMinutes = 31,
                                lat = 37.49812971,
                                lng = 127.02868505,
                            )
                        ),
                    )
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }

        try {
            assertTrue(snapshotReady.await(10, TimeUnit.SECONDS), "owner snapshot이 제한 시간 안에 준비되지 않았습니다.")
            travelPlanRepository.saveAndFlush(
                participantPlan(
                    scheduleId = fixture.scheduleId,
                    memberId = requireNotNull(fixture.participantId),
                )
            )
            participantPlanCommitted.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "active plan guard 검증이 제한 시간 안에 끝나지 않았습니다.")
        } finally {
            participantPlanCommitted.countDown()
            executor.shutdownNow()
        }

        val thrown = failure.get()
        assertTrue(thrown is BusinessException, thrown?.stackTraceToString().orEmpty())
        assertEquals(ErrorCode.INVALID_STATE, (thrown as BusinessException).errorCode)

        val persisted = readPersistedState(fixture)
        assertNull(persisted.destinationLat)
        assertNull(persisted.destinationLng)
        assertEquals(1, persisted.activePlanCount)
        assertEquals(24, persisted.travelMinutes)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `stale full route update cannot erase a supplemented destination on MySQL`() {
        val fixture = createNameOnlyFixture()
        val staleRouteLoaded = CountDownLatch(1)
        val supplementCommitted = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            try {
                repeatableReadTransaction().executeWithoutResult {
                    val staleSchedule = scheduleRepository.findScheduleDetail(
                        fixture.scheduleId,
                        fixture.ownerId,
                    ) ?: error("stale route writer fixture 일정을 찾지 못했습니다.")
                    val staleRoute = requireNotNull(staleSchedule.route)
                    assertNull(staleRoute.destinationLat)
                    assertNull(staleRoute.destinationLng)

                    staleRouteLoaded.countDown()
                    check(supplementCommitted.await(10, TimeUnit.SECONDS)) {
                        "공통 도착지 좌표 보강 commit을 기다리는 동안 제한 시간을 초과했습니다."
                    }

                    // Schedule PUT과 동일하게 parent row lock을 얻더라도, 이미 관리 중인
                    // route와 요청 DTO는 좌표 보강 전 snapshot일 수 있다. 전체 경로를 저장하는
                    // stale writer가 좌표를 null로 되돌리는 상황을 재현한다.
                    val lockedSchedule = scheduleRepository.findActiveForTravelPlanUpdate(fixture.scheduleId)
                        ?: error("stale route writer lock 대상 일정을 찾지 못했습니다.")
                    lockedSchedule.updateRoute(
                        travelMinutes = 88,
                        departAt = null,
                        departedAt = null,
                        travelMode = ScheduleTravelMode.TRANSIT,
                        locationName = "강남역 stale update",
                        originName = "예전 출발지",
                        originAddress = null,
                        originLat = 37.55,
                        originLng = 126.91,
                        destinationName = "강남역",
                        destinationAddress = null,
                        destinationLat = null,
                        destinationLng = null,
                        routeJson = "{\"stale\":true}",
                        notificationEnabled = false,
                        notificationLeadMinutes = null,
                        notificationIntervalMinutes = null,
                    )
                    scheduleRepository.saveAndFlush(lockedSchedule)
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }

        try {
            assertTrue(staleRouteLoaded.await(10, TimeUnit.SECONDS), "stale route snapshot이 준비되지 않았습니다.")
            service.upsertMyTravelPlan(
                memberId = fixture.ownerId,
                scheduleId = fixture.scheduleId,
                command = command(
                    DestinationCandidate(
                        travelMinutes = 31,
                        lat = 37.49812971,
                        lng = 127.02868505,
                    )
                ),
            )
            supplementCommitted.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "stale route writer 검증이 끝나지 않았습니다.")
        } finally {
            supplementCommitted.countDown()
            executor.shutdownNow()
        }

        val thrown = failure.get()
        val optimisticFailureFound = generateSequence(thrown) { it.cause }
            .any { it is OptimisticLockingFailureException }
        assertTrue(optimisticFailureFound, thrown?.stackTraceToString().orEmpty())

        val persisted = readPersistedState(fixture)
        assertEquals(37.49812971, persisted.destinationLat)
        assertEquals(127.02868505, persisted.destinationLng)
        assertEquals(31, persisted.travelMinutes)
        assertEquals(1, persisted.activePlanCount)
    }

    private fun repeatableReadTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            timeout = 20
        }

    private fun createNameOnlyFixture(withParticipant: Boolean = false): MySqlTravelPlanFixture {
        val suffix = System.nanoTime()
        val owner = memberRepository.saveAndFlush(
            Member(
                name = "MySQL Owner",
                password = "Password1!",
                email = "mysql-travel-owner-$suffix@example.com",
            )
        )
        val ownerId = requireNotNull(owner.id)
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = ownerId,
                title = "강남역 이름만 저장된 MySQL 일정",
                startAt = Instant.parse("2026-08-05T01:00:00Z"),
                endAt = Instant.parse("2026-08-05T02:00:00Z"),
            ).apply {
                updateRoute(
                    travelMinutes = null,
                    departAt = null,
                    departedAt = null,
                    travelMode = null,
                    locationName = "강남역",
                    originName = null,
                    originAddress = null,
                    originLat = null,
                    originLng = null,
                    destinationName = "강남역",
                    destinationAddress = null,
                    destinationLat = null,
                    destinationLng = null,
                    routeJson = null,
                    notificationEnabled = false,
                    notificationLeadMinutes = null,
                    notificationIntervalMinutes = null,
                )
            }
        )
        val scheduleId = requireNotNull(schedule.id)
        val participantId = if (withParticipant) {
            val participant = memberRepository.saveAndFlush(
                Member(
                    name = "MySQL Participant",
                    password = "Password1!",
                    email = "mysql-travel-participant-$suffix@example.com",
                )
            )
            requireNotNull(participant.id).also { targetId ->
                scheduleShareRepository.saveAndFlush(
                    ScheduleShare(
                        scheduleId = scheduleId,
                        ownerMemberId = ownerId,
                        targetMemberId = targetId,
                        permission = ScheduleSharePermission.VIEWER,
                    )
                )
            }
        } else {
            null
        }
        return MySqlTravelPlanFixture(
            scheduleId = scheduleId,
            ownerId = ownerId,
            participantId = participantId,
        )
    }

    private fun command(candidate: DestinationCandidate) = ScheduleTravelPlanUpsertCommand(
        travelMinutes = candidate.travelMinutes,
        departAt = "2026-08-05T00:20:00Z",
        travelMode = ScheduleTravelMode.TRANSIT,
        originName = "집",
        originAddress = "서울시 마포구",
        originLat = 37.55,
        originLng = 126.91,
        destinationName = "강남역[2호선]",
        destinationAddress = "서울 강남구 강남대로 지하 396",
        destinationLat = candidate.lat,
        destinationLng = candidate.lng,
        routeJson = "{\"minutes\":${candidate.travelMinutes}}",
        notificationEnabled = false,
    )

    private fun participantPlan(scheduleId: Long, memberId: Long) = ScheduleTravelPlan(
        scheduleId = scheduleId,
        memberId = memberId,
        travelMinutes = 24,
        departAt = Instant.parse("2026-08-05T00:25:00Z"),
        travelMode = ScheduleTravelMode.TRANSIT,
        originName = "참가자 집",
        originAddress = "서울시 송파구",
        originLat = 37.51,
        originLng = 127.10,
        routeJson = "{\"minutes\":24}",
        scheduleFingerprint = "a".repeat(64),
    )

    private fun readPersistedState(fixture: MySqlTravelPlanFixture): PersistedTravelPlanState =
        requireNotNull(
            repeatableReadTransaction().execute {
                val schedule = scheduleRepository.findById(fixture.scheduleId).orElseThrow()
                val route = requireNotNull(schedule.route)
                val plans = travelPlanRepository.findAllByScheduleIdAndDeletedFalse(fixture.scheduleId)
                PersistedTravelPlanState(
                    destinationLat = route.destinationLat,
                    destinationLng = route.destinationLng,
                    travelMinutes = plans.singleOrNull { it.memberId == fixture.ownerId }?.travelMinutes
                        ?: plans.single().travelMinutes,
                    activePlanCount = plans.size,
                )
            }
        )

    companion object {
        @Container
        @JvmStatic
        val mysql = ScheduleTravelPlanMySqlContainer("mysql:8.4")
            .withDatabaseName("nolate_travel_plan_test")
            .withUsername("nolate")
            .withPassword("nolate")

        @DynamicPropertySource
        @JvmStatic
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName)
            registry.add("spring.jpa.properties.hibernate.dialect") { "org.hibernate.dialect.MySQLDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            registry.add("spring.sql.init.mode") { "never" }
        }
    }
}

@TestConfiguration
class ScheduleTravelPlanMySqlTestConfig {
    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}

private data class MySqlTravelPlanFixture(
    val scheduleId: Long,
    val ownerId: Long,
    val participantId: Long?,
)

private data class DestinationCandidate(
    val travelMinutes: Int,
    val lat: Double,
    val lng: Double,
)

private data class PersistedTravelPlanState(
    val destinationLat: Double?,
    val destinationLng: Double?,
    val travelMinutes: Int?,
    val activePlanCount: Int,
)
