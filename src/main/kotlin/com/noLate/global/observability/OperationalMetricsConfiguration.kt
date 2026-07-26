package com.noLate.global.observability

import io.micrometer.core.instrument.config.MeterFilter
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

    @Bean
    fun operationalMetricsAllowlist(): MeterFilter =
        MeterFilter.denyUnless { meter ->
            meter.name.startsWith(OPERATIONAL_METER_PREFIX)
        }

    companion object {
        const val OPERATIONAL_METER_PREFIX = "nolate."
    }
}
