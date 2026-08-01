package com.noLate.schedule.infrastructure

import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficProviderClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.fallbackResult
import com.noLate.schedule.domain.ScheduleTravelMode
import org.springframework.stereotype.Component

/**
 * 이동수단에 맞는 ETA 공급자를 선택한다.
 *
 * TMAP의 활성화 여부와 무관하게 대중교통은 정류장 실시간 도착정보를 사용할 수 있어야 한다.
 * 도로/도보는 기존 conditional provider에 위임하고, 대중교통만 별도 계산기로 보낸다.
 */
@Component
class ModeAwareTrafficClient(
    private val trafficProviderClient: TrafficProviderClient,
    private val transitRealtimeTrafficClient: TransitRealtimeTrafficClient,
) : TrafficClient {
    override fun getTravelMinutes(request: TrafficRequest): TrafficResult {
        request.liveRefreshBlockedReason?.let { return request.fallbackResult(it) }
        return if (request.travelMode == ScheduleTravelMode.TRANSIT) {
            transitRealtimeTrafficClient.getTravelMinutes(request)
        } else {
            trafficProviderClient.getTravelMinutes(request)
        }
    }
}
