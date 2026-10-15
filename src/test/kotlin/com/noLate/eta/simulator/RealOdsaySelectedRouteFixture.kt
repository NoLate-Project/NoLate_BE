package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitLegMode

/** Test-only saved route built from an actual ODsay response, never a relabelled Tmap route. */
object RealOdsaySelectedRouteFixture {
    fun select(candidates: List<TransitJourney>): TransitJourney = requireNotNull(candidates.firstOrNull { journey ->
        val rides = journey.rideLegs
        journey.provider == "odsay" && rides.size == 2 && rides.all { it.mode == TransitLegMode.SUBWAY } &&
            rides[0].from?.name == "노원" && rides[0].to?.name == "사당" &&
            rides[1].from?.name == "사당" && rides[1].to?.name == "신림"
    }) { "Actual ODsay Nowon-Sadang-Sillim journey required" }

    fun encode(mapper: ObjectMapper, journey: TransitJourney): String {
        require(journey.provider == "odsay")
        val legs = journey.legs.map { leg -> mapOf(
            "id" to "odsay-leg-${leg.sequence}", "kind" to leg.mode.name, "type" to leg.mode.name,
            "title" to (leg.from?.name ?: if (leg.mode == TransitLegMode.WALK) "도보" else "이동"),
            "durationMinutes" to leg.durationMinutes, "waitingMinutes" to leg.waitingMinutes,
            "providerRouteId" to leg.line?.providerRouteId, "lineName" to leg.line?.name,
            "serviceClass" to leg.line?.serviceClass?.name,
            "startID" to leg.from?.providerStopId, "endID" to leg.to?.providerStopId,
            "startName" to leg.from?.name, "endName" to leg.to?.name,
            "directionName" to leg.directionName, "directionCode" to leg.directionCode,
            "coordinates" to emptyList<Any>(),
        ) }
        return mapper.writeValueAsString(mapOf(
            "provider" to "odsay", "mode" to "TRANSIT", "minutes" to journey.totalMinutes,
            "providerTimeBasis" to "route_estimate", "transitLegs" to legs,
            "routeInfo" to mapOf("id" to "actual-odsay-public-stations", "provider" to "odsay",
                "originName" to "노원역", "destinationName" to "신림역",
                "totalDurationMinutes" to journey.totalMinutes,
                "departureTime" to journey.departureAt.toString(), "arrivalTime" to journey.arrivalAt.toString(),
                "timeBasis" to "estimated", "providerTimeBasis" to "route_estimate", "steps" to legs),
        ))
    }
}
