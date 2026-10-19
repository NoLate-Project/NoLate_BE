package com.noLate.journey.controller

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.JourneyActivityDeliveryService
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/schedules/{scheduleId}/automatic-journey/{sessionId}/live-activity")
class JourneyActivityController(private val delivery: JourneyActivityDeliveryService) {
    @PostMapping
    fun update(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @PathVariable sessionId: String, @RequestBody command: JsonNode) = authenticated(principal) { id, login ->
        delivery.update(id, scheduleId, login, sessionId, command)
    }
    @PostMapping("/end")
    fun end(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @PathVariable sessionId: String, @RequestBody command: AutomaticJourneyStopCommand) = authenticated(principal) { id, login ->
        delivery.end(id, scheduleId, login, sessionId, command)
    }
    private fun authenticated(principal: MemberPrincipal?, action: (Long, Long) -> Map<String, Any>): ApiResponse<Map<String, Any>> {
        val actor = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val login = actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        return try { ApiResponse.success(action(actor.id, login)) }
        catch (failure: BusinessException) { throw failure }
        catch (_: Exception) { throw BusinessException(ErrorCode.INVALID_STATE, "길안내 Activity 요청을 처리할 수 없습니다.") }
    }
}
