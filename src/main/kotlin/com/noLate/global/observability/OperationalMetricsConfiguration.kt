package com.noLate.global.observability

import com.noLate.global.health.HealthEndpointPaths
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.boot.actuate.endpoint.EndpointFilter
import org.springframework.boot.actuate.endpoint.EndpointId
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The opt-in Prometheus endpoint is intentionally a narrow operational contract.
 *
 * Spring Boot otherwise registers JVM, HTTP, repository, and integration meters. Some of those
 * contain evolving dimensions such as exception or URI tags. Denying every meter outside the
 * application-owned namespace keeps a public scrape bounded even when dependencies add new meters.
 */
@Configuration
class OperationalMetricsConfiguration {

    /**
     * NoLate owns an opaque `/health*` deployment-probe contract. Keeping Actuator health out of
     * web discovery is a code-level invariant so an exposure include or a root management base path
     * cannot register a second handler on those URLs. JMX access is unaffected.
     */
    @Bean
    fun actuatorHealthWebEndpointDenyFilter(): EndpointFilter<ExposableWebEndpoint> =
        EndpointFilter { endpoint ->
            endpoint.endpointId != ACTUATOR_HEALTH_ENDPOINT_ID
        }

    /**
     * Other exposed management endpoints must not be remapped onto NoLate's reserved probe URLs.
     * The health endpoint itself is filtered above; this guard turns an operator-created collision
     * (including a discovery base path such as `/health`) into an explicit startup failure.
     */
    @Bean
    fun customHealthPathCollisionGuard(
        pathMappedEndpoints: PathMappedEndpoints,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val reservedPaths = setOf(
                HealthEndpointPaths.ROOT,
                HealthEndpointPaths.LIVENESS,
                HealthEndpointPaths.READINESS,
            )
            val collisions =
                (pathMappedEndpoints.allPaths + pathMappedEndpoints.basePath)
                    .map(::normalizedEndpointPath)
                    .filterTo(sortedSetOf()) { it in reservedPaths }
            check(collisions.isEmpty()) {
                "Management endpoint paths collide with reserved NoLate health probes: " +
                    collisions.joinToString()
            }
        }

    @Bean
    fun operationalMetricsAllowlist(): MeterFilter =
        MeterFilter.denyUnless { meter ->
            meter.name.startsWith(OPERATIONAL_METER_PREFIX)
        }

    companion object {
        private val ACTUATOR_HEALTH_ENDPOINT_ID = EndpointId.of("health")
        const val OPERATIONAL_METER_PREFIX = "nolate."
    }
}

private fun normalizedEndpointPath(path: String): String =
    "/${path.trim().trim('/')}"
