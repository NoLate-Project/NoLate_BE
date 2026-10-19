package com.noLate.journey.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.JourneyBusArrivalService
import com.noLate.journey.domain.JourneyBusArrivalCommand
import com.noLate.journey.domain.JourneyBusBindingCommand
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/schedules/{scheduleId}/automatic-journey/{sessionId}/bus-arrivals")
class JourneyBusArrivalController(private val service: JourneyBusArrivalService) {
    @PostMapping("/binding")
    fun binding(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @PathVariable sessionId: String, @RequestBody command: JourneyBusBindingCommand) = run {
        val actor = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val loginGeneration = actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        ApiResponse.success(service.binding(actor.id, scheduleId, loginGeneration, sessionId, command))
    }
    /** Read-only POST keeps session identifiers out of URL query strings. */
    @PostMapping
    fun observe(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @PathVariable sessionId: String, @RequestBody command: JourneyBusArrivalCommand) = run {
        val actor = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val loginGeneration = actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        ApiResponse.success(service.observe(actor.id, scheduleId, loginGeneration, sessionId, command))
    }
}
