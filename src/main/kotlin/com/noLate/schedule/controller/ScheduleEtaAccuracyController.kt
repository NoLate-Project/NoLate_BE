package com.noLate.schedule.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleEtaAccuracyObservationDto
import com.noLate.schedule.application.service.ScheduleEtaObservationEngagementDto
import com.noLate.schedule.application.service.ScheduleEtaObservationEngagementEvent
import com.noLate.schedule.application.service.ScheduleEtaAccuracyService
import com.noLate.schedule.domain.ScheduleArrivalObservationSource
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ScheduleArrivalObservationRequest(
    /** Client-observed actual arrival; USER_ADJUSTED contains the corrected time. */
    val arrivedAt: Instant,
    /** USER_NOW, USER_ADJUSTED, or GEOFENCE. Missing/unknown values fail closed. */
    val observationSource: ScheduleArrivalObservationSource,
    /** Temporal uncertainty of the observation. This is not GPS precision. */
    val precisionSeconds: Int,
    /** Required only for USER_ADJUSTED; positive, bounded, whole-minute correction. */
    val adjustmentSeconds: Int? = null,
    /** Optional release provenance. Invalid/unbounded values are discarded, never metric tags. */
    val clientAppVersion: String? = null,
    val clientBuildVersion: String? = null,
)

data class ScheduleEtaObservationEngagementRequest(
    val event: ScheduleEtaObservationEngagementEvent,
    /** Optional bounded release/UX cohort used only for denominator reconstruction. */
    val clientAppVersion: String? = null,
    val clientBuildVersion: String? = null,
    val uxVariant: String? = null,
)

@RestController
@RequestMapping("/api/schedules/{scheduleId}/eta-observations")
@Tag(name = "Schedule ETA accuracy", description = "동의 기반 실제 도착 ETA 오차 측정 API")
class ScheduleEtaAccuracyController(
    private val etaAccuracyService: ScheduleEtaAccuracyService,
) {
    @Operation(summary = "도착 기록 UI 노출 또는 확인창 진입 기록")
    @PostMapping("/engagement")
    fun recordEngagement(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @RequestBody request: ScheduleEtaObservationEngagementRequest,
    ): ApiResponse<ScheduleEtaObservationEngagementDto> =
        ApiResponse.success(
            etaAccuracyService.recordEngagement(
                memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
                scheduleId = scheduleId,
                event = request.event,
                clientAppVersion = request.clientAppVersion,
                clientBuildVersion = request.clientBuildVersion,
                uxVariant = request.uxVariant,
            )
        )

    @Operation(summary = "클라이언트가 관측한 실제 도착시각 기록")
    @PostMapping("/arrival")
    fun recordArrival(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable scheduleId: Long,
        @RequestBody request: ScheduleArrivalObservationRequest,
    ): ApiResponse<ScheduleEtaAccuracyObservationDto> =
        ApiResponse.success(
            etaAccuracyService.recordArrival(
                memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
                scheduleId = scheduleId,
                arrivedAt = request.arrivedAt,
                observationSource = request.observationSource,
                precisionSeconds = request.precisionSeconds,
                adjustmentSeconds = request.adjustmentSeconds,
                clientAppVersion = request.clientAppVersion,
                clientBuildVersion = request.clientBuildVersion,
            )
        )
}
