package com.noLate.eta.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.port.*
import com.noLate.eta.application.transit.*
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.infrastructure.seoul.SeoulTransferReferenceCatalog
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.*

/** 합성 공급자 통합 검증이다. 실제 공급자/설치 앱 E2E와 구분한다. */
class WalkingTimeTimetableIntegrationTest {
    private val now = Instant.parse("2026-10-13T00:00:00Z")

    @Test
    fun `검증된 초 단위 도보로 전 구간 시간표를 다시 연결하되 환승 여유는 유지한다`() {
        val original = calculate(null)
        val refined = calculate(157)
        assertTrue(original.accepted)
        assertTrue(refined.accepted)
        assertEquals("2056", original.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals("2054", refined.effectiveTransitRoute?.segments?.get(3)?.rideTiming?.timetable?.trainNumber)
        assertEquals(now.plusSeconds(1410), original.predictedArrivalAt)
        assertEquals(now.plusSeconds(1340), refined.predictedArrivalAt)
        val route = refined.effectiveTransitRoute!!
        assertEquals(157L, route.segments[2].durationSeconds)
        assertEquals(62L, route.segments[2].walkingTiming?.transferReference?.durationSeconds)
        assertEquals(60L, route.segments[3].transferTiming?.requiredMarginSeconds)
        assertEquals(13L, route.segments[3].transferTiming?.remainingSlackSeconds)
        assertEquals(now.plusSeconds(817), route.segments[3].transferTiming?.confidenceReadyAt)
        assertEquals(listOf(1, 1), listOf(route.segments[0], route.segments[4]).map { it.durationMinutes })
    }

    private fun calculate(seconds: Long?): com.noLate.schedule.application.TrafficResult {
        val raw = TransitJourney("odsay", now, now, now.plusSeconds(3600), 60, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 1),
            TransitJourneyLeg(1, TransitLegMode.SUBWAY, 8, from = TransitStop(name = "노원"), to = TransitStop(name = "사당"),
                line = TransitLine(providerRouteId = "1000:4", name = "수도권 4호선", serviceClass = TransitServiceClass.LOCAL,
                    seoulSubwayLine = SeoulSubwayLine.LINE_4)),
            TransitJourneyLeg(2, TransitLegMode.WALK, 3, walkingGeometry = TransitWalkingGeometry(distanceMeters = 170)),
            TransitJourneyLeg(3, TransitLegMode.SUBWAY, 8, from = TransitStop(name = "사당"), to = TransitStop(name = "신림"),
                line = TransitLine(providerRouteId = "1000:2", name = "수도권 2호선", serviceClass = TransitServiceClass.LOCAL,
                    seoulSubwayLine = SeoulSubwayLine.LINE_2)),
            TransitJourneyLeg(4, TransitLegMode.WALK, 1)), now)
        val provider = object : TransitJourneyProvider {
            override val providerId = "odsay"
            override fun search(request: TransitJourneySearchRequest) = listOf(raw)
        }
        val timetable = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                val first = query.line == SeoulSubwayLine.LINE_4
                val times = if (first) listOf(Triple("4011", 120L, 600L))
                    else listOf(Triple("2054", 830L, 1280L), Triple("2056", 900L, 1350L))
                return times.map { (train, board, arrive) -> TransitLegTimetable("seoul-timetable", query.line,
                    LocalDate.of(2026, 10, 13), train, "DOWN", TransitServiceClass.LOCAL, "FROM", "TO",
                    if (first) "사당" else "성수", now.plusSeconds(board), now.plusSeconds(arrive), now) }
            }
        }
        val walking = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int) = seconds?.let { VerifiedWalkingTime(it, now) }
        }
        val arrivals = mock<TransitArrivalService>()
        whenever(arrivals.getSubwayArrivalsForEta("노원", "4호선", null, "DOWN", 10)).thenReturn(listOf(
            TransitArrivalDto("seoul-openapi", "SUBWAY", lineName = "4호선", stationName = "노원", direction = "하행",
                destinationName = "사당", expectedAt = now.plusSeconds(120).toString(), sourceUpdatedAt = now.toString(),
                freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP, express = false,
                subwayLineId = "1004", trainNumber = "4011")))
        val matcher = TransitJourneyMatcher()
        val service = TransitEtaCalculationService(SelectedTransitRouteDecoder(jacksonObjectMapper()), listOf(provider),
            SafeDepartureResolver(matcher, maxSearches = 1), FirstBoardingRealtimeOverlay(arrivals, Clock.fixed(now, ZoneOffset.UTC)),
            matcher, TransitTransferFeasibilityEvaluator(), TransitJourneyTimetableEnricher(listOf(timetable)),
            TransitWalkingTimeEnricher(listOf(walking), listOf(SeoulTransferReferenceCatalog())))
        return service.calculate(TrafficRequest(37.65, 127.06, 37.48, 126.93, ScheduleTravelMode.TRANSIT, 60,
            evaluatedAt = now, plannedDepartureAt = now,
            selectedRouteJson = """{"provider":"odsay","steps":[
                {"mode":"SUBWAY","routeId":"1000:4","lineName":"수도권 4호선","serviceClass":"LOCAL","startName":"노원","endName":"사당"},
                {"mode":"SUBWAY","routeId":"1000:2","lineName":"수도권 2호선","serviceClass":"LOCAL","startName":"사당","endName":"신림"}]}"""))
    }
}
