package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitLegMode

/** Test-only saved route built from an actual ODsay response, never a relabelled Tmap route. */
object RealOdsaySelectedRouteFixture {
    fun select(
        candidates: List<TransitJourney>,
        direct: Boolean = false,
        overnight: Boolean = false,
        adjacentRing: Boolean = false,
        seolleungAccess: Boolean = false,
        shortFirstRide: Boolean = false,
        mixedBusFirst: Boolean? = null,
    ): TransitJourney = requireNotNull(candidates.firstOrNull { journey ->
        val rides = journey.rideLegs
        if (mixedBusFirst != null) {
            val mixedOrder = if (mixedBusFirst) listOf(TransitLegMode.BUS, TransitLegMode.SUBWAY)
                else listOf(TransitLegMode.SUBWAY, TransitLegMode.BUS)
            return@firstOrNull journey.provider == "odsay" && rides.map { it.mode } == mixedOrder &&
                journey.legs.map { it.mode } == listOf(TransitLegMode.WALK, mixedOrder[0], TransitLegMode.WALK,
                    mixedOrder[1], TransitLegMode.WALK) && rides.all { leg ->
                    val from = leg.from
                    val to = leg.to
                    val line = leg.line
                    if (from?.providerStopId.isNullOrBlank() || to?.providerStopId.isNullOrBlank() ||
                        from?.name.isNullOrBlank() || to?.name.isNullOrBlank() || line?.name.isNullOrBlank() ||
                        line?.providerRouteId.isNullOrBlank()) return@all false
                    if (leg.mode == TransitLegMode.BUS) {
                        val fromId = from?.localStopId.orEmpty()
                        val toId = to?.localStopId.orEmpty()
                        fromId != toId && listOf(line?.localRouteId.orEmpty(), fromId, toId).all(seoulBusId::matches)
                    } else line?.seoulSubwayLine != null
                }
        }
        journey.provider == "odsay" && rides.all { it.mode == TransitLegMode.SUBWAY } &&
            rides.map { it.from?.name to it.to?.name } == when {
                seolleungAccess -> listOf("강남" to "선릉")
                adjacentRing -> listOf("강남" to "역삼")
                overnight -> listOf("사당" to "고잔")
                direct -> listOf("노원" to "사당")
                shortFirstRide -> listOf("동작" to "사당", "사당" to "신림")
                else -> listOf("노원" to "사당", "사당" to "신림")
            } && (!seolleungAccess || rides.single().orderedStopNames == listOf("강남", "역삼", "선릉"))
    }) { "Actual ODsay journey for the scoped stations required" }

    private val seoulBusId = Regex("[1-9][0-9]{8}")

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
            "startArsID" to leg.from?.arsId, "endArsID" to leg.to?.arsId,
            "directionName" to leg.directionName, "directionCode" to leg.directionCode,
            "coordinates" to emptyList<Any>(),
        ) }
        return mapper.writeValueAsString(mapOf(
            "provider" to "odsay", "mode" to "TRANSIT", "minutes" to journey.totalMinutes,
            "providerTimeBasis" to "route_estimate", "transitLegs" to legs,
            "routeInfo" to mapOf("id" to "actual-odsay-public-stations", "provider" to "odsay",
                "originName" to (journey.rideLegs.first().from?.name + "역"),
                "destinationName" to (journey.rideLegs.last().to?.name + "역"),
                "totalDurationMinutes" to journey.totalMinutes,
                "departureTime" to journey.departureAt.toString(), "arrivalTime" to journey.arrivalAt.toString(),
                "timeBasis" to "estimated", "providerTimeBasis" to "route_estimate", "steps" to legs),
        ))
    }
}
