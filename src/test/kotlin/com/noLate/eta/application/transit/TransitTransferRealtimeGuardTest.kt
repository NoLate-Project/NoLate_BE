package com.noLate.eta.application.transit

import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitLegTimingBasis
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.EffectiveTransitRouteSegment
import com.noLate.schedule.application.EffectiveTransitRouteSegmentKind
import com.noLate.schedule.application.EffectiveTransitRideTiming
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CancellationException

class TransitTransferRealtimeGuardTest {
    private val evaluatedAt = Instant.parse("2026-10-20T12:00:00Z")
    private val arrivals = mock<TransitArrivalService>()
    private val guard = TransitTransferRealtimeGuard(arrivals)

    @ParameterizedTest
    @CsvSource("ARRIVED,36,0,0,4012,true", "ARRIVED,120,0,0,4012,true",
        "ARRIVED,121,0,0,4012,false", "DEPARTED,36,0,0,4012,false",
        "IN_TRANSIT,36,0,0,4012,false", "ARRIVED,36,10,0,4012,false",
        "ARRIVED,36,0,1,4012,false", "ARRIVED,36,0,0,4014,false")
    fun `출발 뒤 늦게 게시된 같은 편의 도착 상태는 지난 미래 예보와 구분한다`(
        status: TransitArrivalStatus, age: Long, wait: Int, expectedOffset: Long, train: String, accepted: Boolean,
    ) {
        val original = plannedRoute(evaluatedAt.plusSeconds(2400))
        val segment = original.segments.first()
        val table = requireNotNull(segment.rideTiming).timetable.copy(directionCode = "DOWN", terminalName = "오이도",
            boardingAt = evaluatedAt.minusSeconds(180), boardingStationArrivalAt = evaluatedAt.minusSeconds(210),
            arrivalAt = evaluatedAt.plusSeconds(1800))
        val plan = original.copy(segments = listOf(segment.copy(rideTiming = EffectiveTransitRideTiming(
            table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE))) + original.segments.drop(1))
        val source = evaluatedAt.minusSeconds(age)
        whenever(arrivals.getSubwayArrivalsForEta(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(liveArrival().copy(stationName = "노원", subwayLineId = "1004", lineName = "4호선",
                trainNumber = train, direction = "하행", destinationName = "오이도", arrivalStatus = status,
                waitSeconds = wait, sourceUpdatedAt = source.toString(), expectedAt = source.plusSeconds(expectedOffset).toString())))

        val observed = guard.observePlannedRideChanges(plan, evaluatedAt)

        assertEquals(accepted, observed.isNotEmpty())
        if (accepted) {
            assertTrue(observed.single().reportedBoardingArrival)
            assertEquals(source, observed.single().expectedAt)
            assertEquals(source, observed.single().sourceUpdatedAt)
        }
    }

    @ParameterizedTest
    @CsvSource("180,0,4012,true,true", "180,121,4012,false,true", "180,0,4014,false,true",
        "1200,0,4012,true,true", "1201,0,4012,false,false")
    fun `첫 열차가 예정 승차를 지나 지연돼도 신선한 같은 편의 승차역 예보를 확인한다`(
        elapsedSeconds: Long, sourceAgeSeconds: Long, train: String, accepted: Boolean, queried: Boolean,
    ) {
        val original = plannedRoute(evaluatedAt.plusSeconds(2400))
        val first = original.segments.first()
        val table = requireNotNull(first.rideTiming).timetable.copy(
            directionCode = "DOWN", terminalName = "오이도",
            boardingAt = evaluatedAt.minusSeconds(elapsedSeconds), arrivalAt = evaluatedAt.plusSeconds(1800),
        )
        val plan = original.copy(segments = listOf(first.copy(rideTiming = EffectiveTransitRideTiming(
            table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE,
        ))) + original.segments.drop(1))
        whenever(arrivals.getSubwayArrivalsForEta(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(liveArrival().copy(stationName = "노원", lineName = "4호선", subwayLineId = "1004",
                trainNumber = train, direction = "하행", destinationName = "오이도",
                expectedAt = evaluatedAt.plusSeconds(90).toString(),
                sourceUpdatedAt = evaluatedAt.minusSeconds(sourceAgeSeconds).toString())))

        val observed = guard.observePlannedRideChanges(plan, evaluatedAt)

        assertEquals(accepted, observed.isNotEmpty())
        if (accepted) {
            assertEquals(TransitRideObservationPoint.BOARDING, observed.single().point)
            assertEquals(0, observed.single().legIndex)
            assertEquals(evaluatedAt.plusSeconds(90), observed.single().expectedAt)
        }
        verify(arrivals, times(if (queried) 1 else 0)).getSubwayArrivalsForEta(
            eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10),
        )
    }

    @Test
    fun `긴 후속 구간도 예정 승차가 지연되면 같은 편의 승차역 예보를 확인한다`() {
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(emptyList())
        val original = plannedRoute()
        val last = original.segments.last()
        val table = requireNotNull(last.rideTiming).timetable.copy(directionCode = "DOWN", terminalName = "성수",
            boardingAt = evaluatedAt.minusSeconds(180), arrivalAt = evaluatedAt.plusSeconds(1800))
        val plan = original.copy(arrivalAt = table.arrivalAt.plusSeconds(300),
            segments = original.segments.dropLast(1) + last.copy(rideTiming = EffectiveTransitRideTiming(
                table, table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE,
            )))
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(liveArrival().copy(direction = "하행", destinationName = "성수",
                expectedAt = evaluatedAt.plusSeconds(90).toString())))

        val observed = guard.observePlannedRideChanges(plan, evaluatedAt)

        assertEquals(listOf(1), observed.map { it.legIndex })
        assertEquals(TransitRideObservationPoint.BOARDING, observed.single().point)
        verify(arrivals).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("DOWN"), eq(10))
    }

    @Test
    fun `계획 하차 시각을 5분 지난 첫 열차도 신선한 같은 편 관측으로 확인한다`() {
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(emptyList())
        val original = plannedRoute()
        val first = original.segments.first()
        val timing = requireNotNull(first.rideTiming)
        val plan = original.copy(segments = listOf(first.copy(rideTiming = timing.copy(timetable = timing.timetable.copy(
            directionCode = "DOWN", terminalName = "오이도")))) + original.segments.drop(1))
        val row = liveArrival().copy(stationName = "사당", lineName = "4호선", subwayLineId = "1004",
            trainNumber = "4012", direction = "하행", destinationName = "오이도",
            expectedAt = evaluatedAt.plusSeconds(180).toString())
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(row))

        val observed = guard.observePlannedRideChanges(plan, evaluatedAt)

        assertEquals(listOf(0), observed.map { it.legIndex })
        assertEquals(TransitRideObservationPoint.ALIGHTING, observed.single().point)
        assertEquals(evaluatedAt.plusSeconds(180), observed.single().expectedAt)
        verify(arrivals).getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10))
    }

    @Test
    fun `접근 출발 뒤 첫 승차 전에도 같은 열차의 승차역 관측을 조회한다`() {
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(emptyList())
        val original = plannedRoute()
        val first = original.segments.first()
        val table = requireNotNull(first.rideTiming).timetable.copy(directionCode = "DOWN", terminalName = "오이도",
            boardingAt = evaluatedAt.plusSeconds(180), arrivalAt = evaluatedAt.plusSeconds(1500))
        val plan = original.copy(segments = listOf(first.copy(rideTiming = EffectiveTransitRideTiming(table,
            table.boardingAt, table.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE))) + original.segments.drop(1))
        val row = liveArrival().copy(stationName = "노원", lineName = "4호선", subwayLineId = "1004",
            trainNumber = "4012", direction = "하행", destinationName = "오이도",
            expectedAt = evaluatedAt.plusSeconds(240).toString())
        whenever(arrivals.getSubwayArrivalsForEta(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(row))
        val observed = guard.observePlannedRideChanges(plan, evaluatedAt)
        assertEquals(listOf(0), observed.map { it.legIndex })
        assertEquals(TransitRideObservationPoint.BOARDING, observed.single().point)
        assertEquals(evaluatedAt.plusSeconds(240), observed.single().expectedAt)
        val seen = observed.single()
        val saved = EffectiveTransitRideTiming(table, seen.expectedAt,
            table.arrivalAt.plus(table.shiftFromObservedBoardingArrival(seen.expectedAt)),
            EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME, seen.sourceUpdatedAt)
        val carried = plan.copy(segments = listOf(first.copy(rideTiming = saved)) + plan.segments.drop(1))
        assertTrue(guard.observePlannedRideChanges(carried, evaluatedAt).isEmpty())
    }

    @Test
    fun `두 역이 같은 시각에 게시해도 첫 하차 관측은 채택하고 동일 하차 응답의 재사용은 막는다`() {
        val original = plannedRoute()
        val segment = original.segments.first()
        val timing = requireNotNull(segment.rideTiming)
        val table = timing.timetable.copy(directionCode = "DOWN", terminalName = "오이도")
        val source = evaluatedAt.minusSeconds(15)
        val boarding = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt,
            EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME, source)
        fun withFirst(value: EffectiveTransitRideTiming) = original.copy(
            segments = listOf(segment.copy(rideTiming = value)) + original.segments.drop(1))
        val row = liveArrival().copy(stationName = "사당", lineName = "4호선", subwayLineId = "1004",
            trainNumber = "4012", direction = "하행", destinationName = "오이도",
            sourceUpdatedAt = source.toString(), expectedAt = evaluatedAt.plusSeconds(180).toString())
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(row))
        assertEquals(TransitRideObservationPoint.ALIGHTING,
            guard.observePlannedRideChanges(withFirst(boarding), evaluatedAt).single().point)
        val both = boarding.copy(basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
            plannedBoardingIsStationArrival = true, boardingObservationSourceUpdatedAt = source)
        assertTrue(guard.observePlannedRideChanges(withFirst(both), evaluatedAt).isEmpty())
    }

    @Test
    fun `계획 관측도 오래된 원본 또는 다른 열차를 채택하지 않는다`() {
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(emptyList())
        val plan = plannedRoute()
        val rows = listOf(liveArrival().copy(trainNumber = "OTHER"),
            liveArrival().copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString()))
        stub(rows)
        assertTrue(guard.observePlannedRideChanges(plan, evaluatedAt).isEmpty())
    }

    @Test
    fun `출발 후 저장한 마지막 환승편만 직접 관측해 잠정 도착을 늦춘다`() {
        stub(listOf(liveArrival()))
        val route = plannedRoute()

        val observed = guard.observeLastPlannedRide(route, evaluatedAt)

        assertEquals(route.identity, observed?.routeIdentity)
        assertEquals(route.arrivalAt.plusSeconds(120), observed?.predictedArrivalAt)
        assertEquals(evaluatedAt, observed?.sourceUpdatedAt)
        verify(arrivals, times(1)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `출발 후에도 다른 편 또는 오래된 환승 관측은 반영하지 않는다`() {
        val route = plannedRoute()
        stub(listOf(liveArrival().copy(trainNumber = "2482")))
        assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt))
        stub(listOf(liveArrival().copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString())))
        assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt))
    }

    @Test
    fun `환승 없는 지하철도 승차 경계 이후 같은 열차 하차 시각만 보정한다`() {
        val boardingAt = evaluatedAt.minusSeconds(60)
        val baseline = plannedRoute(transferBoardingAt = boardingAt)
        val route = baseline.copy(segments = listOf(baseline.segments.last().copy(sequence = 0)))

        assertEquals(null, guard.observeLastPlannedRide(route, boardingAt))
        verifyNoInteractions(arrivals)

        val alightingAt = requireNotNull(route.segments.single().rideTiming).predictedArrivalAt.plusSeconds(120)
        val observation = liveArrival().copy(stationName = "신림역", expectedAt = alightingAt.toString())
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(observation.copy(trainNumber = "2482")), listOf(observation))

        assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt))
        val result = guard.observeLastPlannedRide(route, evaluatedAt)
        assertEquals(route.identity, result?.routeIdentity)
        assertEquals(alightingAt.plusSeconds(300), result?.predictedArrivalAt)
        verify(arrivals, times(2)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `새 하차 예보로 같은 열차의 남은 지연만 줄이고 시간표보다 앞당기지 않는다`() {
        val plan = plannedRoute(transferBoardingAt = evaluatedAt.minusSeconds(60))
        val last = plan.segments.last()
        val timetable = requireNotNull(last.rideTiming).timetable
        val delayedAt = timetable.arrivalAt.plusSeconds(45)
        val egressSeconds = 300L
        val previousSource = evaluatedAt.minusSeconds(30)
        val route = plan.copy(arrivalAt = delayedAt.plusSeconds(egressSeconds), segments = listOf(last.copy(
            rideTiming = requireNotNull(last.rideTiming).copy(predictedArrivalAt = delayedAt,
                basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
                observationSourceUpdatedAt = previousSource))))
        val recoveredAt = timetable.arrivalAt.plusSeconds(17).plusMillis(250)
        val forecast = liveArrival().copy(stationName = "신림역", expectedAt = recoveredAt.toString(),
            sourceUpdatedAt = evaluatedAt.minusSeconds(1).toString(), arrivalStatus = TransitArrivalStatus.IN_TRANSIT)
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))).thenReturn(
            listOf(forecast), listOf(forecast), listOf(forecast.copy(expectedAt = timetable.arrivalAt.minusSeconds(1).toString())),
            listOf(forecast.copy(trainNumber = "2482")), listOf(forecast.copy(sourceUpdatedAt = previousSource.toString())),
            listOf(forecast.copy(arrivalStatus = TransitArrivalStatus.UNKNOWN)),
            listOf(forecast, forecast.copy(direction = "하행")),
            listOf(forecast.copy(waitSeconds = 1)),
            listOf(forecast.copy(expectedAt = delayedAt.toString())),
        )

        assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt))
        val recovered = guard.observeLastPlannedRide(route, evaluatedAt, allowAlightingRecovery = true)
        assertEquals(recoveredAt.plusSeconds(egressSeconds), recovered?.predictedArrivalAt)
        assertEquals(evaluatedAt.minusSeconds(1), recovered?.sourceUpdatedAt)
        repeat(6) { assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt, allowAlightingRecovery = true)) }
        assertEquals(route.arrivalAt, guard.observeLastPlannedRide(route, evaluatedAt, allowAlightingRecovery = true)?.predictedArrivalAt)
        verify(arrivals, times(9)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `원래 시간표가 지나도 보정 시각까지 같은 열차의 신선한 하차 관측을 계속 확인한다`() {
        val baseline = plannedRoute(transferBoardingAt = evaluatedAt.minusSeconds(20 * 60))
        val segment = baseline.segments.last()
        val timing = requireNotNull(segment.rideTiming).copy(
            predictedArrivalAt = evaluatedAt.plusSeconds(180),
            basis = EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME,
            observationSourceUpdatedAt = evaluatedAt.minusSeconds(30),
        )
        val route = baseline.copy(
            arrivalAt = timing.predictedArrivalAt.plusSeconds(300),
            segments = baseline.segments.dropLast(1) + segment.copy(rideTiming = timing),
        )
        val observation = liveArrival().copy(
            stationName = "신림역", expectedAt = evaluatedAt.plusSeconds(300).toString(),
        )
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(observation.copy(sourceUpdatedAt = timing.observationSourceUpdatedAt.toString())), listOf(observation))

        assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt))
        assertEquals(evaluatedAt.plusSeconds(600), guard.observeLastPlannedRide(route, evaluatedAt)?.predictedArrivalAt)
        verify(arrivals, times(2)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `하차역 예측이 있는 계획에 승차역 지연을 다시 더하지 않는다`() {
        val baseline = plannedRoute()
        val segment = baseline.segments.last()
        val original = requireNotNull(segment.rideTiming)
        val timing = original.copy(
            predictedArrivalAt = original.predictedArrivalAt.plusSeconds(120),
            basis = EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME,
            observationSourceUpdatedAt = evaluatedAt.minusSeconds(30),
        )
        val route = baseline.copy(
            arrivalAt = baseline.arrivalAt.plusSeconds(120),
            segments = baseline.segments.dropLast(1) + segment.copy(rideTiming = timing),
        )

        assertEquals(null, guard.observeLastPlannedRide(route, evaluatedAt))
        verifyNoInteractions(arrivals)
    }

    private fun plannedRoute(transferBoardingAt: Instant = evaluatedAt.plusSeconds(600)): EffectiveTransitRoute {
        val transfer = requireNotNull(journey(transferBoardingAt).legs.last().timetable)
        val first = transfer.copy(line = SeoulSubwayLine.LINE_4, trainNumber = "4012",
            fromStationCode = "N", toStationCode = "S4", boardingAt = evaluatedAt.minusSeconds(600),
            arrivalAt = evaluatedAt.minusSeconds(300))
        val segments = listOf(first, transfer).mapIndexed { index, table ->
            EffectiveTransitRouteSegment(index, EffectiveTransitRouteSegmentKind.SUBWAY,
                if (index == 0) 5 else 15,
                fromName = if (index == 0) "노원" else "사당",
                toName = if (index == 0) "사당" else "신림",
                rideTiming = EffectiveTransitRideTiming(table, table.boardingAt, table.arrivalAt,
                    EffectiveTransitRideTimingBasis.TIMETABLE))
        }
        return EffectiveTransitRoute("odsay", "a".repeat(64), evaluatedAt.minusSeconds(1200),
            transfer.arrivalAt.plusSeconds(300), 50, segments)
    }

    @Test
    fun `같은 환승 열차의 신선한 2분 지연을 판정한다`() {
        stub(listOf(liveArrival()))

        assertTrue(hasVerifiedLateTransfer(journey(), evaluatedAt))
    }

    @Test
    fun `환승 승차역의 실시간 도착 지연은 시간표상 역 도착과 대조한다`() {
        val liveAt = evaluatedAt.plusSeconds(610)
        stub(listOf(liveArrival().copy(expectedAt = liveAt.toString())))
        val original = journey()
        val lastRide = original.legs.last()
        val timetable = requireNotNull(lastRide.timetable)
        val withDwell = timetable.copy(boardingStationArrivalAt = timetable.boardingAt.minusSeconds(20))
        val journey = original.copy(legs = original.legs.dropLast(1) + lastRide.copy(timetable = withDwell))
        assertEquals(liveAt, guard.observeTransferChanges(journey, evaluatedAt).single().expectedAt)

        val planned = plannedRoute()
        val lastSegment = planned.segments.last()
        val timing = requireNotNull(lastSegment.rideTiming).copy(timetable = withDwell)
        val route = planned.copy(segments = planned.segments.dropLast(1) + lastSegment.copy(rideTiming = timing))
        assertEquals(route.arrivalAt.plusSeconds(30), guard.observeLastPlannedRide(route, evaluatedAt)?.predictedArrivalAt)
    }

    @Test
    fun `별도 역 도착 시간표가 있으면 신선한 동일 환승편의 조기 도착도 반환한다`() {
        val liveAt = evaluatedAt.plusSeconds(550)
        stub(listOf(liveArrival().copy(expectedAt = liveAt.toString())))
        val original = journey()
        assertTrue(guard.observeTransferChanges(original, evaluatedAt).isEmpty())

        val ride = original.legs.last()
        val table = requireNotNull(ride.timetable).copy(boardingStationArrivalAt = evaluatedAt.plusSeconds(580))
        val withArrivalClock = original.copy(legs = original.legs.dropLast(1) + ride.copy(timetable = table))
        val observation = guard.observeTransferChanges(withArrivalClock, evaluatedAt).single()
        assertTrue(observation.earlyBoardingArrival)
        assertEquals(liveAt, observation.expectedAt)
        assertEquals(TransitRideObservationPoint.BOARDING, observation.point)
        verify(arrivals, times(2)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `환승 승차역을 지난 뒤 하차역의 동일 편 지연을 판정한다`() {
        val route = journey(transferBoardingAt = evaluatedAt.minusSeconds(5 * 60))
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(liveArrival().copy(
                stationName = "신림역",
                expectedAt = evaluatedAt.plusSeconds(13 * 60).toString(),
            )))

        val observation = guard.observeTransferChanges(route, evaluatedAt).single()
        assertEquals(1, observation.legIndex)
        assertEquals(TransitRideObservationPoint.ALIGHTING, observation.point)
        assertEquals(evaluatedAt.plusSeconds(13 * 60), observation.expectedAt)
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(1)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `승차 시각을 30초 지나면 이전 역 대신 하차역을 한 번 조회한다`() {
        val boardingAt = evaluatedAt.minusSeconds(30)
        val stationArrival = evaluatedAt.plusSeconds(900)
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(liveArrival().copy(stationName = "신림역", expectedAt = stationArrival.toString())))

        val transfer = guard.observeTransferChanges(journey(transferBoardingAt = boardingAt), evaluatedAt).single()
        assertEquals(TransitRideObservationPoint.ALIGHTING, transfer.point)
        assertEquals(stationArrival, transfer.expectedAt)

        val planned = guard.observeLastPlannedRide(plannedRoute(boardingAt), evaluatedAt)
        assertEquals(stationArrival.plusSeconds(300), planned?.predictedArrivalAt)
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(2)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `자정을 넘는 운행일의 동일 환승편을 신선한 하차 관측으로 보정한다`() {
        val afterMidnight = Instant.parse("2026-10-20T15:01:00Z")
        val boardingAt = afterMidnight.minusSeconds(120)
        val alightingAt = boardingAt.plusSeconds(900)
        val delayedAt = alightingAt.plusSeconds(120)
        val arrival = liveArrival().copy(
            stationName = "신림역", expectedAt = delayedAt.toString(), sourceUpdatedAt = afterMidnight.toString(),
        )
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(arrival.copy(trainNumber = "2482")), listOf(arrival), listOf(arrival))

        val overnight = journey(boardingAt)
        assertTrue(guard.observeTransferChanges(overnight, afterMidnight).isEmpty())
        val observed = guard.observeTransferChanges(overnight, afterMidnight).single()
        assertEquals(TransitRideObservationPoint.ALIGHTING, observed.point)
        assertEquals(delayedAt, observed.expectedAt)

        val planned = plannedRoute(boardingAt)
        assertEquals(planned.arrivalAt.plusSeconds(120), guard.observeLastPlannedRide(planned, afterMidnight)?.predictedArrivalAt)
        verify(arrivals, times(3)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `자정 후 출발한 일정도 전날 운행편의 신선한 하차 예측을 받는다`() {
        val now = Instant.parse("2026-10-20T15:15:00Z")
        val base = plannedRoute(now.minusSeconds(270))
        val route = base.copy(departureAt = now.minusSeconds(540),
            segments = listOf(base.segments.last().copy(sequence = 0)))
        val timing = requireNotNull(route.segments.single().rideTiming)
        val expected = timing.predictedArrivalAt.plusSeconds(75)
        val row = liveArrival().copy(stationName = "신림역", sourceUpdatedAt = now.toString(),
            expectedAt = expected.toString())
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(row.copy(trainNumber = "2482")), listOf(row))

        assertEquals(null, guard.observeLastPlannedRide(route, now))
        assertEquals(route.arrivalAt.plusSeconds(75), guard.observeLastPlannedRide(route, now)?.predictedArrivalAt)
        verify(arrivals, times(2)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `자정 전 하차로 끝난 전날 편과 27시 이후 편의 번호는 재조회하지 않는다`() {
        val afterMidnight = Instant.parse("2026-10-20T15:01:00Z")
        val beforeMidnightArrival = plannedRoute(afterMidnight.minusSeconds(990))
        val afterOvernightWindow = Instant.parse("2026-10-20T18:01:00Z")
        val outsideServiceWindow = plannedRoute(afterOvernightWindow.minusSeconds(120))

        assertEquals(null, guard.observeLastPlannedRide(beforeMidnightArrival, afterMidnight))
        assertEquals(null, guard.observeLastPlannedRide(outsideServiceWindow, afterOvernightWindow))
        verifyNoInteractions(arrivals)
    }

    @Test
    fun `첫 하차역 근거는 조회 창 안에서 같은 첫 열차의 신선한 응답만 받는다`() {
        val original = journey()
        val first = original.legs.first()
        val table = requireNotNull(plannedRoute().segments.first().rideTiming).timetable.copy(
            directionCode = "DOWN", terminalName = "오이도", boardingAt = evaluatedAt.plusSeconds(120),
            arrivalAt = evaluatedAt.plusSeconds(300),
        )
        val firstRide = first.copy(
            scheduledDepartureAt = table.boardingAt, scheduledArrivalAt = table.arrivalAt,
            to = TransitStop(name = "사당"), timingBasis = TransitLegTimingBasis.TIMETABLE, timetable = table,
        )
        val route = original.copy(legs = listOf(firstRide) + original.legs.drop(1))
        val expectedAt = evaluatedAt.plusSeconds(320)
        val arrival = liveArrival().copy(
            direction = "하행", destinationName = "오이도행", subwayLineId = "1004",
            trainNumber = "4012", expectedAt = expectedAt.toString(),
        )
        val distantArrival = evaluatedAt.plusSeconds(901)
        val outsideWindow = route.copy(legs = listOf(firstRide.copy(
            scheduledArrivalAt = distantArrival,
            timetable = table.copy(arrivalAt = distantArrival),
        )) + original.legs.drop(1))
        assertEquals(null, guard.observeFirstRideAlighting(outsideWindow, table.boardingAt, evaluatedAt))
        verifyNoInteractions(arrivals)
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(arrival.copy(trainNumber = "4014"),
                arrival.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString())), listOf(arrival))

        assertEquals(null, guard.observeFirstRideAlighting(route, table.boardingAt, evaluatedAt))
        assertEquals(expectedAt, guard.observeFirstRideAlighting(route, table.boardingAt, evaluatedAt)?.expectedAt)
        verify(arrivals, times(2)).getSubwayArrivalsForEta(eq("사당"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10))
    }

    @Test
    fun `하차역의 다른 열차나 오래된 예측은 지연 증거가 아니다`() {
        val route = journey(transferBoardingAt = evaluatedAt.minusSeconds(5 * 60))
        val lateAtDestination = liveArrival().copy(
            stationName = "신림역",
            expectedAt = evaluatedAt.plusSeconds(13 * 60).toString(),
        )
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(
                lateAtDestination.copy(trainNumber = "2482"),
                lateAtDestination.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString()),
            ))

        assertFalse(hasVerifiedLateTransfer(route, evaluatedAt))
    }

    @Test
    fun `승차역이 조회 가능하면 같은 평가에서 하차역을 중복 조회하지 않는다`() {
        stub(emptyList())

        assertFalse(hasVerifiedLateTransfer(journey(transferBoardingAt = evaluatedAt), evaluatedAt))
        verify(arrivals, times(1)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `다른 편 방향 종착 종별 또는 오래된 응답은 환승 지연의 증거가 아니다`() {
        val base = liveArrival()
        listOf(
            base.copy(trainNumber = "2482"),
            base.copy(direction = "외선"),
            base.copy(destinationName = "신도림"),
            base.copy(express = true),
            base.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString()),
            base.copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY),
            base.copy(arrivalStatus = TransitArrivalStatus.DEPARTED),
            base.copy(expectedAt = evaluatedAt.plusSeconds(10 * 60).toString()),
        ).forEach { row ->
            stub(listOf(row))
            assertFalse(hasVerifiedLateTransfer(journey(), evaluatedAt))
        }
    }

    @Test
    fun `먼 미래 환승은 API를 조회하지 않고 지연이라고 추정하지 않는다`() {
        assertFalse(hasVerifiedLateTransfer(journey(transferBoardingAt = evaluatedAt.plusSeconds(16 * 60)), evaluatedAt))
        verifyNoInteractions(arrivals)
    }

    @Test
    fun `도착 API 오류는 지연 증거가 아니며 같은 평가에서 재시도하지 않는다`() {
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenThrow(IllegalStateException("provider unavailable"))

        assertFalse(hasVerifiedLateTransfer(journey(), evaluatedAt))
        verify(arrivals, times(1)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `작업 취소는 보통 조회 오류로 삼키지 않는다`() {
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            hasVerifiedLateTransfer(journey(), evaluatedAt)
        }
    }

    private fun hasVerifiedLateTransfer(journey: TransitJourney, evaluatedAt: Instant): Boolean =
        guard.observeTransferChanges(journey, evaluatedAt).any { !it.earlyBoardingArrival }

    private fun stub(rows: List<TransitArrivalDto>) {
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(rows)
    }

    private fun liveArrival() = TransitArrivalDto(
        provider = "seoul-openapi",
        kind = "SUBWAY",
        stationName = "사당역",
        direction = "내선",
        destinationName = "성수종착",
        expectedAt = evaluatedAt.plusSeconds(12 * 60).toString(),
        sourceUpdatedAt = evaluatedAt.toString(),
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
        arrivalStatus = TransitArrivalStatus.IN_TRANSIT,
        subwayLineId = "1002",
        trainNumber = "2480",
        express = false,
    )

    private fun journey(transferBoardingAt: Instant = evaluatedAt.plusSeconds(10 * 60)): TransitJourney {
        val transferArrivalAt = transferBoardingAt.plusSeconds(15 * 60)
        val firstDepartureAt = minOf(evaluatedAt, transferBoardingAt.minusSeconds(5 * 60))
        return TransitJourney(
            provider = "odsay",
            requestedDepartureAt = firstDepartureAt,
            departureAt = firstDepartureAt,
            arrivalAt = transferArrivalAt,
            totalMinutes = java.time.Duration.between(firstDepartureAt, transferArrivalAt).toMinutes().toInt(),
            fetchedAt = evaluatedAt,
            legs = listOf(
                TransitJourneyLeg(
                    sequence = 0,
                    mode = TransitLegMode.SUBWAY,
                    durationMinutes = 5,
                    scheduledDepartureAt = firstDepartureAt,
                    scheduledArrivalAt = firstDepartureAt.plusSeconds(5 * 60),
                ),
                TransitJourneyLeg(
                    sequence = 1,
                    mode = TransitLegMode.SUBWAY,
                    durationMinutes = 15,
                    scheduledDepartureAt = transferBoardingAt,
                    scheduledArrivalAt = transferArrivalAt,
                    from = TransitStop(name = "사당"),
                    to = TransitStop(name = "신림"),
                    timingBasis = TransitLegTimingBasis.TIMETABLE,
                    timetable = TransitLegTimetable(
                        provider = "seoul-timetable",
                        line = SeoulSubwayLine.LINE_2,
                        serviceDate = LocalDate.parse("2026-10-20"),
                        trainNumber = "2480",
                        directionCode = "UP",
                        serviceClass = TransitServiceClass.LOCAL,
                        fromStationCode = "020226",
                        toStationCode = "020230",
                        terminalName = "성수",
                        boardingAt = transferBoardingAt,
                        arrivalAt = transferArrivalAt,
                        fetchedAt = evaluatedAt,
                    ),
                ),
            ),
        )
    }
}
