package com.noLate.performance.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.performance.application.NavigationPerformanceBatchResult
import com.noLate.performance.application.NavigationPerformanceSample
import com.noLate.performance.application.NavigationPerformanceService
import com.noLate.performance.domain.NavigationPerformanceCompletionKind
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
@RequestMapping("/api/performance/navigation-events")
class NavigationPerformanceController(
    private val service: NavigationPerformanceService,
) {
    @PostMapping
    fun record(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @Valid @RequestBody request: NavigationPerformanceBatchRequest,
    ): ApiResponse<NavigationPerformanceBatchResult> = ApiResponse.success(
        service.recordBatch(
            memberId = principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED),
            samples = request.events.map { it.toSample() },
        )
    )
}

data class NavigationPerformanceBatchRequest(
    @field:Size(min = 1, max = 50)
    @field:Valid
    val events: List<NavigationPerformanceEventRequest>,
)

data class NavigationPerformanceEventRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )
    val eventId: String,

    @field:NotBlank
    @field:Size(max = 200)
    val fromRoute: String,

    @field:NotBlank
    @field:Size(max = 200)
    val toRoute: String,

    @field:NotBlank
    @field:Size(max = 30)
    val action: String,

    @field:Min(0)
    @field:Max(120_000)
    val routeReadyMs: Int,

    @field:Min(0)
    @field:Max(120_000)
    val totalMs: Int,

    val completionKind: NavigationPerformanceCompletionKind,
    val platform: NavigationPerformancePlatform,

    @field:Size(max = 32)
    val appVersion: String? = null,

    @field:Size(max = 32)
    val buildVersion: String? = null,

    val occurredAt: Instant,
) {
    fun toSample() = NavigationPerformanceSample(
        eventId = eventId,
        fromRoute = fromRoute,
        toRoute = toRoute,
        action = action,
        routeReadyMs = routeReadyMs,
        totalMs = totalMs,
        completionKind = completionKind,
        platform = platform,
        appVersion = appVersion,
        buildVersion = buildVersion,
        occurredAt = occurredAt,
    )
}
