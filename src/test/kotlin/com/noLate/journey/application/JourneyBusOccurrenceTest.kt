package com.noLate.journey.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.automatic.*
import com.noLate.journey.domain.JourneyBusBindingCommand
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.*

class JourneyBusOccurrenceTest {
    @Test fun `next arrival uses its own occurrence and refuses previous bus query before I O`() {
        val f=fixture();val automatic=f.service();automatic.prepare(7,9001,2,f.prepareCommand())
        val provider=mock<com.noLate.transit.application.TransitArrivalService>()
        whenever(provider.getBusArrivals(anyOrNull(),anyOrNull(),anyOrNull(),anyOrNull(),anyOrNull(),any(),any(),anyOrNull())).thenReturn(emptyList())
        val service=JourneyBusArrivalService(automatic,provider,f.clock)
        val fence=JourneyBusBindingCommand(1,1,f.owner,f.digest,3)
        val query=requireNotNull(service.binding(7,9001,2,f.sessionId,fence).mapping.query)
        assertEquals(3,query.legIndex);assertEquals("999999997",query.providerRouteId)
        val command=com.noLate.journey.domain.JourneyBusArrivalCommand(1,1,f.owner,f.digest,query)
        assertEquals("NO_ARRIVAL",service.observe(7,9001,2,f.sessionId,command).status)
        clearInvocations(provider)
        assertFails{service.observe(7,9001,2,f.sessionId,command.copy(query=query.copy(legIndex=1)))}
        assertFails{service.observe(7,9001,2,f.sessionId,command.copy(query=query.copy(providerRouteId="999999999")))}
        assertFails{service.observe(7,9001,2,f.sessionId,command.copy(query=query.copy(legIndex=5)))}
        verifyNoInteractions(provider);verifyNoInteractions(f.departure,f.jobs,f.schedules)
    }
    @Test fun `next arrival response is discarded after login revocation and query index is strict JSON`() {
        val f=fixture();val automatic=f.service();automatic.prepare(7,9001,2,f.prepareCommand())
        val provider=mock<com.noLate.transit.application.TransitArrivalService>()
        val service=JourneyBusArrivalService(automatic,provider,f.clock)
        val query=requireNotNull(service.binding(7,9001,2,f.sessionId,JourneyBusBindingCommand(1,1,f.owner,f.digest,3)).mapping.query)
        val json=f.mapper.writeValueAsString(query)
        for(value in listOf("3.0","\"3\"","true","2","31","9223372036854775808"))assertFails{
            f.mapper.readValue(json.replace("\"legIndex\":3","\"legIndex\":$value"),com.noLate.journey.domain.JourneyBusArrivalQuery::class.java)
        }
        whenever(provider.getBusArrivals(anyOrNull(),anyOrNull(),anyOrNull(),anyOrNull(),anyOrNull(),any(),any(),anyOrNull())).thenAnswer{
            f.actor.sessionGeneration=3;emptyList<com.noLate.transit.domain.TransitArrivalDto>()
        }
        assertFails{service.observe(7,9001,2,f.sessionId,com.noLate.journey.domain.JourneyBusArrivalCommand(1,1,f.owner,f.digest,query))}
    }
    private fun fixture()=AutomaticJourneyFixture().also { f ->
        f.useRideGuidancePlan()
        val root=f.plan.route!! as ObjectNode
        val legs=root.path("transitLegs") as ArrayNode
        val next=legs[1].deepCopy<ObjectNode>()
        next.put("localRouteId","999999997").put("lineName","합성 다음 버스")
        (next.path("navigationEvidence").path("busIdentifiers") as ObjectNode).put("localRouteId","999999997")
        legs.add(next);legs.add(legs[2].deepCopy<ObjectNode>())
    }
    @Test fun `selected occurrence resolves its own raw namespace and independently cached mapping`() {
        val f=fixture();val snapshot=JourneyRouteSnapshot.from(f.plan,f.mapper)
        assertEquals("999999999",JourneyBusRouteBindingResolver().resolve(snapshot,1).query?.providerRouteId)
        assertEquals("999999997",JourneyBusRouteBindingResolver().resolve(snapshot,3).query?.providerRouteId)
        val automatic=f.service();automatic.prepare(7,9001,2,f.prepareCommand())
        val provider=mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999999")).thenReturn(rideFixtureStops())
        whenever(provider.getBusRouteDiagnosticStops("999999997")).thenReturn(rideFixtureStops().map { it.copy(routeId="999999997") })
        whenever(provider.getBusRoutePath(any())).thenReturn(rideFixtureShape())
        val service=JourneyBusRideService(automatic,provider,f.clock,true)
        val command=JourneyBusBindingCommand(1,1,f.owner,f.digest,1)
        assertEquals("999999999",service.binding(7,9001,2,f.sessionId,command)["routeId"])
        val next=service.binding(7,9001,2,f.sessionId,command.copy(busLegIndex=3))
        assertEquals(3,next["schemaVersion"]);assertEquals(3,next["busLegIndex"]);assertEquals("999999997",next["routeId"])
        service.binding(7,9001,2,f.sessionId,command.copy(busLegIndex=3))
        verify(provider,times(1)).getBusRouteDiagnosticStops("999999999")
        verify(provider,times(1)).getBusRouteDiagnosticStops("999999997")
        verify(provider,times(1)).getBusRoutePath("999999999")
        verify(provider,times(1)).getBusRoutePath("999999997")
        verifyNoInteractions(f.departure,f.jobs,f.schedules)
    }
    @Test fun `invalid next occurrence topology is refused before provider I O`() {
        for (index in listOf(-1,0,2,4,5,31)) {
            val f=fixture();val automatic=f.service();automatic.prepare(7,9001,2,f.prepareCommand())
            val provider=mock<SeoulTransitArrivalClient>()
            assertFails { JourneyBusRideService(automatic,provider,f.clock,true).binding(7,9001,2,f.sessionId,
                JourneyBusBindingCommand(1,1,f.owner,f.digest,index)) }
            verifyNoInteractions(provider)
        }
    }
    @Test fun `next occurrence is fenced again after provider response`() {
        val f=fixture();val automatic=f.service();automatic.prepare(7,9001,2,f.prepareCommand())
        val provider=mock<SeoulTransitArrivalClient>()
        whenever(provider.getBusRouteDiagnosticStops("999999997")).thenReturn(rideFixtureStops().map { it.copy(routeId="999999997") })
        whenever(provider.getBusRoutePath("999999997")).thenAnswer { f.actor.sessionGeneration=3;rideFixtureShape() }
        assertFails { JourneyBusRideService(automatic,provider,f.clock,true).binding(7,9001,2,f.sessionId,
            JourneyBusBindingCommand(1,1,f.owner,f.digest,3)) }
        verify(provider,never()).getBusVehicleObservations(any())
    }
    @Test fun `decimal string boolean overflow and invalid offset never coerce into a bus occurrence`() {
        val f=fixture()
        for (value in listOf("3.0","\"3\"","true","-1","0","2","30","9223372036854775808")) {
            assertFails { f.mapper.readValue("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","busLegIndex":$value}""",JourneyBusBindingCommand::class.java) }
        }
        assertEquals(3,f.mapper.readValue("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","busLegIndex":3}""",JourneyBusBindingCommand::class.java).selectedBusLegIndex())
    }
}
