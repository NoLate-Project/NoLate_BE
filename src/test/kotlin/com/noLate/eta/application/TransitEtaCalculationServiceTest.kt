package com.noLate.eta.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class TransitEtaCalculationServiceTest {
    private val evaluatedAt = Instant.parse("2026-07-29T00:00:00Z")
    private val clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC)
    private val arrivalService = mock<TransitArrivalService>()

    @Test
    fun `저장 ODsay 동일 경로를 재조회해 새 총43분의 대기7분을 실시간12분으로 교체하면 48분이다`() {
        whenever(busArrivals()).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(17, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(
                journey(
                    routeId = "bus-421",
                    lineName = "421",
                    totalMinutes = 35,
                    scheduledWaitMinutes = 3,
                ),
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 43,
                    scheduledWaitMinutes = 7,
                ),
            ),
        )

        val result = service(listOf(odsay)).calculate(request())

        assertEquals(1, odsay.searchCount)
        assertEquals(48, result.travelMinutes)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(evaluatedAt.plus(48, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertEquals(evaluatedAt, result.fetchedAt)
        assertFalse(result.stale)
        assertNull(result.failureReason)
    }

    @Test
    fun `대기 제외 이동40분과 실시간 첫 버스 대기20분은 총 ETA 60분으로 한 번만 합산한다`() {
        whenever(busArrivals()).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    // helper journey의 정류장 접근 5분 뒤 20분을 더 기다리는 차량이다.
                    expectedAt = evaluatedAt.plus(25, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    // ODsay의 45분에는 기존 시간표 대기 5분이 이미 포함돼 있다.
                    totalMinutes = 45,
                    scheduledWaitMinutes = 5,
                )
            ),
        )

        val result = service(listOf(odsay)).calculate(request())

        assertEquals(60, result.travelMinutes)
        assertEquals(evaluatedAt.plus(60, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertFalse(result.stale)
        assertNull(result.failureReason)
    }

    @Test
    fun `ODsay 경로가 성공해도 첫 승차 도착정보 실패는 degraded TIMETABLE로 보존한다`() {
        whenever(busArrivals()).thenReturn(emptyList())
        val fetchedAt = evaluatedAt.plusSeconds(3)
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 43,
                    scheduledWaitMinutes = 7,
                    fetchedAt = fetchedAt,
                )
            ),
        )

        val result = service(listOf(odsay)).calculate(request())

        assertEquals(43, result.travelMinutes)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, result.source)
        assertEquals(fetchedAt, result.fetchedAt)
        assertEquals(evaluatedAt.plus(43, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertTrue(result.stale)
        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, result.failureReason)
    }

    @Test
    fun `첫 승차 도착정보가 없으면 늦은 시간표도 실시간 도착 불가로 단정하지 않는다`() {
        whenever(busArrivals()).thenReturn(emptyList())
        val targetArrivalAt = evaluatedAt.plus(40, ChronoUnit.MINUTES)
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 43,
                    scheduledWaitMinutes = 7,
                )
            ),
        )

        val result = service(listOf(odsay)).calculate(
            request(targetArrivalAt = targetArrivalAt)
        )

        assertEquals(TrafficSource.TIMETABLE_PROVIDER, result.source)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, result.failureReason)
        assertFalse(result.accepted)
        assertEquals(evaluatedAt.plus(43, ChronoUnit.MINUTES), result.predictedArrivalAt)
    }

    @Test
    fun `ODsay 재조회가 실패해도 TMAP 후보로 전환하지 않고 저장 선택 경로로 fallback한다`() {
        whenever(busArrivals()).thenReturn(emptyList())
        val odsay = RecordingProvider(
            providerId = "odsay",
            failure = IllegalStateException("ODsay unavailable"),
        )
        val tmap = RecordingProvider(
            providerId = "tmap",
            result = listOf(
                journey(
                    provider = "tmap",
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 35,
                    scheduledWaitMinutes = 3,
                )
            ),
        )

        val result = service(listOf(odsay, tmap)).calculate(request())

        assertEquals(1, odsay.searchCount)
        assertEquals(0, tmap.searchCount)
        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE,
            result.failureReason,
        )
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `ODsay 재조회 실패 뒤 첫 차량 정보가 있어도 검증 안 된 legacy 환승을 actionable로 만들지 않는다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES)))
        )
        val odsay = RecordingProvider(
            providerId = "odsay",
            failure = IllegalStateException("ODsay unavailable"),
        )

        val result = service(listOf(odsay)).calculate(request())

        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertEquals(40, result.travelMinutes)
        assertEquals(
            TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE,
            result.failureReason,
        )
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        verify(arrivalService, never()).getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `실시간 차량이 늦으면 더 이른 동일 경로를 다시 조회해 마감 전 도착하는 차량만 LIVE로 채택한다`() {
        val targetArrivalAt = evaluatedAt.plus(1, ChronoUnit.HOURS)
        whenever(busArrivals()).thenReturn(
            listOf(
                busArrival(expectedAt = evaluatedAt.plus(25, ChronoUnit.MINUTES)),
                busArrival(expectedAt = evaluatedAt.plus(34, ChronoUnit.MINUTES)),
            )
        )
        val searchedAt = mutableListOf<Instant>()
        val odsay = FunctionalProvider { search ->
            searchedAt += search.departureAt
            listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 43,
                    scheduledWaitMinutes = 7,
                    departureAt = search.departureAt,
                )
            )
        }

        val result = service(listOf(odsay)).calculate(
            request(
                plannedDepartureAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
                targetArrivalAt = targetArrivalAt,
            )
        )

        assertEquals(
            listOf(
                evaluatedAt.plus(20, ChronoUnit.MINUTES),
                evaluatedAt.plus(15, ChronoUnit.MINUTES),
                evaluatedAt.plus(19, ChronoUnit.MINUTES),
            ),
            searchedAt,
        )
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertTrue(result.accepted)
        assertFalse(result.stale)
        assertNull(result.failureReason)
        assertEquals(evaluatedAt.plus(19, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(evaluatedAt.plus(56, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertFalse(requireNotNull(result.predictedArrivalAt).isAfter(targetArrivalAt))
        assertEquals(37, result.travelMinutes)
    }

    @Test
    fun `조회 한도 안에 마감 전 동일 경로 차량이 없으면 정상 LIVE가 아닌 명시적 도착 불가 결과를 반환한다`() {
        val targetArrivalAt = evaluatedAt.plus(1, ChronoUnit.HOURS)
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(34, ChronoUnit.MINUTES)))
        )
        val searchedAt = mutableListOf<Instant>()
        val odsay = FunctionalProvider { search ->
            searchedAt += search.departureAt
            listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 43,
                    scheduledWaitMinutes = 7,
                    departureAt = search.departureAt,
                )
            )
        }

        val result = service(listOf(odsay)).calculate(
            request(
                plannedDepartureAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
                targetArrivalAt = targetArrivalAt,
            )
        )

        assertEquals(
            listOf(
                evaluatedAt.plus(20, ChronoUnit.MINUTES),
                evaluatedAt.plus(15, ChronoUnit.MINUTES),
                evaluatedAt.plus(10, ChronoUnit.MINUTES),
            ),
            searchedAt,
        )
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            result.failureReason,
        )
        assertEquals(evaluatedAt, result.recommendedDepartureAt)
        assertEquals(evaluatedAt.plus(65, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertTrue(requireNotNull(result.predictedArrivalAt).isAfter(targetArrivalAt))
        assertEquals(65, result.travelMinutes)
    }

    @Test
    fun `비 ODsay 단일 ride legacy 첫 승차 보정도 마감 이후 도착하면 degraded 결과로 내린다`() {
        val targetArrivalAt = evaluatedAt.plus(1, ChronoUnit.HOURS)
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(34, ChronoUnit.MINUTES)))
        )

        val result = service(emptyList()).calculate(
            request(
                plannedDepartureAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
                targetArrivalAt = targetArrivalAt,
                selectedRouteJson = selectedOdsayRouteJson().replace("\"odsay\"", "\"legacy\""),
            )
        )

        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE,
            result.failureReason,
        )
        assertEquals(evaluatedAt, result.recommendedDepartureAt)
        assertEquals(evaluatedAt.plus(64, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertEquals(64, result.travelMinutes)
    }

    @Test
    fun `비 ODsay 단일 ride legacy는 첫 승차 live wait만 안전하게 보정한다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES)))
        )

        val result = service(emptyList()).calculate(
            request(
                selectedRouteJson = selectedOdsayRouteJson().replace("\"odsay\"", "\"legacy\""),
            )
        )

        assertTrue(result.accepted)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(42, result.travelMinutes)
        assertEquals(evaluatedAt, result.recommendedDepartureAt)
        assertEquals(evaluatedAt.plus(42, ChronoUnit.MINUTES), result.predictedArrivalAt)
    }

    @Test
    fun `비 ODsay 다중 ride legacy는 첫 승차 정보가 있어도 전체 환승 미검증 fallback으로 닫는다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES)))
        )

        val result = service(emptyList()).calculate(
            request(
                selectedRouteJson = selectedTransferRouteJson()
                    .replace("\"odsay\"", "\"legacy\""),
            )
        )

        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED,
            result.failureReason,
        )
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        verify(arrivalService, never()).getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `대체 여정이 정시여도 itinerary 전달 계약이 없으면 선택 경로 실패를 actionable ETA로 승격하지 않는다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES)))
        )
        val selectedJourney = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 5,
            secondDepartureMinute = 24,
            finalArrivalMinute = 50,
        )
        val alternativeJourney = transferJourney(
            firstRouteId = "bus-500",
            firstLineName = "500",
            secondRouteId = "subway-9",
            secondLineName = "9호선",
            firstArrivalMinute = 15,
            transferWalkMinutes = 3,
            secondDepartureMinute = 35,
            finalArrivalMinute = 55,
        )
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(selectedJourney, alternativeJourney),
        )

        val result = service(listOf(odsay)).calculate(
            request(
                targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES),
                selectedRouteJson = selectedTransferRouteJson(),
            )
        )

        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, result.failureReason)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertEquals(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            result.transitTimingBasis,
        )
        assertNull(result.predictedArrivalAt)
        assertEquals(40, result.travelMinutes)
        verify(arrivalService, times(1)).getBusArrivals(
            arsId = anyOrNull(),
            routeName = eq("402"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
        verify(arrivalService, never()).getBusArrivals(
            arsId = anyOrNull(),
            routeName = eq("500"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `선택 여정의 미래 환승 시간표가 없으면 대체 경로로 조용히 바꾸지 않고 저신뢰도 진단을 반환한다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES)))
        )
        val selectedWithUnknownTransfer = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 5,
            secondDepartureMinute = 30,
            finalArrivalMinute = 50,
        ).let { journey ->
            journey.copy(
                legs = journey.legs.map { leg ->
                    if (leg.sequence == 2) {
                        leg.copy(
                            scheduledDepartureAt = null,
                            timingBasis = TransitLegTimingBasis.UNKNOWN,
                        )
                    } else {
                        leg
                    }
                }
            )
        }
        val viableAlternative = transferJourney(
            firstRouteId = "bus-500",
            firstLineName = "500",
            secondRouteId = "subway-9",
            secondLineName = "9호선",
            firstArrivalMinute = 15,
            transferWalkMinutes = 3,
            secondDepartureMinute = 35,
            finalArrivalMinute = 55,
        )

        val result = service(
            listOf(
                RecordingProvider(
                    providerId = "odsay",
                    result = listOf(selectedWithUnknownTransfer, viableAlternative),
                )
            )
        ).calculate(request(selectedRouteJson = selectedTransferRouteJson()))

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertNull(result.predictedArrivalAt)
        assertEquals(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
            result.transitTimingBasis,
        )
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `분 단위 exact-boundary 환승은 정상 ETA가 아니라 저신뢰도 진단으로 반환한다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(5, ChronoUnit.MINUTES)))
        )
        val exactBoundaryJourney = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 4,
            secondDepartureMinute = 24,
            finalArrivalMinute = 50,
        )

        val result = service(
            listOf(
                RecordingProvider(
                    providerId = "odsay",
                    result = listOf(exactBoundaryJourney),
                )
            )
        ).calculate(request(selectedRouteJson = selectedTransferRouteJson()))

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
        assertEquals(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_UNKNOWN,
            result.transitTimingBasis,
        )
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `선택 여정 환승을 놓치고 대체 여정도 없으면 불가능한 시간표 도착을 ETA로 노출하지 않는다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES)))
        )
        val missedSelectedJourney = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 5,
            secondDepartureMinute = 24,
            finalArrivalMinute = 50,
        )

        val result = service(
            listOf(
                RecordingProvider(
                    providerId = "odsay",
                    result = listOf(missedSelectedJourney),
                )
            )
        ).calculate(request(selectedRouteJson = selectedTransferRouteJson()))

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, result.failureReason)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertNull(result.predictedArrivalAt)
    }

    private fun service(providers: List<TransitJourneyProvider>): TransitEtaCalculationService {
        val matcher = TransitJourneyMatcher()
        return TransitEtaCalculationService(
            selectedRouteDecoder = SelectedTransitRouteDecoder(jacksonObjectMapper()),
            journeyProviders = providers,
            safeDepartureResolver = SafeDepartureResolver(
                matcher = matcher,
                maxSearches = 3,
                toleranceSeconds = 60,
            ),
            firstBoardingRealtimeOverlay = FirstBoardingRealtimeOverlay(
                transitArrivalService = arrivalService,
                clock = clock,
                boardingBufferSeconds = 60,
                arrivalLimit = 10,
            ),
            journeyMatcher = matcher,
            transferFeasibilityEvaluator = TransitTransferFeasibilityEvaluator(),
        )
    }

    private fun request(
        plannedDepartureAt: Instant = evaluatedAt,
        targetArrivalAt: Instant? = null,
        selectedRouteJson: String = selectedOdsayRouteJson(),
    ) = TrafficRequest(
        originLat = 37.5547,
        originLng = 126.9706,
        destinationLat = 37.4979,
        destinationLng = 127.0276,
        travelMode = ScheduleTravelMode.TRANSIT,
        fallbackTravelMinutes = 40,
        selectedRouteJson = selectedRouteJson,
        selectedRouteTravelMinutes = 40,
        evaluatedAt = evaluatedAt,
        plannedDepartureAt = plannedDepartureAt,
        targetArrivalAt = targetArrivalAt,
        maxTravelMinutes = 1_440,
    )

    private fun journey(
        provider: String = "odsay",
        routeId: String,
        lineName: String,
        totalMinutes: Int,
        scheduledWaitMinutes: Int,
        fetchedAt: Instant = evaluatedAt,
        departureAt: Instant = evaluatedAt,
    ): TransitJourney {
        val accessMinutes = 5
        val egressMinutes = 8
        val rideMinutes = totalMinutes - accessMinutes - egressMinutes
        return TransitJourney(
            provider = provider,
            requestedDepartureAt = departureAt,
            departureAt = departureAt,
            arrivalAt = departureAt.plus(totalMinutes.toLong(), ChronoUnit.MINUTES),
            totalMinutes = totalMinutes,
            legs = listOf(
                TransitJourneyLeg(
                    sequence = 0,
                    mode = TransitLegMode.WALK,
                    durationMinutes = accessMinutes,
                ),
                TransitJourneyLeg(
                    sequence = 1,
                    mode = TransitLegMode.BUS,
                    durationMinutes = rideMinutes,
                    waitingMinutes = scheduledWaitMinutes,
                    scheduledDepartureAt = departureAt.plus(
                        accessMinutes.toLong(),
                        ChronoUnit.MINUTES,
                    ),
                    scheduledArrivalAt = departureAt.plus(
                        (totalMinutes - egressMinutes).toLong(),
                        ChronoUnit.MINUTES,
                    ),
                    from = TransitStop(
                        providerStopId = "1001",
                        arsId = "02005",
                        name = "서울역버스환승센터",
                    ),
                    to = TransitStop(
                        providerStopId = "2001",
                        arsId = "22009",
                        name = "강남역",
                    ),
                    line = TransitLine(
                        providerRouteId = routeId,
                        name = lineName,
                    ),
                    directionName = "강남역 방면",
                    directionCode = "DOWN",
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                ),
                TransitJourneyLeg(
                    sequence = 2,
                    mode = TransitLegMode.WALK,
                    durationMinutes = egressMinutes,
                ),
            ),
            fetchedAt = fetchedAt,
        )
    }

    private fun transferJourney(
        firstRouteId: String,
        firstLineName: String,
        secondRouteId: String,
        secondLineName: String,
        firstArrivalMinute: Long,
        transferWalkMinutes: Int,
        secondDepartureMinute: Long,
        finalArrivalMinute: Long,
    ): TransitJourney = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = evaluatedAt,
        departureAt = evaluatedAt,
        arrivalAt = evaluatedAt.plus(finalArrivalMinute, ChronoUnit.MINUTES),
        totalMinutes = finalArrivalMinute.toInt(),
        fetchedAt = evaluatedAt,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.BUS,
                durationMinutes = firstArrivalMinute.toInt(),
                waitingMinutes = 5,
                scheduledDepartureAt = evaluatedAt,
                scheduledArrivalAt = evaluatedAt.plus(firstArrivalMinute, ChronoUnit.MINUTES),
                from = TransitStop(arsId = "02005", name = "서울역버스환승센터"),
                to = TransitStop(providerStopId = "transfer", name = "환승역"),
                line = TransitLine(providerRouteId = firstRouteId, name = firstLineName),
                directionName = "환승역 방면",
                directionCode = "DOWN",
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 1,
                mode = TransitLegMode.WALK,
                durationMinutes = transferWalkMinutes,
                scheduledDepartureAt = evaluatedAt.plus(firstArrivalMinute, ChronoUnit.MINUTES),
                scheduledArrivalAt = evaluatedAt.plus(
                    firstArrivalMinute + transferWalkMinutes,
                    ChronoUnit.MINUTES,
                ),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = (finalArrivalMinute - secondDepartureMinute).toInt(),
                waitingMinutes = 0,
                scheduledDepartureAt = evaluatedAt.plus(secondDepartureMinute, ChronoUnit.MINUTES),
                scheduledArrivalAt = evaluatedAt.plus(finalArrivalMinute, ChronoUnit.MINUTES),
                from = TransitStop(providerStopId = "transfer-subway", name = "환승역"),
                to = TransitStop(providerStopId = "destination", name = "목적지역"),
                line = TransitLine(
                    providerRouteId = secondRouteId,
                    name = secondLineName,
                    serviceClass = TransitServiceClass.LOCAL,
                ),
                directionName = "목적지역 방면",
                directionCode = "DOWN",
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
        ),
    )

    private fun selectedTransferRouteJson(): String =
        """
            {
              "provider": "odsay",
              "minutes": 50,
              "transitLegs": [
                {
                  "kind": "BUS", "durationMinutes": 20, "waitingMinutes": 5,
                  "providerRouteId": "bus-402", "lineName": "402",
                  "startArsID": "02005", "startName": "서울역버스환승센터",
                  "endID": "transfer", "endName": "환승역",
                  "directionName": "환승역 방면", "directionCode": "DOWN"
                },
                { "kind": "WALK", "durationMinutes": 5 },
                {
                  "kind": "SUBWAY", "durationMinutes": 26, "waitingMinutes": 0,
                  "serviceClass": "LOCAL",
                  "providerRouteId": "subway-2", "lineName": "2호선",
                  "startID": "transfer-subway", "startName": "환승역",
                  "endID": "destination", "endName": "목적지역",
                  "directionName": "목적지역 방면", "directionCode": "DOWN"
                }
              ]
            }
        """.trimIndent()

    private fun busArrivals(): List<TransitArrivalDto> =
        arrivalService.getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )

    private fun busArrival(expectedAt: Instant) = TransitArrivalDto(
        provider = "seoul-bus",
        kind = "BUS",
        routeName = "402",
        expectedAt = expectedAt.toString(),
        observedAt = evaluatedAt.toString(),
        sourceUpdatedAt = evaluatedAt.toString(),
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
    )

    private fun selectedOdsayRouteJson(): String =
        """
            {
              "provider": "odsay",
              "minutes": 40,
              "transitLegs": [
                {
                  "kind": "WALK",
                  "durationMinutes": 5
                },
                {
                  "kind": "BUS",
                  "durationMinutes": 27,
                  "waitingMinutes": 5,
                  "providerRouteId": "bus-402",
                  "lineName": "402",
                  "startID": "1001",
                  "startArsID": "02005",
                  "startName": "서울역버스환승센터",
                  "endID": "2001",
                  "endArsID": "22009",
                  "endName": "강남역",
                  "directionName": "강남역 방면",
                  "directionCode": "DOWN"
                },
                {
                  "kind": "WALK",
                  "durationMinutes": 8
                }
              ]
            }
        """.trimIndent()

    private class RecordingProvider(
        override val providerId: String,
        private val result: List<TransitJourney> = emptyList(),
        private val failure: RuntimeException? = null,
    ) : TransitJourneyProvider {
        var searchCount: Int = 0
            private set

        override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
            searchCount += 1
            failure?.let { throw it }
            return result
        }
    }

    private class FunctionalProvider(
        private val response: (TransitJourneySearchRequest) -> List<TransitJourney>,
    ) : TransitJourneyProvider {
        override val providerId: String = "odsay"

        override fun search(request: TransitJourneySearchRequest): List<TransitJourney> =
            response(request)
    }
}
