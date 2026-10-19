package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.transit.domain.*
import java.time.Instant

/** Artificial records only. Not a recording of Seoul 7016 or a real passenger/bus. */
fun AutomaticJourneyFixture.useRideGuidancePlan() {
    useSavedGuidancePlan(mapped = true)
    val bus = plan.route!!.path("transitLegs")[1] as ObjectNode
    bus.put("localRouteId", "999999999").put("startLocalStationID", "999999991")
    val evidence = bus.path("navigationEvidence") as ObjectNode
    val identifiers = evidence.path("busIdentifiers") as ObjectNode
    identifiers.put("localRouteId", "999999999").put("startLocalStationID", "999999991")
    evidence.path("stopOccurrences").forEachIndexed { i, row ->
        (row as ObjectNode).put("localStationID", (999999991 + i).toString()).put("code", (999999991 + i).toString())
    }
}

internal fun rideFixtureStops(): List<SeoulBusRouteStop> = (0..2).map { i ->
    SeoulBusRouteStop("999999999", 44 + i, (999999991 + i).toString(), (99999 - i).toString(),
        "합성 정류장 ${i + 1}", "합성 동쪽 방면", "999999990", 37.5665, 126.9795 + i * 0.005)
}
internal fun rideFixtureShape(): List<SeoulBusRoutePathPoint> = (0..100).map { i ->
    SeoulBusRoutePathPoint(700 + i, 37.5665, 126.9795 + i * 0.0001)
}
internal fun rideDepartureFixtureStops(): List<SeoulBusRouteStop> = rideFixtureStops() +
    SeoulBusRouteStop("999999999",47,"999999994","99996","합성 다음 정류장","합성 동쪽 방면","999999990",37.5665,126.992)
internal fun rideDepartureFixtureShape(): List<SeoulBusRoutePathPoint> = (0..125).map { i ->
    SeoulBusRoutePathPoint(700+i,37.5665,126.9795+i*0.0001)
}
/** Synthetic regression for an unselected long tolerance passage; NOT official 7016 data. */
internal fun rideCatalogBendFixtureStops(): List<SeoulBusRouteStop> {
    val scale = 6_371_000 * Math.PI / 180 * kotlin.math.cos(Math.toRadians(37.5665))
    return rideDepartureFixtureStops() + listOf(400.0, 550.0).mapIndexed { i, meters ->
        SeoulBusRouteStop("999999999",48+i,(999999995+i).toString(),(99995-i).toString(),
            "합성 굽은 구간 ${i+1}","합성 동쪽 방면","999999990",37.5665,126.992+meters/scale)
    }
}
internal fun rideCatalogBendFixtureShape(): List<SeoulBusRoutePathPoint> {
    val scale = 6_371_000 * Math.PI / 180 * kotlin.math.cos(Math.toRadians(37.5665))
    return rideDepartureFixtureShape() + listOf(200.0,370.0,430.0,370.0,430.0,600.0).mapIndexed { i, meters ->
        SeoulBusRoutePathPoint(826+i,37.5665,126.992+meters/scale)
    }
}
internal fun rideFixtureVehicle(now: Instant, sourceAt: Instant = now, alongMeters: Double = 100.0): SeoulBusVehicleObservation {
    val scale = 6_371_000 * Math.PI / 180 * kotlin.math.cos(Math.toRadians(37.5665))
    return SeoulBusVehicleObservation("999999998", "999999999", "999999990", 501, 0, false,
        "999999993", sourceAt, now, sourceAt.plusSeconds(15), SeoulBusVehicleCoordinate(37.5665, 126.9795 + alongMeters / scale))
}

/** Arrival metadata is independent of ride evidence, but must name the same synthetic route. */
internal fun authenticatedHarnessArrival(now: Instant, rideEnabled: Boolean) = TransitArrivalDto(
    provider = "seoul-bus", kind = "BUS", routeName = "합성 버스", stationName = "합성 승차",
    arsId = "99999", providerRouteId = if (rideEnabled) "999999999" else "999999",
    direction = "합성 동쪽 방면", vehicleId = "999997", waitSeconds = 120,
    sourceUpdatedAt = now.toString(), observedAt = now.toString(), expectedAt = now.plusSeconds(120).toString(),
    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
