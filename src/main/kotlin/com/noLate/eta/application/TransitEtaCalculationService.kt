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
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.application.transit.TransitTransferRealtimeGuard
import com.noLate.eta.application.transit.TransitRideObservationPoint
import com.noLate.eta.application.transit.TransitObservedRideTiming
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRideTiming
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
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
 * 재조회한 여정의 시간 근거와 첫 승차 실시간 도착정보를 확인하고, 선택 경로가 안전하지 않을 때만
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
    private val timetableEnricher: TransitJourneyTimetableEnricher = TransitJourneyTimetableEnricher(emptyList()),
    private val walkingTimeEnricher: com.noLate.eta.application.transit.TransitWalkingTimeEnricher =
        com.noLate.eta.application.transit.TransitWalkingTimeEnricher(),
    private val rideArrivalPredictor: com.noLate.eta.application.transit.TransitRideArrivalPredictor =
        com.noLate.eta.application.transit.TransitRideArrivalPredictor(),
    private val transferRealtimeGuard: TransitTransferRealtimeGuard? = null,
) {

    fun calculate(request: TrafficRequest): TrafficResult {
        require(request.travelMode == ScheduleTravelMode.TRANSIT) {
            "TransitEtaCalculationService는 TRANSIT 요청만 처리합니다."
        }
        request.liveRefreshBlockedReason?.let { return request.fallbackResult(it) }

        val selected = selectedRouteDecoder.decode(request.selectedRouteJson)
            ?: return request.fallbackResult(TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING)

        if (selected.rides.any { it.mode.isIntercity }) {
            return request.fallbackResult(TrafficFailureReasons.TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE)
        }

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
            // 회복 가능한 정상 후보를 모두 탐색한 뒤에는 확인된 환승 실패를 보존한다.
            // 후속 후보의 시간표 누락(UNKNOWN)이 이 증거를 덮으면 자동 알람 취소도 누락된다.
            resolution?.bestMissedTransfer?.let {
                return transferDiagnosticResult(
                    projected = it,
                    request = request,
                    reason = TrafficFailureReasons.TRANSIT_TRANSFER_MISSED,
                )
            }
            resolution?.bestDegraded?.let { candidate ->
                return degradedTimetableResult(
                    projected = candidate,
                    request = request,
                    reason = TrafficFailureReasons.TRANSIT_TRANSFER_REALTIME_DELAY.takeIf {
                        candidate.projection.transferRealtimeDelayed
                    } ?: candidate.projection.overlayResolution.failureReason
                        ?: TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE,
                )
            }
            resolution?.bestTransferUnknown?.let {
                return transferDiagnosticResult(
                    projected = it,
                    request = request,
                    reason = it.projection.transfer.diagnosticFailureReason(),
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
        // 첫 차량 피드가 없는 후보마다 환승역을 조회하면 한 번의 ETA 평가가 공개 API
        // 예산을 급격히 소모한다. 선택 경로의 첫 관측 가능 후보에만 이 보조 진단을 허용한다.
        var transferOnlyProbeUsed = false
        val claimTransferOnlyProbe = {
            if (transferOnlyProbeUsed) false else {
                transferOnlyProbeUsed = true
                true
            }
        }
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
                        claimTransferOnlyProbe = claimTransferOnlyProbe,
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
                        bestSelectedDegraded = preferableDegraded(bestSelectedDegraded, it)
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
                .filter { provider.supportsAlternativeRouteRecovery }
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
                            claimTransferOnlyProbe = { false },
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
                        bestAlternativeDegraded = preferableDegraded(bestAlternativeDegraded, it)
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
        claimTransferOnlyProbe: () -> Boolean,
    ): ProjectedTransitJourney<TransitCandidateProjection> = projectEnrichedJourney(
        timetableEnricher.enrich(walkingTimeEnricher.enrich(journey), request.evaluatedAt, request.maxTravelMinutes) { timetable ->
            firstBoardingRealtimeOverlay.observeTimetableCandidate(journey, timetable, request.evaluatedAt)
        }, routeProvenance, request, claimTransferOnlyProbe,
    )

    private fun projectEnrichedJourney(
        journey: TransitJourney,
        routeProvenance: TransitRouteProvenance,
        request: TrafficRequest,
        claimTransferOnlyProbe: () -> Boolean,
    ): ProjectedTransitJourney<TransitCandidateProjection> {
        var overlayResolution = firstBoardingRealtimeOverlay.resolve(
            journey = journey,
            evaluatedAt = request.evaluatedAt,
            maxTravelMinutes = request.maxTravelMinutes,
        )
        var projectedJourney = journey
        var transfer = transferFeasibilityEvaluator.evaluate(
            journey = journey,
            firstBoardingOverlay = overlayResolution.overlay,
            evaluatedAt = request.evaluatedAt,
        )
        var overlay = overlayResolution.overlay
        val firstBoardingAt = journey.rideLegs.firstOrNull()?.timetable?.boardingAt
        if (overlay != null && firstBoardingAt != null && overlay.boardingAt < firstBoardingAt) {
            // 조기 도착도 실제 관측으로 확인된 경우에만 환승 후보를 재검색한다.
            // 도보·안전 여유는 그대로이며, 더 이른 전체 여정만 채택한다.
            projectedJourney = timetableEnricher.reconnectEarlierTransfers(
                journey, overlay, overlayResolution.firstBoardingObservation,
                request.evaluatedAt, request.maxTravelMinutes,
            )
        } else if (overlay != null && transfer.status != TransitTransferStatus.FEASIBLE &&
            (transfer.failedTransferSequence ?: 0) > 0) {
            // 첫 열차는 이미 실시간으로 대조했다. 동일 노선의 다음 환승 열차만 시간표로
            // 다시 연결하며, 실제 도착 피드를 재조회하거나 도착 마감을 완화하지 않는다.
            projectedJourney = timetableEnricher.reconnectTransfers(journey, overlay, request.maxTravelMinutes)
        }
        if (projectedJourney !== journey) {
            transfer = transferFeasibilityEvaluator.evaluate(projectedJourney, overlay, request.evaluatedAt)
        }
        if (transfer.eligible && overlay != null && request.targetArrivalAt?.let { transfer.predictedArrivalAt <= it } == true) {
            val later = com.noLate.eta.application.transit.ObservedFirstBoardingDepartureProjector.project(
                projectedJourney, overlayResolution, request.evaluatedAt,
                journeyProviders.firstOrNull { it.providerId == projectedJourney.provider }
                    ?.supportsSecondPrecisionDepartureProjection == true,
            )
            if (later != null) {
                val checked = transferFeasibilityEvaluator.evaluate(later.journey, later.resolution.overlay, request.evaluatedAt)
                if (checked.eligible && checked.predictedArrivalAt == transfer.predictedArrivalAt) {
                    projectedJourney = later.journey
                    overlayResolution = later.resolution
                    overlay = later.resolution.overlay
                    transfer = checked
                }
            }
        }
        // 후속 차량의 동일 편·방향·종별·종착과 신선한 환승역 도착 예측이 시간표 지연을
        // 증명하면, 첫 차량만 실시간인 ETA를 정상 알람/도착시각으로 승격하지 않는다.
        val transferWindowOpen = projectedJourney.rideLegs.drop(1).any { leg ->
            leg.mode == TransitLegMode.SUBWAY && leg.timetable?.let { table ->
                listOf(table.boardingAt, table.arrivalAt).any { at ->
                    !at.isBefore(request.evaluatedAt.minusSeconds(120)) &&
                        !at.isAfter(request.evaluatedAt.plusSeconds(15 * 60))
                }
            } == true
        }
        val mayObserveTransfer = overlay != null ||
            (transferWindowOpen && projectedJourney.rideLegs.all { it.timetable != null } &&
                claimTransferOnlyProbe())
        val transferObservations = if (mayObserveTransfer) transferRealtimeGuard
            ?.observeLateTransfers(projectedJourney, request.evaluatedAt).orEmpty() else emptyList()
        val observedTransfer = transferObservations.takeIf { it.isNotEmpty() }
            ?.let { transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                projectedJourney, overlay, request.evaluatedAt, it,
            ) }
            ?.takeIf {
                // 공급자 전체 경로와 구간별 초 단위 시간축이 일치할 때만 알람 ETA로 채택한다.
                projectedJourney.currentRideDisplayTimings(
                    overlay?.boardingAt ?: projectedJourney.rideLegs.first().timetable?.boardingAt,
                    it.predictedArrivalAt, it.observedRideTimings,
                ).size == projectedJourney.rideLegs.size
            }
        // 근거가 있지만 완전한 시간축으로 연결할 수 없다면 기존 시간표 ETA를 채택하지 않는다.
        val transferRealtimeDelayed = transferObservations.isNotEmpty() && observedTransfer == null
        if (observedTransfer != null) transfer = observedTransfer
        val searchEarlierBySeconds = transfer.searchEarlierBySeconds
        return ProjectedTransitJourney(
            journey = projectedJourney,
            predictedArrivalAt = transfer.predictedArrivalAt,
            projection = TransitCandidateProjection(
                overlayResolution = overlayResolution,
                transfer = transfer,
                transferRealtimeDelayed = transferRealtimeDelayed,
                transferObservedAt = transferObservations.maxOfOrNull { it.sourceUpdatedAt },
                routeProvenance = routeProvenance,
                effectiveTransitRoute = projectedJourney.toEffectiveTransitRoute(
                    predictedArrivalAt = transfer.predictedArrivalAt,
                    totalMinutes = transfer.travelMinutes,
                    firstBoardingWaitMinutes = overlayResolution.overlay
                        ?.firstBoardingWaitMinutes,
                    firstBoardingAt = overlay?.boardingAt,
                    observedRideTimings = transfer.observedRideTimings,
                ),
            ),
            eligible = transfer.eligible &&
                !transferRealtimeDelayed &&
                !journey.departureAt.isBefore(request.evaluatedAt) &&
                overlayResolution.overlay != null,
            // readyAt already includes the actual access walk and configured
            // boarding buffer. Reuse that verified span; do not duplicate or
            // reduce safety settings to manufacture a later departure.
            latestObservedVehicleDepartureAt = overlayResolution.firstBoardingObservation
                ?.takeIf { overlay != null && transfer.eligible && !it.trainNumber.isNullOrBlank() &&
                    projectedJourney.rideLegs.firstOrNull()?.timetable != null }
                ?.let { observation -> observation.expectedBoardingAt.minus(
                    Duration.between(observation.departureAt, observation.readyAt),
                ) },
            searchAdjustmentSeconds = when {
                transferRealtimeDelayed -> -UNKNOWN_TRANSFER_PROBE_SECONDS
                searchEarlierBySeconds != null ->
                    -maxOf(searchEarlierBySeconds, 60L)
                transfer.status != TransitTransferStatus.FEASIBLE ->
                    -UNKNOWN_TRANSFER_PROBE_SECONDS
                // 확정 시간표가 마감보다 조금 늦으면 1분 역산만으로는 같은 열차를
                // 다시 선택하기 쉽다. 실시간 미대조 후보에 한해 이전 차량 탐색 폭을
                // 확보한다. 조회 예산·차량 일치·정시 도착 채택 조건은 완화하지 않는다.
                overlay == null && journey.rideLegs.all { it.timetable != null } &&
                    request.targetArrivalAt?.let { target ->
                        Duration.between(target, transfer.predictedArrivalAt).seconds in
                            1..TIMETABLE_PREVIOUS_VEHICLE_PROBE_SECONDS
                    } == true -> -TIMETABLE_PREVIOUS_VEHICLE_PROBE_SECONDS
                // 시간표만 정시라고 아직 관측되지 않은 더 먼 미래 차량을 탐색하면 안 된다.
                // 실시간 채택이 불가능한 후보는 남은 예산으로 더 이른 동일 경로를 확인한다.
                overlay == null && journey.rideLegs.all { it.timetable != null } &&
                    request.targetArrivalAt?.let { !transfer.predictedArrivalAt.isAfter(it) } == true ->
                    -TIMETABLE_PREVIOUS_VEHICLE_PROBE_SECONDS
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
        if (projected.projection.transferRealtimeDelayed) {
            return degradedTimetableResult(
                projected, request, TrafficFailureReasons.TRANSIT_TRANSFER_REALTIME_DELAY,
            )
        }
        if (transfer.status != TransitTransferStatus.FEASIBLE) {
            return transferDiagnosticResult(
                projected = projected,
                request = request,
                reason = transfer.diagnosticFailureReason(),
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
            fetchedAt = listOfNotNull(overlay?.observedAt,
                projected.projection.transferObservedAt).maxOrNull() ?: journey.fetchedAt,
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
            firstBoardingObservation = projected.projection.overlayResolution.firstBoardingObservation,
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

        // 구형 저장 경로는 전체 구간의 실제 시간표를 확인할 수 없다.
        // 첫 차량 대기만 보정한 값으로 최종 도착이나 지각 여부를 확정하지 않는다.
        return request.fallbackResult(
            TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
        ).copy(
            // 첫 차량은 실시간이지만 나머지 이동시간은 추정값임을 기록한다.
            transitTimingBasis =
                TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,

            // 확인된 첫 차량 대기시간은 진단용으로 보존한다.
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
            firstBoardingObservation = overlayResolution.firstBoardingObservation,
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
        // 확인된 환승편 지연이 있으면 예전 시간표 도착시각을 사용자 예측으로 내리지 않는다.
        predictedArrivalAt = projected.predictedArrivalAt.takeUnless {
            projected.projection.transferRealtimeDelayed
        },
        transitRouteProvenance = projected.projection.routeProvenance,
        transitTimingBasis = projected.projection.transfer.timingBasis.toTrafficTimingBasis(),
        firstBoardingWaitMinutes = projected.journey.legs.firstOrNull { it.isRide }?.waitingMinutes,
        // 후속 열차의 신선한 지연 관측과 충돌하는 구간 시각을 상세 경로로 노출하지 않는다.
        effectiveTransitRoute = projected.projection.effectiveTransitRoute.takeUnless {
            projected.projection.transferRealtimeDelayed
        },
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
        firstBoardingObservation: TransitFirstBoardingObservation? = null,
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
            firstBoardingObservation = firstBoardingObservation,
        )
    }

    private fun transferDiagnosticResult(
        projected: ProjectedTransitJourney<TransitCandidateProjection>,
        request: TrafficRequest,
        reason: String,
    ): TrafficResult {
        return request.fallbackResult(reason).copy(
            // 여정 재조회 성공과 ETA 채택 가능 여부는 별개다. 추정 시각이라 채택하지 못해도
            // 실제 공급자 조회의 취득 시각은 이력에 남겨 저장값만 쓴 fallback과 구분한다.
            // 원천 차량 관측시각이나 평가 시각을 새로 만들어 대신 기록하지 않는다.
            fetchedAt = projected.journey.fetchedAt,
            // MISSED는 승차 불가가 확인된 경로, UNKNOWN은 시각 근거 또는 여유가 부족한 경로다.
            // 추정 도착시각은 정시 도착·알람 재예약에 사용하지 않는다.
            predictedArrivalAt = null,
            transitRouteProvenance = projected.projection.routeProvenance,
            transitTimingBasis = projected.projection.transfer.timingBasis.toTrafficTimingBasis(),
            firstBoardingWaitMinutes = projected.projection.overlayResolution.overlay
                ?.firstBoardingWaitMinutes
                ?: projected.journey.legs.firstOrNull { it.isRide }?.waitingMinutes,
            effectiveTransitRoute = projected.projection.effectiveTransitRoute,
            firstBoardingObservation = projected.projection.overlayResolution.firstBoardingObservation,
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
        if (projection.transferRealtimeDelayed) {
            onDegraded(this)
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
                    !it.projection.transferRealtimeDelayed &&
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
                .filter { it.projection.overlayResolution.overlay == null || it.projection.transferRealtimeDelayed }
                .minByOrNull(ProjectedTransitJourney<TransitCandidateProjection>::predictedArrivalAt)
    }

    private fun TransitJourney.toEffectiveTransitRoute(
        predictedArrivalAt: Instant,
        totalMinutes: Int,
        firstBoardingWaitMinutes: Int?,
        firstBoardingAt: Instant?,
        observedRideTimings: Map<Int, TransitObservedRideTiming> = emptyMap(),
    ): EffectiveTransitRoute {
        val firstRideIndex = legs.indexOfFirst(TransitJourneyLeg::isRide)
        val currentRideTimings = currentRideDisplayTimings(
            firstBoardingAt, predictedArrivalAt, observedRideTimings,
        )
        return EffectiveTransitRoute(
            provider = provider,
            identity = stableRouteIdentity(),
            departureAt = departureAt,
            arrivalAt = predictedArrivalAt,
            totalMinutes = totalMinutes,
            segments = legs.mapIndexed { index, leg ->
                val currentTiming = currentRideTimings[index]
                EffectiveTransitRouteSegment(
                    sequence = index,
                    kind = leg.mode.toEffectiveKind(),
                    // 같은 환승 열차를 유지해도 첫 하차가 달라지면 이후 대기가 바뀐다.
                    // 원본 시간표·도보·총 ETA는 유지하고 표시 구간만 현재 시간축으로 계산한다.
                    durationMinutes = currentTiming?.durationMinutes ?: if (index == firstRideIndex && firstBoardingAt != null && leg.timetable != null) {
                        val seconds = leg.movementDurationSeconds +
                            Duration.between(leg.firstBoardingObservation?.expectedBoardingAt
                                ?: leg.timetable.boardingAt, firstBoardingAt).seconds
                        ((seconds.coerceAtLeast(0) + 59) / 60).toInt()
                    } else leg.durationMinutes,
                    waitingMinutes = currentTiming?.waitingMinutes ?: if (index == firstRideIndex) {
                        firstBoardingWaitMinutes ?: leg.waitingMinutes
                    } else {
                        leg.waitingMinutes
                    },
                    lineName = leg.line?.name,
                    fromName = leg.from?.name,
                    toName = leg.to?.name,
                    directionName = leg.directionName,
                    walkingScope = walkingScopeAt(index),
                    durationSeconds = leg.durationSeconds.takeIf { leg.mode == TransitLegMode.WALK },
                    walkingGeometry = leg.walkingGeometry,
                    walkingTiming = leg.walkingTiming,
                    transferTiming = currentTiming?.transferTiming,
                    rideTiming = leg.timetable?.takeIf { leg.mode == TransitLegMode.SUBWAY }?.let { timetable ->
                        val observed = observedRideTimings[index]
                        val liveBoardingAt = firstBoardingAt.takeIf { index == firstRideIndex }
                        val boardingAt = observed?.boardingAt ?: liveBoardingAt ?: timetable.boardingAt
                        // 항상 원본 시간표에서 계산해 embedded observation의 지연을 이중 적용하지 않는다.
                        // 재연결한 다음 환승 열차에는 첫 열차 지연을 다시 전파하지 않는다.
                        EffectiveTransitRideTiming(
                            timetable = timetable,
                            predictedBoardingAt = boardingAt,
                            predictedArrivalAt = observed?.arrivalAt
                                ?: rideArrivalPredictor.predict(timetable, boardingAt).arrivalAt,
                            basis = if (observed?.observationPoint == TransitRideObservationPoint.ALIGHTING) {
                                EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME
                            } else if (observed?.observationPoint == TransitRideObservationPoint.BOARDING) {
                                EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE
                            } else if (liveBoardingAt != null) {
                                EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE
                            } else EffectiveTransitRideTimingBasis.TIMETABLE,
                            observationSourceUpdatedAt = observed?.observationSourceUpdatedAt,
                        )
                    },
                )
            },
        )
    }

    private data class RideDisplayTiming(val durationMinutes: Int, val waitingMinutes: Int,
        val transferTiming: com.noLate.eta.domain.TransitTransferTimingEvidence? = null)

    /** 완전하고 승차 가능한 시간축에서만 표시 대기를 다시 계산한다. 실패 구간을 0초로 만들지 않는다. */
    private fun TransitJourney.currentRideDisplayTimings(
        firstBoardingAt: Instant?,
        predictedArrivalAt: Instant,
        observedRideTimings: Map<Int, TransitObservedRideTiming> = emptyMap(),
    ): Map<Int, RideDisplayTiming> {
        if (firstBoardingAt == null || rideLegs.any { it.timetable == null }) return emptyMap()
        val firstRideIndex = legs.indexOfFirst(TransitJourneyLeg::isRide)
        val timings = mutableMapOf<Int, RideDisplayTiming>()
        var cursor = departureAt
        var previousRideArrivalAt: Instant? = null
        for ((index, leg) in legs.withIndex()) {
            if (!leg.isRide) {
                if (leg.mode != TransitLegMode.WALK) return emptyMap()
                cursor = cursor.plusSeconds(leg.movementDurationSeconds)
                continue
            }
            val timetable = requireNotNull(leg.timetable)
            val boardingAt = observedRideTimings[index]?.boardingAt
                ?: if (index == firstRideIndex) firstBoardingAt else timetable.boardingAt
            val arrivalAt = observedRideTimings[index]?.arrivalAt
                ?: rideArrivalPredictor.predict(timetable, boardingAt).arrivalAt
            if (boardingAt < cursor || arrivalAt <= boardingAt) return emptyMap()
            fun minutesUntil(at: Instant) = ((Duration.between(cursor, at).seconds + 59) / 60).toInt()
            val transferTiming = previousRideArrivalAt?.let {
                transferFeasibilityEvaluator.describeTransfer(it, Duration.between(it, cursor).seconds, boardingAt)
            }
            timings[index] = RideDisplayTiming(minutesUntil(arrivalAt), minutesUntil(boardingAt), transferTiming)
            cursor = arrivalAt
            previousRideArrivalAt = arrivalAt
        }
        // 다른 최종 도착을 만드는 진단·미완전 구간을 정상 표시 시간축으로 승격하지 않는다.
        return if (cursor == predictedArrivalAt) timings else emptyMap()
    }

    private fun TransitLegMode.toEffectiveKind(): EffectiveTransitRouteSegmentKind = when (this) {
        TransitLegMode.WALK -> EffectiveTransitRouteSegmentKind.WALK
        TransitLegMode.BUS -> EffectiveTransitRouteSegmentKind.BUS
        TransitLegMode.SUBWAY -> EffectiveTransitRouteSegmentKind.SUBWAY
        TransitLegMode.TRAIN -> EffectiveTransitRouteSegmentKind.TRAIN
        TransitLegMode.EXPRESS_BUS -> EffectiveTransitRouteSegmentKind.EXPRESS_BUS
        TransitLegMode.INTERCITY_BUS -> EffectiveTransitRouteSegmentKind.INTERCITY_BUS
        TransitLegMode.COACH -> EffectiveTransitRouteSegmentKind.COACH
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

    private fun preferableDegraded(
        current: ProjectedTransitJourney<TransitCandidateProjection>?,
        candidate: ProjectedTransitJourney<TransitCandidateProjection>,
    ): ProjectedTransitJourney<TransitCandidateProjection> {
        if (current == null) return candidate
        // 조회 예산을 다 쓴 뒤 다른 미관측 시간표 후보가 더 이르다는 이유만으로
        // 이미 확인한 동일 경로 환승 지연을 지우면 안전하지 않다.
        fun ProjectedTransitJourney<TransitCandidateProjection>.evidenceRank() = when {
            projection.transferRealtimeDelayed -> 2
            projection.transferObservedAt != null -> 1
            else -> 0
        }
        return when {
            candidate.evidenceRank() > current.evidenceRank() -> candidate
            candidate.evidenceRank() < current.evidenceRank() -> current
            else -> earlierArrival(current, candidate)
        }
    }

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

    private fun TransitTransferEvaluation.diagnosticFailureReason(): String = when {
        // 실제 승차 실패가 확인된 경우는 추정값 포함 여부보다 우선한다.
        status == TransitTransferStatus.MISSED -> TrafficFailureReasons.TRANSIT_TRANSFER_MISSED
        timingBasis == TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE ||
            timingBasis == TransitJourneyTimingBasis.ROUTE_ESTIMATE_ONLY ->
            TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY
        else -> TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN
    }

    private fun TransitJourneyTimingBasis.toTrafficTimingBasis(): TransitTimingBasis = when (this) {
        TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE ->
            TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE
        TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED ->
            TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED
        TransitJourneyTimingBasis.FIRST_BOARDING_TIMETABLE_TRANSFER_OBSERVED ->
            TransitTimingBasis.FIRST_BOARDING_TIMETABLE_TRANSFER_OBSERVED
        TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN ->
            TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN
        TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE ->
            TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE
        TransitJourneyTimingBasis.ROUTE_ESTIMATE_ONLY -> TransitTimingBasis.ROUTE_ESTIMATE_ONLY
        TransitJourneyTimingBasis.TIMETABLE_ONLY -> TransitTimingBasis.TIMETABLE_ONLY
        TransitJourneyTimingBasis.TIMETABLE_TRANSFER_UNKNOWN ->
            TransitTimingBasis.TIMETABLE_TRANSFER_UNKNOWN
    }

    private fun Instant.coerceAtLeast(minimum: Instant): Instant =
        if (isBefore(minimum)) minimum else this

    private companion object {
        const val TIMETABLE_PREVIOUS_VEHICLE_PROBE_SECONDS = 300L
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
    val transferRealtimeDelayed: Boolean,
    val transferObservedAt: Instant?,
    val routeProvenance: TransitRouteProvenance,
    val effectiveTransitRoute: EffectiveTransitRoute,
)
