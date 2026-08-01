package com.noLate.eta.application

import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.ProjectedTransitJourney
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.TransitRealtimeOverlayResolution
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitJourneyTimingBasis
import com.noLate.eta.application.transit.TransitTransferEvaluation
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.application.transit.TransitTransferStatus
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.application.fallbackResult
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 대중교통 ETA의 단일 진입점.
 *
 * ODsay에서 선택한 전체 시간표 경로를 같은 공급자로 다시 찾고, 조회 시야 안에서는 첫 승차
 * 실시간 도착정보를 겹친다. ODsay 실패 시 TMAP으로 경로를 바꾸지 않고 저장 경로의 첫 승차
 * 보정만 사용하는 것이 사용자 선택 보존 정책이다.
 */
@Service
class TransitEtaCalculationService(
    private val selectedRouteDecoder: SelectedTransitRouteDecoder,
    private val journeyProviders: List<TransitJourneyProvider>,
    private val safeDepartureResolver: SafeDepartureResolver,
    private val firstBoardingRealtimeOverlay: FirstBoardingRealtimeOverlay,
    private val journeyMatcher: TransitJourneyMatcher,
    private val transferFeasibilityEvaluator: TransitTransferFeasibilityEvaluator,
) {

    fun calculate(request: TrafficRequest): TrafficResult {
        require(request.travelMode == ScheduleTravelMode.TRANSIT) {
            "TransitEtaCalculationService는 TRANSIT 요청만 처리합니다."
        }
        request.liveRefreshBlockedReason?.let { return request.fallbackResult(it) }

        val selected = selectedRouteDecoder.decode(request.selectedRouteJson)
            ?: return request.fallbackResult(TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING)
        val provider = selected.odsayProvider()
        if (provider != null) {
            val refreshed = runCatching {
                resolveOdsayJourney(provider, selected, request)
            }
            val resolution = refreshed.getOrNull()
            val projected = resolution?.bestFeasibleLive ?: resolution?.projected
            if (projected != null) {
                // 한 번이라도 실제 차량이 마감 이후 도착한다고 확인한 뒤 arrival provider가
                // 일시적으로 비어 버린 시간표 후보를 정상 결과로 승격하지 않는다.
                val bestLateLive = resolution?.bestLateLive
                if (projected.projection.overlayResolution.overlay == null && bestLateLive != null) {
                    return onTimeUnavailableResult(bestLateLive, request)
                }
                return refreshedResult(projected, request)
            }
            resolution?.bestLateLive?.let {
                return onTimeUnavailableResult(it, request)
            }
            resolution?.bestLate?.let {
                return onTimeUnavailableResult(it, request)
            }
            resolution?.bestTransferUnknown?.let {
                return transferDiagnosticResult(
                    projected = it,
                    request = request,
                    reason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
                )
            }
            resolution?.bestMissedTransfer?.let {
                return transferDiagnosticResult(
                    projected = it,
                    request = request,
                    reason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                )
            }

            val reason = if (refreshed.isFailure) {
                TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE
            } else {
                TrafficFailureReasons.TRANSIT_SELECTED_ROUTE_NOT_FOUND
            }
            return legacyResult(selected, request, reason)
        }

        val reason = if (selected.provider.equals(ODSAY_PROVIDER_ID, ignoreCase = true)) {
            TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE
        } else {
            null
        }
        return legacyResult(selected, request, reason)
    }

    private fun resolveOdsayJourney(
        provider: TransitJourneyProvider,
        selected: SelectedTransitRoute,
        request: TrafficRequest,
    ): OdsayJourneyResolution {
        val evaluatedAt = request.evaluatedAt
        val initialDepartureAt = maxOf(
            evaluatedAt,
            request.plannedDepartureAt ?: evaluatedAt,
        )
        var bestLate: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestLateLive: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestFeasibleLive: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestMissedTransfer: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestTransferUnknown: ProjectedTransitJourney<TransitCandidateProjection>? = null
        val projected = safeDepartureResolver.resolveProjectedCandidates(
            provider = provider,
            request = TransitJourneySearchRequest(
                originLat = request.originLat,
                originLng = request.originLng,
                destinationLat = request.destinationLat,
                destinationLng = request.destinationLng,
                departureAt = initialDepartureAt,
                maxTravelMinutes = request.maxTravelMinutes,
            ),
            targetArrivalAt = request.targetArrivalAt,
            evaluatedAt = evaluatedAt,
        ) { candidates ->
            val selectedJourney = journeyMatcher.findSelected(selected, candidates)
            val selectedProjection = selectedJourney?.let {
                projectJourney(
                    journey = it,
                    routeProvenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                    request = request,
                )
            }
            selectedProjection?.recordTransferDiagnostic(
                onMissed = { bestMissedTransfer = earlierArrival(bestMissedTransfer, it) },
                onUnknown = { bestTransferUnknown = earlierArrival(bestTransferUnknown, it) },
            )

            // 시간표가 불완전한 선택 경로는 임의 대체로 바꾸지 않고 낮은 신뢰도로 보존한다.
            if (selectedProjection?.projection?.transfer?.status == TransitTransferStatus.UNKNOWN) {
                return@resolveProjectedCandidates selectedProjection
            }

            // 다른 itinerary의 승하차 상세를 앱에 전달하는 계약이 없으므로 선택 경로만
            // 계산한다. 대체 후보를 진단 명목으로 projection해도 arrival provider 호출과
            // 회로/timeout budget만 소비하므로 이 경로에서는 아예 평가하지 않는다.
            val candidate = selectedProjection ?: return@resolveProjectedCandidates null

            val overlayResolution = candidate.projection.overlayResolution
            if (
                candidate.eligible &&
                request.targetArrivalAt?.let(candidate.predictedArrivalAt::isAfter) == true
            ) {
                bestLate = earlierArrival(bestLate, candidate)
                if (overlayResolution.overlay != null) {
                    bestLateLive = earlierArrival(bestLateLive, candidate)
                }
            } else if (
                request.targetArrivalAt != null &&
                overlayResolution.overlay != null &&
                candidate.eligible
            ) {
                bestFeasibleLive = laterDeparture(bestFeasibleLive, candidate)
            }
            candidate
        }
        return OdsayJourneyResolution(
            projected = projected,
            bestLate = bestLate,
            bestLateLive = bestLateLive,
            bestFeasibleLive = bestFeasibleLive,
            bestMissedTransfer = bestMissedTransfer,
            bestTransferUnknown = bestTransferUnknown,
        )
    }

    private fun projectJourney(
        journey: com.noLate.eta.domain.TransitJourney,
        routeProvenance: TransitRouteProvenance,
        request: TrafficRequest,
    ): ProjectedTransitJourney<TransitCandidateProjection> {
        val overlayResolution = firstBoardingRealtimeOverlay.resolve(
            journey = journey,
            evaluatedAt = request.evaluatedAt,
            maxTravelMinutes = request.maxTravelMinutes,
        )
        val transfer = transferFeasibilityEvaluator.evaluate(
            journey = journey,
            firstBoardingOverlay = overlayResolution.overlay,
            evaluatedAt = request.evaluatedAt,
        )
        return ProjectedTransitJourney(
            journey = journey,
            predictedArrivalAt = transfer.predictedArrivalAt,
            projection = TransitCandidateProjection(
                overlayResolution = overlayResolution,
                transfer = transfer,
                routeProvenance = routeProvenance,
            ),
            eligible = transfer.eligible,
            searchAdjustmentSeconds = transfer.searchEarlierBySeconds
                ?.let { -maxOf(it, 60L) },
        )
    }

    private fun refreshedResult(
        projected: ProjectedTransitJourney<TransitCandidateProjection>,
        request: TrafficRequest,
    ): TrafficResult {
        val journey = projected.journey
        val overlay = projected.projection.overlayResolution.overlay
        val transfer = projected.projection.transfer
        if (transfer.status != TransitTransferStatus.FEASIBLE) {
            return transferDiagnosticResult(
                projected = projected,
                request = request,
                reason = if (transfer.status == TransitTransferStatus.UNKNOWN) {
                    TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN
                } else {
                    TrafficFailureReasons.TRANSIT_TRANSFER_MISSED
                },
            )
        }
        val travelMinutes = transfer.travelMinutes
        val predictedArrivalAt = projected.predictedArrivalAt
        require(request.targetArrivalAt == null || !predictedArrivalAt.isAfter(request.targetArrivalAt)) {
            "정상 대중교통 ETA의 예측 도착시각은 도착 마감시각을 넘을 수 없습니다."
        }
        val recommendedDepartureAt = journey.departureAt.coerceAtLeast(request.evaluatedAt)
        val overlayFailureReason = projected.projection.overlayResolution.failureReason
            ?: TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE.takeIf { overlay == null }

        return TrafficResult(
            travelMinutes = travelMinutes,
            source = if (overlay == null) {
                TrafficSource.TIMETABLE_PROVIDER
            } else {
                TrafficSource.LIVE_PROVIDER
            },
            fetchedAt = overlay?.observedAt ?: journey.fetchedAt,
            // A timetable refresh and a realtime arrival lookup are separate evidence. Do not
            // silently turn an unavailable/stale first-boarding feed into a fully accepted ETA.
            stale = overlayFailureReason != null,
            failureReason = overlayFailureReason,
            recommendedDepartureAt = recommendedDepartureAt,
            predictedArrivalAt = predictedArrivalAt,
            transitRouteProvenance = projected.projection.routeProvenance,
            transitTimingBasis = transfer.timingBasis.toTrafficTimingBasis(),
        )
    }

    private fun legacyResult(
        selected: SelectedTransitRoute,
        request: TrafficRequest,
        refreshFailureReason: String?,
    ): TrafficResult {
        // ODsay 전체 itinerary 갱신이 실패한 뒤 첫 승차만 보정하면 이후 환승을 검증하지
        // 못한 recommendedDepartureAt이 worker/native alarm으로 승격된다. 저장 ETA로 닫는다.
        refreshFailureReason?.let { return request.fallbackResult(it) }
        if (selected.rides.size > 1) {
            return request.fallbackResult(
                TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED
            )
        }
        val plan = selected.legacyBoardingPlan
            ?: return request.fallbackResult(
                TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING
            )
        val departureAt = maxOf(
            request.evaluatedAt,
            request.plannedDepartureAt ?: request.evaluatedAt,
        )
        val resolution = firstBoardingRealtimeOverlay.resolveLegacy(
            plan = plan,
            plannedDepartureAt = departureAt,
            evaluatedAt = request.evaluatedAt,
            maxTravelMinutes = request.maxTravelMinutes,
        )
        val overlay = resolution.overlay ?: return request.fallbackResult(
            resolution.failureReason
                ?: TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
        )
        if (request.targetArrivalAt?.let(overlay.predictedArrivalAt::isAfter) == true) {
            return onTimeUnavailableResult(
                predictedArrivalAt = overlay.predictedArrivalAt,
                fetchedAt = overlay.observedAt,
                source = TrafficSource.LIVE_PROVIDER,
                request = request,
            )
        }

        return TrafficResult(
            travelMinutes = overlay.travelMinutes,
            source = TrafficSource.LIVE_PROVIDER,
            fetchedAt = overlay.observedAt,
            stale = false,
            recommendedDepartureAt = departureAt,
            predictedArrivalAt = overlay.predictedArrivalAt,
        )
    }

    private fun onTimeUnavailableResult(
        projected: ProjectedTransitJourney<TransitCandidateProjection>,
        request: TrafficRequest,
    ): TrafficResult {
        val overlayResolution = projected.projection.overlayResolution
        if (overlayResolution.overlay == null) {
            return degradedTimetableResult(
                projected = projected,
                request = request,
                reason = overlayResolution.failureReason
                    ?: TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE,
            )
        }
        val overlay = overlayResolution.overlay
        return onTimeUnavailableResult(
            predictedArrivalAt = projected.predictedArrivalAt,
            fetchedAt = overlay.observedAt,
            source = TrafficSource.LIVE_PROVIDER,
            request = request,
            routeProvenance = projected.projection.routeProvenance,
            timingBasis = projected.projection.transfer.timingBasis.toTrafficTimingBasis(),
        )
    }

    private fun degradedTimetableResult(
        projected: ProjectedTransitJourney<TransitCandidateProjection>,
        request: TrafficRequest,
        reason: String,
    ): TrafficResult = TrafficResult(
        travelMinutes = projected.projection.transfer.travelMinutes,
        source = TrafficSource.TIMETABLE_PROVIDER,
        fetchedAt = projected.journey.fetchedAt,
        stale = true,
        failureReason = reason,
        recommendedDepartureAt = projected.journey.departureAt.coerceAtLeast(request.evaluatedAt),
        predictedArrivalAt = projected.predictedArrivalAt,
        transitRouteProvenance = projected.projection.routeProvenance,
        transitTimingBasis = projected.projection.transfer.timingBasis.toTrafficTimingBasis(),
    )

    private fun onTimeUnavailableResult(
        predictedArrivalAt: Instant,
        fetchedAt: Instant,
        source: TrafficSource,
        request: TrafficRequest,
        routeProvenance: TransitRouteProvenance? = null,
        timingBasis: TransitTimingBasis? = null,
    ): TrafficResult {
        val travelMinutes = EtaTravelTimePolicy.normalizeMinutes(
            Duration.between(request.evaluatedAt, predictedArrivalAt).toMillis() / MILLIS_PER_MINUTE,
            request.maxTravelMinutes,
        ) ?: return request.fallbackResult(TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE)
        return TrafficResult(
            travelMinutes = travelMinutes,
            source = source,
            fetchedAt = fetchedAt,
            stale = true,
            failureReason = TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            // worker 계약을 바꾸지 않는 범위에서 가장 안전한 행동은 즉시 출발 안내다.
            recommendedDepartureAt = request.evaluatedAt,
            predictedArrivalAt = predictedArrivalAt,
            transitRouteProvenance = routeProvenance,
            transitTimingBasis = timingBasis,
        )
    }

    private fun transferDiagnosticResult(
        projected: ProjectedTransitJourney<TransitCandidateProjection>,
        request: TrafficRequest,
        reason: String,
    ): TrafficResult {
        return request.fallbackResult(reason).copy(
            // MISSED는 실제로 다음 차량을 타지 못한 경로이고 UNKNOWN은 다음 차량의
            // 시간표 자체가 없다. 둘 다 원래 시간표 도착시각을 사용자 도착 예측으로
            // 노출하면 worker가 거짓 정시 가능 판정을 만들 수 있으므로 진단 계산에만 쓴다.
            predictedArrivalAt = null,
            transitRouteProvenance = projected.projection.routeProvenance,
            transitTimingBasis = projected.projection.transfer.timingBasis.toTrafficTimingBasis(),
        )
    }

    private fun <T> earlierArrival(
        current: ProjectedTransitJourney<T>?,
        candidate: ProjectedTransitJourney<T>,
    ): ProjectedTransitJourney<T> =
        listOfNotNull(current, candidate)
            .minWith(
                compareBy<ProjectedTransitJourney<T>> { it.predictedArrivalAt }
                    .thenBy { it.journey.departureAt }
            )

    private fun <T> laterDeparture(
        current: ProjectedTransitJourney<T>?,
        candidate: ProjectedTransitJourney<T>,
    ): ProjectedTransitJourney<T> =
        listOfNotNull(current, candidate)
            .maxWith(
                compareBy<ProjectedTransitJourney<T>> { it.journey.departureAt }
                    .thenByDescending { it.predictedArrivalAt }
            )

    private fun ProjectedTransitJourney<TransitCandidateProjection>.recordTransferDiagnostic(
        onMissed: (ProjectedTransitJourney<TransitCandidateProjection>) -> Unit,
        onUnknown: (ProjectedTransitJourney<TransitCandidateProjection>) -> Unit,
    ) {
        when (projection.transfer.status) {
            TransitTransferStatus.MISSED -> onMissed(this)
            TransitTransferStatus.UNKNOWN -> onUnknown(this)
            TransitTransferStatus.FEASIBLE -> Unit
        }
    }

    private fun TransitJourneyTimingBasis.toTrafficTimingBasis(): TransitTimingBasis = when (this) {
        TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE ->
            TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE
        TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN ->
            TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN
        TransitJourneyTimingBasis.TIMETABLE_ONLY -> TransitTimingBasis.TIMETABLE_ONLY
        TransitJourneyTimingBasis.TIMETABLE_TRANSFER_UNKNOWN ->
            TransitTimingBasis.TIMETABLE_TRANSFER_UNKNOWN
    }

    private fun Instant.coerceAtLeast(minimum: Instant): Instant =
        if (isBefore(minimum)) minimum else this

    private fun SelectedTransitRoute.odsayProvider(): TransitJourneyProvider? {
        if (!provider.equals(ODSAY_PROVIDER_ID, ignoreCase = true)) return null
        return journeyProviders.singleOrNull {
            it.providerId.equals(ODSAY_PROVIDER_ID, ignoreCase = true)
        }
    }

    private companion object {
        const val ODSAY_PROVIDER_ID = "odsay"
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}

private data class OdsayJourneyResolution(
    val projected: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestLate: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestLateLive: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestFeasibleLive: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestMissedTransfer: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestTransferUnknown: ProjectedTransitJourney<TransitCandidateProjection>?,
)

private data class TransitCandidateProjection(
    val overlayResolution: TransitRealtimeOverlayResolution,
    val transfer: TransitTransferEvaluation,
    val routeProvenance: TransitRouteProvenance,
)
