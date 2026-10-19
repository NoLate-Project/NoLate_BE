package com.noLate.journey.automatic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

class AutomaticJourneyControllerTest {
    @Test fun `actual controller accepts fixture JSON and returns flat API data ISO timestamps`() {
        val f = AutomaticJourneyFixture(); val mvc = automaticJourneyFixtureMvc(f)
        val base = "/api/schedules/9001/automatic-journey"
        val prepare = mvc.perform(post("$base/prepare").contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.prepareCommand()))).andReturn().response
        assertThat(prepare.status).isEqualTo(200)
        val prepared = f.mapper.readTree(prepare.contentAsByteArray).path("data")
        assertThat(prepared.path("scheduleId").asText()).isEqualTo("9001")
        assertThat(prepared.path("state").asText()).isEqualTo("ARMED")
        assertThat(prepared.path("preparedAt").isTextual).isTrue()
        assertThat(f.departureCount.get()).isZero()
        repeat(2) { index ->
            val result = mvc.perform(post("$base/${f.sessionId}/departure").contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.event()))).andReturn().response
            assertThat(result.status).isEqualTo(200)
            val ack = f.mapper.readTree(result.contentAsByteArray).path("data")
            assertThat(ack.path("accepted").asBoolean()).isTrue()
            assertThat(ack.path("duplicate").asBoolean()).isEqualTo(index == 1)
            assertThat(ack.path("departedAt").isTextual).isTrue()
        }
        assertThat(f.departureCount.get()).isEqualTo(1)
    }

    @Test fun `no principal and malformed execution are rejected`() {
        val f = AutomaticJourneyFixture()
        val response = automaticJourneyFixtureMvc(f, false).perform(post("/api/schedules/9001/automatic-journey/prepare")
            .contentType(MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(f.prepareCommand()))).andReturn().response
        assertThat(response.status).isEqualTo(401)
        val malformed = automaticJourneyFixtureMvc(f).perform(post("/api/schedules/9001/automatic-journey/prepare")
            .contentType(MediaType.APPLICATION_JSON).content("""{"requestId":"${f.sessionId}","clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","execution":"FAKE"}""")).andReturn().response
        assertThat(malformed.status).isEqualTo(400)
        assertThat(f.prepareCount.get()).isZero()
    }
}
