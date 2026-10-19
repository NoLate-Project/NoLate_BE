package com.noLate.journey.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.JourneySubwayObservationService
import com.noLate.journey.application.SubwayObservationCheckRejected
import com.noLate.journey.domain.JourneySubwayObservationCommand
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@ConditionalOnProperty(prefix="journey.subway-observation",name=["enabled"],havingValue="true")
@RequestMapping("/api/schedules/{scheduleId}/automatic-journey/{sessionId}/subway-observation")
internal class JourneySubwayObservationController(private val service: JourneySubwayObservationService) {
    @PostMapping("/input") fun input(@AuthenticationPrincipal principal: MemberPrincipal?,@PathVariable scheduleId: Long,
        @PathVariable sessionId: String,@RequestBody command: JourneySubwayObservationCommand): ApiResponse<Map<String,Any?>> {
        val actor=principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val login=actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        return try { ApiResponse.success(service.input(actor.id,scheduleId,login,sessionId,command)) }
        catch (_: SubwayObservationCheckRejected) { throw BusinessException(ErrorCode.INVALID_STATE) }
    }
    @PostMapping fun observe(@AuthenticationPrincipal principal: MemberPrincipal?,@PathVariable scheduleId: Long,
        @PathVariable sessionId: String,@RequestBody command: JourneySubwayObservationCommand): ApiResponse<Map<String,Any?>> {
        val actor=principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val login=actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        return try { ApiResponse.success(service.observe(actor.id,scheduleId,login,sessionId,command)) }
        catch (_: SubwayObservationCheckRejected) { throw BusinessException(ErrorCode.INVALID_STATE) }
    }
}
