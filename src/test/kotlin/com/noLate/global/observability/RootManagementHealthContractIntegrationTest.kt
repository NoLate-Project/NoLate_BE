package com.noLate.global.observability

import com.noLate.global.health.HealthController
import com.noLate.global.health.HealthEndpointPaths
import com.noLate.global.health.HealthStatus
import com.noLate.global.security.JwtTokenProvider
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.endpoint.EndpointId
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        "management.endpoints.web.base-path=/",
        // Deliberately tries to override the normal deployment configuration. The code-level
        // filter must still keep Actuator health out of web discovery.
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoint.health.access=unrestricted",
    ]
)
@AutoConfigureMockMvc
class RootManagementHealthContractIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val applicationContext: ConfigurableApplicationContext,
    private val webEndpointDiscoverer: WebEndpointDiscoverer,
    private val environment: Environment,
    private val memberRepository: MemberRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @AfterEach
    fun restoreAvailability() {
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.CORRECT)
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC)
    }

    @Test
    fun `root management base keeps anonymous and JWT probes on the custom handler`() {
        assertEquals("/", environment.getProperty("management.endpoints.web.base-path"))
        assertActuatorHealthIsNotWebDiscovered(webEndpointDiscoverer)
        AvailabilityChangeEvent.publish(applicationContext, LivenessState.CORRECT)
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC)
        val accessToken = createMemberAccessToken(memberRepository, jwtTokenProvider)

        mapOf(
            HealthEndpointPaths.ROOT to "health",
            HealthEndpointPaths.LIVENESS to "liveness",
            HealthEndpointPaths.READINESS to "readiness",
        ).forEach { (path, methodName) ->
            assertCustomHealthResponse(mockMvc, path, methodName)
            assertCustomHealthResponse(mockMvc, path, methodName, accessToken)
        }
    }

    @Test
    fun `root management base preserves opaque readiness 503 semantics`() {
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)

        mockMvc.get(HealthEndpointPaths.READINESS)
            .andExpect {
                status { isServiceUnavailable() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                jsonPath("$.status") { value(HealthStatus.OUT_OF_SERVICE.name) }
                jsonPath("$.components") { doesNotExist() }
                jsonPath("$.details") { doesNotExist() }
                match(handler().handlerType(HealthController::class.java))
                match(handler().methodName("readiness"))
            }
    }
}

@SpringBootTest(
    properties = [
        "management.endpoints.web.base-path=",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoint.health.access=unrestricted",
    ]
)
@AutoConfigureMockMvc
class EmptyManagementHealthContractIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val webEndpointDiscoverer: WebEndpointDiscoverer,
    private val environment: Environment,
    private val memberRepository: MemberRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @Test
    fun `empty management base keeps anonymous and JWT probes on the custom handler`() {
        assertEquals("", environment.getProperty("management.endpoints.web.base-path"))
        assertActuatorHealthIsNotWebDiscovered(webEndpointDiscoverer)
        val accessToken = createMemberAccessToken(memberRepository, jwtTokenProvider)

        mapOf(
            HealthEndpointPaths.ROOT to "health",
            HealthEndpointPaths.LIVENESS to "liveness",
            HealthEndpointPaths.READINESS to "readiness",
        ).forEach { (path, methodName) ->
            assertCustomHealthResponse(mockMvc, path, methodName)
            assertCustomHealthResponse(mockMvc, path, methodName, accessToken)
        }
    }
}

private fun assertActuatorHealthIsNotWebDiscovered(
    webEndpointDiscoverer: WebEndpointDiscoverer,
) {
    assertEquals(
        0,
        webEndpointDiscoverer.endpoints.count {
            it.endpointId == EndpointId.of("health")
        },
    )
}

private fun createMemberAccessToken(
    memberRepository: MemberRepository,
    jwtTokenProvider: JwtTokenProvider,
): String {
    val key = UUID.randomUUID().toString()
    val member = memberRepository.saveAndFlush(
        Member(
            name = "root-health-member",
            password = "Password1!",
            email = "$key@nolate.test",
            loginType = LoginType.COMMON,
            sessionGeneration = 1,
        )
    )
    return jwtTokenProvider.createAccessToken(
        memberId = requireNotNull(member.id),
        memberName = requireNotNull(member.name),
        sessionGeneration = member.sessionGeneration,
    )
}

private fun assertCustomHealthResponse(
    mockMvc: MockMvc,
    path: String,
    methodName: String,
    accessToken: String? = null,
) {
    mockMvc.get(path) {
        accessToken?.let {
            header(HttpHeaders.AUTHORIZATION, "Bearer $it")
        }
    }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
        jsonPath("$.status") { value(HealthStatus.UP.name) }
        jsonPath("$.errorCode") { doesNotExist() }
        jsonPath("$.components") { doesNotExist() }
        jsonPath("$.details") { doesNotExist() }
        match(handler().handlerType(HealthController::class.java))
        match(handler().methodName(methodName))
    }
}
