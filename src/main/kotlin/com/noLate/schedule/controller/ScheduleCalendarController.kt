package com.noLate.schedule.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleCalendarService
import com.noLate.schedule.application.service.ScheduleShareService
import com.noLate.schedule.domain.ScheduleCalendarDto
import com.noLate.schedule.domain.ScheduleCalendarMemberDto
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleShareInvitationDto
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 공유 캘린더는 화면 분류용 category와 별개의 협업 리소스다. 이 API는 캘린더 자체의
 * 수명주기와 멤버십만 다루며, 캘린더에 일정을 넣는 권한은 ScheduleService가 같은
 * membership row를 기준으로 다시 검증한다.
 */
@RestController
@RequestMapping("/api/schedule-calendars")
@Tag(name = "Schedule Calendar", description = "공유 캘린더와 멤버십 API")
class ScheduleCalendarController(
    private val calendarService: ScheduleCalendarService,
    private val shareService: ScheduleShareService,
) {

    @Operation(summary = "공유 캘린더 목록 조회")
    @GetMapping
    fun getCalendars(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<ScheduleCalendarDto>> =
        ApiResponse.success(calendarService.getCalendars(requireCalendarMemberId(principal)))

    @Operation(summary = "공유 캘린더 생성")
    @PostMapping
    fun createCalendar(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: CreateScheduleCalendarRequest,
    ): ApiResponse<ScheduleCalendarDto> = ApiResponse.success(
        calendarService.createCalendar(
            ownerMemberId = requireCalendarMemberId(principal),
            title = request.title,
            color = request.color,
            defaultContentMode = request.defaultContentMode,
        )
    )

    @Operation(summary = "공유 캘린더 상세 조회")
    @GetMapping("/{calendarId}")
    fun getCalendar(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
    ): ApiResponse<ScheduleCalendarDto> = ApiResponse.success(
        calendarService.getCalendar(requireCalendarMemberId(principal), calendarId)
    )

    @Operation(summary = "공유 캘린더 설정 변경")
    @PatchMapping("/{calendarId}")
    fun updateCalendar(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @RequestBody request: UpdateScheduleCalendarRequest,
    ): ApiResponse<ScheduleCalendarDto> = ApiResponse.success(
        calendarService.updateCalendar(
            ownerMemberId = requireCalendarMemberId(principal),
            calendarId = calendarId,
            title = request.title,
            color = request.color,
            defaultContentMode = request.defaultContentMode,
        )
    )

    @Operation(summary = "공유 캘린더 보관")
    @DeleteMapping("/{calendarId}")
    fun archiveCalendar(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
    ): ApiResponse<Unit> {
        calendarService.archiveCalendar(requireCalendarMemberId(principal), calendarId)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "공유 캘린더 멤버 목록 조회")
    @GetMapping("/{calendarId}/members")
    fun getMembers(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
    ): ApiResponse<List<ScheduleCalendarMemberDto>> = ApiResponse.success(
        calendarService.getMembers(requireCalendarMemberId(principal), calendarId)
    )

    @Operation(summary = "이메일 또는 앱 ID로 캘린더 직접 공유")
    @PostMapping("/{calendarId}/members")
    fun addMember(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @RequestBody request: AddScheduleCalendarMemberRequest,
    ): ApiResponse<ScheduleCalendarMemberDto> = ApiResponse.success(
        calendarService.addMember(
            ownerMemberId = requireCalendarMemberId(principal),
            calendarId = calendarId,
            targetEmail = request.targetEmail,
            targetAppId = request.targetAppId,
            role = request.role ?: ScheduleCalendarRole.VIEWER,
        )
    )

    @Operation(summary = "공유 캘린더 멤버 권한 변경")
    @PatchMapping("/{calendarId}/members/{memberId}")
    fun updateMember(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @PathVariable memberId: Long,
        @RequestBody request: UpdateScheduleCalendarMemberRequest,
    ): ApiResponse<ScheduleCalendarMemberDto> = ApiResponse.success(
        calendarService.updateMember(
            ownerMemberId = requireCalendarMemberId(principal),
            calendarId = calendarId,
            targetMemberId = memberId,
            role = request.role,
        )
    )

    @Operation(summary = "내 공유 캘린더 경로 알림 설정 변경")
    @PatchMapping("/{calendarId}/preferences")
    fun updateMyPreferences(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @RequestBody request: UpdateMyScheduleCalendarPreferencesRequest,
    ): ApiResponse<ScheduleCalendarMemberDto> = ApiResponse.success(
        calendarService.updateMyPreferences(
            memberId = requireCalendarMemberId(principal),
            calendarId = calendarId,
            routeReminderEnabled = request.routeReminderEnabled,
        )
    )

    @Operation(summary = "공유 캘린더 멤버 제거")
    @DeleteMapping("/{calendarId}/members/{memberId}")
    fun removeMember(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @PathVariable memberId: Long,
    ): ApiResponse<Unit> {
        calendarService.removeMember(requireCalendarMemberId(principal), calendarId, memberId)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "공유 캘린더 나가기")
    @PostMapping("/{calendarId}/leave")
    fun leaveCalendar(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
    ): ApiResponse<Unit> {
        calendarService.leaveCalendar(requireCalendarMemberId(principal), calendarId)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "공유 캘린더 소유권 이전")
    @PostMapping("/{calendarId}/ownership")
    fun transferOwnership(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @RequestBody request: TransferScheduleCalendarOwnershipRequest,
    ): ApiResponse<ScheduleCalendarDto> = ApiResponse.success(
        calendarService.transferOwnership(
            ownerMemberId = requireCalendarMemberId(principal),
            calendarId = calendarId,
            targetMemberId = request.targetMemberId,
        )
    )

    @Operation(summary = "공유 캘린더 초대 링크 목록 조회")
    @GetMapping("/{calendarId}/invitations")
    fun getInvitations(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
    ): ApiResponse<List<ScheduleShareInvitationDto>> = ApiResponse.success(
        shareService.getCalendarInvitations(requireCalendarMemberId(principal), calendarId)
    )

    @Operation(summary = "공유 캘린더 초대 링크 생성")
    @PostMapping("/{calendarId}/invitations")
    fun createInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @RequestBody request: CreateScheduleCalendarInvitationRequest,
    ): ApiResponse<ScheduleShareInvitationDto> = ApiResponse.success(
        shareService.createCalendarInvitation(
            ownerMemberId = requireCalendarMemberId(principal),
            calendarId = calendarId,
            permission = request.permission ?: ScheduleSharePermission.VIEWER,
            ttlHours = request.ttlHours,
            maxAcceptCount = request.maxAcceptCount,
        )
    )

    @Operation(summary = "공유 캘린더 초대 링크 폐기")
    @DeleteMapping("/{calendarId}/invitations/{invitationId}")
    fun revokeInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable calendarId: Long,
        @PathVariable invitationId: Long,
    ): ApiResponse<Unit> {
        shareService.revokeInvitation(
            ownerMemberId = requireCalendarMemberId(principal),
            resourceType = ScheduleShareResourceType.CALENDAR,
            resourceId = calendarId,
            invitationId = invitationId,
        )
        return ApiResponse.success(Unit)
    }
}

data class CreateScheduleCalendarRequest(
    val title: String? = null,
    val color: String? = null,
    val defaultContentMode: ScheduleShareContentMode? = ScheduleShareContentMode.SCHEDULE_ONLY,
)

data class UpdateScheduleCalendarRequest(
    val title: String? = null,
    val color: String? = null,
    val defaultContentMode: ScheduleShareContentMode? = null,
)

data class AddScheduleCalendarMemberRequest(
    val targetEmail: String? = null,
    val targetAppId: Long? = null,
    val role: ScheduleCalendarRole? = ScheduleCalendarRole.VIEWER,
)

data class UpdateScheduleCalendarMemberRequest(
    val role: ScheduleCalendarRole? = null,
)

data class UpdateMyScheduleCalendarPreferencesRequest(
    val routeReminderEnabled: Boolean,
)

data class TransferScheduleCalendarOwnershipRequest(
    val targetMemberId: Long,
)

data class CreateScheduleCalendarInvitationRequest(
    val permission: ScheduleSharePermission? = ScheduleSharePermission.VIEWER,
    val ttlHours: Long? = null,
    val maxAcceptCount: Int? = null,
)

private fun requireCalendarMemberId(principal: MemberPrincipal?): Long =
    principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
