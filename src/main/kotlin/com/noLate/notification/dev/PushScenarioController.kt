package com.noLate.notification.dev

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dev/push-scenarios")
@ConditionalOnProperty(
    prefix = "notification.push-scenario",
    name = ["enabled"],
    havingValue = "true",
)
class PushScenarioController(
    private val pushScenarioRunner: PushScenarioRunner,
) {

    @PostMapping("/run")
    fun run(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: PushScenarioRunRequest,
    ): ApiResponse<PushScenarioRunResponse> {
        val memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return ApiResponse.success(pushScenarioRunner.run(memberId, request))
    }
}
