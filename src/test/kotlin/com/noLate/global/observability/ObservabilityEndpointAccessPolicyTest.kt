package com.noLate.global.observability

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilityEndpointAccessPolicyTest {

    @Test
    fun `only exact lowercase true enables the exact GET scrape path`() {
        val enabled = ObservabilityEndpointAccessPolicy("true")

        assertTrue(enabled.prometheusPublicEnabled)
        assertTrue(enabled.publicPrometheusRequestMatcher.matches(request("GET", "/actuator/prometheus")))
        assertTrue(
            enabled.publicPrometheusRequestMatcher.matches(
                request("GET", "/nolate/actuator/prometheus", "/nolate"),
            )
        )
        assertFalse(enabled.publicPrometheusRequestMatcher.matches(request("POST", "/actuator/prometheus")))
        assertFalse(enabled.publicPrometheusRequestMatcher.matches(request("GET", "/actuator/prometheus/")))
        assertFalse(enabled.publicPrometheusRequestMatcher.matches(request("GET", "/actuator/health")))
    }

    @Test
    fun `missing false and malformed values fail closed`() {
        listOf("", "false", "TRUE", " true", "1", "yes").forEach { raw ->
            val policy = ObservabilityEndpointAccessPolicy(raw)

            assertFalse(policy.prometheusPublicEnabled, raw)
            assertFalse(
                policy.publicPrometheusRequestMatcher.matches(
                    request("GET", "/actuator/prometheus"),
                ),
                raw,
            )
        }
    }

    private fun request(
        method: String,
        requestUri: String,
        contextPath: String = "",
    ) = MockHttpServletRequest(method, requestUri).apply {
        this.contextPath = contextPath
    }
}
