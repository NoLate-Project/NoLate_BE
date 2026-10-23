package com.noLate.journey.application

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.automatic.*
import com.noLate.journey.domain.JourneyBusBindingCommand
import com.noLate.transit.domain.*
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.*

class JourneyBusRideMappingTest {
    private fun fixture() = AutomaticJourneyFixture().also { it.useRideGuidancePlan() }
    private fun snapshot(f: AutomaticJourneyFixture) = JourneyRouteSnapshot.from(f.plan, f.mapper)
    private fun mapping(f: AutomaticJourneyFixture = fixture()) = JourneyBusRideMapping.resolve(snapshot(f), rideFixtureStops(), rideFixtureShape())
    @Test fun `official ordered BIS and ARS tuple crosswalk and saved shape resolve without ordinal equivalence`() {
        val value = mapping(); assertEquals("VERIFIED", value.status)
        val verified = assertNotNull(value.verified)
        assertEquals(listOf(44,45,46), verified.stops.map { it.sequence }) // saved ODsay ordinals are 10,11,12
        assertEquals(3, verified.coordinates.size)
    }
    @Test fun `official catalog supplies direction and stop count omitted by real ODsay bus response`() {
        val f = fixture()
        val evidence = f.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode
        evidence.remove(listOf("directionName", "declaredStationCount"))
        val route = snapshot(f)
        assertEquals("DIRECTION_REQUIRED", JourneyBusRouteBindingResolver().resolve(route).status)
        val result = JourneyBusRideMapping.resolve(route, rideFixtureStops(), rideFixtureShape())
        assertEquals("VERIFIED", result.status)
        val verified = assertNotNull(result.verified)
        assertEquals("합성 동쪽 방면", verified.direction)
        assertEquals(listOf(44, 45, 46), verified.stops.map { it.sequence })
    }
    @Test fun `official catalog cannot override contradictory raw direction or station count`() {
        val wrongDirection = fixture()
        val directionEvidence = wrongDirection.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode
        directionEvidence.put("directionName", "반대 방면")
        assertEquals("DIRECTION_CROSSWALK_MISMATCH", mapping(wrongDirection).status)
        val wrongCount = fixture()
        val countEvidence = wrongCount.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode
        countEvidence.put("declaredStationCount", 3)
        assertEquals("STOP_OCCURRENCE_REQUIRED", mapping(wrongCount).status)
        val mixedDirections = rideFixtureStops().mapIndexed { i, stop -> if (i == 1) stop.copy(direction = "반대 방면") else stop }
        assertEquals("DIRECTION_CROSSWALK_MISMATCH", JourneyBusRideMapping.resolve(snapshot(fixture()), mixedDirections, rideFixtureShape()).status)
    }
    @Test fun `read only ride endpoint fetches official catalog for raw ODsay stop evidence without way or count`() {
        val f = fixture()
        val evidence = f.plan.route!!.path("transitLegs")[1].path("navigationEvidence") as ObjectNode
        evidence.remove(listOf("directionName", "declaredStationCount"))
        val automatic = f.service()
        automatic.prepare(7, 9001, 2, f.prepareCommand())
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        val binding = JourneyBusRideService(automatic, provider, f.clock, true)
            .binding(7, 9001, 2, f.sessionId, JourneyBusBindingCommand(1, 1, f.owner, f.digest))
        assertEquals("VERIFIED", binding["status"])
        assertEquals("합성 동쪽 방면", binding["direction"])
        verify(provider).getBusRouteDiagnosticStops("999999999")
        verify(provider).getBusRoutePath("999999999")
        verifyNoInteractions(f.departure, f.jobs, f.schedules)
    }
    @Test fun `unselected synthetic bend preserves saved tuple binding and bounded continuation`() {
        val result = JourneyBusRideMapping.resolve(snapshot(fixture()),rideCatalogBendFixtureStops(),rideCatalogBendFixtureShape())
        val verified = assertNotNull(result.verified)
        assertEquals("VERIFIED",result.status)
        assertEquals(listOf(44,45,46),verified.stops.map { it.sequence })
        assertEquals(47,verified.observationEndSequence)
        assertTrue(verified.departureWindowMeters in 200.0..230.0)
        assertEquals(6,verified.catalog.size)
        val selectedBend = SeoulBusStopShapeMatcher.match("999999999",verified.catalog,"999999999",verified.shape,48,49)
        assertEquals(SeoulBusStopShapeMatcher.Ambiguity.OCCURRENCE_SPAN,selectedBend.ambiguity)
    }
    @Test fun `missing wrong reversed duplicated or unnumbered stop windows fail closed`() {
        val base = rideFixtureStops()
        for (stops in listOf(base.drop(1), base.reversed(), base + base,
            base.map { it.copy(arsId = "00000") }, base.map { it.copy(stationId = "999999990") },
            base.map { it.copy(publicStopNumberInvalid = true) }, base.map { it.copy(latitude = it.latitude + 0.002) })) {
            assertNull(JourneyBusRideMapping.resolve(snapshot(fixture()), stops, rideFixtureShape()).verified)
        }
    }
    @Test fun `raw saved namespace and geometry claims cannot be repaired by catalog`() {
        for (field in listOf("localRouteId", "navigationEvidence", "pathCoordsIsExact", "pathGeometrySource", "pathCoords")) {
            val f = fixture(); (f.plan.route!!.path("transitLegs")[1] as ObjectNode).remove(field)
            assertNull(mapping(f).verified, field)
        }
    }
    @Test fun `saved path reverse distant interior shortcut and length mismatches are rejected`() {
        for (mode in listOf("reverse", "distant", "shortcut", "length")) {
            val f = fixture(); val bus = f.plan.route!!.path("transitLegs")[1] as ObjectNode
            val points = bus.path("pathCoords").map { it.deepCopy<ObjectNode>() }
            when (mode) {
                "reverse" -> bus.set<com.fasterxml.jackson.databind.JsonNode>("pathCoords", f.mapper.valueToTree(points.reversed()))
                "distant" -> points[1].put("lat", 37.57)
                "shortcut" -> points[1].put("lng", 126.9795)
                "length" -> points.last().put("lng", 126.9845)
            }
            if (mode != "reverse") bus.set<com.fasterxml.jackson.databind.JsonNode>("pathCoords", f.mapper.valueToTree(points))
            assertNull(mapping(f).verified, mode)
        }
    }
    @Test fun `independent vehicle source clocks are preserved and road section ordinal is not stop order`() {
        val m = assertNotNull(mapping().verified); val now = java.time.Instant.parse("2026-10-08T00:00:00Z")
        val v = rideFixtureVehicle(now, now.minusSeconds(4))
        val values = JourneyBusRideMapping.vehicles(m, SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, listOf(v)), now)
        assertEquals(1, values.size); assertEquals(now.minusSeconds(4).toEpochMilli() / 1000.0, values.single().sourceAt)
        assertEquals(now.plusSeconds(11).toEpochMilli() / 1000.0, values.single().validUntil)
        assertFalse(values.single().toString().contains("999999998"))
        assertEquals(501, v.roadSectionOrder) // Never equated to 44 or inferred from lastStnId.
    }
    @Test fun `stale future wrong route missing coordinate duplicate and off shape vehicles are refused`() {
        val m = assertNotNull(mapping().verified); val now = java.time.Instant.parse("2026-10-08T00:00:00Z")
        val original = rideFixtureVehicle(now)
        fun modified(route: String = original.routeId, coordinate: SeoulBusVehicleCoordinate? = original.wgs84Coordinate) =
            SeoulBusVehicleObservation(original.vehicleId, route, original.roadSectionId, original.roadSectionOrder,
                0, false, original.lastStationId, now, now, now.plusSeconds(15), coordinate)
        for (vehicles in listOf(listOf(rideFixtureVehicle(now, now.minusSeconds(15))), listOf(rideFixtureVehicle(now, now.plusSeconds(1))),
            listOf(modified(route = "999999997")), listOf(modified(coordinate = null)), listOf(original, original),
            listOf(modified(coordinate = SeoulBusVehicleCoordinate(37.57,126.98))))) {
            assertTrue(JourneyBusRideMapping.vehicles(m, SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE, vehicles), now).isEmpty())
        }
    }
    @Test fun `unavailable provider status rejects even contradictory nonempty observations`() {
        val m = assertNotNull(mapping().verified); val now = java.time.Instant.parse("2026-10-08T00:00:00Z")
        for (status in SeoulBusVehicleObservationStatus.entries.filter { it != SeoulBusVehicleObservationStatus.AVAILABLE }) {
            assertTrue(JourneyBusRideMapping.vehicles(m, SeoulBusVehicleObservationBatch(status, listOf(rideFixtureVehicle(now))), now).isEmpty())
        }
    }
    @Test fun `disabled adapter and rejected fence do not dispatch any provider request`() {
        val f = fixture(); val automatic = f.service(); automatic.prepare(7,9001,2,f.prepareCommand())
        val provider = mock<SeoulTransitArrivalClient>(); val service = JourneyBusRideService(automatic, provider, f.clock, false)
        val c = JourneyBusBindingCommand(1,1,f.owner,f.digest)
        assertEquals("RIDE_ADAPTER_DISABLED", service.binding(7,9001,2,f.sessionId,c)["status"])
        assertFails { service.binding(7,9001,2,f.sessionId,c.copy(generation = 2)) }
        verifyNoInteractions(provider)
    }
    @Test fun `metadata is bounded cached and vehicle cooldown cannot freshen cached observations`() {
        val f = fixture(); val automatic = f.service(); automatic.prepare(7,9001,2,f.prepareCommand())
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideFixtureShape())
        whenever(provider.getBusVehicleObservations(any())).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES, emptyList()))
        val service = JourneyBusRideService(automatic, provider, f.clock, true); val c = JourneyBusBindingCommand(1,1,f.owner,f.digest)
        assertEquals("VERIFIED", service.binding(7,9001,2,f.sessionId,c)["status"])
        assertEquals("VEHICLE_EVIDENCE_UNAVAILABLE", service.observe(7,9001,2,f.sessionId,c)["status"])
        assertEquals("POLL_BUDGET_EXHAUSTED", service.observe(7,9001,2,f.sessionId,c)["status"])
        verify(provider, times(1)).getBusRouteDiagnosticStops("999999999")
        verify(provider, times(1)).getBusRoutePath("999999999")
        verify(provider, times(1)).getBusVehicleObservations(SeoulBusVehicleQuery("999999999",44,46))
        verifyNoInteractions(f.departure,f.jobs,f.schedules)
    }
    @Test fun `ride endpoint requires authenticated owner generation revision and digest before dispatch`() {
        val f = fixture(); f.service().prepare(7,9001,2,f.prepareCommand())
        val provider = mock<SeoulTransitArrivalClient>(); val command = JourneyBusBindingCommand(1,1,f.owner,f.digest)
        val path = "/api/schedules/9001/automatic-journey/${f.sessionId}/bus-ride/binding"
        fun send(mvc: org.springframework.test.web.servlet.MockMvc, c: JourneyBusBindingCommand) = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json")
                .content(f.mapper.writeValueAsString(c))).andReturn().response.status
        assertEquals(401, send(automaticJourneyFixtureMvc(f,authenticated=false,rideProvider=provider),command))
        val mvc = automaticJourneyFixtureMvc(f,rideProvider=provider)
        for (bad in listOf(command.copy(generation=2),command.copy(routeRevision=2),command.copy(clientOwnerId=f.sessionId),command.copy(routeInputDigest="b".repeat(64)))) {
            assertNotEquals(200,send(mvc,bad))
        }
        verifyNoInteractions(provider)
    }
    @Test fun `route revocation during metadata response cannot return a stale binding`() {
        val f = fixture(); val automatic = f.service(); automatic.prepare(7,9001,2,f.prepareCommand())
        val provider = mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenAnswer {
            f.actor.sessionGeneration = 3; rideFixtureShape()
        }
        assertFails { JourneyBusRideService(automatic,provider,f.clock,true).binding(7,9001,2,f.sessionId,JourneyBusBindingCommand(1,1,f.owner,f.digest)) }
        verify(provider,never()).getBusVehicleObservations(any())
    }
    @Test fun `only a verified immediate same direction continuation opens a bounded departure window`() {
        val base = snapshot(fixture())
        val verified = assertNotNull(JourneyBusRideMapping.resolve(base,rideDepartureFixtureStops(),rideDepartureFixtureShape()).verified)
        assertTrue(verified.departureWindowMeters in 200.0..230.0)
        assertEquals(47,verified.observationEndSequence)
        assertEquals(0.0,assertNotNull(mapping().verified).departureWindowMeters)
        for (stops in listOf(rideDepartureFixtureStops().map { if(it.sequence==47) it.copy(direction="반대") else it },
            rideDepartureFixtureStops().map { if(it.sequence==47) it.copy(arsId=null) else it })) {
            assertEquals(0.0,assertNotNull(JourneyBusRideMapping.resolve(base,stops,rideDepartureFixtureShape()).verified).departureWindowMeters)
        }
    }
    @Test fun `fresh geographically unique departure vehicles have separate scope and original clocks`() {
        val m = assertNotNull(JourneyBusRideMapping.resolve(snapshot(fixture()),rideDepartureFixtureStops(),rideDepartureFixtureShape()).verified)
        val now=java.time.Instant.parse("2026-10-08T00:00:00Z")
        val target=m.projections.last().alongShapeMeters
        fun values(v:SeoulBusVehicleObservation)=JourneyBusRideMapping.vehicles(m,
            SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE,listOf(v)),now)
        val departure=values(rideFixtureVehicle(now,now.minusSeconds(4),target+100)).single()
        assertEquals("AFTER_ALIGHTING",departure.positionScope)
        assertEquals(100.0,assertNotNull(departure.metersPastAlighting),0.01)
        assertEquals(now.minusSeconds(4).toEpochMilli()/1000.0,departure.sourceAt)
        assertEquals(now.plusSeconds(11).toEpochMilli()/1000.0,departure.validUntil)
        assertTrue(values(rideFixtureVehicle(now,alongMeters=target+260)).isEmpty())
        val selected=values(rideFixtureVehicle(now,alongMeters=100.0)).single()
        assertEquals("SELECTED_SEGMENT",selected.positionScope); assertNull(selected.metersPastAlighting)
        assertTrue(JourneyBusRideMapping.vehicles(assertNotNull(mapping().verified),
            SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE,
                listOf(rideFixtureVehicle(now,alongMeters=target+100))),now).isEmpty())
    }
    @Test fun `departure scope cannot bypass duplicates stale wrong route or looping complete shape`() {
        val m=assertNotNull(JourneyBusRideMapping.resolve(snapshot(fixture()),rideDepartureFixtureStops(),rideDepartureFixtureShape()).verified)
        val now=java.time.Instant.parse("2026-10-08T00:00:00Z")
        val at=m.projections.last().alongShapeMeters+100
        val v=rideFixtureVehicle(now,alongMeters=at)
        for(rows in listOf(listOf(v,v),listOf(rideFixtureVehicle(now,now.minusSeconds(15),at))))
            assertTrue(JourneyBusRideMapping.vehicles(m,SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE,rows),now).isEmpty())
        val loop=rideDepartureFixtureShape()+rideDepartureFixtureShape().reversed().drop(1).mapIndexed { i,p -> p.copy(order=826+i) }
        val ambiguous=JourneyBusRideMapping.Verified(m.routeId,m.direction,m.coordinates,m.stops,m.projections,loop,m.catalog,m.departureWindowMeters,47)
        assertTrue(JourneyBusRideMapping.vehicles(ambiguous,SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.AVAILABLE,listOf(v)),now).isEmpty())
    }
    @Test fun `departure query extends one verified stop without extra polls or side effects`() {
        val f=fixture(); val automatic=f.service(); automatic.prepare(7,9001,2,f.prepareCommand())
        val provider=mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideDepartureFixtureStops())
        whenever(provider.getBusRoutePath("999999999")).thenReturn(rideDepartureFixtureShape())
        whenever(provider.getBusVehicleObservations(any())).thenReturn(SeoulBusVehicleObservationBatch(SeoulBusVehicleObservationStatus.NO_VEHICLES,emptyList()))
        val service=JourneyBusRideService(automatic,provider,f.clock,true);val c=JourneyBusBindingCommand(1,1,f.owner,f.digest)
        val binding=service.binding(7,9001,2,f.sessionId,c)
        assertEquals(2,binding["schemaVersion"]); assertTrue(binding["departureWindowMeters"] as Double > 100)
        service.observe(7,9001,2,f.sessionId,c);service.observe(7,9001,2,f.sessionId,c)
        verify(provider,times(1)).getBusVehicleObservations(SeoulBusVehicleQuery("999999999",44,47))
        verifyNoInteractions(f.departure,f.jobs,f.schedules)
    }
}
