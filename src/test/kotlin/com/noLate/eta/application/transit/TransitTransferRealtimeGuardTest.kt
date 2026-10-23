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

    private fun plannedRoute(): EffectiveTransitRoute {
        val transfer = requireNotNull(journey().legs.last().timetable)
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
            evaluatedAt.plusSeconds(1800), 50, segments)
    }

    @Test
    fun `같은 환승 열차의 신선한 2분 지연만 시간표 모순으로 판정한다`() {
        stub(listOf(liveArrival()))

        assertTrue(guard.hasVerifiedLateTransfer(journey(), evaluatedAt))
    }

    @Test
    fun `환승 승차역을 지난 뒤 하차역의 동일 편 지연을 판정한다`() {
        val route = journey(transferBoardingAt = evaluatedAt.minusSeconds(5 * 60))
        whenever(arrivals.getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenReturn(listOf(liveArrival().copy(
                stationName = "신림역",
                expectedAt = evaluatedAt.plusSeconds(13 * 60).toString(),
            )))

        val observation = guard.observeLateTransfers(route, evaluatedAt).single()
        assertEquals(1, observation.legIndex)
        assertEquals(TransitRideObservationPoint.ALIGHTING, observation.point)
        assertEquals(evaluatedAt.plusSeconds(13 * 60), observation.expectedAt)
        verify(arrivals, times(0)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
        verify(arrivals, times(1)).getSubwayArrivalsForEta(eq("신림"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
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

        assertFalse(guard.hasVerifiedLateTransfer(route, evaluatedAt))
    }

    @Test
    fun `승차역이 조회 가능하면 같은 평가에서 하차역을 중복 조회하지 않는다`() {
        stub(emptyList())

        assertFalse(guard.hasVerifiedLateTransfer(journey(transferBoardingAt = evaluatedAt), evaluatedAt))
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
            base.copy(expectedAt = evaluatedAt.plusSeconds(10 * 60 + 60).toString()),
        ).forEach { row ->
            stub(listOf(row))
            assertFalse(guard.hasVerifiedLateTransfer(journey(), evaluatedAt))
        }
    }

    @Test
    fun `먼 미래 환승은 API를 조회하지 않고 지연이라고 추정하지 않는다`() {
        assertFalse(guard.hasVerifiedLateTransfer(journey(transferBoardingAt = evaluatedAt.plusSeconds(16 * 60)), evaluatedAt))
        verifyNoInteractions(arrivals)
    }

    @Test
    fun `도착 API 오류는 지연 증거가 아니며 같은 평가에서 재시도하지 않는다`() {
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenThrow(IllegalStateException("provider unavailable"))

        assertFalse(guard.hasVerifiedLateTransfer(journey(), evaluatedAt))
        verify(arrivals, times(1)).getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10))
    }

    @Test
    fun `작업 취소는 보통 조회 오류로 삼키지 않는다`() {
        whenever(arrivals.getSubwayArrivalsForEta(eq("사당"), eq("2호선"), anyOrNull(), eq("UP"), eq(10)))
            .thenThrow(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            guard.hasVerifiedLateTransfer(journey(), evaluatedAt)
        }
    }

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
