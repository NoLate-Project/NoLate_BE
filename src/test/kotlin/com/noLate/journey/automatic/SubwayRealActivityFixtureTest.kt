package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.notification.liveactivity.application.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class SubwayRealActivityFixtureTest {
    private val fcm="synthetic-only-installation"
    private val digest=MessageDigest.getInstance("SHA-256").digest(fcm.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun body(f:AutomaticJourneyFixture,target:String=fcm):ObjectNode {
        val now=f.clock.instant().epochSecond
        return f.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","activityId":"synthetic-subway","fcmRegistrationToken":"$target","liveActivityToken":"${"a".repeat(64)}","timestamp":$now,"contentState":{"schemaVersion":8,"sessionId":"${f.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${f.digest}","sequence":10,"status":"ACTIVE","phase":"RIDING","quality":"GOOD","instruction":"지하철로 이동 중","activeLegIndex":1,"rideProgress":0.5,"boardingConfirmed":true,"observedAt":$now,"validUntil":${now+15},"subway":{"currentStopIndex":1,"totalStops":3,"currentStopName":"합성 중간역","nextStopName":"합성 하차역"}}}""") as ObjectNode
    }
    @Test fun `current v8 service sends through pinned sender and durable END strips subway and stays single`() {
        val clock=AutomaticJourneyMutableClock()
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:real-v8-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock,
            connectorWalking=true,durablePreparations=true,subwayFixture=true).use { db ->
            val sent=mutableListOf<LiveActivityPushRequest>()
            val sender=object:LiveActivityPushClient {override fun send(request:LiveActivityPushRequest):LiveActivityPushResult {
                sent.add(request);return LiveActivityPushResult("synthetic-receipt")
            }}
            SubwayRealActivityFixture(db,sender,digest).use { remote ->
                val f=db.fixture;val automatic=db.automaticService()
                automatic.prepare(7,9001,2,f.prepareCommand());automatic.departure(7,9001,2,f.sessionId,f.event())
                remote.delivery.update(7,9001,2,f.sessionId,body(f))
                assertEquals(8,sent.single().contentState["schemaVersion"])
                assertEquals(mapOf(8 to 1),remote.state()["realActivityAcceptedBySchema"])
                val stored=db.tx { db.lanes.findBySessionId(f.sessionId)!! }
                assertFalse(stored.encryptedCredentials.contains(fcm))
                clock.now=clock.now.plusSeconds(1)
                remote.delivery.end(7,9001,2,f.sessionId,f.stopCommand())
                assertEquals(1,remote.delivery.dispatchTerminal())
                assertEquals(1,remote.state()["realActivityEnds"])
                assertNull(sent.last().contentState["subway"])
                assertEquals(1,sent.last().contentState["schemaVersion"])
                remote.delivery.end(7,9001,2,f.sessionId,f.stopCommand())
                assertEquals(0,remote.delivery.dispatchTerminal());assertEquals(2,sent.size)
            }
        }
    }
    @Test fun `foreign installation never reaches provider`() {
        val clock=AutomaticJourneyMutableClock()
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:real-v8-reject-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock,
            connectorWalking=true,durablePreparations=true,subwayFixture=true).use { db ->
            var calls=0
            val sender=object:LiveActivityPushClient {override fun send(request:LiveActivityPushRequest):LiveActivityPushResult {
                calls++;error("secret-provider-token-that-must-not-escape")
            }}
            SubwayRealActivityFixture(db,sender,digest).use { remote ->
                val f=db.fixture;val automatic=db.automaticService()
                automatic.prepare(7,9001,2,f.prepareCommand());automatic.departure(7,9001,2,f.sessionId,f.event())
                val error=assertFails { remote.delivery.update(7,9001,2,f.sessionId,body(f,"foreign-installation")) }
                assertEquals(0,calls);assertFalse(error.toString().contains("secret"))
            }
        }
    }
    @Test fun `provider exception is redacted without a retry`() {
        val clock=AutomaticJourneyMutableClock()
        WalkingSnapshotDatabaseFixture("jdbc:h2:mem:real-v8-provider-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",clock,
            connectorWalking=true,durablePreparations=true,subwayFixture=true).use { db ->
            var calls=0
            val sender=object:LiveActivityPushClient {override fun send(request:LiveActivityPushRequest):LiveActivityPushResult {
                calls++;error("secret-provider-token-that-must-not-escape")
            }}
            SubwayRealActivityFixture(db,sender,digest).use { remote ->
                val f=db.fixture;val automatic=db.automaticService()
                automatic.prepare(7,9001,2,f.prepareCommand());automatic.departure(7,9001,2,f.sessionId,f.event())
                val failure=assertFails { remote.delivery.update(7,9001,2,f.sessionId,body(f)) }
                assertEquals(1,calls)
                assertEquals("ISOLATED_PROVIDER_SEND_FAILED",failure.message)
                assertNull(failure.cause)
                assertEquals(1,remote.state()["realActivityRejected"])
                assertEquals(0,remote.state()["realActivityAccepted"])
            }
        }
    }
}
