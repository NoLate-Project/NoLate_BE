package com.noLate.journey.automatic

import com.noLate.global.security.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class AutomaticJourneyAuthenticationTest {
    private val jwt = JourneyFixtureAuthentication.jwt
    @Test fun `signed access identity reaches actual controller while missing malformed refresh foreign and wrong issuer do not`() {
        val foreign = JwtTokenProvider("unrelated-test-signature-key-not-for-production-20261005", 600, 600, "journey-isolated-auth-fixture")
        val wrongIssuer = JwtTokenProvider("isolated-journey-fixture-key-not-for-production-20261005", 600, 600, "unrelated-issuer")
        val cases = listOf(null, "Bearer invalid", "Bearer ${jwt.createRefreshToken(7, "fixture", 2)}",
            "Bearer ${jwt.createAccessToken(8, "fixture", 2)}", "Bearer ${foreign.createAccessToken(7, "fixture", 2)}",
            "Bearer ${wrongIssuer.createAccessToken(7, "fixture", 2)}")
        for (header in cases) {
            val f = AutomaticJourneyFixture()
            val mvc = automaticJourneyFixtureMvc(f, principalForRequest = { JourneyFixtureAuthentication.principal(it.getHeader("Authorization")) })
            val request = post("/api/schedules/9001/automatic-journey/prepare").contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.prepareCommand()))
            if (header != null) request.header("Authorization", header)
            assertThat(mvc.perform(request).andReturn().response.status).isEqualTo(401)
            assertThat(f.rows).isEmpty(); assertThat(f.departureCount.get()).isZero()
        }
        val f = AutomaticJourneyFixture()
        val mvc = automaticJourneyFixtureMvc(f, principalForRequest = { JourneyFixtureAuthentication.principal(it.getHeader("Authorization")) })
        val response = mvc.perform(post("/api/schedules/9001/automatic-journey/prepare").header("Authorization", "Bearer ${jwt.createAccessToken(7, "fixture", 2)}")
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.prepareCommand()))).andReturn().response
        assertThat(response.status).isEqualTo(200); assertThat(f.rows).hasSize(1); assertThat(f.departureCount.get()).isZero()
    }
    @Test fun `valid signature cannot cross current login generation`() {
        val f = AutomaticJourneyFixture()
        val mvc = automaticJourneyFixtureMvc(f, principalForRequest = { JourneyFixtureAuthentication.principal(it.getHeader("Authorization")) })
        val response = mvc.perform(post("/api/schedules/9001/automatic-journey/prepare").header("Authorization", "Bearer ${jwt.createAccessToken(7, "fixture", 3)}")
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.prepareCommand()))).andReturn().response
        assertThat(response.status).isEqualTo(401); assertThat(f.rows).isEmpty()
    }
    @Test fun `new account generation zero is a valid signed identity`() {
        val f = AutomaticJourneyFixture(); f.actor.sessionGeneration = 0
        val mvc = automaticJourneyFixtureMvc(f, principalForRequest = { JourneyFixtureAuthentication.principal(it.getHeader("Authorization")) })
        val response = mvc.perform(post("/api/schedules/9001/automatic-journey/prepare").header("Authorization", "Bearer ${jwt.createAccessToken(7, "fixture", 0)}")
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.prepareCommand()))).andReturn().response
        assertThat(response.status).isEqualTo(200); assertThat(f.rows).hasSize(1)
    }
}
