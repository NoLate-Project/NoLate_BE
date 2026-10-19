package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.journey.domain.*
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Actual mixed walking admission/Activity controller/DB; never a Firebase or device receipt. */
class MixedWalkingActivityDeliveryTest {
    private fun fixture(url: String, clock: AutomaticJourneyMutableClock) = WalkingSnapshotDatabaseFixture(url, clock,
        durablePreparations=true, transferFixture=true, retainedNavigation=true, extendedNavigation=true)
    private fun active(f: WalkingSnapshotDatabaseFixture): Pair<AutomaticJourneyPreparationDto,String> {
        val w=f.service.issue(7,9001,2,1);val json=nativeInput(f,w).toString()
        val digest=MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString(""){"%02x".format(it)}
        val service=f.automaticService()
        val p=service.prepareSaved(7,9001,2,AutomaticJourneySavedPrepareCommand(f.fixture.sessionId,f.fixture.owner,digest,
            AutomaticJourneyExecution.SIMULATOR,json))
        service.departure(7,9001,2,p.sessionId,AutomaticJourneyDepartureCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest,
            "${p.sessionId}:${p.generation}:${p.routeRevision}:AUTO_DEPARTURE",1,"AUTO_DEPARTURE",f.fixture.clock.instant(),AutomaticJourneyExecution.SIMULATOR))
        return p to w.snapshotId
    }
    private fun body(f: WalkingSnapshotDatabaseFixture,p:AutomaticJourneyPreparationDto,index:Int=0):ObjectNode {
        val now=f.fixture.clock.instant().toEpochMilli()/1000.0
        val c=f.fixture.mapper.createObjectNode().put("schemaVersion",if(index==0)1 else 6)
            .put("sessionId",p.sessionId).put("scheduleId","9001").put("generation",p.generation).put("routeRevision",p.routeRevision)
            .put("routeInputDigest",p.routeInputDigest).put("sequence",1).put("status","ACTIVE").put("quality","GOOD")
            .put("phase",if(index==0)"WALKING_TO_STOP" else "FINAL_WALK")
            .put("instruction",if(index==0)"승차 정류장으로 이동 중" else "목적지로 도보 이동 중")
            .put("activeLegIndex",index).put("walkProgress",0.3).put("boardingConfirmed",false)
            .put("observedAt",now).put("validUntil",now+15).put("arrivalStatus","UNAVAILABLE").putNull("arrival")
        return f.fixture.mapper.createObjectNode().put("generation",p.generation).put("routeRevision",p.routeRevision)
            .put("clientOwnerId",p.clientOwnerId).put("routeInputDigest",p.routeInputDigest).put("activityId",p.sessionId)
            .put("fcmRegistrationToken",MixedWalkingActivityFixture.FCM).put("liveActivityToken",MixedWalkingActivityFixture.TOKEN)
            .put("timestamp",f.fixture.clock.instant().epochSecond).set<ObjectNode>("contentState",c)
    }
    private fun stop(p:AutomaticJourneyPreparationDto)=AutomaticJourneyStopCommand(p.generation,p.routeRevision,p.clientOwnerId,p.routeInputDigest)

    @Test fun `five leg v4 lane survives file DB restart beyond admission and ends after walking snapshot cancellation`() {
        val clock=AutomaticJourneyMutableClock();val dir=Files.createTempDirectory("journey-mixed-activity-")
        val url="jdbc:h2:file:${dir.resolve("fixture")};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE"
        val prepared=fixture(url,clock).use { f ->
            val (p,w)=active(f);val offline=MixedWalkingActivityFixture(f)
            assertThat(offline.delivery.update(7,9001,2,p.sessionId,body(f,p))).containsEntry("providerAccepted",true).containsEntry("deviceVerified",false)
            val lane=f.tx{f.lanes.findBySessionId(p.sessionId)!!}
            assertThat(lane.encryptedCredentials).doesNotContain(MixedWalkingActivityFixture.FCM,MixedWalkingActivityFixture.TOKEN)
            assertThat(f.calls).isEqualTo(3)
            p to w
        }
        val (p,w)=prepared;clock.now=p.preparedAt.plusSeconds(601)
        fixture(url,clock).use { f ->
            val offline=MixedWalkingActivityFixture(f)
            val next=body(f,p,3);val now=clock.instant().toEpochMilli()/1000.0
            (next.path("contentState") as ObjectNode).apply {
                put("schemaVersion",7);put("phase","WAITING");put("instruction","정류장 대기 · 탑승 미확정");put("walkProgress",1)
                put("arrivalStatus","WAITING_INFORMATION");putObject("arrival").put("busLegIndex",3)
                    .put("providerRouteId","999999997").put("directionLabel","합성 동쪽 방면")
                    .put("sourceUpdatedAt",now).put("expectedAt",now+120).put("validUntil",now+120)
            }
            offline.delivery.update(7,9001,2,p.sessionId,next)
            clock.now=clock.now.plusSeconds(1);offline.delivery.update(7,9001,2,p.sessionId,body(f,p,4))
            assertThat(offline.sent.map{it.contentState["activeLegIndex"]}).containsExactly(3,4)
            assertThat(f.calls).isZero();assertThat(f.fixture.departureCount.get()).isZero()
            f.service.cancel(7,9001,2,w);clock.now=clock.now.plusSeconds(1)
            assertThat(offline.delivery.end(7,9001,2,p.sessionId,stop(p))).containsEntry("endQueued",true).containsEntry("providerAccepted",false)
        }
        fixture(url,clock).use { f ->
            val offline=MixedWalkingActivityFixture(f)
            assertThat(offline.delivery.dispatchTerminal()).isEqualTo(1);assertThat(offline.delivery.dispatchTerminal()).isZero()
            val end=offline.sent.single();assertThat(end.event).isEqualTo(LiveActivityPushEvent.END)
            assertThat(end.contentState).containsEntry("arrival",null).containsEntry("walkProgress",null).containsEntry("rideProgress",null)
                .containsEntry("alighting",null).containsEntry("boardingConfirmed",false).containsEntry("status","STOPPED")
            assertThat(f.tx{f.lanes.findBySessionId(p.sessionId)!!.endState}).isEqualTo(JourneyActivityEndState.ACCEPTED)
            assertThat(f.calls).isZero();assertThat(f.fixture.departureCount.get()).isZero()
        }
    }
    @Test fun `mixed Activity HTTP controller uses real admission and never accepts raw coordinates`() {
        fixture("jdbc:h2:mem:mixed-activity-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",AutomaticJourneyMutableClock()).use { f ->
            val (p,_)=active(f);val offline=MixedWalkingActivityFixture(f);val mvc=walkingSnapshotMvc(f,activity=offline.delivery)
            val token=JourneyFixtureAuthentication.extendedJwt.createAccessToken(7,"Synthetic fixture",2)
            fun send(value:ObjectNode)=mvc.perform(post("/api/schedules/9001/automatic-journey/${p.sessionId}/live-activity")
                .header("Authorization","Bearer $token").contentType("application/json").content(value.toString())).andReturn().response
            val invalid=body(f,p);(invalid.path("contentState") as ObjectNode).put("latitude",37.5)
            assertThat(send(invalid).status).isEqualTo(409);assertThat(offline.sent).isEmpty()
            assertThat(send(body(f,p)).status).isEqualTo(200);assertThat(offline.sent).hasSize(1)
            assertThat(offline.state()).containsEntry("guidancePushRealProvider",false).containsEntry("offlineActivityUpdates",1)
        }
    }
    @Test fun `snapshot revocation blocks mixed updates without blocking durable END cleanup`() {
        for(mode in listOf("cancel","replace","revision")) {
            val clock=AutomaticJourneyMutableClock()
            fixture("jdbc:h2:mem:mixed-activity-$mode-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock).use { f ->
                val (p,w)=active(f);val offline=MixedWalkingActivityFixture(f)
                offline.delivery.update(7,9001,2,p.sessionId,body(f,p));clock.now=clock.now.plusSeconds(1)
                when(mode){"cancel"->f.service.cancel(7,9001,2,w);"replace"->f.service.issue(7,9001,2,1);else->f.revision++}
                assertThatThrownBy{offline.delivery.update(7,9001,2,p.sessionId,body(f,p))}.isInstanceOf(RuntimeException::class.java)
                assertThat(offline.sent).hasSize(1)
                offline.delivery.end(7,9001,2,p.sessionId,stop(p))
                assertThat(offline.service().dispatchTerminal()).isEqualTo(1)
                assertThat(offline.sent.map{it.event}).containsExactly(LiveActivityPushEvent.UPDATE,LiveActivityPushEvent.END)
            }
        }
    }
    @Test fun `server TTL expiry ends an orphaned mixed Activity without a client END or another departure`() {
        val clock=AutomaticJourneyMutableClock()
        fixture("jdbc:h2:mem:mixed-activity-expiry-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock).use { f ->
            val (p,_)=active(f);val offline=MixedWalkingActivityFixture(f)
            offline.delivery.update(7,9001,2,p.sessionId,body(f,p))
            val expiry=AutomaticJourneyExpiry(f.preparations,clock,true,true,true)
            clock.now=p.expiresAt.minusMillis(1)
            assertThat(f.tx{expiry.expireElapsedSessions()}).isZero()
            assertThat(offline.delivery.dispatchTerminal()).isZero()
            clock.now=p.expiresAt
            assertThat(f.tx{expiry.expireElapsedSessions()}).isEqualTo(1)
            assertThat(f.tx{expiry.expireElapsedSessions()}).isZero()
            assertThat(offline.delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(offline.delivery.dispatchTerminal()).isZero()
            assertThat(offline.sent.map{it.event}).containsExactly(LiveActivityPushEvent.UPDATE,LiveActivityPushEvent.END)
            assertThat(offline.sent.last().contentState).containsEntry("status","EXPIRED").containsEntry("arrival",null)
                .containsEntry("walkProgress",null).containsEntry("rideProgress",null).containsEntry("boardingConfirmed",false)
            assertThat(f.fixture.departureCount.get()).isEqualTo(1)
            assertThat(offline.state()).containsEntry("offlineActivityEndState","ACCEPTED")
        }
    }
}
