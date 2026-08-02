package com.noLate.schedule.infrastructure

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaSoftDeadlineExceededException
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.fallbackResult
import org.springframework.stereotype.Component

/**
 * 기존 schedule 패키지와의 호환 어댑터.
 *
 * 대중교통 시간표 재조회, 경로 매칭, 실시간 첫 승차 보정은 모두 com.noLate.eta에서 수행한다.
 */
@Component
class TransitRealtimeTrafficClient(
    private val transitEtaCalculationService: TransitEtaCalculationService,
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
) {
    fun getTravelMinutes(request: TrafficRequest): TrafficResult = try {
        calculationDeadline.within {
            transitEtaCalculationService.calculate(request)
        }
    } catch (_: EtaSoftDeadlineExceededException) {
        // A soft deadline is a quality boundary, not a reason to publish a late live value. The
        // saved selected-route ETA remains available and the next durable worker cycle can retry.
        request.fallbackResult(TrafficFailureReasons.PROVIDER_TIMEOUT)
    }
}
