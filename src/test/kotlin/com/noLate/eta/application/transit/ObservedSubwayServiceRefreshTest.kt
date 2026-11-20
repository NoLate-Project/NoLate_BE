package com.noLate.eta.application.transit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.domain.*
import com.noLate.schedule.application.*
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CancellationException

/** 통제된 두 역 예보를 실제 guard→구간 계산→공개 상태 변환까지 연결한다. 실운행 정확도 표본은 아니다. */
class ObservedSubwayServiceRefreshTest {
    private val epoch = Instant.parse("2026-11-19T12:00:00Z")
    private fun at(seconds: Long) = epoch.plusSeconds(seconds)
    private val arrivals = mock<TransitArrivalService>()
    private val timetable = mock<TransitTimetableProvider>()
    private val guard = TransitTransferRealtimeGuard(arrivals)
    private val engine = PlannedTransitRouteRefresher(guard, TransitJourneyTimetableEnricher(listOf(timetable)),
        TransitTransferFeasibilityEvaluator())
    private var rows: List<TransitArrivalDto> = emptyList()

    init {
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer { call ->
            rows.filter { it.stationName == call.arguments[0] && it.lineName == call.arguments[1] }
        }
    }

    @Test
    fun `다른 실제 편은 두 역에서 같은 차량으로 확인한 뒤 원본 시간표와 분리해 선택한다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100), row("신림", "3202", 1600))
        val read = guard.observePlannedTransfers(original, at(800), emptyMap())
        assertEquals(1, read.additionalEndpointReads)
        assertEquals("3202", read.serviceCandidates.getValue(3).single().service.trainNumber)
        val update = requireNotNull(engine.refresh(original, at(800)))
        val route = requireNotNull(update.route)
        val timing = requireNotNull(route.segments[3].rideTiming)
        assertEquals("2202", timing.timetable.trainNumber)
        assertEquals("3202", timing.selectedTrainNumber())
        assertEquals(EffectiveTransitRideTimingBasis.OBSERVED_SUBWAY_SERVICE_REALTIME, timing.basis)
        assertEquals(at(1100), timing.predictedBoardingAt)
        assertEquals(at(1600), timing.predictedArrivalAt)
        assertEquals(at(1771), route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        assertEquals(original.segments[2], route.segments[2])
        assertEquals(original.segments[4], route.segments[4])
        assertFalse(update.onlyLastRideObserved)
        verifyNoInteractions(timetable)

        val snapshot = EffectiveTransitRouteSnapshot(accepted = false, inProgressPlannedRoute = original,
            plannedRouteUpdate = update)
        val live = requireNotNull(snapshot.plannedJourneyArrivalPrediction(at(800), 120))
        assertEquals(PlannedJourneyArrivalBasis.OBSERVED_SERVICE_CONNECTION, live.basis)
        assertEquals(route, live.effectiveTransitRoute)
        val stale = requireNotNull(snapshot.plannedJourneyArrivalPrediction(at(921), 120))
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, stale.basis)
        assertEquals("3202", stale.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.selectedTrainNumber())
        assertNull(stale.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.alightingSourceAt())
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        assertEquals(route, mapper.readValue(mapper.writeValueAsString(route), EffectiveTransitRoute::class.java))
        assertFalse(mapper.writeValueAsString(original).contains("observedService"))
    }

    @Test
    fun `각 구간을 먼저 갱신한 준비 시각으로 환승 가능한 실제 편을 고른다`() {
        rows = listOf(row("사당", "4012", 900, line = SeoulSubwayLine.LINE_4),
            row("사당", "3202", 1100), row("신림", "3202", 1600),
            row("사당", "3204", 1250), row("신림", "3204", 1680))
        val route = requireNotNull(engine.refresh(plan(), at(800))?.route)
        // 900 + 환승 도보 243 + 기존 여유 60 = 1203초. 1100초 편은 탈 수 없다.
        assertEquals(at(900), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals("3204", route.segments[3].rideTiming?.selectedTrainNumber())
        assertEquals(at(1851), route.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `양 끝 차량번호가 다르거나 최신 행이 충돌하면 연결하지 않는다`() {
        val valid = row("신림", "3202", 1600)
        val invalid = listOf(valid.copy(trainNumber = "3204"), valid.copy(direction = "상행"),
            valid.copy(destinationName = "신도림"), valid.copy(express = true),
            valid.copy(sourceUpdatedAt = at(679).toString()),
            valid.copy(arrivalStatus = TransitArrivalStatus.DEPARTED),
            valid.copy(arrivalStatus = TransitArrivalStatus.UNKNOWN),
            valid.copy(expectedAt = at(1000).toString()),
            valid.copy(waitSeconds = 799), valid.copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY))
        for (bad in invalid) {
            rows = listOf(row("사당", "3202", 1100), bad)
            assertNull(engine.refresh(plan(), at(800)), "invalid endpoint: $bad")
        }
        rows = listOf(row("사당", "3202", 1100), valid, valid.copy(direction = "상행"))
        assertNull(engine.refresh(plan(), at(800)))
        rows = listOf(row("사당", "3202", 1100), valid, valid.copy(expectedAt = at(1590).toString()))
        assertNull(engine.refresh(plan(), at(800)))
    }

    @Test
    fun `먼 하차역과 빈 예보와 정차 순서 없는 경로에는 보충 역을 조회하지 않는다`() {
        for (scenario in 0..2) {
            clearInvocations(arrivals)
            val original = plan()
            val p = if (scenario == 2) original.copy(segments = original.segments.map {
                if (it.sequence == 3) it.copy(orderedStopNames = emptyList()) else it
            }) else original
            rows = if (scenario == 1) emptyList() else listOf(row("사당", "3202", 1100))
            val read = guard.observePlannedTransfers(p, at(if (scenario == 0) 300 else 800), emptyMap())
            assertEquals(0, read.additionalEndpointReads)
            verify(arrivals, never()).getSubwayArrivalsForEta(eq("신림"), anyOrNull(), anyOrNull(), anyOrNull(), any())
        }
    }

    @Test
    fun `실제 선택 차량의 승차 예보만으로 원래 다른 편의 운행시간을 적용하지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100), row("신림", "3202", 1600))
        val selected = requireNotNull(engine.refresh(original, at(800))?.route)
        rows = listOf(row("사당", "3202", 1150, source = 810))
        assertNull(engine.refresh(selected, at(810), original))
        // 승차 시각 뒤 직접 하차 예보는 비교 시간표가 아니라 새로 선택한 실제 차량을 대조한다.
        rows = listOf(row("신림", "3202", 1680, source = 1200))
        val later = requireNotNull(engine.refresh(selected, at(1200), original)?.route)
        assertEquals("3202", later.segments[3].rideTiming?.selectedTrainNumber())
        assertEquals("2202", later.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1100), later.segments[3].rideTiming?.predictedBoardingAt)
        assertEquals(at(1851), later.arrivalAt)
    }

    @Test
    fun `상류 지연이 생기면 만료된 실제 편 예보로 미래 환승을 보장하지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100), row("신림", "3202", 1600))
        val selected = requireNotNull(engine.refresh(original, at(800))?.route)
        rows = listOf(row("사당", "4012", 1000, source = 960, line = SeoulSubwayLine.LINE_4))
        val result = requireNotNull(engine.refresh(selected, at(960), original))
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, result.failure)
        assertNull(result.route)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `첫 구간의 실제 편을 선택한 뒤 뒤쪽 시간표 환승도 같은 경로에서 다시 연결한다`() {
        val original = plan()
        rows = listOf(row("노원", "4014", 240, source = 100, line = SeoulSubwayLine.LINE_4),
            row("사당", "4014", 800, source = 100, line = SeoulSubwayLine.LINE_4))
        val nextTable = requireNotNull(original.segments[3].rideTiming).timetable.copy(trainNumber = "2204",
            boardingAt = at(1300), arrivalAt = at(1781), fetchedAt = at(100))
        whenever(timetable.findRides(any())).thenReturn(listOf(nextTable))
        val route = requireNotNull(engine.refresh(original, at(100))?.route)
        assertEquals("4014", route.segments[1].rideTiming?.selectedTrainNumber())
        assertEquals("4012", route.segments[1].rideTiming?.timetable?.trainNumber)
        assertEquals("2204", route.segments[3].rideTiming?.selectedTrainNumber())
        assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, route.segments[3].rideTiming?.basis)
        assertEquals(at(1952), route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        verify(timetable).findRides(check { assertEquals(at(1103), it.notBefore) })
    }

    @Test
    fun `다가오는 환승의 두 역 대조를 지난 구간의 대체 편 검색보다 우선한다`() {
        rows = listOf(row("노원", "4014", 850, line = SeoulSubwayLine.LINE_4),
            row("사당", "4014", 1000, line = SeoulSubwayLine.LINE_4),
            row("사당", "3202", 1100), row("신림", "3202", 1600))
        val result = guard.observePlannedTransfers(plan(), at(800), emptyMap())
        assertEquals(1, result.additionalEndpointReads)
        assertEquals(setOf(3), result.serviceCandidates.keys)
        verify(arrivals, times(3)).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(arrivals, never()).getSubwayArrivalsForEta(eq("노원"), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `상류 재계산으로 선택 편을 놓치면 다른 실제 편의 두 역 예보로 연결한다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100, source = 700), row("신림", "3202", 1600, source = 700))
        val selected = requireNotNull(engine.refresh(original, at(700))?.route)
        rows = listOf(row("사당", "4012", 1000, line = SeoulSubwayLine.LINE_4),
            row("사당", "3202", 1150), row("신림", "3202", 1600),
            row("사당", "3204", 1320), row("신림", "3204", 1680))
        val update = requireNotNull(engine.refresh(selected, at(800), original))
        assertNull(update.failure, "앞 구간 하차1000 + 도보243 + 여유60 이후에는1320편을 탈 수 있다")
        val route = requireNotNull(update.route)
        assertEquals("3204", route.segments[3].rideTiming?.selectedTrainNumber())
        assertEquals("2202", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1851), route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `선택 편이 최신 준비 시각으로도 가능하면 더 이른 다른 편으로 임의 변경하지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100, source = 700), row("신림", "3202", 1600, source = 700))
        val selected = requireNotNull(engine.refresh(original, at(700))?.route)
        rows = listOf(row("사당", "3202", 1150), row("신림", "3202", 1640),
            row("사당", "3204", 1000), row("신림", "3204", 1450))
        val route = requireNotNull(engine.refresh(selected, at(800), original)?.route)
        assertEquals("3202", route.segments[3].rideTiming?.selectedTrainNumber())
        assertEquals(at(1150), route.segments[3].rideTiming?.predictedBoardingAt)
        assertEquals(at(1811), route.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `선택 편의 새 예보가 환승 준비보다 빨라지면 이전의 늦은 승차 계획을 유지하지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100, source = 700), row("신림", "3202", 1600, source = 700))
        val selected = requireNotNull(engine.refresh(original, at(700))?.route)
        // 앞 구간513 + 도보243 + 여유60 = 816초. 새810초 예보는 더는 탈 수 없다.
        rows = listOf(row("사당", "3202", 810), row("신림", "3202", 1400))
        val update = requireNotNull(engine.refresh(selected, at(800), original))
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, update.failure)
        assertNull(update.route)
    }

    @Test
    fun `같은 실제 편을 놓친 새 예보가 오면 가능한 다음 시간표로 전체 도착을 다시 연결한다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1100, source = 700), row("신림", "3202", 1600, source = 700))
        val selected = requireNotNull(engine.refresh(original, at(700))?.route)
        rows = listOf(row("사당", "3202", 810), row("신림", "3202", 1400))
        val nextTable = requireNotNull(original.segments[3].rideTiming).timetable.copy(
            trainNumber = "2204", boardingAt = at(1200), arrivalAt = at(1681), fetchedAt = at(800))
        whenever(timetable.findRides(any())).thenReturn(listOf(nextTable))
        val route = requireNotNull(engine.refresh(selected, at(800), original)?.route)
        assertEquals("2204", route.segments[3].rideTiming?.selectedTrainNumber())
        assertNull(route.segments[3].rideTiming?.observedService)
        assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, route.segments[3].rideTiming?.basis)
        assertEquals(at(1852), route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        verify(timetable).findRides(check { assertEquals(at(816), it.notBefore) })
    }

    @Test
    fun `첫 실제 편이 접근 준비보다 빨라지면 경고하고 다음 신선한 쌍으로 회복한다`() {
        val base = plan()
        val original = base.copy(identity = "b".repeat(64), arrivalAt = at(684), totalMinutes = 11,
            segments = base.segments.take(2) + base.segments.last().copy(sequence = 2))
        rows = listOf(row("노원", "4014", 240, source = 50, line = SeoulSubwayLine.LINE_4),
            row("사당", "4014", 600, source = 50, line = SeoulSubwayLine.LINE_4))
        val selected = requireNotNull(engine.refresh(original, at(50))?.route)
        rows = listOf(row("노원", "4014", 200, source = 100, line = SeoulSubwayLine.LINE_4),
            row("사당", "4014", 650, source = 100, line = SeoulSubwayLine.LINE_4))
        val warning = requireNotNull(engine.refresh(selected, at(100), original))
        assertEquals(PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED, warning.failure)
        assertNull(warning.route)
        assertFalse(warning.requiresFirstBoardingRecheck)
        rows = listOf(row("노원", "4014", 230, source = 101, line = SeoulSubwayLine.LINE_4),
            row("사당", "4014", 650, source = 101, line = SeoulSubwayLine.LINE_4))
        val recovered = requireNotNull(engine.refresh(selected, at(101), original, warning)?.route)
        assertEquals("4014", recovered.segments[1].rideTiming?.selectedTrainNumber())
        assertEquals(at(821), recovered.arrivalAt)
        assertEquals(original.departureAt, recovered.departureAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `응답 오류 뒤에는 다른 역을 추가 호출하지 않고 취소는 전파한다`() {
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenThrow(IllegalStateException("provider unavailable"))
        assertNull(engine.refresh(plan(), at(800)))
        verify(arrivals, times(1)).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        reset(arrivals)
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenThrow(CancellationException())
        assertThrows(CancellationException::class.java) { engine.refresh(plan(), at(800)) }
    }

    @Test
    fun `실제 캡처의 오래 지난 첫 승차역에 보충 예산을 낭비하지 않는다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/endpoint-budget-starvation-20261120.json"))
            .use(mapper::readTree)
        val original = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val now = Instant.parse(input["evaluatedAt"].asText())
        rows = input["knownStations"].flatMap { batch -> batch["rows"].map { mapper.treeToValue(it, TransitArrivalDto::class.java) } } +
            mapper.treeToValue(input["syntheticSupplement"], TransitArrivalDto::class.java)
        // 신림 행은 실제로 요청되지 않았다. 합성 행은 예산 선택의 회귀에만 쓰며 정확도 표본이 아니다.
        val result = guard.observePlannedTransfers(original, now, emptyMap())
        assertEquals(1, result.additionalEndpointReads)
        assertEquals("2268", result.serviceCandidates.getValue(3).single().service.trainNumber)
        assertTrue(result.serviceCandidates[1].isNullOrEmpty())
        verify(arrivals, never()).getSubwayArrivalsForEta(eq("노원"), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(arrivals).getSubwayArrivalsForEta(eq("신림"), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(arrivals, times(3)).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        clearInvocations(arrivals)
        val update = requireNotNull(engine.refresh(original, now))
        assertEquals(original.departureAt, update.route?.departureAt)
        assertEquals("2268", update.route?.segments?.get(3)?.rideTiming?.selectedTrainNumber())
        assertEquals(Instant.parse("2026-11-20T06:45:19Z"), update.route?.arrivalAt)
    }

    @Test
    fun `승차역의 도착 상태를 보존하고 나중의 같은 실제 차량 하차 예보와 연결한다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1010, source = 1010).copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, observedAt = at(1036).toString()),
            row("신림", "3300", 1510, source = 1030))
        val pending = requireNotNull(engine.refresh(original, at(1036)))
        assertNull(pending.route)
        assertNull(pending.failure)
        assertEquals("3202", pending.reportedBoardings.single().service.trainNumber)
        assertEquals(at(1010), pending.reportedBoardings.single().sourceUpdatedAt)
        assertEquals(at(1036), pending.reportedBoardings.single().recordedAt)
        val waiting = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = original, plannedRouteUpdate = pending)
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            waiting.plannedJourneyArrivalPrediction(at(1036), 120)?.basis)
        clearInvocations(arrivals)

        // 230초 전 ARRIVED는 과거 기록이다. 현재 승차 예보가 신선한 것으로 취급하지 않는다.
        rows = listOf(row("신림", "3202", 1590, source = 1239))
        val update = requireNotNull(engine.refresh(original, at(1240), previousUpdate = pending))
        val route = requireNotNull(update.route)
        val timing = requireNotNull(route.segments[3].rideTiming)
        assertEquals(EffectiveTransitRideTimingBasis.REPORTED_BOARDING_AND_ALIGHTING_REALTIME, timing.basis)
        assertEquals("2202", timing.timetable.trainNumber)
        assertEquals("3202", timing.selectedTrainNumber())
        assertEquals(at(1010), timing.predictedBoardingAt)
        assertEquals(at(1590), timing.predictedArrivalAt)
        assertEquals(pending.reportedBoardings.single(), timing.reportedBoarding)
        assertEquals(at(1239), update.sourceUpdatedAt)
        assertEquals(at(1761), route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        assertThrows(IllegalArgumentException::class.java) { route.copy(identity = "b".repeat(64)) }
        assertEquals(original.segments.filter { it.kind == EffectiveTransitRouteSegmentKind.WALK },
            route.segments.filter { it.kind == EffectiveTransitRouteSegmentKind.WALK })
        verify(arrivals, times(3)).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verifyNoInteractions(timetable)
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        assertEquals(update, mapper.readValue(mapper.writeValueAsString(update), PlannedTransitRouteUpdate::class.java))
        val snapshot = waiting.copy(plannedRouteUpdate = update)
        assertEquals(PlannedJourneyArrivalBasis.OBSERVED_SERVICE_CONNECTION,
            snapshot.plannedJourneyArrivalPrediction(at(1240), 120)?.basis)
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN,
            snapshot.plannedJourneyArrivalPrediction(at(1360), 120)?.basis)
    }

    @Test
    fun `만료된 미래 승차 예보나 출발 상태는 ARRIVED 기록으로 바꾸지 않는다`() {
        val original = plan()
        val base = row("사당", "3202", 1010, source = 1010).copy(observedAt = at(1036).toString())
        for (bad in listOf(base, base.copy(arrivalStatus = TransitArrivalStatus.DEPARTED, waitSeconds = 0),
                base.copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, expectedAt = at(1100).toString()),
                base.copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, sourceUpdatedAt = at(800).toString(), expectedAt = at(800).toString()))) {
            rows = listOf(bad, row("신림", "3300", 1510, source = 1030))
            assertNull(engine.refresh(original, at(1036)))
        }
    }

    @Test
    fun `과거 도착 차량도 앞 구간 도착과 환승 여유보다 빠르면 연결하지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 800, source = 800).copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, observedAt = at(830).toString()),
            row("신림", "3202", 1450, source = 830))
        // 원래 도착513 + 도보243 + 여유60 =816. 800초에 게시된 차량을 탈 수 있다고 하지 않는다.
        val result = requireNotNull(engine.refresh(original, at(830)))
        assertNull(result.route)
        assertEquals(1, result.reportedBoardings.size)
        assertEquals(original.departureAt, plan().departureAt)
    }

    @Test
    fun `과거 기록과 다른 차량 또는 상충하거나 오래된 하차 예보는 연결하지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1010, source = 1010).copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, observedAt = at(1036).toString()),
            row("신림", "3300", 1510, source = 1030))
        val pending = requireNotNull(engine.refresh(original, at(1036)))
        val valid = row("신림", "3202", 1590, source = 1239)
        for (bad in listOf(listOf(valid.copy(trainNumber = "3204")), listOf(valid.copy(direction = "상행")),
                listOf(valid.copy(destinationName = "신도림")), listOf(valid.copy(express = true)),
                listOf(valid.copy(sourceUpdatedAt = at(1119).toString())),
                listOf(valid, valid.copy(expectedAt = at(1600).toString())),
                listOf(valid.copy(sourceUpdatedAt = at(1238).toString()), valid.copy(direction = "상행")))) {
            rows = bad
            assertNull(engine.refresh(original, at(1240), previousUpdate = pending)?.route)
        }
    }

    @Test
    fun `같은 도착 상태의 재게시로 과거 승차 마감을 뒤로 미루지 않는다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 800, source = 800).copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, observedAt = at(830).toString()),
            row("신림", "3202", 1450, source = 830))
        val pending = requireNotNull(engine.refresh(original, at(830)))
        rows = listOf(row("사당", "3202", 850, source = 850).copy(arrivalStatus = TransitArrivalStatus.ARRIVED, waitSeconds = 0, observedAt = at(870).toString()),
            row("신림", "3202", 1450, source = 870))
        val result = engine.refresh(original, at(870), previousUpdate = pending)
        assertNull(result?.route)
        assertEquals(at(800), (result ?: pending).reportedBoardings.single().sourceUpdatedAt)
    }

    @Test
    fun `하차 예보가 승차역 상태 게시보다 조금 먼저 발행됐어도 둘 다 확인 가능하고 신선하면 사용한다`() {
        val original = plan()
        rows = listOf(row("사당", "3202", 1010, source = 1010).copy(arrivalStatus = TransitArrivalStatus.ARRIVED,
            waitSeconds = 0, observedAt = at(1036).toString()), row("신림", "3202", 1590, source = 1000))
        val update = requireNotNull(engine.refresh(original, at(1036)))
        assertEquals(at(1000), update.sourceUpdatedAt)
        assertEquals(at(1010), update.route?.segments?.get(3)?.rideTiming?.predictedBoardingAt)
        assertEquals(at(1590), update.route?.segments?.get(3)?.rideTiming?.predictedArrivalAt)
        assertEquals(original.departureAt, update.route?.departureAt)
    }

    private fun row(station: String, train: String, expected: Long, source: Long = 800,
                    line: SeoulSubwayLine = SeoulSubwayLine.LINE_2) = TransitArrivalDto(
        provider = "seoul-openapi", kind = "SUBWAY", lineName = line.displayName, stationName = station,
        direction = "하행", destinationName = if (line == SeoulSubwayLine.LINE_2) "성수" else "오이도",
        expectedAt = at(expected).toString(), sourceUpdatedAt = at(source).toString(),
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
        arrivalStatus = TransitArrivalStatus.IN_TRANSIT, subwayLineId = line.realtimeId,
        trainNumber = train, express = false)

    private fun plan(): EffectiveTransitRoute {
        fun table(line: SeoulSubwayLine, train: String, boarding: Long, arrival: Long) = TransitLegTimetable(
            "seoul-timetable", line, LocalDate.parse("2026-11-19"), train, "DOWN", TransitServiceClass.LOCAL,
            if (line == SeoulSubwayLine.LINE_4) "N" else "S2", if (line == SeoulSubwayLine.LINE_4) "S4" else "L",
            if (line == SeoulSubwayLine.LINE_4) "오이도" else "성수", at(boarding), at(arrival), at(0))
        fun walk(sequence: Int, seconds: Long) = EffectiveTransitRouteSegment(sequence, EffectiveTransitRouteSegmentKind.WALK,
            ((seconds + 59) / 60).toInt(), durationSeconds = seconds)
        fun ride(sequence: Int, line: SeoulSubwayLine, train: String, from: String, to: String,
                 boarding: Long, arrival: Long, stops: List<String>) = EffectiveTransitRouteSegment(
            sequence, EffectiveTransitRouteSegmentKind.SUBWAY, if (sequence == 1) 7 else 13, lineName = line.displayName, fromName = from, toName = to,
            rideTiming = EffectiveTransitRideTiming(table(line, train, boarding, arrival), at(boarding), at(arrival),
                EffectiveTransitRideTimingBasis.TIMETABLE), orderedStopNames = stops)
        return EffectiveTransitRoute("odsay", "a".repeat(64), at(39), at(1652), 27, listOf(walk(0, 111),
            ride(1, SeoulSubwayLine.LINE_4, "4012", "노원", "사당", 210, 513, listOf("노원", "창동", "사당")),
            walk(2, 243), ride(3, SeoulSubwayLine.LINE_2, "2202", "사당", "신림", 1000, 1481,
                listOf("사당", "낙성대", "서울대입구", "봉천", "신림")), walk(4, 171)))
    }
}
