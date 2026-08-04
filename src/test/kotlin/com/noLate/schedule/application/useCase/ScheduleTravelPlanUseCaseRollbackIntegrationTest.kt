package com.noLate.schedule.application.useCase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheAudienceResolver
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import com.noLate.subscription.domain.SubscriptionPlan
import com.noLate.subscription.domain.SubscriptionPolicyDto
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doThrow
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

@DataJpaTest
@Import(
    ScheduleTravelPlanService::class,
    ScheduleTravelPlanUseCase::class,
    ScheduleTravelPlanUseCaseRollbackTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-travel-plan-use-case-rollback;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleTravelPlanUseCaseRollbackIntegrationTest @Autowired constructor(
    private val useCase: ScheduleTravelPlanUseCase,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
) {
    @MockBean
    lateinit var scheduleService: ScheduleService

    @MockBean
    lateinit var pushJobService: SchedulePushJobService

    @MockBean
    lateinit var cacheAudienceResolver: ScheduleCalendarCacheAudienceResolver

    @MockBean
    lateinit var subscriptionPolicyService: SubscriptionPolicyService

    @Test
    fun `push registration failure rolls back destination supplement and personal plan`() {
        val fixture = createNameOnlyDestinationFixture("push")
        val scheduleDto = scheduleDto(fixture)
        whenever(scheduleService.getScheduleDetail(fixture.memberId, fixture.scheduleId))
            .thenReturn(scheduleDto)
        whenever(subscriptionPolicyService.getPolicy(fixture.memberId)).thenReturn(freePolicy())
        doThrow(InjectedTravelPlanRollbackFailure("push registration failed"))
            .whenever(pushJobService)
            .registerFromTravelPlanDto(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            )

        assertThrows<InjectedTravelPlanRollbackFailure> {
            useCase.upsertMyTravelPlan(
                memberId = fixture.memberId,
                scheduleId = fixture.scheduleId,
                command = completeCommand(notificationEnabled = true),
                presentedSessionGeneration = 0L,
            )
        }

        assertDestinationSupplementAndPlanWereRolledBack(fixture)
    }

    @Test
    fun `cache audience failure rolls back destination supplement and personal plan`() {
        val fixture = createNameOnlyDestinationFixture("cache")
        val scheduleDto = scheduleDto(fixture)
        whenever(scheduleService.getScheduleDetail(fixture.memberId, fixture.scheduleId))
            .thenReturn(scheduleDto)
        whenever(cacheAudienceResolver.resolve(scheduleDto))
            .thenThrow(InjectedTravelPlanRollbackFailure("cache handling failed"))

        assertThrows<InjectedTravelPlanRollbackFailure> {
            useCase.upsertMyTravelPlan(
                memberId = fixture.memberId,
                scheduleId = fixture.scheduleId,
                command = completeCommand(),
                presentedSessionGeneration = 0L,
            )
        }

        assertDestinationSupplementAndPlanWereRolledBack(fixture)
    }

    private fun createNameOnlyDestinationFixture(label: String): RollbackFixture {
        val suffix = System.nanoTime()
        val member = memberRepository.saveAndFlush(
            Member(
                name = "Owner",
                password = "Password1!",
                email = "travel-plan-rollback-$label-$suffix@example.com",
            )
        )
        val memberId = requireNotNull(member.id)
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = memberId,
                title = "강남역 이름만 저장된 일정",
                startAt = Instant.parse("2026-08-05T01:00:00Z"),
                endAt = Instant.parse("2026-08-05T02:00:00Z"),
            ).apply {
                updateCategorySnapshot("1", "개인", "#2979FF")
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
        return RollbackFixture(
            memberId = memberId,
            scheduleId = requireNotNull(schedule.id),
        )
    }

    private fun completeCommand(notificationEnabled: Boolean = false) = ScheduleTravelPlanUpsertCommand(
        travelMinutes = 31,
        departAt = "2026-08-05T00:29:00Z",
        travelMode = ScheduleTravelMode.TRANSIT,
        originName = "집",
        originAddress = "서울시 마포구",
        originLat = 37.55,
        originLng = 126.91,
        destinationName = "강남역[2호선]",
        destinationAddress = "서울 강남구 강남대로 지하 396",
        destinationLat = 37.49812971,
        destinationLng = 127.02868505,
        routeJson = "{\"provider\":\"TMAP\"}",
        notificationEnabled = notificationEnabled,
        notificationLeadMinutes = 60.takeIf { notificationEnabled },
        notificationIntervalMinutes = 20.takeIf { notificationEnabled },
    )

    private fun freePolicy() = SubscriptionPolicyDto(
        plan = SubscriptionPlan.FREE,
        maxSmartSchedulesPerMonth = SubscriptionPlan.FREE.maxSmartSchedulesPerMonth,
        usedSmartSchedulesThisMonth = 0,
        maxNotificationLeadMinutes = SubscriptionPlan.FREE.maxNotificationLeadMinutes,
        minNotificationIntervalMinutes = SubscriptionPlan.FREE.minNotificationIntervalMinutes,
        minEtaRefreshIntervalMinutes = SubscriptionPlan.FREE.minEtaRefreshIntervalMinutes,
    )

    private fun scheduleDto(fixture: RollbackFixture) = ScheduleDto(
        id = fixture.scheduleId,
        ownerMemberId = fixture.memberId,
        title = "강남역 이름만 저장된 일정",
        startAt = "2026-08-05T01:00:00Z",
        endAt = "2026-08-05T02:00:00Z",
        category = ScheduleCategoryDto(id = "1", title = "개인", color = "#2979FF"),
    )

    private fun assertDestinationSupplementAndPlanWereRolledBack(fixture: RollbackFixture) {
        val persisted = scheduleRepository.findById(fixture.scheduleId).orElseThrow()
        assertNull(persisted.route?.destinationLat)
        assertNull(persisted.route?.destinationLng)
        assertNull(
            travelPlanRepository.findByScheduleIdAndMemberId(
                fixture.scheduleId,
                fixture.memberId,
            )
        )
    }
}

@TestConfiguration
class ScheduleTravelPlanUseCaseRollbackTestConfig {
    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}

private data class RollbackFixture(
    val memberId: Long,
    val scheduleId: Long,
)

private class InjectedTravelPlanRollbackFailure(message: String) : RuntimeException(message)
