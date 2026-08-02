package com.noLate.sharing.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.sharing.application.SharingSafetyService
import com.noLate.sharing.domain.BlockedSharingMemberDto
import com.noLate.sharing.domain.SharingReportDto
import com.noLate.sharing.domain.SharingReportReason
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sharing-safety")
@Tag(name = "Sharing safety", description = "공유 신고와 회원 차단 API")
class SharingSafetyController(
    private val service: SharingSafetyService,
) {
    @Operation(summary = "공유 항목 신고")
    @PostMapping("/reports")
    fun report(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: CreateSharingReportRequest,
    ): ApiResponse<SharingReportDto> {
        val actor = requireActor(principal)
        return ApiResponse.success(
            service.reportShare(
                reporterMemberId = actor.memberId,
                reportedMemberId = request.reportedMemberId,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                reason = request.reason,
                details = request.details,
                presentedSessionGeneration = actor.sessionGeneration,
            )
        )
    }

    @Operation(summary = "내 신고 내역 조회")
    @GetMapping("/reports")
    fun getMyReports(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<SharingReportDto>> = ApiResponse.success(
        service.getMyReports(requireActor(principal).memberId)
    )

    @Operation(summary = "공유 회원 차단")
    @PostMapping("/blocks/{targetMemberId}")
    fun block(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable targetMemberId: Long,
    ): ApiResponse<BlockedSharingMemberDto> {
        val actor = requireActor(principal)
        return ApiResponse.success(
            service.blockMember(actor.memberId, targetMemberId, actor.sessionGeneration)
        )
    }

    @Operation(summary = "차단 해제")
    @DeleteMapping("/blocks/{targetMemberId}")
    fun unblock(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable targetMemberId: Long,
    ): ApiResponse<Unit> {
        val actor = requireActor(principal)
        service.unblockMember(actor.memberId, targetMemberId, actor.sessionGeneration)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "차단 회원 목록 조회")
    @GetMapping("/blocks")
    fun getBlockedMembers(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<BlockedSharingMemberDto>> = ApiResponse.success(
        service.getBlockedMembers(requireActor(principal).memberId)
    )
}

data class CreateSharingReportRequest(
    val reportedMemberId: Long,
    val resourceType: ScheduleShareResourceType,
    val resourceId: Long,
    val reason: SharingReportReason = SharingReportReason.UNWANTED_SHARING,
    val details: String? = null,
)

private data class SharingSafetyActor(
    val memberId: Long,
    val sessionGeneration: Long,
)

private fun requireActor(principal: MemberPrincipal?): SharingSafetyActor {
    val authenticated = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
    return SharingSafetyActor(
        memberId = authenticated.id,
        sessionGeneration = authenticated.accessTokenSessionGeneration
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN),
    )
}
