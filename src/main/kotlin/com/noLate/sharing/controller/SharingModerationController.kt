package com.noLate.sharing.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.sharing.application.SharingSafetyService
import com.noLate.sharing.domain.SharingModerationDashboardDto
import com.noLate.sharing.domain.SharingModerationReportDto
import com.noLate.sharing.domain.SharingReportStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class SharingModerationController(
    private val service: SharingSafetyService,
) {
    @GetMapping("/sharing-admin")
    fun dashboardPage(): String = "forward:/sharing-admin/index.html"

    @ResponseBody
    @GetMapping("/api/sharing-moderation/reports")
    fun dashboard(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam(required = false) status: Set<SharingReportStatus>?,
    ): ApiResponse<SharingModerationDashboardDto> = ApiResponse.success(
        service.getModerationDashboard(
            moderatorMemberId = requireMemberId(principal),
            statuses = status.orEmpty(),
        )
    )

    @ResponseBody
    @PatchMapping("/api/sharing-moderation/reports/{reportId}")
    fun moderate(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable reportId: Long,
        @RequestBody request: ModerateSharingReportRequest,
    ): ApiResponse<SharingModerationReportDto> = ApiResponse.success(
        service.moderateReport(
            moderatorMemberId = requireMemberId(principal),
            reportId = reportId,
            status = request.status,
            resolutionNote = request.resolutionNote,
        )
    )
}

data class ModerateSharingReportRequest(
    val status: SharingReportStatus,
    val resolutionNote: String? = null,
)

private fun requireMemberId(principal: MemberPrincipal?): Long =
    principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
