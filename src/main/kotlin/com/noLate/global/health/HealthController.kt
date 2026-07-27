package com.noLate.global.health

import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(
    private val applicationAvailability: ApplicationAvailability,
) {
    @GetMapping(HealthEndpointPaths.ROOT, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun health(): ResponseEntity<HealthResponse> = liveness()

    @GetMapping(HealthEndpointPaths.LIVENESS, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun liveness(): ResponseEntity<HealthResponse> =
        availabilityResponse(
            available = applicationAvailability.livenessState == LivenessState.CORRECT,
            unavailableStatus = HealthStatus.DOWN,
        )

    @GetMapping(HealthEndpointPaths.READINESS, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun readiness(): ResponseEntity<HealthResponse> =
        availabilityResponse(
            available = applicationAvailability.readinessState == ReadinessState.ACCEPTING_TRAFFIC,
            unavailableStatus = HealthStatus.OUT_OF_SERVICE,
        )

    private fun availabilityResponse(
        available: Boolean,
        unavailableStatus: HealthStatus,
    ): ResponseEntity<HealthResponse> {
        // Probes need only process availability; dependency names and failure details stay internal.
        val response = HealthResponse(
            status = if (available) HealthStatus.UP else unavailableStatus,
        )
        val responseBuilder = if (available) {
            ResponseEntity.ok()
        } else {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        }
        return responseBuilder
            .cacheControl(CacheControl.noStore())
            .body(response)
    }
}

data class HealthResponse(
    val status: HealthStatus,
)

enum class HealthStatus {
    UP,
    DOWN,
    OUT_OF_SERVICE,
}

object HealthEndpointPaths {
    const val ROOT = "/health"
    const val LIVENESS = "/health/liveness"
    const val READINESS = "/health/readiness"

    private val paths = setOf(ROOT, LIVENESS, READINESS)

    fun contains(path: String): Boolean = path in paths
}
