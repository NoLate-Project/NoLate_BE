package com.noLate.journey.automatic

import com.noLate.global.error.BusinessException
import com.noLate.journey.domain.AutomaticJourneyState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class AutomaticJourneyReconciliationTest {
    @Test fun `disabled read performs no authority queries`() {
        val f = AutomaticJourneyFixture()
        assertThatThrownBy { f.service(enabled = false).reconcile(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        verifyNoInteractions(f.members, f.plans, f.repository)
    }
    @Test fun `state read never activates stops extends or expires an existing session`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val s = f.service()
        val prepared = s.prepare(7, 9001, 2, f.prepareCommand())
        assertThat(s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.ARMED)
        s.departure(7, 9001, 2, f.sessionId, f.event())
        clock.now = clock.now.plusSeconds(601)
        val read = s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThat(read.state).isEqualTo(AutomaticJourneyState.ACTIVE); assertThat(read.endedAt).isNull()
        assertThat(f.departureCount.get()).isEqualTo(1)
        assertThat(s.current(7, 9001, 2, f.owner)!!.expiresAt).isEqualTo(prepared.expiresAt)
    }
    @Test fun `terminal receipt is exact owned metadata without observation lease or tokens`() {
        val f = AutomaticJourneyFixture(); val s = f.service()
        s.prepare(7, 9001, 2, f.prepareCommand()); s.stop(7, 9001, 2, f.sessionId, f.stopCommand())
        val receipt = s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThat(receipt.state).isEqualTo(AutomaticJourneyState.STOPPED)
        assertThat(receipt.endedAt).isNotNull(); assertThat(receipt.sessionId).isEqualTo(f.sessionId)
        val json = f.mapper.writeValueAsString(receipt)
        assertThat(json).doesNotContain("validUntil", "accessToken", "latitude", "liveActivityToken")
        assertThat(f.departureCount.get()).isZero(); assertThat(f.cancelCount.get()).isZero()
    }
    @Test fun `owner generation revision digest account membership and login remain fenced`() {
        val f = AutomaticJourneyFixture(); val s = f.service(); s.prepare(7, 9001, 2, f.prepareCommand())
        for (c in listOf(f.stopCommand().copy(generation = 2), f.stopCommand().copy(routeRevision = 2),
            f.stopCommand().copy(clientOwnerId = f.sessionId), f.stopCommand().copy(routeInputDigest = "b".repeat(64)))) {
            assertThatThrownBy { s.reconcile(7, 9001, 2, f.sessionId, c) }.isInstanceOf(BusinessException::class.java)
        }
        assertThatThrownBy { s.reconcile(8, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        assertThatThrownBy { s.reconcile(7, 9002, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        f.actor.sessionGeneration = 3
        assertThatThrownBy { s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
        f.actor.sessionGeneration = 2; f.accessAllowed = false
        assertThatThrownBy { s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand()) }.isInstanceOf(BusinessException::class.java)
    }
    @Test fun `expired ARMED receipt requires authoritative persisted expiry not client clock`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val s = f.service()
        s.prepare(7, 9001, 2, f.prepareCommand()); clock.now = clock.now.plusSeconds(600)
        assertThat(s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.ARMED)
        s.current(7, 9001, 2, f.owner)
        assertThat(s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.EXPIRED)
    }
    @Test fun `controller requires authenticated state-only request`() {
        val f = AutomaticJourneyFixture(); f.service().prepare(7, 9001, 2, f.prepareCommand())
        val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/reconcile"
        val body = f.mapper.writeValueAsBytes(f.stopCommand())
        assertThat(automaticJourneyFixtureMvc(f, false).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status).isEqualTo(401)
        val result = automaticJourneyFixtureMvc(f).perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
        assertThat(result.status).isEqualTo(200)
        val data = f.mapper.readTree(result.contentAsByteArray).path("data")
        assertThat(data.path("state").asText()).isEqualTo("ARMED"); assertThat(data.has("validUntil")).isFalse()
    }
}
