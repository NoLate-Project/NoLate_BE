package com.noLate.global.observability

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties = [
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.metrics.export.enabled=true",
    ]
)
@AutoConfigureMockMvc
class ActuatorSecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {

    @Test
    fun `prometheus scrape is closed without an explicit opt in`() {
        mockMvc.get(ObservabilityEndpointAccessPolicy.PROMETHEUS_PATH)
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    @WithMockUser
    fun `member authentication cannot unlock actuator surfaces`() {
        listOf(
            ObservabilityEndpointAccessPolicy.PROMETHEUS_PATH,
            "/actuator/health",
            "/actuator/env",
        ).forEach { path ->
            mockMvc.get(path)
                .andExpect {
                    status { isForbidden() }
                }
        }
    }
}
