package com.noLate.eta.application.transit

import com.noLate.eta.domain.*
import com.noLate.schedule.application.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** 출발지를 다시 검색하지 않고, 보존한 편의 관측과 이후 시간표로 계획 여정을 갱신한다. */
@Component
class PlannedTransitRouteRefresher(
    private val guard: TransitTransferRealtimeGuard,
    private val timetableEnricher: TransitJourneyTimetableEnricher,
    private val evaluator: TransitTransferFeasibilityEvaluator,
    private val predictor: TransitRideArrivalPredictor = TransitRideArrivalPredictor(),
    @Value("\${schedule.traffic.max-travel-minutes:1440}") private val maxTravelMinutes: Int = 1440,
) {
    internal fun supports(plan: EffectiveTransitRoute): Boolean {
        val rides = plan.segments.filter { it.kind != EffectiveTransitRouteSegmentKind.WALK }
        return rides.isNotEmpty() && rides.all { it.kind == EffectiveTransitRouteSegmentKind.SUBWAY && it.rideTiming != null }
    }

    @JvmOverloads
    fun refresh(
        plan: EffectiveTransitRoute, evaluatedAt: Instant, departurePlan: EffectiveTransitRoute = plan,
        previousUpdate: PlannedTransitRouteUpdate? = null,
        endsAtTransfer: Boolean = false,
    ): PlannedTransitRouteUpdate? {
        if (evaluatedAt < plan.departureAt || !supports(plan)) return null
        val segments = plan.segments.sortedBy { it.sequence }
        val rides = segments.indices.filter { segments[it].kind != EffectiveTransitRouteSegmentKind.WALK }
        val freshWindow = evaluatedAt.minusSeconds(120)..evaluatedAt.plusSeconds(5)
        val originalTrains = originalTransferTrains(plan, departurePlan, segments, rides, evaluatedAt)
        val conflict = previousUpdate?.takeIf { it.routeIdentity == plan.identity }
        val lastRide = segments[rides.last()]
        val storedFinalReport = conflict?.finalTrainAlightingReport()
        if (!endsAtTransfer && storedFinalReport != null &&
            storedFinalReport.sourceUpdatedAt == lastRide.rideTiming?.reportedAlightingAt) return null
        val transferRecheck = conflict?.transferBoardingRecheck
        val recheckIndex = when {
            conflict?.requiresFirstBoardingRecheck == true -> rides.first()
            transferRecheck != null -> rides.drop(1).find { index ->
                val segment = segments[index]
                val table = requireNotNull(segment.rideTiming).timetable
                segment.sequence == transferRecheck.segmentSequence && table.trainNumber == transferRecheck.trainNumber &&
                    table.boardingAt == transferRecheck.scheduledBoardingAt
            } ?: return null
            else -> null
        }
        val readings = guard.observePlannedTransfers(plan, evaluatedAt, originalTrains, recheckBoardingIndex = recheckIndex,
            reportedBoardings = conflict?.reportedBoardings.orEmpty())
        val allAlightingRecords = (conflict?.reportedAlightings.orEmpty() + readings.reportedAlightings)
            .filter { record -> rides.any { record.matches(plan, segments[it], evaluatedAt) } }
            .groupBy { it.segmentSequence }.mapValues { (_, records) -> records.minBy { it.sourceUpdatedAt } }
        val finalReport = allAlightingRecords[lastRide.sequence]
        if (endsAtTransfer && finalReport != null && recheckIndex == null) {
            return PlannedTransitRouteUpdate(plan.identity, finalReport.sourceUpdatedAt,
                reportedBoardings = readings.reportedBoardings, reportedAlightings = allAlightingRecords.values.sortedBy { it.segmentSequence })
        }
        val alightingRecords = if (endsAtTransfer) allAlightingRecords else allAlightingRecords - lastRide.sequence
        // 기록만 생긴 평가가 정상 ETA나 새 출발 알람으로 승격되지 않도록 계산 결과와 분리한다.
        fun calculate(): PlannedTransitRouteUpdate? {
            val observations = readings.current.filter {
                it.legIndex in rides && it.sourceUpdatedAt in freshWindow &&
                    (it.legIndex == recheckIndex || segments[it.legIndex].sequence !in alightingRecords) &&
                    !(it.point == TransitRideObservationPoint.BOARDING && segments[it.legIndex].rideTiming?.observedService != null) &&
                    (it.expectedAt > evaluatedAt.minusSeconds(5) ||
                        it.reportedBoardingArrival && it.expectedAt <= evaluatedAt &&
                        it.expectedAt >= evaluatedAt.minusSeconds(120))
            }
            val firstBoarding = observations.firstOrNull {
                it.legIndex == rides.first() && it.point == TransitRideObservationPoint.BOARDING
            }
            if (recheckIndex != null && observations.none {
                    it.legIndex == recheckIndex && it.point == TransitRideObservationPoint.BOARDING &&
                        it.sourceUpdatedAt > requireNotNull(conflict).sourceUpdatedAt
                }) return null
            val candidates = readings.serviceCandidates.filterKeys { it in rides }.mapValues { (index, choices) ->
                val segment = segments[index]
                choices.filter { candidate ->
                    candidate.service.matchesReference(requireNotNull(segment.rideTiming).timetable) &&
                        candidate.service.matchesOrderedRide(segment.fromName, segment.toName, segment.orderedStopNames) &&
                        candidate.alightingSourceAt in freshWindow &&
                        (candidate.reportedBoarding?.let { it.matches(plan, segment, evaluatedAt) &&
                        it.sourceUpdatedAt == candidate.boardingAt }
                            ?: (candidate.boardingSourceAt in freshWindow && candidate.boardingAt > evaluatedAt.minusSeconds(5))) &&
                        candidate.alightingAt > evaluatedAt &&
                        candidate.alightingAt <= evaluatedAt.plusSeconds(15 * 60L)
                }
            }
            val candidateSources = candidates.values.flatten().flatMap { listOf(it.boardingSourceAt, it.alightingSourceAt) }
            val sourceAt = ((observations + readings.originalBoardings.values).map { it.sourceUpdatedAt } + candidateSources)
                .maxOrNull() ?: return null
            fun failed(reason: PlannedTransitTimingFailure) = PlannedTransitRouteUpdate(plan.identity, sourceAt, failure = reason)
            if (observations.map { it.legIndex }.distinct().size != observations.size) {
                return failed(PlannedTransitTimingFailure.TIMELINE_INCONSISTENT)
            }
            if (firstBoarding != null) {
                // 지연이 줄면 여전히 시간표보다 늦은 열차도 원래 출발 계획으로는 타기 어려울 수 있다.
                val accessSeconds = walkingSeconds(segments, 0, firstBoarding.legIndex)
                val readyAt = plan.departureAt.plusSeconds(accessSeconds + timetableEnricher.boardingBufferSeconds)
                if (firstBoarding.expectedAt < readyAt) return PlannedTransitRouteUpdate(
                    plan.identity, firstBoarding.sourceUpdatedAt, failure = PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED,
                    requiresFirstBoardingRecheck = true,
                )
            }
            val timings = rides.associateWith { index ->
                val segment = segments[index]
                val baseline = requireNotNull(segment.rideTiming).asPlannedBaseline()
                val reportedAt = alightingRecords[segment.sequence]?.sourceUpdatedAt.takeUnless { index == recheckIndex }
                baseline.copy(predictedArrivalAt = reportedAt ?: baseline.predictedArrivalAt, reportedAlightingAt = reportedAt)
            }.toMutableMap()
            val applied = linkedMapOf<Int, TransitRideRealtimeObservation>()
            for (observation in observations) {
                val index = observation.legIndex
                val timing = requireNotNull(timings[index])
                val prior = requireNotNull(segments[index].rideTiming)
                if (observation.earlyBoardingArrival && index != rides.first()) {
                    val preceding = rides.last { it < index }
                    val ready = requireNotNull(timings[preceding]).predictedArrivalAt.plusSeconds(
                        walkingSeconds(segments, preceding + 1, index) + evaluator.requiredTransferMarginSeconds)
                    val possibleDeparture = timing.timetable.boardingAt.plus(
                        timing.timetable.shiftFromObservedBoardingArrival(observation.expectedAt))
                    if (ready > observation.expectedAt) {
                        // 도착과 출발 사이에 준비되는 경우에는 승차역 도착 예보로 탑승 시각을 정할 수 없다.
                        // 기존 시간표가 여전히 가능하면 유지하고, 이전 지연에 의존하던 안내는 철회한다.
                        if (ready > possibleDeparture || prior.predictedBoardingAt != timing.timetable.boardingAt) {
                            val table = timing.timetable
                            return PlannedTransitRouteUpdate(plan.identity, observation.sourceUpdatedAt,
                                failure = PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED,
                                transferBoardingRecheck = PlannedTransferBoardingRecheck(segments[index].sequence,
                                    table.trainNumber, table.boardingAt))
                        }
                        continue
                    }
                }
                val updated = if (observation.point == TransitRideObservationPoint.ALIGHTING) {
                    if (observation.expectedAt <= timing.predictedBoardingAt) {
                        return failed(PlannedTransitTimingFailure.TIMELINE_INCONSISTENT)
                    }
                    timing.copy(predictedArrivalAt = observation.expectedAt,
                        basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
                        observationSourceUpdatedAt = observation.sourceUpdatedAt,
                        boardingObservationSourceUpdatedAt = prior.boardingSourceAt()?.takeIf { it in freshWindow },
                        plannedBoardingIsStationArrival = prior.observedService?.let { true } ?: timing.plannedBoardingIsStationArrival)
                } else {
                    // A fresh prediction at the destination belongs to this train even if a boarding update arrives later.
                    val direct = prior.alightingSourceAt()?.takeIf { it in freshWindow }
                    val arrival = when {
                        direct != null -> prior.predictedArrivalAt
                        observation.earlyBoardingArrival -> timing.timetable.arrivalAt
                        else -> predictor.predictFromObservedBoardingArrival(timing.timetable, observation.expectedAt).arrivalAt
                    }
                    if (arrival <= observation.expectedAt) return failed(PlannedTransitTimingFailure.TIMELINE_INCONSISTENT)
                    val basis = when {
                        direct != null && index == rides.first() -> EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME
                        direct != null -> EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME
                        observation.earlyBoardingArrival -> earlyBoardingBasis(index == rides.first())
                        else -> EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME
                    }
                    timing.copy(predictedBoardingAt = observation.expectedAt, predictedArrivalAt = arrival, basis = basis,
                        observationSourceUpdatedAt = direct ?: observation.sourceUpdatedAt,
                        boardingObservationSourceUpdatedAt = if (direct != null) observation.sourceUpdatedAt else null,
                        plannedBoardingIsStationArrival = if (basis == EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME) true else null)
                }
                timings[index] = updated
                applied[index] = observation
            }
            // 앞 구간의 새 관측을 반영할 때, 미래 환승의 만료된 지연을 승차 가능 근거로 쓰지 않는다.
            // 이미 지난 승차 계획은 보존하고, 아직 탈 다음 편만 원본 시간표로 다시 판단한다.
            val firstChangedRide = (applied.keys + candidates.filterValues { it.isNotEmpty() }.keys).minOrNull()
            for (index in rides.filter { it !in applied }) {
                if (segments[index].sequence in alightingRecords) continue
                val prior = requireNotNull(segments[index].rideTiming)
                val reassessBoarding = firstChangedRide != null && index > firstChangedRide && prior.predictedBoardingAt > evaluatedAt
                val liveSource = prior.observationSourceUpdatedAt?.takeIf { it in freshWindow }
                val boardSource = prior.boardingObservationSourceUpdatedAt?.takeIf { it in freshWindow }
                timings[index] = when {
                    // Keep the original station timestamp: the next read may deliver an older row for this same train.
                    liveSource != null && (prior.boardingObservationSourceUpdatedAt == null || boardSource != null) -> prior
                    liveSource != null -> {
                        val boardingAt = if (reassessBoarding && prior.observedService == null) prior.timetable.boardingAt else prior.predictedBoardingAt
                        if (prior.predictedArrivalAt <= boardingAt) return failed(PlannedTransitTimingFailure.TIMELINE_INCONSISTENT)
                        prior.copy(predictedBoardingAt = boardingAt,
                            plannedBoardingIsStationArrival = prior.observedService != null || !reassessBoarding,
                            basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, boardingObservationSourceUpdatedAt = null)
                    }
                    boardSource != null && prior.observedService == null -> {
                        val earlyBoarding = prior.predictedBoardingAt < (prior.timetable.boardingStationArrivalAt ?: prior.timetable.boardingAt)
                        val arrival = if (earlyBoarding) prior.timetable.arrivalAt
                            else predictor.predictFromObservedBoardingArrival(prior.timetable, prior.predictedBoardingAt).arrivalAt
                        val basis = if (earlyBoarding) earlyBoardingBasis(index == rides.first())
                            else EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME
                        prior.copy(predictedArrivalAt = arrival, basis = basis,
                            observationSourceUpdatedAt = boardSource, boardingObservationSourceUpdatedAt = null, plannedBoardingIsStationArrival = null)
                    }
                    reassessBoarding && prior.observedService == null -> EffectiveTransitRideTiming(prior.timetable, prior.timetable.boardingAt,
                        prior.timetable.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE)
                    else -> timings.getValue(index)
                }
            }

            var changedTrain = false
            // 모든 구간의 직접 예보를 먼저 반영한 후, 그 시각으로 준비 가능한 실제 편을 고른다.
            for ((ordinal, index) in rides.withIndex()) {
                if (index in applied || segments[index].sequence in alightingRecords) continue
                val prior = requireNotNull(segments[index].rideTiming)
                val ready = if (ordinal == 0) plan.departureAt.plusSeconds(
                    walkingSeconds(segments, 0, index) + timetableEnricher.boardingBufferSeconds)
                else timings.getValue(rides[ordinal - 1]).predictedArrivalAt.plusSeconds(
                    walkingSeconds(segments, rides[ordinal - 1] + 1, index) + evaluator.requiredTransferMarginSeconds)
                val available = candidates[index].orEmpty().filter { candidate ->
                        (candidate.service != prior.observedService ||
                            (prior.boardingSourceAt()?.let { candidate.boardingSourceAt >= it } != false &&
                             prior.alightingSourceAt()?.let { candidate.alightingSourceAt >= it } != false))
                }
                val eligible = available.filter { it.boardingAt >= ready }
                // 현재 편도 새 준비시각으로 탈 수 있을 때만 우선한다. 불가능해졌다면 두 역이
                // 확인한 다른 편을 선택한다. 어느 편도 탈 수 없으면 새 모순을 버리지 않는다.
                val currentEligible = eligible.filter { it.service == prior.observedService }
                val selected = currentEligible.ifEmpty { eligible }.minWithOrNull(compareBy<ObservedSubwayRideCandidate> { it.alightingAt }
                    .thenBy { it.boardingAt }.thenBy { it.service.trainNumber })
                    ?: available.firstOrNull { it.service == prior.observedService && it.boardingAt < ready }
                    ?: continue
                if (ordinal == 0 && selected.boardingAt < ready) return PlannedTransitRouteUpdate(plan.identity,
                    minOf(selected.boardingSourceAt, selected.alightingSourceAt),
                    failure = PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED)
                if (selected.service == prior.observedService && selected.boardingAt == prior.predictedBoardingAt &&
                    selected.alightingAt == prior.predictedArrivalAt && selected.boardingSourceAt == prior.boardingSourceAt() &&
                    selected.alightingSourceAt == prior.alightingSourceAt()) continue
                val timing = timings.getValue(index)
                timings[index] = EffectiveTransitRideTiming(timing.timetable, selected.boardingAt, selected.alightingAt,
                    if (selected.reportedBoarding != null) EffectiveTransitRideTimingBasis.REPORTED_BOARDING_AND_ALIGHTING_REALTIME
                        else EffectiveTransitRideTimingBasis.OBSERVED_SUBWAY_SERVICE_REALTIME, selected.alightingSourceAt,
                    boardingObservationSourceUpdatedAt = selected.boardingSourceAt, observedService = selected.service,
                    reportedBoarding = selected.reportedBoarding)
                applied[index] = TransitRideRealtimeObservation(index, TransitRideObservationPoint.ALIGHTING,
                    selected.alightingAt, selected.alightingSourceAt)
                changedTrain = changedTrain || selected.service.trainNumber != prior.selectedTrainNumber()
            }
            val replacedBoardingSources = mutableListOf<Instant>()
            for ((current, next) in rides.zipWithNext()) {
                val inbound = timings.getValue(current)
                val arrival = inbound.predictedArrivalAt
                val ready = arrival.plusSeconds(walkingSeconds(segments, current + 1, next) + evaluator.requiredTransferMarginSeconds)
                val original = originalTrains[next]
                val boarding = readings.originalBoardings[next]
                // A verified past station report can establish readiness. It contributes no freshness to the new ETA.
                val inboundSupported = inbound.reportedAlightingAt != null ||
                    arrival > evaluatedAt && inbound.alightingSourceAt()?.let { it in freshWindow } == true
                if (original != null && boarding != null && inboundSupported) {
                    restoredTransfer(original, boarding, timings.getValue(next), ready, evaluatedAt, freshWindow)?.let { restored ->
                        timings[next] = restored
                        applied[next] = boarding
                        changedTrain = true
                    }
                }
                if (applied.isEmpty() && replacedBoardingSources.isEmpty()) continue
                val nextTiming = timings.getValue(next)
                if (nextTiming.observedService != null && nextTiming.predictedBoardingAt > evaluatedAt &&
                    applied.keys.any { it <= current } && nextTiming.boardingSourceAt()?.let { it in freshWindow } != true) {
                    // 다른 차량의 오래된 시간표로 현재 선택 차량의 승차 가능성을 판단하지 않는다.
                    return failed(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE)
                }
                if (ready <= requireNotNull(timings[next]).predictedBoardingAt) continue
                // 관측한 구간까지 보존하고, 이후 같은 경로에서 탈 수 있는 다음 편을 찾는다.
                val journey = toJourney(plan, segments, timings, evaluatedAt)
                val observedOnwardTimes = rides.filter { it > current }.mapNotNull { index ->
                    val timing = timings.getValue(index)
                    if (timing.alightingSourceAt()?.let { it in freshWindow } != true ||
                        (timing.observedService != null && timing.boardingSourceAt()?.let { it in freshWindow } != true)) {
                        return@mapNotNull null
                    }
                    index to TransitConnectionRideTimes(timing.predictedBoardingAt, timing.predictedArrivalAt)
                }.toMap()
                val reconnected = timetableEnricher.reconnectAfterObservedRide(
                    journey, current, arrival, evaluatedAt, maxTravelMinutes, observedOnwardTimes)
                if (reconnected === journey) return failed(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE)
                for (index in rides.filter { it > current }) {
                    val table = requireNotNull(reconnected.legs[index].timetable)
                    val old = requireNotNull(timings[index])
                    if (table.copy(fetchedAt = Instant.EPOCH) != old.timetable.copy(fetchedAt = Instant.EPOCH)) {
                        changedTrain = true
                        timings[index] = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt,
                            EffectiveTransitRideTimingBasis.TIMETABLE)
                        // 승차 여유가 부족해진 근거를 보존한다. 대체 편은 자체 시간표를 사용한다.
                        val removed = applied.remove(index)
                        if (index == next && removed?.point == TransitRideObservationPoint.BOARDING) {
                            replacedBoardingSources += removed.sourceUpdatedAt
                        } else if (index == next && removed != null && old.observedService != null &&
                            old.predictedBoardingAt < ready) {
                            // 두 역 예보로 현재 실제 편을 놓친 근거다. 다음 시간표로 연결한 뒤에도
                            // 이 승차역 출처를 보존해 새 계획을 실시간 하차 예보로 오인하지 않는다.
                            old.boardingSourceAt()?.let { replacedBoardingSources += it }
                        }
                    }
                }
                if (ready > requireNotNull(timings[next]).predictedBoardingAt) {
                    return failed(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE)
                }
            }

            if (applied.isEmpty() && replacedBoardingSources.isEmpty()) return null
            var cursor = plan.departureAt
            var precedingRide: Int? = null
            val updatedSegments = segments.mapIndexed { index, segment ->
                if (segment.kind == EffectiveTransitRouteSegmentKind.WALK) {
                    cursor = cursor.plusSeconds(segment.durationSeconds ?: segment.durationMinutes.toLong() * 60)
                    segment
                } else {
                    val timing = requireNotNull(timings[index])
                    if (timing.predictedBoardingAt < cursor || timing.predictedArrivalAt <= cursor) {
                        return failed(PlannedTransitTimingFailure.TIMELINE_INCONSISTENT)
                    }
                    val duration = Duration.between(cursor, timing.predictedArrivalAt).seconds
                    val wait = Duration.between(cursor, timing.predictedBoardingAt).seconds
                    val transfer = precedingRide?.let { previous -> evaluator.describeTransfer(
                        requireNotNull(timings[previous]).predictedArrivalAt,
                        walkingSeconds(segments, previous + 1, index), timing.predictedBoardingAt,
                    ) }
                    cursor = timing.predictedArrivalAt
                    precedingRide = index
                    segment.copy(durationMinutes = ceilMinutes(duration), waitingMinutes = ceilMinutes(wait),
                        rideTiming = timing, transferTiming = transfer)
                }
            }
            val duration = Duration.between(plan.departureAt, cursor).seconds
            if (duration !in 1..maxTravelMinutes.toLong() * 60 || cursor <= evaluatedAt) {
                return failed(PlannedTransitTimingFailure.TIMELINE_INCONSISTENT)
            }
            val route = plan.copy(arrivalAt = cursor, totalMinutes = ceilMinutes(duration), segments = updatedSegments)
            // 여러 직접 관측 중 가장 오래된 출처로 전체 잠정 경로의 신선도를 제한한다.
            val routeSourceAt = (replacedBoardingSources + applied.values.map { it.sourceUpdatedAt } +
                timings.values.flatMap { listOfNotNull(it.observationSourceUpdatedAt,
                    it.boardingObservationSourceUpdatedAt.takeIf { _ -> it.reportedBoarding == null }) }).minOrNull() ?: sourceAt
            return PlannedTransitRouteUpdate(plan.identity, routeSourceAt, route = route,
                onlyLastRideObserved = !changedTrain && applied.keys == setOf(rides.last()) &&
                    rides.dropLast(1).none { timings.getValue(it).observationSourceUpdatedAt != null } &&
                    !timings.getValue(rides.last()).basis.keepsTimetableAlightingAfterEarlyBoarding)
        }
        val calculated = calculate()
        val reportedAlightings = allAlightingRecords.values.sortedBy { it.segmentSequence }
        // Resolve any newly observed upstream conflict before applying the final station report.
        val finalRoute = calculated?.route ?: plan.takeIf { calculated == null && conflict?.failure == null }
        if (!endsAtTransfer && finalReport != null && finalRoute != null && recheckIndex == null) {
            finalReport.applyToFinalRide(finalRoute, evaluatedAt, maxTravelMinutes)?.let { revised ->
                return PlannedTransitRouteUpdate(plan.identity, finalReport.sourceUpdatedAt, route = revised,
                    reportedBoardings = readings.reportedBoardings, reportedAlightings = reportedAlightings)
            }
        }
        if (calculated != null) return calculated.copy(reportedBoardings = readings.reportedBoardings,
            reportedAlightings = reportedAlightings)
        if (readings.reportedBoardings == conflict?.reportedBoardings.orEmpty() &&
            reportedAlightings == conflict?.reportedAlightings.orEmpty()) return null
        if (conflict != null && (conflict.route != null || conflict.failure != null)) {
            return conflict.copy(reportedBoardings = readings.reportedBoardings, reportedAlightings = reportedAlightings)
        }
        val recordedAt = (readings.reportedBoardings.map { it.sourceUpdatedAt } + reportedAlightings.map { it.sourceUpdatedAt })
            .maxOrNull() ?: return null
        return PlannedTransitRouteUpdate(plan.identity, recordedAt, reportedBoardings = readings.reportedBoardings,
            reportedAlightings = reportedAlightings)
    }

    private fun earlyBoardingBasis(firstRide: Boolean) =
        if (firstRide) EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING
        else EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING

    private fun restoredTransfer(original: EffectiveTransitRideTiming, boarding: TransitRideRealtimeObservation,
                                 current: EffectiveTransitRideTiming, readyAt: Instant,
                                 evaluatedAt: Instant, freshWindow: ClosedRange<Instant>): EffectiveTransitRideTiming? {
        val table = original.timetable
        // Once the scheduled departure has passed, the original train is eligible only with a fresh
        // approaching forecast. Use its station arrival as the conservative boarding boundary.
        val boardingBoundary = if (table.boardingAt <= evaluatedAt) boarding.expectedAt
            else minOf(table.boardingAt, boarding.expectedAt)
        if (current.selectedTrainNumber() == table.trainNumber || boarding.expectedAt >= current.predictedBoardingAt ||
            readyAt > boardingBoundary) return null
        val direct = original.alightingSourceAt()?.takeIf { it in freshWindow }
        val arrival = when {
            direct != null -> original.predictedArrivalAt
            boarding.earlyBoardingArrival -> table.arrivalAt
            else -> predictor.predictFromObservedBoardingArrival(table, boarding.expectedAt).arrivalAt
        }
        if (arrival <= boarding.expectedAt || arrival >= current.predictedArrivalAt) return null
        return if (direct != null) EffectiveTransitRideTiming(table, boarding.expectedAt, arrival,
            EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, direct,
            plannedBoardingIsStationArrival = true, boardingObservationSourceUpdatedAt = boarding.sourceUpdatedAt)
        else {
            val basis = if (boarding.earlyBoardingArrival) EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING
                else EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME
            EffectiveTransitRideTiming(table, boarding.expectedAt, arrival, basis, boarding.sourceUpdatedAt)
        }
    }

    private fun originalTransferTrains(plan: EffectiveTransitRoute, departurePlan: EffectiveTransitRoute,
                                       segments: List<EffectiveTransitRouteSegment>, rides: List<Int>,
                                       evaluatedAt: Instant): Map<Int, EffectiveTransitRideTiming> {
        if (plan === departurePlan || plan.identity != departurePlan.identity || plan.provider != departurePlan.provider ||
            plan.departureAt != departurePlan.departureAt) return emptyMap()
        val original = departurePlan.segments.sortedBy { it.sequence }
        if (original.size != segments.size || segments.indices.any { index ->
                val a = segments[index]
                val b = original[index]
                a.sequence != b.sequence || a.kind != b.kind || a.fromName != b.fromName || a.toName != b.toName ||
                    a.orderedStopNames != b.orderedStopNames
            }) return emptyMap()
        return rides.drop(1).mapNotNull { index ->
            val timing = segments[index].rideTiming ?: return@mapNotNull null
            val active = timing.timetable
            val originalTiming = original[index].rideTiming ?: return@mapNotNull null
            val earlier = originalTiming.timetable
            if (earlier.provider != active.provider || earlier.line != active.line || earlier.serviceDate != active.serviceDate ||
                earlier.directionCode != active.directionCode || earlier.serviceClass != active.serviceClass ||
                earlier.fromStationCode != active.fromStationCode || earlier.toStationCode != active.toStationCode ||
                earlier.trainNumber == active.trainNumber || earlier.boardingAt >= active.boardingAt ||
                timing.predictedBoardingAt <= evaluatedAt) return@mapNotNull null
            index to originalTiming
        }.toMap()
    }

    private fun toJourney(plan: EffectiveTransitRoute, segments: List<EffectiveTransitRouteSegment>,
                          timings: Map<Int, EffectiveTransitRideTiming>, evaluatedAt: Instant): TransitJourney =
        TransitJourney(plan.provider, plan.departureAt, plan.departureAt, plan.arrivalAt, plan.totalMinutes,
            segments.mapIndexed { index, segment ->
                val timing = timings[index]
                val table = timing?.timetable
                TransitJourneyLeg(index,
                    if (table == null) TransitLegMode.WALK else TransitLegMode.SUBWAY,
                    segment.durationMinutes, segment.waitingMinutes,
                    scheduledDepartureAt = table?.boardingAt, scheduledArrivalAt = table?.arrivalAt,
                    from = table?.let { TransitStop(providerStopId = it.fromStationCode, name = segment.fromName) },
                    to = table?.let { TransitStop(providerStopId = it.toStationCode, name = segment.toName) },
                    line = table?.let { TransitLine(name = segment.lineName, serviceClass = it.serviceClass, seoulSubwayLine = it.line) },
                    directionCode = table?.directionCode,
                    nextStopName = segment.orderedStopNames.getOrNull(1),
                    orderedStopNames = segment.orderedStopNames,
                    timingBasis = if (table == null) TransitLegTimingBasis.UNKNOWN else TransitLegTimingBasis.TIMETABLE,
                    durationSeconds = segment.durationSeconds, timetable = table,
                    walkingGeometry = segment.walkingGeometry, walkingTiming = segment.walkingTiming)
            }, evaluatedAt)

    private fun walkingSeconds(segments: List<EffectiveTransitRouteSegment>, from: Int, to: Int) =
        segments.subList(from, to).sumOf { it.durationSeconds ?: it.durationMinutes.toLong() * 60 }

    private fun ceilMinutes(seconds: Long): Int = ((seconds + 59) / 60).toInt()
}
