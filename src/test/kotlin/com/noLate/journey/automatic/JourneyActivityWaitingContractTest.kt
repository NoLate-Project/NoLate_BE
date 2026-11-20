package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.JourneyActivityContract
import com.noLate.journey.application.JourneyRouteSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Match the native WAITING copy without asserting that the passenger boarded. */
class JourneyActivityWaitingContractTest {
    @Test fun `first and transfer subway waiting reports work with and without an arrival`() {
        for (index in listOf(1, 3)) for (arrival in listOf(false, true)) {
            val f = AutomaticJourneyFixture().also { TransferRideFixture.apply(it) }
            val route = JourneyRouteSnapshot.from(f.plan, f.mapper).let { value ->
                value.copy(legs = value.legs.mapIndexed { i, leg ->
                    if (i == index) leg.copy(kind = "SUBWAY", lineName = "2호선") else leg
                })
            }
            val service = f.service()
            service.prepare(7, 9001, 2, f.prepareCommand())
            service.departure(7, 9001, 2, f.sessionId, f.event())
            val bound = service.validate(7, 9001, 2, f.sessionId, f.stopCommand())
            val now = f.clock.instant().epochSecond
            val body = f.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${f.owner}","routeInputDigest":"${f.digest}","activityId":"waiting-contract","fcmRegistrationToken":"synthetic","liveActivityToken":"${"a".repeat(64)}","timestamp":$now,"contentState":{"schemaVersion":${if (index == 1) 1 else if (arrival) 7 else 6},"sessionId":"${f.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${f.digest}","sequence":10,"status":"ACTIVE","phase":"WAITING","quality":"GOOD","instruction":"승차역에서 열차 대기 · 탑승 미확정","activeLegIndex":$index,"walkProgress":1.0,"boardingConfirmed":false,"observedAt":$now,"validUntil":${now+15}}}""") as ObjectNode
            val content = body.path("contentState") as ObjectNode
            if (arrival) {
                content.put("arrivalStatus", "WAITING_INFORMATION")
                content.putObject("arrival").put("kind", "SUBWAY").put("busLegIndex", index)
                    .put("providerRouteId", "2호선").put("directionLabel", "성수행")
                    .put("expectedAt", now+120).put("sourceUpdatedAt", now).put("validUntil", now+120)
            }
            assertNotNull(JourneyActivityContract.parse(body, f.sessionId, 9001, bound, f.clock.instant(), f.mapper))
            JourneyActivityContract.validateWaitingSavedLeg(content, route)
            JourneyActivityContract.validateArrivalSavedLeg(content, route)
            val busRoute = route.copy(legs = route.legs.mapIndexed { i, leg -> if (i == index) leg.copy(kind = "BUS") else leg })
            assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateWaitingSavedLeg(content, busRoute) }
            content.put("instruction", "정류장 대기 · 탑승 미확정")
            JourneyActivityContract.validateWaitingSavedLeg(content, busRoute)
            assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateWaitingSavedLeg(content, route) }
            content.put("activeLegIndex", index-1)
            assertFailsWith<IllegalArgumentException> { JourneyActivityContract.validateWaitingSavedLeg(content, route) }
        }
    }
}
