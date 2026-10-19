package com.noLate.journey.automatic

import com.noLate.global.error.BusinessException
import com.noLate.journey.domain.AutomaticJourneyState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class AutomaticJourneyValidationTest {
    @Test fun `disabled validation performs no authority reads`() {
        val f = AutomaticJourneyFixture()
        assertThatThrownBy { f.service(enabled = false).validate(7, 9001, 2, f.sessionId, f.stopCommand()) }
            .isInstanceOf(BusinessException::class.java)
        org.mockito.kotlin.verifyNoInteractions(f.members, f.plans, f.repository)
    }
    @Test fun `ACTIVE lease requires current account and still expires at original session deadline`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand()); service.departure(7, 9001, 2, f.sessionId, f.event())
        assertThat(service.validate(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.ACTIVE)
        clock.now = clock.now.plusSeconds(600)
        assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        assertThat(f.departureCount.get()).isEqualTo(1)
    }
    @Test fun `validation lease is bounded and does not activate departure`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        val lease = service.validate(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThat(lease.state).isEqualTo(AutomaticJourneyState.ARMED)
        assertThat(lease.validUntil).isEqualTo(clock.now.plusSeconds(30))
        assertThat(f.departureCount.get()).isZero(); assertThat(f.cancelCount.get()).isZero()
        clock.now = clock.now.plusSeconds(590)
        assertThat(service.validate(7, 9001, 2, f.sessionId, f.stopCommand()).validUntil).isEqualTo(lease.validatedAt.plusSeconds(600))
        clock.now = clock.now.plusSeconds(10)
        assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
    }
    @Test fun `route replacement revokes lease even with same route revision`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        f.plan = f.plan.copy(route = f.mapper.readTree("""{"transitLegs":[{"kind":"WALK","durationMinutes":4},{"kind":"BUS","lineName":"changed","stationCount":2}]}"""))
        assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
    }
    @Test fun `account access owner digest generation and stopped state revoke lease`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        for (command in listOf(f.stopCommand().copy(generation = 2), f.stopCommand().copy(routeRevision = 2),
            f.stopCommand().copy(routeInputDigest = "b".repeat(64)), f.stopCommand().copy(clientOwnerId = f.sessionId))) {
            assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, command) }.isInstanceOf(BusinessException::class.java)
        }
        f.actor.sessionGeneration = 3
        assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        f.actor.sessionGeneration = 2; f.accessAllowed = false
        assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        f.accessAllowed = true; service.stop(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThatThrownBy { service.validate(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
    }
    @Test fun `controller validates auth and emits fenced lease contract`() {
        val f = AutomaticJourneyFixture(); f.service().prepare(7, 9001, 2, f.prepareCommand())
        val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/validate"
        val body = f.mapper.writeValueAsBytes(f.stopCommand())
        assertThat(automaticJourneyFixtureMvc(f, false).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().response.status).isEqualTo(401)
        val response = automaticJourneyFixtureMvc(f).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertThat(response.status).isEqualTo(200)
        val data = f.mapper.readTree(response.contentAsByteArray).path("data")
        assertThat(data.path("sessionId").asText()).isEqualTo(f.sessionId)
        assertThat(data.path("clientOwnerId").asText()).isEqualTo(f.owner)
        assertThat(data.path("validUntil").isTextual).isTrue()
        assertThat(data.has("latitude")).isFalse()
    }
}
