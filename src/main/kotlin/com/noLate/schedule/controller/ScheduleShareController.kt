package com.noLate.schedule.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleShareService
import com.noLate.schedule.domain.ScheduleShareDto
import com.noLate.schedule.domain.ScheduleShareInboxDto
import com.noLate.schedule.domain.ScheduleShareInvitationAcceptDto
import com.noLate.schedule.domain.ScheduleShareInvitationDto
import com.noLate.schedule.domain.ScheduleShareOutboxDto
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

@RestController
@RequestMapping("/api/shares")
@Tag(name = "Share Inbox", description = "공유함 API")
class ShareInboxController(
    private val scheduleShareService: ScheduleShareService,
) {
    @Operation(summary = "내가 받은 공유 목록 조회")
    @GetMapping("/inbox")
    fun getShareInbox(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<ScheduleShareInboxDto> {
        return ApiResponse.success(
            scheduleShareService.getShareInbox(
                memberId = requireShareMemberId(principal),
            )
        )
    }

    @Operation(summary = "내가 공유한 항목과 활성 링크 조회")
    @GetMapping("/outbox")
    fun getShareOutbox(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<ScheduleShareOutboxDto> {
        return ApiResponse.success(
            scheduleShareService.getShareOutbox(
                ownerMemberId = requireShareMemberId(principal),
            )
        )
    }
}

@RestController
@RequestMapping("/api/schedules/{scheduleId}/shares")
@Tag(name = "Schedule Share", description = "일정 공유 API")
class ScheduleShareController(
    private val scheduleShareService: ScheduleShareService,
) {
    @Operation(summary = "일정 공유 대상 목록 조회")
    @GetMapping
    fun getScheduleShares(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<List<ScheduleShareDto>> {
        return ApiResponse.success(
            scheduleShareService.getScheduleShares(
                ownerMemberId = requireShareMemberId(principal),
                scheduleId = scheduleId,
            )
        )
    }

    @Operation(summary = "일정 공유")
    @PostMapping
    fun shareSchedule(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @RequestBody request: CreateShareRequest,
    ): ApiResponse<ScheduleShareDto> {
        return ApiResponse.success(
            scheduleShareService.shareSchedule(
                ownerMemberId = requireShareMemberId(principal),
                scheduleId = scheduleId,
                targetEmail = request.targetEmail,
                targetAppId = request.targetAppId,
                permission = request.permission ?: ScheduleSharePermission.VIEWER,
            )
        )
    }

    @Operation(summary = "일정 공유 권한 변경")
    @PatchMapping("/{shareId}")
    fun updateScheduleShare(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @PathVariable shareId: Long,
        @RequestBody request: UpdateShareRequest,
    ): ApiResponse<ScheduleShareDto> {
        return ApiResponse.success(
            scheduleShareService.updateScheduleShare(
                ownerMemberId = requireShareMemberId(principal),
                scheduleId = scheduleId,
                shareId = shareId,
                permission = request.permission,
            )
        )
    }

    @Operation(summary = "일정 공유 해제")
    @DeleteMapping("/{shareId}")
    fun revokeScheduleShare(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @PathVariable shareId: Long,
    ): ApiResponse<Unit> {
        scheduleShareService.revokeScheduleShare(
            ownerMemberId = requireShareMemberId(principal),
            scheduleId = scheduleId,
            shareId = shareId,
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "일정 공유 초대 링크 목록 조회")
    @GetMapping("/invitations")
    fun getScheduleInvitations(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
    ): ApiResponse<List<ScheduleShareInvitationDto>> {
        return ApiResponse.success(
            scheduleShareService.getScheduleInvitations(
                ownerMemberId = requireShareMemberId(principal),
                scheduleId = scheduleId,
            )
        )
    }

    @Operation(summary = "일정 공유 초대 링크 생성")
    @PostMapping("/invitations")
    fun createScheduleInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @RequestBody request: CreateShareInvitationRequest,
    ): ApiResponse<ScheduleShareInvitationDto> {
        return ApiResponse.success(
            scheduleShareService.createScheduleInvitation(
                ownerMemberId = requireShareMemberId(principal),
                scheduleId = scheduleId,
                permission = request.permission ?: ScheduleSharePermission.VIEWER,
                ttlHours = request.ttlHours,
                maxAcceptCount = request.maxAcceptCount,
            )
        )
    }

    @Operation(summary = "일정 공유 초대 링크 폐기")
    @DeleteMapping("/invitations/{invitationId}")
    fun revokeScheduleInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @PathVariable invitationId: Long,
    ): ApiResponse<Unit> {
        scheduleShareService.revokeInvitation(
            ownerMemberId = requireShareMemberId(principal),
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = scheduleId,
            invitationId = invitationId,
        )
        return ApiResponse.success(Unit)
    }
}

@RestController
@RequestMapping("/api/schedule-categories/{categoryId}/shares")
@Tag(name = "Schedule Category Share", description = "일정 카테고리 공유 API")
class ScheduleCategoryShareController(
    private val scheduleShareService: ScheduleShareService,
) {
    @Operation(summary = "일정 카테고리 공유 대상 목록 조회")
    @GetMapping
    fun getCategoryShares(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
    ): ApiResponse<List<ScheduleShareDto>> {
        return ApiResponse.success(
            scheduleShareService.getCategoryShares(
                ownerMemberId = requireShareMemberId(principal),
                categoryId = categoryId,
            )
        )
    }

    @Operation(summary = "일정 카테고리 공유")
    @PostMapping
    fun shareCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @RequestBody request: CreateShareRequest,
    ): ApiResponse<ScheduleShareDto> {
        return ApiResponse.success(
            scheduleShareService.shareCategory(
                ownerMemberId = requireShareMemberId(principal),
                categoryId = categoryId,
                targetEmail = request.targetEmail,
                targetAppId = request.targetAppId,
                permission = request.permission ?: ScheduleSharePermission.VIEWER,
            )
        )
    }

    @Operation(summary = "일정 카테고리 공유 권한 변경")
    @PatchMapping("/{shareId}")
    fun updateCategoryShare(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @PathVariable shareId: Long,
        @RequestBody request: UpdateShareRequest,
    ): ApiResponse<ScheduleShareDto> {
        return ApiResponse.success(
            scheduleShareService.updateCategoryShare(
                ownerMemberId = requireShareMemberId(principal),
                categoryId = categoryId,
                shareId = shareId,
                permission = request.permission,
            )
        )
    }

    @Operation(summary = "일정 카테고리 공유 해제")
    @DeleteMapping("/{shareId}")
    fun revokeCategoryShare(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @PathVariable shareId: Long,
    ): ApiResponse<Unit> {
        scheduleShareService.revokeCategoryShare(
            ownerMemberId = requireShareMemberId(principal),
            categoryId = categoryId,
            shareId = shareId,
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "일정 카테고리 공유 초대 링크 목록 조회")
    @GetMapping("/invitations")
    fun getCategoryInvitations(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
    ): ApiResponse<List<ScheduleShareInvitationDto>> {
        return ApiResponse.success(
            scheduleShareService.getCategoryInvitations(
                ownerMemberId = requireShareMemberId(principal),
                categoryId = categoryId,
            )
        )
    }

    @Operation(summary = "일정 카테고리 공유 초대 링크 생성")
    @PostMapping("/invitations")
    fun createCategoryInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @RequestBody request: CreateShareInvitationRequest,
    ): ApiResponse<ScheduleShareInvitationDto> {
        return ApiResponse.success(
            scheduleShareService.createCategoryInvitation(
                ownerMemberId = requireShareMemberId(principal),
                categoryId = categoryId,
                permission = request.permission ?: ScheduleSharePermission.VIEWER,
                ttlHours = request.ttlHours,
                maxAcceptCount = request.maxAcceptCount,
            )
        )
    }

    @Operation(summary = "일정 카테고리 공유 초대 링크 폐기")
    @DeleteMapping("/invitations/{invitationId}")
    fun revokeCategoryInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @PathVariable invitationId: Long,
    ): ApiResponse<Unit> {
        scheduleShareService.revokeInvitation(
            ownerMemberId = requireShareMemberId(principal),
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = categoryId,
            invitationId = invitationId,
        )
        return ApiResponse.success(Unit)
    }
}

