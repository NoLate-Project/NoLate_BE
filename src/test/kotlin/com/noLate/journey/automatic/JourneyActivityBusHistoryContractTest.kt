package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyActivityContract
import com.noLate.journey.application.JourneyRouteSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.*

class JourneyActivityBusHistoryContractTest {
    private fun fixture() = AutomaticJourneyFixture().also { TransferRideFixture.apply(it) }
    private fun body(f: AutomaticJourneyFixture, leg: Int, active: Int = leg): ObjectNode {
        val now = f.clock.instant().toEpochMilli() / 1000.0
        return f.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","activityId":"test-bus-history","fcmRegistrationToken":"synthetic","liveActivityToken":"${"a".repeat(64)}","timestamp":${f.clock.instant().epochSecond},"contentState":{"schemaVersion":1,"sessionId":"${f.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${f.digest}","sequence":10,"status":"ACTIVE","phase":"UNAVAILABLE","quality":"UNCERTAIN","instruction":"최근 버스 탑승 확인 · 현재 위치 재확인 중","activeLegIndex":$active,"boardingConfirmed":false,"observedAt":${now-20},"validUntil":${now-5},"lastConfirmedBus":{"legIndex":$leg,"confirmedAt":${now-20}}}}""") as ObjectNode
    }

    @Test fun `native recent bus history survives source gaps without becoming a live position`() {
        val f = fixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand()); service.departure(7, 9001, 2, f.sessionId, f.event())
        val bound = service.validate(7, 9001, 2, f.sessionId, f.stopCommand())
        for (leg in listOf(1, 3)) for (active in listOf(0, leg)) {
            val report = body(f, leg, active)
            if (active == 0) (report.path("contentState") as ObjectNode).put("instruction", "현재 위치 확인 필요")
            val parsed = JourneyActivityContract.parse(report, f.sessionId, 9001, bound, f.clock.instant(), f.mapper)
            assertNotNull(parsed.content["lastConfirmedBus"])
            assertNull(parsed.content["rideProgress"])
            assertEquals(false, parsed.content["boardingConfirmed"])
        }
    }

    @Test fun `bus history rejects stale future live conflicting or expanded reports`() {
        val f = fixture(); val service = f.service()
        service.prepare(7, 9001, 2, f.prepareCommand()); service.departure(7, 9001, 2, f.sessionId, f.event())
        val bound = service.validate(7, 9001, 2, f.sessionId, f.stopCommand())
        val now = f.clock.instant().toEpochMilli() / 1000.0
        val patches = listOf<(ObjectNode) -> Unit>(
            { (it.path("lastConfirmedBus") as ObjectNode).put("confirmedAt", now - 90) },
            { (it.path("lastConfirmedBus") as ObjectNode).put("confirmedAt", now + 1) },
            { (it.path("lastConfirmedBus") as ObjectNode).put("legIndex", 2) },
            { (it.path("lastConfirmedBus") as ObjectNode).put("latitude", 37.5) },
            { it.put("quality", "GOOD") }, { it.put("boardingConfirmed", true) },
            { it.put("rideProgress", 0.5) }, { it.put("activeLegIndex", 3) },
            { it.putObject("lastConfirmedSubway").put("stopIndex", 1) }
        )
        for (patch in patches) {
            val report = body(f, 1); patch(report.path("contentState") as ObjectNode)
            assertFailsWith<IllegalArgumentException> {
                JourneyActivityContract.parse(report, f.sessionId, 9001, bound, f.clock.instant(), f.mapper)
            }
        }
    }

    @Test fun `historical bus index must exist as a bus on the same saved topology`() {
        val f = fixture()
        val route = JourneyRouteSnapshot.from(f.plan, f.mapper)
        val report = body(f, 3).path("contentState")
        JourneyActivityContract.validateLastBusSavedLeg(report, route)
        val wrongKind = route.copy(legs = route.legs.mapIndexed { i, leg ->
            if (i == 3) leg.copy(kind = "SUBWAY") else leg
        })
        assertFailsWith<IllegalArgumentException> {
            JourneyActivityContract.validateLastBusSavedLeg(report, wrongKind)
        }
        for (index in listOf(0L, 2L, 5L, 29L, 4_294_967_299L)) {
            val invalid = body(f, 3).path("contentState") as ObjectNode
            (invalid.path("lastConfirmedBus") as ObjectNode).put("legIndex", index)
            assertFailsWith<IllegalArgumentException> {
                JourneyActivityContract.validateLastBusSavedLeg(invalid, route)
            }
        }
    }
}
