package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.journey.domain.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

class MixedWalkingAdmissionTest {
    @Test fun `subway original station namespace survives saved preparation and cannot be substituted`() {
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:subway-admission-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            connectorWalking=true,durablePreparations=true,retainedNavigation=true,subwayFixture=true).use { f ->
            val walking=f.service.issue(7,9001,2,1)
            val input=nativeInput(f,walking)
            assertThat(input.path("legs")[1].path("stops")[0].path("routingStationId").asText()).isEqualTo("222")
            val json=input.toString()
            val prepared=f.automaticService().prepareSaved(7,9001,2,command(f,walking,json))
            assertThat(prepared.savedRouteValidated).isTrue()
            (input.path("legs")[1].path("stops")[0] as ObjectNode).put("routingStationId","999")
            val changed=input.toString()
            val digest=MessageDigest.getInstance("SHA-256").digest(changed.toByteArray()).joinToString(""){"%02x".format(it)}
            assertThatThrownBy{SavedJourneyInputValidator(f.fixture.mapper).validate(f.fixture.plan,changed,digest,1,walking)}
                .isInstanceOf(RuntimeException::class.java)
        }
    }
    @Test fun `v4 two hour lifetime survives restart but does not renew admission provider calls or validation leases`() {
        val clock=AutomaticJourneyMutableClock()
        val url="jdbc:h2:mem:extended-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"
        val p=WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,
            retainedNavigation=true,extendedNavigation=true).use { f ->
            val w=f.service.issue(7,9001,2,1)
            assertThat(w.schemaVersion).isEqualTo(4)
            assertThat(w.navigationExpiresAtMs).isEqualTo(w.expiresAtMs+7_200_000)
            assertThatThrownBy { f.tx { f.fixture.service(walkingSnapshots=f.service).prepareSaved(7,9001,2,command(f,w)) } }
                .isInstanceOf(RuntimeException::class.java)
            f.automaticService().prepareSaved(7,9001,2,command(f,w)).also {
                assertThat(it.expiresAt).isEqualTo(it.preparedAt.plusSeconds(7200))
            }
        }
        val c=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
        clock.now=p.preparedAt.plusSeconds(3600)
        WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,
            retainedNavigation=true,extendedNavigation=true).use { f ->
            val service=f.automaticService()
            val lease=service.validate(7,9001,2,p.sessionId,c)
            assertThat(lease.state).isEqualTo(AutomaticJourneyState.ARMED)
            assertThat(lease.validUntil).isEqualTo(clock.now.plusSeconds(30))
            assertThatThrownBy { f.tx { f.fixture.service(walkingSnapshots=f.service).validate(7,9001,2,p.sessionId,c) } }
                .isInstanceOf(RuntimeException::class.java)
            assertThatThrownBy { f.service.current(7,9001,2) }.hasMessage("EXPIRED")
            assertThat(f.calls).isZero()
            assertThat(service.current(7,9001,2,p.clientOwnerId)!!.expiresAt).isEqualTo(p.expiresAt)
            assertThat(f.tx { f.repository.purgeExpired(clock.now.toEpochMilli()) }).isZero()
        }
        WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,
            retainedNavigation=true).use { f ->
            assertThatThrownBy { f.automaticService().validate(7,9001,2,p.sessionId,c) }.hasMessage("FEATURE_DISABLED")
        }
        WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,
            retainedNavigation=true,extendedNavigation=true).use { f ->
            clock.now=p.expiresAt
            assertThatThrownBy { f.automaticService().validate(7,9001,2,p.sessionId,c) }.isInstanceOf(RuntimeException::class.java)
        }
    }
    @Test fun `authenticated device v4 retains bounded two hour navigation while device gate defaults closed`() {
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:device-v4-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            connectorWalking=true,durablePreparations=true,retainedNavigation=true,extendedNavigation=true).use { f ->
            val walking=f.service.issue(7,9001,2,1)
            val device=command(f,walking).copy(execution=AutomaticJourneyExecution.DEVICE)
            assertThatThrownBy { f.automaticService().prepareSaved(7,9001,2,device) }
                .isInstanceOf(RuntimeException::class.java)
            val prepared=f.automaticService(deviceEnabled=true).prepareSaved(7,9001,2,device)
            assertThat(prepared.execution).isEqualTo(AutomaticJourneyExecution.DEVICE)
            assertThat(prepared.expiresAt).isEqualTo(prepared.preparedAt.plusSeconds(7200))
        }
    }
    @Test fun `v4 long navigation still rejects cancellation replacement owner login route and backwards clock`() {
        for(mode in listOf("cancel","replacement","owner","login","route","clock")) {
            val clock=AutomaticJourneyMutableClock()
            WalkingSnapshotDatabaseFixture("jdbc:h2:mem:extended-$mode-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock,
                connectorWalking=true,retainedNavigation=true,extendedNavigation=true).use { f ->
                val w=f.service.issue(7,9001,2,1);val service=f.automaticService()
                val p=service.prepareSaved(7,9001,2,command(f,w))
                if(mode=="replacement") f.service.issue(7,9001,2,1)
                clock.now=clock.now.plusSeconds(if(mode=="clock") -1 else 3600)
                if(mode=="cancel") f.service.cancel(7,9001,2,w.snapshotId)
                if(mode=="owner") { f.visible=false;f.fixture.accessAllowed=false }
                if(mode=="login") { f.login++;f.fixture.actor.sessionGeneration++ }
                if(mode=="route") f.revision++
                val c=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
                assertThatThrownBy { service.validate(7,9001,2,p.sessionId,c) }.`as`(mode).isInstanceOf(RuntimeException::class.java)
            }
        }
    }
    @Test fun `retained preparation survives database restart while disabling its gate revokes use and cleanup stays bounded`() {
        val clock=AutomaticJourneyMutableClock()
        val url="jdbc:h2:mem:retained-restart-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"
        val p=WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,retainedNavigation=true).use { f ->
            val w=f.service.issue(7,9001,2,1)
            f.automaticService().prepareSaved(7,9001,2,command(f,w))
        }
        val c=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
        clock.now=p.preparedAt.plusSeconds(301)
        WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,retainedNavigation=true).use { f ->
            assertThat(f.automaticService().validate(7,9001,2,p.sessionId,c).state).isEqualTo(AutomaticJourneyState.ARMED)
            assertThat(f.calls).isZero()
        }
        WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true).use { f ->
            assertThatThrownBy{f.automaticService().validate(7,9001,2,p.sessionId,c)}.hasMessage("FEATURE_DISABLED")
        }
        WalkingSnapshotDatabaseFixture(url,clock,connectorWalking=true,durablePreparations=true,retainedNavigation=true).use { f ->
            clock.now=p.preparedAt.plusSeconds(900)
            assertThat(f.tx{f.repository.purgeExpired(clock.now.toEpochMilli())}).isEqualTo(1)
            assertThatThrownBy{f.automaticService().validate(7,9001,2,p.sessionId,c)}.isInstanceOf(RuntimeException::class.java)
        }
    }
    @Test fun `retained navigation never renews admission but an already owned preparation survives the planning TTL`() {
        val clock=AutomaticJourneyMutableClock()
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:retained-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock,
            connectorWalking=true,retainedNavigation=true).use { f ->
            val w=f.service.issue(7,9001,2,1);val service=f.automaticService()
            assertThat(w.schemaVersion).isEqualTo(3);assertThat(w.navigationExpiresAtMs).isEqualTo(w.expiresAtMs+600_000)
            val p=service.prepareSaved(7,9001,2,command(f,w))
            assertThat(p.expiresAt).isEqualTo(p.preparedAt.plusSeconds(600))
            val c=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
            clock.now=clock.now.plusSeconds(301)
            assertThatThrownBy{f.service.current(7,9001,2)}.hasMessage("EXPIRED")
            assertThatThrownBy{service.prepareSaved(7,9001,2,command(f,w))}.isInstanceOf(RuntimeException::class.java)
            assertThat(f.tx{f.repository.purgeExpired(clock.now.toEpochMilli())}).isZero()
            assertThat(service.validate(7,9001,2,p.sessionId,c).state).isEqualTo(AutomaticJourneyState.ARMED)
            assertThat(f.calls).isEqualTo(2)
            f.service.cancel(7,9001,2,w.snapshotId)
            assertThatThrownBy{service.validate(7,9001,2,p.sessionId,c)}.isInstanceOf(RuntimeException::class.java)
        }
    }
    @Test fun `retained navigation rejects route replacement login change backwards clock and its own fixed expiry`() {
        for(mode in listOf("route","login","clock","expiry","replacement")) {
            val clock=AutomaticJourneyMutableClock()
            WalkingSnapshotDatabaseFixture("jdbc:h2:mem:retained-$mode-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock,
                connectorWalking=true,retainedNavigation=true).use { f ->
                val w=f.service.issue(7,9001,2,1);val service=f.automaticService();val p=service.prepareSaved(7,9001,2,command(f,w))
                val c=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
                if(mode=="replacement")f.service.issue(7,9001,2,1)
                clock.now=clock.now.plusSeconds(if(mode=="expiry")600 else if(mode=="clock")-1 else 301)
                if(mode=="route")f.revision++
                if(mode=="login"){f.login++;f.fixture.actor.sessionGeneration++}
                assertThatThrownBy{service.validate(7,9001,2,p.sessionId,c)}.`as`(mode).isInstanceOf(RuntimeException::class.java)
            }
        }
    }
    private fun fixture()=WalkingSnapshotDatabaseFixture("jdbc:h2:mem:mixed-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",connectorWalking=true)
    @Test fun `all three server walks remain separate and authorize both bus occurrences with immutable snapshot`() {
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:transfer-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",transferFixture=true).use { f ->
            val w=f.service.issue(7,9001,2,1)
            assertThat(w.schemaVersion).isEqualTo(2);assertThat(w.walks.map {it.legIndex}).containsExactly(0,2,4)
            assertThat(f.calls).isEqualTo(3);assertThat(w.navigationReady).isFalse()
            val reloaded=f.service.current(7,9001,2);assertThat(reloaded).isEqualTo(w);assertThat(f.calls).isEqualTo(3)
            val service=f.automaticService();val p=service.prepareSaved(7,9001,2,command(f,w))
            val ride=MixedWalkingRideFixture(f.fixture.clock,true);val bus=JourneyBusRideService(service,ride.provider,f.fixture.clock,true)
            val c=JourneyBusBindingCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest,1)
            assertThat(bus.binding(7,9001,2,p.sessionId,c)).containsEntry("status","VERIFIED").containsEntry("busLegIndex",1)
            assertThat(bus.binding(7,9001,2,p.sessionId,c.copy(busLegIndex=3))).containsEntry("status","VERIFIED").containsEntry("routeId","999999997")
            f.service.cancel(7,9001,2,w.snapshotId)
            assertThatThrownBy{bus.binding(7,9001,2,p.sessionId,c.copy(busLegIndex=3))}.isInstanceOf(RuntimeException::class.java)
            assertThat(f.fixture.departureCount.get()).isZero()
        }
    }
    @Test fun `durable mixed preparation survives fixture restart and cancelled snapshot still permits stop`() {
        val url="jdbc:h2:mem:mixed-restart-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"
        val prepared=WalkingSnapshotDatabaseFixture(url,connectorWalking=true,durablePreparations=true).use { f ->
            val w=f.service.issue(7,9001,2,1)
            f.automaticService().prepareSaved(7,9001,2,command(f,w)) to w.snapshotId
        }
        val p=prepared.first;val c=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
        WalkingSnapshotDatabaseFixture(url,connectorWalking=true,durablePreparations=true).use { f ->
            val service=f.automaticService()
            assertThat(service.validate(7,9001,2,p.sessionId,c).state).isEqualTo(AutomaticJourneyState.ARMED)
            f.service.cancel(7,9001,2,prepared.second)
            assertThat(service.stop(7,9001,2,p.sessionId,c).state).isEqualTo(AutomaticJourneyState.STOPPED)
        }
        WalkingSnapshotDatabaseFixture(url,connectorWalking=true,durablePreparations=true).use { f ->
            assertThat(f.automaticService().reconcile(7,9001,2,p.sessionId,c).state).isEqualTo(AutomaticJourneyState.STOPPED)
        }
    }
    @Test fun `server context is fresh metadata only and never fetches provider`() {
        fixture().use { f ->
            f.revision=4
            assertThat(f.service.context(7,9001,2)).containsEntry("routeRevision",4L)
            assertThat(f.calls).isZero()
            assertThatThrownBy{f.service.context(8,9001,2)}.hasMessage("OWNER_CHANGED")
        }
    }
    @Test fun `real prepare controller emits ISO instants consumable by native and FE`() {
        fixture().use { f ->
            val w=f.service.issue(7,9001,2,1)
            val token=JourneyFixtureAuthentication.jwt.createAccessToken(7,"Synthetic fixture",2)
            val result=walkingSnapshotMvc(f).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/schedules/9001/automatic-journey/prepare-saved").header("Authorization","Bearer $token")
                .contentType("application/json").content(f.fixture.mapper.writeValueAsString(command(f,w))))
                .andReturn().response
            assertThat(result.status).isEqualTo(200)
            val data=f.fixture.mapper.readTree(result.contentAsString).path("data")
            assertThat(data.path("preparedAt").isTextual).isTrue()
            assertThat(java.time.Instant.parse(data.path("expiresAt").asText()).toEpochMilli()).isEqualTo(w.expiresAtMs)
            assertThat(data.path("savedRouteValidated").asBoolean()).isTrue()
        }
    }
    @Test fun `mixed prepare binds exact saved input snapshot version id and shorter TTL with no departure`() {
        fixture().use { f ->
            f.revision=4;val w=f.service.issue(7,9001,2,4);val service=f.automaticService()
            val command=command(f,w);val p=service.prepareSaved(7,9001,2,command)
            assertThat(p.savedRouteValidated).isTrue();assertThat(p.routeRevision).isEqualTo(4)
            assertThat(p.expiresAt.toEpochMilli()).isEqualTo(w.expiresAtMs)
            assertThat(f.fixture.rows[p.sessionId]!!.walkingSnapshotId).isEqualTo(w.snapshotId)
            assertThat(f.fixture.departureCount.get()).isZero();assertThat(f.calls).isEqualTo(2)
            assertThat(service.prepareSaved(7,9001,2,command)).isEqualTo(p)
            service.validate(7,9001,2,p.sessionId,AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest))
            assertThat(f.calls).isEqualTo(2)
        }
    }
    @Test fun `replacement cancellation revision expiry revoke observation admission without provider refetch`() {
        for(mode in listOf("replace","cancel","revision","expire"))fixture().use { f ->
            val w=f.service.issue(7,9001,2,1);val service=f.automaticService();val p=service.prepareSaved(7,9001,2,command(f,w))
            when(mode){"replace"->f.service.issue(7,9001,2,1);"cancel"->f.service.cancel(7,9001,2,w.snapshotId)
                "revision"->f.revision=2;"expire"->f.tx{f.repository.purgeExpired(w.expiresAtMs)}}
            assertThatThrownBy{service.validate(7,9001,2,p.sessionId,
                AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest))}.isInstanceOf(RuntimeException::class.java)
            // Explicit stop remains possible after snapshot revocation, so cleanup is not deadlocked.
            assertThat(service.stop(7,9001,2,p.sessionId,
                AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)).state).isEqualTo(AutomaticJourneyState.STOPPED)
            assertThat(f.calls).isEqualTo(if(mode=="replace")4 else 2)
        }
    }
    @Test fun `client cannot change separate snapshot or promote saved bus evidence even with matching byte digest`() {
        fixture().use { f ->
            val w=f.service.issue(7,9001,2,1);val service=f.automaticService();val valid=nativeInput(f,w)
            for(mode in listOf("id","point","provider","bus","unknown","ready")){
                val n=valid.deepCopy();val snapshot=n.path("walkingSnapshot") as ObjectNode
                when(mode){"id"->snapshot.put("snapshotId",UUID.randomUUID().toString())
                    "point"->(snapshot.path("walks")[0].path("points")[0] as ObjectNode).put("lat",0)
                    "provider"->snapshot.put("walkProvider","odsay")
                    "bus"->(n.path("legs")[1] as ObjectNode).put("providerRouteId","invented")
                    "unknown"->snapshot.put("extra",true);"ready"->snapshot.put("navigationReady",true)}
                assertThatThrownBy{service.prepareSaved(7,9001,2,command(f,w,n.toString()))}.isInstanceOf(RuntimeException::class.java)
            }
            assertThat(f.fixture.prepareCount.get()).isZero()
        }
    }
    @Test fun `mixed snapshot binding admits official bus fixture and cancellation fences cached mapping`() {
        fixture().use { f ->
            val w=f.service.issue(7,9001,2,1);val service=f.automaticService()
            val p=service.prepareSaved(7,9001,2,command(f,w));val ride=MixedWalkingRideFixture(f.fixture.clock)
            val bus=JourneyBusRideService(service,ride.provider,f.fixture.clock,true)
            val c=JourneyBusBindingCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)
            assertThat(bus.binding(7,9001,2,p.sessionId,c)["status"]).isEqualTo("VERIFIED")
            ride.start();bus.observe(7,9001,2,p.sessionId,c)
            assertThat(ride.requests).isEqualTo(1)
            f.service.cancel(7,9001,2,w.snapshotId)
            assertThatThrownBy{bus.observe(7,9001,2,p.sessionId,c)}.isInstanceOf(RuntimeException::class.java)
            assertThat(ride.requests).isEqualTo(1)
        }
    }
    private fun command(f:WalkingSnapshotDatabaseFixture,w:WalkingSnapshotContract,json:String=nativeInput(f,w).toString())=
        AutomaticJourneySavedPrepareCommand(f.fixture.sessionId,f.fixture.owner,
            MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString(""){"%02x".format(it)},AutomaticJourneyExecution.SIMULATOR,json)
}

