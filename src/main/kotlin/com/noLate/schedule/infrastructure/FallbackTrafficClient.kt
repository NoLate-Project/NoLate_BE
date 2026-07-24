package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.fallbackResult
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
    override fun getTravelMinutes(request: TrafficRequest): TrafficResult =
        request.fallbackResult(
            request.liveRefreshBlockedReason
                ?: TrafficFailureReasons.PROVIDER_DISABLED
        )
}
