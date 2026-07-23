package com.noLate.calendar.controller

import com.noLate.calendar.application.CalendarMetadataService
import com.noLate.calendar.domain.CalendarDayDto
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/calendar")
@Tag(name = "Calendar", description = "대한민국 공휴일 및 음양력 메타데이터 API")
class CalendarController(
    private val calendarMetadataService: CalendarMetadataService,
) {
    @Operation(summary = "대한민국 공휴일 및 음력 날짜 범위 조회")
    @GetMapping("/days")
    fun getDays(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate,
    ): ApiResponse<List<CalendarDayDto>> {
        requireMember(principal)
        return ApiResponse.success(calendarMetadataService.getDays(startDate, endDate))
    }

    private fun requireMember(principal: MemberPrincipal?): Long =
        principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
}
