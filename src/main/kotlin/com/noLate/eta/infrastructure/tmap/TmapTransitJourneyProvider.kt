package com.noLate.eta.infrastructure.tmap

import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.eta.resilience.EtaProviderGuard
import com.noLate.eta.resilience.StaticEtaProviderResiliencePolicyResolver
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.TransitEtaProviderMetricId
import com.noLate.global.observability.observeTransitEtaProviderCall
import com.noLate.route.application.TransitRouteProvider
import com.noLate.route.domain.TransitRouteRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 일반 길찾기 포트를 ETA 여정 포트에 연결하는 어댑터다.
 * 저장 경로의 providerId로 선택되므로 ODsay와 공존하며 HTTP 구현/키를 중복하지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "eta.transit.tmap", name = ["enabled"], havingValue = "true")
class TmapTransitJourneyProvider(
    routeProviders: List<TransitRouteProvider>,
    private val mapper: TmapTransitJourneyMapper,
    private val clock: Clock = Clock.systemUTC(),
    calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
    private val providerGuard: EtaProviderGuard = EtaProviderGuard(
        policyResolver = StaticEtaProviderResiliencePolicyResolver(),
        calculationDeadline = calculationDeadline,
    ),
    private val operationalMetrics: NoLateOperationalMetrics? = null,
) : TransitJourneyProvider {
    private val routeProvider = routeProviders.filter {
        it.providerId().trim().equals(PROVIDER_ID, ignoreCase = true)
    }.let { matches ->
        require(matches.size == 1) {
            "TMAP ETA 재조회에는 활성화된 TMAP 경로 공급자 하나가 필요합니다."
        }
        matches.single()
    }

    override val providerId = PROVIDER_ID

    // 시간표가 없는 다른 후보를 사용자 선택 경로 대신 채택하거나 ODsay 대체 경로로 기록하지 않는다.
    override val supportsAlternativeRouteRecovery = false

    // Tmap의 구간 초는 mapper가 요청 출발에 투영한다. 검색 HTTP는 기존 분 해상도를
    // 유지하되, 시간표 연결의 실제 출발을 다음 분으로 미뤄 탈 수 있는 열차를 버리지 않는다.
    override val supportsSecondPrecisionDepartureProjection = true

    override fun search(request: TransitJourneySearchRequest): List<TransitJourney> =
        operationalMetrics.observeTransitEtaProviderCall(
            provider = TransitEtaProviderMetricId.TMAP_ROUTE,
            isEmpty = { journeys -> journeys.isEmpty() },
        ) {
            providerGuard.execute(providerId) {
                val routeRequest = TransitRouteRequest(
                    startX = request.originLng,
                    startY = request.originLat,
                    endX = request.destinationLng,
                    endY = request.destinationLat,
                    searchDttm = SEARCH_TIME_FORMATTER.format(request.departureAt),
                ).validated()
                val response = routeProvider.getRoutes(routeRequest)
                // 평가 시작시각이 아니라 외부 응답을 실제 취득한 시각만 기록한다.
                mapper.map(response, request, Instant.now(clock))
            }
        }

    private companion object {
        const val PROVIDER_ID = "tmap"
        val SEARCH_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMddHHmm")
            .withZone(ZoneId.of("Asia/Seoul"))
    }
}
