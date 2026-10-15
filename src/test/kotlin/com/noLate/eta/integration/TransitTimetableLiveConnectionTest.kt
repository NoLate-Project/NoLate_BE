package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.noLate.route.application.TransitRouteProvider
import com.noLate.route.domain.TransitRouteRequest
import com.noLate.calendar.domain.CalendarDayCache
import com.noLate.calendar.domain.PublicHoliday
import com.noLate.calendar.infrastructure.CalendarDayCacheRepository
import com.noLate.calendar.infrastructure.PublicHolidayRepository
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.*
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.infrastructure.seoul.SeoulTimetableServiceDayResolver
import com.noLate.eta.infrastructure.seoul.SeoulTransitTimetableProvider
import com.noLate.eta.infrastructure.tmap.TmapTransitJourneyMapper
import com.noLate.eta.infrastructure.tmap.TmapTransitJourneyProvider
import com.noLate.route.infrastructure.TmapTransitRouteClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.Properties

/**
 * 실제 Tmap + 서울 시간표/실시간을 실제 ETA 클래스에 연결하는 수동 검증이다.
 * Spring context/worker를 띄우지 않으며 DB는 read-only transaction의 SELECT 후 rollback만 한다.
 * 시뮬레이터 E2E나 실제 도착 오차 검증이 아니다. 비밀값/공급자 URL/원문 예외는 출력하지 않는다.
 */
@Tag("external")
@Tag("transit-timetable-live")
@EnabledIfSystemProperty(named = "nolate.eta.live-probe", matches = "true")
class TransitTimetableLiveConnectionTest {
    private val mapper = jacksonObjectMapper()
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `실제 노원 사당 신림 여정의 시간표와 ETA 연결을 일정 변경 없이 확인한다`() {
        try {
            probe()
        } catch (error: AssertionError) {
            throw error // 이 클래스의 검증 메시지는 공개 상태값만 포함한다.
        } catch (error: Exception) {
            // RestClient 예외는 인증키를 담은 URL이 포함될 수 있으므로 cause를 전달하지 않는다.
            fail<Unit>("실제 ETA 연결 검증 중 ${error.javaClass.simpleName}; 원문과 인증정보는 출력하지 않습니다.")
        }
    }

