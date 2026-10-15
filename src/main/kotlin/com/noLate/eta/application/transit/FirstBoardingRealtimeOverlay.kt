package com.noLate.eta.application.transit

import com.noLate.eta.domain.LegacyTransitBoardingPlan
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.TransitEtaProviderMetricId
import com.noLate.global.observability.TransitEtaProviderMetricOutcome
import com.noLate.global.observability.recordSafely
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.domain.TransitCityCodeNamespace
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.noLate.eta.resilience.EtaProviderBulkheadRejectedException
import org.springframework.web.client.RestClientResponseException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class TransitRealtimeOverlay(
    val travelMinutes: Int,
    val predictedArrivalAt: Instant,
    val observedAt: Instant,
    /** 실시간 도착 후보가 첫 승차 정류장에 도착하는 절대 시각. */
    val boardingAt: Instant,
    /** 표시 전용 첫 대기시간이며 travelMinutes에 이미 합산되어 있다. */
    val firstBoardingWaitMinutes: Int = 0,
)

data class TransitRealtimeOverlayResolution(
    val overlay: TransitRealtimeOverlay? = null,
    val failureReason: String? = null,
    /** 전체 ETA를 보정하지 못해도 검증된 첫 차량 관측은 따로 보존한다. */
    val firstBoardingObservation: TransitFirstBoardingObservation? = null,
) {
    init {
        require((overlay != null) xor !failureReason.isNullOrBlank()) {
            "실시간 승차 보정 결과는 overlay 또는 실패 사유 중 정확히 하나를 가져야 합니다."
        }
    }
}

/**
 * 전체 시간표 여정의 첫 승차 대기만 현재 정류장 도착정보로 교체한다.
 *
 * 미래 환승 정류장의 현재 도착정보는 조회하지 않는다. 사용자가 실제로 환승할 시각의
 * 정보가 아니기 때문에 전체 시간표 재조회 결과보다 정확하다고 볼 수 없기 때문이다.
 */
