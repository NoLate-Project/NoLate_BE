package com.noLate.notification.liveactivity.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.notification.liveactivity.application.LiveActivityTokenService
import com.noLate.notification.liveactivity.application.NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE
import com.noLate.notification.liveactivity.application.NOLATE_LIVE_ACTIVITY_SCHEMA_VERSION
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications/live-activities")
@Tag(name = "Live Activity", description = "iOS Live Activity token lifecycle API")
class LiveActivityController(
    private val tokenService: LiveActivityTokenService,
) {
    @Operation(summary = "ActivityKit push-to-start 토큰 등록/회전")
    @PutMapping("/start-token")
    fun registerStartToken(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: RegisterLiveActivityStartTokenRequest,
    ): ApiResponse<Unit> {
        val authenticated = requirePrincipal(principal)
        tokenService.registerStartToken(
            memberId = authenticated.id,
            deviceId = request.deviceId,
            activityType = request.activityType,
            pushToStartToken = request.pushToStartToken,
            schemaVersion = request.schemaVersion,
            presentedSessionGeneration = requireSessionGeneration(authenticated),
            appearance = request.appearance,
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "ActivityKit push-to-start 토큰 폐기")
    @DeleteMapping("/start-token")
    fun retireStartToken(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestParam deviceId: String,
        @RequestParam(defaultValue = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE)
        activityType: String,
    ): ApiResponse<Unit> {
        val authenticated = requirePrincipal(principal)
        tokenService.retireStartToken(
            authenticated.id,
            deviceId,
            activityType,
            requireSessionGeneration(authenticated),
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "ActivityKit activity update 토큰 등록/회전")
    @PutMapping("/{activityId}/update-token")
    fun registerUpdateToken(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable activityId: String,
        @RequestBody request: RegisterLiveActivityUpdateTokenRequest,
    ): ApiResponse<Unit> {
        val authenticated = requirePrincipal(principal)
        tokenService.registerUpdateToken(
            memberId = authenticated.id,
            deviceId = request.deviceId,
            scheduleId = request.scheduleId,
            activityId = activityId,
            updateToken = request.updateToken,
            generation = request.generation,
            schemaVersion = request.schemaVersion,
            presentedSessionGeneration = requireSessionGeneration(authenticated),
        )
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "클라이언트 Live Activity 종료 상태 동기화")
    @DeleteMapping("/{activityId}")
    fun reportEnded(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable activityId: String,
        @RequestParam deviceId: String,
        @RequestParam scheduleId: Long,
    ): ApiResponse<Unit> {
        val authenticated = requirePrincipal(principal)
        tokenService.reportEnded(
            authenticated.id,
            deviceId,
            scheduleId,
            activityId,
            requireSessionGeneration(authenticated),
        )
        return ApiResponse.success(Unit)
    }

    private fun requirePrincipal(principal: MemberPrincipal?): MemberPrincipal =
        principal ?: throw BusinessException(ErrorCode.UNAUTHORIZED)

    private fun requireSessionGeneration(principal: MemberPrincipal): Long =
        principal.accessTokenSessionGeneration ?: throw BusinessException(ErrorCode.INVALID_TOKEN)
}

data class RegisterLiveActivityStartTokenRequest(
    val deviceId: String,
    val activityType: String = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
    val pushToStartToken: String,
    val schemaVersion: Int = NOLATE_LIVE_ACTIVITY_SCHEMA_VERSION,
    val appearance: String? = null,
) {
    override fun toString(): String =
        "RegisterLiveActivityStartTokenRequest(activityType=$activityType, " +
            "schemaVersion=$schemaVersion, deviceIdPresent=${deviceId.isNotEmpty()}, " +
            "appearancePresent=${appearance != null})"
}

data class RegisterLiveActivityUpdateTokenRequest(
    val deviceId: String,
    val scheduleId: Long,
    val updateToken: String,
    val generation: Long,
    val schemaVersion: Int = NOLATE_LIVE_ACTIVITY_SCHEMA_VERSION,
) {
    override fun toString(): String =
        "RegisterLiveActivityUpdateTokenRequest(scheduleId=$scheduleId, generation=$generation, " +
            "schemaVersion=$schemaVersion, deviceIdPresent=${deviceId.isNotEmpty()})"
}
