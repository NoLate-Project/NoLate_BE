package com.noLate.subscription.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.subscription.application.SubscriptionPolicyService
import com.noLate.subscription.domain.SubscriptionPolicyDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/subscriptions")
@Tag(name = "Subscription", description = "회원 요금제와 사용 제한 API")
class SubscriptionController(
    private val subscriptionPolicyService: SubscriptionPolicyService,
) {
    @Operation(summary = "내 요금제 정책과 이번 달 사용량 조회")
    @GetMapping("/me")
    fun getMyPolicy(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<SubscriptionPolicyDto> {
        val memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return ApiResponse.success(subscriptionPolicyService.getPolicy(memberId))
    }
}
