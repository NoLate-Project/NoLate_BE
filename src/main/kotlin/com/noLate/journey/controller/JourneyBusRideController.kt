package com.noLate.journey.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.JourneyBusRideService
import com.noLate.journey.domain.JourneyBusBindingCommand
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/schedules/{scheduleId}/automatic-journey/{sessionId}/bus-ride")
class JourneyBusRideController(private val service: JourneyBusRideService) {
    @PostMapping("/binding")
    fun binding(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @PathVariable sessionId: String, @RequestBody command: JourneyBusBindingCommand) = principal(principal).let {
        ApiResponse.success(service.binding(it.id, scheduleId, requireNotNull(it.accessTokenSessionGeneration), sessionId, command))
    }
    @PostMapping
    fun observe(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @PathVariable sessionId: String, @RequestBody command: JourneyBusBindingCommand) = principal(principal).let {
        ApiResponse.success(service.observe(it.id, scheduleId, requireNotNull(it.accessTokenSessionGeneration), sessionId, command))
    }
    private fun principal(value: MemberPrincipal?): MemberPrincipal {
        val actor = value ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        if (actor.accessTokenSessionGeneration == null) throw BusinessException(ErrorCode.INVALID_TOKEN)
        return actor
    }
}
