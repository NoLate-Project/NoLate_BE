package com.noLate.journey.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.journey.domain.JourneyBusArrivalCommand
import com.noLate.journey.domain.JourneyBusBindingCommand
import com.noLate.journey.domain.JourneyBusBindingResult
import com.noLate.transit.application.TransitArrivalService
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class JourneyBusArrivalService(
    private val automatic: AutomaticJourneyService,
    private val arrivals: TransitArrivalService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun binding(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: JourneyBusBindingCommand): JourneyBusBindingResult {
        val route = automatic.requireObservationFence(memberId, scheduleId, loginGeneration, sessionId, command)
        return JourneyBusBindingResult(sessionId, command.generation, command.routeRevision, command.routeInputDigest,
            JourneyBusRouteBindingResolver().resolve(route,command.selectedBusLegIndex()))
    }
    fun observe(memberId: Long, scheduleId: Long, loginGeneration: Long, sessionId: String, command: JourneyBusArrivalCommand) = run {
        if (!command.query.valid()) throw BusinessException(ErrorCode.INVALID_INPUT)
        val canonical = binding(memberId, scheduleId, loginGeneration, sessionId, command.fence()).mapping
        if (canonical.status != "SUPPORTED" || canonical.query != command.query) throw BusinessException(ErrorCode.INVALID_STATE, "저장된 경로와 도착 조회 식별자가 일치하지 않습니다.")
        // No station-name fallback or unnamespaced routing ID. Existing provider deadlines/rate limits apply.
        val values = arrivals.getBusArrivals(command.query.arsId, null, "1000", null, null, 10,
            cityCodeNamespace = com.noLate.transit.domain.TransitCityCodeNamespace.ODSAY_CID)
        // A response that arrives after revocation/route replacement must not be exposed as current.
        val after = binding(memberId, scheduleId, loginGeneration, sessionId, command.fence()).mapping
        if (after != canonical) throw BusinessException(ErrorCode.INVALID_STATE)
        JourneyBusArrivalMatcher().match(sessionId, command, values, clock.instant()).copy(identityEvidence = "SAVED_ROUTE_METADATA")
    }
}
