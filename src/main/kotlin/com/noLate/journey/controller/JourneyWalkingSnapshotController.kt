package com.noLate.journey.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.journey.application.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class WalkingSnapshotIssueCommand(val expectedRevision: Long)
@RestController
@ConditionalOnProperty(prefix = "journey.walking", name = ["snapshots-enabled"], havingValue = "true")
@RequestMapping("/api/schedules/{scheduleId}/walking-snapshot")
class JourneyWalkingSnapshotController(private val service: SavedJourneyWalkingSnapshotService) {
    @GetMapping("/context") fun context(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long) =
        owned(principal) { id, login -> response(service.context(id, scheduleId, login)) }
    @PostMapping fun issue(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long,
        @RequestBody command: WalkingSnapshotIssueCommand) = owned(principal) { id, login ->
        response(service.issue(id, scheduleId, login, command.expectedRevision))
    }
    @GetMapping fun current(@AuthenticationPrincipal principal: MemberPrincipal?, @PathVariable scheduleId: Long) =
        owned(principal) { id, login -> response(service.current(id, scheduleId, login)) }
    @DeleteMapping("/{snapshotId}") fun cancel(@AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long, @PathVariable snapshotId: String) = owned(principal) { id, login ->
        service.cancel(id, scheduleId, login, snapshotId); response(mapOf("cancelled" to true))
    }
    @ExceptionHandler(WalkingContractRejected::class)
    fun rejected(error: WalkingContractRejected): ResponseEntity<ApiResponse<Nothing>> = ResponseEntity
        .status(when(error.reason) { "OWNER_CHANGED" -> 403; "SNAPSHOT_NOT_FOUND" -> 404;
            "PROVIDER_BUSY", "ROUTE_BUSY" -> 429; "FEATURE_DISABLED" -> 503; else -> 409 })
        .cacheControl(CacheControl.noStore()).body(ApiResponse.failure("도보 경로를 사용할 수 없습니다.", error.reason))
    private fun <T> response(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value))
    private fun <T> owned(principal: MemberPrincipal?, action: (Long, Long) -> T): T {
        val actor = principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return action(actor.id, actor.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN))
    }
}
