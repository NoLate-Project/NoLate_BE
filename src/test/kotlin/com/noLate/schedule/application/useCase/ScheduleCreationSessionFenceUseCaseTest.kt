package com.noLate.schedule.application.useCase

import com.noLate.favorite.application.service.FavoritePlaceService
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.application.service.MemberService
import com.noLate.member.domain.member.Member
import com.noLate.schedule.application.service.ScheduleDepartureStatusService
import com.noLate.schedule.application.service.ScheduleHybridParserService
import com.noLate.schedule.application.service.ScheduleNotificationActionIdempotencyService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportResultDto
import com.noLate.schedule.domain.ScheduleImportSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScheduleCreationSessionFenceUseCaseTest {
    private val scheduleService = mock<ScheduleService>()
    private val pushJobService = mock<SchedulePushJobService>()
    private val hybridParser = mock<ScheduleHybridParserService>()
    private val departureStatusService = mock<ScheduleDepartureStatusService>()
    private val favoritePlaceService = mock<FavoritePlaceService>()
    private val actionIdempotencyService = mock<ScheduleNotificationActionIdempotencyService>()
    private val memberService = mock<MemberService>()
    private val travelPlanService = mock<ScheduleTravelPlanService>()
    private lateinit var useCase: ScheduleUseCase

    @BeforeEach
    fun setUp() {
        useCase = ScheduleUseCase(
            scheduleService = scheduleService,
            schedulePushJobService = pushJobService,
            scheduleHybridParserService = hybridParser,
            scheduleDepartureStatusService = departureStatusService,
            favoritePlaceService = favoritePlaceService,
            notificationActionIdempotencyService = actionIdempotencyService,
            memberService = memberService,
            clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC),
            scheduleTravelPlanService = travelPlanService,
        )
    }

    @Test
    fun `add locks and validates the member before any schedule travel plan or push job write`() {
        val request = schedule("ordered-add")
        val saved = request.copy(id = 100L, ownerMemberId = 77L)
        whenever(memberService.getActiveMemberForUpdate(77L, 9L)).thenReturn(Member(id = 77L))
        whenever(scheduleService.addSchedule(77L, request)).thenReturn(saved)

        useCase.addSchedule(
            memberId = 77L,
            scheduleDto = request,
            presentedSessionGeneration = 9L,
        )

        inOrder(memberService, scheduleService, travelPlanService, pushJobService) {
            verify(memberService).getActiveMemberForUpdate(77L, 9L)
            verify(scheduleService).addSchedule(77L, request)
            verify(travelPlanService).syncOwnerTravelPlan(77L, saved)
            verify(pushJobService).registerFromScheduleDto(77L, saved)
        }
    }

    @Test
    fun `failed member fence prevents every add side effect`() {
        val failure = BusinessException(ErrorCode.INVALID_TOKEN)
        whenever(memberService.getActiveMemberForUpdate(78L, 2L)).thenThrow(failure)

        val thrown = assertThrows<BusinessException> {
            useCase.addSchedule(
                memberId = 78L,
                scheduleDto = schedule("stale-add"),
                presentedSessionGeneration = 2L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, thrown.errorCode)
        verify(scheduleService, never()).addSchedule(78L, schedule("stale-add"))
        verify(travelPlanService, never()).syncOwnerTravelPlan(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(pushJobService, never()).registerFromScheduleDto(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `even an already imported source crosses the member fence before idempotent lookup`() {
        val request = schedule("existing-import")
        val source = source("existing")
        val existing = request.copy(id = 101L, ownerMemberId = 79L)
        whenever(memberService.getActiveMemberForUpdate(79L, 14L)).thenReturn(Member(id = 79L))
        whenever(scheduleService.importSchedule(79L, request, source)).thenReturn(
            ScheduleImportResultDto(schedule = existing, created = false),
        )

        val result = useCase.importSchedule(
            memberId = 79L,
            scheduleDto = request,
            source = source,
            presentedSessionGeneration = 14L,
        )

        assertEquals(false, result.created)
        inOrder(memberService, scheduleService) {
            verify(memberService).getActiveMemberForUpdate(79L, 14L)
            verify(scheduleService).importSchedule(79L, request, source)
        }
        verify(travelPlanService, never()).syncOwnerTravelPlan(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(pushJobService, never()).registerFromScheduleDto(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `failed member fence prevents both new and legacy import mutation paths`() {
        val request = schedule("stale-import")
        val source = source("stale")
        whenever(memberService.getActiveMemberForUpdate(80L, 4L))
            .thenThrow(BusinessException(ErrorCode.INVALID_TOKEN))

        val thrown = assertThrows<BusinessException> {
            useCase.importSchedule(
                memberId = 80L,
                scheduleDto = request,
                source = source,
                presentedSessionGeneration = 4L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, thrown.errorCode)
        verify(scheduleService, never()).importSchedule(80L, request, source)
        verify(travelPlanService, never()).syncOwnerTravelPlan(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(pushJobService, never()).registerFromScheduleDto(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    private fun schedule(title: String): ScheduleDto =
        ScheduleDto(
            title = title,
            startAt = "2099-07-24T05:00:00Z",
            endAt = "2099-07-24T06:00:00Z",
            category = ScheduleCategoryDto(
                id = "1",
                title = "업무",
                color = "#123456",
            ),
            notificationEnabled = true,
        )

    private fun source(suffix: String): ScheduleImportSource =
        ScheduleImportSource(
            provider = ScheduleImportProvider.GOOGLE,
            calendarId = "calendar-$suffix",
            eventId = "event-$suffix",
            occurrenceStartAt = "2099-07-24T05:00:00Z",
        )
}
