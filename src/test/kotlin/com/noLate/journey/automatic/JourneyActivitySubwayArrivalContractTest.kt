package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyActivityContract
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Platform arrival payloads emitted by the native Codable content. */
class JourneyActivitySubwayArrivalContractTest {
    private fun body(f: AutomaticJourneyFixture): ObjectNode {
        val now = f.clock.instant().epochSecond
        return f.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","activityId":"native-content","fcmRegistrationToken":"synthetic","liveActivityToken":"${"a".repeat(64)}","timestamp":$now,"contentState":{"schemaVersion":1,"sessionId":"${f.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${f.digest}","sequence":10,"status":"ACTIVE","phase":"WALKING_TO_STOP","quality":"GOOD","instruction":"승차 정류장으로 이동 중","activeLegIndex":0,"boardingConfirmed":false,"observedAt":$now,"validUntil":${now+15}}}""") as ObjectNode
    }

    private fun parse(f: AutomaticJourneyFixture, body: ObjectNode) {
        val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand())
        service.departure(7, 9001, 2, f.sessionId, f.event())
        val bound = service.validate(7, 9001, 2, f.sessionId, f.stopCommand())
        assertNotNull(JourneyActivityContract.parse(body, f.sessionId, 9001, bound, f.clock.instant(), f.mapper))
    }

    @Test fun `native subway arrival includes kind and occurrence at first and transfer platforms`() {
        for (index in listOf(0, 2)) {
            val f = AutomaticJourneyFixture()
            parse(f, arrivalBody(f, index))
        }
    }

    private fun arrivalBody(f: AutomaticJourneyFixture, index: Int = 0) = body(f).also { b ->
        val c = b.path("contentState") as ObjectNode
        c.put("schemaVersion", if (index == 0) 1 else 7)
        c.put("phase", "WALKING_TO_STOP"); c.put("quality", "GOOD")
        c.put("instruction", "승차 정류장으로 이동 중"); c.put("activeLegIndex", index)
        c.put("walkProgress", 0.95); c.put("arrivalStatus", "WAITING_INFORMATION")
        val now = f.clock.instant().epochSecond
        c.put("observedAt", now); c.put("validUntil", now+15)
        c.putObject("arrival").put("busLegIndex", index+1).put("kind", "SUBWAY")
            .put("providerRouteId", "2호선").put("directionLabel", "성수행")
            .put("expectedAt", now+120).put("sourceUpdatedAt", now).put("validUntil", now+120)
    }

    @Test fun `bus arrival from the previous leg cannot be shown at a transfer platform`() {
        val f = AutomaticJourneyFixture()
        val route = syntheticSavedSubwayRoute(f.mapper, transfer=true)
        (route.path("transitLegs")[3] as ObjectNode).put("lineName","수도권 2호선")
        val snapshot = com.noLate.journey.application.JourneyRouteSnapshot.from(f.plan.copy(route=route),f.mapper)
        val content = arrivalBody(f, 2).path("contentState") as ObjectNode
        JourneyActivityContract.validateArrivalSavedLeg(content,snapshot)
        (content.path("arrival") as ObjectNode).remove("kind")
        assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateArrivalSavedLeg(content,snapshot) }
    }

    @Test fun `subway arrivals reject wrong occurrence unsupported kind early approach and expired information`() {
        for (mode in listOf("index", "kind", "line", "early", "expired", "stale", "future", "boarding")) {
            val f = AutomaticJourneyFixture()
            val b = arrivalBody(f)
            val c = b.path("contentState") as ObjectNode
            val a = c.path("arrival") as ObjectNode
            val now = f.clock.instant().epochSecond
            when (mode) {
                "index" -> a.put("busLegIndex", 3)
                "kind" -> a.put("kind", "OTHER")
                "line" -> a.put("providerRouteId", "9호선")
                "early" -> c.put("walkProgress", 0.5)
                "expired" -> a.put("validUntil", now)
                "stale" -> a.put("sourceUpdatedAt", now-121)
                "future" -> a.put("sourceUpdatedAt", now+6)
                "boarding" -> c.put("boardingConfirmed", true)
            }
            assertFailsWith<IllegalArgumentException>(mode) { parse(f, b) }
        }
    }
}
