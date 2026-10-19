package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.journey.application.AutomaticJourneyDepartureAck
import com.noLate.journey.application.AutomaticJourneyService
import com.noLate.journey.domain.*
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.*
import com.noLate.schedule.domain.*
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:auto-journey-only;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomaticJourneyPersistenceTest @Autowired constructor(
    private val rows: AutomaticJourneyPreparationRepository,
    private val members: MemberRepository,
    private val departed: ScheduleDepartureStatusRepository,
    private val manager: PlatformTransactionManager,
) {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val now = Instant.parse("2026-10-04T03:00:00Z")

    @Test fun `concurrent same event commits one activation and one departure row`() {
        val fixture = fixture()
        val ready = CountDownLatch(6); val start = CountDownLatch(1); val done = CountDownLatch(6)
        val failures = ConcurrentLinkedQueue<Throwable>(); val acks = ConcurrentLinkedQueue<AutomaticJourneyDepartureAck>()
        val workers = Executors.newFixedThreadPool(6)
        repeat(6) {
            workers.submit {
                ready.countDown(); start.await()
                try { acks.add(tx { fixture.service.departure(fixture.memberId, 9001, 2, fixture.sessionId, fixture.command) }) }
                catch (error: Throwable) { failures.add(error) }
                finally { done.countDown() }
            }
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown()
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue(); workers.shutdownNow()
        assertThat(failures).isEmpty()
        assertThat(acks.count { !it.duplicate }).isEqualTo(1)
        assertThat(acks.count { it.duplicate }).isEqualTo(5)
        assertThat(tx { rows.findBySessionId(fixture.sessionId)!!.state }).isEqualTo(AutomaticJourneyState.ACTIVE)
        assertThat(tx { departed.findByScheduleIdAndMemberIdAndDeletedFalse(9001, fixture.memberId)!!.departedAt }).isEqualTo(now)
        verify(fixture.jobs, times(1)).cancelByScheduleIdAndMemberId(9001, fixture.memberId, "DEPARTED", false)
    }

    @Test fun `effect failure rolls back departure and receipt and retry can commit once`() {
        val fixture = fixture()
        doThrow(IllegalStateException("synthetic rollback")).whenever(fixture.jobs)
            .cancelByScheduleIdAndMemberId(9001, fixture.memberId, "DEPARTED", false)
        assertThatThrownBy { tx { fixture.service.departure(fixture.memberId, 9001, 2, fixture.sessionId, fixture.command) } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(tx { departed.findByScheduleIdAndMemberIdAndDeletedFalse(9001, fixture.memberId) }).isNull()
        val armed = tx { rows.findBySessionId(fixture.sessionId)!! }
        assertThat(armed.state).isEqualTo(AutomaticJourneyState.ARMED)
        assertThat(armed.acceptedEventKey).isNull(); assertThat(armed.sequence).isZero()
        doNothing().whenever(fixture.jobs).cancelByScheduleIdAndMemberId(9001, fixture.memberId, "DEPARTED", false)
        assertThat(tx { fixture.service.departure(fixture.memberId, 9001, 2, fixture.sessionId, fixture.command) }.duplicate).isFalse()
        assertThat(tx { fixture.service.departure(fixture.memberId, 9001, 2, fixture.sessionId, fixture.command) }.duplicate).isTrue()
    }

    @Test fun `repository supports privacy cleanup by member schedule and bounded retention`() {
        val fixture = fixture()
        assertThat(tx { rows.findBySessionId(fixture.sessionId) }).isNotNull()
        tx { rows.deleteAllByMemberId(fixture.memberId) }
        assertThat(tx { rows.findBySessionId(fixture.sessionId) }).isNull()
        val second = fixture()
        tx { rows.deleteAllByRetentionExpiresAtBefore(now.plusSeconds(8 * 24 * 60 * 60)) }
        assertThat(tx { rows.findBySessionId(second.sessionId) }).isNull()
    }

    private data class Fixture(val service: AutomaticJourneyService, val jobs: SchedulePushJobService,
        val memberId: Long, val sessionId: String, val command: AutomaticJourneyDepartureCommand)

    @Test fun `real expiry query keeps receipt and survives worker reconstruction`() {
        val f = fixture()
        tx { f.service.departure(f.memberId, 9001, 2, f.sessionId, f.command) }
        val before = tx { rows.findBySessionId(f.sessionId)!! }
        val atExpiry = Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC)
        val worker = com.noLate.journey.application.AutomaticJourneyExpiry(rows, atExpiry, true, true, true)
        assertThat(tx { worker.expireElapsedSessions() }).isGreaterThanOrEqualTo(1)
        val expired = tx { rows.findBySessionId(f.sessionId)!! }
        assertThat(expired.state).isEqualTo(AutomaticJourneyState.EXPIRED)
        assertThat(expired.endedAt).isEqualTo(now.plusSeconds(600))
        assertThat(expired.version).isEqualTo(before.version + 1)
        assertThat(expired.acceptedEventKey).isEqualTo(before.acceptedEventKey)
        assertThat(tx { com.noLate.journey.application.AutomaticJourneyExpiry(rows, atExpiry, true, true, true).expireElapsedSessions() }).isZero()
        assertThat(tx { f.service.departure(f.memberId, 9001, 2, f.sessionId, f.command) }.duplicate).isTrue()
        assertThat(tx { rows.findBySessionId(f.sessionId)!!.state }).isEqualTo(AutomaticJourneyState.EXPIRED)
    }

    @Test fun `expiry boundary terminal protection and stale writer fence use real DB`() {
        val f = fixture()
        val stale = tx { rows.findBySessionId(f.sessionId)!! }
        val live = listOf(AutomaticJourneyState.ARMED, AutomaticJourneyState.ACTIVE)
        tx { rows.expireElapsed(now.plusSeconds(599), live, AutomaticJourneyState.EXPIRED) }
        assertThat(tx { rows.findBySessionId(f.sessionId)!!.state }).isEqualTo(AutomaticJourneyState.ARMED)
        tx { rows.expireElapsed(now.plusSeconds(600), live, AutomaticJourneyState.EXPIRED) }
        stale.state = AutomaticJourneyState.ACTIVE
        assertThatThrownBy { tx { rows.saveAndFlush(stale) } }.isInstanceOf(org.springframework.dao.OptimisticLockingFailureException::class.java)
        val ended = tx { rows.findBySessionId(f.sessionId)!!.endedAt }
        tx { rows.expireElapsed(now.plusSeconds(1200), live, AutomaticJourneyState.EXPIRED) }
        assertThat(tx { rows.findBySessionId(f.sessionId)!!.endedAt }).isEqualTo(ended)
        val stopped = fixture()
        tx { val row = rows.findBySessionId(stopped.sessionId)!!; row.stop(now); rows.saveAndFlush(row) }
        tx { rows.expireElapsed(now.plusSeconds(1200), live, AutomaticJourneyState.EXPIRED) }
        val terminal = tx { rows.findBySessionId(stopped.sessionId)!! }
        assertThat(terminal.state).isEqualTo(AutomaticJourneyState.STOPPED)
        assertThat(terminal.endedAt).isEqualTo(now)
    }

    private fun fixture(): Fixture {
        val memberId = tx { members.saveAndFlush(Member(name = "Fixture", email = "${UUID.randomUUID()}@example.invalid", sessionGeneration = 2)).id!! }
        val plans = mock<ScheduleTravelPlanService>()
        val departure = mock<ScheduleDepartureStatusService>()
        val jobs = mock<SchedulePushJobService>(); val schedules = mock<ScheduleService>()
        val manual = mock<JourneyGuidanceSessionRepository>()
        val plan = ScheduleTravelPlanDto(scheduleId = 9001, memberId = memberId, status = ScheduleTravelPlanStatus.READY,
            travelMode = ScheduleTravelMode.TRANSIT, route = mapper.readTree("""{"transitLegs":[{"kind":"WALK"},{"kind":"BUS"}]}"""))
        whenever(plans.getMyJourneyTravelPlanForStart(memberId, 9001)).thenReturn(plan)
        whenever(departure.lockNotificationActionMembers(memberId, 9001, 2)).thenAnswer {
            val member = members.findByIdForUpdate(memberId)!!
            check(member.sessionGeneration == 2L)
            ScheduleDepartureMemberFence(memberId, 9001, emptySet(), setOf(memberId))
        }
        whenever(departure.markDeparted(eq(memberId), eq(9001), any())).thenAnswer {
            departed.saveAndFlush(ScheduleDepartureStatus(scheduleId = 9001, memberId = memberId, departedAt = now))
        }
        val service = AutomaticJourneyService(rows, members, plans, departure, jobs, schedules, manual, mapper,
            Clock.fixed(now, ZoneOffset.UTC), true, true, true, true, false, "test")
        val sessionId = UUID.randomUUID().toString(); val owner = UUID.randomUUID().toString(); val digest = "b".repeat(64)
        tx { service.prepare(memberId, 9001, 2, AutomaticJourneyPrepareCommand(sessionId, owner, digest, AutomaticJourneyExecution.SIMULATOR)) }
        return Fixture(service, jobs, memberId, sessionId, AutomaticJourneyDepartureCommand(
            1, 1, owner, digest, "$sessionId:1:1:AUTO_DEPARTURE", 1, "AUTO_DEPARTURE", now, AutomaticJourneyExecution.SIMULATOR,
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> tx(action: () -> T): T {
        val template = TransactionTemplate(manager)
        template.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        return template.execute { action() } as T
    }
}
