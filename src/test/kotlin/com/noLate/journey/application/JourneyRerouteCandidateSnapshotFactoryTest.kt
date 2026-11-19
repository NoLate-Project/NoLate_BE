package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.automatic.AutomaticJourneyFixture
import com.noLate.journey.automatic.rideFixtureShape
import com.noLate.journey.automatic.rideFixtureStops
import com.noLate.journey.automatic.useRideGuidancePlan
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class JourneyRerouteCandidateSnapshotFactoryTest {
    private val fixture = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
    private val mapper = fixture.mapper
    private val source = fixture.plan.route!!.path("transitLegs")
    private val ride = source[1]
    private val proof = JourneyRerouteWalkingProofService.Proof("candidate", fixture.clock.instant(),
        fixture.clock.instant().plusSeconds(30), mapOf(0 to walk(source[0]), 2 to walk(source[2])))

    private fun walk(leg: JsonNode) = leg.path("pathCoords").map {
        ServerWalkingCoordinate(it.path("lat").asDouble(), it.path("lng").asDouble())
    }
    private fun raw(): ObjectNode {
        val evidence = ride.path("navigationEvidence")
        val identifiers = evidence.path("busIdentifiers")
        val lane = mapOf("busLocalBlID" to identifiers.path("localRouteId").asText(),
            "busCityCode" to identifiers.path("routeCityCode").asText(),
            "busProviderCode" to identifiers.path("routeProviderCode").asText())
        val stops = evidence.path("stopOccurrences").map { stop ->
            mapOf("index" to stop.path("providerSequence").asInt(),
                "x" to stop.path("coord").path("lng").asDouble(),
                "y" to stop.path("coord").path("lat").asDouble(),
                "localStationID" to stop.path("localStationID").asText(),
                "arsID" to stop.path("arsID").asText(),
                "stationCityCode" to stop.path("stationCityCode").asText(),
                "stationProviderCode" to stop.path("stationProviderCode").asText())
        }
        val graph = ride.path("pathCoords").joinToString("|") {
            "${it.path("lng").asDouble()} ${it.path("lat").asDouble()}"
        }
        val bus = linkedMapOf<String, Any>("trafficType" to 2,
            "startX" to ride.path("startCoord").path("lng").asDouble(),
            "startY" to ride.path("startCoord").path("lat").asDouble(),
            "endX" to ride.path("endCoord").path("lng").asDouble(),
            "endY" to ride.path("endCoord").path("lat").asDouble(),
            "graph" to graph, "lane" to listOf(lane), "passStopList" to mapOf("stations" to stops),
            "way" to evidence.path("directionName").asText(), "stationCount" to 2)
        for (key in listOf("startLocalStationID", "startArsID", "startStationCityCode", "startStationProviderCode"))
            bus[key] = ride.path(key).asText()
        return mapper.valueToTree(mapOf("rps" to listOf(mapOf("trafficType" to 3), bus,
            mapOf("trafficType" to 3))))
    }
    private fun context(raw: JsonNode) = JourneyRerouteSearchService.CandidateContext(raw,
        proof.walkingPaths[0]!!.first(), proof.walkingPaths[2]!!.last(), fixture.clock.instant(),
        fixture.clock.instant().plusSeconds(120))

    @Test fun `server normalized candidate passes existing official stop and shape admission`() {
        val snapshot = JourneyRerouteCandidateSnapshotFactory(mapper).bus(context(raw()), proof)
        assertThat(snapshot.legs.map { it.kind }).containsExactly("WALK", "BUS", "WALK")
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        val admission = JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot)
        assertThat(admission.routeId).isEqualTo("999999999")
        assertThat(admission.firstStopSequence).isEqualTo(44)
        assertThat(admission.lastStopSequence).isEqualTo(46)
    }

    @Test fun `official proof rechecks the same active session after external catalog calls`() {
        val search = mock<JourneyRerouteSearchService>()
        val walking = mock<JourneyRerouteWalkingProofService>()
        val provider = mock<SeoulTransitArrivalClient>()
        val raw = context(raw())
        whenever(search.requireOwnedCandidateContext(eq(7), eq(9001), eq(2), eq(fixture.sessionId),
            eq(fixture.stopCommand()), eq(proof.candidateId))).thenReturn(raw)
        whenever(walking.requireProof(eq(7), eq(9001), eq(2), eq(fixture.sessionId),
            eq(fixture.stopCommand()), eq(proof.candidateId))).thenReturn(proof)
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        val service = JourneyRerouteTransitProofService(search, walking,
            JourneyBusDeviceAdmissionVerifier(provider, true), mapper, fixture.clock)
        val verified = service.verify(7, 9001, 2, fixture.sessionId, fixture.stopCommand(), proof.candidateId)
        assertThat(verified.busAdmission.routeId).isEqualTo("999999999")
        assertThat(verified.routeFingerprint).matches("[a-f0-9]{64}")
        whenever(search.requireOwnedCandidateContext(eq(7), eq(9001), eq(2), eq(fixture.sessionId),
            eq(fixture.stopCommand()), eq(proof.candidateId)))
            .thenReturn(raw).thenThrow(BusinessException(ErrorCode.INVALID_STATE))
        assertThatThrownBy { service.verify(7, 9001, 2, fixture.sessionId, fixture.stopCommand(), proof.candidateId) }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test fun `malformed ODsay shape and stop tuple cannot borrow official admission`() {
        val provider = mock<SeoulTransitArrivalClient>()
        val badGraph = raw()
        (badGraph.path("rps")[1] as ObjectNode).put("graph", "126.9795 37.5665|not-a-point")
        assertThatThrownBy { JourneyRerouteCandidateSnapshotFactory(mapper).bus(context(badGraph), proof) }
            .isInstanceOf(WalkingContractRejected::class.java)
        val badStop = raw()
        val station = badStop.path("rps")[1].path("passStopList").path("stations")[1] as ObjectNode
        station.put("localStationID", "999999111")
        val snapshot = JourneyRerouteCandidateSnapshotFactory(mapper).bus(context(badStop), proof)
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        assertThatThrownBy { JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot) }
            .isInstanceOf(Exception::class.java)
    }
}
