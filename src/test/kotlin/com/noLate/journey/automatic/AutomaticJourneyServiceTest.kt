package com.noLate.journey.automatic

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant

class AutomaticJourneyServiceTest {
    @Test fun `all flags default closed before touching data`() {
        val f = AutomaticJourneyFixture()
        listOf(
            f.service(enabled = false), f.service(guidanceEnabled = false), f.service(simulatorEnabled = false),
            f.service(isolated = false), f.service(profiles = "prod"), f.service(profiles = "test,prod"), f.service(profiles = ""),
        ).forEach { service ->
            assertThatThrownBy { service.prepare(7, 9001, 2, f.prepareCommand()) }
                .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.FEATURE_DISABLED) }
        }
        verifyNoInteractions(f.repository, f.members, f.plans, f.departure, f.jobs, f.schedules, f.manual)
    }

    @Test fun `device execution has a separate default off gate`() {
        val f = AutomaticJourneyFixture()
        assertThatThrownBy { f.service().prepare(7, 9001, 2, f.prepareCommand().copy(execution = AutomaticJourneyExecution.DEVICE)) }
            .isInstanceOf(BusinessException::class.java)
        verifyNoInteractions(f.repository, f.departure, f.jobs)
    }

    @Test fun `prepare is bounded idempotent and never marks departed or cancels alerts`() {
        val f = AutomaticJourneyFixture()
        val service = f.service()
        val first = service.prepare(7, 9001, 2, f.prepareCommand())
        val duplicate = service.prepare(7, 9001, 2, f.prepareCommand())
        assertThat(duplicate).isEqualTo(first)
        assertThat(first.state).isEqualTo(AutomaticJourneyState.ARMED)
        assertThat(first.sequence).isZero()
        assertThat(first.expiresAt.epochSecond - first.preparedAt.epochSecond).isEqualTo(600)
        assertThat(f.prepareCount.get()).isEqualTo(1)
        verifyNoInteractions(f.departure, f.jobs, f.schedules)
        verify(f.plans, never()).disableNotification(any(), any())
    }

    @Test fun `only auto departure activates once and response loss retry is a receipt replay`() {
        val f = AutomaticJourneyFixture()
        val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        val first = service.departure(7, 9001, 2, f.sessionId, f.event())
        val retry = service.departure(7, 9001, 2, f.sessionId, f.event())
        assertThat(first.accepted).isTrue(); assertThat(first.duplicate).isFalse()
        assertThat(retry.duplicate).isTrue(); assertThat(retry.departedAt).isEqualTo(first.departedAt)
        assertThat(f.rows[f.sessionId]?.state).isEqualTo(AutomaticJourneyState.ACTIVE)
        assertThat(f.departureCount.get()).isEqualTo(1); assertThat(f.activationCount.get()).isEqualTo(1)
        verify(f.plans, times(1)).disableNotification(7, 9001)
        verify(f.jobs, times(1)).cancelByScheduleIdAndMemberId(9001, 7, "DEPARTED", false)
        verify(f.schedules, times(1)).markDeparted(7, 9001)
        verify(f.manual, never()).saveAndFlush(any())
    }

    @Test fun `accepted receipt survives expiry and stop without resurrecting current state`() {
        val clock = AutomaticJourneyMutableClock()
        val f = AutomaticJourneyFixture(clock); val service = f.service(); val event = f.event()
        service.prepare(7, 9001, 2, f.prepareCommand())
        service.departure(7, 9001, 2, f.sessionId, event)
        service.stop(7, 9001, 2, f.sessionId, f.stopCommand())
        clock.now = clock.now.plusSeconds(3600)
        assertThat(service.departure(7, 9001, 2, f.sessionId, event).duplicate).isTrue()
        assertThat(f.rows[f.sessionId]?.state).isEqualTo(AutomaticJourneyState.STOPPED)
        assertThat(f.departureCount.get()).isEqualTo(1)
    }

    @Test fun `changed same event payload is rejected`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand()); service.departure(7, 9001, 2, f.sessionId, f.event())
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event().copy(occurredAt = f.clock.instant().plusMillis(1))) }
            .isInstanceOf(BusinessException::class.java)
        assertThat(f.departureCount.get()).isEqualTo(1)
    }

    @Test fun `owner route generation revision sequence execution and event type must match`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        val command = f.event()
        listOf(command.copy(clientOwnerId = "cc67598a-6c84-4d0d-8d25-134e0c01c12e"), command.copy(routeInputDigest = "b".repeat(64)),
            command.copy(generation = 2), command.copy(routeRevision = 2), command.copy(sequence = 0), command.copy(sequence = 2),
            command.copy(eventKey = "wrong"), command.copy(type = "MANUAL"), command.copy(execution = AutomaticJourneyExecution.DEVICE)
        ).forEach { invalid -> assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, invalid) }.isInstanceOf(BusinessException::class.java) }
        assertThat(f.departureCount.get()).isZero(); assertThat(f.activationCount.get()).isZero()
    }

    @Test fun `malformed IDs or digests fail before member or plan reads`() {
        val f = AutomaticJourneyFixture()
        listOf(f.prepareCommand().copy(requestId = "1-1-1-1-1"), f.prepareCommand().copy(clientOwnerId = "bad"),
            f.prepareCommand().copy(routeInputDigest = "A".repeat(64)), f.prepareCommand().copy(routeInputDigest = "a".repeat(63))
        ).forEach { invalid -> assertThatThrownBy { f.service().prepare(7, 9001, 2, invalid) }.isInstanceOf(BusinessException::class.java) }
        verifyNoInteractions(f.members, f.plans, f.repository)
    }

    @Test fun `events before preparation after expiry in future or stopped cannot activate`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        listOf(clock.now.minusMillis(1), clock.now.plusSeconds(31)).forEach { bad ->
            assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event(bad)) }.isInstanceOf(BusinessException::class.java)
        }
        clock.now = clock.now.plusSeconds(599)
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event(clock.now.plusSeconds(1))) }
            .isInstanceOf(BusinessException::class.java)
        clock.now = clock.now.plusSeconds(1)
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(BusinessException::class.java)
        service.stop(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(BusinessException::class.java)
        assertThat(f.departureCount.get()).isZero()
    }

    @Test fun `offline departure delayed 121 through 599 seconds activates within preparation TTL`() {
        listOf(121L, 180L, 599L).forEach { delay ->
            val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
            service.prepare(7, 9001, 2, f.prepareCommand())
            val savedEvent = f.event()
            clock.now = clock.now.plusSeconds(delay)
            val accepted = service.departure(7, 9001, 2, f.sessionId, savedEvent)
            assertThat(accepted.accepted).describedAs("delivery delay %s seconds", delay).isTrue()
            assertThat(accepted.duplicate).isFalse()
            assertThat(f.rows[f.sessionId]?.state).isEqualTo(AutomaticJourneyState.ACTIVE)
            assertThat(f.departureCount.get()).isEqualTo(1)
            assertThat(f.activationCount.get()).isEqualTo(1)
            assertThat(f.cancelCount.get()).isEqualTo(1)
        }
    }

    @Test fun `unaccepted saved departure at or after server TTL cannot activate`() {
        listOf(600L, 601L, 3600L).forEach { delay ->
            val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
            service.prepare(7, 9001, 2, f.prepareCommand())
            val savedEvent = f.event()
            clock.now = clock.now.plusSeconds(delay)
            assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, savedEvent) }
                .isInstanceOfSatisfying(BusinessException::class.java) { assertThat(it.errorCode).isEqualTo(ErrorCode.INVALID_STATE) }
            assertThat(f.rows[f.sessionId]?.acceptedEventKey).isNull()
            assertThat(f.departureCount.get()).isZero()
            assertThat(f.activationCount.get()).isZero()
            assertThat(f.cancelCount.get()).isZero()
        }
    }

    @Test fun `late accepted departure exact receipt remains replayable after server TTL`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        val savedEvent = f.event()
        clock.now = clock.now.plusSeconds(599)
        val accepted = service.departure(7, 9001, 2, f.sessionId, savedEvent)
        clock.now = clock.now.plusSeconds(2)
        val duplicate = service.departure(7, 9001, 2, f.sessionId, savedEvent)
        assertThat(duplicate.duplicate).isTrue()
        assertThat(duplicate.departedAt).isEqualTo(accepted.departedAt)
        assertThat(f.departureCount.get()).isEqualTo(1)
        assertThat(f.activationCount.get()).isEqualTo(1)
        assertThat(f.cancelCount.get()).isEqualTo(1)
    }

    @Test fun `route edit access revocation and logout reject new activation and even receipt access`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        val original = f.plan
        f.plan = f.plan.copy(route = f.mapper.readTree("""{"transitLegs":[{"kind":"BUS","lineName":"changed"}]}"""))
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(BusinessException::class.java)
        f.plan = original; service.departure(7, 9001, 2, f.sessionId, f.event())
        f.accessAllowed = false
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(BusinessException::class.java)
        f.accessAllowed = true; f.actor.sessionGeneration = 3
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(BusinessException::class.java)
        assertThat(f.departureCount.get()).isEqualTo(1)
    }

    @Test fun `stop and current have no departure effects and do not reveal another client owner`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        assertThat(service.current(7, 9001, 2, "cc67598a-6c84-4d0d-8d25-134e0c01c12e")).isNull()
        assertThat(service.stop(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.STOPPED)
        assertThat(service.stop(7, 9001, 2, f.sessionId, f.stopCommand()).state).isEqualTo(AutomaticJourneyState.STOPPED)
        verifyNoInteractions(f.departure, f.jobs, f.schedules)
    }

    @Test fun `new login can replace the old preparation but cannot accept its late event`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand()); f.actor.sessionGeneration = 3
        val next = service.prepare(7, 9001, 3, f.prepareCommand().copy(requestId = "dd67598a-6c84-4d0d-8d25-134e0c01c12e"))
        assertThat(next.generation).isEqualTo(2)
        assertThat(f.rows[f.sessionId]?.state).isEqualTo(AutomaticJourneyState.STOPPED)
        assertThatThrownBy { service.departure(7, 9001, 3, f.sessionId, f.event()) }.isInstanceOf(BusinessException::class.java)
    }

    @Test fun `effect failure does not create an accepted receipt`() {
        val f = AutomaticJourneyFixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        doThrow(IllegalStateException("fixture failure")).whenever(f.plans).disableNotification(7, 9001)
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, f.event()) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(f.rows[f.sessionId]?.acceptedEventKey).isNull()
        assertThat(f.rows[f.sessionId]?.state).isEqualTo(AutomaticJourneyState.ARMED)
    }

    @Test fun `clock rollback blocks unaccepted activation`() {
        val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand()); val event = f.event(); clock.now = clock.now.minusSeconds(1)
        assertThatThrownBy { service.departure(7, 9001, 2, f.sessionId, event) }.isInstanceOf(BusinessException::class.java)
        assertThat(f.departureCount.get()).isZero()
    }
}
