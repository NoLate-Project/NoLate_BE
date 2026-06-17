package com.noLate.schedule.dev

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
@RequestMapping("/api/dev/schedule-push-scenarios")
@ConditionalOnProperty(
    prefix = "notification.push-schedule-scenario",
    name = ["enabled"],
    havingValue = "true",
)
class SchedulePushScenarioController(
    private val runner: SchedulePushScenarioRunner,
) {

    @PostMapping("/run")
    fun run(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: SchedulePushScenarioRunRequest,
    ): ApiResponse<SchedulePushScenarioRunResponse> {
        val memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return ApiResponse.success(runner.run(memberId, request))
    }
}
