package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleTravelPlanServiceUnitTest {

    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var travelPlanRepository: ScheduleTravelPlanRepository
    @Mock lateinit var scheduleShareRepository: ScheduleShareRepository
    @Mock lateinit var categoryShareRepository: ScheduleCategoryShareRepository
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var subscriptionPolicyService: SubscriptionPolicyService

    private lateinit var service: ScheduleTravelPlanService

    @BeforeEach
    fun setUp() {
        service = ScheduleTravelPlanService(
            scheduleRepository = scheduleRepository,
            travelPlanRepository = travelPlanRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            memberRepository = memberRepository,
            subscriptionPolicyService = subscriptionPolicyService,
            objectMapper = jacksonObjectMapper(),
        )
    }

    @Test
    fun `shared participant stores a route owned only by that participant`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberId(10L, 2L)).thenReturn(null)
        whenever(travelPlanRepository.saveAndFlush(any<ScheduleTravelPlan>()))
            .thenAnswer { it.getArgument(0) }

        val result = service.upsertMyTravelPlan(
            memberId = 2L,
            scheduleId = 10L,
            command = routeCommand(originName = "참여자 집", travelMinutes = 28),
        )

        verify(travelPlanRepository).saveAndFlush(check {
            assertEquals(10L, it.scheduleId)
            assertEquals(2L, it.memberId)
            assertEquals("참여자 집", it.originName)
            assertEquals(28, it.travelMinutes)
        })
        assertEquals("참여자 집", result.origin?.name)
        assertEquals("강남역", result.destination?.name)
        assertEquals(2L, result.memberId)
    }

    @Test
    fun `empty personal plan is rejected instead of being marked ready`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 2L,
                scheduleId = 10L,
                command = ScheduleTravelPlanUpsertCommand(),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `participant without a plan keeps common destination without seeing owner route`() {
        val schedule = scheduleEntity()
        val ownerView = schedule.toDto(jacksonObjectMapper())

        val participantView = service.personalizeScheduleDto(
            memberId = 2L,
            schedule = schedule,
            base = ownerView,
            plan = null,
        )

        // 공통 목적지는 새 개인 경로를 계산하는 데 필요하지만, 출발지부터 알림까지는
        // 오너 개인 정보이므로 공유받은 사용자의 평탄형 응답에서 반드시 제거한다.
        assertEquals("강남역", participantView.destination?.name)
        assertNull(participantView.origin)
        assertNull(participantView.route)
        assertNull(participantView.travelMinutes)
        assertNull(participantView.departAt)
        assertNull(participantView.travelMode)
        assertFalse(participantView.notificationEnabled ?: true)
        assertTrue(participantView.routeSetupRequired == true)
    }

    @Test
    fun `removing owner route retires mirrored owner travel plan`() {
        val schedule = scheduleEntity().apply {
            updateRoute(
                travelMinutes = null,
                departAt = null,
                departedAt = null,
                travelMode = null,
                locationName = null,
                originName = null,
                originAddress = null,
                originLat = null,
                originLng = null,
                destinationName = null,
                destinationAddress = null,
                destinationLat = null,
                destinationLng = null,
                routeJson = null,
                notificationEnabled = false,
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }
        val mirrored = travelPlan(memberId = 1L, originName = "오너 집")
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberId(10L, 1L)).thenReturn(mirrored)

        val result = service.syncOwnerTravelPlan(
            memberId = 1L,
            scheduleDto = schedule.toDto(jacksonObjectMapper()),
        )

        assertNull(result)
        assertTrue(mirrored.deleted)
        assertTrue(mirrored.deletedAt != null)
    }

    @Test
    fun `editor can read another participants full saved travel plan`() {
        val schedule = scheduleEntity()
        val targetPlan = travelPlan(memberId = 2L, originName = "참여자 집")
        whenever(scheduleRepository.findScheduleDetail(10L, 3L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 3L))
            .thenReturn(scheduleShare(targetMemberId = 3L, permission = ScheduleSharePermission.EDITOR))
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                10L,
                com.noLate.schedule.domain.ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                scheduleShare(targetMemberId = 2L, permission = ScheduleSharePermission.VIEWER),
                scheduleShare(targetMemberId = 3L, permission = ScheduleSharePermission.EDITOR),
            )
        )
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(targetPlan)

        val result = service.getTravelPlan(
            requesterMemberId = 3L,
            scheduleId = 10L,
            targetMemberId = 2L,
        )

        assertEquals("참여자 집", result.origin?.name)
        assertEquals(2L, result.memberId)
    }

    @Test
    fun `viewer cannot read another participants full saved travel plan`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 3L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 3L))
            .thenReturn(scheduleShare(targetMemberId = 3L, permission = ScheduleSharePermission.VIEWER))

        val error = assertThrows(BusinessException::class.java) {
            service.getTravelPlan(
                requesterMemberId = 3L,
                scheduleId = 10L,
                targetMemberId = 2L,
            )
        }

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode)
    }

    @Test
    fun `owner can read another participants full saved travel plan`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                10L,
                com.noLate.schedule.domain.ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(listOf(scheduleShare(targetMemberId = 2L, permission = ScheduleSharePermission.VIEWER)))
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(travelPlan(memberId = 2L, originName = "회사"))

        val result = service.getTravelPlan(1L, 10L, 2L)

        assertTrue(result.canManageSchedule)
        assertEquals("회사", result.origin?.name)
    }

    private fun scheduleEntity(): Schedule = Schedule(
        id = 10L,
        memberId = 1L,
        title = "공유 미팅",
        startAt = Instant.parse("2026-07-20T01:00:00Z"),
        endAt = Instant.parse("2026-07-20T02:00:00Z"),
    ).apply {
        updateCategorySnapshot("5", "프로젝트", "#2979FF")
        updateRoute(
            travelMinutes = 40,
            departAt = Instant.parse("2026-07-20T00:20:00Z"),
            departedAt = null,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = "강남역",
            originName = "오너 집",
            originAddress = "서울시 은평구",
            originLat = 37.6,
            originLng = 126.9,
            destinationName = "강남역",
            destinationAddress = "서울시 강남구",
            destinationLat = 37.497,
            destinationLng = 127.027,
            routeJson = "{\"id\":\"owner-route\"}",
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
        )
    }

    private fun routeCommand(originName: String, travelMinutes: Int) = ScheduleTravelPlanUpsertCommand(
        travelMinutes = travelMinutes,
        departAt = "2026-07-20T00:32:00Z",
        travelMode = ScheduleTravelMode.CAR,
        originName = originName,
        originAddress = "서울시 마포구",
        originLat = 37.55,
        originLng = 126.91,
        routeJson = "{\"id\":\"target-route\"}",
        notificationEnabled = false,
    )

    private fun travelPlan(memberId: Long, originName: String): ScheduleTravelPlan =
        ScheduleTravelPlan(scheduleId = 10L, memberId = memberId).apply {
            replace(
                command = routeCommand(originName, 28),
                scheduleFingerprint = "fingerprint",
                departAt = Instant.parse("2026-07-20T00:32:00Z"),
                routeJson = "{\"id\":\"target-route\"}",
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }

    private fun scheduleShare(
        targetMemberId: Long,
        permission: ScheduleSharePermission,
    ) = ScheduleShare(
        scheduleId = 10L,
        ownerMemberId = 1L,
        targetMemberId = targetMemberId,
        permission = permission,
    )
}
