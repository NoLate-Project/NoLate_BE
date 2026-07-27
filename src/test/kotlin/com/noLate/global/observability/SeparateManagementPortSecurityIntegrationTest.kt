package com.noLate.global.observability

import com.noLate.global.security.JwtTokenProvider
import com.noLate.global.health.HealthEndpointPaths
import com.noLate.global.health.HealthStatus
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "management.endpoints.web.base-path=/",
        "observability.prometheus.public-enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoint.health.access=unrestricted",
        "management.prometheus.metrics.export.enabled=true",
    ]
)
class SeparateManagementPortSecurityIntegrationTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val memberRepository: MemberRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @LocalManagementPort
    private var managementPort: Int = 0

    @LocalServerPort
    private var applicationPort: Int = 0

    @Test
    fun `separate management port keeps endpoint-aware allow and default deny`() {
        val managementBaseUrl = "http://127.0.0.1:$managementPort"
        val applicationBaseUrl = "http://127.0.0.1:$applicationPort"
        val key = UUID.randomUUID().toString()
        val member = memberRepository.saveAndFlush(
            Member(
                name = "management-port-member",
                password = "Password1!",
                email = "$key@nolate.test",
                loginType = LoginType.COMMON,
                sessionGeneration = 1,
            )
        )
        val accessToken = jwtTokenProvider.createAccessToken(
            memberId = requireNotNull(member.id),
            memberName = requireNotNull(member.name),
            sessionGeneration = member.sessionGeneration,
        )
        val memberRequest = HttpEntity<Void>(
            HttpHeaders().apply {
                setBearerAuth(accessToken)
            }
        )

        assertEquals(
            200,
            restTemplate.getForEntity("$managementBaseUrl/prometheus", String::class.java)
                .statusCode
                .value(),
        )
        assertEquals(
            401,
            restTemplate.exchange(
                "$managementBaseUrl/prometheus",
                HttpMethod.OPTIONS,
                null,
                String::class.java,
            ).statusCode.value(),
        )
        assertEquals(
            401,
            restTemplate.getForEntity("$managementBaseUrl/prometheus/", String::class.java)
                .statusCode
                .value(),
        )

        listOf(
            HealthEndpointPaths.ROOT,
            HealthEndpointPaths.LIVENESS,
            HealthEndpointPaths.READINESS,
        ).forEach { path ->
            assertEquals(
                401,
                restTemplate.getForEntity("$managementBaseUrl$path", String::class.java)
                    .statusCode
                    .value(),
            )
            assertEquals(
                403,
                restTemplate.exchange(
                    "$managementBaseUrl$path",
                    HttpMethod.GET,
                    memberRequest,
                    String::class.java,
                ).statusCode.value(),
            )
            listOf(HttpEntity<Void>(HttpHeaders()), memberRequest).forEach { request ->
                val response = restTemplate.exchange(
                    "$applicationBaseUrl$path",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
                assertEquals(200, response.statusCode.value())
                assertEquals("no-store", response.headers.cacheControl)
                assertEquals("""{"status":"${HealthStatus.UP.name}"}""", response.body)
            }
        }
    }
}
