package com.noLate.eta.application.transit

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import com.noLate.schedule.application.*
import com.noLate.schedule.domain.PlannedJourneyArrivalBasis
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.*
import java.time.Instant
import java.time.LocalDate

class PlannedTransitRouteRefresherTest {
    private val epoch = Instant.parse("2026-11-19T12:00:00Z")
    private val guard = mock<TransitTransferRealtimeGuard>()
    private val timetable = mock<TransitTimetableProvider>()
    private val refresher = PlannedTransitRouteRefresher(guard,
        TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
    private fun at(seconds: Long) = epoch.plusSeconds(seconds)

    @Test
    fun `게시된 승차역 상태 뒤 직접 하차 예보가 오면 첫 지연을 중복 합산하지 않는다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        fun input(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use(mapper::readTree)
        val first = input("/eta/reported-boarding-arrival-20261120.json")
        val next = input("/eta/reported-boarding-then-alighting-20261120.json")
        val original = mapper.treeToValue(first["plan"], EffectiveTransitRoute::class.java)
        var feed = first["station"]["rows"].map { mapper.treeToValue(it, TransitArrivalDto::class.java) }
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer { call ->
            feed.filter { it.stationName == call.arguments[0] && it.lineName == call.arguments[1] }
        }
        val query = mapper.treeToValue(next["timetables"][0]["query"], TransitTimetableQuery::class.java)
        val onward = next["timetables"][0]["rides"].map { mapper.treeToValue(it, TransitLegTimetable::class.java) }
        whenever(timetable.findRides(eq(query))).thenReturn(onward)
        val engine = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        val initialUpdate = requireNotNull(engine.refresh(original, Instant.parse(first["evaluatedAt"].asText())))
        val initialRoute = requireNotNull(initialUpdate.route)
        assertEquals(Instant.parse("2026-11-20T02:46:55Z"), initialRoute.segments[1].rideTiming?.predictedArrivalAt)

        feed = next["stations"].flatMap { station -> station["rows"].map {
            mapper.treeToValue(it, TransitArrivalDto::class.java)
        } }
        val refreshed = requireNotNull(engine.refresh(initialRoute, Instant.parse(next["evaluatedAt"].asText()),
            departurePlan = original, previousUpdate = initialUpdate))
        val changed = requireNotNull(refreshed.route)

        assertEquals(original.departureAt, changed.departureAt)
        assertEquals(Instant.parse("2026-11-20T02:48:05Z"), changed.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(Instant.parse("2026-11-20T01:58:25Z"), changed.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals("2202", changed.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(Instant.parse("2026-11-20T03:05:43Z"), changed.arrivalAt)
        verify(timetable).findRides(eq(query))
    }

    @Test
    fun `4075편의 신선한 도착 상태를 원래 출발 계획의 구간과 환승 대기에 반영한다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/reported-boarding-arrival-20261120.json"))
            .use(mapper::readTree)
        val route = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val evaluated = Instant.parse(input["evaluatedAt"].asText())
        val rows = input["station"]["rows"].map { mapper.treeToValue(it, TransitArrivalDto::class.java) }
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(rows)
        val engine = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())

        val update = requireNotNull(engine.refresh(route, evaluated))
        val changed = requireNotNull(update.route)
        val first = requireNotNull(changed.segments[1].rideTiming)

