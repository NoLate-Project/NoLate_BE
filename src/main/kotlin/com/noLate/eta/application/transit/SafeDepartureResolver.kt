package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneySearchRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * depart-at만 지원하는 공급자에서 도착 마감 전 가능한 가장 늦은 선택 경로를 제한된 횟수로 찾는다.
 *
 * 대중교통 함수는 차량을 놓치는 지점에서 불연속이므로 한 번의 `마감 - ETA` 역산을 사용하지
 * 않는다. 비수렴 시에는 조회한 후보 중 실제로 마감 전에 도착한 가장 늦은 여정만 채택한다.
 */
@Component
class SafeDepartureResolver(
    private val matcher: TransitJourneyMatcher,
    @Value("\${eta.transit.safe-departure.max-searches:3}")
    private val maxSearches: Int = DEFAULT_MAX_SEARCHES,
    @Value("\${eta.transit.safe-departure.tolerance-seconds:60}")
    private val toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
) {
    init {
        require(maxSearches in 1..MAX_SEARCH_LIMIT) {
            "안전 출발시각 공급자 조회 횟수는 1~$MAX_SEARCH_LIMIT 사이여야 합니다."
        }
        require(toleranceSeconds in 0..MAX_TOLERANCE_SECONDS) {
            "안전 출발시각 수렴 허용치는 0~${MAX_TOLERANCE_SECONDS}초 사이여야 합니다."
        }
    }

    fun resolve(
        provider: TransitJourneyProvider,
        request: TransitJourneySearchRequest,
        selected: SelectedTransitRoute,
        targetArrivalAt: Instant?,
        evaluatedAt: Instant,
    ): TransitJourney? = resolveProjected(
        provider = provider,
        request = request,
        selected = selected,
        targetArrivalAt = targetArrivalAt,
        evaluatedAt = evaluatedAt,
    ) { journey ->
        ProjectedTransitJourney(
            journey = journey,
            predictedArrivalAt = journey.arrivalAt,
            projection = Unit,
        )
    }?.journey

    /**
     * 시간표 도착시각 대신 실시간 보정 도착시각을 사용해 같은 제한 횟수 안에서 출발시각을 찾는다.
     *
     * 첫 차량이 늦어진 경우 단순히 `도착 마감 - 보정 ETA`를 반환하면 같은 차량의 절대
     * 도착시각은 그대로인데 출발만 앞당기는 모순이 생긴다. 호출자가 제공한 projection의
     * 절대 도착시각으로 다음 검색점을 이동해, 더 이른 동일 경로 차량이 실제로 있는 경우만
     * 마감 전 여정으로 채택한다.
     */
    fun <T> resolveProjected(
        provider: TransitJourneyProvider,
        request: TransitJourneySearchRequest,
        selected: SelectedTransitRoute,
        targetArrivalAt: Instant?,
        evaluatedAt: Instant,
        project: (TransitJourney) -> ProjectedTransitJourney<T>,
    ): ProjectedTransitJourney<T>? = resolveProjectedCandidates(
        provider = provider,
        request = request,
        targetArrivalAt = targetArrivalAt,
        evaluatedAt = evaluatedAt,
    ) { candidates ->
        matcher.findSelected(selected, candidates)?.let(project)
    }

    /**
     * 한 공급자 응답 안에서 선택 여정과 안전한 대체 여정을 함께 평가할 수 있는 변형이다.
     * selector가 반환한 후보만 검색 수렴에 사용하므로 공급자 밖의 임의 경로는 섞이지 않는다.
     */
    fun <T> resolveProjectedCandidates(
        provider: TransitJourneyProvider,
        request: TransitJourneySearchRequest,
        targetArrivalAt: Instant?,
        evaluatedAt: Instant,
        select: (List<TransitJourney>) -> ProjectedTransitJourney<T>?,
    ): ProjectedTransitJourney<T>? {
        val minimumSearchAt = evaluatedAt.ceilToMinute()
        var searchAt = request.departureAt
            .ceilToMinute()
            .coerceAtLeast(minimumSearchAt)
        val latestAllowedSearch = targetArrivalAt
            ?.minusSeconds(MINIMUM_JOURNEY_SECONDS)
            ?.truncatedTo(ChronoUnit.MINUTES)
            ?.coerceAtLeast(minimumSearchAt)
        if (latestAllowedSearch != null) {
            searchAt = searchAt.coerceAtMost(latestAllowedSearch)
        }

        val feasible = mutableListOf<ProjectedTransitJourney<T>>()
        val visited = mutableSetOf<Instant>()
        repeat(maxSearches) search@{
            if (!visited.add(searchAt)) return feasible.latestFeasible()
            val candidates = provider.search(request.copy(departureAt = searchAt))
            val projected = select(candidates)
            if (projected == null) {
                if (targetArrivalAt == null || feasible.isNotEmpty()) {
                    return feasible.latestFeasible()
                }
                // Sparse timetables can temporarily push the selected itinerary out of the
                // provider's bounded result set. Spend only the already configured query budget
                // and probe one deterministic earlier slot before falling back.
                val sparseProbe = searchAt
                    .minus(SPARSE_TIMETABLE_PROBE_MINUTES, ChronoUnit.MINUTES)
                    .truncatedTo(ChronoUnit.MINUTES)
                    .coerceAtLeast(minimumSearchAt)
                if (sparseProbe == searchAt || sparseProbe in visited) {
                    return feasible.latestFeasible()
                }
                searchAt = sparseProbe
                return@search
            }
            if (targetArrivalAt == null) return projected

            val arrivalDeltaSeconds = Duration.between(
                projected.predictedArrivalAt,
                targetArrivalAt,
            ).seconds
            val onTime = projected.eligible && !projected.predictedArrivalAt.isAfter(targetArrivalAt)
            if (onTime) {
                feasible += projected
            }
            // Tolerance is a convergence criterion only for an actually on-time candidate. A
            // candidate that is 1~60 seconds late still needs an earlier probe; otherwise the
            // first query is incorrectly promoted to an "on-time impossible" conclusion.
            if (onTime && abs(arrivalDeltaSeconds) <= toleranceSeconds) {
                return feasible.latestFeasible()
            }

            val adjustmentSeconds = projected.searchAdjustmentSeconds ?: arrivalDeltaSeconds
            var nextSearchAt = searchAt.adjustForProviderMinute(adjustmentSeconds)
                .coerceAtLeast(minimumSearchAt)
            if (latestAllowedSearch != null) {
                nextSearchAt = nextSearchAt.coerceAtMost(latestAllowedSearch)
            }
            if (nextSearchAt == searchAt) return feasible.latestFeasible()
            searchAt = nextSearchAt
        }
        return feasible.latestFeasible()
    }

    private fun <T> List<ProjectedTransitJourney<T>>.latestFeasible(): ProjectedTransitJourney<T>? =
        maxWithOrNull(
            compareBy<ProjectedTransitJourney<T>> { it.journey.departureAt }
                .thenByDescending { it.predictedArrivalAt }
        )

    private fun Instant.coerceAtLeast(minimum: Instant): Instant =
        if (isBefore(minimum)) minimum else this

    private fun Instant.coerceAtMost(maximum: Instant): Instant =
        if (isAfter(maximum)) maximum else this

    private fun Instant.ceilToMinute(): Instant {
        val floor = truncatedTo(ChronoUnit.MINUTES)
        return if (this == floor) floor else floor.plus(1, ChronoUnit.MINUTES)
    }

    /**
     * Positive probes round up so they never precede the requested instant. Negative probes round
     * down so a sub-minute late delta still moves to an earlier ODsay SearchTime minute.
     */
    private fun Instant.adjustForProviderMinute(adjustmentSeconds: Long): Instant {
        if (adjustmentSeconds == 0L) return this
        val adjusted = plusSeconds(adjustmentSeconds)
        return if (adjustmentSeconds < 0L) {
            adjusted.truncatedTo(ChronoUnit.MINUTES)
        } else {
            adjusted.ceilToMinute()
        }
    }

    private companion object {
        const val DEFAULT_MAX_SEARCHES = 3
        const val MAX_SEARCH_LIMIT = 6
        const val DEFAULT_TOLERANCE_SECONDS = 60L
        const val MAX_TOLERANCE_SECONDS = 300L
        const val MINIMUM_JOURNEY_SECONDS = 60L
        const val SPARSE_TIMETABLE_PROBE_MINUTES = 5L
    }
}

data class ProjectedTransitJourney<T>(
    val journey: TransitJourney,
    val predictedArrivalAt: Instant,
    val projection: T,
    /** 환승 실패처럼 도착시각만으로 정상 후보가 될 수 없는 경우 false다. */
    val eligible: Boolean = true,
    /** 불연속 환승 실패에서 다음 검색시각을 앞당길 공급자 검색 보정값이다. */
    val searchAdjustmentSeconds: Long? = null,
) {
    init {
        require(!predictedArrivalAt.isBefore(journey.departureAt)) {
            "예측 도착시각은 여정 출발시각보다 이를 수 없습니다."
        }
    }
}
