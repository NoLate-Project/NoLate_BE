package com.noLate.journey.automatic

import com.noLate.global.error.BusinessException
import com.noLate.journey.domain.AutomaticJourneyState
import com.noLate.schedule.domain.SchedulePlaceDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class AutomaticJourneyValidationTest {
    @Test fun `database row lock contention is retryable and releasing it validates the same owned session`() {
        val f = AutomaticJourneyFixture()
        f.service().prepare(7, 9001, 2, f.prepareCommand())
        f.service().departure(7, 9001, 2, f.sessionId, f.event())
        val url = "jdbc:h2:mem:lease-lock-${java.util.UUID.randomUUID()};MODE=MySQL;LOCK_TIMEOUT=120"
        DurableJourneyFixtureDatabase(url).use { db ->
            java.sql.DriverManager.getConnection(url, "sa", "").use { holder ->
                holder.createStatement().use { setup ->
                    setup.execute("create table lease_lock_member (id bigint primary key)")
                    setup.execute("insert into lease_lock_member values (7)")
                }
                holder.autoCommit = false
                holder.createStatement().use { it.executeQuery("select id from lease_lock_member where id=7 for update").close() }
                // Exercise the Hibernate and Spring exception translation used by the member FOR UPDATE fence.
                org.mockito.kotlin.whenever(f.members.findById(7L)).thenAnswer {
                    val em = requireNotNull(db.manager.entityManagerFactory).createEntityManager()
                    try {
                        em.transaction.begin()
                        em.createNativeQuery("select id from lease_lock_member where id=7 for update").singleResult
                        em.transaction.commit()
                    } catch (failure: RuntimeException) {
                        if (em.transaction.isActive) em.transaction.rollback()
                        throw org.springframework.orm.jpa.vendor.HibernateJpaDialect().translateExceptionIfPossible(failure) ?: failure
                    } finally { em.close() }
                    java.util.Optional.of(f.actor)
                }
                val mvc = automaticJourneyFixtureMvc(f)
                val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/validate"
                fun validate() = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(f.mapper.writeValueAsBytes(f.stopCommand()))).andReturn().response
                val busy = validate()
                val payload = f.mapper.readTree(busy.contentAsByteArray)
                assertThat(busy.status).describedAs("lock response %s", payload.path("errorCode").asText()).isEqualTo(503)
                assertThat(payload.path("errorCode").asText()).isEqualTo("JOURNEY_STORAGE_BUSY")
                assertThat(payload.path("data").isNull).isTrue()
                holder.rollback()
                val fresh = validate()
                assertThat(fresh.status).isEqualTo(200)
                assertThat(f.mapper.readTree(fresh.contentAsByteArray).path("data").path("state").asText()).isEqualTo("ACTIVE")
                org.mockito.kotlin.doThrow(org.springframework.dao.OptimisticLockingFailureException("changed saved row"))
                    .whenever(f.members).findById(7L)
                val changed = validate()
                assertThat(changed.status).isEqualTo(409)
                assertThat(f.mapper.readTree(changed.contentAsByteArray).path("errorCode").asText()).isEqualTo("C003")
            }
        }
    }
    @Test fun `reroute destination stays owned and rejects an armed or changed route`() {
        val f = AutomaticJourneyFixture()
        f.plan = f.plan.copy(destination = SchedulePlaceDto("경복궁", lat = 37.576, lng = 126.978))
        val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        assertThatThrownBy { service.requireActiveRerouteDestination(7, 9001, 2, f.sessionId, f.stopCommand()) }
            .isInstanceOf(BusinessException::class.java)
        service.departure(7, 9001, 2, f.sessionId, f.event())
        val destination = service.requireActiveRerouteDestination(7, 9001, 2, f.sessionId, f.stopCommand())
        assertThat(destination.latitude).isEqualTo(37.576)
        assertThat(destination.longitude).isEqualTo(126.978)
        f.plan = f.plan.copy(destination = SchedulePlaceDto("다른 곳", lat = 37.58, lng = 126.99))
        assertThatThrownBy { service.requireActiveRerouteDestination(7, 9001, 2, f.sessionId, f.stopCommand()) }
            .isInstanceOf(BusinessException::class.java)
    }
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
