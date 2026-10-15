package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.port.*
import com.noLate.eta.application.transit.*
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.infrastructure.tmap.TmapTransitJourneyMapper
import com.noLate.eta.infrastructure.odsay.OdsayTransitJourneyMapper
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficFailureReasons
import com.noLate.schedule.application.TransitTimingBasis
import com.noLate.schedule.application.EffectiveTransitRideTimingBasis
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.TrafficSource
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.*

/** 시간표와 실시간 피드는 fixture다. 공급자 HTTP/실기기 E2E 검증과 구분한다. */
class TransitTimetableCalculationIntegrationTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")

    @Test
    fun `실제 계산 스냅샷은 현재 환승 도보와 안전 여유를 분리해 보존하고 역직렬화한다`() {
        val result = calculate("4012", false)
        val route = requireNotNull(result.effectiveTransitRoute)
        val audit = requireNotNull(route.segments[3].transferTiming)
        assertEquals(243L, audit.walkingSeconds)
        assertEquals(0L, audit.bufferSeconds)
        assertEquals(60L, audit.confidenceMarginSeconds)
        assertEquals(60L, audit.requiredMarginSeconds)
        assertEquals(now.plusSeconds(1410), audit.previousArrivalAt)
        assertEquals(now.plusSeconds(1713), audit.confidenceReadyAt)
        assertEquals(87L, audit.remainingSlackSeconds)
        val json = jacksonObjectMapper().findAndRegisterModules()
        val encoded = json.writeValueAsString(route)
        assertEquals(route, json.readValue(encoded, com.noLate.schedule.application.EffectiveTransitRoute::class.java))
    }

    @Test
    fun `첫 열차가 일러도 같은 환승편을 유지하면 실제 도착 이후의 늘어난 대기를 표시한다`() {
        val result = calculate("4012", false, liveBoardingSeconds = 197,
            firstBoardingSeconds = 330, firstArrivalSeconds = 1450)
        assertTrue(result.accepted)
        val route = requireNotNull(result.effectiveTransitRoute)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals("2012", route.segments[3].rideTiming?.timetable?.trainNumber)
        // 첫 하차1317초 + 원본 도보243초 = 1560초. 1800초 승차까지 240초 기다린다.
        // 다음 편이 같아도 옛 시간표 하차1450초를 기준으로 대기/구간 분을 남겨두면 안 된다.
        assertEquals(4, route.segments[3].waitingMinutes)
        assertEquals(13, route.segments[3].durationMinutes) // 2281 - 1560 = 721초
        assertEquals(listOf(111L, 243L, 171L), route.segments.filter { it.kind.name == "WALK" }.map { it.durationSeconds })
        assertEquals(now.plusSeconds(330), route.segments[1].rideTiming?.timetable?.boardingAt)
    }

    @Test
    fun `첫 열차 지연이 같은 환승 대기에 흡수되면 줄어든 대기와 구간 시간을 표시한다`() {
        val result = calculate("4012", false, liveBoardingSeconds = 280)
        assertTrue(result.accepted)
        val route = requireNotNull(result.effectiveTransitRoute)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals("2012", route.segments[3].rideTiming?.timetable?.trainNumber)
        // 첫 하차1480초 + 원본 도보243초 = 1723초. 다음 승차까지77초, 하차까지558초다.
        assertEquals(2, route.segments[3].waitingMinutes)
        assertEquals(10, route.segments[3].durationMinutes)
        assertEquals(now.plusSeconds(1800), route.segments[3].rideTiming?.predictedBoardingAt)
        assertEquals(now.plusSeconds(2281), route.segments[3].rideTiming?.predictedArrivalAt)
    }

    @Test
    fun `실제 관측이 첫 열차보다 이르면 가능한 기존 환승도 더 이른 시간표로 재연결한다`() {
        val searches = mutableListOf<Instant>()
        val result = calculate("4012", false, liveBoardingSeconds = 197, firstBoardingSeconds = 330,
            firstArrivalSeconds = 1530, nextTransferTrain = true, searchedDepartures = searches,
            targetArrivalAt = now.plusSeconds(2452))
        // 기존 시간표만으로는 2014를 선택한다. 검증한 133초 조기 관측으로는
        // 원래 도보243초+여유60초를 유지해도 2012를 탈 수 있으며 5분 일찍 도착한다.
        assertEquals(1, searches.size)
        assertTrue(result.accepted)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals("2012", result.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(listOf(111L, 243L, 171L), result.effectiveTransitRoute?.segments
            ?.filter { it.kind.name == "WALK" }?.map { it.durationSeconds })
        assertEquals(now.plusSeconds(330), result.effectiveTransitRoute?.segments?.get(1)?.rideTiming?.timetable?.boardingAt)
        assertEquals(now.plusSeconds(197), result.effectiveTransitRoute?.segments?.get(1)?.rideTiming?.predictedBoardingAt)
    }

    @Test
    fun `완전한 동일 열차 ETA를 확보한 뒤 추가 경로 조회가 타임아웃이어도 정상 결과를 잃지 않는다`() {
        val searches = mutableListOf<Instant>()
        val result = calculate("4012", false, targetArrivalAt = now.plusSeconds(3600),
            searchedDepartures = searches, failAfterSuccessfulSearches = 1)
        assertEquals(2, searches.size)
        assertTrue(result.accepted)
        assertFalse(result.stale)
        assertNull(result.failureReason)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(now, result.recommendedDepartureAt)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals("4012", result.firstBoardingObservation?.trainNumber)
        assertEquals(listOf(111L, 243L, 171L), result.effectiveTransitRoute?.segments
            ?.filter { it.kind.name == "WALK" }?.map { it.durationSeconds })
    }

    @Test
    fun `Tmap 재조회가 분 경계를 조금 지나도 실제로 탈 수 있는 동일 열차를 다음 분으로 밀지 않는다`() {
        val searches = mutableListOf<Instant>()
        val evaluatedAt = now.plusMillis(2649)
        val result = calculate("4012", false, evaluatedAt = evaluatedAt, searchedDepartures = searches,
            firstBoardingSeconds = 210, liveBoardingSeconds = 195)

        // 접근 111초 + 승차 여유 60초: 15:02:54에 준비된다.
        // 무조건 다음 분(15:01)부터 계산하면 15:03:51이 되어 실시간15:03:15
        // (시간표15:03:30) 열차를 잘못 놓친다. 10/12 실제 관측의 15초 빠른 도착을 재현한다.
        assertEquals(listOf(now.plusSeconds(3)), searches)
        assertTrue(result.accepted)
        assertEquals("4012", result.firstBoardingObservation?.trainNumber)
        assertEquals(now.plusSeconds(3), result.recommendedDepartureAt)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals(listOf(111L, 243L, 171L), result.effectiveTransitRoute?.segments
            ?.filter { it.kind.name == "WALK" }?.map { it.durationSeconds })
    }

    @Test
    fun `초 단위 출발도 오래된 원천 다른 열차와 부족한 승차 여유를 정상으로 만들지 않는다`() {
        val evaluatedAt = now.plusMillis(2649)
        assertFalse(calculate("4999", false, evaluatedAt = evaluatedAt).accepted)
        assertFalse(calculate("4012", false, evaluatedAt = evaluatedAt, sourceAgeSeconds = 121).accepted)
        // 출발 40초 + 접근111초 + 여유60초 = 211초: 실제 승차210초보다 1초 늦다.
        assertFalse(calculate("4012", false, evaluatedAt = now.plusSeconds(40)).accepted)
    }

    @Test
    fun `ODsay는 초 단위 재투영을 지원한다고 가정하지 않고 기존 분 검색을 유지한다`() {
        val searches = mutableListOf<Instant>()
        val result = calculate("4012", false, odsay = true, evaluatedAt = now.plusMillis(2649),
            searchedDepartures = searches)
        assertEquals(listOf(now.plusSeconds(60)), searches)
        assertFalse(result.accepted)
    }

    @Test
    fun `ODsay 응답도 표준 매핑 후 두 시간표와 동일 첫 열차 관측을 연결해야 정상 ETA가 된다`() {
        val result = calculate("4012", false, odsay = true)
        assertTrue(result.accepted)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals("odsay", result.effectiveTransitRoute?.provider)
        assertEquals(now.plusSeconds(2461), result.predictedArrivalAt)
        assertEquals(42, result.travelMinutes)
        val walks = requireNotNull(result.effectiveTransitRoute).segments.filter { it.kind.name == "WALK" }
        assertEquals(listOf(2, 4, 3), walks.map { it.durationMinutes })
        assertTrue(walks.all { it.durationSeconds == null && it.walkingGeometry == null })
    }

    @Test
    fun `ODsay라도 첫 열차 불일치 오래된 관측 누락된 환승은 정상으로 승격하지 않는다`() {
        assertFalse(calculate("4999", false, odsay = true).accepted)
        assertFalse(calculate("4012", false, odsay = true, sourceAgeSeconds = 121).accepted)
        assertFalse(calculate("4012", true, odsay = true).accepted)
    }

    @Test
    fun `시간표만 정시인 미관측 후보는 더 먼 미래가 아니라 이전 실시간 열차를 탐색한다`() {
        val searches = mutableListOf<Instant>()
        val result = calculate("4151", false, liveBoardingSeconds = 360,
            targetArrivalAt = now.plusSeconds(85 * 60), plateauFixture = true, searchedDepartures = searches)
        assertEquals(listOf(11L, 6L, 1L).map { now.plusSeconds(it * 60) }, searches)
        assertTrue(result.accepted)
        assertEquals("4151", result.firstBoardingObservation?.trainNumber)
        assertTrue(requireNotNull(result.predictedArrivalAt) <= now.plusSeconds(85 * 60))
    }

    @Test
    fun `시간표상 지나간 첫 열차도 실제 지연으로 승차 가능하면 환승까지 연결하고 지연은 한 번만 적용한다`() {
        val result = calculate("4012", false, firstBoardingSeconds = -60, firstArrivalSeconds = 1140)
        assertTrue(result.accepted)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals("4012", result.firstBoardingObservation?.trainNumber)
        assertEquals(22, result.effectiveTransitRoute?.segments?.get(1)?.durationMinutes)
        assertEquals(2, result.effectiveTransitRoute?.segments?.get(1)?.waitingMinutes)
        val timing = requireNotNull(result.effectiveTransitRoute?.segments?.get(1)?.rideTiming)
        assertEquals(now.minusSeconds(60), timing.timetable.boardingAt)
        assertEquals(now.plusSeconds(210), timing.predictedBoardingAt)
        assertEquals(now.plusSeconds(1410), timing.predictedArrivalAt)
    }

    @Test
    fun `지연 후보가 다른 차량이거나 오래된 피드이거나 승차 여유 1초가 부족하면 채택하지 않는다`() {
        assertFalse(calculate("4999", false, firstBoardingSeconds = -60, firstArrivalSeconds = 1140).accepted)
        assertFalse(calculate("4012", false, firstBoardingSeconds = -60, firstArrivalSeconds = 1140,
            sourceAgeSeconds = 121).accepted)
        assertFalse(calculate("4012", false, firstBoardingSeconds = -60, firstArrivalSeconds = 1140,
            liveBoardingSeconds = 170).accepted)
    }

    @Test
    fun `시간표 승차와 하차가 모두 지났던 짧은 단일 승차도 실제 지연을 두 번 더하지 않는다`() {
        val result = calculate("4012", false, firstBoardingSeconds = -60, firstArrivalSeconds = -30,
            singleRide = true)
        assertTrue(result.accepted)
        assertEquals(now.plusSeconds(240), result.predictedArrivalAt)
        assertEquals(4, result.travelMinutes)
        assertEquals(3, result.effectiveTransitRoute?.segments?.get(1)?.durationMinutes)
        val timing = requireNotNull(result.effectiveTransitRoute?.segments?.get(1)?.rideTiming)
        assertEquals(now.plusSeconds(210), timing.predictedBoardingAt)
        assertEquals(now.plusSeconds(240), timing.predictedArrivalAt)
    }

    @Test
    fun `마감보다 51초 늦은 시간표를 1분씩 재조회하지 않고 이전 열차를 찾아 실시간 대조한다`() {
        val searches = mutableListOf<Instant>()
        val result = calculate("4151", false, liveBoardingSeconds = 360,
            targetArrivalAt = now.plusSeconds(75 * 60), plateauFixture = true, searchedDepartures = searches)

        // 실제 관측한 실패: 18:11 → 18:07 → 18:06은 같은 늦은 열차를 반복 선택했다.
        // 5분 probe도 실제 동일 차량/환승/마감 조건을 모두 통과해야만 채택한다.
        assertEquals(listOf(11L, 6L, 1L).map { now.plusSeconds(it * 60) }, searches)
        assertTrue(result.accepted)
        assertEquals("4151", result.firstBoardingObservation?.trainNumber)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertTrue(requireNotNull(result.predictedArrivalAt) <= now.plusSeconds(75 * 60))
    }

    @Test
    fun `이전 시간표 열차를 찾아도 실시간 차량이 다르면 정상 ETA로 승격하지 않는다`() {
        val searches = mutableListOf<Instant>()
        val result = calculate("4999", false, liveBoardingSeconds = 360,
            targetArrivalAt = now.plusSeconds(75 * 60), plateauFixture = true, searchedDepartures = searches)
        assertEquals(3, searches.size)
        assertFalse(result.accepted)
        assertTrue(result.stale)
    }

    @Test
    fun `두 구간 시간표와 같은 첫 열차 실시간을 연결해 환승 여유에 지연을 흡수한 ETA만 채택한다`() {
        val result = calculate(liveTrain = "4012", missingSecondRide = false)

        assertTrue(result.accepted)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(41, result.travelMinutes)
        assertEquals(now.plusSeconds(2452), result.predictedArrivalAt)
        assertEquals(TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE, result.transitTimingBasis)
        assertEquals(2, result.firstBoardingWaitMinutes)
        assertEquals("4012", result.firstBoardingObservation?.trainNumber)
    }

    @Test
    fun `반올림한 구간 분과 별개로 시간표 원본과 실제 계산한 승하차 예측을 보존한다`() {
        val segments = requireNotNull(calculate("4012", false).effectiveTransitRoute).segments
        val first = requireNotNull(segments[1].rideTiming)
        assertEquals("4012", first.timetable.trainNumber)
        assertEquals("fixture-timetable", first.timetable.provider)
        assertEquals(now, first.timetable.fetchedAt)
        assertEquals(now.plusSeconds(197), first.timetable.boardingAt)
        assertEquals(now.plusSeconds(1397), first.timetable.arrivalAt)
        assertEquals(now.plusSeconds(210), first.predictedBoardingAt)
        assertEquals(now.plusSeconds(1410), first.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.FIRST_BOARDING_REALTIME_ADJUSTED_TIMETABLE, first.basis)
        val transfer = requireNotNull(segments[3].rideTiming)
        assertEquals("2012", transfer.timetable.trainNumber)
        assertEquals(now.plusSeconds(1800), transfer.predictedBoardingAt)
        assertEquals(now.plusSeconds(2281), transfer.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, transfer.basis)
        assertTrue(segments.filter { it.kind.name == "WALK" }.all { it.rideTiming == null })
    }

    @Test
    fun `같은 노선 일반열차라도 시간표와 다른 열차면 보정 성공으로 채택하지 않는다`() {
        assertFalse(calculate(liveTrain = "4999", missingSecondRide = false).accepted)
    }

    @Test
    fun `첫 열차만 확인하고 미래 환승 시간표가 없으면 전체 ETA를 채택하지 않는다`() {
        val result = calculate(liveTrain = "4012", missingSecondRide = true)
        assertFalse(result.accepted)
        assertNull(result.predictedArrivalAt)
        assertEquals(65, result.travelMinutes)
    }

    @Test
    fun `첫 열차 지연으로 환승을 놓쳐도 같은 경로의 다음 시간표 열차를 연결해 도착을 다시 계산한다`() {
        val result = calculate("4012", false, liveBoardingSeconds = 407, nextTransferTrain = true)

        assertTrue(result.accepted)
        assertEquals(TrafficSource.LIVE_PROVIDER, result.source)
        assertEquals(now.plusSeconds(2752), result.predictedArrivalAt)
        assertEquals(46, result.travelMinutes)
        assertEquals(5, result.firstBoardingWaitMinutes)
        assertEquals("4012", result.firstBoardingObservation?.trainNumber)
        assertEquals(TransitTimingBasis.FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE, result.transitTimingBasis)
        assertEquals(now.plusSeconds(2752), result.effectiveTransitRoute?.arrivalAt)
        assertEquals(25, result.effectiveTransitRoute?.segments?.get(1)?.durationMinutes)
        assertEquals(13, result.effectiveTransitRoute?.segments?.get(3)?.durationMinutes)
        assertEquals(5, result.effectiveTransitRoute?.segments?.get(3)?.waitingMinutes)
        val first = requireNotNull(result.effectiveTransitRoute?.segments?.get(1)?.rideTiming)
        assertEquals(now.plusSeconds(407), first.predictedBoardingAt)
        assertEquals(now.plusSeconds(1607), first.predictedArrivalAt)
        val transfer = requireNotNull(result.effectiveTransitRoute?.segments?.get(3)?.rideTiming)
        assertEquals("2014", transfer.timetable.trainNumber)
        assertEquals(now.plusSeconds(2100), transfer.predictedBoardingAt)
        assertEquals(now.plusSeconds(2581), transfer.predictedArrivalAt)
        assertEquals(EffectiveTransitRideTimingBasis.TIMETABLE, transfer.basis)
    }

    @Test
    fun `환승 여유가 부족한 경우에도 다음 시간표 열차로 여유를 확보한 뒤에만 채택한다`() {
        val result = calculate("4012", false, liveBoardingSeconds = 347, nextTransferTrain = true)

        assertTrue(result.accepted)
        assertEquals(now.plusSeconds(2752), result.predictedArrivalAt)
    }

    @Test
    fun `다음 환승 열차가 없으면 놓친 환승을 정상 ETA로 승격하지 않는다`() {
        val result = calculate("4012", false, liveBoardingSeconds = 407)

        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_TRANSFER_MISSED, result.failureReason)
        assertNull(result.predictedArrivalAt)
    }

    @Test
    fun `다음 환승 열차로 회복해도 도착 마감을 넘기면 정시 도착 성공이 아니다`() {
        val result = calculate("4012", false, liveBoardingSeconds = 407,
            nextTransferTrain = true, targetArrivalAt = now.plusSeconds(2452))

        assertFalse(result.accepted)
        assertEquals(TrafficFailureReasons.TRANSIT_ON_TIME_ARRIVAL_UNAVAILABLE, result.failureReason)
        assertEquals(now.plusSeconds(2752), result.predictedArrivalAt)
        assertEquals(now, result.recommendedDepartureAt)
        assertEquals("4012", result.firstBoardingObservation?.trainNumber)
    }

    private fun calculate(
        liveTrain: String,
        missingSecondRide: Boolean,
        liveBoardingSeconds: Long = 210,
        nextTransferTrain: Boolean = false,
        targetArrivalAt: Instant? = null,
        plateauFixture: Boolean = false,
        searchedDepartures: MutableList<Instant>? = null,
        firstBoardingSeconds: Long = 197,
        firstArrivalSeconds: Long = 1397,
        sourceAgeSeconds: Long = 0,
        singleRide: Boolean = false,
        odsay: Boolean = false,
        evaluatedAt: Instant = now,
        failAfterSuccessfulSearches: Int? = null,
    ) = run {
        val mapper = jacksonObjectMapper()
        val response = mapper.readTree("""
          {"metaData":{"plan":{"itineraries":[{"totalTime":3888,"legs":[
            {"mode":"WALK","sectionTime":111},
            {"mode":"SUBWAY","sectionTime":2882,"service":1,"type":4,"route":"수도권 4호선",
              "routeId":"11004","start":{"name":"서울역"},"end":{"name":"사당"}},
            {"mode":"WALK","sectionTime":243},
            {"mode":"SUBWAY","sectionTime":481,"service":1,"type":2,"route":"수도권 2호선",
              "routeId":"11002","start":{"name":"사당"},"end":{"name":"신림"}},
            {"mode":"WALK","sectionTime":171}
          ]}]}}}
        """)
        val routeProvider = object : TransitJourneyProvider {
            private var calls = 0
            override val providerId = if (odsay) "odsay" else "tmap"
            override val supportsAlternativeRouteRecovery = false
            override val supportsSecondPrecisionDepartureProjection = !odsay
            override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
                searchedDepartures?.add(request.departureAt)
                if (failAfterSuccessfulSearches != null && calls++ >= failAfterSuccessfulSearches) {
                    throw java.net.SocketTimeoutException("synthetic follow-up timeout")
                }
                return (if (odsay) odsayJourney(request) else TmapTransitJourneyMapper().map(response, request, now))
                    .map { if (singleRide) it.copy(legs = it.legs.take(2)) else it }
            }
        }
        val timetable = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                val first = query.line == SeoulSubwayLine.LINE_4
                if (!first && missingSecondRide) return emptyList()
                if (plateauFixture) {
                    val times = if (first) listOf(
                        Triple("4151", 360L, 3240L), Triple("K4401", 630L, 3510L), Triple("4153", 840L, 3720L),
                    ) else listOf(
                        Triple("2378", 3600L, 4050L), Triple("2380", 3930L, 4380L), Triple("2382", 4110L, 4560L),
                    )
                    return times.map { (train, boarding, arrival) ->
                        TransitLegTimetable("fixture-timetable", query.line, LocalDate.of(2026, 10, 4),
                            train, "DOWN", TransitServiceClass.LOCAL, "FROM", "TO",
                            if (first) "오이도" else "성수", now.plusSeconds(boarding), now.plusSeconds(arrival), now)
                    }
                }
                val original = TransitLegTimetable("fixture-timetable", query.line, LocalDate.of(2026, 10, 4),
                    if (first) "4012" else "2012", "DOWN", TransitServiceClass.LOCAL, "FROM", "TO",
                    if (first) "오이도" else "성수", now.plusSeconds(if (first) firstBoardingSeconds else 1800),
                    now.plusSeconds(if (first) firstArrivalSeconds else 2281), now)
                return listOf(original) + if (!first && nextTransferTrain) {
                    listOf(original.copy(trainNumber = "2014", boardingAt = now.plusSeconds(2100),
                        arrivalAt = now.plusSeconds(2581)))
                } else emptyList()
            }
        }
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta("서울역", "4호선", null, "DOWN", 10)).thenReturn(listOf(
            TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선", stationName = "서울역",
                direction = "하행", destinationName = "오이도", expectedAt = now.plusSeconds(liveBoardingSeconds).toString(),
                sourceUpdatedAt = now.minusSeconds(sourceAgeSeconds).toString(), freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                express = false, subwayLineId = "1004", trainNumber = liveTrain),
        ))
        val matcher = TransitJourneyMatcher()
        val service = TransitEtaCalculationService(SelectedTransitRouteDecoder(mapper), listOf(routeProvider),
            // 이 fixture는 한 응답의 환승 재연결을 검증한다. 전체 경로 재검색은 별도 resolver 테스트가 소유한다.
            SafeDepartureResolver(matcher, maxSearches = if (plateauFixture || failAfterSuccessfulSearches != null) 3 else 1), FirstBoardingRealtimeOverlay(arrivals, Clock.fixed(now, ZoneOffset.UTC)),
            matcher, TransitTransferFeasibilityEvaluator(), TransitJourneyTimetableEnricher(listOf(timetable)))
        service.calculate(TrafficRequest(37.55, 126.97, 37.48, 126.93, ScheduleTravelMode.TRANSIT, 65,
            selectedRouteTravelMinutes = 65, evaluatedAt = evaluatedAt, plannedDepartureAt = if (plateauFixture) now.plusSeconds(660) else now,
            targetArrivalAt = targetArrivalAt,
            selectedRouteJson = """{"provider":"${routeProvider.providerId}","steps":[
              {"mode":"SUBWAY","routeId":"${if (odsay) "1000:4" else "11004"}","lineName":"수도권 4호선","serviceClass":"LOCAL","startName":"서울역","endName":"사당"}
              ${if (singleRide) "" else """,{"mode":"SUBWAY","routeId":"${if (odsay) "1000:2" else "11002"}","lineName":"수도권 2호선","serviceClass":"LOCAL","startName":"사당","endName":"신림"}"""}
            ]}"""))
    }

    private fun odsayJourney(request: TransitJourneySearchRequest): List<TransitJourney> {
        val json = jacksonObjectMapper()
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.of("Asia/Seoul"))
        val legs = json.createArrayNode()
        var cursor = request.departureAt
        listOf(2, 48, 4, 8, 3).forEachIndexed { index, minutes ->
            val end = cursor.plusSeconds(minutes * 60L)
            val node = json.createObjectNode().put("trafficType", if (index % 2 == 0) 3 else 1)
                .put("duration", minutes).put("startDateTime", format.format(cursor)).put("endDateTime", format.format(end))
            if (index % 2 == 1) {
                val line = if (index == 1) 4 else 2
                val names = if (index == 1) listOf("서울역", "숙대입구", "사당") else listOf("사당", "낙성대", "신림")
                node.put("startID", line * 100).put("endID", line * 100 + 2)
                    .put("startName", names.first()).put("endName", names.last()).put("wayCode", 2)
                node.set<com.fasterxml.jackson.databind.JsonNode>("lane", json.createObjectNode()
                    .put("subwayCityCode", 1000).put("subwayCode", line).put("name", "수도권 ${line}호선"))
                node.set<com.fasterxml.jackson.databind.JsonNode>("passStopList", json.createObjectNode().apply {
                    set<com.fasterxml.jackson.databind.JsonNode>("stations", json.createArrayNode().apply {
                        names.forEachIndexed { i, name -> add(json.createObjectNode().put("index", i)
                            .put("stationID", line * 100 + i).put("stationName", name)) }
                    })
                })
            }
            legs.add(node)
            cursor = end
        }
        val path = json.createObjectNode().put("pathType", 2).put("totalTime", 65)
            .put("startDateTime", format.format(request.departureAt)).put("endDateTime", format.format(cursor))
        path.set<com.fasterxml.jackson.databind.JsonNode>("rps", legs)
        val result = json.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>("paths", path)
        return OdsayTransitJourneyMapper().map(json.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>("result", result), request, now)
    }
}
