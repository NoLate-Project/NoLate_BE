package com.noLate.journey.automatic

import com.noLate.journey.application.AutomaticJourneyExpiry
import com.noLate.journey.domain.AutomaticJourneyState
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class AutomaticJourneyExpiryTest {
    @Test fun `all three switches must opt in and defaults perform no writes`() {
        val f = AutomaticJourneyFixture()
        AutomaticJourneyExpiry(f.repository, f.clock).expireElapsedSessions()
        listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false)).forEach { (g, a, e) ->
            assertThat(AutomaticJourneyExpiry(f.repository, f.clock, g, a, e).expireElapsedSessions()).isZero()
        }
        verify(f.repository, never()).expireElapsed(any(), any(), any())
    }

    @Test fun `active TTL expires without client and retains accepted departure receipt`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val s = f.service()
        s.prepare(7, 9001, 2, f.prepareCommand()); s.departure(7, 9001, 2, f.sessionId, f.event())
        val worker = AutomaticJourneyExpiry(f.repository, clock, true, true, true)
        clock.now = clock.now.plusSeconds(599)
        assertThat(worker.expireElapsedSessions()).isZero()
        clock.now = clock.now.plusSeconds(1)
        assertThat(worker.expireElapsedSessions()).isEqualTo(1)
        val expired = f.rows.getValue(f.sessionId)
        assertThat(expired.state).isEqualTo(AutomaticJourneyState.EXPIRED)
        assertThat(expired.endedAt).isEqualTo(clock.now)
        assertThat(expired.sequence).isEqualTo(1)
        assertThat(expired.acceptedEventKey).isNotNull()
        assertThat(worker.expireElapsedSessions()).isZero()
        assertThat(s.reconcile(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.EXPIRED)
        assertThat(s.departure(7, 9001, 2, f.sessionId, f.event(expired.acceptedOccurredAt!!)).duplicate).isTrue()
        assertThat(expired.state).isEqualTo(AutomaticJourneyState.EXPIRED)
        assertThat(f.departureCount.get()).isEqualTo(1)
        assertThat(f.cancelCount.get()).isEqualTo(1)
    }

    @Test fun `armed expires without generating departure or changing stopped terminal time`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val s = f.service()
        s.prepare(7, 9001, 2, f.prepareCommand())
        clock.now = clock.now.plusSeconds(600)
        assertThat(AutomaticJourneyExpiry(f.repository, clock, true, true, true).expireElapsedSessions()).isEqualTo(1)
        assertThat(f.departureCount.get()).isZero()
        assertThatThrownBy { s.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(Exception::class.java)
        s.stop(7, 9001, 2, f.sessionId, f.stopCommand())
        val ended = f.rows.getValue(f.sessionId).endedAt
        clock.now = clock.now.plusSeconds(600)
        assertThat(AutomaticJourneyExpiry(f.repository, clock, true, true, true).expireElapsedSessions()).isZero()
        assertThat(f.rows.getValue(f.sessionId).endedAt).isEqualTo(ended)
    }

    @Test fun `clock rollback leaves live records unchanged`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock)
        f.service().prepare(7, 9001, 2, f.prepareCommand()); clock.now = clock.now.minusSeconds(100)
        assertThat(AutomaticJourneyExpiry(f.repository, clock, true, true, true).expireElapsedSessions()).isZero()
        assertThat(f.rows.getValue(f.sessionId).state).isEqualTo(AutomaticJourneyState.ARMED)
    }

    @Test fun `short TTL is isolated simulator only and bounded`() {
        val f = AutomaticJourneyFixture(isolatedTtlSeconds = 60)
        val simulator = f.service().prepare(7, 9001, 2, f.prepareCommand())
        assertThat(simulator.expiresAt.epochSecond - simulator.preparedAt.epochSecond).isEqualTo(60)
        val device = AutomaticJourneyFixture(isolatedTtlSeconds = 60)
        val real = device.service(deviceEnabled = true, isolated = false, profiles = "prod")
            .prepare(7, 9001, 2, device.prepareCommand().copy(execution = com.noLate.journey.domain.AutomaticJourneyExecution.DEVICE))
        assertThat(real.expiresAt.epochSecond - real.preparedAt.epochSecond).isEqualTo(600)
        listOf(59L, 601L).forEach { ttl ->
            val bad = AutomaticJourneyFixture(isolatedTtlSeconds = ttl)
            assertThatThrownBy { bad.service().prepare(7, 9001, 2, bad.prepareCommand()) }.isInstanceOf(Exception::class.java)
            assertThat(bad.rows).isEmpty()
        }
        val prod = AutomaticJourneyFixture(isolatedTtlSeconds = 60)
        assertThatThrownBy { prod.service(profiles = "prod,test").prepare(7, 9001, 2, prod.prepareCommand()) }.isInstanceOf(Exception::class.java)
    }
}
