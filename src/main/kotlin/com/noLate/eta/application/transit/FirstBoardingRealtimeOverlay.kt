package com.noLate.eta.application.transit

import com.noLate.eta.domain.LegacyTransitBoardingPlan
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.global.observability.TransitEtaProviderMetricId
import com.noLate.global.observability.TransitEtaProviderMetricOutcome
import com.noLate.global.observability.recordSafely
import com.noLate.schedule.application.EtaTravelTimePolicy
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitCityCodeNamespace
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class TransitRealtimeOverlay(
    val travelMinutes: Int,
    val predictedArrivalAt: Instant,
    val observedAt: Instant,
    /** 실시간 도착 후보가 첫 승차 정류장에 도착하는 절대 시각. */
    val boardingAt: Instant,
)

data class TransitRealtimeOverlayResolution(
    val overlay: TransitRealtimeOverlay? = null,
    val failureReason: String? = null,
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
        val firstStop = firstRide.from ?: return unavailableMetadata()
        val firstLine = firstRide.line ?: return unavailableMetadata()
        val scheduledWaitMinutes = firstRide.waitingMinutes ?: return unavailableMetadata()
        val accessMinutes = journey.legs
            .take(firstRideIndex)
            .sumOf(TransitJourneyLeg::durationMinutes)
            .toDouble()
        val plan = BoardingLookupPlan(
            kind = firstRide.mode,
            accessMinutes = accessMinutes,
            travelMinutesWithoutFirstWait = journey.totalMinutes - scheduledWaitMinutes.toDouble(),
            stationName = firstStop.name ?: return unavailableMetadata(),
            alightingStationName = firstRide.to?.name,
            lineName = firstLine.name ?: return unavailableMetadata(),
            subwayServiceClass = firstLine.serviceClass,
            directionName = firstRide.directionName,
            directionCode = firstRide.directionCode,
            arsId = firstStop.arsId,
            cityCode = firstStop.cityCode,
            cityCodeNamespace = firstStop.cityCodeNamespace,
            providerCode = firstStop.providerCode,
            nodeId = firstStop.localStopId,
            subwayRealtimeMetadataSupported = firstRide.mode != TransitLegMode.SUBWAY ||
                supportsSeoulSubwayRealtime(
                    networkCityCode = firstLine.cityCode,
                    stationCityCode = firstStop.cityCode,
                    stationCityCodeNamespace = firstStop.cityCodeNamespace,
                    alightingStationName = firstRide.to?.name,
                    serviceClass = firstLine.serviceClass,
                ),
        )
        val baseDepartureAt = maxOf(evaluatedAt, journey.departureAt)
        val selected = selectArrival(plan, baseDepartureAt, evaluatedAt)
        if (selected.candidate == null) return TransitRealtimeOverlayResolution(failureReason = selected.failureReason)
        val overlay = overlay(
            plan = plan,
            baseDepartureAt = baseDepartureAt,
            scheduledArrivalAt = journey.arrivalAt,
            scheduledWaitMinutes = scheduledWaitMinutes.toDouble(),
            selected = selected.candidate,
            maxTravelMinutes = maxTravelMinutes,
        )
        return if (overlay == null) {
            TransitRealtimeOverlayResolution(failureReason = TrafficFailureReasons.PROVIDER_INVALID_RESPONSE)
        } else {
            TransitRealtimeOverlayResolution(overlay = overlay)
        }
    }

    fun resolveLegacy(
        plan: LegacyTransitBoardingPlan,
        plannedDepartureAt: Instant,
        evaluatedAt: Instant,
        maxTravelMinutes: Int,
    ): TransitRealtimeOverlayResolution {
        val lookup = BoardingLookupPlan(
            kind = plan.kind,
            accessMinutes = plan.accessMinutes,
            travelMinutesWithoutFirstWait = plan.travelMinutesWithoutFirstWait,
            stationName = plan.stationName,
            // Legacy snapshots do not preserve the alighting station or subway network code.
            // A direction label alone cannot prove that a short-turn/express train serves the
            // selected stop, so legacy subway realtime overlay stays fail-closed.
            alightingStationName = null,
            lineName = plan.lineName,
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
        val stopArrivalAt = stopArrivalAt(baseDepartureAt, lookup.accessMinutes)
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
            )
        )
    }

    private fun overlay(
        plan: BoardingLookupPlan,
        baseDepartureAt: Instant,
        scheduledArrivalAt: Instant,
        scheduledWaitMinutes: Double,
        selected: ArrivalCandidate,
        maxTravelMinutes: Int,
    ): TransitRealtimeOverlay? {
        val stopArrivalAt = stopArrivalAt(baseDepartureAt, plan.accessMinutes)
        val liveWaitMinutes = Duration.between(stopArrivalAt, selected.expectedAt)
            .toMillis()
            .coerceAtLeast(0)
            .toDouble() / MILLIS_PER_MINUTE
        val travelMinutes = EtaTravelTimePolicy.normalizeMinutes(
            plan.travelMinutesWithoutFirstWait + liveWaitMinutes,
            maxTravelMinutes,
        ) ?: return null
        val waitDeltaSeconds = ((liveWaitMinutes - scheduledWaitMinutes) * 60).toLong()
        return TransitRealtimeOverlay(
            travelMinutes = travelMinutes,
            predictedArrivalAt = scheduledArrivalAt.plusSeconds(waitDeltaSeconds),
            observedAt = selected.observedAt ?: Instant.now(clock),
            boardingAt = selected.expectedAt,
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
            return ArrivalSelection(
                failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
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
                failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE
            )
        }
        val catchableAt = stopArrivalAt(departureAt, plan.accessMinutes)
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
        synchronized(arrivalCache) {
            val cached = arrivalCache[key]
            if (cached != null && !now.isAfter(cached.cachedAt.plusSeconds(arrivalCacheTtlSeconds))) {
                return cached
            }
            if (cached != null) arrivalCache.remove(key)
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
                TransitLegMode.SUBWAY -> transitArrivalService.getSubwayArrivals(
                    stationName = plan.stationName,
                    lineName = plan.lineName,
                    directionName = plan.directionName,
                    directionCode = plan.directionCode,
                    limit = arrivalLimit,
                )
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).toList()

        val entry = ArrivalCacheEntry(cachedAt = now, arrivals = fetched)
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

    private fun filterCompatibleSubwayArrivals(
        arrivals: List<TransitArrivalDto>,
        plan: BoardingLookupPlan,
    ): List<TransitArrivalDto> {
        val directionTokens = when (plan.directionCode) {
            "UP" -> setOf("상행", "내선")
            "DOWN" -> setOf("하행", "외선")
            else -> emptySet()
        }
        val byCode = arrivals.filter { arrival ->
            directionTokens.any { arrival.direction?.contains(it) == true }
        }
        val expectedDirection = normalizeDirectionName(plan.directionName)
        val directionMatched = if (byCode.isNotEmpty()) {
            byCode
        } else {
            if (expectedDirection.isNullOrBlank()) return emptyList()
            arrivals.filter { arrival ->
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
            normalizeStopName(arrival.destinationName) == expectedAlightingStation &&
                // A generic/local itinerary cannot inherit an express train's shorter runtime (or
                // vice versa). Missing provider service-type evidence is deliberately not guessed.
                arrival.express != null && arrival.express == selectedExpress
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
        // A freshly received HTTP response is not proof that the upstream vehicle observation is
        // fresh. Only a parsed provider-owned timestamp can cross the actionable ETA boundary.
        if (freshnessEvidence != TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP) {
            return ArrivalCandidateEvaluation(rejectedUnverifiedSource = true)
        }
        val sourceUpdated = parseInstant(sourceUpdatedAt) ?: return ArrivalCandidateEvaluation()
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
            candidate = ArrivalCandidate(expected, sourceUpdated)
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

    private fun stopArrivalAt(departureAt: Instant, accessMinutes: Double): Instant =
        departureAt.plusMillis((accessMinutes * MILLIS_PER_MINUTE).toLong())

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
        val accessMinutes: Double,
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
    )

    private data class ArrivalCandidate(
        val expectedAt: Instant,
        val observedAt: Instant?,
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
        val WHITESPACE_PATTERN = Regex("""\s+""")
        val PARENTHESIZED_PATTERN = Regex("""\([^)]*\)""")
    }
}
