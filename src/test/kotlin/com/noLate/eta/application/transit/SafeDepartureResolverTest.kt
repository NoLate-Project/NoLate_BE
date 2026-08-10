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
                fromIds = setOf("ODsay:1001"),
                fromName = "서울역",
                toIds = setOf("ODsay:2001"),
                toName = "강남역",
                directionCode = "DOWN",
            )
        ),
        legacyBoardingPlan = null,
    )

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
