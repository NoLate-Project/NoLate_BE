package com.noLate.global.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilityEndpointAccessPolicyTest {

    @Test
    fun `only exact lowercase true enables the endpoint-aware scrape policy`() {
        val enabled = ObservabilityEndpointAccessPolicy("true")

        assertTrue(enabled.prometheusPublicEnabled)
        assertTrue(enabled.managementEndpointNamespacePattern == "/actuator/**")
    }

    @Test
    fun `missing false and malformed values fail closed`() {
        listOf("", "false", "TRUE", " true", "1", "yes").forEach { raw ->
            val policy = ObservabilityEndpointAccessPolicy(raw)

            assertFalse(policy.prometheusPublicEnabled, raw)
        }
    }
}