@RestController
@RequestMapping("/api/share-invitations")
@Tag(name = "Share Invitation", description = "공유 초대 링크 수락 API")
class ShareInvitationController(
    private val scheduleShareService: ScheduleShareService,
) {
    @Operation(summary = "공유 초대 링크 수락")
    @PostMapping("/{token}/accept")
    fun acceptInvitation(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable token: String,
    ): ApiResponse<ScheduleShareInvitationAcceptDto> {
        return ApiResponse.success(
            scheduleShareService.acceptInvitation(
                currentMemberId = requireShareMemberId(principal),
                token = token,
            )
        )
    }
}

data class CreateShareRequest(
    /**
     * 이메일과 앱 ID는 공유 대상을 찾는 서로 다른 키다.
     * 잘못된 대상을 조용히 선택하지 않도록 서비스에서 둘 중 정확히 하나만 허용한다.
     */
    val targetEmail: String? = null,
    val targetAppId: Long? = null,
    val permission: ScheduleSharePermission? = ScheduleSharePermission.VIEWER,
)

data class UpdateShareRequest(
    val permission: ScheduleSharePermission,
)

data class CreateShareInvitationRequest(
    val permission: ScheduleSharePermission? = ScheduleSharePermission.VIEWER,
    val ttlHours: Long? = null,
    val maxAcceptCount: Int? = null,
)

private fun requireShareMemberId(principal: MemberPrincipal?): Long =
    principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
