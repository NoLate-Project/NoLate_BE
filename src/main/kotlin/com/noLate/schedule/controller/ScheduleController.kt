package com.noLate.schedule.controller

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleUseCase
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportResultDto
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/schedules")
@Tag(name = "Schedule", description = "일정 관리 API")
class ScheduleController(
    private val scheduleUseCase: ScheduleUseCase,
) {

    /**
     * 자유 형식 예약 문구 분석.
     * 저장하지 않고 일정 입력 폼에 반영할 후보값과 누락 필드를 반환한다.
     */
    @Operation(summary = "자유 형식 일정 문구 분석")
    @PostMapping("/parse")
    fun parseScheduleText(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: ParseScheduleTextRequest,
    ): ApiResponse<ScheduleParseDto> {
        val memberId = requireMemberId(principal)
        val result = scheduleUseCase.parseScheduleText(
            memberId = memberId,
            text = request.text,
            // 구버전 FE가 inputType을 보내지 않아도 기존 TEXT 동작을 유지한다.
            // 값이 있으면 음성/OCR 정규화 정책이 적용되도록 서비스 계층까지 전달한다.
            inputType = request.inputType ?: ScheduleParseInputType.TEXT,
            recognitionConfidence = request.recognitionConfidence,
            referenceDate = request.referenceDate,
            defaultDurationMinutes = request.defaultDurationMinutes,
        )
        return ApiResponse.success(result)
    }

    /**
     * 일정 저장.
     * 기존 컨트롤러 패턴처럼 Controller 전용 Request를 받은 뒤 DTO로 변환해서 UseCase로 전달한다.
     */
    @Operation(summary = "일정 저장")
    @PostMapping
    fun addSchedule(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: AddScheduleRequest,
    ): ApiResponse<ScheduleDto> {
        val result = scheduleUseCase.addSchedule(requireMemberId(principal), request.toDto())
        return ApiResponse.success(result)
    }

    /**
     * 외부 캘린더 일정 저장.
     * 같은 회원이 같은 원본 발생 건을 다시 보내면 기존 일정과 created=false를 반환한다.
     */
    @Operation(summary = "외부 캘린더 일정 가져오기")
    @PostMapping("/import")
    fun importSchedule(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: ImportCalendarScheduleRequest,
    ): ApiResponse<ScheduleImportResultDto> {
        val result = scheduleUseCase.importSchedule(
            memberId = requireMemberId(principal),
            scheduleDto = request.schedule.toDto(),
            source = request.source.toDomain(),
        )
        return ApiResponse.success(result)
    }

    /**
     * 일정 수정.
     * 일정 시간, 카테고리, 출발지/도착지, 선택 경로 정보를 한 번에 갱신한다.
     */
    @Operation(summary = "일정 수정")
    @PutMapping("/{scheduleId}")
    fun updateSchedule(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @RequestBody request: UpdateScheduleRequest,
    ): ApiResponse<ScheduleDto> {
        val result = scheduleUseCase.updateSchedule(requireMemberId(principal), scheduleId, request.toDto())
        return ApiResponse.success(result)
    }

    /**
     * 일정 삭제.
     * 실제 row 삭제가 아니라 BaseAt.deleted flag를 변경하는 soft delete 방식이다.
     */
    @Operation(summary = "일정 삭제")
    @DeleteMapping("/{scheduleId}")
    fun deleteSchedule(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<Unit> {
        scheduleUseCase.deleteSchedule(requireMemberId(principal), scheduleId)
        return ApiResponse.success(Unit)
    }

    /**
     * 사용자가 출발했음을 기록하고 해당 일정의 실시간 출발 푸시를 중지한다.
     */
    @Operation(summary = "출발 처리 및 실시간 출발 알림 중지")
    @PostMapping("/{scheduleId}/depart-now")
    fun markScheduleDeparted(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<ScheduleDto> {
        val result = scheduleUseCase.markDeparted(requireMemberId(principal), scheduleId)
        return ApiResponse.success(result)
    }

    /**
     * 사용자가 푸시의 "5분 뒤 다시 알림" 액션을 선택했을 때 출발 알림을 재예약한다.
     */
    @Operation(summary = "출발 알림 5분 뒤 다시 알림")
    @PostMapping("/{scheduleId}/departure-reminder/snooze")
    fun snoozeDepartureReminder(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<Unit> {
        scheduleUseCase.snoozeDepartureReminder(requireMemberId(principal), scheduleId)
        return ApiResponse.success(Unit)
    }

    /**
     * 전체 일정 목록 조회.
     * 초기 동기화나 전체 리스트 화면에서 사용한다.
     */
    @Operation(summary = "전체 일정 목록 조회")
    @GetMapping
    fun getScheduleList(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<ScheduleDto>> {
        val result = scheduleUseCase.getScheduleList(requireMemberId(principal))
        return ApiResponse.success(result)
    }

    /**
     * 캘린더 범위 일정 조회.
     * 월간/주간 캘린더에 표시되는 날짜 범위와 겹치는 일정을 조회한다.
     */
    @Operation(summary = "캘린더 범위 일정 조회")
    @GetMapping("/calendar")
    fun getCalendarScheduleList(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam startAt: String,
        @RequestParam endAt: String,
    ): ApiResponse<List<ScheduleDto>> {
        val result = scheduleUseCase.getCalendarScheduleList(
            memberId = requireMemberId(principal),
            startAt = startAt,
            endAt = endAt,
        )
        return ApiResponse.success(result)
    }

    /**
     * 하루 일정 조회.
     * 사용자가 선택한 날짜의 일정 리스트나 타임라인을 구성할 때 사용한다.
     */
    @Operation(summary = "하루 일정 조회")
    @GetMapping("/daily")
    fun getDailyScheduleList(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam date: String,
    ): ApiResponse<List<ScheduleDto>> {
        val result = scheduleUseCase.getDailyScheduleList(requireMemberId(principal), date)
        return ApiResponse.success(result)
    }

    /**
     * 다가오는 일정 조회.
     * 홈 화면의 다음 일정 카드나 알림 준비 작업에서 사용한다.
     */
    @Operation(summary = "다가오는 일정 조회")
    @GetMapping("/upcoming")
    fun getUpcomingScheduleList(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) fromAt: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<ScheduleDto>> {
        val result = scheduleUseCase.getUpcomingScheduleList(
            memberId = requireMemberId(principal),
            fromAt = fromAt,
            limit = limit,
        )
        return ApiResponse.success(result)
    }

    /**
     * 일정 검색/필터 조회.
     * 키워드, 카테고리, 기간 조건으로 일정 목록을 좁힌다.
     */
    @Operation(summary = "일정 검색/필터 조회")
    @GetMapping("/search")
    fun searchScheduleList(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) categoryId: String?,
        @RequestParam(required = false) startAt: String?,
        @RequestParam(required = false) endAt: String?,
    ): ApiResponse<List<ScheduleDto>> {
        val result = scheduleUseCase.searchScheduleList(
            memberId = requireMemberId(principal),
            keyword = keyword,
            categoryId = categoryId,
            startAt = startAt,
            endAt = endAt,
        )
        return ApiResponse.success(result)
    }

    /**
     * 출발 준비 일정 조회.
     * 이동 시간이나 경로 정보가 있는 일정만 조회해서 NoLate 출발 알림 후보로 사용한다.
     */
    @Operation(summary = "출발 준비 일정 조회")
    @GetMapping("/departures")
    fun getDepartureReadyScheduleList(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) fromAt: String?,
        @RequestParam(required = false) toAt: String?,
    ): ApiResponse<List<ScheduleDto>> {
        val result = scheduleUseCase.getDepartureReadyScheduleList(
            memberId = requireMemberId(principal),
            fromAt = fromAt,
            toAt = toAt,
        )
        return ApiResponse.success(result)
    }

    /**
     * 일정 상세 조회.
     * scheduleId와 인증 회원 id를 같이 확인해서 다른 회원의 일정 접근을 막는다.
     */
    @Operation(summary = "일정 상세 조회")
    @GetMapping("/{scheduleId}")
    fun getScheduleDetail(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<ScheduleDto> {
        val result = scheduleUseCase.getScheduleDetail(requireMemberId(principal), scheduleId)
        return ApiResponse.success(result)
    }

    private fun requireMemberId(principal: MemberPrincipal?): Long {
        return principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
    }
}

/**
 * 일정 분석 API가 받는 입력 모델이다.
 *
 * referenceDate는 연도가 생략된 문장의 기준 날짜이며, 기본 소요 시간은 종료 시각 계산에 사용한다.
 */
data class ParseScheduleTextRequest(
    val text: String,
    val inputType: ScheduleParseInputType? = null,
    val recognitionConfidence: Double? = null,
    val referenceDate: String? = null,
    val defaultDurationMinutes: Int? = null,
)

data class AddScheduleRequest(
    val calendarId: Long? = null,
    val scheduleType: com.noLate.schedule.domain.ScheduleType? = null,
    val calendarContentModeOverride: com.noLate.schedule.domain.ScheduleShareContentMode? = null,
    val title: String,
    val startAt: String,
    val endAt: String? = null,
    val hasEndTime: Boolean? = null,
    val allDay: Boolean? = null,
    val travelMinutes: Int? = null,
    val departAt: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val origin: SchedulePlaceDto? = null,
    val destination: SchedulePlaceDto? = null,
    val locationName: String? = null,
    val category: ScheduleCategoryDto,
    val notes: String? = null,
    val routeSetupRequired: Boolean? = null,
    val route: JsonNode? = null,
    val notificationEnabled: Boolean? = null,
    val notificationLeadMinutes: Int? = null,
    val notificationIntervalMinutes: Int? = null,
) {
    fun toDto(): ScheduleDto =
        ScheduleDto(
            calendarId = calendarId,
            scheduleType = scheduleType,
            calendarContentModeOverride = calendarContentModeOverride,
            title = title,
            startAt = startAt,
            endAt = endAt,
            hasEndTime = hasEndTime,
            allDay = allDay,
            travelMinutes = travelMinutes,
            departAt = departAt,
            travelMode = travelMode,
            origin = origin,
            destination = destination,
            locationName = locationName,
            category = category,
            notes = notes,
            routeSetupRequired = routeSetupRequired,
            route = route,
            notificationEnabled = notificationEnabled,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
        )
}

data class ImportCalendarScheduleRequest(
    val schedule: AddScheduleRequest,
    val source: CalendarImportSourceRequest,
)

data class CalendarImportSourceRequest(
    val provider: ScheduleImportProvider,
    val calendarId: String,
    val eventId: String,
    val occurrenceStartAt: String,
) {
    fun toDomain(): ScheduleImportSource = ScheduleImportSource(
        provider = provider,
        calendarId = calendarId,
        eventId = eventId,
        occurrenceStartAt = occurrenceStartAt,
    )
}

data class UpdateScheduleRequest(
    val calendarId: Long? = null,
    val scheduleType: com.noLate.schedule.domain.ScheduleType? = null,
    val calendarContentModeOverride: com.noLate.schedule.domain.ScheduleShareContentMode? = null,
    val title: String,
    val startAt: String,
    val endAt: String? = null,
    val hasEndTime: Boolean? = null,
    val allDay: Boolean? = null,
    val travelMinutes: Int? = null,
    val departAt: String? = null,
    val travelMode: ScheduleTravelMode? = null,
    val origin: SchedulePlaceDto? = null,
    val destination: SchedulePlaceDto? = null,
    val locationName: String? = null,
    val category: ScheduleCategoryDto,
    val notes: String? = null,
    val routeSetupRequired: Boolean? = null,
    val route: JsonNode? = null,
    val notificationEnabled: Boolean? = null,
    val notificationLeadMinutes: Int? = null,
    val notificationIntervalMinutes: Int? = null,
) {
    fun toDto(): ScheduleDto =
        ScheduleDto(
            calendarId = calendarId,
            scheduleType = scheduleType,
            calendarContentModeOverride = calendarContentModeOverride,
            title = title,
            startAt = startAt,
            endAt = endAt,
            hasEndTime = hasEndTime,
            allDay = allDay,
            travelMinutes = travelMinutes,
            departAt = departAt,
            travelMode = travelMode,
            origin = origin,
            destination = destination,
            locationName = locationName,
            category = category,
            notes = notes,
            routeSetupRequired = routeSetupRequired,
            route = route,
            notificationEnabled = notificationEnabled,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
        )
}
