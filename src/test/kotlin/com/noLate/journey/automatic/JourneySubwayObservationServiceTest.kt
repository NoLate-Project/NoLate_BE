package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.readValue
import com.noLate.global.error.BusinessException
import com.noLate.journey.application.*
import com.noLate.journey.domain.*
import com.noLate.transit.domain.*
import kotlin.test.*

class JourneySubwayObservationServiceTest {
    private val clock=AutomaticJourneyMutableClock()
    private val f=AutomaticJourneyFixture(clock).also { it.plan=it.plan.copy(route=syntheticSavedSubwayRoute(it.mapper,transfer=true)) }
    private val automatic=f.service()
    private val ids=listOf("9990000001","9990000002","9990000003")
    private var reads=0; private var fetches=0
    private val command get()=JourneySubwayObservationCommand(1,1,f.owner,f.digest,1)
    private fun service(change:()->Unit={}):JourneySubwayObservationService {
        val checker=SavedJourneySubwayObservationCheck(f.mapper,null,
            { _,_ -> JourneySubwayTrainStationMatcher.Pattern(SeoulSubwayObservationLine.LINE_2,0,ids.last(),0,ids,0,2) },
            { fetches++;change();SeoulSubwayTrainObservationBatch(SeoulSubwayTrainStatus.AVAILABLE,listOf(
                SeoulSubwayTrainObservation("00123",SeoulSubwayObservationLine.LINE_2,ids[1],ids.last(),0,1,0,false,
                    clock.now.minusSeconds(1),clock.now,clock.now.plusSeconds(29)))) },clock,true)
        return JourneySubwayObservationService({ member,schedule,login,session,c ->
            reads++;automatic.requireSubwayObservationFence(member,schedule,login,session,c)
        },checker)
    }
    private fun observe(service:JourneySubwayObservationService,c:JourneySubwayObservationCommand=command)=
        service.observe(7,9001,2,f.sessionId,c)
    private fun prepare(){automatic.prepare(7,9001,2,f.prepareCommand())}

    @Test fun `native input carries scoped raw namespace and independent trains without claiming passenger position`() {
        prepare();val s=service()
        val result=s.input(7,9001,2,f.sessionId,command)
        assertEquals(2,result["schemaVersion"])
        assertEquals(false,result["navigationReady"]);assertEquals(false,result["passengerBoardingConfirmed"])
        assertNull(result["passengerPosition"]);assertNull(result["passengerRemainingStops"])
        val input=result["nativeInput"] as Map<*,*>
        val stations=input["stations"] as List<*>
        assertEquals("222",(stations.first() as Map<*,*>)["routingStationId"])
        assertEquals(ids.first(),(stations.first() as Map<*,*>)["stationId"])
        val trains=input["trains"] as List<*>
        assertEquals("00123",(trains.single() as Map<*,*>)["id"])
        assertEquals(1,(trains.single() as Map<*,*>)["stopIndex"])
        val legacy=observe(s)
        assertFalse(legacy.containsKey("nativeInput"));assertFalse(f.mapper.writeValueAsString(legacy).contains("00123"))
        assertEquals(4,reads);assertEquals(2,fetches)
    }
    @Test fun `native input cannot bypass concurrent cancellation fence`() {
        prepare();assertFailsWith<BusinessException> {
            service { automatic.stop(7,9001,2,f.sessionId,f.stopCommand()) }.input(7,9001,2,f.sessionId,command)
        }
        assertEquals(1,fetches);assertEquals(2,reads)
    }

