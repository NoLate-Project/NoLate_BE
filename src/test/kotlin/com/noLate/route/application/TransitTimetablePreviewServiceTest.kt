package com.noLate.route.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.port.DatedTransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.port.TransitWalkingTimeProvider
import com.noLate.eta.application.port.VerifiedWalkingTime
import com.noLate.eta.application.port.VerifiedAccessWalk
import com.noLate.eta.application.port.BoundaryWalkRefinement
import com.noLate.eta.application.port.WalkingPathRefinement
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.application.transit.SeoulBusEta
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.application.transit.TransitWalkingTimeEnricher
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.transit.domain.SeoulBusRouteStop
import com.noLate.transit.infrastructure.SeoulBusPairedArrival
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TransitTimetablePreviewServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-15T06:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-11-15")
    private val json = jacksonObjectMapper()
    private val queries = mutableListOf<TransitTimetableQuery>()
    private val routeSearchTimes = mutableListOf<Instant>()
    private var routeCalls = 0
    private val subway = TransitLegMode.SUBWAY
    private val local = TransitServiceClass.LOCAL
    private val line4 = SeoulSubwayLine.LINE_4
    private val line2 = SeoulSubwayLine.LINE_2

    @Test
    fun `버스 미리보기는 같은 차량의 하차와 목적지 도보를 마감 시각에 맞춘다`() {
        val now = clock.instant()
        val routeId = "100100057"
        val board = SeoulBusRouteStop(routeId, 71, "122000184", "23287", "승차", "방면", null, 37.0, 127.0)
        val middle = board.copy(sequence = 72, stationId = "122000185", arsId = "23288", name = "경유")
        val alight = board.copy(sequence = 73, stationId = "122000186", arsId = "23289", name = "하차")
        val stops = listOf(board, middle, alight)
        val client = mock<SeoulTransitArrivalClient>()
        whenever(client.getBusRouteDiagnosticStops(routeId)).thenReturn(stops)
        val source = now.minusSeconds(11)
        whenever(client.getPairedBusArrivals(stops)).thenReturn(listOf(
            SeoulBusPairedArrival(now.plusSeconds(302), now.plusSeconds(438), source, now),
            SeoulBusPairedArrival(now.plusSeconds(422), now.plusSeconds(558), source, now),
        ))
        val journey = TransitJourney("odsay", now, now, now.plusSeconds(900), 15, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 3, durationSeconds = 157),
            TransitJourneyLeg(1, TransitLegMode.BUS, 6,
                from = TransitStop(providerStopId = "160678", localStopId = board.stationId, name = board.name),
                to = TransitStop(providerStopId = "106478", localStopId = alight.stationId, name = alight.name),
                line = TransitLine(providerRouteId = "2822539", localRouteId = "110000004", name = "8146"),
                alternativeBusLines = listOf(TransitLine(providerRouteId = "1440", localRouteId = routeId, name = "360"))),
            TransitJourneyLeg(2, TransitLegMode.WALK, 6),
        ), now)
        val provider = object : TransitJourneyProvider {
            override val providerId = "odsay"
            override fun search(request: TransitJourneySearchRequest): List<TransitJourney> = listOf(journey)
        }
        val service = TransitTimetablePreviewService(listOf(provider), SelectedTransitRouteDecoder(json),
            TransitJourneyMatcher(), TransitJourneyTimetableEnricher(emptyList()), clock, EtaCalculationDeadline(),
            seoulBusEta = SeoulBusEta(client, 60, 120))
        val request = TransitBusPreviewRequest(127.027619, 37.497952, 127.036456, 37.500622,
            targetArrivalAt = now.plusSeconds(850), route = json.readTree("""
                {"provider":"odsay","transitLegs":[{"kind":"BUS","providerRouteId":"2822539",
                "lineName":"8146","startID":"160678","endID":"106478","startName":"승차","endName":"하차"}]}
            """.trimIndent()), alternateProviderRouteId = "1440")
        val result = requireNotNull(service.previewBus(request))
        assertEquals(now.plusSeconds(85), result.departureAt)
        assertEquals(now.plusSeconds(302), result.boardingAt)
        assertEquals(now.plusSeconds(438), result.alightingAt)
        assertEquals(now.plusSeconds(798), result.arrivalAt)
        assertEquals(source, result.sourceUpdatedAt)
        assertEquals(157, result.accessSeconds)
        assertEquals(360, result.egressSeconds)
        assertEquals("1440", result.providerRouteId)
        verify(client).getPairedBusArrivals(stops)
        assertNull(service.previewBus(request.copy(alternateProviderRouteId = "unlisted")))
    }

    @Test
    fun `버스 환승 미리보기는 도착 목표에 맞는 가장 늦은 출발을 선택한다`() {
        val now = clock.instant()
        val client = mock<SeoulTransitArrivalClient>()
        val source = now.minusSeconds(11)
        fun prediction(board: Long, alight: Long) =
            SeoulBusPairedArrival(now.plusSeconds(board), now.plusSeconds(alight), source, now)
        fun ride(sequence: Int, routeId: String, boardId: String, alightId: String,
                 arrivals: List<SeoulBusPairedArrival>): TransitJourneyLeg {
            val board = SeoulBusRouteStop(routeId, 1, boardId, "23287", "승차", "방면", null, 37.0, 127.0)
            val alight = board.copy(sequence = 2, stationId = alightId, arsId = "23288", name = "하차")
            val stops = listOf(board, alight)
            whenever(client.getBusRouteDiagnosticStops(routeId)).thenReturn(stops)
            whenever(client.getPairedBusArrivals(stops)).thenReturn(arrivals)
            return TransitJourneyLeg(sequence, TransitLegMode.BUS, 3,
                from = TransitStop(providerStopId = boardId, localStopId = boardId, name = board.name),
                to = TransitStop(providerStopId = alightId, localStopId = alightId, name = alight.name),
                line = TransitLine(providerRouteId = routeId, localRouteId = routeId, name = "버스 $sequence"))
        }
        val alternate = ride(1, "100100059", "122000184", "122000185", listOf(prediction(200, 300)))
        val journey = TransitJourney("odsay", now, now, now.plusSeconds(900), 15, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 1),
            ride(1, "100100057", "122000184", "122000185", listOf(prediction(240, 420), prediction(360, 540)))
                .copy(alternativeBusLines = listOf(requireNotNull(alternate.line))),
            TransitJourneyLeg(2, TransitLegMode.WALK, 2),
            ride(3, "100100058", "122000186", "122000187",
                listOf(prediction(480, 600), prediction(600, 730), prediction(720, 850))),
            TransitJourneyLeg(4, TransitLegMode.WALK, 2),
        ), now)
        val provider = object : TransitJourneyProvider {
            override val providerId = "odsay"
            override fun search(request: TransitJourneySearchRequest): List<TransitJourney> = listOf(journey)
        }
        val service = TransitTimetablePreviewService(listOf(provider), SelectedTransitRouteDecoder(json),
            TransitJourneyMatcher(), TransitJourneyTimetableEnricher(emptyList()), clock, EtaCalculationDeadline(),
            seoulBusEta = SeoulBusEta(client, 60, 120))
        val request = TransitBusPreviewRequest(127.027619, 37.497952, 127.036456, 37.500622,
            targetArrivalAt = now.plusSeconds(980), route = json.readTree("""
                {"provider":"odsay","transitLegs":[
                  {"kind":"BUS","providerRouteId":"100100057","startID":"122000184","endID":"122000185"},
                  {"kind":"WALK","durationMinutes":2},
                  {"kind":"BUS","providerRouteId":"100100058","startID":"122000186","endID":"122000187"}]}
            """.trimIndent()))

        val later = requireNotNull(service.previewBus(request))
        assertEquals(now.plusSeconds(240), later.departureAt)
        assertEquals(now.plusSeconds(360), later.boardingAt)
        assertEquals(now.plusSeconds(970), later.arrivalAt)
        verify(client).getBusRouteDiagnosticStops("100100057")
        verify(client).getBusRouteDiagnosticStops("100100058")

        val earlier = requireNotNull(service.previewBus(request.copy(targetArrivalAt = now.plusSeconds(930))))
        assertEquals(now.plusSeconds(120), earlier.departureAt)
        assertEquals(now.plusSeconds(240), earlier.boardingAt)
        assertEquals(now.plusSeconds(850), earlier.arrivalAt)
        val rescued = requireNotNull(service.previewBus(request.copy(
            targetArrivalAt = now.plusSeconds(820), alternateProviderRouteId = "100100059",
        )))
        assertEquals("100100059", rescued.providerRouteId)
        assertEquals("100100058", rescued.transfer?.secondProviderRouteId)
        assertEquals(now.plusSeconds(80), rescued.departureAt)
        assertEquals(now.plusSeconds(720), rescued.arrivalAt)
        verify(client).getBusRouteDiagnosticStops("100100059")
        verify(client, times(3)).getBusRouteDiagnosticStops("100100058")

        val savedSelection = request.copy(targetArrivalAt = now.plusSeconds(820),
            route = json.readTree(request.route.toString().replace("100100057", "100100059")))
        assertEquals(rescued, service.previewBus(savedSelection))
    }

    @Test
    fun `목표에서 역산하며 환승 도보와 여유에 부족한 열차를 건너뛴다`() {
        val service = service()
        val result = requireNotNull(service.preview(request()).preview)
        assertEquals(at("06:07:00"), result.departureAt)
        assertEquals(at("07:11:00"), result.arrivalAt)
        assertEquals(listOf("4317", "2301"), result.rides.map { it.trainNumber })
        assertEquals(listOf("낙성대", "창동"), queries.map { it.nextStopName })
        assertEquals(1, routeCalls)
    }

    @Test
    fun `고정 출발과 접근 도보 및 승차 여유가 첫 열차 선택에 반영된다`() {
        val service = service()
        val chosen = at("06:10:30")
        val result = requireNotNull(service.preview(request().copy(
            targetArrivalAt = null, requestedDepartureAt = chosen,
        )).preview)
        assertEquals(chosen, result.departureAt)
        assertEquals(at("07:16:00"), result.arrivalAt)
        assertEquals(listOf("4319", "2302"), result.rides.map { it.trainNumber })
        assertEquals(listOf(at("06:13:30"), at("07:02:30")), queries.map { it.notBefore })
        assertEquals(listOf(at("06:10:00")), routeSearchTimes)
        assertEquals(at("07:02:30"), result.transferTimings.single().confidenceReadyAt)
        assertEquals(1, routeCalls)
    }

    @Test
    fun `검증된 같은 도보 경로의 초를 미리보기에 적용하면 안전 여유를 유지하며 이른 환승편에 연결된다`() {
        val provider = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime =
                VerifiedWalkingTime(137, clock.instant())
        }
        val service = service(TransitWalkingTimeEnricher(listOf(provider)))
        val result = requireNotNull(service.preview(request().copy(
            targetArrivalAt = null, requestedDepartureAt = at("06:10:00"),
        )).preview)
        assertEquals(listOf("4319", "2301"), result.rides.map { it.trainNumber })
        assertEquals(at("07:11:00"), result.arrivalAt)
        val transfer = result.transferTimings.single()
        assertEquals(137, transfer.walkingSeconds)
        assertEquals(60, transfer.requiredMarginSeconds)
        assertEquals(at("07:01:47"), transfer.confidenceReadyAt)
    }

    @Test
    fun `출발과 도착 기준을 함께 주면 외부 조회하지 않는다`() {
        assertNull(service().preview(request().copy(requestedDepartureAt = at("06:10:00"))).preview)
        assertEquals(0, routeCalls)
    }

    @Test
    fun `지원하지 않는 소수초 출발은 공급자를 호출하기 전에 거절한다`() {
        assertNull(service().preview(request().copy(
            targetArrivalAt = null, requestedDepartureAt = at("06:10:30").plusMillis(500),
        )).preview)
        assertEquals(0, routeCalls)
    }

    @Test
    fun `다른 운행일은 외부 조회하지 않고 경유역이 다른 응답은 시간표에 연결하지 않는다`() {
        val service = service()
        assertNull(service.preview(request().copy(targetArrivalAt = Instant.parse("2026-11-16T07:12:00Z"))).preview)
        assertEquals(0, routeCalls)
        assertNull(service.preview(request().copy(nextStops = listOf("창동", "방배"))).preview)
        assertEquals(1, routeCalls)
        assertEquals(emptyList<TransitTimetableQuery>(), queries)
    }

    @Test
    fun `다음 날짜 출발은 ODsay 정차역이 일치할 때 접근과 환승 준비 시각부터 운행일 시간표를 묻는다`() {
        val date = this.date.plusDays(1)
        val tomorrow = { time: String -> Instant.parse("${date}T${time}Z") }
        val requests = mutableListOf<Pair<TransitTimetableQuery, List<String>>>()
        val dated = DatedTransitTimetableProvider { query, stops ->
            requests.add(query to stops)
            val first = query.line == line4
            val sample = if (first) ride(line4, "K4651", "06:06:30", "06:54:30")
            else ride(line2, "2340", "06:59:30", "07:07:00")
            sample.copy(
                serviceDate = date, boardingAt = sample.boardingAt.plusSeconds(86_400),
                arrivalAt = sample.arrivalAt.plusSeconds(86_400),
            )
        }
        val input = request().copy(
            targetArrivalAt = null, requestedDepartureAt = tomorrow("06:00:30"),
            orderedStops = listOf(listOf("노원", "창동", "사당"), listOf("사당", "낙성대", "신림")),
        )
        val service = service(datedTimetable = dated)
        val preview = requireNotNull(service.preview(input).preview)
        assertEquals(tomorrow("06:00:30"), preview.departureAt)
        assertEquals(tomorrow("07:09:00"), preview.arrivalAt)
        assertEquals(listOf("K4651", "2340"), preview.rides.map { it.trainNumber })
        assertEquals(listOf(tomorrow("06:03:30"), tomorrow("06:58:30")), requests.map { it.first.notBefore })
        assertEquals(listOf(tomorrow("06:00:00")), routeSearchTimes)
        assertEquals(input.orderedStops, requests.map { it.second })
        assertEquals(listOf(date, date), requests.map { it.first.serviceDate })
        assertEquals(emptyList<TransitTimetableQuery>(), queries)

        assertNull(service.preview(input.copy(orderedStops = listOf(
            listOf("노원", "창동", "서울역", "사당"), listOf("사당", "낙성대", "신림"),
        ))).preview)
        assertEquals(2, requests.size)
    }

    @Test
    fun `다음 날짜 목표 도착에 첫 조회가 늦으면 이전 열차를 조회하고 통과한 계획의 출발을 역산한다`() {
        val tomorrow = date.plusDays(1)
        val calls = mutableListOf<TransitTimetableQuery>()
        var intermediateAvailable = false
        var refinementUnavailable = false
        val dated = DatedTransitTimetableProvider { query, _ ->
            calls.add(query)
            if (refinementUnavailable && query.line == line4 && query.notBefore == at("06:10:01").plusSeconds(86_400)) {
                throw IllegalStateException("Dated timetable is unavailable")
            }
            val rides = if (query.line == line4) buildList {
                add(ride(line4, "4317", "06:10:00", "06:54:00"))
                if (intermediateAvailable) add(ride(line4, "4318", "06:11:30", "06:55:00"))
                add(ride(line4, "4319", "06:15:00", "06:58:30"))
            } else listOf(
                ride(line2, "2301", "07:02:00", "07:09:00"),
                ride(line2, "2302", "07:07:00", "07:14:00"),
            )
            rides.map { it.copy(serviceDate = tomorrow, boardingAt = it.boardingAt.plusSeconds(86_400),
                arrivalAt = it.arrivalAt.plusSeconds(86_400)) }
                .firstOrNull { it.boardingAt >= query.notBefore }
        }
        val ordered = listOf(listOf("노원", "창동", "사당"), listOf("사당", "낙성대", "신림"))
        val input = request().copy(targetArrivalAt = at("07:12:00").plusSeconds(86_400), orderedStops = ordered)
        val service = service(datedTimetable = dated)
        val preview = requireNotNull(service.preview(input).preview)
        assertEquals(at("06:07:00").plusSeconds(86_400), preview.departureAt)
        assertEquals(at("07:11:00").plusSeconds(86_400), preview.arrivalAt)
        assertEquals(listOf("4317", "2301"), preview.rides.map { it.trainNumber })
        assertEquals(listOf("06:12:00", "07:02:30", "06:07:00", "06:58:00", "06:10:01")
            .map { at(it).plusSeconds(86_400) }, calls.map { it.notBefore })
        assertEquals(1, routeCalls)
        assertEquals(emptyList<TransitTimetableQuery>(), queries)

        calls.clear()
        assertNull(service.preview(input.copy(targetArrivalAt = at("07:06:00").plusSeconds(86_400))).preview)
        assertEquals(3, calls.count { it.line == line4 })
        assertEquals(1, calls.count { it.line == line2 })

        calls.clear()
        intermediateAvailable = true
        val improved = requireNotNull(service.preview(input).preview)
        assertEquals(at("06:08:30").plusSeconds(86_400), improved.departureAt)
        assertEquals(preview.arrivalAt, improved.arrivalAt)
        assertEquals(listOf("4318", "2301"), improved.rides.map { it.trainNumber })
        assertEquals(preview.transferTimings.single().walkingSeconds, improved.transferTimings.single().walkingSeconds)
        assertEquals(3, calls.count { it.line == line4 })

        calls.clear()
        refinementUnavailable = true
        assertEquals(preview, service.preview(input).preview)
        assertEquals(3, calls.count { it.line == line4 })
    }

    @Test
    fun `자정 직후 도착 목표는 전날 운행일의 열차와 마지막 도보를 사용한다`() {
        val serviceDay = date.plusDays(1)
        val firstBoarding = Instant.parse("2026-11-16T13:55:00Z")
        val secondBoarding = Instant.parse("2026-11-16T14:47:00Z")
        val calls = mutableListOf<TransitTimetableQuery>()
        val dated = DatedTransitTimetableProvider { query, _ ->
            calls.add(query)
            val first = query.line == line4
            val boardingAt = if (first) firstBoarding else secondBoarding
            if (boardingAt < query.notBefore) null else ride(
                query.line, if (first) "4317" else "2301", "06:10:00", "06:54:00",
            ).copy(serviceDate = serviceDay, boardingAt = boardingAt,
                arrivalAt = if (first) Instant.parse("2026-11-16T14:40:00Z")
                else Instant.parse("2026-11-16T15:00:30Z"))
        }
        val preview = requireNotNull(service(datedTimetable = dated).preview(request().copy(
            targetArrivalAt = Instant.parse("2026-11-16T15:05:00Z"),
            orderedStops = listOf(listOf("노원", "창동", "사당"), listOf("사당", "낙성대", "신림")),
        )).preview)
        assertEquals(firstBoarding.minusSeconds(180), preview.departureAt)
        assertEquals(Instant.parse("2026-11-16T15:02:30Z"), preview.arrivalAt)
        assertEquals(listOf("4317", "2301"), preview.rides.map { it.trainNumber })
        assertEquals(setOf(serviceDay), calls.map { it.serviceDate }.toSet())
    }

    @Test
    fun `당일 순환선의 인접 승차는 날짜 지정 응답으로 전체 연결을 확인한다`() {
        val calls = mutableListOf<Pair<TransitTimetableQuery, List<String>>>()
        val dated = DatedTransitTimetableProvider { query, stops ->
            calls.add(query to stops)
            if (query.line == line4) ride(line4, "4319", "06:15:00", "06:58:30")
            else ride(line2, "2302", "07:07:00", "07:09:00").copy(toStationCode = "0227")
        }
        val input = request(adjacentRingRide = true).copy(targetArrivalAt = null, requestedDepartureAt = at("06:10:30"))

        val preview = requireNotNull(service(datedTimetable = dated, adjacentRingRide = true).preview(input).preview)
        assertEquals(listOf("4319", "2302"), preview.rides.map { it.trainNumber })
        assertEquals(at("07:11:00"), preview.arrivalAt)
        assertEquals(listOf(listOf("노원", "창동", "사당"), listOf("사당", "낙성대")), calls.map { it.second })
        assertEquals(listOf(date, date), calls.map { it.first.serviceDate })
        assertEquals(null, calls.last().first.directionCode)
        assertEquals(emptyList<TransitTimetableQuery>(), queries)
        assertNull(service(adjacentRingRide = true).preview(input).preview)
        assertEquals(emptyList<TransitTimetableQuery>(), queries)
    }

    @Test
    fun `시간표를 못 찾으면 실제 출입구 도보만 추정값에 돌려준다`() {
        val access = TransitWalkingQueryEndpoints(TransitWalkingCoordinate(37.655, 127.061),
            TransitWalkingCoordinate(37.656, 127.062))
        val walking = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime? = null
            override fun refineBoundaries(access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
                                          unchanged: List<WalkingPathRefinement>) =
                BoundaryWalkRefinement(access?.let { VerifiedAccessWalk(103, 112, clock.instant(), it.start, it.end) }, null, emptyMap())
        }
        val service = service(TransitWalkingTimeEnricher(listOf(walking)), missingAccess = access)
        val response = service.preview(request().copy(targetArrivalAt = null, requestedDepartureAt = at("06:50:00")))
        assertNull(response.preview)
        assertEquals(103L, response.missingEntranceWalk?.seconds)
        assertEquals(112L, response.missingEntranceWalk?.distanceMeters)
        assertEquals(1, routeCalls)
    }

    private fun service(
        walkingTimeEnricher: TransitWalkingTimeEnricher = TransitWalkingTimeEnricher(),
        datedTimetable: DatedTransitTimetableProvider? = null,
        adjacentRingRide: Boolean = false,
        missingAccess: TransitWalkingQueryEndpoints? = null,
    ): TransitTimetablePreviewService {
        val odsay = object : TransitJourneyProvider {
            override val providerId = "odsay"
            override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
                routeCalls++
                routeSearchTimes.add(request.departureAt)
                // Real maasRP returns the SearchTime minute, which its mapper rejects if earlier than the request.
                if (request.departureAt.epochSecond % 60L != 0L || request.departureAt.nano != 0) return emptyList()
                return listOf(TransitJourney(
                    providerId, request.departureAt, request.departureAt, request.departureAt.plusSeconds(3600),
                    60, listOf(
                        walk(0, 120),
                        TransitJourneyLeg(1, subway, 46, from = TransitStop("411", name = "노원"),
                            to = TransitStop("433", name = "사당"), line = TransitLine("1000:4",
                                name = "4호선", serviceClass = local, seoulSubwayLine = line4),
                            directionCode = "DOWN", nextStopName = "창동", orderedStopNames = listOf("노원", "창동", "사당")),
                        walk(2, 180).copy(durationSeconds = null, walkingGeometry = TransitWalkingGeometry(distanceMeters = 170)),
                        TransitJourneyLeg(3, subway, if (adjacentRingRide) 2 else 8,
                            from = TransitStop("226", name = "사당"),
                            to = if (adjacentRingRide) TransitStop("227", name = "낙성대") else TransitStop("230", name = "신림"),
                            line = TransitLine("1000:2",
                                name = "2호선", serviceClass = local, seoulSubwayLine = line2),
                            nextStopName = "낙성대",
                            orderedStopNames = if (adjacentRingRide) listOf("사당", "낙성대")
                                else listOf("사당", "낙성대", "신림")),
                        walk(4, 120),
                    ).let { legs -> if (missingAccess == null) legs else
                        legs.drop(1).mapIndexed { index, leg -> leg.copy(sequence = index) } }, clock.instant(),
                    entranceWalkEndpoints = missingAccess,
                ))
            }
        }
        val timetable = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries.add(query)
                return when (query.line) {
                    line4 -> listOf(
                        ride(line4, "4317", "06:10:00", "06:54:00"),
                        ride(line4, "4319", "06:15:00", "06:58:30"),
                    )
                    line2 -> listOf(
                        ride(line2, "2301", "07:02:00", "07:09:00"),
                        ride(line2, "2302", "07:07:00", "07:14:00"),
                    )
                    else -> emptyList()
                }
            }
        }
        return TransitTimetablePreviewService(listOf(odsay), SelectedTransitRouteDecoder(json), TransitJourneyMatcher(),
            TransitJourneyTimetableEnricher(listOf(timetable)), clock, EtaCalculationDeadline(), walkingTimeEnricher, datedTimetable)
    }

    private fun walk(index: Int, seconds: Long) =
        TransitJourneyLeg(index, TransitLegMode.WALK, ((seconds + 59) / 60).toInt(), durationSeconds = seconds)

    private fun ride(line: SeoulSubwayLine, train: String, boarding: String, arrival: String) =
        TransitLegTimetable("seoul-timetable", line, date, train, if (line == line4) "DOWN" else "UP", local,
            if (line == line4) "0411" else "0226", if (line == line4) "0433" else "0230",
            "종착역", at(boarding), at(arrival), clock.instant())

    private fun request(adjacentRingRide: Boolean = false) = TransitTimetablePreviewRequest(
        127.061, 37.655, 126.929, 37.484, at("07:12:00"),
        json.readTree("""{"provider":"odsay","transitLegs":[
            {"kind":"SUBWAY","providerRouteId":"1000:4","lineName":"4호선","serviceClass":"LOCAL",
             "startID":"411","endID":"433","directionCode":"DOWN"},
            {"kind":"SUBWAY","providerRouteId":"1000:2","lineName":"2호선","serviceClass":"LOCAL",
             "startID":"226","endID":"${if (adjacentRingRide) "227" else "230"}","directionCode":"DOWN"}
        ]}"""), listOf("창동", "낙성대"),
    )

    private fun at(clock: String) = Instant.parse("${date}T${clock}Z")
}
