package com.noLate.journey.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.JourneyGuidanceService
import com.noLate.journey.application.JourneySessionDto
import com.noLate.journey.application.JourneyStartCommand
import com.noLate.journey.domain.JourneyPhase
import com.noLate.journey.domain.JourneyProgressCommand
import com.noLate.journey.domain.JourneyTerminalCommand
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/schedules/{scheduleId}/journey")
class JourneyGuidanceController(private val service: JourneyGuidanceService) {
    @PostMapping("/start")
    fun start(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @RequestBody command: JourneyStartCommand): ApiResponse<JourneySessionDto> =
        authenticated(principal) { id, generation -> ApiResponse.success(service.start(id, scheduleId, generation, command)) }

    @GetMapping
    fun current(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long): ApiResponse<JourneySessionDto?> =
        authenticated(principal) { id, generation -> ApiResponse.success(service.current(id, scheduleId, generation)) }

    @PutMapping("/{sessionId}/progress")
    fun progress(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: JourneyProgressCommand): ApiResponse<JourneySessionDto> =
        authenticated(principal) { id, generation -> ApiResponse.success(service.progress(id, scheduleId, generation, sessionId, command)) }

    @PostMapping("/{sessionId}/arrive")
    fun arrive(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: JourneyTerminalCommand): ApiResponse<JourneySessionDto> =
        authenticated(principal) { id, generation -> ApiResponse.success(service.finish(id, scheduleId, generation, sessionId, command, JourneyPhase.ARRIVED)) }

    @PostMapping("/{sessionId}/stop")
    fun stop(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: JourneyTerminalCommand): ApiResponse<JourneySessionDto> =
        authenticated(principal) { id, generation -> ApiResponse.success(service.finish(id, scheduleId, generation, sessionId, command, JourneyPhase.STOPPED)) }

    private fun <T> authenticated(principal: MemberPrincipal?, action: (Long, Long) -> T): T {
        val actor = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val generation = actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        return action(actor.id, generation)
    }
}