    @Test fun `owned ARMED and ACTIVE receipts are train only and do not activate or advance navigation`() {
        prepare();val s=service()
        for(active in listOf(false,true)) {
            if(active)automatic.departure(7,9001,2,f.sessionId,f.event())
            val result=observe(s)
            assertEquals("TRAIN_REFERENCES_ONLY",result["status"]);assertEquals(1,result["matchingTrainCount"])
            assertEquals(f.sessionId,result["sessionId"]);assertEquals(f.digest,result["routeInputDigest"])
            assertEquals(false,result["navigationReady"]);assertEquals(false,result["passengerBoardingConfirmed"])
            assertNull(result["passengerPosition"]);assertNull(result["passengerRemainingStops"])
            val json=f.mapper.writeValueAsString(result)
            assertFalse(json.contains("00123"));assertFalse(json.contains(ids[1]))
        }
        assertEquals(4,reads);assertEquals(2,fetches);assertEquals(1,f.departureCount.get())
    }
    @Test fun `later subway occurrence uses exact command index and unchanged saved route`() {
        prepare();assertEquals(3,observe(service(),command.copy(subwayLegIndex=3))["subwayLegIndex"])
        assertEquals(1,fetches)
    }
    @Test fun `wrong generation revision owner digest leg and session never issue provider calls`() {
        prepare();val s=service()
        for(c in listOf(command.copy(generation=2),command.copy(routeRevision=2),
            command.copy(clientOwnerId="cc67598a-6c84-4d0d-8d25-134e0c01c12e"),command.copy(routeInputDigest="b".repeat(64)),
            command.copy(subwayLegIndex=0),command.copy(subwayLegIndex=2),command.copy(subwayLegIndex=29)))
            assertFailsWith<BusinessException>{observe(s,c)}
        assertFailsWith<BusinessException>{s.observe(7,9001,2,"cc67598a-6c84-4d0d-8d25-134e0c01c12e",command)}
        assertEquals(0,fetches)
    }
    @Test fun `cancel during fetch discards train result`() {
        prepare();assertFailsWith<BusinessException>{observe(service { automatic.stop(7,9001,2,f.sessionId,f.stopCommand()) })}
        assertEquals(1,fetches);assertEquals(2,reads)
    }
    @Test fun `replacement during fetch discards old session even with same route`() {
        prepare();assertFailsWith<BusinessException>{observe(service {
            automatic.stop(7,9001,2,f.sessionId,f.stopCommand())
            automatic.prepare(7,9001,2,f.prepareCommand().copy(requestId="cc67598a-6c84-4d0d-8d25-134e0c01c12e"))
        })};assertEquals(1,fetches);assertEquals(2,reads)
    }
    @Test fun `logout or route edit during fetch discards train result`() {
        prepare();assertFailsWith<BusinessException>{observe(service { f.actor.sessionGeneration=3 })}
        assertEquals(1,fetches);assertEquals(2,reads)
    }
    @Test fun `saved route edit during fetch discards train result`() {
        prepare();assertFailsWith<BusinessException>{observe(service {
            (f.plan.route as com.fasterxml.jackson.databind.node.ObjectNode).put("edited",true)
        })};assertEquals(1,fetches);assertEquals(2,reads)
    }
    @Test fun `fixed session expiry during fetch cannot be extended by new observations`() {
        prepare();assertFailsWith<BusinessException>{observe(service { clock.now=clock.now.plusSeconds(600) })}
        assertEquals(1,fetches);assertEquals(2,reads)
    }
    @Test fun `stopped or expired session before fetch makes zero provider calls`() {
        prepare();automatic.stop(7,9001,2,f.sessionId,f.stopCommand())
        assertFailsWith<BusinessException>{observe(service())};assertEquals(0,fetches)
    }
    @Test fun `subway leg index accepts only explicit odd integer occurrence`() {
        val root=f.mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(command)
        for(value in listOf("null","true","\"1\"","1.0","0","2","30","2147483648","[]","{}")) {
            val changed=root.deepCopy();changed.set<com.fasterxml.jackson.databind.JsonNode>("subwayLegIndex",f.mapper.readTree(value))
            assertFails { f.mapper.readValue<JourneySubwayObservationCommand>(changed.toString()) }
        }
        root.remove("subwayLegIndex");assertFails { f.mapper.readValue<JourneySubwayObservationCommand>(root.toString()) }
        for(index in listOf(1,3,29))assertEquals(index,f.mapper.readValue<JourneySubwayObservationCommand>(
            f.mapper.writeValueAsString(command.copy(subwayLegIndex=index))).subwayLegIndex)
    }
}