@Component
class FirstBoardingRealtimeOverlay(
    private val transitArrivalService: TransitArrivalService,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${eta.transit.boarding-buffer-seconds:60}")
    private val boardingBufferSeconds: Long = DEFAULT_BOARDING_BUFFER_SECONDS,
    @Value("\${eta.transit.arrival-limit:10}")
    private val arrivalLimit: Int = DEFAULT_ARRIVAL_LIMIT,
    @Value("\${eta.transit.arrival-source-max-age-seconds:120}")
    private val arrivalSourceMaxAgeSeconds: Long = DEFAULT_ARRIVAL_SOURCE_MAX_AGE_SECONDS,
    @Value("\${eta.transit.arrival-cache-ttl-seconds:15}")
    private val arrivalCacheTtlSeconds: Long = DEFAULT_ARRIVAL_CACHE_TTL_SECONDS,
    @Value("\${eta.transit.arrival-cache-max-entries:512}")
    private val arrivalCacheMaxEntries: Int = DEFAULT_ARRIVAL_CACHE_MAX_ENTRIES,
    private val operationalMetrics: NoLateOperationalMetrics? = null,
) {
    init {
        require(boardingBufferSeconds in 0..MAX_BOARDING_BUFFER_SECONDS) {
            "대중교통 승차 여유시간은 0~${MAX_BOARDING_BUFFER_SECONDS}초 사이여야 합니다."
        }
        require(arrivalLimit in 1..MAX_ARRIVAL_LIMIT) {
            "대중교통 도착 조회 개수는 1~$MAX_ARRIVAL_LIMIT 사이여야 합니다."
        }
        require(arrivalSourceMaxAgeSeconds in 1..MAX_ARRIVAL_SOURCE_MAX_AGE_SECONDS) {
            "대중교통 도착정보 원천 시각 허용치는 1~${MAX_ARRIVAL_SOURCE_MAX_AGE_SECONDS}초 사이여야 합니다."
        }
        require(arrivalCacheTtlSeconds in 1..MAX_ARRIVAL_CACHE_TTL_SECONDS) {
            "대중교통 도착정보 캐시 TTL은 1~${MAX_ARRIVAL_CACHE_TTL_SECONDS}초 사이여야 합니다."
        }
        require(arrivalCacheMaxEntries in 1..MAX_ARRIVAL_CACHE_MAX_ENTRIES) {
            "대중교통 도착정보 캐시는 1~${MAX_ARRIVAL_CACHE_MAX_ENTRIES}개 사이여야 합니다."
        }
    }

    /**
     * safe-departure 재탐색은 같은 first-stop을 연속 조회한다. 짧은 bounded LRU로 그 중복만
     * 흡수하며, DTO의 sourceUpdatedAt freshness는 cache hit 뒤에도 매번 다시 검증한다.
     */
    private val arrivalCache = LinkedHashMap<ArrivalCacheKey, ArrivalCacheEntry>(16, 0.75f, true)

    fun resolve(
        journey: TransitJourney,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
    ): TransitRealtimeOverlayResolution {
        val firstRideIndex = journey.legs.indexOfFirst(TransitJourneyLeg::isRide)
        if (firstRideIndex < 0) return unavailableMetadata()
        val firstRide = journey.legs[firstRideIndex]
        // 실제 시간표의 초 단위 대기를 표시용 올림 분으로 되돌리지 않는다.
        val scheduledWaitMillis = firstRide.timetable?.let {
            val start = firstRide.scheduledDepartureAt ?: return unavailableMetadata()
            Duration.between(start, it.boardingAt).toMillis()
        } ?: firstRide.waitingMinutes?.toLong()?.times(60_000L)
        val accessMillis = journey.legs
            .take(firstRideIndex)
            .sumOf(TransitJourneyLeg::movementDurationSeconds) * 1_000L
        val embeddedDelay = firstRide.firstBoardingObservation?.let { observation ->
            Duration.between(requireNotNull(firstRide.timetable).boardingAt, observation.expectedBoardingAt)
        } ?: Duration.ZERO
        // 지연 후보 연결에서 이미 적용한 지연을 실시간 보정으로 두 번 더하지 않는다.
        val baselineArrivalAt = journey.arrivalAt.minus(embeddedDelay)
        val plan = lookupPlan(journey, firstRide, firstRide.timetable, accessMillis,
            baselineArrivalAt, scheduledWaitMillis) ?: return unavailableMetadata()
        val baseDepartureAt = maxOf(evaluatedAt, journey.departureAt)
        // 시간표가 없는 미래 경로의 승차시각을 현재 차량으로 채우지 않는다.
        if (scheduledWaitMillis == null && stopArrivalAt(baseDepartureAt, accessMillis)
                .isAfter(evaluatedAt.plusSeconds(OBSERVATION_LOOKAHEAD_SECONDS))) {
            return TransitRealtimeOverlayResolution(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON)
        }
        val selected = selectArrival(plan, baseDepartureAt, evaluatedAt)
        if (selected.candidate == null) return TransitRealtimeOverlayResolution(failureReason = selected.failureReason)
        val candidate = selected.candidate
        if (scheduledWaitMillis == null && candidate.expectedAt.isAfter(evaluatedAt.plusSeconds(OBSERVATION_LOOKAHEAD_SECONDS))) {
            return TransitRealtimeOverlayResolution(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON)
        }
        val observation = observation(plan, baseDepartureAt, evaluatedAt, candidate)
        if (scheduledWaitMillis == null) {
            return TransitRealtimeOverlayResolution(
                failureReason = TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
                firstBoardingObservation = observation,
            )
        }
        val overlay = overlay(plan, baseDepartureAt, baselineArrivalAt, scheduledWaitMillis, candidate, maxTravelMinutes)
        return if (overlay == null) {
            TransitRealtimeOverlayResolution(failureReason = TrafficFailureReasons.PROVIDER_INVALID_RESPONSE)
        } else {
            TransitRealtimeOverlayResolution(overlay = overlay, firstBoardingObservation = observation)
        }
    }

    /** 과거 시간표 후보도 동일 차량·방향·종별·최신성·승차 여유 검증을 모두 통과해야 한다. */
    fun observeTimetableCandidate(
        journey: TransitJourney,
        timetable: TransitLegTimetable,
        evaluatedAt: Instant,
    ): TransitFirstBoardingObservation? {
        val index = journey.legs.indexOfFirst(TransitJourneyLeg::isRide)
        if (index < 0) return null
        val first = journey.legs[index]
        if (first.mode != TransitLegMode.SUBWAY || first.line?.seoulSubwayLine != timetable.line ||
            first.line.serviceClass != timetable.serviceClass) return null
        val accessMillis = journey.legs.take(index).sumOf(TransitJourneyLeg::movementDurationSeconds) * 1000L
        val plan = lookupPlan(journey, first.copy(directionCode = timetable.directionCode, directionName = null),
            timetable, accessMillis, journey.arrivalAt, null) ?: return null
        val base = maxOf(evaluatedAt, journey.departureAt)
        val candidate = selectArrival(plan, base, evaluatedAt).candidate ?: return null
        return observation(plan, base, evaluatedAt, candidate)
    }

    private fun observation(plan: BoardingLookupPlan, base: Instant, evaluatedAt: Instant, candidate: ArrivalCandidate) =
        TransitFirstBoardingObservation(
            provider = candidate.arrival.provider, stationName = plan.stationName, lineName = plan.lineName,
            departureAt = base, readyAt = stopArrivalAt(base, plan.accessMillis).plusSeconds(boardingBufferSeconds),
            expectedBoardingAt = candidate.expectedAt, sourceUpdatedAt = requireNotNull(candidate.observedAt),
            evaluatedAt = evaluatedAt, trainNumber = candidate.arrival.trainNumber,
        )

    private fun lookupPlan(
        journey: TransitJourney,
        firstRide: TransitJourneyLeg,
        timetable: TransitLegTimetable?,
        accessMillis: Long,
        baselineArrivalAt: Instant,
        scheduledWaitMillis: Long?,
    ): BoardingLookupPlan? {
        val firstStop = firstRide.from ?: return null
        val firstLine = firstRide.line ?: return null
        return BoardingLookupPlan(
            kind = firstRide.mode,
            accessMillis = accessMillis,
            // 전체 표시용 분의 올림까지 실시간 보정에 다시 넣지 않는다.
            travelMinutesWithoutFirstWait = Duration.between(journey.departureAt, baselineArrivalAt)
                .toMillis().toDouble() / MILLIS_PER_MINUTE - (scheduledWaitMillis ?: 0L).toDouble() / MILLIS_PER_MINUTE,
            stationName = firstStop.name ?: return null,
            alightingStationName = firstRide.to?.name,
            lineName = firstLine.seoulSubwayLine?.displayName ?: firstLine.name ?: return null,
            seoulSubwayLine = firstLine.seoulSubwayLine,
            expectedTrainNumber = timetable?.trainNumber,
            scheduledBoardingAt = timetable?.boardingAt,
            expectedRealtimeTrainNumber = timetable?.realtimeTrainNumber,
            verifiedTerminalName = timetable?.terminalName,
            subwayServiceClass = firstLine.serviceClass,
            directionName = firstRide.directionName,
            directionCode = firstRide.directionCode,
            arsId = firstStop.arsId,
            cityCode = firstStop.cityCode,
            cityCodeNamespace = firstStop.cityCodeNamespace,
            providerCode = firstStop.providerCode,
            nodeId = firstStop.localStopId,
            subwayRealtimeMetadataSupported = firstRide.mode != TransitLegMode.SUBWAY ||
                firstLine.seoulSubwayLine != null ||
                supportsSeoulSubwayRealtime(
                    networkCityCode = firstLine.cityCode,
                    stationCityCode = firstStop.cityCode,
                    stationCityCodeNamespace = firstStop.cityCodeNamespace,
                    alightingStationName = firstRide.to?.name,
                    serviceClass = firstLine.serviceClass,
                ),
        )
    }

    fun resolveLegacy(
        plan: LegacyTransitBoardingPlan,
        plannedDepartureAt: Instant,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
    ): TransitRealtimeOverlayResolution {
        val lookup = BoardingLookupPlan(
            kind = plan.kind,
            accessMillis = (plan.accessMinutes * MILLIS_PER_MINUTE).toLong(),
            travelMinutesWithoutFirstWait = plan.travelMinutesWithoutFirstWait,
            stationName = plan.stationName,
            // Legacy snapshots do not preserve the alighting station or subway network code.
            // A direction label alone cannot prove that a short-turn/express train serves the
            // selected stop, so legacy subway realtime overlay stays fail-closed.
            alightingStationName = null,
            lineName = plan.lineName,
            seoulSubwayLine = null,
            expectedTrainNumber = null,
            expectedRealtimeTrainNumber = null,
            verifiedTerminalName = null,
            subwayServiceClass = TransitServiceClass.UNKNOWN,
            directionName = plan.directionName,
            directionCode = plan.directionCode,
            arsId = plan.arsId,
            cityCode = plan.cityCode,
            cityCodeNamespace = plan.cityCodeNamespace,
            providerCode = null,
            nodeId = plan.nodeId,
            subwayRealtimeMetadataSupported = plan.kind != TransitLegMode.SUBWAY,
        )
        val baseDepartureAt = maxOf(evaluatedAt, plannedDepartureAt)
        val selected = selectArrival(lookup, baseDepartureAt, evaluatedAt)
        if (selected.candidate == null) return TransitRealtimeOverlayResolution(failureReason = selected.failureReason)
        val stopArrivalAt = stopArrivalAt(baseDepartureAt, lookup.accessMillis)
        val liveWaitMinutes = Duration.between(stopArrivalAt, selected.candidate.expectedAt)
            .toMillis()
            .coerceAtLeast(0)
            .toDouble() / MILLIS_PER_MINUTE
        val travelMinutes = EtaTravelTimePolicy.normalizeMinutes(
            lookup.travelMinutesWithoutFirstWait + liveWaitMinutes,
            maxTravelMinutes,
        ) ?: return TransitRealtimeOverlayResolution(
            failureReason = TrafficFailureReasons.PROVIDER_INVALID_RESPONSE
        )
        return TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(
                travelMinutes = travelMinutes,
                predictedArrivalAt = baseDepartureAt.plusSeconds(travelMinutes.toLong() * 60),
                observedAt = selected.candidate.observedAt ?: Instant.now(clock),
                boardingAt = selected.candidate.expectedAt,
                firstBoardingWaitMinutes = ceil(liveWaitMinutes).toInt().coerceAtLeast(0),
            )
        )
    }

    private fun overlay(
        plan: BoardingLookupPlan,
        baseDepartureAt: Instant,
        scheduledArrivalAt: Instant,
        scheduledWaitMillis: Long,
        selected: ArrivalCandidate,
        maxTravelMinutes: Int,
    ): TransitRealtimeOverlay? {
        val stopArrivalAt = stopArrivalAt(baseDepartureAt, plan.accessMillis)
        val liveWaitMillis = Duration.between(stopArrivalAt, selected.expectedAt)
            .toMillis().coerceAtLeast(0)
        val liveWaitMinutes = liveWaitMillis.toDouble() / MILLIS_PER_MINUTE
        val travelMinutes = EtaTravelTimePolicy.normalizeMinutes(
            plan.travelMinutesWithoutFirstWait + liveWaitMinutes,
            maxTravelMinutes,
        ) ?: return null
        // 정수 밀리초로 차이를 계산해 소수 분 변환 때의 절삭 오차를 피한다.
        val waitDeltaMillis = liveWaitMillis - scheduledWaitMillis
        return TransitRealtimeOverlay(
            travelMinutes = travelMinutes,
            predictedArrivalAt = scheduledArrivalAt.plusMillis(waitDeltaMillis),
            observedAt = selected.observedAt ?: Instant.now(clock),
            boardingAt = selected.expectedAt,
            firstBoardingWaitMinutes = ceil(liveWaitMinutes).toInt().coerceAtLeast(0),
        )
    }

    private fun selectArrival(
        plan: BoardingLookupPlan,
        departureAt: Instant,
        evaluatedAt: Instant,
    ): ArrivalSelection {
        if (plan.kind == TransitLegMode.SUBWAY && !plan.subwayRealtimeMetadataSupported) {
            return ArrivalSelection(
                failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
            )
        }
        val lookup = getArrivals(plan)
        lookup.failureReason?.let { return ArrivalSelection(failureReason = it) }
        val arrivals = lookup.arrivals
            .filter(TransitArrivalDto::realtime)
            .let { candidates ->
                if (plan.kind == TransitLegMode.SUBWAY) {
                    filterCompatibleSubwayArrivals(candidates, plan)
                } else {
                    candidates
                }
            }
        if (arrivals.isEmpty()) {
            // 신선한 같은 역·노선·방향 feed가 있어야만 '미래 열차 미관측'이라고 말한다.
            // 빈 응답/다른 방향/다른 종별/오래된 응답을 그 상태로 승격하지 않는다.
            val related = if (plan.kind == TransitLegMode.SUBWAY && plan.expectedTrainNumber != null) {
                filterCompatibleSubwayArrivals(lookup.arrivals.filter(TransitArrivalDto::realtime), plan, matchTrain = false)
            } else emptyList()
            val evaluations = related.map { it.toCandidate(evaluatedAt) }
            val expectedTrainPresent = lookup.arrivals.any { it.trainNumber == plan.expectedTrainNumber ||
                (plan.expectedRealtimeTrainNumber != null && it.trainNumber == plan.expectedRealtimeTrainNumber) }
            val reason = sourceRejectionReason(evaluations) ?: if (
                !expectedTrainPresent && plan.scheduledBoardingAt?.isAfter(evaluatedAt) == true &&
                evaluations.any { it.candidate != null }
            ) TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED else TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
            return ArrivalSelection(
                failureReason = reason
            )
        }
        val evaluations = arrivals.map { it.toCandidate(evaluatedAt) }
        val candidates = evaluations.mapNotNull(ArrivalCandidateEvaluation::candidate)
        if (candidates.isEmpty()) {
            val rejectionOutcome = when {
                evaluations.any(ArrivalCandidateEvaluation::rejectedUnverifiedSource) ->
                    TransitEtaProviderMetricOutcome.REJECTED_UNVERIFIED_SOURCE
                evaluations.isNotEmpty() && evaluations.all(ArrivalCandidateEvaluation::rejectedStale) ->
                    TransitEtaProviderMetricOutcome.REJECTED_STALE
                else -> null
            }
            rejectionOutcome?.let { lookup.recordLocalRejection(arrivals, it) }
            return ArrivalSelection(
                failureReason = sourceRejectionReason(evaluations) ?: TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
            )
        }
        val catchableAt = stopArrivalAt(departureAt, plan.accessMillis)
            .plusSeconds(boardingBufferSeconds)
        val candidate = candidates
            .filter { !it.expectedAt.isBefore(catchableAt) }
            .minByOrNull(ArrivalCandidate::expectedAt)
        return if (candidate == null) {
            ArrivalSelection(
                failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON
            )
        } else {
            ArrivalSelection(candidate = candidate)
        }
    }

    private fun getArrivals(plan: BoardingLookupPlan): ArrivalCacheEntry {
        val key = ArrivalCacheKey.from(plan, arrivalLimit)
        val now = Instant.now(clock)
        val previous = synchronized(arrivalCache) {
            val cached = arrivalCache[key]
            if (cached != null && !now.isAfter(cached.cachedAt.plusSeconds(arrivalCacheTtlSeconds))) {
                return cached
            }
            if (cached != null) arrivalCache.remove(key)
            cached
        }

        val fetched = runCatching {
            when (plan.kind) {
                TransitLegMode.BUS -> transitArrivalService.getBusArrivals(
                    arsId = plan.arsId,
                    routeName = plan.lineName,
                    cityCode = plan.cityCode,
                    cityCodeNamespace = plan.cityCodeNamespace,
                    providerCode = plan.providerCode,
                    nodeId = plan.nodeId,
                    stationName = plan.stationName,
                    limit = arrivalLimit,
                )
                TransitLegMode.SUBWAY -> transitArrivalService.getSubwayArrivalsForEta(
                    stationName = plan.stationName,
                    lineName = plan.lineName,
                    directionName = plan.directionName,
                    directionCode = plan.directionCode,
                    limit = arrivalLimit,
                )
                else -> emptyList()
            }
        }

        val receivedAt = Instant.now(clock)
        val latest = fetched.getOrDefault(emptyList()).toList()
        val retained = if (fetched.isSuccess && previous != null) {
            retainFreshMissingVehicles(plan, latest, previous.arrivals, receivedAt)
        } else emptyList()
        val entry = ArrivalCacheEntry(cachedAt = now, arrivals = latest + retained,
            failureReason = fetched.exceptionOrNull()?.let(::arrivalApiFailureReason))
        synchronized(arrivalCache) {
            if (!arrivalCache.containsKey(key) && arrivalCache.size >= arrivalCacheMaxEntries) {
                val eldest = arrivalCache.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            arrivalCache[key] = entry
        }
        return entry
    }

    /**
     * 도착 목록은 차량 전체 목록이 아니다. 짧은 누락은 원래 원천시각/예상시각 그대로
     * 보존하되 120초 신선도와 현재 접근·승차 가능성은 selectArrival에서 다시 검증한다.
     * API 실패·빈 목록·다른 방향/종별·오래된 feed에는 이전 관측을 정상으로 덮어쓰지 않는다.
     */
    private fun retainFreshMissingVehicles(
        plan: BoardingLookupPlan,
        latest: List<TransitArrivalDto>,
        previous: List<TransitArrivalDto>,
        now: Instant,
    ): List<TransitArrivalDto> {
        if (plan.kind != TransitLegMode.SUBWAY || plan.seoulSubwayLine == null) return emptyList()
        val related = filterCompatibleSubwayArrivals(latest.filter(TransitArrivalDto::realtime), plan, matchTrain = false)
        if (related.none { it.toCandidate(now).candidate != null }) return emptyList()
        // 같은 번호의 최신 행이 있으면 종착역/종별 충돌이나 출발 상태라도 최신 행을 우선한다.
        val latestNumbers = latest.mapNotNull { it.trainNumber }.toSet()
        return filterCompatibleSubwayArrivals(previous.filter(TransitArrivalDto::realtime), plan, matchTrain = false)
            .filter { !it.trainNumber.isNullOrBlank() && it.trainNumber !in latestNumbers }
            .filter { it.toCandidate(now).candidate != null }
            .distinctBy { it.trainNumber }
            .take(arrivalLimit)
    }

    private fun filterCompatibleSubwayArrivals(
        arrivals: List<TransitArrivalDto>,
        plan: BoardingLookupPlan,
        matchTrain: Boolean = true,
    ): List<TransitArrivalDto> {
        val identifiedArrivals = if (plan.seoulSubwayLine != null) {
            arrivals.filter { arrival ->
                arrival.provider == "seoul-openapi" && arrival.kind == "SUBWAY" &&
                    arrival.subwayLineId == plan.seoulSubwayLine.realtimeId &&
                    normalizeStopName(arrival.stationName) == normalizeStopName(plan.stationName)
            }
        } else arrivals
        val directionTokens = when (plan.directionCode) {
            "UP" -> setOf("상행", "내선")
            "DOWN" -> setOf("하행", "외선")
            else -> emptySet()
        }
        val byCode = identifiedArrivals.filter { arrival ->
            directionTokens.any { arrival.direction?.contains(it) == true }
        }
        val expectedDirection = normalizeDirectionName(plan.directionName)
        val directionMatched = if (directionTokens.isNotEmpty()) {
            // 명시적 방향과 충돌하는 응답을 종착역 이름 fallback으로 되살리지 않는다.
            byCode
        } else {
            if (expectedDirection.isNullOrBlank()) return emptyList()
            identifiedArrivals.filter { arrival ->
                sequenceOf(arrival.destinationName, arrival.direction)
                    .mapNotNull(::normalizeDirectionName)
                    .any { actual ->
                        actual.contains(expectedDirection) || expectedDirection.contains(actual)
                    }
            }
        }

        val expectedAlightingStation = normalizeStopName(plan.alightingStationName)
            ?: return emptyList()
        val selectedExpress = when (plan.subwayServiceClass) {
            TransitServiceClass.LOCAL -> false
            TransitServiceClass.EXPRESS -> true
            TransitServiceClass.UNKNOWN -> return emptyList()
        }
        return directionMatched.filter { arrival ->
            // Direction alone is insufficient: a short-turn train can have the same up/down label
            // while terminating before the selected alighting station. With no station topology in
            // the arrival response, exact terminal == alighting-stop is the only positive proof.
            (if (!matchTrain) true else if (plan.expectedTrainNumber != null) {
                // 이 열차가 선택 하차역까지 간다는 증거는 승하차역 시간표의 동일 열차 join이다.
                // 따라서 종착역==하차역인 차량만 허용하던 제한을 검증된 열차에 한해 해제한다.
                (arrival.trainNumber == plan.expectedTrainNumber ||
                    (plan.expectedRealtimeTrainNumber != null && arrival.trainNumber == plan.expectedRealtimeTrainNumber)) &&
                    normalizeStopName(arrival.destinationName) == normalizeStopName(plan.verifiedTerminalName)
            } else normalizeStopName(arrival.destinationName) == expectedAlightingStation) &&
                // A generic/local itinerary cannot inherit an express train's shorter runtime (or
                // vice versa). Missing provider service-type evidence is deliberately not guessed.
                arrival.express != null && arrival.express == selectedExpress
        }
    }

    private fun sourceRejectionReason(evaluations: List<ArrivalCandidateEvaluation>): String? = when {
        evaluations.isEmpty() || evaluations.any { it.candidate != null } -> null
        evaluations.any { it.rejectedUnverifiedSource } -> TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_UNVERIFIED
        evaluations.all { it.rejectedStale } -> TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE
        else -> null
    }

    private fun arrivalApiFailureReason(failure: Throwable): String {
        // 현재 요청의 예외만 사용한다. 마지막 실패 metric을 재사용하거나 예외 원문을 저장하지 않는다.
        val causes = generateSequence(failure) { it.cause }.take(16).toList()
        return when {
            causes.any { it is TransitProviderApplicationException && it.code == "ERROR-337" } ||
                causes.any { it is RestClientResponseException && it.statusCode.value() == 429 } ||
                causes.any { it is EtaProviderBulkheadRejectedException } -> TrafficFailureReasons.TRANSIT_ARRIVAL_RATE_LIMITED
            causes.any { it is SocketTimeoutException || it is HttpTimeoutException } -> TrafficFailureReasons.TRANSIT_ARRIVAL_API_TIMEOUT
            else -> TrafficFailureReasons.TRANSIT_ARRIVAL_API_ERROR
        }
    }

    private fun supportsSeoulSubwayRealtime(
        networkCityCode: String?,
        stationCityCode: String?,
        stationCityCodeNamespace: TransitCityCodeNamespace,
        alightingStationName: String?,
        serviceClass: TransitServiceClass,
    ): Boolean {
        if (alightingStationName.isNullOrBlank()) return false
        if (serviceClass == TransitServiceClass.UNKNOWN) return false
        val normalizedNetworkCode = networkCityCode?.trim()?.takeIf(String::isNotBlank)
        if (normalizedNetworkCode != null) {
            return normalizedNetworkCode == ODSAY_SEOUL_SUBWAY_CITY_CODE
        }
        return stationCityCodeNamespace == TransitCityCodeNamespace.ODSAY_CID &&
            stationCityCode?.trim() == ODSAY_SEOUL_SUBWAY_CITY_CODE
    }

    private fun normalizeStopName(value: String?): String? =
        value
            ?.replace(PARENTHESIZED_PATTERN, "")
            ?.replace(WHITESPACE_PATTERN, "")
            ?.removeSuffix("방면")
            ?.removeSuffix("행")
            ?.removeSuffix("역")
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun TransitArrivalDto.toCandidate(evaluatedAt: Instant): ArrivalCandidateEvaluation {
        // 출발한 열차의 잔존 countdown이나 이전 관측으로 승차 가능성을 되살리지 않는다.
        if (kind == "SUBWAY" && arrivalStatus == TransitArrivalStatus.DEPARTED) {
            return ArrivalCandidateEvaluation()
        }
        // A freshly received HTTP response is not proof that the upstream vehicle observation is
        // fresh. Only a parsed provider-owned timestamp can cross the actionable ETA boundary.
        if (freshnessEvidence != TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP) {
            return ArrivalCandidateEvaluation(rejectedUnverifiedSource = true)
        }
        val sourceUpdated = parseInstant(sourceUpdatedAt)
            ?: return ArrivalCandidateEvaluation(rejectedUnverifiedSource = true)
        if (sourceUpdated.isBefore(evaluatedAt.minusSeconds(arrivalSourceMaxAgeSeconds))) {
            return ArrivalCandidateEvaluation(rejectedStale = true)
        }
        if (sourceUpdated.isAfter(evaluatedAt.plusSeconds(ALLOWED_PROVIDER_CLOCK_SKEW_SECONDS))) {
            return ArrivalCandidateEvaluation(rejectedStale = true)
        }
        val expected = parseInstant(expectedAt)
            ?: waitSeconds
                ?.takeIf { it >= 0 }
                ?.let { sourceUpdated.plusSeconds(it.toLong()) }
            ?: waitMinutes
                ?.takeIf { it >= 0 }
                ?.let { sourceUpdated.plusSeconds(it.toLong() * 60) }
            ?: return ArrivalCandidateEvaluation()
        if (expected.isBefore(evaluatedAt.minusSeconds(ALLOWED_PROVIDER_CLOCK_SKEW_SECONDS))) {
            return ArrivalCandidateEvaluation(rejectedStale = true)
        }
        return ArrivalCandidateEvaluation(
            candidate = ArrivalCandidate(expected, sourceUpdated, this)
        )
    }

    private fun ArrivalCacheEntry.recordLocalRejection(
        arrivals: List<TransitArrivalDto>,
        outcome: TransitEtaProviderMetricOutcome,
    ) {
        require(
            outcome == TransitEtaProviderMetricOutcome.REJECTED_STALE ||
                outcome == TransitEtaProviderMetricOutcome.REJECTED_UNVERIFIED_SOURCE
        ) { "provider 결과 뒤 로컬 거절 outcome만 기록할 수 있습니다." }
        // The provider call/timer already supplies the logical lookup denominator. Cache hits and
        // local validation must therefore add at most one result-only numerator event.
        if (!localRejectionRecorded.compareAndSet(false, true)) return
        val metricProvider = arrivals
            .mapNotNull { arrival ->
                when (arrival.provider.trim().lowercase()) {
            "seoul-bus" -> TransitEtaProviderMetricId.SEOUL_BUS
            "seoul-openapi" -> TransitEtaProviderMetricId.SEOUL_SUBWAY
            "tago" -> TransitEtaProviderMetricId.TAGO_BUS
            else -> null
                }
            }
            .distinct()
            .singleOrNull()
            ?: return
        operationalMetrics.recordSafely {
            recordTransitEtaProviderResult(
                provider = metricProvider,
                outcome = outcome,
            )
        }
    }

    private fun stopArrivalAt(departureAt: Instant, accessMillis: Long): Instant =
        departureAt.plusMillis(accessMillis)

    private fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun normalizeDirectionName(value: String?): String? =
        value
            ?.replace(WHITESPACE_PATTERN, "")
            ?.removeSuffix("방면")
            ?.removeSuffix("행")
            ?.removeSuffix("역")
            ?.takeIf(String::isNotBlank)

    private data class BoardingLookupPlan(
        val kind: TransitLegMode,
        val accessMillis: Long,
        val travelMinutesWithoutFirstWait: Double,
        val stationName: String,
        val alightingStationName: String?,
        val lineName: String,
        val subwayServiceClass: TransitServiceClass,
        val directionName: String?,
        val directionCode: String?,
        val arsId: String?,
        val cityCode: String?,
        val cityCodeNamespace: TransitCityCodeNamespace,
        val providerCode: String?,
        val nodeId: String?,
        val subwayRealtimeMetadataSupported: Boolean,
        val seoulSubwayLine: SeoulSubwayLine?,
        val expectedTrainNumber: String?,
        val scheduledBoardingAt: Instant? = null,
        val expectedRealtimeTrainNumber: String?,
        val verifiedTerminalName: String?,
    )

    private data class ArrivalCandidate(
        val expectedAt: Instant,
        val observedAt: Instant?,
        val arrival: TransitArrivalDto,
    )

    private data class ArrivalCandidateEvaluation(
        val candidate: ArrivalCandidate? = null,
        val rejectedStale: Boolean = false,
        val rejectedUnverifiedSource: Boolean = false,
    )

    private data class ArrivalSelection(
        val candidate: ArrivalCandidate? = null,
        val failureReason: String? = null,
    )

    private data class ArrivalCacheKey(
        val kind: TransitLegMode,
        val stationName: String,
        val lineName: String,
        val directionName: String?,
        val directionCode: String?,
        val arsId: String?,
        val cityCode: String?,
        val cityCodeNamespace: TransitCityCodeNamespace,
        val providerCode: String?,
        val nodeId: String?,
        val limit: Int,
    ) {
        companion object {
            fun from(plan: BoardingLookupPlan, limit: Int) = ArrivalCacheKey(
                kind = plan.kind,
                stationName = plan.stationName,
                lineName = plan.lineName,
                directionName = plan.directionName,
                directionCode = plan.directionCode,
                arsId = plan.arsId,
                cityCode = plan.cityCode,
                cityCodeNamespace = plan.cityCodeNamespace,
                providerCode = plan.providerCode,
                nodeId = plan.nodeId,
                limit = limit,
            )
        }
    }

    private data class ArrivalCacheEntry(
        val cachedAt: Instant,
        val arrivals: List<TransitArrivalDto>,
        val failureReason: String? = null,
        val localRejectionRecorded: AtomicBoolean = AtomicBoolean(false),
    )

    private fun unavailableMetadata() = TransitRealtimeOverlayResolution(
        failureReason = TrafficFailureReasons.TRANSIT_ROUTE_METADATA_MISSING
    )

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
        const val DEFAULT_BOARDING_BUFFER_SECONDS = 60L
        const val MAX_BOARDING_BUFFER_SECONDS = 600L
        const val DEFAULT_ARRIVAL_LIMIT = 10
        const val MAX_ARRIVAL_LIMIT = 10
        const val ALLOWED_PROVIDER_CLOCK_SKEW_SECONDS = 60L
        const val DEFAULT_ARRIVAL_SOURCE_MAX_AGE_SECONDS = 120L
        const val MAX_ARRIVAL_SOURCE_MAX_AGE_SECONDS = 900L
        const val DEFAULT_ARRIVAL_CACHE_TTL_SECONDS = 15L
        const val MAX_ARRIVAL_CACHE_TTL_SECONDS = 60L
        const val DEFAULT_ARRIVAL_CACHE_MAX_ENTRIES = 512
        const val MAX_ARRIVAL_CACHE_MAX_ENTRIES = 2_048
        const val ODSAY_SEOUL_SUBWAY_CITY_CODE = "1000"
        const val OBSERVATION_LOOKAHEAD_SECONDS = 20 * 60L
        val WHITESPACE_PATTERN = Regex("""\s+""")
        val PARENTHESIZED_PATTERN = Regex("""\([^)]*\)""")
    }
}