        assertEquals(route.departureAt, changed.departureAt)
        assertEquals("4075", first.timetable.trainNumber)
        assertEquals(Instant.parse("2026-11-20T01:58:25Z"), first.predictedBoardingAt)
        assertEquals(Instant.parse("2026-11-20T02:46:55Z"), first.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME, first.basis)
        assertEquals(Instant.parse("2026-11-20T01:58:25Z"), update.sourceUpdatedAt)
        assertEquals("2200", changed.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(88, changed.segments[3].transferTiming?.let {
            java.time.Duration.between(it.physicalReadyAt, it.boardingAt).seconds.toInt()
        })
        assertEquals(route.arrivalAt, changed.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `예정 승차를 2분 넘게 지난 첫 열차의 새 예보로 환승과 최종 도착을 갱신한다`() {
        val baseline = plan()
        val first = requireNotNull(baseline.segments[1].rideTiming)
        val next = requireNotNull(baseline.segments[3].rideTiming)
        val plan = baseline.copy(arrivalAt = at(4052), totalMinutes = 67,
            segments = baseline.segments.map { segment -> when (segment.sequence) {
                1 -> segment.copy(rideTiming = first.copy(timetable = first.timetable.copy(arrivalAt = at(3000)),
                    predictedArrivalAt = at(3013)))
                3 -> segment.copy(rideTiming = next.copy(timetable = next.timetable.copy(
                    boardingAt = at(3400), arrivalAt = at(3881)),
                    predictedBoardingAt = at(3400), predictedArrivalAt = at(3881)))
                else -> segment
            } })
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10)))
            .thenReturn(listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "노원",
                direction = "하행", destinationName = "오이도", subwayLineId = "1004", trainNumber = "4012",
                express = false, realtime = true, expectedAt = at(600).toString(), sourceUpdatedAt = at(400).toString(),
                arrivalStatus = TransitArrivalStatus.IN_TRANSIT,
                freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)))
        whenever(timetable.findRides(any())).thenReturn(listOf(next.timetable.copy(trainNumber = "2014",
            boardingAt = at(3800), arrivalAt = at(4281))))
        val engine = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())

        val update = requireNotNull(engine.refresh(plan, at(400)))
        val route = requireNotNull(update.route)

        assertEquals(plan.departureAt, route.departureAt)
        assertEquals(at(600), route.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals(at(3403), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals("2014", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(4452), route.arrivalAt)
        assertEquals(at(400), update.sourceUpdatedAt)
        verify(timetable).findRides(argThat { notBefore == at(3706) })
        verify(arrivals).getSubwayArrivalsForEta(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), eq(10))
    }

    @Test
    fun `하차 지연 회복과 원래 환승편을 같은 역 응답에서 확인하면 원래 편을 복구한다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/planned-transfer-recovery-synthetic.json")).use(mapper::readTree)
        val original = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val originalTrain = requireNotNull(original.segments[3].rideTiming).timetable
        val laterTrain = mapper.treeToValue(input["alternateTrain"], TransitLegTimetable::class.java)
        val late = mapper.treeToValue(input["stationRows"][0][0], TransitArrivalDto::class.java)
        val (recovered, transfer) = input["stationRows"][1].map { mapper.treeToValue(it, TransitArrivalDto::class.java) }
        var feed = listOf(late)
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            val line = if (it.arguments[1] == "4호선") "1004" else "1002"
            feed.filter { dto -> dto.stationName == it.arguments[0] && dto.subwayLineId == line }
        }
        whenever(timetable.findRides(any())).thenReturn(listOf(laterTrain))
        val service = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        val delayed = requireNotNull(service.refresh(original, at(450))?.route)
        assertEquals("2014", delayed.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1952), delayed.arrivalAt)
        clearInvocations(arrivals, timetable)

        fun outcome(rows: List<TransitArrivalDto>, saved: EffectiveTransitRoute = original): EffectiveTransitRoute {
            feed = rows
            return service.refresh(delayed, at(470), saved)?.route ?: delayed
        }
        val updated = outcome(listOf(recovered, transfer))
        val connection = requireNotNull(updated.segments[3].rideTiming)
        assertEquals("2012", connection.timetable.trainNumber)
        assertEquals(at(1010), connection.predictedBoardingAt)
        assertEquals(at(469), connection.boardingSourceAt())
        assertEquals(at(1662), updated.arrivalAt)
        assertEquals(original.departureAt, updated.departureAt)
        verify(arrivals, times(1)).getSubwayArrivalsForEta("사당", "2호선", null, "DOWN", 10)
        verify(arrivals, times(1)).getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10)
        verifyNoMoreInteractions(arrivals)
        verifyNoInteractions(timetable)

        // 같은 응답 안의 원래 환승편이 조기 도착해도, 도보와 승차 여유까지 확보했으면 복구한다.
        val withStationArrival = original.copy(segments = original.segments.map { seg ->
            if (seg.sequence != 3) seg else seg.copy(rideTiming = requireNotNull(seg.rideTiming).copy(
                timetable = originalTrain.copy(boardingStationArrivalAt = at(970))))
        })
        val earlyAtReady = transfer.copy(expectedAt = at(953).toString())
        val earlyRoute = outcome(listOf(recovered, earlyAtReady), withStationArrival)
        val earlyRide = requireNotNull(earlyRoute.segments[3].rideTiming)
        assertEquals("2012", earlyRide.timetable.trainNumber)
        assertEquals(at(953), earlyRide.predictedBoardingAt)
        assertEquals(EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING, earlyRide.basis)
        assertEquals(originalTrain.arrivalAt, earlyRide.predictedArrivalAt)
        assertEquals(at(469), earlyRide.boardingSourceAt())
        assertEquals(at(1652), earlyRoute.arrivalAt)
        assertEquals("2014", outcome(listOf(recovered, transfer.copy(expectedAt = at(952).toString())),
            withStationArrival).segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals("2014", outcome(listOf(recovered, earlyAtReady)).segments[3].rideTiming?.timetable?.trainNumber)

        for (rows in listOf(listOf(recovered), listOf(transfer),
            listOf(recovered, transfer.copy(destinationName = "다른 종착")),
            listOf(recovered, transfer.copy(sourceUpdatedAt = at(349).toString())),
            listOf(recovered, transfer, transfer.copy(arrivalStatus = TransitArrivalStatus.DEPARTED, sourceUpdatedAt = at(470).toString())),
            listOf(recovered.copy(expectedAt = at(710).toString()), transfer))) {
            assertEquals("2014", outcome(rows).segments[3].rideTiming?.timetable?.trainNumber)
        }
        val otherDay = original.copy(segments = original.segments.map { if (it.sequence != 3) it else it.copy(
            rideTiming = requireNotNull(it.rideTiming).copy(timetable = originalTrain.copy(serviceDate = originalTrain.serviceDate.minusDays(1)))) })
        assertEquals("2014", outcome(listOf(recovered, transfer), otherDay).segments[3].rideTiming?.timetable?.trainNumber)
        val fromBothStations = EffectiveTransitRideTiming(originalTrain, at(1010), at(1535),
            EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, at(435),
            plannedBoardingIsStationArrival = true, boardingObservationSourceUpdatedAt = at(448))
        val evidenced = original.copy(arrivalAt = at(1706), totalMinutes = 28, segments = original.segments.map {
            if (it.sequence == 3) it.copy(rideTiming = fromBothStations) else it
        })
        val older = transfer.copy(sourceUpdatedAt = at(445).toString())
        assertEquals("2014", outcome(listOf(recovered, older), evidenced).segments[3].rideTiming?.timetable?.trainNumber)
        val restored = outcome(listOf(recovered, transfer), evidenced)
        assertEquals(at(1706), restored.arrivalAt)
        assertEquals(at(435), restored.segments[3].rideTiming?.alightingSourceAt())
        assertEquals(at(469), restored.segments[3].rideTiming?.boardingSourceAt())

        val waiting = outcome(listOf(recovered))
        feed = listOf(transfer.copy(sourceUpdatedAt = at(489).toString()))
        val nextRead = requireNotNull(service.refresh(waiting, at(490), original))
        assertEquals("2012", nextRead.route?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(at(469), nextRead.sourceUpdatedAt)
        feed = listOf(transfer.copy(sourceUpdatedAt = at(590).toString()))
        assertNull(service.refresh(waiting, at(590), original))
        verifyNoInteractions(timetable)
    }

    @ParameterizedTest
    @CsvSource(
        "1370,IN_TRANSIT,1025,2012",
        "1352,IN_TRANSIT,1025,2014",
        "1370,ARRIVED,1025,2014",
        "1370,UNKNOWN,1025,2014",
        "1370,IN_TRANSIT,909,2014",
    )
    fun `예정 승차시각이 지난 원래 환승편도 신선한 미래 예보로 탈 수 있을 때만 복구한다`(
        arrival: Long, status: TransitArrivalStatus, source: Long, expectedTrain: String,
    ) {
        val original = plan()
        val second = requireNotNull(original.segments[3].rideTiming).timetable
        val alternate = second.copy(trainNumber = "2014", boardingAt = at(1500), arrivalAt = at(1981))
        val active = original.copy(arrivalAt = at(2152), totalMinutes = 36, segments = original.segments.map {
            if (it.sequence == 3) it.copy(rideTiming = EffectiveTransitRideTiming(alternate, alternate.boardingAt,
                alternate.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE)) else it
        })
        val evaluatedAt = at(1030)
        val arrivals = mock<TransitArrivalService>()
        val inbound = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "사당",
            direction = "하행", destinationName = "오이도", subwayLineId = "1004", trainNumber = "4012",
            express = false, expectedAt = at(1050).toString(), sourceUpdatedAt = at(1025).toString(),
            arrivalStatus = TransitArrivalStatus.IN_TRANSIT,
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
        val transfer = inbound.copy(subwayLineId = "1002", destinationName = "성수", trainNumber = "2012",
            expectedAt = at(arrival).toString(), sourceUpdatedAt = at(source).toString(), arrivalStatus = status)
        whenever(arrivals.getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10)).thenReturn(listOf(inbound))
        whenever(arrivals.getSubwayArrivalsForEta("사당", "2호선", null, "DOWN", 10)).thenReturn(listOf(transfer))
        val engine = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())

        val update = requireNotNull(engine.refresh(active, evaluatedAt, departurePlan = original))
        val route = requireNotNull(update.route)
        val expectedBoarding = if (expectedTrain == "2012") at(1370) else at(1500)
        assertEquals(expectedTrain, route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(expectedBoarding, route.segments[3].rideTiming?.predictedBoardingAt)
        assertEquals(if (expectedTrain == "2012") at(2022) else at(2152), route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        val timing = requireNotNull(route.segments[3].transferTiming)
        assertEquals(243L, timing.walkingSeconds)
        assertEquals(60L, timing.requiredMarginSeconds)
        assertEquals(17L.takeIf { expectedTrain == "2012" } ?: 147L, timing.remainingSlackSeconds)
        verify(arrivals).getSubwayArrivalsForEta("사당", "2호선", null, "DOWN", 10)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `이미 지난 앞 열차의 하차 관측으로 다음 회차의 환승 예보를 다시 검증한다`() {
        val original = plan().let { route -> route.copy(segments = route.segments.map { segment ->
            if (segment.sequence == 1) segment.copy(orderedStopNames = listOf("노원", "창동", "사당")) else segment
        }) }
        val originalConnection = requireNotNull(original.segments[3].rideTiming).timetable
        val replacement = originalConnection.copy(trainNumber = "2014", boardingAt = at(1500), arrivalAt = at(1981))
        val planned = original.copy(arrivalAt = at(2152), totalMinutes = 36, segments = original.segments.map {
            if (it.sequence == 3) it.copy(rideTiming = EffectiveTransitRideTiming(replacement, replacement.boardingAt,
                replacement.arrivalAt, EffectiveTransitRideTimingBasis.TIMETABLE)) else it
        })
        val reported = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "사당",
            direction = "하행", destinationName = "오이도", subwayLineId = "1004", trainNumber = "4012",
            express = false, expectedAt = at(1050).toString(), waitSeconds = 0, sourceUpdatedAt = at(1050).toString(),
            observedAt = at(1080).toString(), arrivalStatus = TransitArrivalStatus.ARRIVED,
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
        val upcoming = reported.copy(subwayLineId = "1002", destinationName = "성수", trainNumber = "2012",
            expectedAt = at(1370).toString(), waitSeconds = 152, sourceUpdatedAt = at(1218).toString(),
            observedAt = at(1220).toString(), arrivalStatus = TransitArrivalStatus.IN_TRANSIT)
        val arrivals = mock<TransitArrivalService>()
        val engine = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        whenever(arrivals.getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10))
            .thenReturn(listOf(reported.copy(trainNumber = "OTHER")), listOf(reported), emptyList())
        whenever(arrivals.getSubwayArrivalsForEta("사당", "2호선", null, "DOWN", 10))
            .thenReturn(emptyList(), emptyList(), listOf(upcoming))

        assertNull(engine.refresh(planned, at(1080), departurePlan = original))
        val saved = requireNotNull(engine.refresh(planned, at(1080), departurePlan = original))
        assertNull(saved.route)
        val restored = requireNotNull(engine.refresh(planned, at(1220), departurePlan = original, previousUpdate = saved))
        val route = requireNotNull(restored.route)
        assertEquals(original.departureAt, route.departureAt)
        assertEquals(at(1050), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals("2012", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(2022), route.arrivalAt)
        assertEquals(17L, route.segments[3].transferTiming?.remainingSlackSeconds)
        assertEquals(at(1218), restored.sourceUpdatedAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `같은 환승편이 지연에서 조기 도착으로 바뀌면 승차 대기와 하차 근거를 다시 계산한다`() {
        val base = plan()
        val original = base.copy(segments = base.segments.map { segment ->
            if (segment.sequence != 3) segment else {
                val old = requireNotNull(segment.rideTiming)
                segment.copy(rideTiming = old.copy(timetable = old.timetable.copy(boardingStationArrivalAt = at(970))))
            }
        })
        fun row(expected: Long, source: Long) = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY",
            stationName = "사당", subwayLineId = "1002", trainNumber = "2012", direction = "하행", destinationName = "성수",
            expectedAt = at(expected).toString(), sourceUpdatedAt = at(source).toString(), express = false,
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP)
        val arrivals = mock<TransitArrivalService>()
        var feed = row(1100, 449)
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == "사당" && it.arguments[1] == "2호선") listOf(feed) else emptyList<TransitArrivalDto>()
        }
        val service = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        val delayed = requireNotNull(service.refresh(original, at(450))?.route)
        assertEquals(at(1782), delayed.arrivalAt)
        feed = row(960, 469)
        val update = requireNotNull(service.refresh(delayed, at(470), original))
        val route = requireNotNull(update.route)
        val timing = requireNotNull(route.segments[3].rideTiming)
        assertEquals(at(960), timing.predictedBoardingAt)
        assertEquals(at(1481), timing.predictedArrivalAt)
        assertEquals("TRANSFER_BOARDING_REALTIME_TIMETABLE_ALIGHTING", timing.basis.name)
        assertEquals(at(469), timing.boardingSourceAt())
        assertNull(timing.alightingSourceAt())
        assertEquals(original.arrivalAt, route.arrivalAt)
        assertEquals(original.departureAt, route.departureAt)
        assertFalse(update.onlyLastRideObserved)
        val snapshot = EffectiveTransitRouteSnapshot(accepted = false, inProgressPlannedRoute = original,
            routeChanged = false, plannedRouteUpdate = update)
        val fresh = requireNotNull(snapshot.plannedJourneyArrivalPrediction(at(470), 120))
        assertEquals(PlannedJourneyArrivalBasis.MATCHED_RIDE_AND_ONWARD_TIMETABLE, fresh.basis)
        assertEquals(route, fresh.effectiveTransitRoute)
        val expired = requireNotNull(snapshot.plannedJourneyArrivalPrediction(at(590), 120))
        assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, expired.basis)
        assertEquals(true, expired.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.plannedBoardingIsStationArrival)
        feed = row(1100, 465)
        assertNull(service.refresh(route, at(471), original))
        verifyNoInteractions(timetable)
    }

    @Test
    fun `환승역 조기 예보는 신선한 하차 예보를 보존하고 정차 중에야 준비되는 기존 지연 계획을 철회한다`() {
        val base = plan()
        val saved = base.copy(segments = base.segments.map { s ->
            if (s.sequence != 3) s else {
                val t = requireNotNull(s.rideTiming)
                s.copy(rideTiming = t.copy(timetable = t.timetable.copy(boardingStationArrivalAt = at(970)),
                    predictedBoardingAt = at(1100), predictedArrivalAt = at(1560),
                    basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
                    plannedBoardingIsStationArrival = true, observationSourceUpdatedAt = at(445),
                    boardingObservationSourceUpdatedAt = at(440)))
            }
        })
        val early = TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(816), at(469), true)
        stubCurrent(saved, at(470), listOf(early))
        val update = requireNotNull(refresher.refresh(saved, at(470)))
        val route = requireNotNull(update.route)
        val timing = requireNotNull(route.segments[3].rideTiming)
        assertEquals(at(816), timing.predictedBoardingAt)
        assertEquals(at(1560), timing.predictedArrivalAt)
        assertEquals(at(445), timing.alightingSourceAt())
        assertEquals(at(469), timing.boardingSourceAt())
        assertEquals(at(1731), route.arrivalAt)
        assertEquals(at(445), update.sourceUpdatedAt)
        stubCurrent(saved, at(471), listOf(early.copy(expectedAt = at(815))))
        val conflict = requireNotNull(refresher.refresh(saved, at(471)))
        assertEquals(PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED, conflict.failure)
        assertEquals(PlannedTransferBoardingRecheck(3, "2012", at(1000)), conflict.transferBoardingRecheck)
        val upstream = TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(520), at(472))
        for (rows in listOf(listOf(upstream), listOf(early.copy(sourceUpdatedAt = at(468))),
                            listOf(early.copy(sourceUpdatedAt = at(469))))) {
            whenever(guard.observePlannedTransfers(saved, at(473), emptyMap(), 3)).thenReturn(PlannedTransferObservations(rows))
            assertNull(refresher.refresh(saved, at(473), previousUpdate = conflict))
        }
        val recovered = early.copy(sourceUpdatedAt = at(472))
        whenever(guard.observePlannedTransfers(saved, at(474), emptyMap(), 3)).thenReturn(PlannedTransferObservations(listOf(recovered)))
        assertEquals(at(1731), refresher.refresh(saved, at(474), previousUpdate = conflict)?.route?.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `환승 열차의 지연만 회복돼 승차 여유가 부족하면 다음 시간표와 그 도착을 반환한다`() {
        val original = plan()
        val transfer = requireNotNull(original.segments[3].rideTiming).timetable
        val next = transfer.copy(trainNumber = "2014", boardingAt = at(1300), arrivalAt = at(1781))
        whenever(timetable.findRides(any())).thenReturn(listOf(next))
        stubCurrent(original, at(450), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(740), at(449)),
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(1100), at(430))))
        val waiting = requireNotNull(refresher.refresh(original, at(450))?.route)
        assertEquals(at(1100), waiting.segments[3].rideTiming?.predictedBoardingAt)
        verifyNoInteractions(timetable)
        for ((evaluation, receipt) in listOf(470L to at(445), 590L to at(589))) {
            stubCurrent(waiting, at(evaluation), listOf(
                TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(1010), receipt)))
            val update = requireNotNull(refresher.refresh(waiting, at(evaluation), original))
            val route = requireNotNull(update.route)
            val replacement = requireNotNull(route.segments[3].rideTiming)
            // 앞 열차 하차 740 + 도보 243 + 여유 60 = 1043. 새 승차 예측 1010은 여유가 33초 부족하다.
            assertEquals("2014", replacement.timetable.trainNumber)
            assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, replacement.basis)
            assertNull(replacement.observationSourceUpdatedAt)
            assertEquals(receipt, update.sourceUpdatedAt)
            assertFalse(update.onlyLastRideObserved)
            assertEquals(at(1952), route.arrivalAt)
            assertEquals(original.departureAt, route.departureAt)
        }
    }

    @Test
    fun `동일 공급자 응답에서 마지막 편 단독 관측 정책과 구간별 재계산을 비교한다`() {
        val plan = plan()
        val now = at(300)
        val rows = listOf(TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "사당",
            direction = "하행", destinationName = "오이도", expectedAt = at(700).toString(),
            sourceUpdatedAt = now.toString(), freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
            arrivalStatus = TransitArrivalStatus.IN_TRANSIT, subwayLineId = "1004", trainNumber = "4012", express = false))
        val next = requireNotNull(plan.segments[3].rideTiming).timetable.copy(
            trainNumber = "2014", boardingAt = at(1300), arrivalAt = at(1781))
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == "사당" && it.arguments[1] == "4호선") rows else emptyList<TransitArrivalDto>()
        }
        whenever(timetable.findRides(any())).thenReturn(listOf(next))
        val realGuard = TransitTransferRealtimeGuard(arrivals)
        // 기존 정책 구현을 그대로 호출한다. 두 정책의 응답 은행·계획·평가 시계는 같다.
        val beforeObservation = realGuard.observeLastPlannedRide(plan, now)
        val after = requireNotNull(PlannedTransitRouteRefresher(realGuard,
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator()).refresh(plan, now))
        val afterRoute = requireNotNull(after.route)
        assertNull(beforeObservation)
        assertEquals("2014", afterRoute.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1952), afterRoute.arrivalAt)
        val ready = at(700).plusSeconds(243 + 60)
        assertTrue(ready > requireNotNull(plan.segments[3].rideTiming).predictedBoardingAt)
        assertTrue(ready <= requireNotNull(afterRoute.segments[3].rideTiming).predictedBoardingAt)
        assertEquals(plan.departureAt, afterRoute.departureAt)

        System.getProperty("nolate.eta.planned-replay-output")?.let { value ->
            val path = java.nio.file.Path.of(value).toAbsolutePath().normalize()
            require(path.toString().startsWith("/private/tmp/nolate-planned-route-replay."))
            val mapper = jacksonObjectMapper().findAndRegisterModules()
            val input = linkedMapOf("evaluatedAt" to now, "plan" to plan,
                "publicArrivalRows" to rows, "onwardTimetableResponse" to listOf(next))
            val bytes = mapper.writeValueAsBytes(input)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val report = linkedMapOf("classification" to "SYNTHETIC_IDENTICAL_RESPONSE_POLICY_REPLAY",
                "inputsIdentical" to true, "inputSha256" to hash, "input" to input,
                "beforePolicy" to "TransitTransferRealtimeGuard.observeLastPlannedRide",
                "beforeObservation" to beforeObservation, "beforePlannedRoute" to plan,
                "afterPolicy" to "PlannedTransitRouteRefresher.refresh", "afterUpdate" to after,
                "beforeInfeasibleConnectionsUnderObservedArrival" to 1,
                "afterInfeasibleConnectionsUnderObservedArrival" to 0,
                "departurePlanUnchanged" to true, "walkingAndMarginUnchanged" to true,
                "publicTransitHttpCalls" to 0, "actualTravelAccuracyVerified" to false)
            java.nio.file.Files.write(path.resolveSibling("input.json"), bytes,
                java.nio.file.StandardOpenOption.CREATE_NEW)
            java.nio.file.Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                java.nio.file.StandardOpenOption.CREATE_NEW)
        }
    }

    @Test
    fun `출발 후 첫 하차 관측으로 다음 편과 최종 도착을 갱신하고 출발 계획을 보존한다`() {
        val plan = plan()
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(700), at(300))))
        val next = requireNotNull(plan.segments[3].rideTiming).timetable.copy(
            trainNumber = "2014", boardingAt = at(1300), arrivalAt = at(1781))
        whenever(timetable.findRides(any())).thenReturn(listOf(next))

        val update = requireNotNull(refresher.refresh(plan, at(300)))
        val route = requireNotNull(update.route)
        assertNull(update.failure)
        assertFalse(update.onlyLastRideObserved)
        assertEquals(plan.departureAt, route.departureAt)
        assertEquals(at(1952), route.arrivalAt)
        assertEquals("2014", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(700), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(at(210), route.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals(EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME, route.segments[1].rideTiming?.basis)
        assertEquals(listOf(111L, 243L, 171L), route.segments.filter { it.kind == EffectiveTransitRouteSegmentKind.WALK }.map { it.durationSeconds })
        assertEquals(60L, route.segments[3].transferTiming?.requiredMarginSeconds)
        verify(timetable).findRides(argThat { notBefore == at(1003) })
    }

    @Test
    fun `이미 보정된 승차 계획 위에 직접 하차 관측을 반영할 때 승차 지연을 중복 가산하지 않는다`() {
        val plan = plan().let { original ->
            original.copy(arrivalAt = at(1772), segments = original.segments.map { segment ->
                if (segment.sequence != 3) segment else segment.copy(rideTiming = requireNotNull(segment.rideTiming).copy(
                    predictedBoardingAt = at(1120), predictedArrivalAt = at(1601),
                    basis = EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
                    observationSourceUpdatedAt = at(100)))
            })
        }
        stubCurrent(plan, at(1200), listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING, at(1620), at(1200))))

        val update = requireNotNull(refresher.refresh(plan, at(1200)))
        assertTrue(update.onlyLastRideObserved)
        val last = requireNotNull(update.route).segments[3].rideTiming
        assertEquals(at(1120), last?.predictedBoardingAt)
        assertEquals(at(1620), last?.predictedArrivalAt)
        assertEquals(at(1791), update.route?.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `하차 지연이 회복되면 기존 환승을 유지하고 줄어든 지연을 대기에 흡수한다`() {
        val plan = plan()
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(450), at(300))))
        val route = requireNotNull(refresher.refresh(plan, at(300))?.route)
        assertEquals(at(450), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(plan.arrivalAt, route.arrivalAt)
        assertEquals("2012", route.segments[3].rideTiming?.timetable?.trainNumber)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `후속 시간표를 연결하지 못하면 모순된 기존 도착을 반환하지 않는다`() {
        val plan = plan()
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(700), at(300))))
        whenever(timetable.findRides(any())).thenReturn(emptyList())
        val update = requireNotNull(refresher.refresh(plan, at(300)))
        assertNull(update.route)
        assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, update.failure)
    }

    @ParameterizedTest
    @CsvSource("1120,true", "1200,false")
    fun `첫 환승을 다시 연결한 뒤 준비 시각으로 마지막 열차의 하차 예보를 검증한다`(secondArrival: Long, lastRideReachable: Boolean) {
        val original = plan()
        val second = requireNotNull(original.segments[3].rideTiming).timetable
        val thirdTable = second.copy(line = SeoulSubwayLine.LINE_1, trainNumber = "1012",
            fromStationCode = "L", toStationCode = "Y", terminalName = "소요산",
            boardingAt = at(1300), arrivalAt = at(1370))
        val third = EffectiveTransitRouteSegment(5, EffectiveTransitRouteSegmentKind.SUBWAY, 4, lineName = "1호선",
            fromName = "신림", toName = "영등포", rideTiming = EffectiveTransitRideTiming(thirdTable, at(1300), at(1370),
                EffectiveTransitRideTimingBasis.TIMETABLE))
        val plan = original.copy(arrivalAt = at(1541), totalMinutes = 26, segments = original.segments.take(4) +
            EffectiveTransitRouteSegment(4, EffectiveTransitRouteSegmentKind.WALK, 1, durationSeconds = 60) + third +
            original.segments.last().copy(sequence = 6))
        stubCurrent(plan, at(500), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(700), at(499)),
            TransitRideRealtimeObservation(5, TransitRideObservationPoint.ALIGHTING, at(1380), at(500))))
        whenever(timetable.findRides(any())).thenAnswer { call ->
            val query = call.getArgument<TransitTimetableQuery>(0)
            if (query.line == second.line) listOf(second.copy(trainNumber = "2014", boardingAt = at(1010), arrivalAt = at(secondArrival)))
            else emptyList()
        }

        val update = requireNotNull(refresher.refresh(plan, at(500)))
        verify(timetable).findRides(argThat { line == second.line && notBefore == at(1003) })
        if (!lastRideReachable) {
            assertNull(update.route)
            assertEquals(PlannedTransitTimingFailure.TRANSFER_RECONNECTION_UNAVAILABLE, update.failure)
            verify(timetable).findRides(argThat { line == thirdTable.line && notBefore == at(1320) })
            return
        }
        val route = requireNotNull(update.route)
        assertEquals(plan.departureAt, route.departureAt)
        assertEquals("2014", route.segments[3].rideTiming?.timetable?.trainNumber)
        val last = requireNotNull(route.segments[5].rideTiming)
        assertEquals("1012", last.timetable.trainNumber)
        assertEquals(at(1380), last.predictedArrivalAt)
        assertEquals(at(500), last.alightingSourceAt())
        assertEquals(at(1551), route.arrivalAt)
        val snapshot = EffectiveTransitRouteSnapshot(accepted = false, inProgressPlannedRoute = plan,
            routeChanged = false, plannedRouteUpdate = update)
        val prediction = requireNotNull(snapshot.plannedJourneyArrivalPrediction(at(500), 120))
        assertEquals(route, prediction.effectiveTransitRoute)
        assertEquals(at(1551), prediction.predictedArrivalAt)
        verifyNoMoreInteractions(timetable)
    }

    @Test
    fun `출발 후 두 번째 구간의 직접 하차 지연도 세 번째 열차와 도착을 다시 연결한다`() {
        val original = plan()
        val second = original.segments[3].copy(toName = "신도림", rideTiming = requireNotNull(original.segments[3].rideTiming).let {
            it.copy(timetable = it.timetable.copy(toStationCode = "D2"))
        })
        val table = requireNotNull(second.rideTiming).timetable.copy(line = SeoulSubwayLine.LINE_1, trainNumber = "1012",
            fromStationCode = "D1", toStationCode = "Y", terminalName = "소요산", boardingAt = at(1700), arrivalAt = at(1820))
        val third = EffectiveTransitRouteSegment(5, EffectiveTransitRouteSegmentKind.SUBWAY, 5, lineName = "1호선",
            fromName = "신도림", toName = "영등포", rideTiming = EffectiveTransitRideTiming(table, at(1700), at(1820),
                EffectiveTransitRideTimingBasis.TIMETABLE))
        val plan = original.copy(arrivalAt = at(1991), totalMinutes = 33, segments = original.segments.take(3) + second +
            EffectiveTransitRouteSegment(4, EffectiveTransitRouteSegmentKind.WALK, 1, durationSeconds = 60) + third +
            original.segments.last().copy(sequence = 6))
        stubCurrent(plan, at(1200), listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.ALIGHTING, at(1600), at(1200))))
        whenever(timetable.findRides(any())).thenReturn(listOf(table.copy(trainNumber = "1014", boardingAt = at(1800), arrivalAt = at(1920))))

        val update = requireNotNull(refresher.refresh(plan, at(1200)))
        val route = requireNotNull(update.route)
        assertEquals(at(2091), route.arrivalAt)
        assertEquals(plan.departureAt, route.departureAt)
        assertEquals(at(513), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(at(1600), route.segments[3].rideTiming?.predictedArrivalAt)
        assertEquals("1014", route.segments[5].rideTiming?.timetable?.trainNumber)
        assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, route.segments[5].rideTiming?.basis)
        verify(timetable).findRides(argThat { line == SeoulSubwayLine.LINE_1 && notBefore == at(1720) })
    }

    @Test
    fun `미래 환승의 오래된 지연을 승차 가능 근거로 재사용하지 않는다`() {
        val plan = plan().let { original -> original.copy(segments = original.segments.map { segment ->
            if (segment.sequence != 3) segment else segment.copy(rideTiming = requireNotNull(segment.rideTiming).let {
                it.copy(timetable = it.timetable.copy(boardingAt = at(840), arrivalAt = at(1321)),
                    predictedBoardingAt = at(1000), predictedArrivalAt = at(1481),
                    basis = EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
                    observationSourceUpdatedAt = at(0))
            })
        }) }
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(650), at(300))))
        val next = requireNotNull(plan.segments[3].rideTiming).timetable.copy(
            trainNumber = "2014", boardingAt = at(1300), arrivalAt = at(1781))
        whenever(timetable.findRides(any())).thenReturn(listOf(next))

        val route = requireNotNull(refresher.refresh(plan, at(300))?.route)
        // 953초에 환승 준비가 끝난다. 오래된 1000초 예측으로 원래 840초 편을 유지하면 안 된다.
        assertEquals("2014", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1952), route.arrivalAt)
        assertEquals(plan.departureAt, route.departureAt)
        verify(timetable).findRides(argThat { notBefore == at(953) })
    }

    @Test
    fun `노원 K4621의 저장 응답을 출발 경계에 재생하면 사당 환승은 2278편이 된다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/odsay-early-first-boarding-20261118.json"))
            .use { mapper.readTree(it) }
        val plan = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val observation = mapper.treeToValue(input["firstStationObservation"], TransitFirstBoardingObservation::class.java)
        val first = requireNotNull(plan.segments[1].rideTiming)
        assertEquals(first.timetable.realtimeTrainNumber, observation.trainNumber)
        assertEquals(observation.readyAt, observation.expectedBoardingAt)
        assertEquals(103L, java.time.Duration.between(observation.sourceUpdatedAt, plan.departureAt).seconds)
        // Worker의 저장·복원 경계를 거쳐 같은 열차의 출발 전 원본 시각을 대조한다.
        val accepted = EffectiveTransitRouteSnapshot(accepted = true, effectiveTransitRoute = plan,
            firstBoardingObservation = observation)
        val fallback = TrafficResult(plan.totalMinutes, com.noLate.schedule.domain.TrafficSource.SELECTED_ROUTE, stale = true)
        val earlier = requireNotNull(EffectiveTransitRouteSnapshot.from(fallback, accepted, 1,
            evaluatedAt = plan.departureAt.minusSeconds(1)))
        val stored = requireNotNull(EffectiveTransitRouteSnapshot.parse(mapper, mapper.writeValueAsString(earlier)))
        val current = requireNotNull(EffectiveTransitRouteSnapshot.from(fallback, stored, 2, evaluatedAt = plan.departureAt))
        val carried = requireNotNull(current.inProgressPlannedRoute)
        assertEquals(observation.sourceUpdatedAt, carried.segments[1].rideTiming?.boardingSourceAt())
        val row = TransitArrivalDto(provider = observation.provider, kind = "SUBWAY", stationName = observation.stationName,
            subwayLineId = first.timetable.line.realtimeId, trainNumber = observation.trainNumber, direction = "하행",
            destinationName = first.timetable.terminalName, express = false,
            freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
            expectedAt = observation.expectedBoardingAt.toString(), sourceUpdatedAt = observation.sourceUpdatedAt.toString())
        var response = row
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == observation.stationName) listOf(response) else emptyList()
        }
        val service = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        // 독립적으로 만든 역순·동시각 충돌 응답. 둘 다 수신 시점의 120초 범위 안이다.
        for (stamp in listOf(observation.sourceUpdatedAt.minusSeconds(10), observation.sourceUpdatedAt)) {
            response = row.copy(sourceUpdatedAt = stamp.toString(), expectedAt = observation.readyAt.minusSeconds(20).toString())
            assertEquals(PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED, service.refresh(plan, plan.departureAt)?.failure)
            val refresh = service.refresh(carried, plan.departureAt)
            assertNull(refresh)
            val prediction = requireNotNull(current.copy(plannedRouteUpdate = refresh).plannedJourneyArrivalPrediction(plan.departureAt, 120))
            assertEquals(PlannedJourneyArrivalBasis.LAST_ACCEPTED_PLAN, prediction.basis)
            assertEquals(plan.arrivalAt, prediction.predictedArrivalAt)
            assertEquals(plan.departureAt, prediction.effectiveTransitRoute?.departureAt)
            assertNull(prediction.sourceUpdatedAt)
        }
        response = response.copy(sourceUpdatedAt = observation.sourceUpdatedAt.plusSeconds(10).toString())
        val unsafe = current.copy(plannedRouteUpdate = service.refresh(carried, plan.departureAt))
        assertEquals(PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED.name, unsafe.plannedJourneyTimingFailureReason())
        assertNull(unsafe.plannedJourneyArrivalPrediction(plan.departureAt, 120))
        response = row
        whenever(timetable.findRides(any())).thenReturn(input["timetableEvidence"].map {
            mapper.treeToValue(it, TransitLegTimetable::class.java)
        })

        val updated = requireNotNull(service.refresh(carried, plan.departureAt)?.route)
        assertEquals(plan.departureAt, updated.departureAt)
        assertEquals(observation.expectedBoardingAt, updated.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals(Instant.parse("2026-11-18T06:30:00Z"), updated.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals("2278", updated.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(293L, updated.segments[3].transferTiming?.remainingSlackSeconds)
        assertEquals(Instant.parse("2026-11-18T06:47:13Z"), updated.arrivalAt)
    }

    @Test
    fun `첫 열차의 이른 역 도착이 준비 경계면 승차 대기는 갱신하고 하차 시간표로 환승을 확인한다`() {
        val original = plan()
        val plan = original.copy(departureAt = at(0), segments = original.segments.map { segment ->
            val timing = segment.rideTiming ?: return@map segment
            when (segment.sequence) {
                1 -> segment.copy(rideTiming = timing.copy(
                    timetable = timing.timetable.copy(boardingAt = at(230), boardingStationArrivalAt = at(200)),
                    predictedBoardingAt = at(180), predictedArrivalAt = at(480)))
                else -> segment.copy(rideTiming = timing.copy(timetable = timing.timetable.copy(boardingAt = at(792)),
                    predictedBoardingAt = at(792)))
            }
        })
        // 기존 480 + 환승 도보 243 + 여유 60은 792초 편에 맞지만, 시간표 하차 500초로는 탈 수 없다.
        val second = requireNotNull(plan.segments[3].rideTiming).timetable
        whenever(timetable.findRides(any())).thenReturn(listOf(second,
            second.copy(trainNumber = "2014", boardingAt = at(1100), arrivalAt = at(1581))))
        val early = TransitRideRealtimeObservation(1, TransitRideObservationPoint.BOARDING, at(171), at(50), true)
        stubCurrent(plan, at(51), listOf(early))

        val update = requireNotNull(refresher.refresh(plan, at(51)))
        val route = requireNotNull(update.route)
        assertEquals(at(171), route.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals(at(500), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING, route.segments[1].rideTiming?.basis)
        assertEquals(at(50), route.segments[1].rideTiming?.observationSourceUpdatedAt)
        assertEquals("2014", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1752), route.arrivalAt)
        assertEquals(plan.departureAt, route.departureAt)
        verify(timetable).findRides(argThat { notBefore == at(803) })

        // 첫 도보 111초와 준비 여유 60초를 채우기 1초 전이면 같은 열차 승차를 안내할 수 없다.
        stubCurrent(plan, at(52), listOf(early.copy(expectedAt = at(170))))
        val missed = requireNotNull(refresher.refresh(plan, at(52)))
        assertEquals(PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED, missed.failure)
        assertNull(missed.route)
        verifyNoMoreInteractions(timetable)
    }

    @Test
    fun `이른 승차역 도착은 아직 신선한 하차역 직접 관측을 시간표로 되돌리지 않는다`() {
        val original = plan()
        val first = requireNotNull(original.segments[1].rideTiming)
        val single = original.copy(departureAt = at(0), arrivalAt = at(650), segments = listOf(
            original.segments[0], original.segments[1].copy(rideTiming = first.copy(
                predictedArrivalAt = at(650), basis = EffectiveTransitRideTimingBasis.PLANNED_RIDE_ALIGHTING_REALTIME,
                observationSourceUpdatedAt = at(40), plannedBoardingIsStationArrival = true))))
        val early = TransitRideRealtimeObservation(1, TransitRideObservationPoint.BOARDING, at(187), at(55), true)
        stubCurrent(single, at(56), listOf(early))
        val update = requireNotNull(refresher.refresh(single, at(56)))
        assertEquals(at(650), update.route?.arrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,
            update.route?.segments?.get(1)?.rideTiming?.basis)
        assertEquals(at(40), update.sourceUpdatedAt)

        stubCurrent(single, at(161), listOf(early.copy(sourceUpdatedAt = at(160))))
        val expired = requireNotNull(refresher.refresh(single, at(161)))
        assertEquals(first.timetable.arrivalAt, expired.route?.arrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_TIMETABLE_ALIGHTING,
            expired.route?.segments?.get(1)?.rideTiming?.basis)
        assertFalse(expired.onlyLastRideObserved)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `승차 지연을 다시 받아도 신선한 같은 열차의 직접 하차 예상으로 환승을 판정한다`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val input = requireNotNull(javaClass.getResourceAsStream("/eta/planned-station-forecasts.json")).use { mapper.readTree(it) }
        val plan = mapper.treeToValue(input["plan"], EffectiveTransitRoute::class.java)
        val row = mapper.treeToValue(input["stationRow"], TransitArrivalDto::class.java)
        val alternate = mapper.treeToValue(input["alternateTrain"], TransitLegTimetable::class.java)
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == row.stationName && it.arguments[1] == row.lineName) listOf(row) else emptyList()
        }
        whenever(timetable.findRides(any())).thenReturn(listOf(alternate))
        val service = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        val update = requireNotNull(service.refresh(plan, Instant.parse(input["evaluatedAt"].asText())))
        val route = requireNotNull(update.route)
        val timing = requireNotNull(route.segments[1].rideTiming)
        assertEquals(at(400), timing.predictedBoardingAt)
        assertEquals(at(650), timing.predictedArrivalAt)
        assertEquals(at(99), timing.boardingSourceAt())
        assertEquals(at(40), timing.alightingSourceAt())
        assertEquals(at(40), update.sourceUpdatedAt)
        // 하차 650 + 도보 243 + 여유 60 = 953. 승차역 지연으로 하차를 703으로 바꾸면 1000초 편을 놓친다고 오판한다.
        assertEquals("2012", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(plan.arrivalAt, route.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `환승편도 두 역의 원본시각을 구분하고 한 역만 만료되면 나머지 관측만 쓴다`() {
        val original = plan()
        val transfer = original.segments[3]
        val timing = requireNotNull(transfer.rideTiming).copy(predictedArrivalAt = at(1570),
            basis = EffectiveTransitRideTimingBasis.TRANSFER_ALIGHTING_REALTIME, observationSourceUpdatedAt = at(220))
        val prior = original.copy(segments = original.segments.map { if (it === transfer) transfer.copy(rideTiming = timing) else it })
        stubCurrent(prior, at(300), listOf(
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(1050), at(299))))
        val both = requireNotNull(refresher.refresh(prior, at(300))?.route)
        val combined = requireNotNull(both.segments[3].rideTiming)
        assertEquals(at(1570), combined.predictedArrivalAt)
        assertEquals(at(299), combined.boardingSourceAt())
        assertEquals(at(220), combined.alightingSourceAt())
        assertEquals(at(1741), both.arrivalAt)
        val upstream = TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(630), at(350))
        stubCurrent(both, at(350), listOf(upstream))
        val boardOnly = requireNotNull(refresher.refresh(both, at(350))?.route?.segments?.get(3)?.rideTiming)
        assertEquals(at(1531), boardOnly.predictedArrivalAt)
        assertEquals(at(299), boardOnly.boardingSourceAt())
        assertNull(boardOnly.alightingSourceAt())
        val inverse = both.copy(segments = both.segments.map { if (it.sequence != 3) it else it.copy(
            rideTiming = combined.copy(boardingObservationSourceUpdatedAt = at(220), observationSourceUpdatedAt = at(299))) })
        stubCurrent(inverse, at(350), listOf(upstream))
        val alightOnly = requireNotNull(refresher.refresh(inverse, at(350))?.route?.segments?.get(3)?.rideTiming)
        assertEquals(at(1000), alightOnly.predictedBoardingAt)
        assertEquals(at(1570), alightOnly.predictedArrivalAt)
        assertEquals(at(299), alightOnly.alightingSourceAt())
        assertNull(alightOnly.boardingSourceAt())
        verifyNoInteractions(timetable)
    }

    @Test
    fun `접근 도보 출발 후 첫 승차 전 관측도 출발 계획을 움직이지 않고 반영한다`() {
        val plan = plan()
        stubCurrent(plan, at(100), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.BOARDING, at(400), at(100))))
        whenever(timetable.findRides(any())).thenReturn(listOf(requireNotNull(plan.segments[3].rideTiming).timetable.copy(
            trainNumber = "2014", boardingAt = at(1300), arrivalAt = at(1781))))
        val route = requireNotNull(refresher.refresh(plan, at(100))?.route)
        assertEquals(plan.departureAt, route.departureAt)
        assertEquals(at(400), route.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals(at(703), route.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.PLANNED_RIDE_BOARDING_REALTIME, route.segments[1].rideTiming?.basis)
        assertEquals(at(100), route.segments[1].rideTiming?.observationSourceUpdatedAt)
        // 준비 1006초에는 원래 1000초 편을 탈 수 없으므로 다음 편이 필요하다.
        assertNull(route.segments[1].rideTiming?.plannedBoardingIsStationArrival)
        assertEquals("2014", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(at(1952), route.arrivalAt)
        verify(timetable).findRides(argThat { notBefore == at(1006) })
    }

    @Test
    fun `다음 열차의 기존 지연 관측이 아직 신선하면 시간표로 지우지 않는다`() {
        val plan = plan().let { original -> original.copy(segments = original.segments.map { segment ->
            if (segment.sequence != 3) segment else segment.copy(rideTiming = requireNotNull(segment.rideTiming).let {
                it.copy(timetable = it.timetable.copy(boardingAt = at(840), arrivalAt = at(1321)),
                    predictedBoardingAt = at(1000), predictedArrivalAt = at(1481),
                    basis = EffectiveTransitRideTimingBasis.TRANSFER_BOARDING_REALTIME_ADJUSTED_TIMETABLE,
                    observationSourceUpdatedAt = at(290))
            })
        }) }
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(650), at(300))))
        val update = requireNotNull(refresher.refresh(plan, at(300)))
        val route = requireNotNull(update.route)
        assertEquals("2012", route.segments[3].rideTiming?.timetable?.trainNumber)
        assertEquals(plan.arrivalAt, route.arrivalAt)
        assertEquals(at(290), update.sourceUpdatedAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `두번째 열차만 갱신한 뒤 첫 하차역의 역순 응답이 환승을 되돌리지 않는다`() {
        val arrivals = mock<TransitArrivalService>()
        fun row(line: String, number: String, terminal: String, expected: Long, source: Long) =
            TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", stationName = "사당", direction = "하행",
                subwayLineId = line, trainNumber = number, destinationName = terminal, express = false,
                freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                arrivalStatus = TransitArrivalStatus.IN_TRANSIT, expectedAt = at(expected).toString(), sourceUpdatedAt = at(source).toString())
        val feeds = listOf(
            mapOf("4호선" to row("1004", "4012", "오이도", 650, 299)),
            mapOf("2호선" to row("1002", "2012", "성수", 1010, 319)),
            mapOf("4호선" to row("1004", "4012", "오이도", 740, 285)),
        )
        var feed = feeds.first()
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer {
            if (it.arguments[0] == "사당") listOfNotNull(feed[it.arguments[1]]) else emptyList<TransitArrivalDto>()
        }
        val service = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(listOf(timetable)), TransitTransferFeasibilityEvaluator())
        val plan = plan()
        whenever(timetable.findRides(any())).thenReturn(listOf(requireNotNull(plan.segments[3].rideTiming).timetable.copy(
            trainNumber = "2014", boardingAt = at(1300), arrivalAt = at(1781))))
        val first = requireNotNull(service.refresh(plan, at(300))?.route)
        feed = feeds[1]
        val second = requireNotNull(service.refresh(first, at(320)))
        val connected = requireNotNull(second.route)
        feed = feeds[2]
        val third = service.refresh(connected, at(340))
        val latest = third?.route ?: connected
        assertEquals("2012", latest.segments[3].rideTiming?.timetable?.trainNumber,
            "도착 ${latest.arrivalAt}, 첫 하차 ${latest.segments[1].rideTiming?.predictedArrivalAt}, 이전 출처 ${connected.segments[1].rideTiming?.alightingSourceAt()}")
        assertNull(third)
        assertEquals(at(650), latest.segments[1].rideTiming?.predictedArrivalAt)
        assertEquals(at(299), latest.segments[1].rideTiming?.alightingSourceAt())
        assertEquals(at(319), latest.segments[3].rideTiming?.boardingSourceAt())
        assertEquals(at(299), second.sourceUpdatedAt)
        assertEquals(at(1662), latest.arrivalAt)
        assertEquals(plan.departureAt, latest.departureAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `뒤 구간을 갱신할 때 첫 승차와 하차 중 아직 신선한 쪽만 보존한다`() {
        val original = plan()
        val first = requireNotNull(original.segments[1].rideTiming)
        val table = first.timetable.copy(boardingAt = at(250))
        val transfer = TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(1010), at(180))
        fun refresh(boardSource: Long, alightSource: Long): EffectiveTransitRideTiming {
            val both = first.copy(timetable = table, predictedBoardingAt = at(220), predictedArrivalAt = at(540),
                basis = EffectiveTransitRideTimingBasis.FIRST_RIDE_BOARDING_AND_ALIGHTING_REALTIME,
                observationSourceUpdatedAt = at(alightSource), boardingObservationSourceUpdatedAt = at(boardSource))
            val saved = original.copy(segments = original.segments.map { if (it.sequence == 1) it.copy(rideTiming = both) else it })
            stubCurrent(saved, at(180), listOf(transfer))
            return requireNotNull(refresher.refresh(saved, at(180))?.route?.segments?.get(1)?.rideTiming)
        }
        val alight = refresh(50, 170)
        assertEquals(at(540), alight.predictedArrivalAt)
        assertEquals(at(170), alight.alightingSourceAt())
        assertNull(alight.boardingSourceAt())
        assertEquals(true, alight.plannedBoardingIsStationArrival)
        val board = refresh(170, 50)
        assertEquals(table.arrivalAt, board.predictedArrivalAt)
        assertEquals(at(170), board.boardingSourceAt())
        assertNull(board.alightingSourceAt())
        verifyNoInteractions(timetable)
    }

    @Test
    fun `여러 구간의 관측은 가장 오래된 출처로 전체 경로 신선도를 제한한다`() {
        val plan = plan()
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(700), at(200)),
            TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(1100), at(300))))
        val update = requireNotNull(refresher.refresh(plan, at(300)))
        assertEquals(at(200), update.sourceUpdatedAt)
        assertEquals(at(1752), update.route?.arrivalAt)
        verifyNoInteractions(timetable)
    }

    @Test
    fun `오래되거나 다른 구간 번호의 관측은 계획과 시간표를 바꾸지 않는다`() {
        val plan = plan()
        stubCurrent(plan, at(300), listOf(
            TransitRideRealtimeObservation(1, TransitRideObservationPoint.ALIGHTING, at(700), at(179)),
            TransitRideRealtimeObservation(8, TransitRideObservationPoint.ALIGHTING, at(700), at(300))))
        assertNull(refresher.refresh(plan, at(300)))
        assertNull(refresher.refresh(plan, at(0)))
        verifyNoInteractions(timetable)
    }

    @Test
    fun `첫 열차가 시간표보다 늦어도 접근 도보와 승차 여유를 채워야 한다`() {
        val plan = plan()
        // 출발 39초 + 접근 도보 111초 + 승차 여유 60초 = 210초. 시간표 승차는 197초다.
        val beforeReady = TransitRideRealtimeObservation(1, TransitRideObservationPoint.BOARDING, at(209), at(41))
        stubCurrent(plan, at(41), listOf(beforeReady))
        val short = requireNotNull(refresher.refresh(plan, at(41)))
        assertEquals(PlannedTransitTimingFailure.EARLY_BOARDING_UNCONFIRMED, short.failure)
        assertNull(short.route)
        assertTrue(short.requiresFirstBoardingRecheck)
        assertEquals(at(41), short.sourceUpdatedAt)
        val onward = TransitRideRealtimeObservation(3, TransitRideObservationPoint.BOARDING, at(1100), at(42))
        for (observations in listOf(listOf(onward), listOf(onward, beforeReady.copy(expectedAt = at(210))),
            listOf(beforeReady.copy(expectedAt = at(210), sourceUpdatedAt = at(40))))) {
            whenever(guard.observePlannedTransfers(plan, at(42), emptyMap(), 1)).thenReturn(PlannedTransferObservations(observations))
            assertNull(refresher.refresh(plan, at(42), previousUpdate = short))
        }
        val ready = beforeReady.copy(expectedAt = at(210), sourceUpdatedAt = at(42))
        whenever(guard.observePlannedTransfers(plan, at(42), emptyMap(), 1)).thenReturn(PlannedTransferObservations(listOf(ready)))
        val restored = requireNotNull(refresher.refresh(plan, at(42), previousUpdate = short)?.route)
        assertEquals(plan.departureAt, restored.departureAt)
        assertEquals(at(210), restored.segments[1].rideTiming?.predictedBoardingAt)
        assertEquals(plan.arrivalAt, restored.arrivalAt)
    }

    private fun stubCurrent(route: EffectiveTransitRoute, evaluatedAt: Instant,
                            observations: List<TransitRideRealtimeObservation>) {
        whenever(guard.observePlannedTransfers(route, evaluatedAt, emptyMap()))
            .thenReturn(PlannedTransferObservations(current = observations))
    }

    private fun plan(): EffectiveTransitRoute {
        fun table(line: SeoulSubwayLine, train: String, boarding: Long, arrival: Long) = TransitLegTimetable(
            "seoul-timetable", line, LocalDate.parse("2026-11-19"), train, "DOWN", TransitServiceClass.LOCAL,
            if (line == SeoulSubwayLine.LINE_4) "N" else "S2", if (line == SeoulSubwayLine.LINE_4) "S4" else "L",
            if (line == SeoulSubwayLine.LINE_4) "오이도" else "성수", at(boarding), at(arrival), at(0))
        val first = table(SeoulSubwayLine.LINE_4, "4012", 197, 500)
        val next = table(SeoulSubwayLine.LINE_2, "2012", 1000, 1481)
        fun walk(sequence: Int, seconds: Long) = EffectiveTransitRouteSegment(sequence, EffectiveTransitRouteSegmentKind.WALK,
            ((seconds + 59) / 60).toInt(), durationSeconds = seconds)
        val segments = listOf(walk(0, 111), EffectiveTransitRouteSegment(1, EffectiveTransitRouteSegmentKind.SUBWAY,
            7, lineName = "4호선", fromName = "노원", toName = "사당", rideTiming = EffectiveTransitRideTiming(
                first, at(210), at(513), EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE)),
            walk(2, 243), EffectiveTransitRouteSegment(3, EffectiveTransitRouteSegmentKind.SUBWAY,
                13, lineName = "2호선", fromName = "사당", toName = "신림", rideTiming = EffectiveTransitRideTiming(
                    next, at(1000), at(1481), EffectiveTransitRideTimingBasis.TIMETABLE)), walk(4, 171))
        return EffectiveTransitRoute("odsay", "a".repeat(64), at(39), at(1652), 27, segments)
    }
}
