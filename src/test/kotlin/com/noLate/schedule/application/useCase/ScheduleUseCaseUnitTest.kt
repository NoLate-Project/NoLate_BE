package com.noLate.schedule.application.useCase

import com.noLate.favorite.application.service.FavoritePlaceService
import com.noLate.favorite.domain.FavoritePlaceDto
import com.noLate.schedule.application.service.ScheduleHybridParserService
import com.noLate.schedule.application.service.ScheduleDepartureStatusService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleDepartureStatus
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportResultDto
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.domain.ScheduleOriginSource
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.SchedulePlaceDto
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ScheduleUseCaseUnitTest {

    @Mock
    lateinit var scheduleService: ScheduleService

    @Mock
    lateinit var scheduleHybridParserService: ScheduleHybridParserService

    @Mock
    lateinit var schedulePushJobService: SchedulePushJobService

    @Mock
    lateinit var scheduleDepartureStatusService: ScheduleDepartureStatusService

    @Mock
    lateinit var favoritePlaceService: FavoritePlaceService

    @Mock
    lateinit var scheduleTravelPlanService: ScheduleTravelPlanService

    private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private lateinit var scheduleUseCase: ScheduleUseCase

    @BeforeEach
    fun setUp() {
        scheduleUseCase = ScheduleUseCase(
            scheduleService = scheduleService,
            schedulePushJobService = schedulePushJobService,
            scheduleHybridParserService = scheduleHybridParserService,
            scheduleDepartureStatusService = scheduleDepartureStatusService,
            favoritePlaceService = favoritePlaceService,
            clock = clock,
            scheduleTravelPlanService = scheduleTravelPlanService,
        )
    }

    @Test
    fun `사용자가 입력한 자연어 일정 문장은 일정 파서로 넘긴다`() {
        // UseCase는 파싱 규칙을 직접 알지 않고, 파서 서비스의 결과를 그대로 반환한다.
        val parsed = ScheduleParseDto(title = "점심 약속", date = "2026-05-30", time = "12:00")
        whenever(
            scheduleHybridParserService.parse(
                "내일 점심 약속",
                ScheduleParseInputType.CONVERSATION,
                "2026-01-01",
                60,
            )
        ).thenReturn(parsed)

        val result = scheduleUseCase.parseScheduleText(
            text = "내일 점심 약속",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        verify(scheduleHybridParserService, times(1)).parse(
            "내일 점심 약속",
            ScheduleParseInputType.CONVERSATION,
            "2026-01-01",
            60,
        )
        assertEquals(parsed, result)
    }

    @Test
    fun `음성 입력 타입을 유실하지 않고 일정 파서로 넘긴다`() {
        // enum이 Controller에서만 역직렬화되고 중간 계층에서 버려지면 음성 전용 무AI 정책을
        // 적용할 수 없다. UseCase가 VOICE_TRANSCRIPT를 그대로 전달하는지 별도로 고정한다.
        val text = "수요일 저녁 7시 강남역에서 판교 네이버까지"
        val parsed = ScheduleParseDto(title = "판교 네이버 19:00", date = "2026-07-15", time = "19:00")
        whenever(
            scheduleHybridParserService.parse(
                text,
                ScheduleParseInputType.VOICE_TRANSCRIPT,
                "2026-07-11",
                60,
            )
        ).thenReturn(parsed)

        val result = scheduleUseCase.parseScheduleText(
            text = text,
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        verify(scheduleHybridParserService).parse(
            text,
            ScheduleParseInputType.VOICE_TRANSCRIPT,
            "2026-07-11",
            60,
        )
        assertEquals(parsed, result)
    }

    @Test
    fun `출발지가 없으면 회원 계정의 기본 주소를 사용한다`() {
        // 파서 결과에는 사용자가 실제로 말하거나 적은 출발지만 들어간다. 회원별 기본값은
        // 인증된 memberId를 아는 UseCase에서 보완해 다른 회원의 장소가 섞이지 않게 한다.
        val parsed = ScheduleParseDto(
            title = "강남 용용선생 08:00",
            date = "2026-07-18",
            time = "08:00",
            destination = SchedulePlaceDto(name = "강남 용용선생"),
            missingFields = listOf("origin"),
        )
        whenever(
            scheduleHybridParserService.parse(
                "토요일 8시 강남 용용선생",
                ScheduleParseInputType.VOICE_TRANSCRIPT,
                "2026-07-16",
                60,
            ),
        ).thenReturn(parsed)
        whenever(favoritePlaceService.getDefaultOrigin(7L)).thenReturn(
            FavoritePlaceDto(
                id = 12L,
                label = "집",
                placeName = "우리 집",
                address = "서울특별시 강남구 테헤란로 1",
                lat = 37.5,
                lng = 127.0,
                defaultOrigin = true,
                sortOrder = 0,
            ),
        )

        val result = scheduleUseCase.parseScheduleText(
            memberId = 7L,
            text = "토요일 8시 강남 용용선생",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("우리 집", result.origin?.name)
        assertEquals("서울특별시 강남구 테헤란로 1", result.origin?.address)
        assertEquals(37.5, result.origin?.lat)
        assertEquals(127.0, result.origin?.lng)
        assertEquals(ScheduleOriginSource.FAVORITE_DEFAULT, result.originSource)
        assertEquals(false, result.originRequired)
        assertEquals(false, "origin" in result.missingFields)
    }

    @Test
    fun `원문에서 찾은 출발지는 계정 기본 주소로 덮어쓰지 않는다`() {
        // 사용자가 직접 말한 출발지는 가장 강한 의도다. 기본 출발지 조회 자체를 생략하는지
        // 검증해 불필요한 DB 조회와 사용자 입력 덮어쓰기를 동시에 방지한다.
        val parsed = ScheduleParseDto(
            title = "판교 네이버 19:00",
            date = "2026-07-18",
            time = "19:00",
            origin = SchedulePlaceDto(name = "강남역"),
            originSource = ScheduleOriginSource.TEXT,
            originRequired = false,
            destination = SchedulePlaceDto(name = "판교 네이버"),
        )
        whenever(
            scheduleHybridParserService.parse(
                "토요일 저녁 7시 강남역에서 판교 네이버까지",
                ScheduleParseInputType.VOICE_TRANSCRIPT,
                "2026-07-16",
                60,
            ),
        ).thenReturn(parsed)

        val result = scheduleUseCase.parseScheduleText(
            memberId = 7L,
            text = "토요일 저녁 7시 강남역에서 판교 네이버까지",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("강남역", result.origin?.name)
        assertEquals(ScheduleOriginSource.TEXT, result.originSource)
        verify(favoritePlaceService, never()).getDefaultOrigin(7L)
    }

    @Test
    fun `미래 일정에 출발 알림을 켜서 저장하면 푸시 작업을 예약한다`() {
        // 알림이 켜져 있고 시작 시간이 현재보다 이후라면 출발 알림 대상이다.
        val memberId = 1L
        val request = scheduleDto(notificationEnabled = true)
        val saved = request.copy(id = 10L)
        whenever(scheduleService.addSchedule(memberId, request)).thenReturn(saved)

        val result = scheduleUseCase.addSchedule(memberId, request)

        verify(scheduleService, times(1)).addSchedule(memberId, request)
        verify(schedulePushJobService, times(1)).registerFromScheduleDto(memberId, saved)
        verify(schedulePushJobService, never()).cancelByScheduleId(10L)
        assertEquals(10L, result.id)
    }

    @Test
    fun `이미 가져온 외부 일정에는 출발 알림 작업을 다시 만들지 않는다`() {
        val memberId = 1L
        val request = scheduleDto(notificationEnabled = true)
        val saved = request.copy(id = 10L)
        val source = ScheduleImportSource(
            provider = ScheduleImportProvider.APPLE_DEVICE,
            calendarId = "calendar-1",
            eventId = "event-1",
            occurrenceStartAt = request.startAt,
        )
        whenever(scheduleService.importSchedule(memberId, request, source))
            .thenReturn(
                ScheduleImportResultDto(schedule = saved, created = true),
                ScheduleImportResultDto(schedule = saved, created = false),
            )

        val first = scheduleUseCase.importSchedule(memberId, request, source)
        val repeated = scheduleUseCase.importSchedule(memberId, request, source)

        assertEquals(true, first.created)
        assertEquals(false, repeated.created)
        verify(schedulePushJobService, times(1)).registerFromScheduleDto(memberId, saved)
    }

    @Test
    fun `이미 지난 일정은 저장만 하고 출발 알림은 예약하지 않는다`() {
        // 과거 일정은 기록 용도로 저장할 수 있지만, 이미 알림 시점이 지났으므로 PushJob을 만들지 않는다.
        val memberId = 1L
        val request = scheduleDto(
            startAt = "2026-05-31T23:00:00Z",
            endAt = "2026-06-01T00:00:00Z",
            notificationEnabled = true,
        )
        val saved = request.copy(id = 20L)
        whenever(scheduleService.addSchedule(memberId, request)).thenReturn(saved)

        val result = scheduleUseCase.addSchedule(memberId, request)

        assertEquals(20L, result.id)
        verify(scheduleService).addSchedule(memberId, request)
        verify(schedulePushJobService, never()).registerFromScheduleDto(memberId, saved)
        verify(schedulePushJobService, never()).cancelByScheduleId(20L)
    }

    @Test
    fun `알림을 끈 상태로 일정을 수정하면 기존 출발 알림 예약을 취소한다`() {
        // 수정 결과에서 알림이 꺼져 있으면 기존 PushJob이 남아 있더라도 더 이상 발송되면 안 된다.
        val memberId = 1L
        val scheduleId = 10L
        val request = scheduleDto(title = "수정된 회의")
        whenever(scheduleService.updateSchedule(memberId, scheduleId, request)).thenReturn(request.copy(id = scheduleId))

        val result = scheduleUseCase.updateSchedule(memberId, scheduleId, request)

        verify(scheduleService, times(1)).updateSchedule(memberId, scheduleId, request)
        verify(schedulePushJobService, times(1)).cancelByScheduleIdAndMemberId(scheduleId, memberId)
        verify(schedulePushJobService, never()).registerFromScheduleDto(eq(memberId), org.mockito.kotlin.any())
        assertEquals("수정된 회의", result.title)
    }

    @Test
    fun `알림이 켜진 미래 일정으로 수정하면 출발 알림 예약을 다시 계산한다`() {
        // 시간, 경로, 알림 간격이 바뀔 수 있으므로 수정된 일정 정보로 PushJob을 다시 등록한다.
        val memberId = 1L
        val scheduleId = 10L
        val request = scheduleDto(notificationEnabled = true).copy(
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
    fun `일정 조건 변경으로 오래된 참여자 계획이 되면 해당 회원 push job만 취소한다`() {
        val memberId = 1L
        val scheduleId = 10L
        val request = scheduleDto(notificationEnabled = true)
        val updated = request.copy(id = scheduleId)
        whenever(scheduleService.updateSchedule(memberId, scheduleId, request)).thenReturn(updated)
        whenever(scheduleTravelPlanService.findStaleNotificationMemberIds(scheduleId))
            .thenReturn(setOf(2L, 3L))

        scheduleUseCase.updateSchedule(memberId, scheduleId, request)

        verify(schedulePushJobService).cancelByScheduleIdAndMemberId(scheduleId, 2L)
        verify(schedulePushJobService).cancelByScheduleIdAndMemberId(scheduleId, 3L)
        verify(schedulePushJobService).registerFromScheduleDto(memberId, updated)
    }

    @Test
    fun `일정을 과거 시간으로 옮기면 저장은 허용하고 남아 있던 출발 알림은 취소한다`() {
        // 사용자가 일정 기록을 과거로 정정하는 것은 허용한다.
        // 다만 기존 PushJob이 계속 살아 있으면 뒤늦은 알림이 나갈 수 있으므로 반드시 취소한다.
        val memberId = 1L
        val scheduleId = 10L
        val request = scheduleDto(
            startAt = "2026-05-31T23:00:00Z",
            endAt = "2026-06-01T00:00:00Z",
            notificationEnabled = true,
        )
        val updated = request.copy(id = scheduleId)
        whenever(scheduleService.updateSchedule(memberId, scheduleId, request)).thenReturn(updated)

        val result = scheduleUseCase.updateSchedule(memberId, scheduleId, request)

        assertEquals(scheduleId, result.id)
        verify(schedulePushJobService, never()).registerFromScheduleDto(memberId, updated)
        verify(schedulePushJobService).cancelByScheduleIdAndMemberId(scheduleId, memberId)
    }

    @Test
    fun `출발 처리는 일정 알림을 끄고 남아 있는 push job을 취소한다`() {
        val memberId = 1L
        val scheduleId = 10L
        val before = scheduleDto(notificationEnabled = true).copy(id = scheduleId, ownerMemberId = memberId)
        val updated = scheduleDto(notificationEnabled = false).copy(id = scheduleId, ownerMemberId = memberId)
        whenever(scheduleService.getScheduleDetail(memberId, scheduleId)).thenReturn(before)
        whenever(scheduleDepartureStatusService.markDeparted(memberId, scheduleId))
            .thenReturn(ScheduleDepartureStatus(scheduleId = scheduleId, memberId = memberId, departedAt = Instant.parse("2026-06-01T00:00:00Z")))
        whenever(scheduleService.markDeparted(memberId, scheduleId)).thenReturn(updated)
        whenever(scheduleDepartureStatusService.attachDepartureParticipants(memberId, updated)).thenReturn(updated)

        val result = scheduleUseCase.markDeparted(memberId, scheduleId)

        verify(scheduleDepartureStatusService).markDeparted(memberId, scheduleId)
        verify(scheduleService).markDeparted(memberId, scheduleId)
        verify(schedulePushJobService).cancelByScheduleIdAndMemberId(scheduleId, memberId)
        verify(schedulePushJobService, never()).registerFromScheduleDto(eq(memberId), org.mockito.kotlin.any())
        assertEquals(false, result.notificationEnabled)
    }

    @Test
    fun `공유받은 사용자의 출발 처리는 참가자 상태만 기록하고 오너 push job은 취소하지 않는다`() {
        val ownerMemberId = 1L
        val sharedMemberId = 2L
        val scheduleId = 10L
        val detail = scheduleDto(notificationEnabled = true).copy(id = scheduleId, ownerMemberId = ownerMemberId)
        val decorated = detail.copy(myDepartedAt = "2026-06-01T00:00:00Z")
        whenever(scheduleService.getScheduleDetail(sharedMemberId, scheduleId)).thenReturn(detail)
        whenever(scheduleDepartureStatusService.markDeparted(sharedMemberId, scheduleId))
            .thenReturn(ScheduleDepartureStatus(scheduleId = scheduleId, memberId = sharedMemberId, departedAt = Instant.parse("2026-06-01T00:00:00Z")))
        whenever(scheduleDepartureStatusService.attachDepartureParticipants(sharedMemberId, detail)).thenReturn(decorated)

        val result = scheduleUseCase.markDeparted(sharedMemberId, scheduleId)

        verify(scheduleDepartureStatusService).markDeparted(sharedMemberId, scheduleId)
        verify(scheduleService, never()).markDeparted(sharedMemberId, scheduleId)
        verify(schedulePushJobService).cancelByScheduleIdAndMemberId(scheduleId, sharedMemberId)
        verify(schedulePushJobService, never()).cancelByScheduleId(scheduleId)
        assertEquals("2026-06-01T00:00:00Z", result.myDepartedAt)
    }

    @Test
    fun `캘린더 화면의 기간 조회는 일정 서비스에 그대로 위임한다`() {
        val memberId = 1L
        val startAt = "2026-06-01T00:00:00Z"
        val endAt = "2026-06-30T23:59:59Z"
        whenever(scheduleService.getCalendarScheduleList(memberId, startAt, endAt))
            .thenReturn(listOf(scheduleDto()))

        val result = scheduleUseCase.getCalendarScheduleList(memberId, startAt, endAt)

        verify(scheduleService, times(1)).getCalendarScheduleList(memberId, startAt, endAt)
        assertEquals(1, result.size)
    }

    @Test
    fun `하루 일정 목록 조회는 선택 날짜를 일정 서비스에 넘긴다`() {
        val memberId = 1L
        val date = "2026-06-05"
        whenever(scheduleService.getDailyScheduleList(memberId, date)).thenReturn(listOf(scheduleDto()))

        val result = scheduleUseCase.getDailyScheduleList(memberId, date)

        verify(scheduleService, times(1)).getDailyScheduleList(memberId, date)
        assertEquals(1, result.size)
    }

    @Test
    fun `다가오는 일정 조회는 기준 시각과 개수 제한을 일정 서비스에 넘긴다`() {
        val memberId = 1L
        val fromAt = "2026-06-05T00:00:00Z"
        val limit = 5
        whenever(scheduleService.getUpcomingScheduleList(memberId, fromAt, limit))
            .thenReturn(listOf(scheduleDto()))

        val result = scheduleUseCase.getUpcomingScheduleList(memberId, fromAt, limit)

        verify(scheduleService, times(1)).getUpcomingScheduleList(memberId, fromAt, limit)
        assertEquals(1, result.size)
    }

    @Test
    fun `일정 검색은 키워드와 기간 조건을 일정 서비스에 넘긴다`() {
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

        val result = scheduleUseCase.searchScheduleList(
            memberId = memberId,
            keyword = "회의",
            categoryId = "1",
            startAt = "2026-06-01T00:00:00Z",
            endAt = "2026-06-30T23:59:59Z",
        )

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
    fun `출발 준비 일정 조회는 이동 시간이 있는 일정만 서비스에서 가져온다`() {
        val memberId = 1L
        val fromAt = "2026-06-05T00:00:00Z"
        val toAt = "2026-06-06T00:00:00Z"
        whenever(scheduleService.getDepartureReadyScheduleList(memberId, fromAt, toAt))
            .thenReturn(listOf(scheduleDto(travelMinutes = 30)))

        val result = scheduleUseCase.getDepartureReadyScheduleList(memberId, fromAt, toAt)

        verify(scheduleService, times(1)).getDepartureReadyScheduleList(memberId, fromAt, toAt)
        assertEquals(30, result.first().travelMinutes)
    }

    private fun scheduleDto(
        title: String = "회의",
        travelMinutes: Int? = 20,
        startAt: String = "2026-06-05T01:00:00Z",
        endAt: String = "2026-06-05T02:00:00Z",
        notificationEnabled: Boolean? = null,
    ): ScheduleDto =
        ScheduleDto(
            title = title,
            startAt = startAt,
            endAt = endAt,
            allDay = false,
            travelMinutes = travelMinutes,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = "사무실",
            category = ScheduleCategoryDto(id = "1", title = "업무", color = "#f44336"),
            notificationEnabled = notificationEnabled,
        )
}