/** Test-only canonical v1 projection (no display extension). Keeps original saved geometry untouched. */
internal fun nativeInput(f:WalkingSnapshotDatabaseFixture,w:WalkingSnapshotContract):ObjectNode {
    fun coordinate(n:JsonNode):Any?=if(n.path("lat").isNumber&&n.path("lng").isNumber)
        linkedMapOf("latitude" to n.path("lat").asDouble(),"longitude" to n.path("lng").asDouble()) else null
    fun text(n:JsonNode,k:String)=n.path(k).takeIf{it.isTextual}?.asText()
    fun integer(n:JsonNode,k:String)=n.path(k).takeIf{it.isNumber}?.asLong()
    val m=f.fixture.mapper;val plan=f.fixture.plan;val r=plan.route!!
    return m.valueToTree(linkedMapOf("schemaVersion" to 1,"routeRevision" to w.routeRevision,"context" to "SAVED_ROUTE",
        "provider" to r.path("provider").asText(),"source" to "api","reliability" to r.path("routeReliability").asText(),
        "plausibility" to "normal","serviceState" to "operating","origin" to coordinate(m.valueToTree(plan.origin)),
        "destination" to coordinate(m.valueToTree(plan.destination)),"walkingSnapshot" to w,
        "legs" to r.path("transitLegs").mapIndexed { i,l ->val e=l.path("navigationEvidence")
            linkedMapOf("index" to i,"kind" to l.path("kind").asText(),"geometrySource" to l.path("pathGeometrySource").asText(),
                "exact" to l.path("pathCoordsIsExact").asBoolean(),"geometryInputComplete" to e.path("geometryInputComplete").asBoolean(),
                "coordinates" to l.path("pathCoords").map(::coordinate),"start" to coordinate(l.path("startCoord")),"end" to coordinate(l.path("endCoord")),
                "providerRouteId" to text(l,"providerRouteId"),"localRouteId" to text(l,"localRouteId"),
                "directionName" to text(e,"directionName"),"directionCode" to text(e,"directionCode"),
                "serviceAvailable" to l.path("serviceAvailable").takeIf{it.isBoolean}?.booleanValue(),
                "declaredStationCount" to integer(e,"declaredStationCount"),"stopEvidenceProvider" to text(e,"provider"),
                "stopEvidenceVersion" to integer(e,"version"),"stops" to e.path("stopOccurrences").map { s ->
                    linkedMapOf<String,Any?>("sourceIndex" to integer(s,"sourceIndex"),"providerSequence" to integer(s,"providerSequence"),
                        "name" to text(s,"name"),"code" to text(s,"code"),"coordinate" to coordinate(s.path("coord"))).also { row ->
                        if(l.path("kind").asText()=="SUBWAY")text(s,"routingStationID")?.let{row["routingStationId"]=it}
                    }}) }))
}
