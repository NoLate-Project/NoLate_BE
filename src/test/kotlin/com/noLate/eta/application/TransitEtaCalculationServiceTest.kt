package com.noLate.eta.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.port.DatedTransitTimetableProvider
import com.noLate.eta.application.port.SEOUL_DATED_TIMETABLE_PROVIDER_ID
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitWalkingTimeProvider
import com.noLate.eta.application.port.VerifiedWalkingTime
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.PlannedTransitRouteRefresher
import com.noLate.eta.application.transit.PlannedTransferObservations
import com.noLate.eta.application.transit.TransitRealtimeOverlay
import com.noLate.eta.application.transit.TransitRealtimeOverlayResolution
import com.noLate.eta.application.transit.SafeDepartureResolver
import com.noLate.eta.application.transit.SeoulBusEta
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.application.transit.TransitTransferFeasibilityEvaluator
import com.noLate.eta.application.transit.TransitTransferRealtimeGuard
import com.noLate.eta.application.transit.TransitRideObservationPoint
import com.noLate.eta.application.transit.TransitRideRealtimeObservation
import com.noLate.eta.application.transit.TransitWalkingTimeEnricher
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitJourneySearchRequest
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.eta.domain.TransitWalkingCoordinate
import com.noLate.eta.domain.TransitWalkingGeometry
import com.noLate.eta.domain.TransitWalkingQueryEndpoints
import com.noLate.eta.domain.TransitWalkingScope
import com.noLate.eta.domain.TransitWalkingTimingSource
import com.noLate.eta.infrastructure.odsay.OdsayTransitJourneyMapper
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.application.PlannedTransitTimingFailure
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.schedule.application.TransitRouteProvenance
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.infrastructure.SeoulBusPairedArrival
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class TransitEtaCalculationServiceTest {
    private val evaluatedAt = Instant.parse("2026-07-29T00:00:00Z")
    private val clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC)
    private val arrivalService = mock<TransitArrivalService>()

    @Test
    fun `서울 단일 버스는 같은 차량의 승하차 예측과 도보로 출발 및 도착을 보정한다`() {
        val bus = mock<SeoulTransitArrivalClient>()
        val routeId = "100100057"
        val board = SeoulBusRouteStop(routeId, 71, "122000184", "02005", "승차", "방면", null, 37.0, 127.0)
        val alight = board.copy(sequence = 72, stationId = "122000185", arsId = "22009", name = "하차")
        whenever(bus.getBusRouteDiagnosticStops(routeId)).thenReturn(listOf(board, alight))
        val source = evaluatedAt.minusSeconds(11)
        val boardAt = evaluatedAt.plusSeconds(368)
        val alightAt = boardAt.plusSeconds(136)
        whenever(bus.getPairedBusArrivals(listOf(board, alight))).thenReturn(listOf(
            SeoulBusPairedArrival(evaluatedAt.plusSeconds(18), evaluatedAt.plusSeconds(154), source, evaluatedAt),
            SeoulBusPairedArrival(boardAt, alightAt, source, evaluatedAt),
        ))
        val original = journey(routeId = "bus-402", lineName = "402", totalMinutes = 40,
            scheduledWaitMinutes = 5, timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE)
        val leg = original.rideLegs.single()
        val enriched = original.copy(legs = listOf(original.legs.first(), leg.copy(
            from = leg.from!!.copy(localStopId = board.stationId), to = leg.to!!.copy(localStopId = alight.stationId),
            line = TransitLine(providerRouteId = "other-line", name = "다른 버스"),
            alternativeBusLines = listOf(leg.line!!.copy(localRouteId = routeId))), original.legs.last()))
        val result = service(listOf(RecordingProvider("odsay", listOf(enriched))),
            busEta = SeoulBusEta(bus, 60, 120)).calculate(request())

        assertTrue(result.accepted)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(TransitTimingBasis.SAME_BUS_BOARDING_AND_ALIGHTING_REALTIME, result.transitTimingBasis)
        assertEquals(evaluatedAt, result.recommendedDepartureAt)
        assertEquals(alightAt.plusSeconds(480), result.predictedArrivalAt)
        assertEquals(17, result.travelMinutes)
        val segments = result.effectiveTransitRoute!!.segments
        assertEquals(listOf(5, 4, 8), segments.map { it.durationMinutes })
        assertEquals(boardAt, segments[1].busRidePrediction?.boardingAt)
        assertEquals(alightAt, segments[1].busRidePrediction?.alightingAt)
        assertEquals(source, result.firstBoardingObservation?.sourceUpdatedAt)
        verify(bus).getPairedBusArrivals(listOf(board, alight))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `버스 환승에서는 탈 수 있는 차량만 연결하고 미확인 시 첫 승차를 보존한다`(onwardAvailable: Boolean) {
        val bus = mock<SeoulTransitArrivalClient>()
        val source = evaluatedAt.minusSeconds(11)
        fun stops(routeId: String, firstStation: String, lastStation: String): List<SeoulBusRouteStop> {
            val board = SeoulBusRouteStop(routeId, 4, firstStation, "02005", "승차", "방면", null, 37.0, 127.0)
            return listOf(board, board.copy(sequence = 5, stationId = lastStation, arsId = "22009", name = "하차"))
                .also { whenever(bus.getBusRouteDiagnosticStops(routeId)).thenReturn(it) }
        }
        val first = stops("100100057", "122000184", "122000185")
        val second = stops("100100058", "122000186", "122000187")
        fun prediction(board: Long, alight: Long) = SeoulBusPairedArrival(
            evaluatedAt.plusSeconds(board), evaluatedAt.plusSeconds(alight), source, evaluatedAt,
        )
        whenever(bus.getPairedBusArrivals(first)).thenReturn(listOf(prediction(360, 480)))
        whenever(bus.getPairedBusArrivals(second)).thenReturn(
            if (onwardAvailable) listOf(prediction(659, 799), prediction(660, 800)) else emptyList(),
        )
        fun ride(index: Int, stops: List<SeoulBusRouteStop>) = TransitJourneyLeg(
            index, TransitLegMode.BUS, 10,
            from = TransitStop(providerStopId = "board-$index", localStopId = stops.first().stationId, name = "승차"),
            to = TransitStop(providerStopId = "alight-$index", localStopId = stops.last().stationId, name = "하차"),
            line = TransitLine(providerRouteId = "bus-$index", localRouteId = stops.first().routeId, name = "버스 $index"),
        )
        val journey = TransitJourney("odsay", evaluatedAt, evaluatedAt, evaluatedAt.plusSeconds(1800), 30, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 4),
            ride(1, first),
            TransitJourneyLeg(2, TransitLegMode.WALK, 2),
            ride(3, second),
            TransitJourneyLeg(4, TransitLegMode.WALK, 2),
        ), evaluatedAt)
        val selected = """{"provider":"odsay","transitLegs":[
            {"kind":"BUS","providerRouteId":"bus-1","startID":"board-1","endID":"alight-1"},
            {"kind":"WALK","durationMinutes":2},
            {"kind":"BUS","providerRouteId":"bus-3","startID":"board-3","endID":"alight-3"}]}"""
        val service = service(listOf(RecordingProvider("odsay", listOf(journey))),
            busEta = SeoulBusEta(bus, 60, 120))

        val result = service.calculate(request(selectedRouteJson = selected))
        assertEquals(onwardAvailable, result.accepted)
        assertEquals(evaluatedAt.plusSeconds(360), result.firstBoardingObservation?.expectedBoardingAt)
        if (onwardAvailable) {
            assertEquals(TransitTimingBasis.BUS_TRANSFER_BOARDING_AND_ALIGHTING_REALTIME, result.transitTimingBasis)
            assertEquals(evaluatedAt.plusSeconds(920), result.predictedArrivalAt)
            assertEquals(evaluatedAt.plusSeconds(660),
                result.effectiveTransitRoute?.segments?.get(3)?.busRidePrediction?.boardingAt)
            assertEquals(120L, result.effectiveTransitRoute?.segments?.get(3)?.transferTiming?.walkingSeconds)
        } else {
            assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
            assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
            assertEquals(40, result.travelMinutes)
            assertTrue(result.stale)
            assertNull(result.predictedArrivalAt)
            assertEquals(2, result.firstBoardingWaitMinutes)
        }
        verify(bus).getPairedBusArrivals(first)
        verify(bus).getPairedBusArrivals(second)
        org.mockito.kotlin.verifyNoInteractions(arrivalService)
    }

    @ParameterizedTest
    @CsvSource("false,true,false", "true,true,false", "true,false,false", "true,true,true")
    fun `버스 하차 예보가 늦어지면 이동 여유를 지켜 지하철을 다시 고른다`(
        lateBus: Boolean, onwardAvailable: Boolean, selectedBusIsAlternative: Boolean,
    ) {
        val bus = mock<SeoulTransitArrivalClient>()
        val stops = listOf(
            SeoulBusRouteStop("100100057", 4, "122000184", "02005", "승차", "방면", null, 37.0, 127.0),
            SeoulBusRouteStop("100100057", 5, "122000185", "22009", "하차", "방면", null, 37.0, 127.0),
        )
        val busAlightingAt = evaluatedAt.plusSeconds(if (lateBus) 495 else 480)
        whenever(bus.getBusRouteDiagnosticStops("100100057")).thenReturn(stops)
        whenever(bus.getPairedBusArrivals(stops)).thenReturn(listOf(
            SeoulBusPairedArrival(evaluatedAt.plusSeconds(360), busAlightingAt, evaluatedAt.minusSeconds(11), evaluatedAt),
        ))
        val rail = mock<TransitTimetableProvider>()
        fun train(number: String, boarding: Long, alighting: Long) = TransitLegTimetable(
            "seoul-timetable", SeoulSubwayLine.LINE_2, LocalDate.of(2026, 7, 29), number, "UP", TransitServiceClass.LOCAL,
            "0226", "0230", "성수", evaluatedAt.plusSeconds(boarding), evaluatedAt.plusSeconds(alighting), evaluatedAt,
        )
        val earlierTrain = train("2012", 660, 1110)
        whenever(rail.findRides(any())).thenReturn(
            if (onwardAvailable) listOf(earlierTrain, train("2014", 780, 1230)) else listOf(earlierTrain),
        )
        val selectedLine = TransitLine(providerRouteId = "bus-1", localRouteId = stops.first().routeId, name = "402")
        val candidateLine = if (selectedBusIsAlternative) TransitLine(providerRouteId = "other-bus", name = "다른 버스") else selectedLine
        val route = TransitJourney("odsay", evaluatedAt, evaluatedAt, evaluatedAt.plusSeconds(1800), 30, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 4),
            TransitJourneyLeg(1, TransitLegMode.BUS, 10,
                from = TransitStop(providerStopId = "board", localStopId = stops.first().stationId, name = "승차"),
                to = TransitStop(providerStopId = "alight", localStopId = stops.last().stationId, name = "하차"),
                line = candidateLine, alternativeBusLines = if (selectedBusIsAlternative) listOf(selectedLine) else emptyList()),
            TransitJourneyLeg(2, TransitLegMode.WALK, 2, durationSeconds = 120),
            TransitJourneyLeg(3, TransitLegMode.SUBWAY, 10,
                from = TransitStop(providerStopId = "sadang", name = "사당"),
                to = TransitStop(providerStopId = "sillim", name = "신림"),
                line = TransitLine(providerRouteId = "rail-2", name = "2호선", serviceClass = TransitServiceClass.LOCAL,
                    seoulSubwayLine = SeoulSubwayLine.LINE_2), directionCode = "UP"),
            TransitJourneyLeg(4, TransitLegMode.WALK, 2),
        ), evaluatedAt)
        val selected = """{"provider":"odsay","transitLegs":[
            {"kind":"BUS","providerRouteId":"bus-1","startID":"board","endID":"alight"},
            {"kind":"WALK","durationMinutes":2},
            {"kind":"SUBWAY","providerRouteId":"rail-2","startID":"sadang","endID":"sillim",
             "lineName":"2호선","serviceClass":"LOCAL","directionCode":"UP"}]}"""
        val result = service(listOf(RecordingProvider("odsay", listOf(route))),
            timetableProvider = rail, busEta = SeoulBusEta(bus, 60, 120)).calculate(request(selectedRouteJson = selected))

        assertEquals(onwardAvailable, result.accepted)
        if (onwardAvailable) {
            assertEquals(TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE, result.transitTimingBasis)
            assertEquals(evaluatedAt, result.recommendedDepartureAt)
            assertEquals(evaluatedAt.plusSeconds(if (lateBus) 1350 else 1230), result.predictedArrivalAt)
            val segments = requireNotNull(result.effectiveTransitRoute).segments
            assertEquals(busAlightingAt, segments[1].busRidePrediction?.alightingAt)
            assertEquals(if (lateBus) "2014" else "2012", segments[3].rideTiming?.timetable?.trainNumber)
            assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, segments[3].rideTiming?.basis)
            assertEquals(120L, segments[3].transferTiming?.walkingSeconds)
            assertEquals(60L, segments[3].transferTiming?.requiredMarginSeconds)
            assertEquals(if (lateBus) 105L else 0L, segments[3].transferTiming?.remainingSlackSeconds)
        } else {
            assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
            assertNull(result.predictedArrivalAt)
            assertEquals(evaluatedAt.plusSeconds(360), result.firstBoardingObservation?.expectedBoardingAt)
        }
        verify(bus).getPairedBusArrivals(stops)
        verify(rail).findRides(org.mockito.kotlin.check { query ->
            assertEquals(busAlightingAt.plusSeconds(180), query.notBefore)
        })
        org.mockito.kotlin.verifyNoInteractions(arrivalService)
    }

    @ParameterizedTest
    @CsvSource("660,true", "675,true", "675,false", "1800,false")
    fun `같은 열차의 하차 예보와 도보 여유로 후속 버스 차량을 고른다`(
        railAlightingSeconds: Long, laterBusAvailable: Boolean,
    ) {
        val (base, _) = timedSubwayFixture()
        val outsideBusHorizon = railAlightingSeconds == 1800L
        val train = base.legs[1].let { leg ->
            val table = requireNotNull(leg.timetable).copy(fromStationCode = "0431", terminalName = "오이도",
                arrivalAt = evaluatedAt.plusSeconds(if (outsideBusHorizon) 1800 else 660))
            leg.copy(from = TransitStop(providerStopId = "431", name = "동작"), timetable = table,
                scheduledArrivalAt = table.arrivalAt)
        }
        val routeId = "100100057"
        val board = SeoulBusRouteStop(routeId, 71, "122000184", "02005", "승차", "방면", null, 37.0, 127.0)
        val alight = board.copy(sequence = 72, stationId = "122000185", arsId = "22009", name = "하차")
        val paired = mock<SeoulTransitArrivalClient>()
        whenever(paired.getBusRouteDiagnosticStops(routeId)).thenReturn(listOf(board, alight))
        fun pair(boarding: Long, alighting: Long) = SeoulBusPairedArrival(evaluatedAt.plusSeconds(boarding),
            evaluatedAt.plusSeconds(alighting), evaluatedAt.minusSeconds(11), evaluatedAt)
        whenever(paired.getPairedBusArrivals(listOf(board, alight))).thenReturn(
            listOfNotNull(pair(840, 960), pair(960, 1080).takeIf { laterBusAvailable }),
        )
        val bus = TransitJourneyLeg(3, TransitLegMode.BUS, 5, timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
            from = TransitStop(providerStopId = "board", localStopId = board.stationId, name = board.name),
            to = TransitStop(providerStopId = "alight", localStopId = alight.stationId, name = alight.name),
            line = TransitLine(providerRouteId = "bus-1", localRouteId = routeId, name = "402"))
        val journey = base.copy(legs = listOf(base.legs.first(), train,
            TransitJourneyLeg(2, TransitLegMode.WALK, 2, durationSeconds = 120), bus,
            TransitJourneyLeg(4, TransitLegMode.WALK, 1, durationSeconds = 60)),
            arrivalAt = evaluatedAt.plusSeconds(1200), totalMinutes = 20)
        val selected = com.noLate.eta.simulator.RealOdsaySelectedRouteFixture.encode(jacksonObjectMapper(), journey)
        val boarding = evaluatedAt.plusSeconds(480)
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(11, evaluatedAt.plusSeconds(660), evaluatedAt, boarding, 3),
            firstBoardingObservation = TransitFirstBoardingObservation("seoul-openapi", "동작", "4호선", evaluatedAt,
                evaluatedAt.plusSeconds(360), boarding, evaluatedAt, evaluatedAt, "4703"),
        ))
        whenever(arrivalService.getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "사당",
                direction = "하행", destinationName = "오이도", subwayLineId = "1004", trainNumber = "4703",
                express = false, realtime = true, expectedAt = evaluatedAt.plusSeconds(railAlightingSeconds).toString(),
                sourceUpdatedAt = evaluatedAt.toString(), freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)))

        val result = service(listOf(RecordingProvider("odsay", listOf(journey))),
            TransitTransferRealtimeGuard(arrivalService), overlay, busEta = SeoulBusEta(paired, 60, 120))
            .calculate(request(selectedRouteJson = selected, targetArrivalAt = evaluatedAt.plusSeconds(3600).takeIf { outsideBusHorizon }))

        assertEquals(laterBusAvailable, result.accepted)
        assertEquals("4703", result.firstBoardingObservation?.trainNumber)
        if (laterBusAvailable) {
            assertEquals(TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED, result.transitTimingBasis)
            assertEquals(evaluatedAt.plusSeconds(if (railAlightingSeconds == 660L) 1020 else 1140), result.predictedArrivalAt)
            val segments = requireNotNull(result.effectiveTransitRoute).segments
            assertEquals(evaluatedAt.plusSeconds(railAlightingSeconds), segments[1].rideTiming?.predictedArrivalAt)
            assertEquals(evaluatedAt.plusSeconds(if (railAlightingSeconds == 660L) 840 else 960),
                segments[3].busRidePrediction?.boardingAt)
            assertEquals(120L, segments[3].transferTiming?.walkingSeconds)
            assertEquals(60L, segments[3].transferTiming?.requiredMarginSeconds)
        } else {
            assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
            assertNull(result.predictedArrivalAt)
        }
        if (outsideBusHorizon) {
            val deferred = requireNotNull(result.deferredSeoulBusTransfer)
            assertEquals(evaluatedAt.plusSeconds(1800), deferred.railPlan.segments.last().rideTiming?.timetable?.arrivalAt)
            assertEquals(evaluatedAt.plusSeconds(1980), deferred.railPlan.arrivalAt)
            assertEquals(evaluatedAt, deferred.railPlan.departureAt)
            assertEquals(routeId, deferred.stops.routeId)
            assertEquals(listOf(2), deferred.transferWalk.map { it.sequence })
            assertEquals(TransitWalkingScope.TRANSFER, deferred.transferWalk.single().walkingScope)
            assertEquals(3, deferred.bus.sequence)
            assertNull(deferred.bus.busRidePrediction)
            org.mockito.kotlin.verifyNoInteractions(paired)
        } else verify(paired).getPairedBusArrivals(listOf(board, alight))
    }

    @Test
    fun `실제 maasRP 스키마를 매핑한 경로는 첫 차량 실시간 보정이 성공해도 신뢰 가능한 전체 ETA가 아니다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(25, ChronoUnit.MINUTES)))
        )
        val fetchedAt = evaluatedAt.plusSeconds(3)
        val provider = mappedMaasRpProvider(fetchedAt)

        val result = service(listOf(provider)).calculate(request())

        // 실제 매퍼를 통과하지만 HTTP/실시간 피드는 fixture다. 앱 UI E2E로 간주하지 않는다.
        // 시간 근거 UNKNOWN을 해소하려고 앞선 출발 후보를 찾아도 maasRP는 계속 추정값이다.
        assertEquals(3, provider.searchCount)
        assertEquals(40, result.travelMinutes) // 채택하지 못한 60분 추정값으로 저장 ETA를 덮지 않는다.
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        assertEquals(fetchedAt, result.fetchedAt)
        assertEquals(TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE, result.transitTimingBasis)
        assertEquals(20, result.firstBoardingWaitMinutes)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertEquals("odsay", result.effectiveTransitRoute?.provider)
    }

    @Test
    fun `실제 maasRP 매핑 후 차량 정보가 없어도 경로 재조회 취득시각은 진단에 보존한다`() {
        whenever(busArrivals()).thenReturn(emptyList())
        val fetchedAt = evaluatedAt.plusSeconds(3)
        val provider = mappedMaasRpProvider(fetchedAt)

        val result = service(listOf(provider)).calculate(request())

        assertEquals(3, provider.searchCount)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(fetchedAt, result.fetchedAt)
        assertEquals(TransitTimingBasis.ROUTE_ESTIMATE_ONLY, result.transitTimingBasis)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertNull(result.firstBoardingWaitMinutes)
        assertNull(result.effectiveTransitRoute?.segments?.first { it.kind.name == "BUS" }?.waitingMinutes)
    }

    @Test
    fun `ODsay 배차 추정값이 없어도 관측된 첫 버스 대기만 공개한다`() {
        val boarding = evaluatedAt.plus(18, ChronoUnit.MINUTES)
        whenever(busArrivals()).thenReturn(listOf(busArrival(expectedAt = boarding)))
        val provider = RecordingProvider(providerId = "odsay", result = listOf(journey(
            routeId = "bus-402", lineName = "402", totalMinutes = 45,
            scheduledWaitMinutes = null, timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
        )))

        val result = service(listOf(provider)).calculate(request())

        assertFalse(result.accepted)
        assertNull(result.predictedArrivalAt)
        assertNull(result.recommendedDepartureAt)
        assertEquals(boarding, result.firstBoardingObservation?.expectedBoardingAt)
        assertEquals(13, result.firstBoardingWaitMinutes)
        assertNull(result.effectiveTransitRoute?.segments?.first { it.kind.name == "BUS" }?.waitingMinutes)
    }

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
        assertEquals(20, result.firstBoardingWaitMinutes)
        assertEquals(evaluatedAt.plus(60, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertFalse(result.stale)
        assertNull(result.failureReason)
    }

    @Test
    fun `단일 승차의 경로 추정값은 첫 차량 실시간 정보가 있어도 전체 ETA로 확정하지 않는다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(25, ChronoUnit.MINUTES)))
        )
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 45,
                    scheduledWaitMinutes = 5,
                    timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
                )
            ),
        )

        val result = service(listOf(odsay)).calculate(
            request(targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES))
        )

        // 첫 차량 대기만 실시간으로 알아도 이후 이동시간의 추정값이 검증되지는 않는다.
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        assertEquals(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_ROUTE_ESTIMATE,
            result.transitTimingBasis,
        )
        // 확인한 첫 차량 대기시간은 진단 증거로 남긴다: 도착 25분 - 접근 5분.
        assertEquals(20, result.firstBoardingWaitMinutes)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertNotNull(result.effectiveTransitRoute)
    }

    @Test
    fun `단일 승차의 경로 추정값은 첫 차량 정보가 없을 때 시간표 ETA로 노출하지 않는다`() {
        whenever(busArrivals()).thenReturn(emptyList())
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(
                journey(
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 45,
                    scheduledWaitMinutes = 5,
                    timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE,
                )
            ),
        )

        val result = service(listOf(odsay)).calculate(
            request(targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES))
        )

        // 경로 조회가 성공했더라도 추정 시각을 시간표 공급자의 확정 시각으로 취급하지 않는다.
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertEquals(TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        assertEquals(TransitTimingBasis.ROUTE_ESTIMATE_ONLY, result.transitTimingBasis)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertNotNull(result.effectiveTransitRoute)
        assertTrue(requireNotNull(result.effectiveTransitRoute).segments.all { it.rideTiming == null })
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
        // 조회가 실제 실패했을 때는 평가 시각으로 fetchedAt을 채우지 않는다.
        assertNull(result.fetchedAt)
        assertTrue(result.stale)
        assertEquals(
            TrafficFailureReasons.TRANSIT_JOURNEY_PROVIDER_UNAVAILABLE,
            result.failureReason,
        )
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `ODsay만 활성화된 환경에서 저장된 Tmap 여정은 재조회 미지원으로 기록한다`() {
        val odsay = RecordingProvider(providerId = "odsay", result = emptyList())
        val result = service(listOf(odsay)).calculate(
            request(selectedRouteJson = selectedOdsayRouteJson().replace("\"odsay\"", "\"tmap\"")),
        )

        assertEquals(TrafficFailureReasons.TRANSIT_ITINERARY_REFRESH_UNSUPPORTED, result.failureReason)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertEquals(0, odsay.searchCount)
        org.mockito.kotlin.verifyNoInteractions(arrivalService)
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

        // 전체 여정은 검증하지 못했으므로 저장된 선택 경로를 사용한다.
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)

        // 지각이 확정된 것이 아니라 전체 도착시각의 근거가 부족한 상태다.
        assertEquals(
            TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            result.failureReason,
        )

        // 확정할 수 없는 시각은 비워두고, 요청에 저장된 40분을 유지한다.
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertEquals(40, result.travelMinutes)
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

        // 전체 여정은 검증하지 못했으므로 저장된 선택 경로를 사용한다.
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertFalse(result.accepted)
        assertTrue(result.stale)

        // 지각이 확정된 것이 아니라 전체 도착시각의 근거가 부족한 상태다.
        assertEquals(
            TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY,
            result.failureReason,
        )

        // 확정할 수 없는 시각은 비워두고, 요청에 저장된 40분을 유지한다.
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        assertEquals(40, result.travelMinutes)    }

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
    fun `선택 환승을 놓치면 같은 응답의 검증된 대체 여정을 LIVE ETA로 채택한다`() {
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

        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertTrue(result.accepted)
        assertFalse(result.stale)
        assertNull(result.failureReason)
        assertEquals(55, result.travelMinutes)
        assertEquals(evaluatedAt.plus(55, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertEquals(TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE, result.transitRouteProvenance)
        assertEquals(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            result.transitTimingBasis,
        )
        val effectiveRoute = requireNotNull(result.effectiveTransitRoute)
        assertEquals("odsay", effectiveRoute.provider)
        assertTrue(effectiveRoute.identity.matches(Regex("[0-9a-f]{64}")))
        assertEquals("500", effectiveRoute.segments.first().lineName)
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
        verify(arrivalService, times(1)).getBusArrivals(
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
    fun `동일 환승 열차의 실시간 지연이 확인되면 시간표 도착을 정상 ETA로 채택하지 않는다`() {
        whenever(busArrivals()).thenReturn(
            listOf(busArrival(expectedAt = evaluatedAt.plus(5, ChronoUnit.MINUTES)))
        )
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(listOf(
            TransitRideRealtimeObservation(2, TransitRideObservationPoint.BOARDING,
                evaluatedAt.plus(32, ChronoUnit.MINUTES), evaluatedAt),
        ))
        val selectedJourney = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 5,
            secondDepartureMinute = 30,
            finalArrivalMinute = 50,
        )
        val provider = RecordingProvider(providerId = "odsay", result = listOf(selectedJourney))

        val result = service(listOf(provider), guard).calculate(
            request(selectedRouteJson = selectedTransferRouteJson())
        )

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_REALTIME_DELAY, result.failureReason)
        assertNull(result.predictedArrivalAt)
        assertNull(result.effectiveTransitRoute)
        // 기존 검색 예산 안에서만 더 이른 동일 경로를 찾아본다.
        assertEquals(3, provider.searchCount)
    }

    @Test
    fun `ODsay 경로 재조회는 지연된 하차시각에 검증한 목적지 도보 188초를 더한다`() {
        val (fixture, _) = timedSubwayFixture()
        val firstRide = fixture.legs[1]
        val table = requireNotNull(firstRide.timetable).copy(boardingAt = evaluatedAt.plusSeconds(360))
        val geometry = TransitWalkingGeometry(
            distanceMeters = 203,
            providerQueryEndpoints = TransitWalkingQueryEndpoints(
                TransitWalkingCoordinate(37.477, 126.981),
                TransitWalkingCoordinate(37.476563, 126.981746),
            ),
        )
        val journey = fixture.copy(
            totalMinutes = 24,
            arrivalAt = evaluatedAt.plusSeconds(24 * 60),
            legs = listOf(
                fixture.legs.first(),
                firstRide.copy(
                    scheduledDepartureAt = null, scheduledArrivalAt = null,
                    line = requireNotNull(firstRide.line).copy(seoulSubwayLine = SeoulSubwayLine.LINE_4),
                    timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE, timetable = null,
                ),
                TransitJourneyLeg(2, TransitLegMode.WALK, 4, walkingGeometry = geometry),
            ),
        )
        val selectedJson = """
            {"provider":"odsay","minutes":24,"transitLegs":[
              {"kind":"WALK","durationMinutes":5},
              {"kind":"SUBWAY","durationMinutes":15,"providerRouteId":"subway-4",
               "lineName":"4호선","serviceClass":"LOCAL","startID":"nowon","startName":"노원",
               "endID":"sadang4","endName":"사당","directionName":"사당 방면","directionCode":"DOWN"},
              {"kind":"WALK","durationMinutes":4}
            ]}
        """.trimIndent()
        val timetable = mock<TransitTimetableProvider>()
        whenever(timetable.findRides(any())).thenReturn(listOf(table))
        val walkingProvider = mock<TransitWalkingTimeProvider>()
        whenever(walkingProvider.providerId).thenReturn("odsay")
        whenever(walkingProvider.refine(geometry, 4)).thenReturn(VerifiedWalkingTime(188, evaluatedAt))
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        val observedBoardingAt = table.boardingAt.plusSeconds(60)
        val predictedAlightingAt = table.arrivalAt.plusSeconds(60)
        val predictedArrivalAt = predictedAlightingAt.plusSeconds(188)
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(overlay = TransitRealtimeOverlay(
                travelMinutes = 25,
                predictedArrivalAt = predictedArrivalAt,
                observedAt = evaluatedAt,
                boardingAt = observedBoardingAt,
            ))
        )

        val result = service(
            listOf(RecordingProvider("odsay", listOf(journey))),
            firstBoardingOverlay = firstOverlay,
            timetableProvider = timetable,
            walkingTimeEnricher = TransitWalkingTimeEnricher(listOf(walkingProvider)),
        ).calculate(request(selectedRouteJson = selectedJson))

        assertTrue(result.accepted) { result.toString() }
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(predictedArrivalAt, result.predictedArrivalAt)
        assertEquals(25, result.travelMinutes)
        val route = requireNotNull(result.effectiveTransitRoute)
        assertEquals("odsay", route.provider)
        assertEquals(predictedArrivalAt, route.arrivalAt)
        assertEquals(predictedAlightingAt, route.segments[1].rideTiming?.predictedArrivalAt)
        val egress = route.segments.last()
        assertEquals(TransitWalkingScope.EGRESS, egress.walkingScope)
        assertEquals(188L, egress.durationSeconds)
        assertEquals(TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS, egress.walkingTiming?.source)
        verify(walkingProvider).refine(geometry, 4)
    }

    @Test
    fun `환승편이 일찍 올 때 도보 여유가 부족할 가능성만 반영하고 도착을 앞당기지 않는다`() {
        val (fixture, selectedJson) = timedSubwayFixture()
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(overlay = TransitRealtimeOverlay(
                travelMinutes = 24, predictedArrivalAt = evaluatedAt.plusSeconds(1440),
                observedAt = evaluatedAt, boardingAt = evaluatedAt.plusSeconds(360),
            )),
        )
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING,
                evaluatedAt.plusSeconds(780), evaluatedAt, earlyBoardingArrival = true),
        ))

        // 첫 열차의 60초 지연을 포함한 사당 도착은 540초, 환승역 도착 예보는 780초다.
        for (transferWalkSeconds in listOf(300L, 211L, 210L, 181L, 180L, 120L)) {
            val journey = fixture.copy(arrivalAt = evaluatedAt.plusSeconds(1440), totalMinutes = 24,
                legs = fixture.legs.mapIndexed { index, leg -> when (index) {
                    1 -> leg.copy(scheduledArrivalAt = evaluatedAt.plusSeconds(480),
                        timetable = leg.timetable!!.copy(arrivalAt = evaluatedAt.plusSeconds(480)))
                    2 -> leg.copy(durationMinutes = ((transferWalkSeconds + 59) / 60).toInt(), durationSeconds = transferWalkSeconds)
                    3 -> leg.copy(scheduledDepartureAt = evaluatedAt.plusSeconds(900),
                        scheduledArrivalAt = evaluatedAt.plusSeconds(1320), timetable = leg.timetable!!.copy(
                            boardingStationArrivalAt = evaluatedAt.plusSeconds(870),
                            boardingAt = evaluatedAt.plusSeconds(900), arrivalAt = evaluatedAt.plusSeconds(1320),
                        ))
                    else -> leg
                } })
            val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, firstOverlay).calculate(
                request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(1500)),
            )
            if (transferWalkSeconds > 210L) {
                assertFalse(result.accepted)
                assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
                assertNull(result.predictedArrivalAt)
            } else {
                assertTrue(result.accepted) { result.toString() }
                assertEquals(journey.arrivalAt, result.predictedArrivalAt)
                val segments = requireNotNull(result.effectiveTransitRoute).segments
                assertEquals(evaluatedAt.plusSeconds(540), segments[1].rideTiming?.predictedArrivalAt)
                val segment = segments[3]
                val timing = requireNotNull(segment.rideTiming)
                val readyBeforeArrival = transferWalkSeconds <= 180
                assertEquals(evaluatedAt.plusSeconds(if (readyBeforeArrival) 780 else 900), timing.predictedBoardingAt)
                assertEquals(if (readyBeforeArrival) EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING
                    else EffectiveTransitRideTimingBasis.TIMETABLE, timing.basis)
                assertEquals(if (readyBeforeArrival) evaluatedAt else null, timing.observationSourceUpdatedAt)
                assertEquals(evaluatedAt.plusSeconds(1320), timing.predictedArrivalAt)
            }
        }
    }

    @Test
    fun `저장한 날짜 지정 환승 계획에서 첫 열차 지연 뒤 같은 정차 순서의 다음 편을 찾는다`() {
        val (fixture, selectedJson) = timedSubwayFixture()
        val stops = listOf("사당", "낙성대", "서울대입구", "봉천", "신림")
        val journey = fixture.copy(legs = fixture.legs.map { leg ->
            val table = leg.timetable
            when {
                leg.sequence == 0 -> leg.copy(durationMinutes = 3)
                table == null -> leg
                leg.sequence == 3 -> leg.copy(timetable = table.copy(provider = SEOUL_DATED_TIMETABLE_PROVIDER_ID),
                    orderedStopNames = stops, nextStopName = stops[1])
                else -> leg.copy(timetable = table.copy(provider = SEOUL_DATED_TIMETABLE_PROVIDER_ID))
            }
        })
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE))
        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), firstBoardingOverlay = overlay)
            .calculate(request(selectedRouteJson = selectedJson, targetArrivalAt = fixture.arrivalAt))
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val json = mapper.readTree(mapper.writeValueAsString(requireNotNull(result.effectiveTransitRoute)))
        val original = mapper.treeToValue(json, EffectiveTransitRoute::class.java)
        val now = evaluatedAt.plusSeconds(18 * 60)
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observePlannedTransfers(any(), eq(now), any(), anyOrNull(), any(), eq(false))).thenReturn(PlannedTransferObservations(current = listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, evaluatedAt.plusSeconds(25 * 60), now))))
        val nextTable = requireNotNull(journey.legs[3].timetable).copy(trainNumber = "2482",
            boardingAt = evaluatedAt.plusSeconds(35 * 60), arrivalAt = evaluatedAt.plusSeconds(45 * 60))
        val deadline = EtaCalculationDeadline()
        val provider = DatedTransitTimetableProvider { query, order ->
            nextTable.takeIf { deadline.current()?.isExpired() == false && query.notBefore == evaluatedAt.plusSeconds(31 * 60) &&
                query.nextStopName == stops[1] && order == stops }
        }
        val refresh = PlannedTransitRouteRefresher(guard, TransitJourneyTimetableEnricher(emptyList(),
            datedTimetableProvider = provider, calculationDeadline = deadline), TransitTransferFeasibilityEvaluator())
        val update = requireNotNull(refresh.refresh(original, now))
        assertNull(update.failure)
        val route = requireNotNull(update.route)
        assertEquals(original.departureAt, route.departureAt)
        assertEquals("2482", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(evaluatedAt.plusSeconds(47 * 60), route.arrivalAt)
        assertEquals(original.segments[2], route.segments[2])
        assertNull(deadline.current())

        // 이전 버전의 저장 경로는 정차역 순서가 없으므로 날짜 지정 공급자로 같은 편을 입증할 수 없다.
        (json["segments"][3] as com.fasterxml.jackson.databind.node.ObjectNode).remove("orderedStopNames")
        val legacy = mapper.treeToValue(json, EffectiveTransitRoute::class.java)
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, refresh.refresh(legacy, now)?.failure)
    }

    @Test
    fun `환승편 하차역 관측을 최종 ETA와 두 승차 구간 표시로 함께 계산한다`() {
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(overlay = TransitRealtimeOverlay(
                travelMinutes = 43,
                predictedArrivalAt = evaluatedAt.plus(43, ChronoUnit.MINUTES),
                observedAt = evaluatedAt,
                boardingAt = evaluatedAt.plus(6, ChronoUnit.MINUTES),
            ))
        )
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING,
                evaluatedAt.plus(45, ChronoUnit.MINUTES), evaluatedAt),
        ))
        val (journey, selectedJson) = timedSubwayFixture()
        val provider = RecordingProvider(providerId = "odsay", result = listOf(journey))

        val result = service(listOf(provider), guard, firstOverlay).calculate(
            request(selectedRouteJson = selectedJson,
                targetArrivalAt = evaluatedAt.plus(50, ChronoUnit.MINUTES))
        )

        assertTrue(result.accepted)
        assertFalse(result.stale)
        assertEquals(TransitTimingBasis.FIRST_BOARDING_REALTIME_TRANSFER_OBSERVED, result.transitTimingBasis)
        assertEquals(evaluatedAt.plus(47, ChronoUnit.MINUTES), result.predictedArrivalAt)
        val route = requireNotNull(result.effectiveTransitRoute)
        assertEquals(result.predictedArrivalAt, route.arrivalAt)
        assertEquals(evaluatedAt.plus(21, ChronoUnit.MINUTES), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(evaluatedAt.plus(45, ChronoUnit.MINUTES), route.segments[3].rideTiming?.predictedArrivalAt)
        assertEquals(evaluatedAt, route.segments[3].rideTiming?.observationSourceUpdatedAt)

        // 첫 차량 피드가 끊겨도 뒤 구간의 관측 자체는 폐기하지 않는다. 다만 출발 알림에
        // 사용할 전체 여정 근거가 없으므로 정상 ETA로 승격하지 않는다.
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(
                overlay = null,
                failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE,
            )
        )
        // 두 번째 열차가 15분 조회 창 안으로 들어온 별도 짧은 후보. 먼 미래 열차를
        // 실시간으로 가장해 조회하지 않는 기존 한도 보호는 유지한다.
        val shortJourney = journey.copy(
            arrivalAt = evaluatedAt.plus(22, ChronoUnit.MINUTES), totalMinutes = 22,
            legs = journey.legs.mapIndexed { index, leg -> when (index) {
                1 -> leg.copy(durationMinutes = 3,
                    scheduledArrivalAt = evaluatedAt.plus(8, ChronoUnit.MINUTES),
                    timetable = leg.timetable!!.copy(arrivalAt = evaluatedAt.plus(8, ChronoUnit.MINUTES)))
                2 -> leg.copy(durationMinutes = 2)
                3 -> leg.copy(durationMinutes = 8,
                    scheduledDepartureAt = evaluatedAt.plus(12, ChronoUnit.MINUTES),
                    scheduledArrivalAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
                    timetable = leg.timetable!!.copy(
                        boardingAt = evaluatedAt.plus(12, ChronoUnit.MINUTES),
                        arrivalAt = evaluatedAt.plus(20, ChronoUnit.MINUTES)))
                else -> leg
            } },
        )
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING,
                evaluatedAt.plus(25, ChronoUnit.MINUTES), evaluatedAt),
        ))
        org.mockito.Mockito.clearInvocations(guard)
        val firstUnobserved = service(listOf(RecordingProvider("odsay", listOf(shortJourney))),
            guard, firstOverlay).calculate(
            request(selectedRouteJson = selectedJson,
                targetArrivalAt = evaluatedAt.plus(30, ChronoUnit.MINUTES))
        )
        assertFalse(firstUnobserved.accepted)
        assertTrue(firstUnobserved.stale)
        assertEquals(TransitTimingBasis.FIRST_BOARDING_TIMETABLE_TRANSFER_OBSERVED,
            firstUnobserved.transitTimingBasis)
        assertEquals(evaluatedAt.plus(27, ChronoUnit.MINUTES), firstUnobserved.predictedArrivalAt)
        assertEquals(evaluatedAt.plus(25, ChronoUnit.MINUTES),
            firstUnobserved.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.predictedArrivalAt)
        verify(guard, times(1)).observeTransferChanges(any(), eq(evaluatedAt))
    }

    @Test
    fun `오래된 실시간 응답에서 같은 정시 열차를 타는 시간표 후보는 더 늦은 검증 출발을 유지한다`() {
        val (fixture, selectedJson) = timedSubwayFixture()
        val planned = evaluatedAt.plusSeconds(60)
        val departures = mutableListOf<Instant>()
        val provider = FunctionalProvider { input ->
            departures += input.departureAt
            if (input.departureAt > planned) emptyList() else listOf(fixture.copy(requestedDepartureAt = input.departureAt, departureAt = input.departureAt,
                legs = fixture.legs.map { leg -> when (leg.sequence) {
                    0 -> leg.copy(durationMinutes = 2)
                    1 -> leg.copy(timetable = requireNotNull(leg.timetable).let { it.copy(boardingStationArrivalAt = it.boardingAt.minusSeconds(30)) })
                    else -> leg
                } }))
        }
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(failureReason = TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE),
        )
        val result = service(listOf(provider), firstBoardingOverlay = overlay).calculate(request(
            plannedDepartureAt = planned, selectedRouteJson = selectedJson, targetArrivalAt = fixture.arrivalAt,
        ))

        assertEquals(planned, departures.first())
        assertTrue(evaluatedAt in departures)
        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE, result.failureReason)
        assertEquals(TransitTimingBasis.TIMETABLE_ONLY, result.transitTimingBasis)
        assertEquals(planned, result.effectiveTransitRoute?.departureAt)
        assertEquals(fixture.arrivalAt, result.effectiveTransitRoute?.arrivalAt)
        val transfer = requireNotNull(result.effectiveTransitRoute?.segments?.get(3)?.transferTiming)
        assertEquals(evaluatedAt.plusSeconds(20 * 60), transfer.previousArrivalAt)
        assertEquals(300L, transfer.walkingSeconds)
        assertEquals(evaluatedAt.plusSeconds(30 * 60), transfer.boardingAt)
        assertEquals(60L, transfer.requiredMarginSeconds)
    }

    @Test
    fun `더 이른 복구 후보의 실패와 원래 요청 시각의 미래 시간표를 따로 보존한다`() {
        val (fixture, selectedJson) = timedSubwayFixture()
        val planned = evaluatedAt.plusSeconds(600)
        val provider = FunctionalProvider { input ->
            val delta = java.time.Duration.between(evaluatedAt, input.departureAt)
            listOf(fixture.copy(requestedDepartureAt = input.departureAt, departureAt = input.departureAt,
                arrivalAt = fixture.arrivalAt.plus(delta), legs = fixture.legs.map { leg ->
                    if (!leg.isRide) if (leg.sequence == 0) leg.copy(durationMinutes = 2) else leg
                    else leg.copy(scheduledDepartureAt = leg.scheduledDepartureAt?.plus(delta),
                        scheduledArrivalAt = leg.scheduledArrivalAt?.plus(delta),
                        timetable = requireNotNull(leg.timetable).let { table -> table.copy(
                            boardingAt = table.boardingAt.plus(delta), arrivalAt = table.arrivalAt.plus(delta)) })
                }))
        }
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenAnswer { call ->
            val journey = call.getArgument<TransitJourney>(0)
            TransitRealtimeOverlayResolution(failureReason = if (journey.departureAt >= planned)
                TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED else TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON)
        }
        val target = evaluatedAt.plusSeconds(3600)
        val result = service(listOf(provider), firstBoardingOverlay = overlay).calculate(request(
            plannedDepartureAt = planned, targetArrivalAt = target, selectedRouteJson = selectedJson))
        assertFalse(result.accepted)
        // 현재 진단과 재조회 결과를 바꾸지 않고, 그 전에 확인한 미래 계획만 별도로 보존한다.
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_OUT_OF_HORIZON, result.failureReason)
        assertTrue(requireNotNull(result.effectiveTransitRoute).departureAt < planned)
        val evidence = requireNotNull(result.futureTimetablePlanEvidence)
        assertEquals(planned, evidence.route.departureAt)
        assertEquals(TrafficFailureReasons.TRANSIT_TRAIN_NOT_YET_OBSERVED, evidence.failureReason)
        val verified = com.noLate.schedule.application.TimetableDeparturePlanPolicy.verifiedRoute(
            result, evaluatedAt.plusSeconds(1), target, 60)
        assertEquals(evidence.route, verified)
        assertNull(com.noLate.schedule.application.TimetableDeparturePlanPolicy.verifiedRoute(
            result.copy(futureTimetablePlanEvidence = null), evaluatedAt.plusSeconds(1), target, 60))
    }

    @Test
    fun `첫 역의 조기 도착만으로 노원 사당 환승의 부족한 여유를 채우지 않는다`() {
        val (fixture, selectedJson) = timedSubwayFixture()
        val journey = fixture.copy(arrivalAt = evaluatedAt.plusSeconds(4017), totalMinutes = 67,
            legs = fixture.legs.map { leg ->
                when (leg.sequence) {
                    0 -> leg.copy(durationMinutes = 3, durationSeconds = 137)
                    1 -> leg.copy(
                        scheduledArrivalAt = evaluatedAt.plusSeconds(3284),
                        timetable = requireNotNull(leg.timetable).copy(
                            boardingStationArrivalAt = evaluatedAt.plusSeconds(374),
                            boardingAt = evaluatedAt.plusSeconds(404),
                            arrivalAt = evaluatedAt.plusSeconds(3284),
                        ),
                    )
                    2 -> leg.copy(durationMinutes = 3, durationSeconds = 157)
                    3 -> leg.copy(
                        scheduledDepartureAt = evaluatedAt.plusSeconds(3494),
                        scheduledArrivalAt = evaluatedAt.plusSeconds(3944),
                        timetable = requireNotNull(leg.timetable).copy(
                            boardingAt = evaluatedAt.plusSeconds(3494),
                            arrivalAt = evaluatedAt.plusSeconds(3944),
                        ),
                    )
                    4 -> leg.copy(durationMinutes = 2, durationSeconds = 73)
                    else -> leg
                }
            })
        val boarding = evaluatedAt.plusSeconds(264)
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(
                overlay = TransitRealtimeOverlay(67, journey.arrivalAt, evaluatedAt, boarding),
                firstBoardingObservation = TransitFirstBoardingObservation(
                    "seoul-openapi", "노원", "4호선", evaluatedAt, boarding, boarding, evaluatedAt, evaluatedAt, "4703",
                ),
            ),
        )
        val guard = mock<TransitTransferRealtimeGuard>()
        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, firstOverlay).calculate(
            request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(4200)),
        )

        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
        assertNull(result.predictedArrivalAt)
        verify(guard, org.mockito.Mockito.never()).observeFirstRideAlighting(any(), any(), any())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `두 번째 승차의 하차 지연 뒤 재연결한 세 번째 열차에 이전 편 관측을 섞지 않는다`(obsoleteLastObservation: Boolean) {
        val (journey, selectedJson) = timedThreeRideFixture()
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(13, journey.arrivalAt, evaluatedAt, evaluatedAt.plusSeconds(120)),
        ))
        val guard = mock<TransitTransferRealtimeGuard>()
        val observations = listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING,
                evaluatedAt.plusSeconds(600), evaluatedAt),
        ) + if (obsoleteLastObservation) listOf(TransitRideRealtimeObservation(
            5, TransitRideObservationPoint.ALIGHTING, evaluatedAt.plusSeconds(1020), evaluatedAt,
        )) else emptyList()
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(observations)
        val timetable = mock<TransitTimetableProvider>()
        whenever(timetable.findRides(any())).thenReturn(listOf(requireNotNull(journey.legs[5].timetable).copy(
            trainNumber = "1014", boardingAt = evaluatedAt.plusSeconds(780), arrivalAt = evaluatedAt.plusSeconds(900),
        )))

        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, firstOverlay, timetable)
            .calculate(request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(1200)))

        println("ETA_ONWARD_ALIGHTING_REPLAY " + jacksonObjectMapper().writeValueAsString(mapOf(
            "inputJourney" to journey.toString(), "observations" to observations.toString(),
            "obsoleteLastObservation" to obsoleteLastObservation, "accepted" to result.accepted,
            "arrivalAt" to result.predictedArrivalAt?.toString(), "failureReason" to result.failureReason,
            "trains" to result.effectiveTransitRoute?.segments?.mapNotNull { it.rideTiming?.timetable?.trainNumber },
        )))
        assertTrue(result.accepted)
        assertEquals(evaluatedAt.plusSeconds(960), result.predictedArrivalAt)
        val timings = requireNotNull(result.effectiveTransitRoute).segments.mapNotNull { it.rideTiming }
        assertEquals(listOf("4703", "2480", "1014"), timings.map { it.timetable.trainNumber })
        assertEquals(evaluatedAt.plusSeconds(600), timings[1].predictedArrivalAt)
        assertEquals(evaluatedAt.plusSeconds(900), timings[2].predictedArrivalAt)
        assertEquals(listOf(60L, 60L, 60L, 60L), result.effectiveTransitRoute?.segments
            ?.filter { it.kind == com.noLate.schedule.application.EffectiveTransitRouteSegmentKind.WALK }
            ?.map { it.durationSeconds })
        verify(timetable, org.mockito.kotlin.atLeastOnce()).findRides(org.mockito.kotlin.argThat {
            line == SeoulSubwayLine.LINE_1 && notBefore == evaluatedAt.plusSeconds(720)
        })
    }

    @Test
    fun `후속 승차의 하차 지연 뒤 다음 시간표가 없으면 기존 환승을 정상 ETA로 재사용하지 않는다`() {
        val (journey, selectedJson) = timedThreeRideFixture()
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(13, journey.arrivalAt, evaluatedAt, evaluatedAt.plusSeconds(120)),
        ))
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING,
                evaluatedAt.plusSeconds(600), evaluatedAt),
        ))
        val timetable = mock<TransitTimetableProvider>()
        whenever(timetable.findRides(any())).thenReturn(emptyList())
        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, firstOverlay, timetable)
            .calculate(request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(1200)))

        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_REALTIME_DELAY, result.failureReason)
        assertNull(result.predictedArrivalAt)
        assertNull(result.effectiveTransitRoute)
    }

    @ParameterizedTest
    @CsvSource("4703,660,660,810", "4703,840,840,1200", "4705,660,780,1200", "4703,840,840,910")
    fun `환승 없는 열차는 같은 편의 하차역 예보와 마지막 도보로 목적지 시각을 정한다`(
        destinationTrain: String, forecastSeconds: Long, expectedAlightingSeconds: Long, targetSeconds: Long,
    ) {
        val (base, _) = timedSubwayFixture()
        val first = base.legs[1]
        val table = requireNotNull(first.timetable).copy(fromStationCode = "0431",
            terminalName = "오이도", arrivalAt = evaluatedAt.plusSeconds(600))
        val shortRide = first.copy(from = TransitStop(providerStopId = "431", name = "동작"),
            durationMinutes = 5, scheduledArrivalAt = table.arrivalAt, timetable = table)
        val journey = base.copy(legs = listOf(base.legs.first(), shortRide, base.legs.last().copy(sequence = 2)),
            arrivalAt = table.arrivalAt.plusSeconds(120), totalMinutes = 12)
        val selected = com.noLate.eta.simulator.RealOdsaySelectedRouteFixture.encode(jacksonObjectMapper(), journey)
        val boarding = evaluatedAt.plusSeconds(480)
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(15, evaluatedAt.plusSeconds(900), evaluatedAt, boarding, 3),
            firstBoardingObservation = TransitFirstBoardingObservation("seoul-openapi", "동작", "4호선",
                evaluatedAt, evaluatedAt.plusSeconds(360), boarding, evaluatedAt, evaluatedAt, "4703"),
        ))
        whenever(arrivalService.getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "사당",
                direction = "하행", destinationName = "오이도", subwayLineId = "1004", trainNumber = destinationTrain,
                express = false, realtime = true, expectedAt = evaluatedAt.plusSeconds(forecastSeconds).toString(),
                sourceUpdatedAt = evaluatedAt.toString(), freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)))
        val result = service(listOf(RecordingProvider("odsay", listOf(journey))),
            TransitTransferRealtimeGuard(arrivalService), overlay)
            .calculate(request(selectedRouteJson = selected, targetArrivalAt = evaluatedAt.plusSeconds(targetSeconds)))

        val ride = requireNotNull(result.effectiveTransitRoute?.segments?.get(1)?.rideTiming)
        val onTime = expectedAlightingSeconds + 120 <= targetSeconds
        assertEquals(onTime, result.accepted)
        assertEquals(if (onTime) null else TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE, result.failureReason)
        assertEquals(boarding, ride.predictedBoardingAt)
        assertEquals(evaluatedAt.plusSeconds(expectedAlightingSeconds), ride.predictedArrivalAt)
        assertEquals(evaluatedAt.plusSeconds(expectedAlightingSeconds + 120), result.predictedArrivalAt)
        assertEquals(if (onTime) evaluatedAt.plusSeconds(120) else evaluatedAt, result.recommendedDepartureAt)
        assertEquals(if (destinationTrain == table.trainNumber) EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME
            else EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE, ride.basis)
        assertEquals(if (destinationTrain == table.trainNumber) TransitTimingBasis.SAME_SUBWAY_BOARDING_AND_ALIGHTING_REALTIME
            else TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE, result.transitTimingBasis)
        verify(arrivalService, times(1)).getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10))
    }

    @ParameterizedTest
    @ValueSource(longs = [360L, 720L])
    fun `첫 하차역의 직접 관측으로 환승을 놓치면 같은 경로의 다음 열차와 도착을 갱신한다`(boardingSeconds: Long) {
        val (fixture, selectedJson) = timedSubwayFixture()
        val journey = fixture.copy(arrivalAt = evaluatedAt.plusSeconds(1920), totalMinutes = 32,
            legs = fixture.legs.map { leg ->
                when (leg.sequence) {
                    1 -> leg.copy(durationMinutes = 5, scheduledArrivalAt = evaluatedAt.plusSeconds(600),
                        timetable = requireNotNull(leg.timetable).copy(arrivalAt = evaluatedAt.plusSeconds(600)))
                    3 -> leg.copy(scheduledDepartureAt = evaluatedAt.plusSeconds(1200),
                        scheduledArrivalAt = evaluatedAt.plusSeconds(1800),
                        timetable = requireNotNull(leg.timetable).copy(
                            boardingAt = evaluatedAt.plusSeconds(1200), arrivalAt = evaluatedAt.plusSeconds(1800)))
                    else -> leg
                }
            })
        val boarding = evaluatedAt.plusSeconds(boardingSeconds)
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(32, journey.arrivalAt, evaluatedAt, boarding),
            firstBoardingObservation = TransitFirstBoardingObservation(
                "seoul-openapi", "노원", "4호선", evaluatedAt, evaluatedAt.plusSeconds(360), boarding, evaluatedAt, evaluatedAt, "4703"),
        ))
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeFirstRideAlighting(any(), eq(boarding), eq(evaluatedAt))).thenReturn(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING,
                evaluatedAt.plusSeconds(900), evaluatedAt))
        val timetable = mock<TransitTimetableProvider>()
        whenever(timetable.findRides(any())).thenReturn(listOf(requireNotNull(journey.legs[3].timetable).copy(
            trainNumber = "2482", boardingAt = evaluatedAt.plusSeconds(1320), arrivalAt = evaluatedAt.plusSeconds(1920))))

        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, overlay, timetable)
            .calculate(request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(2400)))

        println("ETA_FIRST_ALIGHTING_REPLAY " + jacksonObjectMapper().writeValueAsString(mapOf(
            "case" to "late-alighting-next-transfer", "inputJourney" to journey.toString(),
            "observedBoardingAt" to boarding.toString(), "observedAlightingAt" to evaluatedAt.plusSeconds(900).toString(),
            "recommendedDepartureAt" to result.recommendedDepartureAt?.toString(),
            "accepted" to result.accepted, "arrivalAt" to result.predictedArrivalAt?.toString(),
            "onwardTrain" to result.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber,
            "failureReason" to result.failureReason,
        )))
        assertTrue(result.accepted)
        assertEquals("2482", result.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(boarding, result.firstBoardingObservation?.expectedBoardingAt)
        assertEquals(evaluatedAt.plusSeconds(900), result.effectiveTransitRoute?.segments?.get(1)?.rideTiming?.predictedArrivalAt)
        assertEquals(evaluatedAt.plusSeconds(2040), result.predictedArrivalAt)
        verify(timetable, org.mockito.kotlin.atLeastOnce()).findRides(org.mockito.kotlin.argThat {
            notBefore == evaluatedAt.plusSeconds(1260)
        })
        verify(guard, times(1)).observeFirstRideAlighting(any(), eq(boarding), eq(evaluatedAt))
    }

    @ParameterizedTest
    @ValueSource(longs = [390L, 450L])
    fun `하차역 관측에서 지연이 줄어 기존 환승이 가능하면 같은 첫 열차의 안전한 출발을 안내한다`(boardingSeconds: Long) {
        val (fixture, selectedJson) = timedSubwayFixture()
        val journey = fixture.copy(arrivalAt = evaluatedAt.plusSeconds(1720), totalMinutes = 29,
            legs = fixture.legs.map { leg ->
                when (leg.sequence) {
                    1 -> leg.copy(durationMinutes = 5, scheduledArrivalAt = evaluatedAt.plusSeconds(600),
                        timetable = requireNotNull(leg.timetable).copy(arrivalAt = evaluatedAt.plusSeconds(600)))
                    3 -> leg.copy(scheduledDepartureAt = evaluatedAt.plusSeconds(1000),
                        scheduledArrivalAt = evaluatedAt.plusSeconds(1600),
                        timetable = requireNotNull(leg.timetable).copy(
                            boardingAt = evaluatedAt.plusSeconds(1000), arrivalAt = evaluatedAt.plusSeconds(1600)))
                    else -> leg
                }
            })
        val boarding = evaluatedAt.plusSeconds(boardingSeconds)
        val overlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(overlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(TransitRealtimeOverlayResolution(
            overlay = TransitRealtimeOverlay(30, journey.arrivalAt.plusSeconds(90), evaluatedAt, boarding),
            firstBoardingObservation = TransitFirstBoardingObservation(
                "seoul-openapi", "노원", "4호선", evaluatedAt, evaluatedAt.plusSeconds(360),
                boarding, evaluatedAt, evaluatedAt, "4703"),
        ))
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeFirstRideAlighting(any(), eq(boarding), eq(evaluatedAt))).thenReturn(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING,
                evaluatedAt.plusSeconds(620), evaluatedAt))
        val timetable = mock<TransitTimetableProvider>()

        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, overlay, timetable)
            .calculate(request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(2000)))

        println("ETA_FIRST_ALIGHTING_REPLAY " + jacksonObjectMapper().writeValueAsString(mapOf(
            "case" to "recovered-alighting-original-transfer", "inputJourney" to journey.toString(),
            "observedBoardingAt" to boarding.toString(), "observedAlightingAt" to evaluatedAt.plusSeconds(620).toString(),
            "recommendedDepartureAt" to result.recommendedDepartureAt?.toString(),
            "accepted" to result.accepted, "arrivalAt" to result.predictedArrivalAt?.toString(),
            "onwardTrain" to result.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber,
            "failureReason" to result.failureReason,
        )))
        assertTrue(result.accepted)
        // 접근 300초와 승차 여유 60초를 모두 확보하며 ODsay 출발 분을 내림한다.
        val expectedDeparture = evaluatedAt.plusSeconds((boardingSeconds - 360) / 60 * 60)
        assertEquals(expectedDeparture, result.recommendedDepartureAt)
        assertEquals(expectedDeparture, result.effectiveTransitRoute?.departureAt)
        assertEquals("2480", result.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(evaluatedAt.plusSeconds(620), result.effectiveTransitRoute?.segments?.get(1)?.rideTiming?.predictedArrivalAt)
        assertEquals(evaluatedAt.plusSeconds(1720), result.predictedArrivalAt)
        verify(guard, times(1)).observeFirstRideAlighting(any(), eq(boarding), eq(evaluatedAt))
        verify(timetable, never()).findRides(any())
    }

    @Test
    fun `같은 첫 열차의 하차 지연이 환승 안전 여유를 없애면 조기 연결을 채택하지 않는다`() {
        val (original, selectedJson) = timedSubwayFixture()
        val journey = original.copy(arrivalAt = evaluatedAt.plusSeconds(1620), totalMinutes = 27,
            legs = original.legs.map { leg ->
                val (board, alight) = when (leg.sequence) {
                    1 -> 480L to 660L
                    3 -> 900L to 1500L
                    else -> return@map leg
                }
                leg.copy(scheduledDepartureAt = evaluatedAt.plusSeconds(board),
                    scheduledArrivalAt = evaluatedAt.plusSeconds(alight),
                    timetable = requireNotNull(leg.timetable).copy(
                        boardingAt = evaluatedAt.plusSeconds(board), arrivalAt = evaluatedAt.plusSeconds(alight),
                    ))
            })
        val boarding = evaluatedAt.plusSeconds(360)
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(
                overlay = TransitRealtimeOverlay(27, journey.arrivalAt, evaluatedAt, boarding),
                firstBoardingObservation = TransitFirstBoardingObservation(
                    "seoul-openapi", "노원", "4호선", evaluatedAt, boarding, boarding, evaluatedAt, evaluatedAt, "4703",
                ),
            ),
        )
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeFirstRideAlighting(any(), eq(boarding), eq(evaluatedAt)))
            .thenReturn(TransitRideRealtimeObservation(
                1, TransitRideObservationPoint.ALIGHTING, evaluatedAt.plusSeconds(570), evaluatedAt,
            ))

        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, firstOverlay).calculate(
            request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(1720)),
        )

        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
        assertNull(result.predictedArrivalAt)
        verify(guard, times(1)).observeFirstRideAlighting(any(), eq(boarding), eq(evaluatedAt))
    }

    @Test
    fun `마지막 열차의 30초 하차 지연으로 도착 마감을 넘긴 여정은 정시로 채택하지 않는다`() {
        val (journey, selectedJson) = timedSubwayFixture()
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(overlay = TransitRealtimeOverlay(
                travelMinutes = 42, predictedArrivalAt = journey.arrivalAt, observedAt = evaluatedAt,
                boardingAt = evaluatedAt.plusSeconds(300),
            )),
        )
        val guard = mock<TransitTransferRealtimeGuard>()
        whenever(guard.observeTransferChanges(any(), eq(evaluatedAt))).thenReturn(listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING,
                requireNotNull(journey.legs[3].timetable).arrivalAt.plusSeconds(30), evaluatedAt),
        ))

        val result = service(listOf(RecordingProvider("odsay", listOf(journey))), guard, firstOverlay).calculate(
            request(selectedRouteJson = selectedJson, targetArrivalAt = journey.arrivalAt.plusSeconds(20)),
        )

        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE, result.failureReason)
        assertEquals(journey.arrivalAt.plusSeconds(30), result.predictedArrivalAt)
    }

    @Test
    fun `시간표 도착과 출발 사이에 들어온 관측도 지연이면 안전한 다음 환승편으로 연결한다`() {
        val (fixture, selectedJson) = timedSubwayFixture()
        val first = fixture.legs[1]
        val transfer = fixture.legs[3]
        val originalTransfer = requireNotNull(transfer.timetable)
        val journey = fixture.copy(legs = fixture.legs.mapIndexed { index, leg ->
            when (index) {
                1 -> leg.copy(timetable = requireNotNull(first.timetable).copy(
                    boardingStationArrivalAt = evaluatedAt.plusSeconds(300),
                    boardingAt = evaluatedAt.plusSeconds(330),
                ))
                3 -> leg.copy(
                    scheduledDepartureAt = evaluatedAt.plusSeconds(1570),
                    timetable = originalTransfer.copy(boardingAt = evaluatedAt.plusSeconds(1570)),
                )
                else -> leg
            }
        })
        val firstOverlay = mock<FirstBoardingRealtimeOverlay>()
        whenever(firstOverlay.resolve(any(), eq(evaluatedAt), any())).thenReturn(
            TransitRealtimeOverlayResolution(overlay = TransitRealtimeOverlay(
                travelMinutes = 42, predictedArrivalAt = journey.arrivalAt, observedAt = evaluatedAt,
                boardingAt = evaluatedAt.plusSeconds(320),
            ))
        )
        val timetable = mock<TransitTimetableProvider>()
        whenever(timetable.findRides(any())).thenReturn(listOf(originalTransfer.copy(
            trainNumber = "2482", boardingAt = evaluatedAt.plusSeconds(1620),
            arrivalAt = evaluatedAt.plusSeconds(2460),
        )))
        val result = service(listOf(RecordingProvider("odsay", listOf(journey))),
            firstBoardingOverlay = firstOverlay, timetableProvider = timetable).calculate(
            request(selectedRouteJson = selectedJson, targetArrivalAt = evaluatedAt.plusSeconds(3000)),
        )

        assertTrue(result.accepted)
        assertEquals("2482", result.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(evaluatedAt.plusSeconds(2580), result.predictedArrivalAt)
        verify(timetable, org.mockito.kotlin.atLeastOnce()).findRides(org.mockito.kotlin.argThat {
            notBefore == evaluatedAt.plusSeconds(1580)
        })
    }

    @Test
    fun `선택 여정의 미래 환승 시간표가 없어도 검증된 대체 경로가 있으면 명시적으로 전환한다`() {
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

        assertTrue(result.accepted)
        assertFalse(result.stale)
        assertNull(result.failureReason)
        assertEquals(TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE, result.transitRouteProvenance)
        assertEquals(
            TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE,
            result.transitTimingBasis,
        )
        assertEquals(evaluatedAt.plus(55, ChronoUnit.MINUTES), result.predictedArrivalAt)
    }

    @Test
    fun `첫 동일 signature 차량의 환승 여유가 부족해도 다음 안전 차량을 선택 경로로 유지한다`() {
        stubBusArrivals("402", evaluatedAt.plus(5, ChronoUnit.MINUTES))
        val exactBoundary = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 4,
            secondDepartureMinute = 24,
            finalArrivalMinute = 50,
        )
        val nextSafeVehicle = exactBoundary.copy(
            legs = exactBoundary.legs.map { leg ->
                if (leg.sequence == 2) leg.copy(waitingMinutes = 5) else leg
            }
        )
        val odsay = RecordingProvider(
            providerId = "odsay",
            result = listOf(exactBoundary, nextSafeVehicle),
        )

        val result = service(listOf(odsay)).calculate(
            request(
                targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES),
                selectedRouteJson = selectedTransferRouteJson(),
            )
        )

        assertTrue(result.accepted)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertEquals(evaluatedAt.plus(50, ChronoUnit.MINUTES), result.predictedArrivalAt)
        assertEquals("402", requireNotNull(result.effectiveTransitRoute).segments.first().lineName)
    }

    @Test
    fun `도착 마감이 있으면 한 응답의 안전한 선택 차량 중 가장 늦은 출발을 채택한다`() {
        stubBusArrivals(
            "402",
            evaluatedAt.plus(10, ChronoUnit.MINUTES),
            evaluatedAt.plus(15, ChronoUnit.MINUTES),
        )
        val first = journey(
            routeId = "bus-402",
            lineName = "402",
            totalMinutes = 43,
            scheduledWaitMinutes = 5,
            departureAt = evaluatedAt,
        )
        val later = journey(
            routeId = "bus-402",
            lineName = "402",
            totalMinutes = 43,
            scheduledWaitMinutes = 5,
            departureAt = evaluatedAt.plus(5, ChronoUnit.MINUTES),
        )

        val result = service(
            listOf(RecordingProvider("odsay", result = listOf(first, later)))
        ).calculate(request(targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES)))

        assertTrue(result.accepted)
        assertEquals(evaluatedAt.plus(5, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)

        val firstVehicleResult = service(
            listOf(RecordingProvider("odsay", result = listOf(first)))
        ).calculate(request(targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES)))
        assertEquals(
            requireNotNull(firstVehicleResult.effectiveTransitRoute).identity,
            requireNotNull(result.effectiveTransitRoute).identity,
            "차량 시각만 달라진 같은 노선은 동일한 deterministic route identity여야 한다.",
        )
    }

    @Test
    fun `앞 조회의 안전한 대체 경로보다 뒤 조회에서 회복된 선택 경로를 전역 우선한다`() {
        val initialDeparture = evaluatedAt.plus(10, ChronoUnit.MINUTES)
        stubBusArrivals(
            "402",
            evaluatedAt.plus(15, ChronoUnit.MINUTES),
            evaluatedAt.plus(25, ChronoUnit.MINUTES),
            evaluatedAt.plus(30, ChronoUnit.MINUTES),
        )
        stubBusArrivals("500", evaluatedAt.plus(15, ChronoUnit.MINUTES))
        val searchedAt = mutableListOf<Instant>()
        val odsay = FunctionalProvider { search ->
            searchedAt += search.departureAt
            val selectedJourney = transferJourney(
                firstRouteId = "bus-402",
                firstLineName = "402",
                secondRouteId = "subway-2",
                secondLineName = "2호선",
                firstArrivalMinute = 20,
                transferWalkMinutes = 4,
                secondDepartureMinute = 24,
                finalArrivalMinute = 35,
                secondWaitingMinutes = if (search.departureAt == initialDeparture) 0 else 5,
                departureAt = search.departureAt,
            )
            if (search.departureAt == initialDeparture) {
                listOf(
                    selectedJourney,
                    transferJourney(
                        firstRouteId = "bus-500",
                        firstLineName = "500",
                        secondRouteId = "subway-9",
                        secondLineName = "9호선",
                        firstArrivalMinute = 15,
                        transferWalkMinutes = 3,
                        secondDepartureMinute = 22,
                        finalArrivalMinute = 40,
                        secondWaitingMinutes = 8,
                        departureAt = search.departureAt,
                    ),
                )
            } else {
                listOf(selectedJourney)
            }
        }

        val result = service(listOf(odsay)).calculate(
            request(
                plannedDepartureAt = initialDeparture,
                targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES),
                selectedRouteJson = selectedTransferRouteJson(),
            )
        )

        assertEquals(
            listOf(
                evaluatedAt.plus(10, ChronoUnit.MINUTES),
                evaluatedAt.plus(9, ChronoUnit.MINUTES),
                evaluatedAt.plus(25, ChronoUnit.MINUTES),
            ),
            searchedAt,
        )
        assertTrue(result.accepted)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertEquals(evaluatedAt.plus(25, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(evaluatedAt.plus(60, ChronoUnit.MINUTES), result.predictedArrivalAt)
    }

    @Test
    fun `대체 후보는 응답당 앞선 세 개까지만 실시간 검증한다`() {
        stubBusArrivals("402", evaluatedAt.plus(5, ChronoUnit.MINUTES))
        listOf("500", "501", "502", "503").forEach { route ->
            stubBusArrivals(route, evaluatedAt.plus(10, ChronoUnit.MINUTES))
        }
        val selectedUnknown = transferJourney(
            firstRouteId = "bus-402",
            firstLineName = "402",
            secondRouteId = "subway-2",
            secondLineName = "2호선",
            firstArrivalMinute = 20,
            transferWalkMinutes = 4,
            secondDepartureMinute = 24,
            finalArrivalMinute = 50,
        )
        val alternatives = listOf("500", "501", "502", "503").mapIndexed { index, route ->
            journey(
                routeId = "bus-$route",
                lineName = route,
                totalMinutes = 30 + index,
                scheduledWaitMinutes = 5,
            )
        }

        val result = service(
            listOf(RecordingProvider("odsay", result = listOf(selectedUnknown) + alternatives))
        ).calculate(request(selectedRouteJson = selectedTransferRouteJson()))

        assertTrue(result.accepted)
        assertEquals(TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE, result.transitRouteProvenance)
        listOf("500", "501", "502").forEach { route ->
            verify(arrivalService, times(1)).getBusArrivals(
                arsId = anyOrNull(),
                routeName = eq(route),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        }
        verify(arrivalService, never()).getBusArrivals(
            arsId = anyOrNull(),
            routeName = eq("503"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `선택 signature가 응답에서 사라져도 세 번 탐색 후 검증된 대체 경로를 채택한다`() {
        stubBusArrivals(
            "500",
            evaluatedAt.plus(10, ChronoUnit.MINUTES),
            evaluatedAt.plus(15, ChronoUnit.MINUTES),
            evaluatedAt.plus(20, ChronoUnit.MINUTES),
        )
        val searchedAt = mutableListOf<Instant>()
        val odsay = FunctionalProvider { search ->
            searchedAt += search.departureAt
            listOf(
                journey(
                    routeId = "bus-500",
                    lineName = "500",
                    totalMinutes = 30,
                    scheduledWaitMinutes = 5,
                    departureAt = search.departureAt,
                )
            )
        }

        val result = service(listOf(odsay)).calculate(
            request(
                plannedDepartureAt = evaluatedAt.plus(10, ChronoUnit.MINUTES),
                targetArrivalAt = evaluatedAt.plus(60, ChronoUnit.MINUTES),
            )
        )

        assertEquals(
            listOf(
                evaluatedAt.plus(10, ChronoUnit.MINUTES),
                evaluatedAt.plus(5, ChronoUnit.MINUTES),
                evaluatedAt,
            ),
            searchedAt,
        )
        assertTrue(result.accepted)
        assertEquals(TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE, result.transitRouteProvenance)
        assertEquals(evaluatedAt.plus(10, ChronoUnit.MINUTES), result.recommendedDepartureAt)
        assertEquals(evaluatedAt.plus(40, ChronoUnit.MINUTES), result.predictedArrivalAt)
    }

    @Test
    fun `선택 signature도 유효 ODsay 대체 후보도 없으면 NOT_FOUND로 닫는다`() {
        val result = service(
            listOf(RecordingProvider("odsay", result = emptyList()))
        ).calculate(request())

        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_SELECTED_ROUTE_NOT_FOUND, result.failureReason)
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
    fun `선택 signature가 없고 대체 후보의 실시간 도착도 없으면 accepted로 승격하지 않는다`() {
        stubBusArrivals("500")
        val searchedAt = mutableListOf<Instant>()
        val odsay = FunctionalProvider { search ->
            searchedAt += search.departureAt
            listOf(
                journey(
                    routeId = "bus-500",
                    lineName = "500",
                    totalMinutes = 30,
                    scheduledWaitMinutes = 5,
                    departureAt = search.departureAt,
                )
            )
        }

        val result = service(listOf(odsay)).calculate(
            request(plannedDepartureAt = evaluatedAt.plus(10, ChronoUnit.MINUTES))
        )

        assertEquals(
            listOf(
                evaluatedAt.plus(10, ChronoUnit.MINUTES),
                evaluatedAt.plus(5, ChronoUnit.MINUTES),
                evaluatedAt,
            ),
            searchedAt,
        )
        assertFalse(result.accepted)
        assertEquals(TrafficSource.TIMETABLE_PROVIDER, result.source)
        assertEquals(TrafficFailureReasons.TRANSIT_ARRIVAL_UNAVAILABLE, result.failureReason)
        assertEquals(TransitRouteProvenance.ODSAY_ALTERNATIVE_ROUTE, result.transitRouteProvenance)
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
    fun `환승 시간표 UNKNOWN 경고는 세 번의 ODsay 회복 조회를 모두 쓴 뒤 반환한다`() {
        stubBusArrivals("402", evaluatedAt.plus(15, ChronoUnit.MINUTES))
        val searchedAt = mutableListOf<Instant>()
        val odsay = FunctionalProvider { search ->
            searchedAt += search.departureAt
            val journey = transferJourney(
                firstRouteId = "bus-402",
                firstLineName = "402",
                secondRouteId = "subway-2",
                secondLineName = "2호선",
                firstArrivalMinute = 20,
                transferWalkMinutes = 5,
                secondDepartureMinute = 30,
                finalArrivalMinute = 50,
                departureAt = search.departureAt,
            )
            listOf(
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
            )
        }

        val result = service(listOf(odsay)).calculate(
            request(
                plannedDepartureAt = evaluatedAt.plus(10, ChronoUnit.MINUTES),
                selectedRouteJson = selectedTransferRouteJson(),
            )
        )

        assertEquals(
            listOf(
                evaluatedAt.plus(10, ChronoUnit.MINUTES),
                evaluatedAt.plus(5, ChronoUnit.MINUTES),
                evaluatedAt,
            ),
            searchedAt,
        )
        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_TIMING_UNKNOWN, result.failureReason)
        assertEquals(TransitRouteProvenance.SELECTED_ROUTE_PRESERVED, result.transitRouteProvenance)
        assertNotNull(result.effectiveTransitRoute)
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

    @Test
    fun `저장 경로 provider와 같은 여정 provider를 선택한다`() {
        whenever(busArrivals()).thenReturn(emptyList())

        val kakao = RecordingProvider(
            providerId = "kakao",
            result = listOf(
                journey(
                    provider = "kakao",
                    routeId = "bus-402",
                    lineName = "402",
                    totalMinutes = 43,
                    scheduledWaitMinutes = 7,
                )
            ),
        )

        val result = service(listOf(kakao)).calculate(
            request(
                selectedRouteJson = selectedOdsayRouteJson()
                    .replace(
                        "\"provider\": \"odsay\"",
                        "\"provider\": \"kakao\"",
                    ),
            )
        )

        assertTrue(kakao.searchCount > 0)
        assertEquals("kakao", result.effectiveTransitRoute?.provider)
    }

    @Test
    fun `확인된 환승 실패는 후속 후보의 시간표 누락으로 덮지 않는다`() {
        stubBusArrivals("402", evaluatedAt.plus(15, ChronoUnit.MINUTES))
        var searches = 0
        val provider = FunctionalProvider { search ->
            searches++
            val candidate = transferJourney(
                firstRouteId = "bus-402", firstLineName = "402",
                secondRouteId = "subway-2", secondLineName = "2호선",
                firstArrivalMinute = 20, transferWalkMinutes = 5,
                secondDepartureMinute = 24, finalArrivalMinute = 50,
                departureAt = search.departureAt,
            )
            listOf(if (searches == 1) candidate else candidate.copy(
                legs = candidate.legs.map { leg ->
                    if (leg.sequence == 2) leg.copy(
                        scheduledDepartureAt = null, timingBasis = TransitLegTimingBasis.UNKNOWN,
                    ) else leg
                },
            ))
        }

        val result = service(listOf(provider)).calculate(request(
            plannedDepartureAt = evaluatedAt.plus(10, ChronoUnit.MINUTES),
            selectedRouteJson = selectedTransferRouteJson(),
        ))

        // 정상 회복 후보 검색은 모두 수행하되, 불확실한 후속 후보로 실패 증거를 지우지 않는다.
        assertEquals(3, searches)
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, result.failureReason)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
    }


    @Test
    fun `시외 시간표 미연결 상태는 저장 경로를 보존하고 시내 실시간 API를 호출하지 않는다`() {
        val provider = mock<TransitJourneyProvider>()
        val selected = """{"schemaVersion":2,"provider":"tmap","transitLegs":[
          {"kind":"INTERCITY_BUS","lineName":"서울-공주","startName":"서울남부","endName":"공주"}
        ]}"""
        val result = service(listOf(provider)).calculate(request(selectedRouteJson = selected))
        assertFalse(result.accepted)
        assertTrue(result.stale)
        assertEquals(TrafficSource.SELECTED_ROUTE, result.source)
        assertEquals(40, result.travelMinutes)
        assertEquals(TrafficFailureReasons.TRANSIT_INTERCITY_TIMETABLE_UNAVAILABLE, result.failureReason)
        assertNull(result.recommendedDepartureAt)
        assertNull(result.predictedArrivalAt)
        org.mockito.kotlin.verifyNoInteractions(provider, arrivalService)
    }

    private fun timedThreeRideFixture(): Pair<TransitJourney, String> {
        val specs = listOf(
            Triple(SeoulSubwayLine.LINE_4, "노원", "사당"),
            Triple(SeoulSubwayLine.LINE_2, "사당", "신도림"),
            Triple(SeoulSubwayLine.LINE_1, "신도림", "영등포"),
        )
        val numbers = listOf("4703", "2480", "1012")
        val legs = mutableListOf<TransitJourneyLeg>()
        for ((index, spec) in specs.withIndex()) {
            legs += TransitJourneyLeg(legs.size, TransitLegMode.WALK, 1, durationSeconds = 60)
            val boarding = evaluatedAt.plusSeconds(120L + index * 240L)
            val arrival = boarding.plusSeconds(120)
            val table = TransitLegTimetable("seoul-timetable", spec.first, LocalDate.of(2026, 7, 29),
                numbers[index], "DOWN", TransitServiceClass.LOCAL, "from-$index", "to-$index", "종착",
                boarding, arrival, evaluatedAt)
            legs += TransitJourneyLeg(legs.size, TransitLegMode.SUBWAY, 3, waitingMinutes = 1,
                scheduledDepartureAt = boarding.minusSeconds(60), scheduledArrivalAt = arrival,
                from = TransitStop(providerStopId = "from-$index", name = spec.second),
                to = TransitStop(providerStopId = "to-$index", name = spec.third),
                line = TransitLine(providerRouteId = "route-$index", name = spec.first.displayName,
                    serviceClass = TransitServiceClass.LOCAL, seoulSubwayLine = spec.first),
                directionCode = "DOWN", timingBasis = TransitLegTimingBasis.TIMETABLE,
                durationSeconds = 180, timetable = table)
        }
        legs += TransitJourneyLeg(legs.size, TransitLegMode.WALK, 1, durationSeconds = 60)
        val journey = TransitJourney("odsay", evaluatedAt, evaluatedAt, evaluatedAt.plusSeconds(780),
            13, legs, evaluatedAt)
        return journey to com.noLate.eta.simulator.RealOdsaySelectedRouteFixture.encode(jacksonObjectMapper(), journey)
    }

    private fun timedSubwayFixture(): Pair<TransitJourney, String> {
        fun table(line: SeoulSubwayLine, train: String, from: String, to: String,
                  boardingMinute: Long, arrivalMinute: Long) = TransitLegTimetable(
            provider = "seoul-timetable", line = line,
            serviceDate = LocalDate.of(2026, 7, 29), trainNumber = train,
            directionCode = "DOWN", serviceClass = TransitServiceClass.LOCAL,
            fromStationCode = from, toStationCode = to, terminalName = "종착",
            boardingAt = evaluatedAt.plus(boardingMinute, ChronoUnit.MINUTES),
            arrivalAt = evaluatedAt.plus(arrivalMinute, ChronoUnit.MINUTES), fetchedAt = evaluatedAt,
        )
        val journey = TransitJourney(
            provider = "odsay", requestedDepartureAt = evaluatedAt, departureAt = evaluatedAt,
            arrivalAt = evaluatedAt.plus(42, ChronoUnit.MINUTES), totalMinutes = 42, fetchedAt = evaluatedAt,
            legs = listOf(
                TransitJourneyLeg(0, TransitLegMode.WALK, 5),
                TransitJourneyLeg(1, TransitLegMode.SUBWAY, 15,
                    scheduledDepartureAt = evaluatedAt.plus(5, ChronoUnit.MINUTES),
                    scheduledArrivalAt = evaluatedAt.plus(20, ChronoUnit.MINUTES),
                    from = TransitStop(providerStopId = "nowon", name = "노원"),
                    to = TransitStop(providerStopId = "sadang4", name = "사당"),
                    line = TransitLine(providerRouteId = "subway-4", name = "4호선",
                        serviceClass = TransitServiceClass.LOCAL),
                    directionName = "사당 방면", directionCode = "DOWN",
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                    timetable = table(SeoulSubwayLine.LINE_4, "4703", "0411", "0433", 5, 20)),
                TransitJourneyLeg(2, TransitLegMode.WALK, 5),
                TransitJourneyLeg(3, TransitLegMode.SUBWAY, 10,
                    scheduledDepartureAt = evaluatedAt.plus(30, ChronoUnit.MINUTES),
                    scheduledArrivalAt = evaluatedAt.plus(40, ChronoUnit.MINUTES),
                    from = TransitStop(providerStopId = "sadang2", name = "사당"),
                    to = TransitStop(providerStopId = "sillim", name = "신림"),
                    line = TransitLine(providerRouteId = "subway-2", name = "2호선",
                        serviceClass = TransitServiceClass.LOCAL),
                    directionName = "신림 방면", directionCode = "DOWN",
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                    timetable = table(SeoulSubwayLine.LINE_2, "2480", "0226", "0230", 30, 40)),
                TransitJourneyLeg(4, TransitLegMode.WALK, 2),
            ),
        )
        val selectedJson = """
            {"provider":"odsay","minutes":42,"transitLegs":[
              {"kind":"WALK","durationMinutes":5},
              {"kind":"SUBWAY","durationMinutes":15,"providerRouteId":"subway-4",
               "lineName":"4호선","serviceClass":"LOCAL","startID":"nowon","startName":"노원",
               "endID":"sadang4","endName":"사당","directionName":"사당 방면","directionCode":"DOWN"},
              {"kind":"WALK","durationMinutes":5},
              {"kind":"SUBWAY","durationMinutes":10,"providerRouteId":"subway-2",
               "lineName":"2호선","serviceClass":"LOCAL","startID":"sadang2","startName":"사당",
               "endID":"sillim","endName":"신림","directionName":"신림 방면","directionCode":"DOWN"},
              {"kind":"WALK","durationMinutes":2}
            ]}
        """.trimIndent()
        return journey to selectedJson
    }

    private fun service(
        providers: List<TransitJourneyProvider>,
        transferRealtimeGuard: TransitTransferRealtimeGuard? = null,
        firstBoardingOverlay: FirstBoardingRealtimeOverlay? = null,
        timetableProvider: TransitTimetableProvider? = null,
        walkingTimeEnricher: TransitWalkingTimeEnricher = TransitWalkingTimeEnricher(),
        busEta: SeoulBusEta? = null,
    ): TransitEtaCalculationService {
        val matcher = TransitJourneyMatcher()
        return TransitEtaCalculationService(
            selectedRouteDecoder = SelectedTransitRouteDecoder(jacksonObjectMapper()),
            journeyProviders = providers,
            safeDepartureResolver = SafeDepartureResolver(
                matcher = matcher,
                maxSearches = 3,
                toleranceSeconds = 60,
            ),
            firstBoardingRealtimeOverlay = firstBoardingOverlay ?: FirstBoardingRealtimeOverlay(
                transitArrivalService = arrivalService,
                clock = clock,
                boardingBufferSeconds = 60,
                arrivalLimit = 10,
            ),
            journeyMatcher = matcher,
            transferFeasibilityEvaluator = TransitTransferFeasibilityEvaluator(),
            transferRealtimeGuard = transferRealtimeGuard,
            timetableEnricher = TransitJourneyTimetableEnricher(listOfNotNull(timetableProvider)),
            walkingTimeEnricher = walkingTimeEnricher,
            seoulBusEta = busEta,
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

    // 아래 TIMETABLE 기본값은 실제 시간표 공급자를 가정한 계산 fixture다.
    // ODsay maasRP 운영 응답은 위 mappedMaasRpProvider처럼 실제 매퍼로 검증해야 한다.
    private fun journey(
        provider: String = "odsay",
        routeId: String,
        lineName: String,
        totalMinutes: Int,
        scheduledWaitMinutes: Int?,
        fetchedAt: Instant = evaluatedAt,
        departureAt: Instant = evaluatedAt,
        timingBasis: TransitLegTimingBasis = TransitLegTimingBasis.TIMETABLE,
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
                    timingBasis = timingBasis,
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
        secondWaitingMinutes: Int = 0,
        departureAt: Instant = evaluatedAt,
    ): TransitJourney = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = departureAt,
        departureAt = departureAt,
        arrivalAt = departureAt.plus(finalArrivalMinute, ChronoUnit.MINUTES),
        totalMinutes = finalArrivalMinute.toInt(),
        fetchedAt = evaluatedAt,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.BUS,
                durationMinutes = firstArrivalMinute.toInt(),
                waitingMinutes = 5,
                scheduledDepartureAt = departureAt,
                scheduledArrivalAt = departureAt.plus(firstArrivalMinute, ChronoUnit.MINUTES),
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
                scheduledDepartureAt = departureAt.plus(firstArrivalMinute, ChronoUnit.MINUTES),
                scheduledArrivalAt = departureAt.plus(
                    firstArrivalMinute + transferWalkMinutes,
                    ChronoUnit.MINUTES,
                ),
                timingBasis = TransitLegTimingBasis.TIMETABLE,
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = (finalArrivalMinute - secondDepartureMinute).toInt(),
                waitingMinutes = secondWaitingMinutes,
                scheduledDepartureAt = departureAt.plus(secondDepartureMinute, ChronoUnit.MINUTES),
                scheduledArrivalAt = departureAt.plus(finalArrivalMinute, ChronoUnit.MINUTES),
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

    private fun stubBusArrivals(routeName: String, vararg expectedAt: Instant) {
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = eq(routeName),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            expectedAt.map { busArrival(it).copy(routeName = routeName) }
        )
    }

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

    private fun mappedMaasRpProvider(fetchedAt: Instant): RecordingProvider {
        val search = TransitJourneySearchRequest(
            originLat = 37.5547,
            originLng = 126.9706,
            destinationLat = 37.4979,
            destinationLng = 127.0276,
            departureAt = evaluatedAt,
            maxTravelMinutes = 1_440,
        )
        // ODsay 필드/단위 그대로 입력한다. canonical 여정을 직접 만들며 TIMETABLE을 주입하지 않는다.
        val response = jacksonObjectMapper().readTree(
            """
                {"result":{"paths":{
                  "pathType":2,"totalTime":45,
                  "startDateTime":"202607290900","endDateTime":"202607290945",
                  "rps":[
                    {"trafficType":3,"duration":5,
                     "startDateTime":"202607290900","endDateTime":"202607290905"},
                    {"trafficType":2,"duration":32,"waitingTime":5,
                     "startDateTime":"202607290905","endDateTime":"202607290937",
                     "startID":"1001","startArsID":"02005","startName":"서울역버스환승센터",
                     "endID":"2001","endArsID":"22009","endName":"강남역",
                     "way":"강남역 방면","wayCode":2,
                     "lane":{"busNo":"402","busID":"bus-402"}},
                    {"trafficType":3,"duration":8,
                     "startDateTime":"202607290937","endDateTime":"202607290945"}
                  ]
                }}}
            """.trimIndent()
        )
        val journeys = OdsayTransitJourneyMapper().map(response, search, fetchedAt)
        assertEquals(TransitLegTimingBasis.ROUTE_ESTIMATE, journeys.single().rideLegs.single().timingBasis)
        return RecordingProvider(providerId = "odsay", result = journeys)
    }

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
