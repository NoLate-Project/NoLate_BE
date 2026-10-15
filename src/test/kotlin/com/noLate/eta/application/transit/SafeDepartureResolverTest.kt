package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitRideSignature
import com.noLate.eta.domain.TransitStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SafeDepartureResolverTest {
    private val matcher = TransitJourneyMatcher()
    private val selected = SelectedTransitRoute(
        provider = "odsay",
        rides = listOf(
            TransitRideSignature(
                mode = TransitLegMode.BUS,
                providerRouteId = "bus-402",
                lineName = "402",
                fromIds = setOf("PROVIDER:odsay:1001"),
                fromName = "서울역",
                toIds = setOf("PROVIDER:odsay:2001"),
                toName = "강남역",
                directionCode = "DOWN",
            )
        ),
        legacyBoardingPlan = null,
    )

    @Test
    fun `관측 열차의 마지막 승차 가능 출발을 건너뛰지 않고 공급자 해상도 안에서 다시 검증한다`() {
        // Recorded 11:20 observation: ready 11:22, train 11:23:35,
        // arrival 12:27:30, deadline 12:35. Previous search jumped to
        // 11:28 and then 11:23, skipping the still-safe 11:21 departure.
        // This is a deterministic replay, not another live-provider sample.
        val start = Instant.parse("2026-10-13T02:20:00Z")
        val arrival = Instant.parse("2026-10-13T03:27:30Z")
        val deadline = Instant.parse("2026-10-13T03:35:00Z")
        val observedLimit = start.plusSeconds(95)
        for (seconds in listOf(false, true)) {
            val searched = mutableListOf<Instant>()
            val provider = object : TransitJourneyProvider {
                override val providerId = "odsay"
                override val supportsSecondPrecisionDepartureProjection = seconds
                override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
                    searched += request.departureAt
                    return listOf(journey(request, request.departureAt, arrival))
                }
            }
            val result = SafeDepartureResolver(matcher).resolveProjectedCandidates(
                provider, request(start), deadline, start,
            ) { candidates ->
                val candidate = candidates.single()
                ProjectedTransitJourney(candidate, arrival, Unit,
                    eligible = candidate.departureAt <= observedLimit,
                    latestObservedVehicleDepartureAt = observedLimit)
            }
            val expected = start.plusSeconds(if (seconds) 95 else 60)
            assertEquals(listOf(start, expected), searched)
            assertEquals(expected, result?.journey?.departureAt)
            assertEquals(arrival, result?.predictedArrivalAt)
        }
    }

    @Test
    fun `후속 최적화 조회가 실패해도 같은 평가에서 마감 전으로 검증한 가장 늦은 후보를 보존한다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        val target = eight.plusSeconds(3600)
        for (failure in listOf(java.net.SocketTimeoutException("synthetic timeout"), IllegalStateException("synthetic 503"))) {
            val searches = mutableListOf<Instant>()
            val provider = RecordingJourneyProvider { search ->
                searches += search.departureAt
                when (searches.size) {
                    1 -> listOf(journey(search, search.departureAt, eight.plusSeconds(2700)))
                    2 -> listOf(journey(search, search.departureAt, eight.plusSeconds(3300)))
                    else -> throw failure
                }
            }
            val result = SafeDepartureResolver(matcher, toleranceSeconds = 0).resolve(
                provider, request(eight), selected, target, eight,
            )
            assertEquals(listOf(eight, eight.plusSeconds(900), eight.plusSeconds(1200)), searches)
            assertEquals(eight.plusSeconds(900), result?.departureAt)
            assertEquals(eight.plusSeconds(3300), result?.arrivalAt)
        }
    }

    @Test
    fun `마감 초과나 미검증 후보만 있으면 후속 조회 실패를 정상 후보로 덮지 않는다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        for (eligible in listOf(true, false)) {
            val failure = java.net.SocketTimeoutException("synthetic timeout")
            var calls = 0
            val provider = RecordingJourneyProvider { search ->
                if (++calls > 1) throw failure
                listOf(journey(search, search.departureAt, eight.plusSeconds(if (eligible) 3660 else 2700)))
            }
            val thrown = assertThrows(java.net.SocketTimeoutException::class.java) {
                SafeDepartureResolver(matcher, toleranceSeconds = 0).resolveProjectedCandidates(
                    provider, request(eight), eight.plusSeconds(3600), eight,
                ) { candidates -> candidates.single().let { ProjectedTransitJourney(it, it.arrivalAt, Unit, eligible = eligible) } }
            }
            assertSame(failure, thrown)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `안전 후보가 있어도 취소 인터럽트와 치명적 오류는 삼키지 않는다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        for (failure in listOf(java.util.concurrent.CancellationException("synthetic cancel"),
            InterruptedException("synthetic interrupt"), AssertionError("synthetic fatal"))) {
            var calls = 0
            val provider = RecordingJourneyProvider { search ->
                if (++calls > 1) throw failure
                listOf(journey(search, search.departureAt, eight.plusSeconds(2700)))
            }
            val thrown = assertThrows(failure.javaClass) {
                SafeDepartureResolver(matcher, toleranceSeconds = 0).resolve(
                    provider, request(eight), selected, eight.plusSeconds(3600), eight,
                )
            }
            assertSame(failure, thrown)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `초 투영은 공급자가 명시한 경우만 허용하고 현재와 미래 계획 출발을 앞당기지 않는다`() {
        val base = Instant.parse("2026-10-12T03:16:00Z")
        val evaluated = base.plusMillis(2649)
        for (secondsSupported in listOf(false, true)) {
            for (planned in listOf(base, base.plusMillis(42001))) {
                val searches = mutableListOf<Instant>()
                val provider = object : TransitJourneyProvider {
                    override val providerId = if (secondsSupported) "tmap" else "odsay"
                    override val supportsSecondPrecisionDepartureProjection = secondsSupported
                    override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
                        searches += request.departureAt
                        return listOf(journey(request, request.departureAt, request.departureAt.plusSeconds(2400)))
                    }
                }
                val result = SafeDepartureResolver(matcher, maxSearches = 1).resolveProjectedCandidates(
                    provider, request(planned), null, evaluated,
                ) { candidates -> candidates.single().let { ProjectedTransitJourney(it, it.arrivalAt, Unit) } }
                val expected = if (!secondsSupported) base.plusSeconds(60)
                    else base.plusSeconds(if (planned == base) 3 else 43)
                assertEquals(listOf(expected), searches)
                assertEquals(expected, result?.journey?.departureAt)
            }
        }
    }

    @Test
    fun `ODsay 전체 탐색 예산은 세 회를 넘길 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeDepartureResolver(matcher, maxSearches = 4, toleranceSeconds = 0)
        }
    }

    @Test
    fun `시간표 불연속으로 단순 역산 후보가 늦으면 조회한 후보 중 가장 늦게 출발하는 feasible 여정을 고른다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        val eightTen = eight.plus(10, ChronoUnit.MINUTES)
        val eightFifteen = eight.plus(15, ChronoUnit.MINUTES)
        val targetArrivalAt = Instant.parse("2026-07-29T09:00:00Z")
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { request ->
            searchedDepartures += request.departureAt
            when (request.departureAt) {
                eight -> listOf(journey(request, eight, Instant.parse("2026-07-29T08:45:00Z")))
                eightFifteen -> listOf(
                    journey(request, eightFifteen, Instant.parse("2026-07-29T09:05:00Z"))
                )
                eightTen -> listOf(
                    journey(request, eightTen, Instant.parse("2026-07-29T08:55:00Z"))
                )
                else -> emptyList()
            }
        }
        val resolver = SafeDepartureResolver(
            matcher = matcher,
            maxSearches = 3,
            toleranceSeconds = 0,
        )

        val result = resolver.resolve(
            provider = provider,
            request = request(eight),
            selected = selected,
            targetArrivalAt = targetArrivalAt,
            evaluatedAt = eight.minus(30, ChronoUnit.MINUTES),
        )

        assertEquals(listOf(eight, eightFifteen, eightTen), searchedDepartures)
        assertEquals(eightTen, result?.departureAt)
        assertEquals(Instant.parse("2026-07-29T08:55:00Z"), result?.arrivalAt)
    }

    @Test
    fun `실시간 projection의 절대 도착시각으로 검색점을 옮겨 마감 전 후보만 반환한다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        val targetArrivalAt = Instant.parse("2026-07-29T09:00:00Z")
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { request ->
            searchedDepartures += request.departureAt
            listOf(
                journey(
                    request = request,
                    departureAt = request.departureAt,
                    arrivalAt = request.departureAt.plus(40, ChronoUnit.MINUTES),
                )
            )
        }
        val resolver = SafeDepartureResolver(
            matcher = matcher,
            maxSearches = 3,
            toleranceSeconds = 0,
        )

        val result = resolver.resolveProjected(
            provider = provider,
            request = request(eight),
            selected = selected,
            targetArrivalAt = targetArrivalAt,
            evaluatedAt = eight,
        ) { candidate ->
            val predictedArrivalAt = when (candidate.departureAt) {
                eight -> Instant.parse("2026-07-29T08:50:00Z")
                eight.plus(10, ChronoUnit.MINUTES) -> Instant.parse("2026-07-29T09:05:00Z")
                eight.plus(5, ChronoUnit.MINUTES) -> Instant.parse("2026-07-29T08:55:00Z")
                else -> error("unexpected search")
            }
            ProjectedTransitJourney(candidate, predictedArrivalAt, predictedArrivalAt)
        }

        assertEquals(
            listOf(
                eight,
                eight.plus(10, ChronoUnit.MINUTES),
                eight.plus(5, ChronoUnit.MINUTES),
            ),
            searchedDepartures,
        )
        assertEquals(eight.plus(5, ChronoUnit.MINUTES), result?.journey?.departureAt)
        assertEquals(Instant.parse("2026-07-29T08:55:00Z"), result?.predictedArrivalAt)
    }

    @Test
    fun `음수 probe가 평가 시각에 막혀도 양방향 슬롯으로 세 번 예산을 소진하며 과거로 내려가지 않는다`() {
        val evaluatedAt = Instant.parse("2026-07-29T08:00:30Z")
        val firstValidMinute = Instant.parse("2026-07-29T08:01:00Z")
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { search ->
            searchedDepartures += search.departureAt
            listOf(
                journey(
                    request = search,
                    departureAt = search.departureAt,
                    arrivalAt = search.departureAt.plus(40, ChronoUnit.MINUTES),
                )
            )
        }
        val resolver = SafeDepartureResolver(matcher, maxSearches = 3, toleranceSeconds = 0)

        resolver.resolveProjected(
            provider = provider,
            request = request(Instant.parse("2026-07-29T08:00:05Z")),
            selected = selected,
            targetArrivalAt = Instant.parse("2026-07-29T08:30:00Z"),
            evaluatedAt = evaluatedAt,
        ) { candidate ->
            ProjectedTransitJourney(
                journey = candidate,
                predictedArrivalAt = candidate.arrivalAt,
                projection = Unit,
                eligible = false,
                searchAdjustmentSeconds = -90,
            )
        }

        assertEquals(
            listOf(
                firstValidMinute,
                firstValidMinute.plus(5, ChronoUnit.MINUTES),
                firstValidMinute.plus(3, ChronoUnit.MINUTES),
            ),
            searchedDepartures,
        )
    }

    @Test
    fun `현재 시각 첫 UNKNOWN이 음수 경계에 막히면 5분 뒤 동일 signature 안전 차량을 찾는다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        val eightFive = eight.plus(5, ChronoUnit.MINUTES)
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { search ->
            searchedDepartures += search.departureAt
            listOf(
                journey(
                    request = search,
                    departureAt = search.departureAt,
                    arrivalAt = search.departureAt.plus(40, ChronoUnit.MINUTES),
                )
            )
        }
        val resolver = SafeDepartureResolver(matcher, maxSearches = 3, toleranceSeconds = 0)

        val result = resolver.resolveProjectedCandidates(
            provider = provider,
            request = request(eight),
            targetArrivalAt = null,
            evaluatedAt = eight,
        ) { candidates ->
            val candidate = candidates.single()
            ProjectedTransitJourney(
                journey = candidate,
                predictedArrivalAt = candidate.arrivalAt,
                projection = Unit,
                eligible = candidate.departureAt == eightFive,
                searchAdjustmentSeconds = -60,
            )
        }

        assertEquals(listOf(eight, eightFive), searchedDepartures)
        assertEquals(eightFive, result?.journey?.departureAt)
    }

    @Test
    fun `도착 마감이 없어도 중간 빈 응답 뒤 남은 예산으로 다음 안전 차량을 찾는다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        val eightFive = eight.plus(5, ChronoUnit.MINUTES)
        val eightTen = eight.plus(10, ChronoUnit.MINUTES)
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { search ->
            searchedDepartures += search.departureAt
            when (search.departureAt) {
                eightFive -> emptyList()
                else -> listOf(
                    journey(
                        request = search,
                        departureAt = search.departureAt,
                        arrivalAt = search.departureAt.plus(40, ChronoUnit.MINUTES),
                    )
                )
            }
        }
        val resolver = SafeDepartureResolver(matcher, maxSearches = 3, toleranceSeconds = 0)

        val result = resolver.resolveProjectedCandidates(
            provider = provider,
            request = request(eight),
            targetArrivalAt = null,
            evaluatedAt = eight,
        ) { candidates ->
            candidates.singleOrNull()?.let { candidate ->
                ProjectedTransitJourney(
                    journey = candidate,
                    predictedArrivalAt = candidate.arrivalAt,
                    projection = Unit,
                    eligible = candidate.departureAt == eightTen,
                    searchAdjustmentSeconds = -60,
                )
            }
        }

        assertEquals(listOf(eight, eightFive, eightTen), searchedDepartures)
        assertEquals(eightTen, result?.journey?.departureAt)
    }

    @Test
    fun `첫 후보가 허용 오차 안이지만 늦으면 이전 분을 재조회해 정시 후보를 찾는다`() {
        val eight = Instant.parse("2026-07-29T08:00:00Z")
        val sevenFiftyNine = eight.minus(1, ChronoUnit.MINUTES)
        val targetArrivalAt = Instant.parse("2026-07-29T09:00:00Z")
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { search ->
            searchedDepartures += search.departureAt
            val arrivalAt = when (search.departureAt) {
                eight -> targetArrivalAt.plusSeconds(30)
                sevenFiftyNine -> targetArrivalAt.minusSeconds(30)
                else -> error("unexpected search ${search.departureAt}")
            }
            listOf(journey(search, search.departureAt, arrivalAt))
        }
        val resolver = SafeDepartureResolver(matcher, maxSearches = 3, toleranceSeconds = 60)

        val result = resolver.resolveProjected(
            provider = provider,
            request = request(eight),
            selected = selected,
            targetArrivalAt = targetArrivalAt,
            evaluatedAt = eight.minus(10, ChronoUnit.MINUTES),
        ) { candidate ->
            ProjectedTransitJourney(candidate, candidate.arrivalAt, Unit)
        }

        assertEquals(listOf(eight, sevenFiftyNine), searchedDepartures)
        assertEquals(sevenFiftyNine, result?.journey?.departureAt)
        assertEquals(targetArrivalAt.minusSeconds(30), result?.predictedArrivalAt)
    }

    @Test
    fun `희소 시간표에서 선택 경로가 비면 쿼리 한도 안에서 5분 이전 슬롯을 탐색한다`() {
        val eightTen = Instant.parse("2026-07-29T08:10:00Z")
        val eightFive = eightTen.minus(5, ChronoUnit.MINUTES)
        val targetArrivalAt = Instant.parse("2026-07-29T09:00:00Z")
        val searchedDepartures = mutableListOf<Instant>()
        val provider = RecordingJourneyProvider { search ->
            searchedDepartures += search.departureAt
            if (search.departureAt == eightFive) {
                listOf(
                    journey(
                        request = search,
                        departureAt = eightFive,
                        arrivalAt = targetArrivalAt.minus(5, ChronoUnit.MINUTES),
                    )
                )
            } else {
                emptyList()
            }
        }
        val resolver = SafeDepartureResolver(matcher, maxSearches = 3, toleranceSeconds = 60)

        val result = resolver.resolve(
            provider = provider,
            request = request(eightTen),
            selected = selected,
            targetArrivalAt = targetArrivalAt,
            evaluatedAt = eightTen.minus(20, ChronoUnit.MINUTES),
        )

        assertEquals(listOf(eightTen, eightFive), searchedDepartures)
        assertEquals(eightFive, result?.departureAt)
    }

    private fun request(departureAt: Instant) = TransitJourneySearchRequest(
        originLat = 37.5547,
        originLng = 126.9706,
        destinationLat = 37.4979,
        destinationLng = 127.0276,
        departureAt = departureAt,
        maxTravelMinutes = 1_440,
    )

    private fun journey(
        request: TransitJourneySearchRequest,
        departureAt: Instant,
        arrivalAt: Instant,
    ) = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = request.departureAt,
        departureAt = departureAt,
        arrivalAt = arrivalAt,
        totalMinutes = Math.toIntExact(
            ChronoUnit.MINUTES.between(departureAt, arrivalAt)
        ),
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.BUS,
                durationMinutes = Math.toIntExact(
                    ChronoUnit.MINUTES.between(departureAt, arrivalAt)
                ),
                waitingMinutes = 5,
                from = TransitStop(providerStopId = "1001", name = "서울역"),
                to = TransitStop(providerStopId = "2001", name = "강남역"),
                line = TransitLine(providerRouteId = "bus-402", name = "402"),
                directionCode = "DOWN",
            )
        ),
        fetchedAt = request.departureAt,
    )

    private class RecordingJourneyProvider(
        private val response: (TransitJourneySearchRequest) -> List<TransitJourney>,
    ) : TransitJourneyProvider {
        override val providerId: String = "odsay"

        override fun search(request: TransitJourneySearchRequest): List<TransitJourney> =
            response(request)
    }
}
