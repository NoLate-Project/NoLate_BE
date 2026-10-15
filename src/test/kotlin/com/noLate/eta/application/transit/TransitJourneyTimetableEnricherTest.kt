package com.noLate.eta.application.transit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.tmap.TmapTransitJourneyMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import java.time.*

class TransitJourneyTimetableEnricherTest {
    private val now = Instant.parse("2026-10-04T06:00:00Z")

    @ParameterizedTest
    @CsvSource("243,60,3483,2272,4159", "120,60,3360,2272,4159", "136,60,3376,2272,4159",
        "243,0,3423,2272,4159", "120,0,3300,2270,3859", "136,0,3316,2270,3859",
        "136,14,3330,2270,3859", "136,15,3331,2272,4159")
    fun `환승도보 단축과 안전여유 변경은 다른 조건이며 열차 경계에서 분리해 검증한다`(
        walkingSeconds: Long, marginSeconds: Long, readySeconds: Long, train: String, arrivalSeconds: Long,
    ) {
        // 10/9 공개 좌표 비교에서 관찰한 시각 구조를 재생한다. 실제 공급자/정확도 검증은 아니다.
        // 243초는 경로 원본, 120초는 지도 표시, 136초는 공사 최단환승 참고값이다.
        // 참고값은 빠른 환승 위치/방향에 조건부이므로 운영 도보를 자동 치환하지 않는다.
        val original = journey()
        val seconds = listOf(41L, 2880L, walkingSeconds, 450L, 79L)
        val input = original.copy(legs = original.legs.mapIndexed { index, leg ->
            leg.copy(durationSeconds = seconds[index], durationMinutes = ((seconds[index] + 59) / 60).toInt())
        })
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return if (query.line == SeoulSubwayLine.LINE_4) listOf(ride(query.line).copy(
                    boardingAt = now.plusSeconds(300), arrivalAt = now.plusSeconds(3180),
                )) else listOf(
                    ride(query.line).copy(trainNumber = "2270", boardingAt = now.plusSeconds(3330),
                        arrivalAt = now.plusSeconds(3780)),
                    ride(query.line).copy(trainNumber = "2272", boardingAt = now.plusSeconds(3630),
                        arrivalAt = now.plusSeconds(4080)),
                )
            }
        }
        val enriched = TransitJourneyTimetableEnricher(listOf(provider), transferMarginSeconds = marginSeconds)
            .enrich(input, now, 180)

        assertEquals(now.plusSeconds(readySeconds), queries[1].notBefore)
        assertEquals(train, enriched.rideLegs[1].timetable?.trainNumber)
        assertEquals(now.plusSeconds(arrivalSeconds), enriched.arrivalAt)
        assertEquals(walkingSeconds, enriched.legs[2].movementDurationSeconds)
        assertEquals(243L, original.legs[2].movementDurationSeconds) // 원본을 덮어쓰지 않는다.
        // 0초 여유는 원인 분리를 위한 테스트 입력이다. 운영 기본값을 줄이라는 근거가 아니다.
    }

    @Test
    fun `환승 여유 설정은 합산하지 않고 재검증에서도 도보나 총 ETA에 두 번 더하지 않는다`() {
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                // 환승도보 243초 뒤 정확히 여유 60초에 출발한다. 60+60 합산이면 탈 수 없다.
                return listOf(ride(query.line).let {
                    if (query.line == SeoulSubwayLine.LINE_2) it.copy(boardingAt = now.plusSeconds(1700)) else it
                })
            }
        }
        val original = journey()
        val enriched = TransitJourneyTimetableEnricher(listOf(provider), transferMarginSeconds = 60,
            transferBufferSeconds = 60).enrich(original, now, 1440)
        assertEquals(now.plusSeconds(1700), queries[1].notBefore)
        assertEquals(listOf(111L, 243L, 171L), enriched.legs.filter { !it.isRide }.map { it.durationSeconds })
        assertEquals(now.plusSeconds(2452), enriched.arrivalAt)
        val evaluated = TransitTransferFeasibilityEvaluator(60, 60).evaluate(enriched, null, now)
        assertEquals(TransitTransferStatus.FEASIBLE, evaluated.status)
        assertEquals(enriched.arrivalAt, evaluated.predictedArrivalAt)
        assertEquals(41, evaluated.travelMinutes)
    }

    @Test
    fun `실제 승차 대기와 이후 환승 도보 및 여유를 순서대로 계산하고 원본 초를 보존한다`() {
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return listOf(ride(query.line))
            }
        }

        val enriched = TransitJourneyTimetableEnricher(listOf(provider)).enrich(journey(), now, 1440)

        assertEquals(now.plusSeconds(171), queries[0].notBefore) // 접근111초 + 여유60초
        assertEquals(now.plusSeconds(1700), queries[1].notBefore) // 하차1397초 + 환승도보243초 + 여유60초
        assertTrue(queries.all { it.serviceDate == LocalDate.of(2026, 10, 4) })
        assertEquals(now.plusSeconds(2452), enriched.arrivalAt)
        assertEquals(listOf(111L, 1286L, 243L, 641L, 171L), enriched.legs.map { it.durationSeconds })
        assertEquals(41, enriched.totalMinutes)
        assertEquals(now.plusSeconds(197), enriched.rideLegs[0].timetable?.boardingAt)
        assertEquals(2, enriched.rideLegs[0].waitingMinutes) // 실제86초; overlay는 이 표시 분을 계산에 쓰지 않는다.
        assertTrue(enriched.rideLegs.all { it.timingBasis == TransitLegTimingBasis.TIMETABLE })
        assertEquals(65, journey().totalMinutes)
    }

    @Test
    fun `마지막 구간 미확인 또는 provider 실패는 앞 구간 성공까지 버리고 원래 추정 여정을 유지한다`() {
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery) = if (query.line == SeoulSubwayLine.LINE_4) listOf(ride(query.line)) else emptyList()
        }
        val original = journey()
        assertSame(original, TransitJourneyTimetableEnricher(listOf(provider)).enrich(original, now, 1440))
        val failing = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> = throw IllegalStateException("fixture failure")
        }
        assertSame(original, TransitJourneyTimetableEnricher(listOf(failing)).enrich(original, now, 1440))
    }

    @Test
    fun `버스 혼합과 미지원 호선 및 최대 소요시간 초과를 시간표 여정으로 승격하지 않는다`() {
        val provider = object : TransitTimetableProvider { override fun findRides(query: TransitTimetableQuery) = listOf(ride(query.line)) }
        val enricher = TransitJourneyTimetableEnricher(listOf(provider))
        val original = journey()
        val busMixed = original.copy(legs = original.legs.map { if (it.sequence == 3) it.copy(mode = TransitLegMode.BUS) else it })
        val unknownLine = original.copy(legs = original.legs.map { if (it.isRide) it.copy(line = it.line?.copy(seoulSubwayLine = null)) else it })
        assertSame(busMixed, enricher.enrich(busMixed, now, 1440))
        assertSame(unknownLine, enricher.enrich(unknownLine, now, 1440))
        assertSame(original, enricher.enrich(original, now, 1))
    }

    @Test
    fun `지연 뒤 환승 도보와 60초 여유를 적용하고 첫 열차의 원본 증거는 보존한다`() {
        val original = timetableJourney()
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return listOf(ride(query.line), nextRide())
            }
        }
        val recovered = TransitJourneyTimetableEnricher(listOf(provider))
            .reconnectTransfers(original, delayedOverlay(), 1440)

        assertEquals(1, queries.size) // 첫 차량을 다시 조회하거나 다른 경로를 탐색하지 않는다.
        assertEquals(SeoulSubwayLine.LINE_2, queries.single().line)
        assertEquals("DOWN", queries.single().directionCode)
        assertEquals(now.plusSeconds(1910), queries.single().notBefore)
        assertEquals(original.rideLegs[1].timetable?.serviceDate, queries.single().serviceDate)
        assertSame(original.rideLegs[0], recovered.rideLegs[0])
        assertEquals("2014", recovered.rideLegs[1].timetable?.trainNumber)
        assertEquals(731L, recovered.rideLegs[1].durationSeconds)
        assertEquals(5, recovered.rideLegs[1].waitingMinutes)
        assertEquals(now.plusSeconds(2752), recovered.arrivalAt)
        assertEquals(46, recovered.totalMinutes)
        assertEquals("2012", original.rideLegs[1].timetable?.trainNumber)
        assertEquals(now.plusSeconds(2452), original.arrivalAt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["provider", "from", "to", "direction", "line", "class", "date", "margin"])
    fun `다음 열차의 경로 운행일 종별 공급자 또는 환승 여유가 다르면 회복하지 않는다`(mismatch: String) {
        val original = timetableJourney()
        val next = nextRide().let {
            when (mismatch) {
                "provider" -> it.copy(provider = "other-provider")
                "from" -> it.copy(fromStationCode = "OTHER_FROM")
                "to" -> it.copy(toStationCode = "OTHER_TO")
                "direction" -> it.copy(directionCode = "UP")
                "line" -> it.copy(line = SeoulSubwayLine.LINE_3)
                "class" -> it.copy(serviceClass = TransitServiceClass.EXPRESS)
                "date" -> it.copy(serviceDate = it.serviceDate.plusDays(1))
                else -> it.copy(boardingAt = now.plusSeconds(1909)) // 필요한 여유보다 1초 부족
            }
        }
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery) = listOf(next)
        }
        assertSame(original, TransitJourneyTimetableEnricher(listOf(provider))
            .reconnectTransfers(original, delayedOverlay(), 1440))
    }

    @Test
    fun `이후 환승 하나라도 실패하면 앞 환승의 부분 회복도 버린다`() {
        val base = timetableJourney()
        val third = ride(SeoulSubwayLine.LINE_3).copy(
            trainNumber = "3012", boardingAt = now.plusSeconds(2512), arrivalAt = now.plusSeconds(2700),
        )
        val original = base.copy(
            arrivalAt = third.arrivalAt, totalMinutes = 45,
            legs = base.legs + TransitJourneyLeg(
                sequence = 5, mode = TransitLegMode.SUBWAY, durationMinutes = 5, durationSeconds = 248,
                scheduledDepartureAt = base.arrivalAt, scheduledArrivalAt = third.arrivalAt,
                from = TransitStop(name = "환승역"), to = TransitStop(name = "목적지역"),
                line = TransitLine(serviceClass = TransitServiceClass.LOCAL, seoulSubwayLine = SeoulSubwayLine.LINE_3),
                directionCode = "DOWN", timingBasis = TransitLegTimingBasis.TIMETABLE, timetable = third,
            ),
        )
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return if (query.line == SeoulSubwayLine.LINE_2) listOf(nextRide()) else emptyList()
            }
        }
        assertSame(original, TransitJourneyTimetableEnricher(listOf(provider))
            .reconnectTransfers(original, delayedOverlay(), 1440))
        assertEquals(2, queries.size)
        assertEquals(now.plusSeconds(2812), queries[1].notBefore)
    }

    @Test
    fun `최대 이동시간 초과와 조회 예외 및 미확인 여정은 원본을 유지한다`() {
        val original = timetableJourney()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery) = listOf(nextRide())
        }
        val enricher = TransitJourneyTimetableEnricher(listOf(provider))
        assertSame(original, enricher.reconnectTransfers(original, delayedOverlay(), 45))
        val estimated = journey()
        assertSame(estimated, enricher.reconnectTransfers(estimated, delayedOverlay(), 1440))
        val failure = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> = error("fixture failure")
        }
        assertSame(original, TransitJourneyTimetableEnricher(listOf(failure))
            .reconnectTransfers(original, delayedOverlay(), 1440))
    }

    private fun timetableJourney(): TransitJourney {
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery) = listOf(ride(query.line))
        }
        return TransitJourneyTimetableEnricher(listOf(provider)).enrich(journey(), now, 1440)
    }

    @Test
    fun `자정을 넘는 환승에도 여정 출발 운행일을 전달하고 다른 운행일 결과를 거절한다`() {
        val departure = Instant.parse("2026-10-04T14:40:00Z") // 10/4 23:40
        val offset = Duration.between(now, departure)
        val original = journey().copy(departureAt = departure, arrivalAt = journey().arrivalAt.plus(offset))
        val queries = mutableListOf<TransitTimetableQuery>()
        fun provider(wrongDate: Boolean) = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return listOf(ride(query.line).let {
                    it.copy(boardingAt = it.boardingAt.plus(offset), arrivalAt = it.arrivalAt.plus(offset),
                        serviceDate = if (wrongDate && query.line == SeoulSubwayLine.LINE_2)
                            it.serviceDate.plusDays(1) else it.serviceDate)
                })
            }
        }
        val enriched = TransitJourneyTimetableEnricher(listOf(provider(false))).enrich(original, departure, 180)
        assertEquals(2, queries.size)
        assertTrue(queries.all { it.serviceDate == LocalDate.of(2026, 10, 4) })
        assertEquals(LocalDate.of(2026, 10, 5), queries[1].notBefore.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
        assertTrue(enriched.rideLegs.all { it.timetable?.serviceDate == LocalDate.of(2026, 10, 4) })
        assertEquals(Instant.parse("2026-10-04T15:20:52Z"), enriched.arrivalAt)
        assertSame(original, TransitJourneyTimetableEnricher(listOf(provider(true))).enrich(original, departure, 180))
    }

    @Test
    fun `자정 이후 지연 환승 재연결도 첫 시간표의 원래 운행일을 보존한다`() {
        val offset = Duration.ofHours(9) // 10/4 15:00 → 10/5 00:00
        val base = timetableJourney()
        val original = base.copy(departureAt = base.departureAt.plus(offset), arrivalAt = base.arrivalAt.plus(offset),
            legs = base.legs.map { leg -> leg.copy(
                scheduledDepartureAt = leg.scheduledDepartureAt?.plus(offset),
                scheduledArrivalAt = leg.scheduledArrivalAt?.plus(offset),
                timetable = leg.timetable?.let { it.copy(boardingAt = it.boardingAt.plus(offset),
                    arrivalAt = it.arrivalAt.plus(offset)) },
            ) })
        val queries = mutableListOf<TransitTimetableQuery>()
        val provider = object : TransitTimetableProvider {
            override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                queries += query
                return listOf(nextRide().copy(boardingAt = now.plusSeconds(2100).plus(offset),
                    arrivalAt = now.plusSeconds(2581).plus(offset)))
            }
        }
        val overlay = delayedOverlay().let { it.copy(boardingAt = it.boardingAt.plus(offset),
            predictedArrivalAt = it.predictedArrivalAt.plus(offset), observedAt = it.observedAt.plus(offset)) }
        val recovered = TransitJourneyTimetableEnricher(listOf(provider)).reconnectTransfers(original, overlay, 180)
        assertEquals(LocalDate.of(2026, 10, 5), queries.single().notBefore.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 4), queries.single().serviceDate)
        assertEquals("2014", recovered.rideLegs[1].timetable?.trainNumber)
        assertEquals(LocalDate.of(2026, 10, 4), recovered.rideLegs[1].timetable?.serviceDate)
        assertSame(original.rideLegs[0], recovered.rideLegs[0])
    }

    private fun nextRide() = ride(SeoulSubwayLine.LINE_2).copy(
        trainNumber = "2014", boardingAt = now.plusSeconds(2100), arrivalAt = now.plusSeconds(2581),
    )

    private fun delayedOverlay() = TransitRealtimeOverlay(
        travelMinutes = 45, predictedArrivalAt = now.plusSeconds(2662),
        observedAt = now, boardingAt = now.plusSeconds(407), firstBoardingWaitMinutes = 5,
    )

    private fun ride(line: SeoulSubwayLine) = TransitLegTimetable(
        "fixture-timetable", line, LocalDate.of(2026, 10, 4), if (line == SeoulSubwayLine.LINE_4) "4012" else "2012",
        "DOWN", TransitServiceClass.LOCAL, "FROM", "TO", if (line == SeoulSubwayLine.LINE_4) "오이도" else "성수",
        now.plusSeconds(if (line == SeoulSubwayLine.LINE_4) 197 else 1800),
        now.plusSeconds(if (line == SeoulSubwayLine.LINE_4) 1397 else 2281), now,
    )

    private fun journey() = TmapTransitJourneyMapper().map(jacksonObjectMapper().readTree("""
      {"metaData":{"plan":{"itineraries":[{"totalTime":3888,"legs":[
        {"mode":"WALK","sectionTime":111},
        {"mode":"SUBWAY","sectionTime":2882,"service":1,"type":4,"route":"수도권 4호선",
          "routeId":"11004","start":{"name":"서울역"},"end":{"name":"사당"}},
        {"mode":"WALK","sectionTime":243},
        {"mode":"SUBWAY","sectionTime":481,"service":1,"type":2,"route":"수도권 2호선",
          "routeId":"11002","start":{"name":"사당"},"end":{"name":"신림"}},
        {"mode":"WALK","sectionTime":171}
      ]}]}}}
    """), TransitJourneySearchRequest(37.55, 126.97, 37.48, 126.93, now, 1440), now).single()
}
