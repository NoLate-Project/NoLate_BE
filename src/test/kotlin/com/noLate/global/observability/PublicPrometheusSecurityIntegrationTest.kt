package com.noLate.global.observability

import com.noLate.global.health.HealthEndpointPaths
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "observability.prometheus.public-enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "management.metrics.tags.application=noLate-test",
    ]
)
@AutoConfigureMockMvc
class PublicPrometheusSecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val applicationContext: ApplicationContext,
    private val endpointAccessPolicy: ObservabilityEndpointAccessPolicy,
) {

    @Test
    fun `exact GET scrape path is public and exports the bounded release metrics`() {
        val prometheusBeans = applicationContext.beanDefinitionNames
            .filter { it.contains("prometheus", ignoreCase = true) }
        kotlin.test.assertTrue(
            prometheusBeans.any { it.contains("scrape", ignoreCase = true) },
            prometheusBeans.toString(),
        )
        // Register an HTTP observation before scraping; the allowlist must still keep framework
        // exception/URI tags and every non-NoLate meter out of the public payload.
        mockMvc.get(HealthEndpointPaths.LIVENESS)
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
        val scrape = mockMvc.get(ObservabilityEndpointAccessPolicy.PROMETHEUS_PATH)
            .andExpect {
                status { isOk() }
                content { string(containsString("nolate_push_delivery_claims_total")) }
                content { string(containsString("nolate_eta_jobs_due")) }
                content {
                    string(containsString("nolate_push_provider_duration_seconds_count"))
                }
                content {
                    string(containsString("nolate_eta_provider_duration_seconds_count"))
                }
                content {
                    string(containsString("nolate_eta_jobs_oldest_delay_seconds"))
                }
                content {
                    string(containsString("nolate_push_outbox_oldest_delay_seconds"))
                }
                content {
                    string(containsString("nolate_push_deliveries_ambiguous"))
                }
                content { string(containsString("application=\"noLate-test\"")) }
            }
            .andReturn()
            .response
            .contentAsString

        assertFalse(scrape.contains("http_server_requests"))
        assertFalse(scrape.contains("exception="))
        assertFalse(scrape.contains("uri="))
        scrape.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { sample ->
                assertTrue(sample.startsWith("nolate_"), sample)
            }
    }

    @Test
    @WithMockUser
    fun `opt in does not expose methods subpaths or other actuator endpoints`() {
        assertEquals("/actuator/**", endpointAccessPolicy.managementEndpointNamespacePattern)
        mockMvc.post(ObservabilityEndpointAccessPolicy.PROMETHEUS_PATH)
            .andExpect {
                status { isForbidden() }
            }
        mockMvc.options(ObservabilityEndpointAccessPolicy.PROMETHEUS_PATH)
            .andExpect {
                status { isForbidden() }
            }
        listOf(
            "${ObservabilityEndpointAccessPolicy.PROMETHEUS_PATH}/",
            "/actuator/health",
            "/actuator/metrics",
        ).forEach { path ->
            mockMvc.get(path)
                .andExpect {
                    status { isForbidden() }
                }
        }
    }
}