    private fun probe() {
        val settings = Properties().apply {
            Files.newBufferedReader(Path.of("env.local.properties")).use(::load)
        }
        fun setting(name: String, default: String = "") = System.getenv(name) ?: settings.getProperty(name, default)
        System.getProperty("nolate.eta.probe-comparison-evidence")?.let {
            probeScopedComparison(settings, it)
            return
        }
        val now = Instant.now()
        val explicitDeparture = System.getProperty("nolate.eta.probe-departure-at")
        val departure = explicitDeparture?.let(Instant::parse)
            ?: now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
        // 과거 시각이나 무제한 미래 탐색은 허용하지 않는다. 비교 시각은 명시적인 UTC instant다.
        require(departure.nano == 0 && departure.isAfter(now) && departure <= now.plusSeconds(24 * 60 * 60))
        val timetableOnlyValue = System.getProperty("nolate.eta.probe-timetable-only", "false")
        require(timetableOnlyValue in setOf("true", "false"))
        val timetableOnly = timetableOnlyValue == "true"
        val requireRealtimeValue = System.getProperty("nolate.eta.probe-require-realtime", "false")
        require(requireRealtimeValue in setOf("true", "false"))
        val requireRealtime = requireRealtimeValue == "true"
        require(!timetableOnly || !requireRealtime) { "시간표 비교는 실시간 보정 검증을 대신하지 않습니다." }
        val comparisonWalkSeconds = System.getProperty("nolate.eta.probe-walk-seconds")?.let { value ->
            require(timetableOnly) { "도보 치환은 비교 전용이며 실제 ETA 채택 검증에는 사용할 수 없습니다." }
            value.split(',').map(String::toLong).also { seconds ->
                require(seconds.size == 3 && seconds.all { it in 0..1800 })
            }
        }
        val date = departure.atZone(zone).toLocalDate()
        val day = readCalendar(settings, date)
        val days = mock<CalendarDayCacheRepository>()
        val holidays = mock<PublicHolidayRepository>()
        // 저장소만 읽기 전용 SQL snapshot으로 치환한다. 날짜 판정은 운영 코드 그대로 사용한다.
        whenever(days.findById(date)).thenReturn(Optional.ofNullable(day.first))
        whenever(holidays.findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(date, date)).thenReturn(day.second)
        val serviceDay = SeoulTimetableServiceDayResolver(days, holidays)
        val week = serviceDay.weekTag(date)
        println(mapper.writeValueAsString(mapOf("stage" to "service_day", "date" to date.toString(),
            "weekTag" to week, "holidayCacheConfirmed" to (day.first?.holidaysSyncedAt != null))))
        assertNotNull(week, "대상 날짜의 휴일 캐시가 없거나 오래돼 운행일을 확인할 수 없습니다.")

        val timetable = SeoulTransitTimetableProvider(
            setting("SEOUL_TIMETABLE_API_KEY", setting("SEOUL_OPENAPI_KEY")),
            setting("SEOUL_TIMETABLE_BASE_URL", "http://openapi.seoul.go.kr:8088"), serviceDay,
            allowInsecureHttp = setting("SEOUL_TIMETABLE_ALLOW_INSECURE_HTTP") == "true",
        )
        val routeClient = TmapTransitRouteClient(
            setting("TMAP_APP_KEY"), setting("TMAP_BASE_URL", "https://apis.openapi.sk.com"),
        )
        var publicRouteResponse: JsonNode? = null
        // 아래에 고정된 공개 역 좌표 요청의 도보 경계만 관찰한다. 인증 헤더·URL은 기록하지 않는다.
        val routeAudit = object : TransitRouteProvider {
            override fun providerId() = "tmap"
            override fun getRoutes(request: TransitRouteRequest): JsonNode =
                routeClient.getRoutes(request).also { publicRouteResponse = it }
        }
        val tmap = TmapTransitJourneyProvider(listOf(routeAudit), TmapTransitJourneyMapper())
        // 공개 역 좌표만 사용한다. 개인 일정/위치는 조회하거나 변경하지 않는다.
        // Compare the saved synthetic route's exact street coordinates with the map.
        // Named public fixtures only; never accept arbitrary/private coordinates from CLI.
        val coordinateSet = System.getProperty("nolate.eta.probe-coordinate-set", "public-stations")
        require(coordinateSet in setOf("public-stations", "saved-test-367", "public-exit-path-points"))
        val request = if (coordinateSet == "saved-test-367") {
            TransitJourneySearchRequest(37.65636195, 127.06342739, 37.48426844, 126.92977778, departure, 180)
        } else if (coordinateSet == "public-exit-path-points") {
            // Previously observed public WALK vertices near Nowon exit 10 / Sillim exit 2.
            // These are exact shared INPUT points, not verified entrance/platform locations.
            TransitJourneySearchRequest(37.656597, 127.06401, 37.483917, 126.92989, departure, 180)
        } else TransitJourneySearchRequest(37.6551, 127.0610, 37.4842, 126.9297, departure, 180)
        val journeys = tmap.search(request)
        val selected = journeys.firstOrNull { journey ->
            val rides = journey.rideLegs
            rides.size == 2 && rides[0].line?.seoulSubwayLine == SeoulSubwayLine.LINE_4 &&
                rides[1].line?.seoulSubwayLine == SeoulSubwayLine.LINE_2 &&
                rides[0].from?.name?.startsWith("노원") == true && rides[0].to?.name?.startsWith("사당") == true &&
                rides[1].from?.name?.startsWith("사당") == true && rides[1].to?.name?.startsWith("신림") == true
        }
        println(mapper.writeValueAsString(mapOf("stage" to "route", "observedAt" to Instant.now().toString(),
            "requestedDepartureAt" to departure.toString(), "candidateCount" to journeys.size,
            "origin" to mapOf("lat" to request.originLat, "lng" to request.originLng),
            "destination" to mapOf("lat" to request.destinationLat, "lng" to request.destinationLng),
            "selectedRouteFound" to (selected != null))))
        assertNotNull(selected, "실제 Tmap 응답에 노원 4호선→사당→신림 2호선 경로가 없습니다.")
        val original = requireNotNull(selected)
        val rawMatches = publicRouteResponse?.path("metaData")?.path("plan")?.path("itineraries")
            ?.filter { itinerary ->
                val rawLegs = itinerary.path("legs")
                rawLegs.isArray && rawLegs.size() == original.legs.size &&
                    original.legs.indices.all { index ->
                        val leg = original.legs[index]
                        rawLegs[index].path("mode").asText() == leg.mode.name &&
                            rawLegs[index].path("sectionTime").asLong(-1) == leg.durationSeconds &&
                            (!leg.isRide || rawLegs[index].path("routeId").asText() == leg.line?.providerRouteId)
                    }
            }.orEmpty()
        val rawLegs = rawMatches.singleOrNull()?.path("legs")
        println(mapper.writeValueAsString(mapOf("stage" to "walking_boundaries",
            "classification" to "PUBLIC_ROUTE_BOUNDARY_AUDIT_NOT_PLATFORM_COVERAGE_PROOF",
            "observedAt" to Instant.now().toString(), "requestedDepartureAt" to departure.toString(),
            "rawItineraryUnambiguouslyMatched" to (rawLegs != null), "stationInteriorCoverage" to "UNKNOWN",
            "legs" to original.legs.mapIndexed { index, leg -> mapOf(
                "mode" to leg.mode.name, "scope" to original.walkingScopeAt(index)?.name,
                "durationSeconds" to leg.durationSeconds, "walkingGeometry" to leg.walkingGeometry,
                "fromName" to leg.from?.name, "toName" to leg.to?.name,
                "providerStart" to rawLegs?.get(index)?.path("start"),
                "providerEnd" to rawLegs?.get(index)?.path("end"),
                // 상세 문구가 있어도 출입구·승강장 포함 여부를 자동으로 확정하지 않는다.
                "walkingDescriptions" to rawLegs?.get(index)?.path("steps")
                    ?.takeIf { leg.mode == TransitLegMode.WALK && it.isArray }
                    ?.mapNotNull { it.path("description").takeIf(JsonNode::isTextual)?.textValue()?.take(200) }
            ) })))
        val enricher = TransitJourneyTimetableEnricher(listOf(timetable))
        val enriched = enricher.enrich(original, now, 180)
        val allConfirmed = enriched.rideLegs.all { it.timetable != null }
        println(mapper.writeValueAsString(mapOf("stage" to "timetable", "observedAt" to Instant.now().toString(),
            "requestedDepartureAt" to departure.toString(), "allRidesConfirmed" to allConfirmed,
            "routeEstimateMinutes" to original.totalMinutes, "timetableMinutes" to enriched.totalMinutes,
            "departureAt" to enriched.departureAt.toString(), "arrivalAt" to enriched.arrivalAt.toString(),
            // 총시간 차이를 access/transfer/egress와 열차 운행으로 나눌 수 있도록 전체 구간을 기록한다.
            "legs" to enriched.legs.map { leg -> mapOf("mode" to leg.mode.name,
                "departureAt" to leg.scheduledDepartureAt?.toString(), "arrivalAt" to leg.scheduledArrivalAt?.toString(),
                "durationSeconds" to leg.movementDurationSeconds) },
            "rides" to enriched.rideLegs.map { leg -> mapOf("from" to leg.from?.name, "to" to leg.to?.name,
                "nextStop" to leg.nextStopName, "line" to leg.line?.seoulSubwayLine?.name,
                // 지도와 비교할 때 열차 종별·방향을 미확인 값으로 추정하지 않도록 증거만 기록한다.
                "serviceClass" to leg.timetable?.serviceClass?.name,
                "directionCode" to leg.timetable?.directionCode, "trainNumber" to leg.timetable?.trainNumber,
                "terminalName" to leg.timetable?.terminalName,
                "timingBasis" to leg.timingBasis.name, "boardingAt" to leg.timetable?.boardingAt?.toString(),
                "arrivalAt" to leg.timetable?.arrivalAt?.toString(), "durationSeconds" to leg.durationSeconds) })))
        assertTrue(allConfirmed, "전체 시간표가 연결되지 않았습니다. ETA 보정 성공으로 판정하지 않습니다.")
        if (timetableOnly && coordinateSet == "public-exit-path-points") {
            val first = requireNotNull(enriched.rideLegs[0].timetable)
            val secondLeg = enriched.rideLegs[1]
            val second = requireNotNull(secondLeg.timetable)
            val rideIndices = original.legs.indices.filter { original.legs[it].isRide }
            val transferSeconds = original.legs.subList(rideIndices[0] + 1, rideIndices[1])
                .sumOf { it.movementDurationSeconds }
            // 비교 전용 관찰: 환승 준비 전의 후보도 조회하되 운영 여정/선택/여유는 바꾸지 않는다.
            // 같은 provider 인스턴스의 시간표 캐시를 재사용한다. 실제 앱 ETA 채택을 뜻하지 않는다.
            val candidates = timetable.findRides(com.noLate.eta.application.port.TransitTimetableQuery(
                second.line, requireNotNull(secondLeg.from?.name), requireNotNull(secondLeg.to?.name),
                secondLeg.nextStopName, second.serviceClass, first.arrivalAt, second.directionCode,
            )).filter { it.boardingAt <= second.boardingAt }.take(8)
            println(mapper.writeValueAsString(mapOf("stage" to "transfer_readiness_diagnostic",
                "classification" to "ASSUMPTION_COMPARISON_NOT_APP_ACCURACY",
                "firstArrivalAt" to first.arrivalAt.toString(),
                "selectedSecondTrain" to second.trainNumber, "productionValuesChanged" to false,
                // 지도 반올림값/최단환승 참고값은 실제 승강장 보행의 확정값이 아니다.
                "assumptions" to listOf("TMAP_ORIGINAL" to transferSeconds, "NAVER_DISPLAY_ONLY" to 120L,
                    "SEOUL_SHORTEST_REFERENCE_20250317" to 136L).map { (basis, walk) ->
                    val ready = first.arrivalAt.plusSeconds(walk + 60)
                    mapOf("basis" to basis, "walkSeconds" to walk, "marginSeconds" to 60,
                        "readyAt" to ready.toString(), "candidates" to candidates.map { candidate ->
                            mapOf("trainNumber" to candidate.trainNumber,
                                "boardingAt" to candidate.boardingAt.toString(),
                                "arrivalAt" to candidate.arrivalAt.toString(),
                                "eligibleUnderAssumption" to (candidate.boardingAt >= ready))
                        })
                })))
        }
        comparisonWalkSeconds?.let { seconds ->
            val input = TransitWalkingComparisonFixture.replaceWalkingSeconds(original, seconds)
            // 공급자의 원본 여정/운영 설정은 보존하고, 새 도보 입력으로 잡을 수 있는 열차를 다시 조회한다.
            val diagnostic = enricher.enrich(input, now, 180)
            val confirmed = diagnostic.rideLegs.all { it.timetable != null }
            println(mapper.writeValueAsString(mapOf("stage" to "walking_comparison_diagnostic",
                "classification" to "STANDARDIZED_INPUT_DIAGNOSTIC_NOT_APP_ACCURACY",
                "observedAt" to Instant.now().toString(), "requestedDepartureAt" to departure.toString(),
                "walkingSeconds" to seconds, "boardingBufferSeconds" to 60, "transferMarginSeconds" to 60,
                "allRidesConfirmed" to confirmed, "arrivalAt" to diagnostic.arrivalAt.toString(),
                "totalMinutes" to diagnostic.totalMinutes,
                "rides" to diagnostic.rideLegs.map { leg -> mapOf("line" to leg.line?.seoulSubwayLine?.name,
                    "boardingAt" to leg.timetable?.boardingAt?.toString(), "arrivalAt" to leg.timetable?.arrivalAt?.toString(),
                    "trainNumber" to leg.timetable?.trainNumber, "serviceClass" to leg.timetable?.serviceClass?.name,
                    "directionCode" to leg.timetable?.directionCode) },
                "productionValuesChanged" to false, "realWorldAccuracyVerified" to false, "simulatorE2eVerified" to false)))
            assertTrue(confirmed, "동일 도보 입력의 진단 여정이 전체 시간표로 확인되지 않았습니다.")
        }
        if (timetableOnly) {
            // 한도 초과 중인 실시간 API를 반복 호출하지 않는다. 이 모드는 live ETA 검증이 아니다.
            println(mapper.writeValueAsString(mapOf("stage" to "scope",
                "classification" to "REAL_PROVIDER_TIMETABLE_COMPARISON", "realtimeRequested" to false,
                "simulatorE2eVerified" to false, "realWorldAccuracyVerified" to false)))
            return
        }
        val arrivalService = TransitArrivalService(SeoulTransitArrivalClient(
            setting("SEOUL_OPENAPI_KEY"), setting("SEOUL_SUBWAY_API_KEY"), "",
            setting("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
            "https://ws.bus.go.kr/api/rest", allowInsecureHttp = setting("SEOUL_ALLOW_INSECURE_HTTP") == "true",
        ), TagoTransitArrivalClient("", "", "https://apis.data.go.kr/1613000", ""))
        val matcher = TransitJourneyMatcher()
        val calculator = TransitEtaCalculationService(SelectedTransitRouteDecoder(mapper), listOf(tmap),
            SafeDepartureResolver(matcher, maxSearches = 1), FirstBoardingRealtimeOverlay(arrivalService),
            matcher, TransitTransferFeasibilityEvaluator(), enricher)
        val selectedJson = mapper.writeValueAsString(mapOf("provider" to "tmap", "steps" to original.rideLegs.map { leg ->
            mapOf("mode" to "SUBWAY", "routeId" to leg.line?.providerRouteId, "lineName" to leg.line?.name,
                "serviceClass" to leg.line?.serviceClass?.name, "startName" to leg.from?.name, "endName" to leg.to?.name)
        }))
        val result = calculator.calculate(TrafficRequest(request.originLat, request.originLng,
            request.destinationLat, request.destinationLng, ScheduleTravelMode.TRANSIT, original.totalMinutes,
            selectedRouteJson = selectedJson, selectedRouteTravelMinutes = original.totalMinutes,
            evaluatedAt = now, plannedDepartureAt = departure, targetArrivalAt = departure.plusSeconds(90 * 60), maxTravelMinutes = 180))
        println(mapper.writeValueAsString(mapOf("stage" to "eta", "accepted" to result.accepted,
            "source" to result.source.name, "stale" to result.stale, "failureReason" to result.failureReason,
            "timingBasis" to result.transitTimingBasis?.name, "travelMinutes" to result.travelMinutes,
            "recommendedDepartureAt" to result.recommendedDepartureAt?.toString(),
            "predictedArrivalAt" to result.predictedArrivalAt?.toString(),
            "firstVehicleObserved" to (result.firstBoardingObservation != null))))
        // 실시간 근거가 부족하면 성공으로 부풀리지 않는다. 시간표 연결과 live 채택을 따로 기록한다.
        assertNotNull(result.transitTimingBasis, "ETA 계산의 시간 근거가 없습니다.")
        if (requireRealtime) {
            // fallback도 시간 근거는 가진다. 실제 실시간 보정 검증에서는 채택과 차량 관찰까지 요구한다.
            assertTrue(result.accepted, "실제 공급자 ETA가 채택되지 않았습니다.")
            assertNotNull(result.firstBoardingObservation, "실시간 첫 차량이 관찰되지 않았습니다.")
            assertEquals("FIRST_BOARDING_REALTIME_FUTURE_TIMETABLE", result.transitTimingBasis?.name)
        }
    }

    /** Actual saved synthetic inputs, real providers, no Worker/DB writes; diagnostic only. */
    private fun probeScopedComparison(settings: Properties, evidencePath: String) {
        val evidence = mapper.readTree(Files.readString(Path.of(evidencePath)))
        val schedule = evidence.path("schedules").single()
        val scopes = mapOf("365" to "02", "366" to "03", "367" to "04")
        val scheduleId = schedule.path("id").asText()
        val suffix = requireNotNull(scopes[scheduleId])
        require(evidencePath.startsWith("/private/tmp/nolate-real-naver-requery-$suffix-"))
        require(schedule.path("member_id").asText() == "296" &&
            schedule.path("title").asText() == "ETA E2E NAVER 1007 $suffix")
        val now = Instant.now()
        require(Duration.between(Instant.parse(evidence.path("observedAt").asText()), now).seconds in 0..1800)
        fun instant(value: String) = LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC)
        val target = instant(schedule.path("start_at").asText())
        require(target.isAfter(now))
        val route = evidence.path("routes").single()
        val personal = evidence.path("personalPlans").single()
        require(route.path("schedule_id").asText() == scheduleId && personal.path("schedule_id").asText() == scheduleId &&
            personal.path("member_id").asText() == "296")
        fun setting(name: String, default: String = "") = System.getenv(name) ?: settings.getProperty(name, default)
        val date = target.atZone(zone).toLocalDate()
        val day = readCalendar(settings, date)
        val days = mock<CalendarDayCacheRepository>()
        val holidays = mock<PublicHolidayRepository>()
        whenever(days.findById(date)).thenReturn(Optional.ofNullable(day.first))
        whenever(holidays.findAllByHolidayDateBetweenOrderByHolidayDateAscIdAsc(date, date)).thenReturn(day.second)
        val serviceDay = SeoulTimetableServiceDayResolver(days, holidays)
        assertNotNull(serviceDay.weekTag(date), "Actual service day must be verified")
        val timetable = SeoulTransitTimetableProvider(
            setting("SEOUL_TIMETABLE_API_KEY", setting("SEOUL_OPENAPI_KEY")),
            setting("SEOUL_TIMETABLE_BASE_URL", "http://openapi.seoul.go.kr:8088"), serviceDay,
            allowInsecureHttp = setting("SEOUL_TIMETABLE_ALLOW_INSECURE_HTTP") == "true")
        val enricher = TransitJourneyTimetableEnricher(listOf(timetable))
        val arrivalService = TransitArrivalService(SeoulTransitArrivalClient(
            setting("SEOUL_OPENAPI_KEY"), setting("SEOUL_SUBWAY_API_KEY"), "",
            setting("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
            "https://ws.bus.go.kr/api/rest", allowInsecureHttp = setting("SEOUL_ALLOW_INSECURE_HTTP") == "true"),
            TagoTransitArrivalClient("", "", "https://apis.data.go.kr/1613000", ""))
        val overlay = FirstBoardingRealtimeOverlay(arrivalService)
        val tmap = TmapTransitJourneyProvider(listOf(TmapTransitRouteClient(setting("TMAP_APP_KEY"),
            setting("TMAP_BASE_URL", "https://apis.openapi.sk.com"))), TmapTransitJourneyMapper())
        val decoder = SelectedTransitRouteDecoder(mapper)
        val selectedJson = personal.path("route_json").asText()
        val selected = requireNotNull(decoder.decode(selectedJson))
        require(selected.provider == "tmap")
        val matcher = TransitJourneyMatcher()
        var searches = 0
        val audited = object : com.noLate.eta.application.port.TransitJourneyProvider {
            override val providerId = tmap.providerId
            override val supportsAlternativeRouteRecovery = tmap.supportsAlternativeRouteRecovery
            override fun search(request: TransitJourneySearchRequest): List<TransitJourney> {
                require(++searches <= 3) { "Bounded actual-provider diagnostic" }
                return tmap.search(request).also { journeys ->
                    matcher.findSelectedCandidates(selected, journeys).take(2).forEach { journey ->
                        val enriched = enricher.enrich(journey, now, 180) { timetable ->
                            overlay.observeTimetableCandidate(journey, timetable, now)
                        }
                        val live = overlay.resolve(enriched, now, 180)
                        println(mapper.writeValueAsString(mapOf("classification" to "READ_ONLY_REAL_SEARCH_TRACE_NOT_E2E",
                            "probe" to searches, "searchAt" to request.departureAt.toString(),
                            "timetableArrivalAt" to enriched.arrivalAt.toString(), "liveMatched" to (live.overlay != null),
                            "failureReason" to live.failureReason,
                            "rides" to enriched.rideLegs.map { leg -> mapOf("line" to leg.line?.name,
                                "trainNumber" to leg.timetable?.trainNumber,
                                "verifiedRealtimeTrainNumber" to leg.timetable?.realtimeTrainNumber,
                                "terminal" to leg.timetable?.terminalName,
                                "boardingAt" to leg.timetable?.boardingAt?.toString(),
                                "arrivalAt" to leg.timetable?.arrivalAt?.toString()) })))
                    }
                }
            }
        }
        val calculator = TransitEtaCalculationService(decoder, listOf(audited), SafeDepartureResolver(matcher),
            overlay, matcher, TransitTransferFeasibilityEvaluator(), enricher)
        val result = calculator.calculate(TrafficRequest(personal.path("origin_lat").asDouble(), personal.path("origin_lng").asDouble(),
            route.path("destination_lat").asDouble(), route.path("destination_lng").asDouble(), ScheduleTravelMode.TRANSIT,
            personal.path("travel_minutes").asInt(), selectedRouteJson = selectedJson,
            selectedRouteTravelMinutes = personal.path("travel_minutes").asInt(), evaluatedAt = now,
            plannedDepartureAt = instant(personal.path("depart_at").asText()), targetArrivalAt = target, maxTravelMinutes = 180))
        println(mapper.writeValueAsString(mapOf("classification" to "READ_ONLY_REAL_SEARCH_TRACE_NOT_E2E",
            "observedAt" to now.toString(), "accepted" to result.accepted, "source" to result.source.name,
            "stale" to result.stale, "failureReason" to result.failureReason, "searches" to searches,
            "recommendedDepartureAt" to result.recommendedDepartureAt?.toString(),
            "predictedArrivalAt" to result.predictedArrivalAt?.toString(), "productionDatabaseModified" to false)))
        assertTrue(searches in 1..3)
    }

    private fun readCalendar(settings: Properties, date: LocalDate): Pair<CalendarDayCache?, List<PublicHoliday>> {
        fun setting(name: String, default: String = "") = System.getenv(name) ?: settings.getProperty(name, default)
        val endpoint = setting("MY_SQL_END_POINT")
        val port = setting("MY_SQL_PORT", "3306")
        val database = setting("MY_SQL_DB_NAME")
        DriverManager.getConnection("jdbc:mysql://$endpoint:$port/$database?serverTimezone=Asia/Seoul&connectTimeout=3000&socketTimeout=5000",
            setting("MY_SQL_DB_USERNAME"), setting("MY_SQL_DB_PASSWORD")).use { connection ->
            connection.isReadOnly = true
            connection.autoCommit = false
            try {
                val day = connection.prepareStatement("SELECT holidays_synced_at FROM calendar_day_cache WHERE solar_date = ?").use { statement ->
                    statement.setObject(1, date)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) CalendarDayCache(date = date, holidaysSyncedAt = rows.getObject(1, LocalDateTime::class.java)) else null
                    }
                }
                val holidays = connection.prepareStatement("SELECT name, holiday_type, source FROM public_holidays WHERE holiday_date = ? ORDER BY id").use { statement ->
                    statement.setObject(1, date)
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) add(PublicHoliday(holidayDate = date, name = rows.getString(1), type = rows.getString(2), source = rows.getString(3)))
                    } }
                }
                return day to holidays
            } finally {
                connection.rollback()
            }
        }
    }
}
