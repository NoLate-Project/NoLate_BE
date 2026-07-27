package com.noLate.global.health

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val applicationContext: ConfigurableApplicationContext,
) {
    @AfterEach
    fun restoreAvailability() {
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.CORRECT)
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC)
    }

    @Test
    fun `health endpoints are publicly accessible and expose only availability`() {
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.CORRECT)
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC)

        listOf(
            HealthEndpointPaths.ROOT,
            HealthEndpointPaths.LIVENESS,
            HealthEndpointPaths.READINESS,
        ).forEach { path ->
            mockMvc.get(path)
                .andExpect {
                    status { isOk() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                    header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                    jsonPath("$.status") { value(HealthStatus.UP.name) }
                    jsonPath("$.errorCode") { doesNotExist() }
                    jsonPath("$.components") { doesNotExist() }
                    jsonPath("$.details") { doesNotExist() }
                }
        }
    }

    @Test
    fun `health endpoints do not inherit the global public OPTIONS rule`() {
        listOf(
            HealthEndpointPaths.ROOT,
            HealthEndpointPaths.LIVENESS,
            HealthEndpointPaths.READINESS,
        ).forEach { path ->
            mockMvc.options(path)
                .andExpect {
                    status { isUnauthorized() }
                }
        }
    }

    @Test
    fun `readiness refusal maps to opaque 503 without changing liveness`() {
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.CORRECT)
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)

        mockMvc.get(HealthEndpointPaths.READINESS)
            .andExpect {
                status { isServiceUnavailable() }
                header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                jsonPath("$.status") { value(HealthStatus.OUT_OF_SERVICE.name) }
                jsonPath("$.errorCode") { doesNotExist() }
                jsonPath("$.errorMessage") { doesNotExist() }
            }

        mockMvc.get(HealthEndpointPaths.ROOT)
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value(HealthStatus.UP.name) }
            }
    }

    @Test
    fun `broken liveness maps to opaque 503 instead of generic C000`() {
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.BROKEN)

        listOf(HealthEndpointPaths.ROOT, HealthEndpointPaths.LIVENESS).forEach { path ->
            mockMvc.get(path)
                .andExpect {
                    status { isServiceUnavailable() }
                    header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                    jsonPath("$.status") { value(HealthStatus.DOWN.name) }
                    jsonPath("$.errorCode") { doesNotExist() }
                    jsonPath("$.errorMessage") { doesNotExist() }
                }
        }
    }
}
