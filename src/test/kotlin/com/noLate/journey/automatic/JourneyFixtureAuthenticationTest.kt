package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/** Fixed synthetic credentials only. No user identity, refresh endpoint or external I/O. */
class JourneyFixtureAuthenticationTest {
    @Test fun `ordinary and explicitly extended test issuers retain their exact signed lifetimes`() {
        for ((issuer, lifetime) in listOf(JourneyFixtureAuthentication.jwt to 600L,
            JourneyFixtureAuthentication.extendedJwt to 9000L)) {
            val token = issuer.createAccessToken(7, "Synthetic fixture", 2)
            val claims = jacksonObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split('.')[1]))
            assertThat(claims.path("exp").asLong()-claims.path("iat").asLong()).isEqualTo(lifetime)
            assertThat(JourneyFixtureAuthentication.principal("Bearer $token")?.id).isEqualTo(7L)
            assertThat(JourneyFixtureAuthentication.principal("Bearer $token")?.accessTokenSessionGeneration).isEqualTo(2L)
            assertThat(JourneyFixtureAuthentication.principal("Bearer ${issuer.createAccessToken(8,"Synthetic fixture",2)}")).isNull()
        }
    }
}
