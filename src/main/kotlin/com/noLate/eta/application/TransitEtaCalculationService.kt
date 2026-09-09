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
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
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
 * 대중교통 ETA 계산의 단일 진입점.
 *
 * 저장된 경로의 providerId와 일치하는 TransitJourneyProvider를 선택해 전체 여정을 다시 조회한다.
 * 재조회한 시간표에 첫 승차 실시간 도착정보를 결합하고, 선택 경로가 안전하지 않을 때만
 * 동일 공급자 응답 안의 검증된 대체 여정을 평가한다.
 *
 * 공급자별 HTTP·JSON 차이는 adapter에서 끝내고, 이 서비스는 공통 TransitJourney만 사용한다.
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

        // 저장 경로와 같은 공급자만 선택해 서로 다른 공급자의 노선·정류장 ID가 섞이지 않도록 한다.
        val provider = selected.journeyProvider()
        if (provider != null) {
            val refreshed = runCatching {
                resolveProviderJourney(provider, selected, request)
            }
            val resolution = refreshed.getOrNull()
            val projected = resolution?.projected
            if (projected != null) {
                return refreshedResult(projected, request)
            }
            resolution?.bestLateLive?.let {
                return onTimeUnavailableResult(it, request)
            }
            resolution?.bestDegraded?.let {
                return degradedTimetableResult(
                    projected = it,
                    request = request,
                    reason = it.projection.overlayResolution.failureReason
                        ?: TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE,
                )
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

    /**
     * 선택된 공급자가 반환한 후보들을 공통 여정 모델로 평가한다.
     * 여기서는 공급자 종류와 관계없이 실시간 첫 승차, 환승 가능성, 도착 마감을 계산한다.
     */
    private fun resolveProviderJourney(
        provider: TransitJourneyProvider,
        selected: SelectedTransitRoute,
        request: TrafficRequest,
    ): TransitJourneyResolution {
        val evaluatedAt = request.evaluatedAt
        val initialDepartureAt = maxOf(
            evaluatedAt,
            request.plannedDepartureAt ?: evaluatedAt,
        )
        var bestSelectedActionable: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestAlternativeActionable: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestSelectedLateLive: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestAlternativeLateLive: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestSelectedDegraded: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestAlternativeDegraded: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestSelectedMissed: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestAlternativeMissed: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestSelectedUnknown: ProjectedTransitJourney<TransitCandidateProjection>? = null
        var bestAlternativeUnknown: ProjectedTransitJourney<TransitCandidateProjection>? = null
        safeDepartureResolver.resolveProjectedCandidates(
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
            val selectedJourneys = journeyMatcher.findSelectedCandidates(selected, candidates)
            val selectedProjections = selectedJourneys.mapNotNull { journey ->
                runCatching {
                    projectJourney(
                        journey = journey,
                        routeProvenance = TransitRouteProvenance.SELECTED_ROUTE_PRESERVED,
                        request = request,
                    )
                }.getOrNull()
            }
            selectedProjections.forEach { candidate ->
                candidate.recordResolutionEvidence(
                    request = request,
                    onLateLive = {
                        bestSelectedLateLive = earlierArrival(bestSelectedLateLive, it)
                    },
                    onDegraded = {
                        bestSelectedDegraded = earlierArrival(bestSelectedDegraded, it)
                    },
                    onMissed = { bestSelectedMissed = earlierArrival(bestSelectedMissed, it) },
                    onUnknown = { bestSelectedUnknown = earlierArrival(bestSelectedUnknown, it) },
                )
            }

            // 응답의 동일 signature 차량을 모두 확인한다. 마감이 없으면 다음 차량, 마감이
            // 있으면 정시 도착 가능한 가장 늦은 차량을 다른 경로보다 항상 우선한다.
            selectedProjections.nextActionableSelected(request)?.let {
                bestSelectedActionable = preferableActionable(
                    current = bestSelectedActionable,
                    candidate = it,
                    targetArrivalAt = request.targetArrivalAt,
                )
                return@resolveProjectedCandidates it
            }

            val selectedSet = selectedJourneys.toSet()
            val alternativeProjections = candidates
                .asSequence()
                .filter { it !in selectedSet }
                .filter { it.provider.equals(selected.provider, ignoreCase = true) }
                .filter { it.rideLegs.isNotEmpty() }
                .filter { !it.departureAt.isBefore(evaluatedAt) }
                .sortedWith(
                    compareBy<TransitJourney> { it.arrivalAt }
                        .thenBy { it.departureAt }
                )
                .take(MAX_ALTERNATIVE_CANDIDATES_PER_RESPONSE)
                .mapNotNull { journey ->
                    runCatching {
                        projectJourney(
                            journey = journey,
                            routeProvenance = TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE,
                            request = request,
                        )
                    }.getOrNull()
                }
                .toList()
            alternativeProjections.forEach { candidate ->
                candidate.recordResolutionEvidence(
                    request = request,
                    onLateLive = {
                        bestAlternativeLateLive = earlierArrival(bestAlternativeLateLive, it)
                    },
                    onDegraded = {
                        bestAlternativeDegraded = earlierArrival(bestAlternativeDegraded, it)
                    },
                    onMissed = {
                        bestAlternativeMissed = earlierArrival(bestAlternativeMissed, it)
                    },
                    onUnknown = {
                        bestAlternativeUnknown = earlierArrival(bestAlternativeUnknown, it)
                    },
                )
            }
            val actionableAlternative = alternativeProjections.bestActionableAlternative(request)
            actionableAlternative?.let {
                bestAlternativeActionable = preferableActionable(
                    current = bestAlternativeActionable,
                    candidate = it,
                    targetArrivalAt = request.targetArrivalAt,
                )
            }

            // 안전 후보가 없으면 resolver가 같은 3회 예산 안에서 더 이른 검색점을 찾도록
            // 가장 유용한 실패 증거 하나만 반환한다. 최종 경고는 예산 소진 뒤 accumulator로
            // 결정하므로 첫 MISSED/UNKNOWN에서 즉시 종료하지 않는다.
            selectedProjections.searchSteeringCandidate(request)
                ?: alternativeProjections
                    .filterNot { it.isActionable(request) }
                    .searchSteeringCandidate(request)
                // 대체 경로가 먼저 안전해도 남은 조회에서 선택 경로가 회복될 수 있다.
                // resolver의 전역 feasible에는 넣지 않고 검색점 계산 증거로만 사용한다.
                ?: actionableAlternative?.copy(
                    eligible = false,
                    searchAdjustmentSeconds = -ALTERNATIVE_RECOVERY_PROBE_SECONDS,
                )
        }
        val hasSelectedDiagnosticEvidence = bestSelectedLateLive != null ||
            bestSelectedDegraded != null ||
            bestSelectedMissed != null ||
            bestSelectedUnknown != null
        return TransitJourneyResolution(
            projected = bestSelectedActionable ?: bestAlternativeActionable,
            bestLateLive = if (hasSelectedDiagnosticEvidence) {
                bestSelectedLateLive
            } else {
                bestAlternativeLateLive
            },
            bestDegraded = if (hasSelectedDiagnosticEvidence) {
                bestSelectedDegraded
            } else {
                bestAlternativeDegraded
            },
            bestMissedTransfer = if (hasSelectedDiagnosticEvidence) {
                bestSelectedMissed
            } else {
                bestAlternativeMissed
            },
            bestTransferUnknown = if (hasSelectedDiagnosticEvidence) {
                bestSelectedUnknown
            } else {
                bestAlternativeUnknown
            },
        )
    }

    private fun projectJourney(
        journey: TransitJourney,
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
                effectiveTransitRoute = journey.toEffectiveTransitRoute(
                    predictedArrivalAt = transfer.predictedArrivalAt,
                    totalMinutes = transfer.travelMinutes,
                    firstBoardingWaitMinutes = overlayResolution.overlay
                        ?.firstBoardingWaitMinutes,
                ),
            ),
            eligible = transfer.eligible &&
                !journey.departureAt.isBefore(request.evaluatedAt) &&
                overlayResolution.overlay != null,
            searchAdjustmentSeconds = when {
                transfer.searchEarlierBySeconds != null ->
                    -maxOf(transfer.searchEarlierBySeconds, 60L)
                transfer.status != TransitTransferStatus.FEASIBLE ->
                    -UNKNOWN_TRANSFER_PROBE_SECONDS
                else -> null
            },
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
            firstBoardingWaitMinutes = overlay?.firstBoardingWaitMinutes
                ?: journey.legs.firstOrNull { it.isRide }?.waitingMinutes,
            effectiveTransitRoute = projected.projection.effectiveTransitRoute,
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
            firstBoardingWaitMinutes = overlay.firstBoardingWaitMinutes,
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
            firstBoardingWaitMinutes = overlay.firstBoardingWaitMinutes,
            effectiveTransitRoute = projected.projection.effectiveTransitRoute,
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
        firstBoardingWaitMinutes = projected.journey.legs.firstOrNull { it.isRide }?.waitingMinutes,
        effectiveTransitRoute = projected.projection.effectiveTransitRoute,
    )

    private fun onTimeUnavailableResult(
        predictedArrivalAt: Instant,
        fetchedAt: Instant,
        source: TrafficSource,
        request: TrafficRequest,
        routeProvenance: TransitRouteProvenance? = null,
        timingBasis: TransitTimingBasis? = null,
        firstBoardingWaitMinutes: Int? = null,
        effectiveTransitRoute: EffectiveTransitRoute? = null,
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
            firstBoardingWaitMinutes = firstBoardingWaitMinutes,
            effectiveTransitRoute = effectiveTransitRoute,
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
            firstBoardingWaitMinutes = projected.projection.overlayResolution.overlay
                ?.firstBoardingWaitMinutes
                ?: projected.journey.legs.firstOrNull { it.isRide }?.waitingMinutes,
            effectiveTransitRoute = projected.projection.effectiveTransitRoute,
        )
    }

    private fun List<ProjectedTransitJourney<TransitCandidateProjection>>.nextActionableSelected(
        request: TrafficRequest,
    ): ProjectedTransitJourney<TransitCandidateProjection>? {
        val actionable = filter { it.isActionable(request) }
        return if (request.targetArrivalAt == null) {
            actionable.minWithOrNull(
                compareBy<ProjectedTransitJourney<TransitCandidateProjection>> {
                    it.journey.departureAt
                }.thenBy { it.predictedArrivalAt }
            )
        } else {
            actionable.maxWithOrNull(
                compareBy<ProjectedTransitJourney<TransitCandidateProjection>> {
                    it.journey.departureAt
                }.thenByDescending { it.predictedArrivalAt }
            )
        }
    }

    private fun List<ProjectedTransitJourney<TransitCandidateProjection>>.bestActionableAlternative(
        request: TrafficRequest,
    ): ProjectedTransitJourney<TransitCandidateProjection>? {
        val actionable = filter { it.isActionable(request) }
        return if (request.targetArrivalAt == null) {
            actionable.minWithOrNull(
                compareBy<ProjectedTransitJourney<TransitCandidateProjection>> {
                    it.predictedArrivalAt
                }.thenBy { it.journey.departureAt }
            )
        } else {
            actionable.maxWithOrNull(
                compareBy<ProjectedTransitJourney<TransitCandidateProjection>> {
                    it.journey.departureAt
                }.thenByDescending { it.predictedArrivalAt }
            )
        }
    }

    private fun <T> preferableActionable(
        current: ProjectedTransitJourney<T>?,
        candidate: ProjectedTransitJourney<T>,
        targetArrivalAt: Instant?,
    ): ProjectedTransitJourney<T> = when {
        current == null -> candidate
        targetArrivalAt == null -> listOf(current, candidate).minWith(
            compareBy<ProjectedTransitJourney<T>> { it.journey.departureAt }
                .thenBy { it.predictedArrivalAt }
        )
        else -> listOf(current, candidate).maxWith(
            compareBy<ProjectedTransitJourney<T>> { it.journey.departureAt }
                .thenByDescending { it.predictedArrivalAt }
        )
    }

    private fun ProjectedTransitJourney<TransitCandidateProjection>.isActionable(
        request: TrafficRequest,
    ): Boolean =
        eligible &&
            !journey.departureAt.isBefore(request.evaluatedAt) &&
            request.targetArrivalAt?.let(predictedArrivalAt::isAfter) != true

    private fun ProjectedTransitJourney<TransitCandidateProjection>.recordResolutionEvidence(
        request: TrafficRequest,
        onLateLive: (ProjectedTransitJourney<TransitCandidateProjection>) -> Unit,
        onDegraded: (ProjectedTransitJourney<TransitCandidateProjection>) -> Unit,
        onMissed: (ProjectedTransitJourney<TransitCandidateProjection>) -> Unit,
        onUnknown: (ProjectedTransitJourney<TransitCandidateProjection>) -> Unit,
    ) {
        recordTransferDiagnostic(onMissed = onMissed, onUnknown = onUnknown)
        if (
            projection.transfer.status != TransitTransferStatus.FEASIBLE ||
            journey.departureAt.isBefore(request.evaluatedAt)
        ) {
            return
        }
        if (projection.overlayResolution.overlay == null) {
            onDegraded(this)
        } else if (request.targetArrivalAt?.let(predictedArrivalAt::isAfter) == true) {
            onLateLive(this)
        }
    }

    /**
     * 다음 검색점을 정하기 위한 실패 후보다. LIVE이지만 늦은 여정을 먼저 사용하고, 그 다음
     * 환승 실패 보정값, 마지막으로 실시간 도착정보가 없는 시간표를 사용한다.
     */
    private fun List<ProjectedTransitJourney<TransitCandidateProjection>>.searchSteeringCandidate(
        request: TrafficRequest,
    ): ProjectedTransitJourney<TransitCandidateProjection>? {
        val current = filter { !it.journey.departureAt.isBefore(request.evaluatedAt) }
        return current
            .filter {
                it.projection.overlayResolution.overlay != null &&
                    it.projection.transfer.status == TransitTransferStatus.FEASIBLE
            }
            .minByOrNull(ProjectedTransitJourney<TransitCandidateProjection>::predictedArrivalAt)
            ?: current
                .filter { it.projection.transfer.status != TransitTransferStatus.FEASIBLE }
                .minWithOrNull(
                    compareBy<ProjectedTransitJourney<TransitCandidateProjection>> {
                        it.searchAdjustmentSeconds ?: 0L
                    }.thenBy { it.predictedArrivalAt }
                )
            ?: current
                .filter { it.projection.overlayResolution.overlay == null }
                .minByOrNull(ProjectedTransitJourney<TransitCandidateProjection>::predictedArrivalAt)
    }

    private fun TransitJourney.toEffectiveTransitRoute(
        predictedArrivalAt: Instant,
        totalMinutes: Int,
        firstBoardingWaitMinutes: Int?,
    ): EffectiveTransitRoute {
        val firstRideIndex = legs.indexOfFirst(TransitJourneyLeg::isRide)
        return EffectiveTransitRoute(
            provider = provider,
            identity = stableRouteIdentity(),
            departureAt = departureAt,
            arrivalAt = predictedArrivalAt,
            totalMinutes = totalMinutes,
            segments = legs.mapIndexed { index, leg ->
                EffectiveTransitRouteSegment(
                    sequence = index,
                    kind = leg.mode.toEffectiveKind(),
                    durationMinutes = leg.durationMinutes,
                    waitingMinutes = if (index == firstRideIndex) {
                        firstBoardingWaitMinutes ?: leg.waitingMinutes
                    } else {
                        leg.waitingMinutes
                    },
                    lineName = leg.line?.name,
                    fromName = leg.from?.name,
                    toName = leg.to?.name,
                    directionName = leg.directionName,
                )
            },
        )
    }

    private fun TransitLegMode.toEffectiveKind(): EffectiveTransitRouteSegmentKind = when (this) {
        TransitLegMode.WALK -> EffectiveTransitRouteSegmentKind.WALK
        TransitLegMode.BUS -> EffectiveTransitRouteSegmentKind.BUS
        TransitLegMode.SUBWAY -> EffectiveTransitRouteSegmentKind.SUBWAY
        TransitLegMode.ETC -> EffectiveTransitRouteSegmentKind.ETC
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

    private companion object {
        const val ODSAY_PROVIDER_ID = "odsay"
        const val MILLIS_PER_MINUTE = 60_000.0
        const val MAX_ALTERNATIVE_CANDIDATES_PER_RESPONSE = 3
        const val UNKNOWN_TRANSFER_PROBE_SECONDS = 5L * 60L
        const val ALTERNATIVE_RECOVERY_PROBE_SECONDS = 5L * 60L
    }

    private fun SelectedTransitRoute.journeyProvider(): TransitJourneyProvider? {
        val selectedProviderId = provider
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null

        return journeyProviders.singleOrNull {
            it.providerId.trim().equals(selectedProviderId, ignoreCase = true)
        }
    }
}

/** 한 공급자 조회에서 발견한 정상 후보와 degraded·환승 실패 후보를 함께 보존한다. */
private data class TransitJourneyResolution(
    val projected: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestLateLive: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestDegraded: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestMissedTransfer: ProjectedTransitJourney<TransitCandidateProjection>?,
    val bestTransferUnknown: ProjectedTransitJourney<TransitCandidateProjection>?,
)

private data class TransitCandidateProjection(
    val overlayResolution: TransitRealtimeOverlayResolution,
    val transfer: TransitTransferEvaluation,
    val routeProvenance: TransitRouteProvenance,
    val effectiveTransitRoute: EffectiveTransitRoute,
)
