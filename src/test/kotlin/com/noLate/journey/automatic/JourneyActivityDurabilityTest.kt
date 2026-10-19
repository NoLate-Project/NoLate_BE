package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.journey.domain.*
import com.noLate.notification.liveactivity.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

class JourneyActivityDurabilityTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val fcm = "synthetic-fcm-do-not-print"
    private val token = "e".repeat(64)
    private fun db() = DurableJourneyFixtureDatabase("jdbc:h2:mem:durable-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1")
    private fun fixture(db: DurableJourneyFixtureDatabase, clock: AutomaticJourneyMutableClock,transfer:Boolean=false): AutomaticJourneyFixture =
        AutomaticJourneyFixture(clock, 600, db).also { if(transfer)TransferRideFixture.apply(it);it.service().prepare(7, 9001, 2, it.prepareCommand()); it.service().departure(7, 9001, 2, it.sessionId, it.event()) }
    private fun service(db: DurableJourneyFixtureDatabase, f: AutomaticJourneyFixture, sender: LiveActivityPushClient? = null,
        enabled: Boolean = true, encryptionKey: String = key,
        routes: JourneyActivityRouteAuthority = FixtureJourneyActivityRouteAuthority()): JourneyActivityDeliveryService {
        val beans = StaticListableBeanFactory(); sender?.let { beans.addBean("testSender", it) }
        return JourneyActivityDeliveryService(db.lanes, f.service(), f.members, JourneyActivityCredentials(encryptionKey), f.mapper,
            db.manager, beans.getBeanProvider(LiveActivityPushClient::class.java), routes, f.clock, enabled)
    }
    private fun sender(sent: MutableCollection<LiveActivityPushRequest>) = object : LiveActivityPushClient {
        override fun send(request: LiveActivityPushRequest): LiveActivityPushResult { sent.add(request); return LiveActivityPushResult("synthetic") }
    }
    private fun body(f: AutomaticJourneyFixture): ObjectNode {
        val now = f.clock.instant().toEpochMilli() / 1000.0
        return f.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","activityId":"${f.sessionId}","fcmRegistrationToken":"$fcm","liveActivityToken":"$token","timestamp":${f.clock.instant().epochSecond},"contentState":{"schemaVersion":1,"sessionId":"${f.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${f.digest}","sequence":1,"status":"ACTIVE","phase":"WALKING_TO_STOP","quality":"GOOD","instruction":"승차 정류장으로 이동 중","activeLegIndex":0,"walkProgress":0.3,"observedAt":$now,"validUntil":${now + 15},"boardingConfirmed":false,"arrivalStatus":"UNAVAILABLE","arrival":null}}""") as ObjectNode
    }
    private fun register(db: DurableJourneyFixtureDatabase, f: AutomaticJourneyFixture, sent: MutableCollection<LiveActivityPushRequest>): JourneyActivityDeliveryService =
        service(db, f, sender(sent)).also { it.update(7, 9001, 2, f.sessionId, body(f)) }
    private fun expire(db: DurableJourneyFixtureDatabase, f: AutomaticJourneyFixture, clock: AutomaticJourneyMutableClock) {
        clock.now = clock.now.plusSeconds(600)
        db.tx { AutomaticJourneyExpiry(db.preparations, clock, true, true, true).expireElapsedSessions() }
    }

    @Test fun `encrypted lane and stored expiry survive manager restart without app request`() {
        val directory = Files.createTempDirectory("journey-durable-unit-")
        val url = "jdbc:h2:file:${directory.resolve("fixture")};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE"
        val clock = AutomaticJourneyMutableClock(); val sent = mutableListOf<LiveActivityPushRequest>()
        var database = DurableJourneyFixtureDatabase(url); val first = fixture(database, clock)
        register(database, first, sent)
        val sealed = database.tx { database.lanes.findBySessionId(first.sessionId)!! }
        assertThat(sealed.encryptedCredentials).doesNotContain(fcm, token)
        assertThat(sealed.toString()).doesNotContain(fcm, token, sealed.encryptedCredentials)
        database.close()
        database = DurableJourneyFixtureDatabase(url)
        val restored = AutomaticJourneyFixture(clock, 600, database)
        assertThat(restored.state()["lastState"]).isEqualTo("ACTIVE")
        expire(database, restored, clock)
        val endedAt = database.tx { database.preparations.findBySessionId(first.sessionId)!!.endedAt }
        val delivery = service(database, restored, sender(sent))
        assertThat(delivery.dispatchTerminal()).isEqualTo(1)
        assertThat(delivery.dispatchTerminal()).isZero()
        assertThat(sent.map { it.event }).containsExactly(LiveActivityPushEvent.UPDATE, LiveActivityPushEvent.END)
        assertThat(sent.last().contentState).containsEntry("status", "EXPIRED").containsEntry("walkProgress", null).containsEntry("arrival", null)
        assertThat(sent.last().contentState["observedAt"]).isEqualTo(sent.first().contentState["observedAt"])
        assertThat(database.tx { database.preparations.findBySessionId(first.sessionId)!!.endedAt }).isEqualTo(endedAt)
        assertThat(restored.departureCount.get()).isZero()
        database.close()
        // Unit fixture contains no real credentials; retain the temp DB until OS cleanup, no broad deletion.
    }

    @Test fun `READY intent survives new service and exact duplicate does not send again`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = register(db, f, sent); clock.now = clock.now.plusSeconds(1)
            val receipt = delivery.end(7, 9001, 2, f.sessionId, f.stopCommand())
            assertThat(receipt).containsEntry("providerAccepted", false).containsEntry("endQueued", true)
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.READY)
            val restarted = service(db, f, sender(sent))
            assertThat(restarted.dispatchTerminal()).isEqualTo(1)
            assertThat(restarted.end(7, 9001, 2, f.sessionId, f.stopCommand())).containsEntry("providerAccepted", true)
            assertThat(restarted.dispatchTerminal()).isZero(); assertThat(sent).hasSize(2)
        }
    }
    @Test fun `v2 ride presentation is bounded and durable END strips confirmed ride`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            fun ride() = body(f).also { (it.path("contentState") as ObjectNode)
                .put("schemaVersion", 2).put("phase", "RIDING").put("instruction", "버스로 이동 중")
                .put("activeLegIndex", 1).putNull("walkProgress").put("rideProgress", 0.5).put("boardingConfirmed", true) }
            val delivery = service(db, f, sender(sent))
            for (patch in listOf<(ObjectNode) -> Unit>(
                { it.put("schemaVersion", 1) }, { it.put("boardingConfirmed", false) }, { it.put("activeLegIndex", 0) },
                { it.put("rideProgress", 1.1) }, { it.put("quality", "UNCERTAIN") }, { it.put("walkProgress", 1) },
                { it.put("latitude", 37.0) }, { it.put("vehicleId", "999999998") })) {
                val bad = ride(); patch(bad.path("contentState") as ObjectNode)
                assertThatThrownBy { delivery.update(7,9001,2,f.sessionId,bad) }.isInstanceOf(Exception::class.java)
            }
            assertThat(sent).isEmpty()
            delivery.update(7,9001,2,f.sessionId,ride())
            assertThat(sent.single().contentState).containsEntry("rideProgress",0.5).containsEntry("boardingConfirmed",true)
            clock.now = clock.now.plusSeconds(1); delivery.end(7,9001,2,f.sessionId,f.stopCommand())
            assertThat(delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().contentState).containsEntry("rideProgress",null).containsEntry("boardingConfirmed",false).containsEntry("schemaVersion",1)
        }
    }

    @Test fun `v3 alighting notice is bounded still riding and removed by durable END`() {
        db().use { db ->
            val clock=AutomaticJourneyMutableClock(); val f=fixture(db,clock); val sent=mutableListOf<LiveActivityPushRequest>()
            fun notice()=body(f).also { root -> (root.path("contentState") as ObjectNode).apply {
                put("schemaVersion",3); put("phase","RIDING"); put("instruction","버스로 이동 중")
                put("activeLegIndex",1); putNull("walkProgress"); put("rideProgress",0.9); put("boardingConfirmed",true)
                putObject("alighting").put("kind","ONE_STOP_BEFORE").put("remainingMeters",180)
            } }
            val delivery=service(db,f,sender(sent))
            for(patch in listOf<(ObjectNode)->Unit>(
                {it.put("schemaVersion",2)}, {it.put("boardingConfirmed",false)}, {it.put("activeLegIndex",2)},
                {it.putNull("alighting")}, {(it.path("alighting") as ObjectNode).put("kind","ARRIVED")},
                {(it.path("alighting") as ObjectNode).put("remainingMeters",11)},
                {(it.path("alighting") as ObjectNode).put("kind","NEAR_STOP").put("remainingMeters",60)},
                {(it.path("alighting") as ObjectNode).put("latitude",37.5)})) {
                val bad=notice(); patch(bad.path("contentState") as ObjectNode)
                assertThatThrownBy {delivery.update(7,9001,2,f.sessionId,bad)}.isInstanceOf(Exception::class.java)
            }
            assertThat(sent).isEmpty()
            delivery.update(7,9001,2,f.sessionId,notice())
            assertThat(sent.single().contentState).containsKey("alighting").containsEntry("boardingConfirmed",true)
            clock.now=clock.now.plusSeconds(1); delivery.end(7,9001,2,f.sessionId,f.stopCommand())
            assertThat(delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().contentState).containsEntry("alighting",null).containsEntry("boardingConfirmed",false).containsEntry("schemaVersion",1)
        }
    }

    @Test fun `v4 final walk display rejects conflicting phases and durable END removes its position`() {
        db().use { db ->
            val clock=AutomaticJourneyMutableClock(); val f=fixture(db,clock); val sent=mutableListOf<LiveActivityPushRequest>()
            fun walk()=body(f).also { root -> (root.path("contentState") as ObjectNode).apply {
                put("schemaVersion",4);put("phase","FINAL_WALK");put("instruction","목적지로 도보 이동 중")
                put("activeLegIndex",2);put("walkProgress",0.2);putNull("rideProgress");put("boardingConfirmed",false)
            } }
            val delivery=service(db,f,sender(sent))
            for(patch in listOf<(ObjectNode)->Unit>(
                {it.put("schemaVersion",1)},{it.put("activeLegIndex",1)},{it.put("boardingConfirmed",true)},
                {it.put("quality","UNCERTAIN")},{it.put("rideProgress",0.5)},{it.putNull("walkProgress")},
                {it.put("walkProgress",1.1)},{it.put("phase","ARRIVED")},{it.putObject("alighting").put("kind","NEAR_STOP").put("remainingMeters",0)},
                {it.putObject("arrival")},{it.put("latitude",37.5)},{it.put("vehicleId","999999998")})) {
                val bad=walk();patch(bad.path("contentState") as ObjectNode)
                assertThatThrownBy{delivery.update(7,9001,2,f.sessionId,bad)}.isInstanceOf(Exception::class.java)
            }
            assertThat(sent).isEmpty();delivery.update(7,9001,2,f.sessionId,walk())
            assertThat(sent.single().contentState).containsEntry("schemaVersion",4).containsEntry("activeLegIndex",2).containsEntry("walkProgress",0.2)
            clock.now=clock.now.plusSeconds(1);delivery.end(7,9001,2,f.sessionId,f.stopCommand())
            assertThat(delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().contentState).containsEntry("schemaVersion",1).containsEntry("walkProgress",null).containsEntry("boardingConfirmed",false)
        }
    }

    @Test fun `v5 transfer display sends no boarding claim and durable END removes its position`() {
        db().use { db ->
            val clock=AutomaticJourneyMutableClock();val f=fixture(db,clock,transfer=true);val sent=mutableListOf<LiveActivityPushRequest>()
            fun walk()=body(f).also { root -> (root.path("contentState") as ObjectNode).apply {
                put("schemaVersion",5);put("phase","TRANSFER_WALK");put("instruction","환승 승차 지점으로 도보 이동 중")
                put("activeLegIndex",2);put("walkProgress",0.2);putNull("rideProgress");put("boardingConfirmed",false)
            } }
            val delivery=service(db,f,sender(sent))
            for(patch in listOf<(ObjectNode)->Unit>(
                {it.put("schemaVersion",4)},{it.put("activeLegIndex",3)},{it.put("boardingConfirmed",true)},
                {it.put("quality","UNCERTAIN")},{it.put("rideProgress",0.5)},{it.putNull("walkProgress")},
                {it.put("walkProgress",1.1)},{it.put("phase","ARRIVED")},{it.putObject("arrival")},
                {it.put("latitude",37.5)},{it.put("nextVehicleId","999999998")})) {
                val bad=walk();patch(bad.path("contentState") as ObjectNode)
                assertThatThrownBy{delivery.update(7,9001,2,f.sessionId,bad)}.isInstanceOf(Exception::class.java)
            }
            assertThat(sent).isEmpty();delivery.update(7,9001,2,f.sessionId,walk())
            assertThat(sent.single().contentState).containsEntry("schemaVersion",5).containsEntry("activeLegIndex",2)
                .containsEntry("walkProgress",0.2).containsEntry("boardingConfirmed",false)
            clock.now=clock.now.plusSeconds(1);delivery.end(7,9001,2,f.sessionId,f.stopCommand())
            assertThat(delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().contentState).containsEntry("schemaVersion",1).containsEntry("walkProgress",null)
                .containsEntry("boardingConfirmed",false)
        }
    }

    @Test fun `indexed second ride and last walking display survive storage but END removes both claims`() {
        db().use { db ->
            val clock=AutomaticJourneyMutableClock();val f=fixture(db,clock,transfer=true);val sent=mutableListOf<LiveActivityPushRequest>()
            val delivery=service(db,f,sender(sent))
            val riding=body(f).also { (it.path("contentState") as ObjectNode).apply {
                put("schemaVersion",6);put("phase","RIDING");put("instruction","버스로 이동 중");put("activeLegIndex",3)
                putNull("walkProgress");put("rideProgress",0.8);put("boardingConfirmed",true)
                putObject("alighting").put("kind","ONE_STOP_BEFORE").put("remainingMeters",100)
            } }
            delivery.update(7,9001,2,f.sessionId,riding)
            clock.now=clock.now.plusSeconds(5)
            val walking=body(f).also { (it.path("contentState") as ObjectNode).apply {
                put("schemaVersion",6);put("sequence",2);put("phase","FINAL_WALK");put("instruction","목적지로 도보 이동 중")
                put("activeLegIndex",4);put("walkProgress",0.4)
            } }
            service(db,f,sender(sent)).update(7,9001,2,f.sessionId,walking)
            assertThat(sent.map { it.contentState["activeLegIndex"] }).containsExactly(3,4)
            clock.now=clock.now.plusSeconds(1);delivery.end(7,9001,2,f.sessionId,f.stopCommand())
            assertThat(service(db,f,sender(sent)).dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().contentState).containsEntry("schemaVersion",1).containsEntry("rideProgress",null)
                .containsEntry("walkProgress",null).containsEntry("boardingConfirmed",false).containsEntry("alighting",null)
        }
    }
    @Test fun `indexed ETA v7 stays occurrence bound across restart and terminal delivery clears it`() {
        db().use { db ->
            val clock=AutomaticJourneyMutableClock();val f=fixture(db,clock,transfer=true);val sent=mutableListOf<LiveActivityPushRequest>()
            val now=clock.instant().toEpochMilli()/1000.0
            val b=body(f).also { (it.path("contentState") as ObjectNode).apply {
                put("schemaVersion",7);put("phase","WAITING");put("instruction","정류장 대기 · 탑승 미확정")
                put("activeLegIndex",3);put("walkProgress",1);put("arrivalStatus","WAITING_INFORMATION")
                putObject("arrival").put("busLegIndex",3).put("providerRouteId","999999997").put("directionLabel","합성 동쪽 방면")
                    .put("sourceUpdatedAt",now).put("expectedAt",now+120).put("validUntil",now+120)
            } }
            for(mode in listOf("old-index","legacy-schema","missing-index","boarding","phase","stale")) {
                val bad=b.deepCopy();val c=bad.path("contentState") as ObjectNode;val a=c.path("arrival") as ObjectNode
                when(mode){"old-index"->a.put("busLegIndex",1);"legacy-schema"->c.put("schemaVersion",6)
                    "missing-index"->a.remove("busLegIndex");"boarding"->c.put("boardingConfirmed",true)
                    "phase"->c.put("phase","RIDING");"stale"->a.put("validUntil",now)}
                assertThatThrownBy{service(db,f,sender(sent)).update(7,9001,2,f.sessionId,bad)}.isInstanceOf(RuntimeException::class.java)
            }
            assertThat(sent).isEmpty();service(db,f,sender(sent)).update(7,9001,2,f.sessionId,b)
            assertThat(sent.single().contentState).containsEntry("schemaVersion",7).containsEntry("activeLegIndex",3)
            clock.now=clock.now.plusSeconds(1);service(db,f,sender(sent)).end(7,9001,2,f.sessionId,f.stopCommand())
            assertThat(service(db,f,sender(sent)).dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().contentState).containsEntry("arrival",null).containsEntry("boardingConfirmed",false)
        }
    }
    @Test fun `committed in-flight crash becomes UNKNOWN and is never replayed`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = register(db, f, sent); clock.now = clock.now.plusSeconds(1)
            delivery.end(7, 9001, 2, f.sessionId, f.stopCommand())
            db.tx { val row = db.lanes.lockSession(f.sessionId)!!; row.endState = JourneyActivityEndState.IN_FLIGHT; row.claimedAt = clock.instant(); db.lanes.saveAndFlush(row) }
            clock.now = clock.now.plusSeconds(31)
            val restarted = service(db, f, sender(sent))
            assertThat(restarted.dispatchTerminal()).isZero(); assertThat(restarted.dispatchTerminal()).isZero()
            val row = db.tx { db.lanes.findBySessionId(f.sessionId)!! }
            assertThat(row.endState).isEqualTo(JourneyActivityEndState.UNKNOWN)
            assertThat(row.failureCode).isEqualTo("PROCESS_INTERRUPTED_ACCEPTANCE_UNKNOWN")
            assertThat(sent).hasSize(1)
        }
    }

    @Test fun `unknown and confirmed rejection are durable and never silently retried`() {
        for (confirmed in listOf(false, true)) db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            register(db, f, sent); expire(db, f, clock)
            var calls = 0
            val failing = object : LiveActivityPushClient {
                override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
                    calls++; if (confirmed) throw InvalidLiveActivityRouteException() else throw IllegalStateException("private-provider-detail")
                }
            }
            assertThat(service(db, f, failing).dispatchTerminal()).isZero()
            assertThat(service(db, f, failing).dispatchTerminal()).isZero()
            assertThat(calls).isEqualTo(1)
            val lane = db.tx { db.lanes.findBySessionId(f.sessionId)!! }
            assertThat(lane.endState).isEqualTo(if (confirmed) JourneyActivityEndState.REJECTED else JourneyActivityEndState.UNKNOWN)
            assertThat(lane.failureCode).doesNotContain("private-provider-detail")
            assertThatThrownBy { service(db, f, sender(sent)).update(7, 9001, 2, f.sessionId, body(f)) }.isInstanceOf(Exception::class.java)
            assertThat(db.tx { db.preparations.findBySessionId(f.sessionId)!!.state }).isEqualTo(AutomaticJourneyState.EXPIRED)
        }
    }

    @Test fun `same second defers without future timestamp or lost READY intent`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = register(db, f, sent)
            delivery.end(7, 9001, 2, f.sessionId, f.stopCommand())
            assertThat(delivery.dispatchTerminal()).isZero()
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.READY)
            clock.now = clock.now.plusSeconds(1)
            assertThat(delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(sent.last().timestamp).isEqualTo(clock.instant().epochSecond)
        }
    }

    @Test fun `revoked identity and wrong decrypt key fail closed without remote side effects`() {
        for (revoked in listOf(false, true)) db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            register(db, f, sent); expire(db, f, clock)
            if (revoked) f.actor.sessionGeneration = 3
            val delivery = service(db, f, sender(sent), encryptionKey = if (revoked) key else Base64.getEncoder().encodeToString(ByteArray(32) { 9 }))
            assertThat(delivery.dispatchTerminal()).isZero(); assertThat(delivery.dispatchTerminal()).isZero()
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.BLOCKED)
            assertThat(sent).hasSize(1)
        }
    }

    @Test fun `two workers serialize one durable reservation and one END provider call`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = ConcurrentLinkedQueue<LiveActivityPushRequest>()
            register(db, f, sent); expire(db, f, clock)
            val workers = Executors.newFixedThreadPool(2); val start = CountDownLatch(1); val done = CountDownLatch(2)
            val failures = ConcurrentLinkedQueue<Throwable>()
            repeat(2) { workers.submit { start.await(); try { service(db, f, sender(sent)).dispatchTerminal() } catch (e: Throwable) { failures.add(e) } finally { done.countDown() } } }
            start.countDown(); assertThat(done.await(15, TimeUnit.SECONDS)).isTrue(); workers.shutdownNow()
            assertThat(failures).isEmpty(); assertThat(sent.count { it.event == LiveActivityPushEvent.END }).isEqualTo(1)
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.ACCEPTED)
        }
    }

    @Test fun `disabled absent provider and absent encryption key cannot claim delivery`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            assertThatThrownBy { service(db, f, sender(sent), enabled = false).update(7, 9001, 2, f.sessionId, body(f)) }.isInstanceOf(Exception::class.java)
            assertThatThrownBy { service(db, f, sender(sent), encryptionKey = "").update(7, 9001, 2, f.sessionId, body(f)) }.isInstanceOf(Exception::class.java)
            assertThatThrownBy { service(db, f).update(7, 9001, 2, f.sessionId, body(f)) }.isInstanceOf(Exception::class.java)
            assertThat(db.tx { db.lanes.count() }).isZero(); assertThat(sent).isEmpty()
            register(db, f, sent); expire(db, f, clock)
            assertThat(service(db, f).dispatchTerminal()).isZero()
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.NONE)
        }
    }

    @Test fun `immutable Activity tokens cannot rotate or retarget and duplicate timestamps are rejected`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = register(db, f, sent)
            assertThatThrownBy { delivery.update(7, 9001, 2, f.sessionId, body(f)) }.isInstanceOf(Exception::class.java)
            clock.now = clock.now.plusSeconds(1)
            for (field in listOf("activityId", "liveActivityToken", "fcmRegistrationToken", "clientOwnerId", "routeInputDigest")) {
                val changed = body(f).put(field, if (field == "activityId" || field == "clientOwnerId") UUID.randomUUID().toString() else "b".repeat(64))
                assertThatThrownBy { delivery.update(7, 9001, 2, f.sessionId, changed) }.isInstanceOf(Exception::class.java)
            }
            assertThat(sent).hasSize(1)
        }
    }

    @Test fun `contract rejects GPS fields wrong snapshots and private debugging before persistence`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = service(db, f, sender(sent))
            val changed = body(f).also { (it.path("contentState") as ObjectNode).put("latitude", 37.0) }
            val nested = body(f).put("privateDebug", "secret")
            val badState = body(f).also { (it.path("contentState") as ObjectNode).put("status", "STOPPED") }
            for (invalid in listOf(changed, nested, badState)) assertThatThrownBy { delivery.update(7, 9001, 2, f.sessionId, invalid) }.isInstanceOf(Exception::class.java)
            assertThat(db.tx { db.lanes.count() }).isZero(); assertThat(sent).isEmpty()
        }
    }

    @Test fun `cipher binds session and sanitizes tamper and wrong key without leaking tokens`() {
        val c = JourneyActivityCredentials(key); val sealed = c.seal("a", fcm, token)
        assertThat(c.open("a", sealed)).isEqualTo(fcm to token)
        assertThatThrownBy { c.open("b", sealed) }.hasMessage("JOURNEY_ACTIVITY_CREDENTIALS_UNAVAILABLE").hasNoCause()
        assertThatThrownBy { c.open("a", "broken") }.hasMessage("JOURNEY_ACTIVITY_CREDENTIALS_UNAVAILABLE").hasNoCause()
    }

    @Test fun `real activity controller uses authenticated identity and sanitized flat receipt`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = service(db, f, sender(sent)); val base = "/api/schedules/9001/automatic-journey/${f.sessionId}/live-activity"
            fun request() = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(body(f)))
            val denied = automaticJourneyFixtureMvc(f, authenticated = false, activityDelivery = delivery).perform(request()).andReturn().response
            assertThat(denied.status).isEqualTo(401); assertThat(db.tx { db.lanes.count() }).isZero()
            val mvc = automaticJourneyFixtureMvc(f, activityDelivery = delivery)
            val response = mvc.perform(request()).andReturn().response
            assertThat(response.status).isEqualTo(200)
            val result = f.mapper.readTree(response.contentAsByteArray)
            assertThat(result.path("data").path("providerAccepted").asBoolean()).isTrue()
            assertThat(response.contentAsString).doesNotContain(fcm, token, "encryptedCredentials")
            val malformed = body(f).put("privateDebug", "secret-provider-details")
            val bad = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(f.mapper.writeValueAsBytes(malformed))).andReturn().response
            assertThat(bad.status).isEqualTo(409); assertThat(bad.contentAsString).doesNotContain(fcm, token, "secret-provider-details")
        }
    }

    @Test fun `old expired lane does not stop newer session or steal global token ownership`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val delivery = register(db, f, sent); expire(db, f, clock)
            val next = UUID.randomUUID().toString()
            f.service().prepare(7, 9001, 2, AutomaticJourneyPrepareCommand(next, f.owner, f.digest, AutomaticJourneyExecution.SIMULATOR))
            f.service().departure(7, 9001, 2, next, AutomaticJourneyDepartureCommand(2, 1, f.owner, f.digest, "$next:2:1:AUTO_DEPARTURE", 1, "AUTO_DEPARTURE", clock.instant(), AutomaticJourneyExecution.SIMULATOR))
            val duplicateToken = body(f).put("generation", 2).put("activityId", next)
            (duplicateToken.path("contentState") as ObjectNode).put("sessionId", next).put("generation", 2)
            assertThatThrownBy { delivery.update(7, 9001, 2, next, duplicateToken) }.isInstanceOf(Exception::class.java)
            assertThat(delivery.dispatchTerminal()).isEqualTo(1)
            assertThat(db.tx { db.preparations.findBySessionId(next)!!.state }).isEqualTo(AutomaticJourneyState.ACTIVE)
            assertThat(db.tx { db.lanes.count() }).isEqualTo(1)
            db.tx { db.lanes.deleteAllByMemberId(7) }; assertThat(db.tx { db.lanes.count() }).isZero()
        }
    }

    @Test fun `schema migration marker and privacy bounded deletion are explicit`() {
        val marker = com.noLate.global.config.ProductionSchemaVersionGuard.JOURNEY_ACTIVITY_SCHEMA_VERSION
        assertThat(com.noLate.global.config.ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS).contains(marker)
        val sql = Files.readString(Path.of("docs/schedule/migrations/2026-10-06-journey-activity-lanes.sql"))
        assertThat(sql).contains(marker, "encrypted_credentials", "uk_journey_activity_token", "idx_journey_activity_terminal")
        assertThat(sql.indexOf("CALL assert_journey_activity_postconditions()")).isLessThan(sql.indexOf("INSERT INTO application_schema_migrations"))
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>(); register(db, f, sent)
            db.tx { db.lanes.deleteAllByRetentionExpiresAtBefore(clock.instant().plusSeconds(1)) }
            assertThat(db.tx { db.lanes.count() }).isEqualTo(1)
            db.tx { db.lanes.deleteAllByScheduleIdIn(listOf(9001)) }
            assertThat(db.tx { db.lanes.count() }).isZero()
        }
    }

    @Test fun `retargeted FCM proof blocks END while busy route is deferred without reservation`() {
        db().use { db ->
            val clock = AutomaticJourneyMutableClock(); val f = fixture(db, clock); val sent = mutableListOf<LiveActivityPushRequest>()
            val routes = FixtureJourneyActivityRouteAuthority()
            val delivery = service(db, f, sender(sent), routes = routes)
            delivery.update(7, 9001, 2, f.sessionId, body(f)); expire(db, f, clock)
            routes.busy = true
            assertThat(delivery.dispatchTerminal()).isZero()
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.NONE)
            routes.busy = false; routes.revoked = true
            assertThat(delivery.dispatchTerminal()).isZero()
            assertThat(db.tx { db.lanes.findBySessionId(f.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.BLOCKED)
            assertThat(sent).hasSize(1)
        }
    }
}
