package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.journey.application.JourneyRerouteRouteProvider
import com.noLate.journey.application.ServerPedestrianProvider
import com.noLate.journey.domain.JourneyRerouteSearchCommand
import com.noLate.schedule.domain.SchedulePlaceDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

class AutomaticJourneyControllerTest {
    @Test fun `authenticated candidate walking proof checks both walks without switching route`() {
        val f = AutomaticJourneyFixture()
        f.plan = f.plan.copy(destination = SchedulePlaceDto("경복궁", lat = 37.5765, lng = 126.9785))
        val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        service.departure(7, 9001, 2, f.sessionId, f.event())
        val provider = object : JourneyRerouteRouteProvider {
            override val providerId = "odsay"
            override fun searchRaw(request: TransitJourneySearchRequest): JsonNode = f.mapper.readTree("""
                {"result":{"paths":[{"pathType":2,"totalTime":25,"rps":[
                {"trafficType":3,"duration":5},
                {"trafficType":2,"duration":15,"startX":126.9905,"startY":37.5655,
                 "endX":126.978,"endY":37.576,"graph":"126.9905 37.5655|126.978 37.576",
                 "passStopList":{"stations":[{},{}]},"lane":[{}]},
                {"trafficType":3,"duration":5}]}]}}
            """)
        }
        var walkCalls = 0
        val walking = ServerPedestrianProvider { request ->
            walkCalls++
            """{"type":"FeatureCollection","features":[{"type":"Feature",
                "properties":{"index":0},"geometry":{"type":"LineString","coordinates":[
                [${request.origin.lng},${request.origin.lat}],
                [${request.destination.lng},${request.destination.lat}]]}}]}"""
        }
        val mvc = automaticJourneyFixtureMvc(f, rerouteProviders = listOf(provider), rerouteEnabled = true,
            rerouteWalkingProvider = walking)
        val base = "/api/schedules/9001/automatic-journey/${f.sessionId}/reroute-candidates"
        val searchCommand = JourneyRerouteSearchCommand(1, 1, f.owner, f.digest,
            37.565, 126.990, 8.0, f.clock.instant())
        val found = mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(searchCommand))).andReturn().response
        assertThat(found.status).isEqualTo(200)
        val candidateId = f.mapper.readTree(found.contentAsByteArray)
            .path("data").path("result").path("paths")[0].path("candidateId").asText()
        val proofPath = "$base/$candidateId/walking-proof"
        val verified = mvc.perform(post(proofPath).contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(searchCommand.fence()))).andReturn().response
        assertThat(verified.status).isEqualTo(200)
        val data = f.mapper.readTree(verified.contentAsByteArray).path("data")
        assertThat(data.path("walkCount").asInt()).isEqualTo(2)
        assertThat(data.path("navigationReady").asBoolean()).isFalse()
        assertThat(walkCalls).isEqualTo(2)
        val denied = mvc.perform(post(proofPath).contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(searchCommand.copy(routeRevision = 2).fence()))).andReturn().response
        assertThat(denied.status).isNotEqualTo(200)
        assertThat(walkCalls).isEqualTo(2)
        assertThat(service.current(7, 9001, 2, f.owner)?.sessionId).isEqualTo(f.sessionId)
    }
    @Test fun `active session looks up ODsay reroute candidates through authenticated HTTP`() {
        val f = AutomaticJourneyFixture()
        f.plan = f.plan.copy(destination = SchedulePlaceDto("경복궁", lat = 37.576, lng = 126.978))
        val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        service.departure(7, 9001, 2, f.sessionId, f.event())
        var providerCalls = 0
        val provider = object : JourneyRerouteRouteProvider {
            override val providerId = "odsay"
            override fun searchRaw(request: TransitJourneySearchRequest): JsonNode {
                providerCalls++
                assertThat(request.destinationLat).isEqualTo(37.576)
                assertThat(request.destinationLng).isEqualTo(126.978)
                return f.mapper.readTree("""{"result":{"paths":[{"pathType":2,"totalTime":25,"rps":[
                    {"trafficType":3,"duration":5},
                    {"trafficType":2,"duration":15,"startX":126.99,"startY":37.565,
                     "endX":126.978,"endY":37.576,"graph":"126.99 37.565|126.978 37.576",
                     "passStopList":{"stations":[{},{}]},"lane":[{}]},
                    {"trafficType":3,"duration":5}]}]}}""")
            }
        }
        val command = JourneyRerouteSearchCommand(1, 1, f.owner, f.digest,
            37.565, 126.990, 8.0, f.clock.instant())
        val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/reroute-candidates"
        val mvc = automaticJourneyFixtureMvc(f, rerouteProviders = listOf(provider), rerouteEnabled = true)
        val response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
            .content(f.mapper.writeValueAsBytes(command))).andReturn().response
        assertThat(response.status).isEqualTo(200)
        val paths = f.mapper.readTree(response.contentAsByteArray).path("data").path("result").path("paths")
        assertThat(paths.size()).isEqualTo(1)
        assertThat(paths[0].path("candidateId").asText()).matches("[a-f0-9-]{36}")
        assertThat(providerCalls).isEqualTo(1)
        val unauthenticated = automaticJourneyFixtureMvc(f, false, rerouteProviders = listOf(provider), rerouteEnabled = true)
            .perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(f.mapper.writeValueAsBytes(command))).andReturn().response
        assertThat(unauthenticated.status).isEqualTo(401)
        assertThat(providerCalls).isEqualTo(1)
    }
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
