package com.noLate.journey.application

import com.noLate.journey.domain.*
import com.noLate.transit.domain.TransitArrivalDto
import java.time.Instant

/** Pure projection. Names, unknown namespaces and incoming vehicle IDs never establish boarding. */
class JourneyBusArrivalMatcher {
    fun match(sessionId: String, command: JourneyBusArrivalCommand, arrivals: List<TransitArrivalDto>, now: Instant): JourneyBusArrivalResult {
        require(command.query.valid())
        val query = command.query
        val matching = arrivals.take(10).filter {
            it.provider == "seoul-bus" && it.kind == "BUS" && it.arsId == query.arsId &&
                it.providerRouteId == query.providerRouteId && it.direction == query.directionLabel
        }.map { it to JourneyTransitObservationAdapter().normalize(it, now) }
        val selected = matching.filter { it.second.waitingObservationUsable }
            .minByOrNull { it.second.expectedAt!! } ?: matching.firstOrNull()
        return JourneyBusArrivalResult(
            sessionId, command.generation, command.routeRevision, command.routeInputDigest, query, now,
            when {
                arrivals.isEmpty() -> "NO_ARRIVAL"
                selected == null -> "IDENTITY_MISMATCH"
                selected.second.waitingObservationUsable -> "WAITING_INFORMATION"
                else -> selected.second.quality.name
            }, selected?.second,
            selected?.first?.vehicleId?.takeIf { selected.second.waitingObservationUsable && it.matches(Regex("[1-9][0-9]{0,19}")) },
        )
    }
}
