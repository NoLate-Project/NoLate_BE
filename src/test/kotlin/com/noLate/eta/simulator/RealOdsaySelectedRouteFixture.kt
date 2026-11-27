package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode

/** Test-only saved route built from an actual ODsay response, never a relabelled Tmap route. */
object RealOdsaySelectedRouteFixture {
    data class PublicMixedRoute(
        val railLine: SeoulSubwayLine,
        val railFrom: String,
        val railTo: String,
        val busNumber: String,
        val busFrom: String,
        val busTo: String,
        val busFromArs: String? = null,
        val busToArs: String? = null,
    ) {
        init {
            require(listOf(railFrom, railTo, busNumber, busFrom, busTo).all { it.isNotBlank() && it != "UNKNOWN" })
            require(railFrom != railTo && busFrom != busTo)
            require(listOfNotNull(busFromArs, busToArs).all { it != "00000" && it.matches(Regex("[0-9]{5}")) })
        }

        fun matches(journey: TransitJourney): Boolean = journey.rideLegs.all { leg ->
            val from = leg.from ?: return@all false
            val to = leg.to ?: return@all false
            when (leg.mode) {
                TransitLegMode.BUS -> leg.line?.name == busNumber && from.name == busFrom && to.name == busTo &&
                    (busFromArs == null || from.arsId?.filter(Char::isDigit) == busFromArs) &&
                    (busToArs == null || to.arsId?.filter(Char::isDigit) == busToArs)

                TransitLegMode.SUBWAY -> leg.line?.seoulSubwayLine == railLine && from.name == railFrom && to.name == railTo
                else -> false
            }
        }
    }

    fun select(
        candidates: List<TransitJourney>,
        direct: Boolean = false,
        overnight: Boolean = false,
        adjacentRing: Boolean = false,
        seolleungAccess: Boolean = false,
        shortFirstRide: Boolean = false,
        mixedBusFirst: Boolean? = null,
        publicMixedRoute: PublicMixedRoute? = null,
    ): TransitJourney {
        require(publicMixedRoute == null || mixedBusFirst != null)
        if (mixedBusFirst != null) {
            val mixedOrder = if (mixedBusFirst) listOf(TransitLegMode.BUS, TransitLegMode.SUBWAY)
                else listOf(TransitLegMode.SUBWAY, TransitLegMode.BUS)
            val eligible = candidates.filter { journey ->
                journey.provider == "odsay" && journey.rideLegs.map { it.mode } == mixedOrder &&
                    journey.legs.map { it.mode } == listOf(TransitLegMode.WALK, mixedOrder[0], TransitLegMode.WALK,
                        mixedOrder[1], TransitLegMode.WALK) && journey.rideLegs.all(::hasSupportedMixedRide)
            }
            if (publicMixedRoute == null) {
                return requireNotNull(eligible.firstOrNull()) { "Actual ODsay journey for the scoped stations required" }
            }
            val matching = eligible.filter(publicMixedRoute::matches).distinctBy { journey ->
                journey.rideLegs.map { leg -> listOf(leg.line, leg.from, leg.to, leg.directionCode, leg.directionName,
                    leg.orderedStopIds, leg.orderedStopNames) }
            }
            return requireNotNull(matching.singleOrNull()) { "One unambiguous ODsay journey for the public map route required" }
        }
        val stops = when {
            seolleungAccess -> listOf("강남" to "선릉")
            adjacentRing -> listOf("강남" to "역삼")
            overnight -> listOf("사당" to "고잔")
            direct -> listOf("노원" to "사당")
            shortFirstRide -> listOf("동작" to "사당", "사당" to "신림")
            else -> listOf("노원" to "사당", "사당" to "신림")
        }
        return requireNotNull(candidates.firstOrNull { journey ->
            val rides = journey.rideLegs
            journey.provider == "odsay" && rides.all { it.mode == TransitLegMode.SUBWAY } &&
                rides.map { it.from?.name to it.to?.name } == stops &&
                (!seolleungAccess || rides.single().orderedStopNames == listOf("강남", "역삼", "선릉"))
        }) { "Actual ODsay journey for the scoped stations required" }
    }

    private fun hasSupportedMixedRide(leg: TransitJourneyLeg): Boolean {
        val from = leg.from ?: return false
        val to = leg.to ?: return false
        val line = leg.line ?: return false
        if (from.providerStopId.isNullOrBlank() || to.providerStopId.isNullOrBlank() || from.name.isNullOrBlank() ||
            to.name.isNullOrBlank() || line.name.isNullOrBlank() || line.providerRouteId.isNullOrBlank()) return false
        if (leg.mode != TransitLegMode.BUS) return line.seoulSubwayLine != null
        val fromId = from.localStopId.orEmpty()
        val toId = to.localStopId.orEmpty()
        return fromId != toId && listOf(line.localRouteId.orEmpty(), fromId, toId).all(seoulBusId::matches)
    }

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
