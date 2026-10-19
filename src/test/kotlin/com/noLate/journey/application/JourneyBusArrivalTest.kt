package com.noLate.journey.application

import com.noLate.journey.automatic.*
import com.noLate.journey.domain.*
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant

class JourneyBusArrivalTest {
    private fun mappedFixture() = AutomaticJourneyFixture().also { it.useMappedBusPlan() }
    private val now = Instant.parse("2026-10-04T03:00:30Z")
    private val query = JourneyBusArrivalQuery("SEOUL_BUS", "99999", "999999", "합성 동쪽 방면", 1)
    private fun command(f: AutomaticJourneyFixture = AutomaticJourneyFixture()) = JourneyBusArrivalCommand(1, 1, f.owner, f.digest, query)
    private fun arrival() = TransitArrivalDto(provider = "seoul-bus", kind = "BUS", arsId = "99999", providerRouteId = "999999",
        vehicleId = "999997", direction = query.directionLabel, sourceUpdatedAt = now.toString(), observedAt = now.toString(),
        expectedAt = now.plusSeconds(120).toString(), waitSeconds = 120, freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
    private fun match(values: List<TransitArrivalDto>, evaluatedAt: Instant = now) = JourneyBusArrivalMatcher().match("fixture-session", command(), values, evaluatedAt)

    @Test fun `fresh exact request identity yields waiting only and incoming vehicle never user ride`() {
        val result = match(listOf(arrival()))
        assertThat(result.status).isEqualTo("WAITING_INFORMATION")
        assertThat(result.incomingVehicleId).isEqualTo("999997")
        assertThat(result.identityEvidence).isEqualTo("REQUEST_IDENTIFIERS_ONLY")
        assertThat(result.confirmedBoarding).isFalse()
        assertThat(result.remainingStopsToAlight).isNull()
        assertThat(result.observation!!.vehicleTrackingEnabled).isFalse()
    }
    @Test fun `same names cannot replace provider route stop direction or namespace`() {
        val original = arrival()
        listOf(original.copy(providerRouteId = null), original.copy(providerRouteId = "999998"),
            original.copy(arsId = "99998"), original.copy(direction = "합성 서쪽 방면"),
            original.copy(direction = null), original.copy(provider = "tago"), original.copy(kind = "SUBWAY")).forEach {
            val result = match(listOf(it))
            assertThat(result.status).isEqualTo("IDENTITY_MISMATCH")
            assertThat(result.observation).isNull(); assertThat(result.incomingVehicleId).isNull()
        }
    }
    @Test fun `no arrivals distinct from mismatched arrivals`() { assertThat(match(emptyList()).status).isEqualTo("NO_ARRIVAL") }
    @Test fun `old source cannot be refreshed with new receipt`() {
        val result = match(listOf(arrival().copy(observedAt = now.plusSeconds(121).toString())), now.plusSeconds(121))
        assertThat(result.status).isEqualTo("STALE"); assertThat(result.incomingVehicleId).isNull()
        assertThat(result.observation!!.remainingWaitSeconds).isNull()
    }
    @Test fun `receipt only source proof unavailable for guide timing`() {
        assertThat(match(listOf(arrival().copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY))).status).isEqualTo("UNVERIFIED")
    }
    @Test fun `earliest fresh matched vehicle wins not earliest wrong direction`() {
        val result = match(listOf(arrival().copy(direction = "wrong"), arrival().copy(waitSeconds = 240, expectedAt = now.plusSeconds(240).toString()), arrival()))
        assertThat(result.observation!!.remainingWaitSeconds).isEqualTo(120)
    }
    @Test fun `invalid zero vehicle ID is not exposed`() { assertThat(match(listOf(arrival().copy(vehicleId = "0"))).incomingVehicleId).isNull() }
    @Test fun `query rejects namespace coercion IDs padding labels and unsupported legs`() {
        listOf(query.copy(namespace = "TMAP"), query.copy(arsId = "99-999"), query.copy(arsId = "00000"),
            query.copy(providerRouteId = "0"), query.copy(directionLabel = ""), query.copy(directionLabel = "bad\n"), query.copy(legIndex = 2))
            .forEach { assertThat(it.valid()).isFalse() }
    }
    @Test fun `read only observation preserves armed state and no departure push effects`() {
        val f = mappedFixture(); val automatic = f.service(); automatic.prepare(7, 9001, 2, f.prepareCommand())
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getBusArrivals(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())).thenReturn(listOf(arrival()))
        val observed = JourneyBusArrivalService(automatic, arrivals, f.clock).observe(7, 9001, 2, f.sessionId, command(f))
        assertThat(observed.identityEvidence).isEqualTo("SAVED_ROUTE_METADATA")
        assertThat(f.rows[f.sessionId]!!.state).isEqualTo(AutomaticJourneyState.ARMED)
        verify(arrivals).getBusArrivals("99999", null, "1000", null, null, 10, TransitCityCodeNamespace.ODSAY_CID, null)
        verifyNoInteractions(f.departure, f.jobs, f.schedules)
    }
    @Test fun `wrong owner revision generation digest actor access expired stopped disabled never fetch`() {
        for (mode in listOf("owner", "revision", "generation", "digest", "actor", "access", "expired", "stopped", "disabled", "route")) {
            val clock = AutomaticJourneyMutableClock(); val f = AutomaticJourneyFixture(clock); val enabled = f.service()
            enabled.prepare(7, 9001, 2, f.prepareCommand())
            var cmd = command(f)
            when (mode) {
                "owner" -> cmd = cmd.copy(clientOwnerId = f.sessionId)
                "revision" -> cmd = cmd.copy(routeRevision = 2)
                "generation" -> cmd = cmd.copy(generation = 2)
                "digest" -> cmd = cmd.copy(routeInputDigest = "b".repeat(64))
                "actor" -> f.actor.sessionGeneration = 3
                "access" -> f.accessAllowed = false
                "expired" -> clock.now = clock.now.plusSeconds(600)
                "stopped" -> enabled.stop(7, 9001, 2, f.sessionId, f.stopCommand())
                "route" -> f.plan = f.plan.copy(route = f.mapper.readTree("""{"transitLegs":[{"kind":"BUS","lineName":"changed","durationMinutes":3,"stationCount":2}]}"""))
            }
            val arrivals = mock<TransitArrivalService>()
            val service = JourneyBusArrivalService(if (mode == "disabled") f.service(enabled = false) else enabled, arrivals, clock)
            assertThatThrownBy { service.observe(7, 9001, 2, f.sessionId, cmd) }.isInstanceOf(Exception::class.java)
            verifyNoInteractions(arrivals)
        }
    }
    @Test fun `late provider response after stop or saved route replacement is discarded`() {
        for (mode in listOf("stop", "route")) {
            val f = mappedFixture(); val automatic = f.service(); automatic.prepare(7, 9001, 2, f.prepareCommand())
            val arrivals = mock<TransitArrivalService>()
            whenever(arrivals.getBusArrivals(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())).thenAnswer {
                if (mode == "stop") automatic.stop(7, 9001, 2, f.sessionId, f.stopCommand()) else f.useMappedBusPlan("CHANGED_ROUTE")
                listOf(arrival())
            }
            assertThatThrownBy { JourneyBusArrivalService(automatic, arrivals, f.clock).observe(7, 9001, 2, f.sessionId, command(f)) }.isInstanceOf(Exception::class.java)
        }
    }
    @Test fun `controller auth and session fences precede source access`() {
        val f = mappedFixture(); f.service().prepare(7, 9001, 2, f.prepareCommand())
        val body = f.mapper.writeValueAsString(command(f))
        val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/bus-arrivals"
        var calls = 0
        val anonymous = automaticJourneyFixtureMvc(f, authenticated = false, arrivalSource = { calls++; listOf(arrival()) })
        assertThat(anonymous.perform(post(path).contentType("application/json").content(body)).andReturn().response.status).isEqualTo(401)
        assertThat(calls).isZero()
        val mvc = automaticJourneyFixtureMvc(f, arrivalSource = { calls++; listOf(arrival()) })
        assertThat(mvc.perform(post(path).contentType("application/json").content(body)).andReturn().response.status).isEqualTo(200)
        assertThat(calls).isEqualTo(1)
    }
}
