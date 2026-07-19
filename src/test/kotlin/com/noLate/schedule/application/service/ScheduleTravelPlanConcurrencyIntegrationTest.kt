package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(ScheduleTravelPlanService::class, ScheduleTravelPlanConcurrencyTestConfig::class)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-travel-plan;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class ScheduleTravelPlanConcurrencyIntegrationTest @Autowired constructor(
    private val service: ScheduleTravelPlanService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val shareRepository: ScheduleShareRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
) {

    @MockBean
    lateinit var subscriptionPolicyService: SubscriptionPolicyService

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent first saves for same member leave exactly one travel plan`() {
        val fixture = createFixture()
        whenever(subscriptionPolicyService.getPolicy(any())).thenThrow(AssertionError("notification policy must not run"))

        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()

        listOf(21, 34).forEach { minutes ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    service.upsertMyTravelPlan(
                        memberId = fixture.targetMemberId,
                        scheduleId = fixture.scheduleId,
                        command = command(minutes),
                    )
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        val plans = travelPlanRepository.findAllByScheduleIdAndDeletedFalse(fixture.scheduleId)
        assertEquals(1, plans.size)
        assertTrue(plans.single().travelMinutes in setOf(21, 34))
    }

    private fun createFixture(): TravelPlanConcurrencyFixture {
        val suffix = System.nanoTime()
        val owner = memberRepository.saveAndFlush(
            Member(name = "Owner", password = "Password1!", email = "plan-owner-$suffix@example.com")
        )
        val target = memberRepository.saveAndFlush(
            Member(name = "Target", password = "Password1!", email = "plan-target-$suffix@example.com")
        )
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = requireNotNull(owner.id),
                title = "동시 저장 일정",
                startAt = Instant.parse("2026-07-20T01:00:00Z"),
                endAt = Instant.parse("2026-07-20T02:00:00Z"),
            ).apply {
                updateCategorySnapshot("1", "공유", "#2979FF")
                updateRoute(
                    travelMinutes = 30,
                    departAt = null,
                    departedAt = null,
                    travelMode = ScheduleTravelMode.TRANSIT,
                    locationName = "강남역",
                    originName = "오너 집",
                    originAddress = null,
                    originLat = 37.6,
                    originLng = 126.9,
                    destinationName = "강남역",
                    destinationAddress = null,
                    destinationLat = 37.497,
                    destinationLng = 127.027,
                    routeJson = "{}",
                    notificationEnabled = false,
                    notificationLeadMinutes = null,
                    notificationIntervalMinutes = null,
                )
            }
        )
        shareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = requireNotNull(schedule.id),
                ownerMemberId = requireNotNull(owner.id),
                targetMemberId = requireNotNull(target.id),
                permission = ScheduleSharePermission.VIEWER,
            )
        )
        return TravelPlanConcurrencyFixture(
            scheduleId = requireNotNull(schedule.id),
            targetMemberId = requireNotNull(target.id),
        )
    }

    private fun command(minutes: Int) = ScheduleTravelPlanUpsertCommand(
        travelMinutes = minutes,
        departAt = "2026-07-20T00:30:00Z",
        travelMode = ScheduleTravelMode.TRANSIT,
        originName = "참여자 집",
        originAddress = "서울시 마포구",
        originLat = 37.55,
        originLng = 126.91,
        routeJson = "{\"minutes\":$minutes}",
        notificationEnabled = false,
    )
}

@TestConfiguration
class ScheduleTravelPlanConcurrencyTestConfig {
    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}

private data class TravelPlanConcurrencyFixture(
    val scheduleId: Long,
    val targetMemberId: Long,
)
