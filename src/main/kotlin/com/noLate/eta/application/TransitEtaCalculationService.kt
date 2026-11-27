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
import com.noLate.eta.application.transit.TransitRideRealtimeObservation
import com.noLate.eta.application.transit.TransitObservedRideTiming
import com.noLate.eta.application.transit.SeoulBusEta
import com.noLate.eta.application.transit.SeoulSingleBusProjection
import com.noLate.eta.application.transit.BusTransferTimetableReads
import com.noLate.eta.application.transit.TransitRealtimeOverlay
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveBusRidePrediction
import com.noLate.schedule.application.PlannedSeoulBusJourney
import com.noLate.schedule.application.PlannedSeoulBusRide
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
    private val seoulBusEta: SeoulBusEta? = null,
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
                ).copy(futureTimetablePlanEvidence = resolution.requestedTimetablePlan)
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

        val reason = when {
            selected.provider.equals(ODSAY_PROVIDER_ID, ignoreCase = true) ->
                TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE
            selected.provider.equals(TMAP_PROVIDER_ID, ignoreCase = true) ->
                TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED
            else -> null
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
        var requestedTimetablePlan: com.noLate.schedule.application.TransitTimetablePlanEvidence? = null
        var bestAlternativeUnknown: ProjectedTransitJourney<TransitCandidateProjection>? = null
        // 첫 차량 피드가 없는 후보마다 환승역을 조회하면 한 번의 ETA 평가가 공개 API
        // 예산을 급격히 소모한다. 선택 경로의 첫 관측 가능 후보에만 이 보조 진단을 허용한다.
        var transferOnlyProbeUsed = false
        val busEvaluation = seoulBusEta?.evaluation()
        val busTransferReads = BusTransferTimetableReads()
        val claimTransferOnlyProbe = {
            if (transferOnlyProbeUsed) false else {
                transferOnlyProbeUsed = true
                true
            }
        }
        var firstAlightingLookup: Pair<TransitLegTimetable, TransitRideRealtimeObservation?>? = null
        val checkFirstAlighting: (TransitJourney, Instant, TransitLegTimetable) -> TransitRideRealtimeObservation? = { journey, boarding, table ->
            // Reuse one read across safe-departure candidates for this exact first service.
            val key = table.copy(fetchedAt = Instant.EPOCH)
            val cached = firstAlightingLookup
            if (cached == null) {
                transferRealtimeGuard?.observeFirstRideAlighting(journey, boarding, evaluatedAt)
                    .also { firstAlightingLookup = key to it }
            } else cached.second
                ?.takeIf { cached.first == key && it.expectedAt.isAfter(boarding) }
                ?.copy(legIndex = journey.legs.indexOfFirst(TransitJourneyLeg::isRide))
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
                        matchedSelectedRoute = selected,
                        busEvaluation = busEvaluation,
                        busTransferReads = busTransferReads,
                        checkFirstAlighting = checkFirstAlighting,
                    )
                }.getOrNull()
            }
            selectedProjections.forEach { candidate ->
                val plan = candidate.requestedTimetableEvidence(request)
                if (plan != null && (requestedTimetablePlan == null || plan.route.arrivalAt < requireNotNull(requestedTimetablePlan).route.arrivalAt)) {
                    requestedTimetablePlan = plan
                }
                candidate.recordResolutionEvidence(
                    request = request,
                    onLateLive = {
                        bestSelectedLateLive = earlierArrival(bestSelectedLateLive, it)
                    },
                    onDegraded = {
                        bestSelectedDegraded = preferableDegraded(bestSelectedDegraded, it, request.targetArrivalAt)
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
                        bestAlternativeDegraded = preferableDegraded(bestAlternativeDegraded, it, request.targetArrivalAt)
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
            requestedTimetablePlan = requestedTimetablePlan,
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
        matchedSelectedRoute: SelectedTransitRoute? = null,
        busEvaluation: SeoulBusEta.Evaluation? = null,
        busTransferReads: BusTransferTimetableReads? = null,
        checkFirstAlighting: ((TransitJourney, Instant, TransitLegTimetable) -> TransitRideRealtimeObservation?)? = null,
    ): ProjectedTransitJourney<TransitCandidateProjection> {
        val withWalking = walkingTimeEnricher.enrich(journey)
        busEvaluation?.project(withWalking, request.evaluatedAt, request.maxTravelMinutes)?.let {
            return projectBus(withWalking, listOf(it), it.receivedAt, it.travelMinutes,
                routeProvenance, matchedSelectedRoute)
        }
        val busTransfer = busEvaluation?.projectTransfer(withWalking, request.evaluatedAt, request.maxTravelMinutes,
            transferFeasibilityEvaluator.requiredTransferMarginSeconds)
        busTransfer?.connection?.let {
            return projectBus(withWalking, listOf(it.first, it.second), it.receivedAt, it.travelMinutes,
                routeProvenance, matchedSelectedRoute, it.requiredMarginSeconds)
        }
        val mixedFirstBus = busEvaluation?.firstBusBeforeSubway(withWalking, request.evaluatedAt, request.maxTravelMinutes)
        if (busEvaluation != null && mixedFirstBus != null) {
            projectBusAndSubway(withWalking, mixedFirstBus, busEvaluation, busTransferReads, request, routeProvenance, matchedSelectedRoute)
                ?.let { return it }
        }
        val observedFirstBus = (busTransfer?.firstAvailable ?: mixedFirstBus)?.let {
            // Preserve the verified boarding independently. An unverified connection
            // cannot produce a new arrival estimate or reschedule the existing alert.
            TransitRealtimeOverlayResolution(
                failureReason = TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN,
                firstBoardingObservation = it.observation,
                observedWaitWithoutOverlay = it.waitingMinutes,
            )
        }
        val timed = timetableEnricher.enrich(withWalking, request.evaluatedAt, request.maxTravelMinutes) { timetable ->
            firstBoardingRealtimeOverlay.observeTimetableCandidate(withWalking, timetable, request.evaluatedAt)
        }
        return projectEnrichedJourney(
            timed, routeProvenance, request, claimTransferOnlyProbe, matchedSelectedRoute, observedFirstBus,
            checkFirstAlighting,
        )
    }

    private fun projectBusAndSubway(
        journey: TransitJourney,
        first: SeoulSingleBusProjection,
        busEvaluation: SeoulBusEta.Evaluation,
        busTransferReads: BusTransferTimetableReads?,
        request: TrafficRequest,
        provenance: TransitRouteProvenance,
        selected: SelectedTransitRoute?,
    ): ProjectedTransitJourney<TransitCandidateProjection>? {
        val suffix = journey.legs.drop(first.rideIndex + 1)
        val transferWalk = suffix.takeWhile { it.mode == TransitLegMode.WALK }.sumOf { it.movementDurationSeconds }
        if (transferWalk <= 0) return null
        val requiredMargin = timetableEnricher.busTransferMarginSeconds(transferFeasibilityEvaluator.requiredTransferMarginSeconds)
        val connected = timetableEnricher.connectAfterBus(journey.copy(departureAt = first.alightingAt, legs = suffix),
            request.evaluatedAt, request.maxTravelMinutes, requiredMargin, reads = busTransferReads) ?: return null
        val receivedAt = maxOf(first.receivedAt, connected.rideLegs.maxOf { requireNotNull(it.timetable).fetchedAt })
        if (!busEvaluation.canStillBoard(first, receivedAt)) return null
        val totalMinutes = ((Duration.between(journey.departureAt, connected.arrivalAt).seconds + 59) / 60).toInt()
        if (totalMinutes !in 1..request.maxTravelMinutes) return null
        val combined = journey.copy(arrivalAt = connected.arrivalAt, totalMinutes = totalMinutes,
            legs = journey.legs.take(first.rideIndex + 1) + connected.legs)
        return projectBus(combined, listOf(first), receivedAt, totalMinutes, provenance, selected, requiredMargin)
    }

    private fun projectBus(
        journey: TransitJourney,
        rides: List<SeoulSingleBusProjection>,
        receivedAt: Instant,
        travelMinutes: Int,
        provenance: TransitRouteProvenance,
        selected: SelectedTransitRoute?,
        busTransferMarginSeconds: Long = 0,
    ): ProjectedTransitJourney<TransitCandidateProjection> {
        val bus = rides.first()
        val last = rides.last()
        val observation = bus.observation
        val boarding = observation.expectedBoardingAt
        val mixed = journey.rideLegs.any { it.mode == TransitLegMode.SUBWAY }
        val arrivalAt = if (mixed) journey.arrivalAt else last.destinationAt
        val route = journey.toEffectiveTransitRoute(arrivalAt, travelMinutes,
            bus.waitingMinutes, null, matchedSelectedRoute = selected,
            mixedFirstBus = bus.takeIf { mixed }, mixedTransferMarginSeconds = busTransferMarginSeconds)
        val timingBasis = when {
            mixed -> TransitJourneyTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE
            rides.size == 1 -> TransitJourneyTimingBasis.SAME_BUS_BOARDING_AND_ALIGHTING_REALTIME
            else -> TransitJourneyTimingBasis.BUS_TRANSFER_BOARDING_AND_ALIGHTING_REALTIME
        }
        val segments = route.segments.map { segment ->
            val ride = rides.firstOrNull { it.rideIndex == segment.sequence } ?: return@map segment
            val rideObservation = ride.observation
            segment.copy(
                durationMinutes = ride.rideWithWaitMinutes,
                waitingMinutes = ride.waitingMinutes,
                busRidePrediction = EffectiveBusRidePrediction(rideObservation.expectedBoardingAt, ride.alightingAt,
                    rideObservation.sourceUpdatedAt),
                transferTiming = if (ride === bus) null else transferFeasibilityEvaluator.describeTransfer(
                    bus.alightingAt, ride.accessSeconds, rideObservation.expectedBoardingAt)
                    .copy(confidenceMarginSeconds = busTransferMarginSeconds),
            )
        }
        return ProjectedTransitJourney(
            journey = journey,
            predictedArrivalAt = arrivalAt,
            projection = TransitCandidateProjection(
                overlayResolution = TransitRealtimeOverlayResolution(
                    overlay = TransitRealtimeOverlay(travelMinutes, arrivalAt,
                        receivedAt, boarding, bus.waitingMinutes),
                    firstBoardingObservation = observation,
                ),
                transfer = TransitTransferEvaluation(TransitTransferStatus.FEASIBLE, arrivalAt,
                    travelMinutes, timingBasis),
                transferRealtimeDelayed = false,
                transferObservedAt = null,
                routeProvenance = provenance,
                effectiveTransitRoute = route.copy(segments = segments),
                seoulBusJourney = if (rides.all { it.service != null }) PlannedSeoulBusJourney(
                    route.identity, route.departureAt,
                    rides.map { PlannedSeoulBusRide(it.rideIndex, requireNotNull(it.service)) },
                ) else null,
            ),
            latestObservedVehicleDepartureAt = boarding.minus(
                Duration.between(observation.departureAt, observation.readyAt),
            ),
        )
    }

    private fun projectEnrichedJourney(
        journey: TransitJourney,
        routeProvenance: TransitRouteProvenance,
        request: TrafficRequest,
        claimTransferOnlyProbe: () -> Boolean,
        matchedSelectedRoute: SelectedTransitRoute?,
        observedFirstBus: TransitRealtimeOverlayResolution?,
        checkFirstAlighting: ((TransitJourney, Instant, TransitLegTimetable) -> TransitRideRealtimeObservation?)?,
    ): ProjectedTransitJourney<TransitCandidateProjection> {
        var overlayResolution = observedFirstBus ?: firstBoardingRealtimeOverlay.resolve(
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
        val firstTimetable = journey.rideLegs.firstOrNull()?.timetable
        val earlyFirstTimetable = firstTimetable?.takeIf { table ->
            overlay?.let { table.shiftFromObservedBoardingArrival(it.boardingAt).isNegative } == true &&
                journey.rideLegs.size > 1
        }
        val observedFirstAlighting = firstTimetable?.takeIf {
            overlay != null && overlayResolution.firstBoardingObservation != null &&
                it.arrivalAt <= request.evaluatedAt.plusSeconds(15 * 60L)
        }?.let { table -> checkFirstAlighting?.invoke(journey, requireNotNull(overlay).boardingAt, table) }
        if (observedFirstAlighting != null) {
            // 하차역 관측으로 지연이 커지거나 줄어든 결과를 먼저 환승 판정에 적용한다.
            // 승차역에서 추정한 하차 시각 때문에 탈 수 있는 기존 편을 버리지 않도록 한다.
            transfer = transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                journey, overlay, request.evaluatedAt, listOf(observedFirstAlighting),
            ) ?: transfer
        }
        if (earlyFirstTimetable != null && observedFirstAlighting != null &&
            observedFirstAlighting.expectedAt.isBefore(earlyFirstTimetable.arrivalAt)) {
            projectedJourney = timetableEnricher.reconnectEarlierTransfers(
                journey, requireNotNull(overlay), overlayResolution.firstBoardingObservation,
                request.evaluatedAt, request.maxTravelMinutes, observedFirstAlighting.expectedAt,
            )
        } else if (overlay != null && observedFirstAlighting != null &&
            transferFeasibilityEvaluator.firstTransferShortfall(journey, transfer, observedFirstAlighting.expectedAt) != null) {
            // 하차역에서 확인한 실제 지연으로 환승 여유가 사라지면 같은 경로의 다음 편을 찾는다.
            // 도보·승차 여유는 유지하고, 승차역 관측으로 계산한 지연을 다시 더하지 않는다.
            projectedJourney = timetableEnricher.reconnectTransfers(
                journey, overlay, request.maxTravelMinutes, observedFirstAlighting.expectedAt,
            )
        } else if (earlyFirstTimetable == null && overlay != null && transfer.status != TransitTransferStatus.FEASIBLE &&
            (transfer.failedTransferSequence ?: 0) > 0) {
            // 첫 열차는 이미 실시간으로 대조했다. 동일 노선의 다음 환승 열차만 시간표로
            // 다시 연결하며, 실제 도착 피드를 재조회하거나 도착 마감을 완화하지 않는다.
            projectedJourney = timetableEnricher.reconnectTransfers(journey, overlay, request.maxTravelMinutes)
        }
        if (projectedJourney !== journey) {
            // 환승편이 바뀌어도 첫 열차의 하차역 예보로 환승 가능 시각을 판단한다.
            transfer = transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                projectedJourney, overlay, request.evaluatedAt, listOfNotNull(observedFirstAlighting),
            ) ?: transfer.copy(status = TransitTransferStatus.UNKNOWN)
        }
        // 이미 조회 범위에 들어온 후속 차량만 동일 편의 신선한 도착 예측으로 검증한다.
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
        val observedJourney = projectedJourney
        val transferObservations = if (mayObserveTransfer) transferRealtimeGuard
            ?.observeTransferChanges(projectedJourney, request.evaluatedAt).orEmpty() else emptyList()
        val (earlyBoardingArrivals, lateObservations) = transferObservations.partition { it.earlyBoardingArrival }
        val useFirstTimetableAlighting = earlyFirstTimetable != null && observedFirstAlighting == null
        var usedObservations = lateObservations + listOfNotNull(observedFirstAlighting)
        var observedTransfer = lateObservations.takeIf { it.isNotEmpty() }
            ?.let { transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                projectedJourney, overlay, request.evaluatedAt, usedObservations, useFirstTimetableAlighting,
            ) }
        val recovery = observedTransfer?.takeUnless { it.eligible }?.let {
            reconnectObservedTransfer(projectedJourney, overlay, request, usedObservations, it, useFirstTimetableAlighting)
        }
        if (recovery != null) {
            projectedJourney = recovery.journey
            usedObservations = recovery.observations
            observedTransfer = recovery.evaluation
        }
        observedTransfer = observedTransfer?.takeIf {
                // 공급자 전체 경로와 구간별 초 단위 시간축이 일치할 때만 알람 ETA로 채택한다.
                projectedJourney.currentRideDisplayTimings(
                    overlay?.boardingAt,
                    it.predictedArrivalAt, it.observedRideTimings,
                ).size == projectedJourney.rideLegs.size
            }
        // 근거가 있지만 완전한 시간축으로 연결할 수 없다면 기존 시간표 ETA를 채택하지 않는다.
        val transferRealtimeDelayed = lateObservations.isNotEmpty() && observedTransfer == null
        if (observedTransfer != null) transfer = observedTransfer
        val firstAlightingForTransfer = observedFirstAlighting?.expectedAt ?: earlyFirstTimetable?.arrivalAt
        if (transfer.eligible && !transferRealtimeDelayed && firstAlightingForTransfer != null) {
            // An earlier boarding estimate cannot supply transfer slack by itself.
            val shortfall = transferFeasibilityEvaluator.firstTransferShortfall(
                projectedJourney, transfer, firstAlightingForTransfer,
            )
            if (shortfall != null) {
                transfer = transfer.copy(
                    status = TransitTransferStatus.UNKNOWN,
                    failedTransferSequence = 1,
                    searchEarlierBySeconds = shortfall,
                )
            } else {
                val observations = usedObservations
                val evaluated = transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                    projectedJourney, overlay, request.evaluatedAt, observations, useFirstTimetableAlighting,
                )?.takeIf {
                    projectedJourney.currentRideDisplayTimings(
                        overlay?.boardingAt, it.predictedArrivalAt, it.observedRideTimings,
                    ).size == projectedJourney.rideLegs.size
                }
                if (evaluated == null) {
                    transfer = transfer.copy(status = TransitTransferStatus.UNKNOWN, failedTransferSequence = 1)
                } else {
                    transfer = evaluated
                    usedObservations = observations
                }
            }
        }
        val relevantEarlyArrivals = earlyBoardingArrivals.filter {
            projectedJourney.keepsObservedService(observedJourney, it)
        }
        for (early in relevantEarlyArrivals) {
            if (!transfer.eligible || transferRealtimeDelayed) break
            val observations = usedObservations + early
            val checked = transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                projectedJourney, overlay, request.evaluatedAt, observations, useFirstTimetableAlighting,
            ) ?: continue
            if (!checked.eligible) {
                transfer = transfer.copy(status = TransitTransferStatus.UNKNOWN,
                    failedTransferSequence = checked.failedTransferSequence,
                    searchEarlierBySeconds = checked.searchEarlierBySeconds)
                break
            }
            // 환승역 도착 전에 도보와 승차 여유가 확보될 때만 더 이른 대기 종료를 안내한다.
            // 정차 중에 준비되는 경우에는 시간표를 유지한다. 하차와 최종 도착은 이 근거로 당기지 않는다.
            val timings = projectedJourney.currentRideDisplayTimings(overlay?.boardingAt, checked.predictedArrivalAt,
                checked.observedRideTimings)
            val ready = timings[early.legIndex]?.transferTiming?.confidenceReadyAt ?: continue
            if (ready <= early.expectedAt && checked.predictedArrivalAt == transfer.predictedArrivalAt) {
                transfer = checked
                usedObservations = observations
            }
        }
        if (transfer.eligible && !transferRealtimeDelayed && overlay != null &&
            request.targetArrivalAt?.let { transfer.predictedArrivalAt <= it } == true) {
            val later = com.noLate.eta.application.transit.ObservedFirstBoardingDepartureProjector.project(
                projectedJourney, overlayResolution, request.evaluatedAt,
                journeyProviders.firstOrNull { it.providerId == projectedJourney.provider }
                    ?.supportsSecondPrecisionDepartureProjection == true,
            )
            if (later != null) {
                // 출발 대기를 줄여도 같은 열차들의 승하차 예보와 도착시각이 유지되어야 한다.
                val checked = transferFeasibilityEvaluator.evaluateWithObservedTransfers(
                    later.journey, later.resolution.overlay, request.evaluatedAt, usedObservations, useFirstTimetableAlighting,
                )
                if (checked != null && checked.eligible && checked.predictedArrivalAt == transfer.predictedArrivalAt &&
                    later.journey.currentRideDisplayTimings(later.resolution.overlay?.boardingAt,
                        checked.predictedArrivalAt, checked.observedRideTimings).size == later.journey.rideLegs.size) {
                    projectedJourney = later.journey
                    overlayResolution = later.resolution
                    overlay = later.resolution.overlay
                    transfer = checked
                }
            }
        }
        val searchEarlierBySeconds = transfer.searchEarlierBySeconds
        return ProjectedTransitJourney(
            journey = projectedJourney,
            predictedArrivalAt = transfer.predictedArrivalAt,
            projection = TransitCandidateProjection(
                overlayResolution = overlayResolution,
                transfer = transfer,
                transferRealtimeDelayed = transferRealtimeDelayed,
                transferObservedAt = usedObservations.maxOfOrNull { it.sourceUpdatedAt },
                routeProvenance = routeProvenance,
                effectiveTransitRoute = projectedJourney.toEffectiveTransitRoute(
                    predictedArrivalAt = transfer.predictedArrivalAt,
                    totalMinutes = transfer.travelMinutes,
                    firstBoardingWaitMinutes = overlayResolution.overlay?.firstBoardingWaitMinutes,
                    firstBoardingAt = overlay?.boardingAt,
                    firstBoardingSourceAt = overlayResolution.firstBoardingObservation?.sourceUpdatedAt,
                    observedRideTimings = transfer.observedRideTimings,
                    matchedSelectedRoute = matchedSelectedRoute,
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

    private data class ObservedTransferRecovery(
        val journey: TransitJourney,
        val observations: List<TransitRideRealtimeObservation>,
        val evaluation: TransitTransferEvaluation,
    )

    private fun reconnectObservedTransfer(
        journey: TransitJourney,
        overlay: TransitRealtimeOverlay?,
        request: TrafficRequest,
        observations: List<TransitRideRealtimeObservation>,
        evaluation: TransitTransferEvaluation,
        useFirstTimetableAlighting: Boolean,
    ): ObservedTransferRecovery? {
        val failed = evaluation.failedTransferSequence?.takeIf { it > 1 } ?: return null
        val rides = journey.legs.indices.filter { journey.legs[it].isRide }
        val previousIndex = rides.getOrNull(failed - 1) ?: return null
        // 관측한 승차 구간의 하차 시각을 이후 환승 재연결의 기준으로 사용한다.
        if (observations.none { it.legIndex == previousIndex }) return null
        val arrival = evaluation.observedRideTimings[previousIndex]?.arrivalAt ?: return null
        val reconnected = timetableEnricher.reconnectAfterObservedRide(
            journey, previousIndex, arrival, request.maxTravelMinutes,
        ).takeUnless { it === journey } ?: return null
        val retained = observations.filter { reconnected.keepsObservedService(journey, it) }
        val checked = transferFeasibilityEvaluator.evaluateWithObservedTransfers(
            reconnected, overlay, request.evaluatedAt, retained, useFirstTimetableAlighting,
        )?.takeIf { it.eligible } ?: return null
        if (reconnected.currentRideDisplayTimings(
                overlay?.boardingAt,
                checked.predictedArrivalAt, checked.observedRideTimings,
            ).size != reconnected.rideLegs.size) return null
        return ObservedTransferRecovery(reconnected, retained, checked)
    }

    private fun TransitJourney.keepsObservedService(
        previous: TransitJourney,
        observation: TransitRideRealtimeObservation,
    ): Boolean {
        val before = previous.legs.getOrNull(observation.legIndex)?.timetable ?: return false
        val after = legs.getOrNull(observation.legIndex)?.timetable ?: return false
        return before.copy(fetchedAt = Instant.EPOCH) == after.copy(fetchedAt = Instant.EPOCH)
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
            seoulBusJourney = projected.projection.seoulBusJourney,
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
            firstBoardingWaitMinutes = projected.projection.overlayResolution.firstBoardingWaitMinutes
                ?: projected.journey.rideLegs.firstOrNull()?.let { projected.journey.displayWait(it) },
            effectiveTransitRoute = projected.projection.effectiveTransitRoute,
            firstBoardingObservation = projected.projection.overlayResolution.firstBoardingObservation,
        )
    }

    private fun TransitJourney.displayWait(leg: TransitJourneyLeg): Int? =
        // maasRP bus waits are route estimates and can be zero even when a bus is several minutes away.
        if (provider == ODSAY_PROVIDER_ID && leg.mode == TransitLegMode.BUS &&
            leg.timingBasis == TransitLegTimingBasis.ROUTE_ESTIMATE) null else leg.waitingMinutes

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
        firstBoardingSourceAt: Instant? = null,
        observedRideTimings: Map<Int, TransitObservedRideTiming> = emptyMap(),
        matchedSelectedRoute: SelectedTransitRoute?,
        mixedFirstBus: SeoulSingleBusProjection? = null,
        mixedTransferMarginSeconds: Long = 0,
    ): EffectiveTransitRoute {
        val firstRideIndex = legs.indexOfFirst(TransitJourneyLeg::isRide)
        val currentRideTimings = currentRideDisplayTimings(
            firstBoardingAt, predictedArrivalAt, observedRideTimings, mixedFirstBus, mixedTransferMarginSeconds,
        )
        return EffectiveTransitRoute(
            provider = provider,
            identity = stableRouteIdentity(matchedSelectedRoute),
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
                        val revisedArrivalAt = rideArrivalPredictor
                            .predictFromObservedBoardingArrival(leg.timetable, firstBoardingAt).arrivalAt
                        val seconds = Duration.between(requireNotNull(leg.scheduledDepartureAt), revisedArrivalAt).seconds
                        ((seconds.coerceAtLeast(0) + 59) / 60).toInt()
                    } else leg.durationMinutes,
                    waitingMinutes = currentTiming?.waitingMinutes ?: if (index == firstRideIndex) {
                        firstBoardingWaitMinutes ?: displayWait(leg)
                    } else {
                        displayWait(leg)
                    },
                    lineName = leg.line?.name,
                    fromName = leg.from?.name,
                    toName = leg.to?.name,
                    directionName = leg.directionName,
                    orderedStopNames = leg.orderedStopNames.takeIf { stops ->
                        leg.mode == TransitLegMode.SUBWAY && leg.timetable != null && stops.size in 2..512 &&
                            stops.first() == leg.from?.name && stops.last() == leg.to?.name && stops[1] == leg.nextStopName
                    }.orEmpty(),
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
                                ?: liveBoardingAt?.let {
                                    rideArrivalPredictor.predictFromObservedBoardingArrival(timetable, it).arrivalAt
                                } ?: timetable.arrivalAt,
                            basis = if (observed?.observationPoint == TransitRideObservationPoint.ALIGHTING) {
                                if (index == firstRideIndex) EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME
                                else EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME
                            } else if (observed?.observationPoint == TransitRideObservationPoint.BOARDING) {
                                if (observed.arrivalAt == timetable.arrivalAt && timetable.shiftFromObservedBoardingArrival(boardingAt).isNegative)
                                    EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING
                                else EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE
                            } else if (liveBoardingAt != null && observed?.arrivalAt == timetable.arrivalAt &&
                                timetable.shiftFromObservedBoardingArrival(liveBoardingAt).isNegative) {
                                EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING
                            } else if (liveBoardingAt != null) {
                                EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE
                            } else EffectiveTransitRideTimingBasis.TIMETABLE,
                            observationSourceUpdatedAt = observed?.observationSourceUpdatedAt,
                            boardingObservationSourceUpdatedAt = firstBoardingSourceAt?.takeIf {
                                index == firstRideIndex && liveBoardingAt != null &&
                                    observed?.observationPoint == TransitRideObservationPoint.ALIGHTING
                            },
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
        mixedFirstBus: SeoulSingleBusProjection? = null,
        mixedTransferMarginSeconds: Long = 0,
    ): Map<Int, RideDisplayTiming> {
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
            val bus = mixedFirstBus?.takeIf { it.rideIndex == index }
            val timetable = leg.timetable
            if (timetable == null && bus == null) return emptyMap()
            // 시간표의 역 출발과 실시간 역 도착은 서로 다른 사건이다. 정차 시간을 지연으로 더하지 않는다.
            val firstBoardingObservationAt = firstBoardingAt.takeIf { index == firstRideIndex }
            val boardingAt = bus?.observation?.expectedBoardingAt ?: observedRideTimings[index]?.boardingAt
                ?: firstBoardingObservationAt ?: requireNotNull(timetable).boardingAt
            val arrivalAt = bus?.alightingAt ?: observedRideTimings[index]?.arrivalAt
                ?: if (firstBoardingObservationAt != null) {
                    rideArrivalPredictor.predictFromObservedBoardingArrival(requireNotNull(timetable), boardingAt).arrivalAt
                } else requireNotNull(timetable).arrivalAt
            if (boardingAt < cursor || arrivalAt <= boardingAt) return emptyMap()
            fun minutesUntil(at: Instant) = ((Duration.between(cursor, at).seconds + 59) / 60).toInt()
            val transferTiming = previousRideArrivalAt?.let {
                val timing = transferFeasibilityEvaluator.describeTransfer(it, Duration.between(it, cursor).seconds, boardingAt)
                if (mixedFirstBus != null && timings.size == 1) timing.copy(confidenceMarginSeconds = mixedTransferMarginSeconds)
                else timing
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

    /** 더 이른 복구 탐색의 실패가 원래 요청 시각의 완전한 미래 시간표를 지우지 않게 한다. */
    private fun ProjectedTransitJourney<TransitCandidateProjection>.requestedTimetableEvidence(
        request: TrafficRequest,
    ): com.noLate.schedule.application.TransitTimetablePlanEvidence? {
        val planned = request.plannedDepartureAt ?: return null
        val target = request.targetArrivalAt ?: return null
        val reason = projection.overlayResolution.failureReason ?: return null
        if (planned <= request.evaluatedAt || journey.departureAt < planned || journey.departureAt >= planned.plusSeconds(60) ||
            predictedArrivalAt > target || projection.routeProvenance != TransitRouteProvenance.SELECTED_ROUTE_PRESERVED ||
            projection.overlayResolution.overlay != null || projection.transferRealtimeDelayed || projection.transferObservedAt != null ||
            projection.transfer.status != TransitTransferStatus.FEASIBLE ||
            projection.transfer.timingBasis != TransitJourneyTimingBasis.TIMETABLE_ONLY ||
            reason !in setOf(TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED, TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE) ||
            projection.effectiveTransitRoute.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
                .any { it.kind != EffectiveTransitRouteSegmentKind.SUBWAY || it.rideTiming?.basis != EffectiveTransitRideTimingBasis.TIMETABLE }) return null
        return com.noLate.schedule.application.TransitTimetablePlanEvidence(
            projection.effectiveTransitRoute, journey.fetchedAt, reason, projection.routeProvenance)
    }

    private fun preferableDegraded(
        current: ProjectedTransitJourney<TransitCandidateProjection>?,
        candidate: ProjectedTransitJourney<TransitCandidateProjection>,
        targetArrivalAt: Instant?,
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
            targetArrivalAt != null && candidate.predictedArrivalAt <= targetArrivalAt &&
                candidate.predictedArrivalAt == current.predictedArrivalAt &&
                current.projection.transfer.timingBasis == TransitJourneyTimingBasis.TIMETABLE_ONLY &&
                candidate.projection.transfer.timingBasis == TransitJourneyTimingBasis.TIMETABLE_ONLY &&
                candidate.journey.rideLegs.all { it.timetable != null } &&
                candidate.journey.rideLegs.map { it.timetable?.copy(fetchedAt = Instant.EPOCH) } ==
                    current.journey.rideLegs.map { it.timetable?.copy(fetchedAt = Instant.EPOCH) } ->
                if (candidate.journey.departureAt > current.journey.departureAt) candidate else current
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
        TransitJourneyTimingBasis.SAME_SUBWAY_BOARDING_AND_ALIGHTING_REALTIME ->
            TransitTimingBasis.SAME_SUBWAY_BOARDING_AND_ALIGHTING_REALTIME
        TransitJourneyTimingBasis.SAME_BUS_BOARDING_AND_ALIGHTING_REALTIME ->
            TransitTimingBasis.SAME_BUS_BOARDING_AND_ALIGHTING_REALTIME
        TransitJourneyTimingBasis.BUS_TRANSFER_BOARDING_AND_ALIGHTING_REALTIME ->
            TransitTimingBasis.BUS_TRANSFER_BOARDING_AND_ALIGHTING_REALTIME
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
        const val TMAP_PROVIDER_ID = "tmap"
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
    val requestedTimetablePlan: com.noLate.schedule.application.TransitTimetablePlanEvidence? = null,
)

private data class TransitCandidateProjection(
    val overlayResolution: TransitRealtimeOverlayResolution,
    val transfer: TransitTransferEvaluation,
    val transferRealtimeDelayed: Boolean,
    val transferObservedAt: Instant?,
    val routeProvenance: TransitRouteProvenance,
    val effectiveTransitRoute: EffectiveTransitRoute,
    val seoulBusJourney: PlannedSeoulBusJourney? = null,
)
