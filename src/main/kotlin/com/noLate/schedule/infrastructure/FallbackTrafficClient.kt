package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "schedule.traffic.tmap",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class FallbackTrafficClient : TrafficClient {
    override fun getTravelMinutes(request: TrafficRequest): Int =
        request.selectedRouteTravelMinutes ?: request.fallbackTravelMinutes
}
