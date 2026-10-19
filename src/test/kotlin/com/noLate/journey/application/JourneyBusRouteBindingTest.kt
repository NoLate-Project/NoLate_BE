package com.noLate.journey.application

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.automatic.*
import com.noLate.journey.domain.*
import com.noLate.transit.application.TransitArrivalService
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class JourneyBusRouteBindingTest {
    private fun fixture() = AutomaticJourneyFixture().also { it.useMappedBusPlan() }
    private fun resolve(f: AutomaticJourneyFixture) = JourneyBusRouteBindingResolver().resolve(JourneyRouteSnapshot.from(f.plan, f.mapper))
    private fun bus(f: AutomaticJourneyFixture) = f.plan.route!!.path("transitLegs")[1] as ObjectNode
    private fun evidence(f: AutomaticJourneyFixture) = bus(f).path("navigationEvidence") as ObjectNode
    private fun command(f: AutomaticJourneyFixture) = JourneyBusBindingCommand(1, 1, f.owner, f.digest)
    @Test fun `explicit saved local route stop occurrence namespace and raw direction resolve`() {
        val f = fixture(); val result = resolve(f)
        assertThat(result.status).isEqualTo("SUPPORTED")
        assertThat(result.query!!.providerRouteId).isEqualTo("999999") // NOT ODsay busID 888888
        assertThat(result.query!!.arsId).isEqualTo("99999")
        assertThat(result.boarding).isEqualTo(JourneyBusStopOccurrence(0, 10, "999996", 37.5665, 126.9795))
        bus(f).put("routeProviderCode", "10").put("startStationProviderCode", "10")
        (evidence(f).path("busIdentifiers") as ObjectNode).put("routeProviderCode", "10").put("startStationProviderCode", "10")
        evidence(f).path("stopOccurrences").forEach { (it as ObjectNode).put("stationProviderCode", "10") }
        assertThat(resolve(f).status).isEqualTo("SUPPORTED")
    }
    @Test fun `display names routing bus ID and guessed city cannot repair missing namespace evidence`() {
        for (field in listOf("localRouteId", "startArsID", "startLocalStationID", "routeCityCode", "routeProviderCode", "startStationCityCode", "startStationProviderCode", "navigationEvidence", "startCoord")) {
            val f = fixture(); bus(f).remove(field)
            assertThat(resolve(f).status).describedAs(field).isNotEqualTo("SUPPORTED")
            assertThat(resolve(f).query).isNull()
        }
        for (value in listOf("99x999", "9999", "999999", " 99999", "99/999", "00000")) {
            val f = fixture(); bus(f).put("startArsID", value); assertThat(resolve(f).query).isNull()
        }
        for (value in listOf("0", " 999999", "999999\n", "999999999999999999999")) {
            val f = fixture(); bus(f).put("localRouteId", value); assertThat(resolve(f).query).isNull()
        }
    }
    @Test fun `different city provider raw direction and unsupported provider fail closed`() {
        for (field in listOf("routeCityCode", "routeProviderCode", "startStationCityCode", "startStationProviderCode")) {
            val f = fixture(); bus(f).put(field, "2"); assertThat(resolve(f).query).isNull()
        }
        for (direction in listOf("", " ", "bad\n", "x".repeat(129))) {
            val f = fixture(); evidence(f).put("directionName", direction); assertThat(resolve(f).status).isEqualTo("DIRECTION_REQUIRED")
        }
        val f = fixture(); evidence(f).remove("directionName"); bus(f).put("directionName", "UI fallback")
        assertThat(resolve(f).status).isEqualTo("DIRECTION_REQUIRED")
        (f.plan.route as ObjectNode).put("provider", "tmap"); assertThat(resolve(f).status).isEqualTo("UNSUPPORTED_PROVIDER")
    }
    @Test fun `missing unordered malformed duplicate boarding occurrences cannot certify chosen stop`() {
        for (mode in listOf("sequence", "reversed", "sourceIndex", "sourceIndexString", "coordinate", "count", "localID", "ars", "city", "provider", "repeated", "far", "missingMiddleID", "rawIDs")) {
            val f = fixture(); val first = evidence(f).path("stopOccurrences")[0] as ObjectNode
            val middle = evidence(f).path("stopOccurrences")[1] as ObjectNode
            when (mode) {
                "sequence" -> first.remove("providerSequence")
                "reversed" -> middle.put("providerSequence", 9)
                "sourceIndex" -> first.put("sourceIndex", 1)
                "sourceIndexString" -> first.put("sourceIndex", "0")
                "coordinate" -> first.set<com.fasterxml.jackson.databind.JsonNode>("coord", f.mapper.readTree("""{"lat":95,"lng":126.9795}"""))
                "count" -> evidence(f).put("declaredStationCount", 3)
                "localID" -> first.put("localStationID", "999995")
                "ars" -> first.put("arsID", "99-998")
                "city" -> first.put("stationCityCode", "2")
                "provider" -> first.put("stationProviderCode", "2")
                "repeated" -> middle.put("localStationID", "999996")
                "far" -> (bus(f).path("startCoord") as ObjectNode).put("lat", 37.5675)
                "missingMiddleID" -> middle.remove("localStationID")
                "rawIDs" -> (evidence(f).path("busIdentifiers") as ObjectNode).put("localRouteId", " 999999")
            }
            assertThat(resolve(f).query).describedAs(mode).isNull()
        }
    }
    @Test fun `ambiguous nested route arrays fail rather than choosing a convenient alternative`() {
        val f = fixture(); (f.plan.route as ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>("routeInfo", f.plan.route!!.deepCopy())
        assertThat(resolve(f).status).isEqualTo("AMBIGUOUS_ROUTE_SNAPSHOT")
    }
    @Test fun `wrapped saved route is supported only using metadata on its selected container`() {
        val f = fixture(); f.plan = f.plan.copy(route = f.mapper.createObjectNode().set("routeInfo", f.plan.route))
        assertThat(resolve(f).status).isEqualTo("SUPPORTED")
    }
    @Test fun `read only binding does not access arrivals departure pushes or change state`() {
        val f = fixture(); val automatic = f.service(); automatic.prepare(7, 9001, 2, f.prepareCommand())
        val arrivals = mock<TransitArrivalService>(); val service = JourneyBusArrivalService(automatic, arrivals, f.clock)
        assertThat(service.binding(7, 9001, 2, f.sessionId, command(f)).mapping.status).isEqualTo("SUPPORTED")
        verifyNoInteractions(arrivals, f.departure, f.jobs, f.schedules)
        assertThat(f.rows[f.sessionId]!!.state).isEqualTo(AutomaticJourneyState.ARMED)
    }
    @Test fun `valid syntax but client substituted route stop direction is blocked before provider access`() {
        for (mode in listOf("route", "stop", "direction", "missingSavedID")) {
            val f = fixture(); if (mode == "missingSavedID") bus(f).remove("localRouteId")
            val automatic = f.service(); automatic.prepare(7, 9001, 2, f.prepareCommand())
            var q = JourneyBusArrivalQuery("SEOUL_BUS", "99999", "999999", "합성 동쪽 방면", 1)
            q = when (mode) { "route" -> q.copy(providerRouteId = "888888"); "stop" -> q.copy(arsId = "99998"); "direction" -> q.copy(directionLabel = "합성 서쪽 방면"); else -> q }
            val arrivals = mock<TransitArrivalService>()
            assertThatThrownBy { JourneyBusArrivalService(automatic, arrivals, f.clock).observe(7, 9001, 2, f.sessionId,
                JourneyBusArrivalCommand(1, 1, f.owner, f.digest, q)) }.isInstanceOf(Exception::class.java)
            verifyNoInteractions(arrivals)
        }
    }
    @Test fun `binding endpoint requires authentication owner revision digest and unchanged saved route`() {
        val f = fixture(); f.service().prepare(7, 9001, 2, f.prepareCommand())
        val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/bus-arrivals/binding"
        val mvc = automaticJourneyFixtureMvc(f)
        assertThat(automaticJourneyFixtureMvc(f, false).perform(post(path).contentType("application/json").content(f.mapper.writeValueAsString(command(f)))).andReturn().response.status).isEqualTo(401)
        val response = mvc.perform(post(path).contentType("application/json").content(f.mapper.writeValueAsString(command(f)))).andReturn().response
        assertThat(response.status).isEqualTo(200); assertThat(response.contentAsString).contains("SAVED_ROUTE_METADATA", "SUPPORTED", "999999")
        for (bad in listOf(command(f).copy(generation = 2), command(f).copy(routeRevision = 2), command(f).copy(routeInputDigest = "b".repeat(64)), command(f).copy(clientOwnerId = f.sessionId))) {
            assertThat(mvc.perform(post(path).contentType("application/json").content(f.mapper.writeValueAsString(bad))).andReturn().response.status).isNotEqualTo(200)
        }
        f.useMappedBusPlan("CHANGED_ROUTE")
        assertThat(mvc.perform(post(path).contentType("application/json").content(f.mapper.writeValueAsString(command(f)))).andReturn().response.status).isNotEqualTo(200)
    }
}
