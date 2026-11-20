package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyActivityContract
import org.junit.jupiter.api.Test
import kotlin.test.*

class JourneyActivitySubwayContractTest {
    @Test fun `historical station is bounded non-live and matches the owned saved route`() {
        val f=AutomaticJourneyFixture();f.useRideGuidancePlan();SubwayNavigationFixture.apply(f)
        val service=f.service();service.prepare(7,9001,2,f.prepareCommand())
        service.departure(7,9001,2,f.sessionId,f.event())
        val bound=service.validate(7,9001,2,f.sessionId,f.stopCommand())
        val route=com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan,f.mapper)
        val now=f.clock.instant().epochSecond
        val b=body(f);val c=b.path("contentState") as ObjectNode
        c.put("schemaVersion",1);c.put("phase","UNAVAILABLE");c.put("quality","UNCERTAIN")
        c.put("instruction","현재 위치 확인 필요");c.put("activeLegIndex",0)
        c.put("boardingConfirmed",false);c.putNull("rideProgress");c.putNull("subway")
        c.put("observedAt",now-20);c.put("validUntil",now-5)
        val last=c.putObject("lastConfirmedSubway")
        last.put("stopIndex",1);last.put("totalStops",3);last.put("stopName","합성 중간역")
        last.put("confirmedAt",now-20)
        assertNotNull(JourneyActivityContract.parse(b,f.sessionId,9001,bound,f.clock.instant(),f.mapper))
        JourneyActivityContract.validateLastSubwaySavedCall(c,route,f.mapper)
        last.put("stopName","다른 역")
        assertFailsWith<IllegalArgumentException>{JourneyActivityContract.validateLastSubwaySavedCall(c,route,f.mapper)}
        last.put("stopName","합성 중간역");last.put("confirmedAt",now-90)
        assertFailsWith<IllegalArgumentException>{JourneyActivityContract.parse(b,f.sessionId,9001,bound,f.clock.instant(),f.mapper)}
        last.put("confirmedAt",now-20);c.put("boardingConfirmed",true)
        assertFailsWith<IllegalArgumentException>{JourneyActivityContract.parse(b,f.sessionId,9001,bound,f.clock.instant(),f.mapper)}
    }
    @Test fun `later train history is checked against the transfer station list`() {
        val f = AutomaticJourneyFixture(); TransferRideFixture.apply(f); SubwayNavigationFixture.apply(f)
        val legs = f.plan.route!!.path("transitLegs") as com.fasterxml.jackson.databind.node.ArrayNode
        val second = legs[1].deepCopy<ObjectNode>()
        (second.path("navigationEvidence").path("stopOccurrences")[1] as ObjectNode).put("name", "환승 구간 중간역")
        legs.set(3, second)
        val route = com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan, f.mapper)
        val service = f.service(); service.prepare(7,9001,2,f.prepareCommand())
        service.departure(7,9001,2,f.sessionId,f.event())
        val bound = service.validate(7,9001,2,f.sessionId,f.stopCommand())
        val b = body(f); val c = b.path("contentState") as ObjectNode
        c.put("schemaVersion",1).put("phase","UNAVAILABLE").put("quality","UNCERTAIN").put("instruction","현재 위치 확인 필요")
            .put("activeLegIndex",3).put("boardingConfirmed",false).putNull("rideProgress").putNull("subway")
        c.putObject("lastConfirmedSubway").put("stopIndex",1).put("totalStops",3).put("stopName","환승 구간 중간역")
            .put("confirmedAt", f.clock.instant().epochSecond)
        JourneyActivityContract.parse(b,f.sessionId,9001,bound,f.clock.instant(),f.mapper)
        JourneyActivityContract.validateLastSubwaySavedCall(c,route,f.mapper)
        c.put("activeLegIndex",0)
        assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateLastSubwaySavedCall(c,route,f.mapper) }
        c.put("activeLegIndex",2)
        assertFailsWith<IllegalArgumentException> { JourneyActivityContract.parse(b,f.sessionId,9001,bound,f.clock.instant(),f.mapper) }
    }

    @Test fun `station labels and count must match the same owned saved subway leg`() {
        val f=AutomaticJourneyFixture();f.useRideGuidancePlan();SubwayNavigationFixture.apply(f)
        val route=com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan,f.mapper)
        val c=body(f).path("contentState")
        JourneyActivityContract.validateSubwaySavedCalls(c,route,f.mapper)
        (c.path("subway") as ObjectNode).put("currentStopName","다른 역")
        assertFailsWith<IllegalArgumentException>{JourneyActivityContract.validateSubwaySavedCalls(c,route,f.mapper)}
    }
    private fun body(f:AutomaticJourneyFixture):ObjectNode {
        val now=f.clock.instant().epochSecond
        return f.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","activityId":"test-subway","fcmRegistrationToken":"synthetic","liveActivityToken":"${"a".repeat(64)}","timestamp":$now,"contentState":{"schemaVersion":8,"sessionId":"${f.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${f.digest}","sequence":10,"status":"ACTIVE","phase":"RIDING","quality":"GOOD","instruction":"지하철로 이동 중","activeLegIndex":1,"rideProgress":0.5,"boardingConfirmed":true,"observedAt":$now,"validUntil":${now+15},"subway":{"currentStopIndex":1,"totalStops":3,"currentStopName":"합성 중간역","nextStopName":"합성 하차역"}}}""") as ObjectNode
    }
    @Test fun `subway display accepts station count contract but never arbitrary percent or legacy boarding`() {
        val f=AutomaticJourneyFixture();val service=f.service();service.prepare(7,9001,2,f.prepareCommand())
        service.departure(7,9001,2,f.sessionId,f.event())
        val bound=service.validate(7,9001,2,f.sessionId,f.stopCommand())
        val b=body(f)
        assertEquals(8,JourneyActivityContract.parse(b,f.sessionId,9001,bound,f.clock.instant(),f.mapper).content["schemaVersion"])
        for(patch in listOf("rideProgress" to 0.7,"boardingConfirmed" to false,"schemaVersion" to 7)){
            val changed=b.deepCopy();val c=changed.path("contentState") as ObjectNode
            c.set<com.fasterxml.jackson.databind.JsonNode>(patch.first,f.mapper.valueToTree(patch.second))
            assertFailsWith<IllegalArgumentException>{JourneyActivityContract.parse(changed,f.sessionId,9001,bound,f.clock.instant(),f.mapper)}
        }
    }

    @Test fun `measured segment binds adjacent saved calls and expires with its station proof`() {
        val f = AutomaticJourneyFixture()
        f.useRideGuidancePlan()
        SubwayNavigationFixture.applyMeasuredTrack(f)
        val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        service.departure(7, 9001, 2, f.sessionId, f.event())
        val bound = service.validate(7, 9001, 2, f.sessionId, f.stopCommand())
        val route = com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan, f.mapper)
        val b = body(f)
        val now = f.clock.instant().epochSecond
        val c = b.path("contentState") as ObjectNode
        c.put("schemaVersion", 9).put("phase", "RIDING_SEGMENT").put("instruction", "지하철 역 사이 이동 중")
            .put("rideProgress", 0.625).putNull("subway")
        c.putObject("subwaySegment").put("fromStopIndex", 1).put("totalStops", 3)
            .put("fromStopName", "합성 중간역").put("toStopName", "합성 하차역")
            .put("progress", 0.25).put("confirmedAt", now - 15)

        fun parse(node: ObjectNode) = JourneyActivityContract.parse(node, f.sessionId, 9001, bound, f.clock.instant(), f.mapper)
        assertEquals(9, parse(b).content["schemaVersion"])
        JourneyActivityContract.validateSubwaySavedCalls(c, route, f.mapper)

        fun reject(change: (ObjectNode, ObjectNode) -> Unit) {
            val attempt = b.deepCopy()
            val content = attempt.path("contentState") as ObjectNode
            change(content, content.path("subwaySegment") as ObjectNode)
            assertFailsWith<IllegalArgumentException> { parse(attempt) }
        }
        for (version in listOf(1, 6, 8, 10)) reject { content, _ -> content.put("schemaVersion", version) }
        for (index in listOf(0, 2)) reject { _, segment -> segment.put("fromStopIndex", index) }
        for (count in listOf(2, 65)) reject { _, segment -> segment.put("totalStops", count) }
        for (fraction in listOf(0.0, 1.0, -0.1, 1.1)) reject { _, segment -> segment.put("progress", fraction) }
        for (field in listOf("trainId", "latitude", "longitude", "coordinate", "currentStopName"))
            reject { _, segment -> segment.put(field, "unsupported") }
        for (field in listOf("walkProgress", "lastConfirmedBus", "lastConfirmedSubway", "arrival", "alighting", "subway"))
            reject { content, _ -> content.putObject(field) }
        reject { content, _ -> content.put("phase", "RIDING") }
        reject { content, _ -> content.put("quality", "UNCERTAIN") }
        reject { content, _ -> content.put("boardingConfirmed", false) }
        reject { content, _ -> content.put("activeLegIndex", 2) }
        reject { content, _ -> content.put("rideProgress", 0.5) }
        reject { content, _ -> content.put("observedAt", now - 20) }
        reject { _, segment -> segment.put("confirmedAt", now + 1) }
        reject { _, segment -> segment.put("confirmedAt", now - 90) }
        reject { _, segment -> segment.put("confirmedAt", now - 80) } // validUntil must also respect the evidence lifetime.
        for (key in listOf("fromStopName", "toStopName")) {
            val wrong = c.deepCopy()
            (wrong.path("subwaySegment") as ObjectNode).put(key, "다른 역")
            assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateSubwaySavedCalls(wrong, route, f.mapper) }
        }
        val savedLeg = f.plan.route!!.path("transitLegs")[1] as ObjectNode
        savedLeg.put("pathCoordsIsExact", false)
        val unverified = com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan, f.mapper)
        assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateSubwaySavedCalls(c, unverified, f.mapper) }
    }
}
