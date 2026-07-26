package com.noLate.global.observability

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        "observability.prometheus.public-enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoints.web.base-path=/manage",
        "management.endpoints.web.path-mapping.prometheus=scrape",
        "management.endpoints.web.path-mapping.health=prometheus",
        "management.prometheus.metrics.export.enabled=true",
    ]
)
@AutoConfigureMockMvc
class RemappedActuatorSecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val endpointAccessPolicy: ObservabilityEndpointAccessPolicy,
) {

    @Test
    fun `only the remapped Prometheus endpoint identity is publicly readable`() {
        mockMvc.get("/manage/scrape")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    @WithMockUser
    fun `remapped health and non-GET Prometheus requests remain denied`() {
        assertEquals("/manage/**", endpointAccessPolicy.managementEndpointNamespacePattern)
        mockMvc.get("/manage/prometheus")
            .andExpect {
                status { isForbidden() }
            }
        mockMvc.post("/manage/scrape")
            .andExpect {
                status { isForbidden() }
            }
        mockMvc.options("/manage/scrape")
            .andExpect {
                status { isForbidden() }
            }
        mockMvc.get("/manage/scrape/")
            .andExpect {
                status { isForbidden() }
            }
    }
}
