package com.noLate.schedule.application.useCase

import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleHybridParserService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleTravelMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 유스케이스가 저장 서비스와 텍스트 분석 서비스를 올바르게 위임하는지 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class ScheduleUseCaseUnitTest {

    @Mock
    lateinit var scheduleService: ScheduleService

    // 외부 AI나 실제 파싱 로직 없이 유스케이스의 위임 계약만 검증하기 위한 mock이다.
    @Mock
    lateinit var scheduleHybridParserService: ScheduleHybridParserService

    @Mock
    lateinit var schedulePushJobService: SchedulePushJobService

    private lateinit var scheduleUseCase: ScheduleUseCase

    @BeforeEach
    fun setUp() {
        scheduleUseCase = ScheduleUseCase(
            scheduleService,
            schedulePushJobService,
            scheduleHybridParserService,
        )
    }

    @Test
    fun `parseScheduleText는 자유 형식 문구 분석을 Parser에 위임한다`() {
        val parsed = ScheduleParseDto(title = "촬영", date = "2026-05-30", time = "12:00")
        whenever(scheduleHybridParserService.parse("예약 문구", "2026-01-01", 60)).thenReturn(parsed)

        val result = scheduleUseCase.parseScheduleText("예약 문구", "2026-01-01", 60)

        verify(scheduleHybridParserService, times(1)).parse("예약 문구", "2026-01-01", 60)
        assertEquals(parsed, result)
    }

    @Test
    fun `addSchedule은 일정과 선택 경로 저장을 Service에 위임한다`() {
        // given
        val memberId = 1L
        val request = scheduleDto()
        val saved = request.copy(id = 10L)

        whenever(scheduleService.addSchedule(memberId, request)).thenReturn(saved)

        // when
        val result = scheduleUseCase.addSchedule(memberId, request)

        // then
        verify(scheduleService, times(1)).addSchedule(memberId, request)
        verify(schedulePushJobService, times(1)).registerFromScheduleDto(memberId, saved)
        assertEquals(10L, result.id)
    }

    @Test
    fun `updateSchedule은 일정 편집을 Service에 위임한다`() {
        // given
        val memberId = 1L
        val scheduleId = 10L
        val request = scheduleDto(title = "수정 일정")

        whenever(scheduleService.updateSchedule(memberId, scheduleId, request)).thenReturn(request.copy(id = scheduleId))

        // when
        val result = scheduleUseCase.updateSchedule(memberId, scheduleId, request)

        // then
        verify(scheduleService, times(1)).updateSchedule(memberId, scheduleId, request)
        verify(schedulePushJobService, times(1)).cancelByScheduleId(scheduleId)
        assertEquals("수정 일정", result.title)
    }

    @Test
    fun `알림이 활성화된 일정 update는 push job을 새 일정 정보로 다시 등록한다`() {
        val memberId = 1L
        val scheduleId = 10L
        val request = scheduleDto(title = "시간 변경").copy(
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
        )
        val updated = request.copy(id = scheduleId)
        whenever(scheduleService.updateSchedule(memberId, scheduleId, request)).thenReturn(updated)

        scheduleUseCase.updateSchedule(memberId, scheduleId, request)

        verify(schedulePushJobService).registerFromScheduleDto(memberId, updated)
        verify(schedulePushJobService, never()).cancelByScheduleId(scheduleId)
    }

    @Test
    fun `getCalendarScheduleList는 캘린더 범위 조회를 Service에 위임한다`() {
        // given
        val memberId = 1L
        val startAt = "2026-06-01T00:00:00Z"
        val endAt = "2026-06-30T23:59:59Z"
        whenever(scheduleService.getCalendarScheduleList(memberId, startAt, endAt))
            .thenReturn(listOf(scheduleDto()))

        // when
        val result = scheduleUseCase.getCalendarScheduleList(memberId, startAt, endAt)

        // then
        verify(scheduleService, times(1)).getCalendarScheduleList(memberId, startAt, endAt)
        assertEquals(1, result.size)
    }

    @Test
    fun `getDailyScheduleList는 선택 날짜 조회를 Service에 위임한다`() {
        // given
        val memberId = 1L
        val date = "2026-06-05"
        whenever(scheduleService.getDailyScheduleList(memberId, date)).thenReturn(listOf(scheduleDto()))

        // when
        val result = scheduleUseCase.getDailyScheduleList(memberId, date)

        // then
        verify(scheduleService, times(1)).getDailyScheduleList(memberId, date)
        assertEquals(1, result.size)
    }

    @Test
    fun `getUpcomingScheduleList는 다가오는 일정 조회를 Service에 위임한다`() {
        // given
        val memberId = 1L
        val fromAt = "2026-06-05T00:00:00Z"
        val limit = 5
        whenever(scheduleService.getUpcomingScheduleList(memberId, fromAt, limit))
            .thenReturn(listOf(scheduleDto()))

        // when
        val result = scheduleUseCase.getUpcomingScheduleList(memberId, fromAt, limit)

        // then
        verify(scheduleService, times(1)).getUpcomingScheduleList(memberId, fromAt, limit)
        assertEquals(1, result.size)
    }

    @Test
    fun `searchScheduleList는 검색 조건 조회를 Service에 위임한다`() {
        // given
        val memberId = 1L
        whenever(
            scheduleService.searchScheduleList(
                memberId = eq(memberId),
                keyword = eq("회의"),
                categoryId = eq("1"),
                startAt = eq("2026-06-01T00:00:00Z"),
                endAt = eq("2026-06-30T23:59:59Z"),
            )
        ).thenReturn(listOf(scheduleDto()))

        // when
        val result = scheduleUseCase.searchScheduleList(
            memberId = memberId,
            keyword = "회의",
            categoryId = "1",
            startAt = "2026-06-01T00:00:00Z",
            endAt = "2026-06-30T23:59:59Z",
        )

        // then
        verify(scheduleService, times(1)).searchScheduleList(
            memberId = memberId,
            keyword = "회의",
            categoryId = "1",
            startAt = "2026-06-01T00:00:00Z",
            endAt = "2026-06-30T23:59:59Z",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `getDepartureReadyScheduleList는 출발 알림 후보 조회를 Service에 위임한다`() {
        // given
        val memberId = 1L
        val fromAt = "2026-06-05T00:00:00Z"
        val toAt = "2026-06-06T00:00:00Z"
        whenever(scheduleService.getDepartureReadyScheduleList(memberId, fromAt, toAt))
            .thenReturn(listOf(scheduleDto(travelMinutes = 30)))

        // when
        val result = scheduleUseCase.getDepartureReadyScheduleList(memberId, fromAt, toAt)

        // then
        verify(scheduleService, times(1)).getDepartureReadyScheduleList(memberId, fromAt, toAt)
        assertEquals(30, result.first().travelMinutes)
    }

    private fun scheduleDto(
        title: String = "회의",
        travelMinutes: Int? = 20,
    ): ScheduleDto =
        ScheduleDto(
            title = title,
            startAt = "2026-06-05T01:00:00Z",
            endAt = "2026-06-05T02:00:00Z",
            allDay = false,
            travelMinutes = travelMinutes,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = "집 → 회사",
            category = ScheduleCategoryDto(id = "1", title = "업무", color = "#f44336"),
        )
}
