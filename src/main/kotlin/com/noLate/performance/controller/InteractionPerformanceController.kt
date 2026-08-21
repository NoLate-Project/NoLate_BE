package com.noLate.performance.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.performance.application.InteractionPerformanceBatchResult
import com.noLate.performance.application.InteractionPerformanceSample
import com.noLate.performance.application.InteractionPerformanceService
import com.noLate.performance.domain.InteractionPerformanceKind
import com.noLate.performance.domain.InteractionPerformanceOutcome
import com.noLate.performance.domain.NavigationPerformancePlatform
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/performance/interaction-events")
class InteractionPerformanceController(
    private val service: InteractionPerformanceService,
) {
    @PostMapping
    fun record(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @Valid @RequestBody request: InteractionPerformanceBatchRequest,
    ): ApiResponse<InteractionPerformanceBatchResult> = ApiResponse.success(
        service.recordBatch(
            memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
            samples = request.events.map { it.toSample() },
        )
    )
}

data class InteractionPerformanceBatchRequest(
    @field:Size(min = 1, max = 50)
    @field:Valid
    val events: List<InteractionPerformanceEventRequest>,
)

data class InteractionPerformanceEventRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )
    val eventId: String,

    @field:NotBlank
    @field:Size(max = 200)
    val route: String,

    @field:NotBlank
    @field:Pattern(regexp = "^[a-z][a-z0-9._-]{0,79}$")
    val operation: String,

    val kind: InteractionPerformanceKind,
    val outcome: InteractionPerformanceOutcome,

    @field:Min(0)
    @field:Max(120_000)
    val durationMs: Int,

    val platform: NavigationPerformancePlatform,

    @field:Size(max = 32)
    val appVersion: String? = null,

    @field:Size(max = 32)
    val buildVersion: String? = null,

    val occurredAt: Instant,
) {
    fun toSample() = InteractionPerformanceSample(
        eventId = eventId,
        route = route,
        operation = operation,
        kind = kind,
        outcome = outcome,
        durationMs = durationMs,
        platform = platform,
        appVersion = appVersion,
        buildVersion = buildVersion,
        occurredAt = occurredAt,
    )
}
