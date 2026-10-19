package com.noLate.journey.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.AutomaticJourneyService
import com.noLate.journey.domain.AutomaticJourneyDepartureCommand
import com.noLate.journey.domain.AutomaticJourneyPrepareCommand
import com.noLate.journey.domain.AutomaticJourneySavedPrepareCommand
import com.noLate.journey.domain.AutomaticJourneyStopCommand
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/schedules/{scheduleId}/automatic-journey")
class AutomaticJourneyController(private val service: AutomaticJourneyService) {
    @ExceptionHandler(com.noLate.journey.application.WalkingContractRejected::class)
    fun walkingRejected(error: com.noLate.journey.application.WalkingContractRejected) = org.springframework.http.ResponseEntity
        .status(if (error.reason == "FEATURE_DISABLED") 503 else 409)
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(ApiResponse.failure<Nothing>("도보 경로를 다시 확인해 주세요.", error.reason))
    @PostMapping("/prepare-saved")
    fun prepareSaved(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @RequestBody command: AutomaticJourneySavedPrepareCommand) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.prepareSaved(id, scheduleId, generation, command)) }
    @PostMapping("/prepare")
    fun prepare(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @RequestBody command: AutomaticJourneyPrepareCommand) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.prepare(id, scheduleId, generation, command)) }

    @PostMapping("/{sessionId}/departure")
    fun departure(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: AutomaticJourneyDepartureCommand) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.departure(id, scheduleId, generation, sessionId, command)) }

    @PostMapping("/{sessionId}/stop")
    fun stop(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: AutomaticJourneyStopCommand) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.stop(id, scheduleId, generation, sessionId, command)) }

    @PostMapping("/{sessionId}/validate")
    fun validate(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: AutomaticJourneyStopCommand) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.validate(id, scheduleId, generation, sessionId, command)) }

    @GetMapping
    fun current(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @RequestParam clientOwnerId: String) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.current(id, scheduleId, generation, clientOwnerId)) }

    @PostMapping("/{sessionId}/reconcile")
    fun reconcile(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long, @PathVariable sessionId: String, @RequestBody command: AutomaticJourneyStopCommand) =
        authenticated(principal) { id, generation -> ApiResponse.success(service.reconcile(id, scheduleId, generation, sessionId, command)) }

    private fun <T> authenticated(principal: MemberPrincipal?, action: (Long, Long) -> T): T {
        val actor = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        val generation = actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
        return action(actor.id, generation)
    }
}
