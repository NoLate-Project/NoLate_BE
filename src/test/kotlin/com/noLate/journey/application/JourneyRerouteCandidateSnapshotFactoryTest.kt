package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.automatic.AutomaticJourneyFixture
import com.noLate.journey.automatic.rideFixtureShape
import com.noLate.journey.automatic.rideFixtureStops
import com.noLate.journey.automatic.useRideGuidancePlan
import com.noLate.journey.domain.AutomaticJourneyPreparation
import com.noLate.journey.domain.AutomaticJourneyState
import com.noLate.journey.domain.AutomaticJourneyExecution
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
        return mapper.valueToTree(mapOf("totalTime" to 12, "rps" to listOf(mapOf("trafficType" to 3), bus,
            mapOf("trafficType" to 3))))
    }
    private fun context(raw: JsonNode) = JourneyRerouteSearchService.CandidateContext(raw,
        proof.walkingPaths[0]!!.first(), proof.walkingPaths[2]!!.last(), fixture.clock.instant(),
        fixture.clock.instant().plusSeconds(120))

    @Test fun `server normalized candidate passes existing official stop and shape admission`() {
        val snapshot = JourneyRerouteCandidateSnapshotFactory(mapper).bus(context(raw()), proof)
        val displayRoute = mapper.readTree(snapshot.json)
        assertThat(displayRoute.path("id").asText()).startsWith("odsay-reroute-")
        assertThat(displayRoute.path("minutes").asDouble()).isEqualTo(12.0)
        assertThat(snapshot.legs.map { it.kind }).containsExactly("WALK", "BUS", "WALK")
        val legs = mapper.readTree(snapshot.json).path("transitLegs")
        assertThat(legs[0].path("navigationEvidence").path("provider").asText()).isEqualTo("tmap")
        assertThat(legs[1].path("navigationEvidence").path("stopOccurrences")[0].path("code").asText()).isNotBlank()
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        val admission = JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot)
        assertThat(admission.routeId).isEqualTo("999999999")
        assertThat(admission.firstStopSequence).isEqualTo(44)
        assertThat(admission.lastStopSequence).isEqualTo(46)
    }

    @Test fun `real ODsay style candidate without stationCount derives count from ordered stops`() {
        val candidate = raw()
        val bus = candidate.path("rps")[1] as ObjectNode
        bus.remove("stationCount")
        val snapshot = JourneyRerouteCandidateSnapshotFactory(mapper).bus(context(candidate), proof)
        val evidence = mapper.readTree(snapshot.json).path("transitLegs")[1].path("navigationEvidence")
        assertThat(evidence.path("declaredStationCount").asInt()).isEqualTo(2)

        bus.put("stationCount", 1)
        assertThatThrownBy { JourneyRerouteCandidateSnapshotFactory(mapper).bus(context(candidate), proof) }
            .isInstanceOf(WalkingContractRejected::class.java)
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

    @Test fun `reroute input fence accepts the normalized provider route and rejects changed stops or geometry`() {
        val candidate = context(raw())
        val snapshot = JourneyRerouteCandidateSnapshotFactory(mapper).bus(candidate, proof)
        val legs = mapper.readTree(snapshot.json).path("transitLegs").mapIndexed { index, leg ->
            val evidence = leg.path("navigationEvidence")
            mapOf(
                "index" to index, "kind" to leg.path("kind").asText(),
                "exact" to true, "geometryInputComplete" to true,
                "geometrySource" to leg.path("pathGeometrySource").asText(),
                "coordinates" to leg.path("pathCoords").map { point -> mapOf(
                    "latitude" to point.path("lat").asDouble(), "longitude" to point.path("lng").asDouble()) },
                "start" to leg.path("startCoord").let { mapOf("latitude" to it.path("lat").asDouble(),
                    "longitude" to it.path("lng").asDouble()) },
                "end" to leg.path("endCoord").let { mapOf("latitude" to it.path("lat").asDouble(),
                    "longitude" to it.path("lng").asDouble()) },
                "stopEvidenceProvider" to evidence.path("provider").asText(),
                "stopEvidenceVersion" to 1,
                "localRouteId" to leg.path("localRouteId").asText(),
                "directionName" to evidence.path("directionName").takeIf { it.isTextual }?.asText(),
                "declaredStationCount" to evidence.path("declaredStationCount").takeIf { it.isInt }?.asInt(),
                "stops" to evidence.path("stopOccurrences").map { stop -> mapOf(
                    "sourceIndex" to stop.path("sourceIndex").asInt(),
                    "providerSequence" to stop.path("providerSequence").asInt(),
                    "name" to stop.path("name").takeIf { it.isTextual }?.asText(),
                    "code" to stop.path("code").asText(),
                    "coordinate" to stop.path("coord").let { mapOf("latitude" to it.path("lat").asDouble(),
                        "longitude" to it.path("lng").asDouble()) }) },
            )
        }
        val input = mapper.valueToTree<ObjectNode>(mapOf(
            "schemaVersion" to 1, "routeRevision" to 2, "context" to "SAVED_ROUTE",
            "provider" to "odsay", "source" to "api", "reliability" to "provider_estimate",
            "plausibility" to "normal", "serviceState" to "UNKNOWN",
            "origin" to mapOf("latitude" to candidate.origin.lat, "longitude" to candidate.origin.lng),
            "destination" to mapOf("latitude" to candidate.destination.lat, "longitude" to candidate.destination.lng),
            "legs" to legs,
        ))
        JourneyRerouteInputFence.validate(mapper.writeValueAsString(input), 2, snapshot, candidate, mapper)
        val changedStop = input.deepCopy()
        (changedStop.path("legs")[1].path("stops")[0] as ObjectNode).put("code", "999999999")
        assertThatThrownBy { JourneyRerouteInputFence.validate(mapper.writeValueAsString(changedStop), 2,
            snapshot, candidate, mapper) }.isInstanceOf(BusinessException::class.java)
        val changedGeometry = input.deepCopy()
        (changedGeometry.path("legs")[0].path("coordinates")[0] as ObjectNode).put("latitude", 37.0)
        assertThatThrownBy { JourneyRerouteInputFence.validate(mapper.writeValueAsString(changedGeometry), 2,
            snapshot, candidate, mapper) }.isInstanceOf(BusinessException::class.java)

        val sourceFingerprint = JourneyRouteSnapshot.from(fixture.plan, mapper).fingerprint
        val old = AutomaticJourneyPreparation(sessionId = fixture.sessionId, memberId = 7, scheduleId = 9001,
            loginSessionGeneration = 2, clientOwnerId = fixture.owner, routeInputDigest = fixture.digest,
            routeFingerprint = sourceFingerprint, generation = 1, routeRevision = 1,
            execution = AutomaticJourneyExecution.SIMULATOR, state = AutomaticJourneyState.ACTIVE,
            sequence = 1, preparedAt = fixture.clock.instant(), expiresAt = fixture.clock.instant().plusSeconds(600),
            departedAt = fixture.clock.instant())
        fixture.rows[old.sessionId] = old
        val search = mock<JourneyRerouteSearchService>()
        val walking = mock<JourneyRerouteWalkingProofService>()
        whenever(search.requireOwnedCandidateContext(eq(7), eq(9001), eq(2), eq(old.sessionId),
            eq(fixture.stopCommand()), eq(proof.candidateId))).thenReturn(candidate)
        whenever(walking.requireProof(eq(7), eq(9001), eq(2), eq(old.sessionId),
            eq(fixture.stopCommand()), eq(proof.candidateId))).thenReturn(proof)
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        val busAdmission = JourneyBusDeviceAdmissionVerifier(provider, true).verify(snapshot)
        val transitProof = JourneyRerouteTransitProofService.Proof(proof.candidateId, snapshot.fingerprint,
            proof.validUntil, busAdmission, snapshot)
        val service = JourneyRerouteCommitPersistence(fixture.repository, fixture.members, fixture.plans,
            search, walking, mapper, fixture.clock)
        val command = JourneyRerouteCommitCommand(1, 1, fixture.owner, fixture.digest, mapper.writeValueAsString(input))
        val committed = service.commit(7, 9001, 2, old.sessionId, proof.candidateId, command, transitProof)
        assertThat(old.state).isEqualTo(AutomaticJourneyState.STOPPED)
        assertThat(committed.sessionId).isNotEqualTo(old.sessionId)
        assertThat(committed.generation).isEqualTo(2)
        assertThat(committed.routeRevision).isEqualTo(2)
        assertThat(committed.state).isEqualTo(AutomaticJourneyState.ACTIVE)
        assertThat(fixture.rows[committed.sessionId]?.rerouteSnapshotJson).isEqualTo(snapshot.json)
        assertThat(fixture.rows[committed.sessionId]?.sourceRouteFingerprint).isEqualTo(sourceFingerprint)
        assertThat(fixture.departureCount.get()).isZero()
        assertThatThrownBy { service.commit(7, 9001, 2, old.sessionId, proof.candidateId, command, transitProof) }
            .isInstanceOf(BusinessException::class.java)
    }
}
