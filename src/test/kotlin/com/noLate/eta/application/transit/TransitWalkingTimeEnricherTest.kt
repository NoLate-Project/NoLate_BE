package com.noLate.eta.application.transit

import com.noLate.eta.application.port.*
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.seoul.SeoulTransferReferenceCatalog
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TransitWalkingTimeEnricherTest {
    private val now = Instant.parse("2026-10-13T00:00:00Z")
    private val geometry = TransitWalkingGeometry(distanceMeters = 170)
    private fun journey() = TransitJourney("odsay", now, now, now.plusSeconds(3600), 60, listOf(
        TransitJourneyLeg(0, TransitLegMode.WALK, 1),
        TransitJourneyLeg(1, TransitLegMode.SUBWAY, 48, to = TransitStop(name = "사당"),
            line = TransitLine(seoulSubwayLine = SeoulSubwayLine.LINE_4)),
        TransitJourneyLeg(2, TransitLegMode.WALK, 3, walkingGeometry = geometry),
        TransitJourneyLeg(3, TransitLegMode.SUBWAY, 7, from = TransitStop(name = "사당"),
            line = TransitLine(seoulSubwayLine = SeoulSubwayLine.LINE_2)),
        TransitJourneyLeg(4, TransitLegMode.WALK, 1)), now)

    @Test
    fun `같은 전체 도보 초를 적용하고 공식 74미터 62초는 참조로 분리한다`() {
        val original = journey()
        val enriched = enricher(157).enrich(original)
        assertEquals(157L, enriched.legs[2].movementDurationSeconds)
        val evidence = enriched.legs[2].walkingTiming!!
        assertEquals(TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS, evidence.source)
        assertEquals(3, evidence.originalDurationMinutes)
        assertEquals(74L, evidence.transferReference?.distanceMeters)
        assertEquals(62L, evidence.transferReference?.durationSeconds)
        assertEquals("REFERENCE_ONLY_DIRECTION_AND_PATH_UNVERIFIED", evidence.transferReference?.application)
        assertEquals(listOf(60L, 60L), listOf(enriched.legs[0], enriched.legs[4]).map { it.movementDurationSeconds })
        assertEquals(original.arrivalAt, enriched.arrivalAt) // 시간표 재연결은 다음 단계에서 수행한다.
        assertEquals(180L, original.legs[2].movementDurationSeconds)
    }

    @Test
    fun `시간표 없는 버스 경로의 도보 보정은 전체 예상 도착에도 한 번만 적용한다`() {
        val endpoints = TransitWalkingQueryEndpoints(TransitWalkingCoordinate(37.497952, 127.027619),
            TransitWalkingCoordinate(37.49824307538975, 127.029322))
        val original = journey().copy(arrivalAt = now.plusSeconds(900), totalMinutes = 15,
            legs = listOf(
                TransitJourneyLeg(0, TransitLegMode.WALK, 3,
                    walkingGeometry = geometry.copy(providerQueryEndpoints = endpoints)),
                TransitJourneyLeg(1, TransitLegMode.BUS, 6, waitingMinutes = 0,
                    timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE),
                TransitJourneyLeg(2, TransitLegMode.WALK, 6),
            ),
        )
        val enriched = enricher(157).enrich(original)
        assertEquals(listOf(157L, 360L, 360L), enriched.legs.map { it.movementDurationSeconds })
        assertEquals(now.plusSeconds(877), enriched.arrivalAt)
        assertEquals(15, enriched.totalMinutes)
        val evaluated = TransitTransferFeasibilityEvaluator().evaluate(enriched, null, now)
        assertEquals(TransitTransferStatus.UNKNOWN, evaluated.status)
        assertEquals(enriched.arrivalAt, evaluated.predictedArrivalAt)
        assertEquals(enriched.arrivalAt, enricher(157).enrich(enriched).arrivalAt)
    }

    @Test
    fun `원본 도보 링크의 초를 분 단위 버스 여정에만 한 번 반영한다`() {
        val enricher = TransitWalkingTimeEnricher()
        val endpoints = TransitWalkingQueryEndpoints(TransitWalkingCoordinate(37.49963, 127.03378),
            TransitWalkingCoordinate(37.50062, 127.03646))
        val original = journey().copy(arrivalAt = now.plusSeconds(900), totalMinutes = 15,
            legs = listOf(
                TransitJourneyLeg(0, TransitLegMode.WALK, 3, durationSeconds = 157),
                TransitJourneyLeg(1, TransitLegMode.BUS, 6, waitingMinutes = 0,
                    timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE),
                TransitJourneyLeg(2, TransitLegMode.WALK, 6, durationSeconds = 342,
                    walkingGeometry = geometry.copy(providerQueryEndpoints = endpoints)),
            ),
        )

        val enriched = enricher.enrich(original)

        assertEquals(now.plusSeconds(859), enriched.arrivalAt)
        assertEquals(enriched.arrivalAt, enricher.enrich(enriched).arrivalAt)
        assertEquals(TransitWalkingTimingSource.PROVIDER_LINK_SECONDS, enriched.legs.last().walkingTiming?.source)
        assertEquals(now.plusSeconds(840), enricher.enrich(original.copy(arrivalAt = now.plusSeconds(840))).arrivalAt)
    }

    @Test
    fun `오류와 다른 분 범위는 기존 180초를 유지하며 공식 짧은 값으로 대체하지 않는다`() {
        listOf(null, 120L, 181L, 0L).forEach { seconds ->
            val walk = enricher(seconds).enrich(journey()).legs[2]
            assertEquals(180L, walk.movementDurationSeconds)
            assertEquals(TransitWalkingTimingSource.ROUTE_MINUTES, walk.walkingTiming?.source)
        }
        val failing = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime = error("test failure")
        }
        assertEquals(180L, TransitWalkingTimeEnricher(listOf(failing)).enrich(journey()).legs[2].movementDurationSeconds)
    }

    @Test
    fun `목적지까지 동일 도보가 검증되면 4분 대신 188초를 사용하고 실패하면 기존 값을 유지한다`() {
        val start = TransitWalkingCoordinate(37.477, 126.981)
        val end = TransitWalkingCoordinate(37.476563, 126.981746)
        val egress = TransitJourneyLeg(2, TransitLegMode.WALK, 4, walkingGeometry = TransitWalkingGeometry(
            distanceMeters = 203,
            providerQueryEndpoints = TransitWalkingQueryEndpoints(start, end),
        ))
        val route = journey().copy(legs = journey().legs.take(2) + egress)
        val verified = enricher(188).enrich(route).legs.last()
        assertEquals(188L, verified.movementDurationSeconds)
        assertEquals(TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS, verified.walkingTiming?.source)
        assertEquals(240L, enricher(null).enrich(route).legs.last().movementDurationSeconds)
        assertEquals(240L, enricher(180).enrich(route).legs.last().movementDurationSeconds)
        assertEquals(240L, enricher(188).enrich(route.copy(legs = route.legs +
            TransitJourneyLeg(3, TransitLegMode.WALK, 1))).legs[2].movementDurationSeconds)
    }

    @Test
    fun `같은 목적지 도보의 확인된 초는 분 합계가 증명된 지하철 추정에만 반영한다`() {
        val endpoints = TransitWalkingQueryEndpoints(
            TransitWalkingCoordinate(37.477, 126.981), TransitWalkingCoordinate(37.476563, 126.981746),
        )
        val original = TransitJourney("odsay", now, now, now.plusSeconds(600), 10, listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 1),
            TransitJourneyLeg(1, TransitLegMode.SUBWAY, 5, timingBasis = TransitLegTimingBasis.ROUTE_ESTIMATE),
            TransitJourneyLeg(2, TransitLegMode.WALK, 4,
                walkingGeometry = geometry.copy(providerQueryEndpoints = endpoints)),
        ), now)
        val enricher = enricher(188)

        val enriched = enricher.enrich(original)
        assertEquals(188L, enriched.legs.last().movementDurationSeconds)
        assertEquals(now.plusSeconds(548), enriched.arrivalAt)
        assertEquals(enriched.arrivalAt, enricher.enrich(enriched).arrivalAt)
        assertEquals(original.arrivalAt, enricher(null).enrich(original).arrivalAt)

        val unknownWait = original.copy(arrivalAt = now.plusSeconds(660), totalMinutes = 11)
        assertEquals(unknownWait.arrivalAt, enricher.enrich(unknownWait).arrivalAt)
    }

    @Test
    fun `출입구 도보 보완과 원래 목적지 도보를 다른 근거로 모두 적용한다`() {
        val endpoints = TransitWalkingQueryEndpoints(TransitWalkingCoordinate(37.656274, 127.063183),
            TransitWalkingCoordinate(37.656083, 127.062779))
        val finish = TransitJourneyLeg(1, TransitLegMode.WALK, 4, walkingGeometry = TransitWalkingGeometry(
            distanceMeters = 203, providerQueryEndpoints = endpoints))
        val route = journey().copy(entranceWalkEndpoints = endpoints,
            legs = listOf(journey().legs[1].copy(sequence = 0), finish))
        val provider = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime =
                error("The batched entrance and egress must not consume another request")
            override fun refineBoundaries(access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
                                          unchanged: List<WalkingPathRefinement>): BoundaryWalkRefinement {
                assertEquals(endpoints, access)
                assertEquals(listOf(WalkingPathRefinement(requireNotNull(finish.walkingGeometry), 4)), unchanged)
                return BoundaryWalkRefinement(VerifiedAccessWalk(47, 50, now, endpoints.start, endpoints.end), null,
                    mapOf(unchanged.single() to VerifiedWalkingTime(188, now)))
            }
        }
        val enriched = TransitWalkingTimeEnricher(listOf(provider)).enrich(route)
        assertEquals(listOf(0, 1, 2), enriched.legs.map { it.sequence })
        assertEquals(47L, enriched.legs.first().movementDurationSeconds)
        assertEquals(TransitWalkingTimingSource.ODSAY_ENTRANCE_WALK_SECONDS, enriched.legs.first().walkingTiming?.source)
        assertEquals(188L, enriched.legs.last().movementDurationSeconds)
        assertEquals(TransitWalkingTimingSource.ODSAY_FULL_PATH_SECONDS, enriched.legs.last().walkingTiming?.source)
    }

    @Test
    fun `ODsay가 접근 도보를 생략한 지하철의 경로 추정에 검증 도보를 한 번만 포함한다`() {
        val endpoints = TransitWalkingQueryEndpoints(
            TransitWalkingCoordinate(37.497958, 127.027539), TransitWalkingCoordinate(37.4982, 127.0281),
        )
        val original = TransitJourney("odsay", now, now, now.plusSeconds(300), 5, listOf(
            TransitJourneyLeg(0, TransitLegMode.SUBWAY, 3),
            TransitJourneyLeg(1, TransitLegMode.WALK, 2),
        ), now, entranceWalkEndpoints = endpoints)
        val provider = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime? = null
            override fun refineBoundaries(access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
                                          unchanged: List<WalkingPathRefinement>): BoundaryWalkRefinement {
                assertEquals(endpoints, access)
                return BoundaryWalkRefinement(VerifiedAccessWalk(103, 90, now, endpoints.start, endpoints.end), null, emptyMap())
            }
        }
        val enricher = TransitWalkingTimeEnricher(listOf(provider))
        val enriched = enricher.enrich(original)

        assertEquals(now.plusSeconds(403), enriched.arrivalAt)
        assertEquals(7, enriched.totalMinutes)
        assertEquals(listOf(103L, 180L, 120L), enriched.legs.map { it.movementDurationSeconds })
        assertEquals(enriched.arrivalAt, enricher.enrich(enriched).arrivalAt)
        assertEquals(original.arrivalAt, TransitWalkingTimeEnricher().enrich(original).arrivalAt)
    }

    @Test
    fun `잘린 양 끝 도보와 동일 환승 도보를 한 번 검증해 중복 없이 교체한다`() {
        val accessPins = TransitWalkingQueryEndpoints(TransitWalkingCoordinate(37.65636195, 127.06342739),
            TransitWalkingCoordinate(37.65659005, 127.06400007))
        val exitPins = TransitWalkingQueryEndpoints(TransitWalkingCoordinate(37.48392182, 126.92989417),
            TransitWalkingCoordinate(37.48426844, 126.92977778))
        val observedExit = exitPins.end.copy(lat = 37.48427)
        val transfer = geometry.copy(providerQueryEndpoints = TransitWalkingQueryEndpoints(
            TransitWalkingCoordinate(37.4769, 126.9816), TransitWalkingCoordinate(37.4765, 126.9812)))
        val original = journey().let { it.copy(entranceWalkEndpoints = accessPins, exitWalkEndpoints = exitPins,
            legs = it.legs.map { leg -> if (leg.sequence == 2) leg.copy(walkingGeometry = transfer) else leg }) }
        val provider = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime = error("Use one batch")
            override fun refineBoundaries(access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
                                          unchanged: List<WalkingPathRefinement>): BoundaryWalkRefinement {
                assertEquals(accessPins, access)
                assertEquals(exitPins, exit)
                assertEquals(listOf(WalkingPathRefinement(transfer, 3)), unchanged)
                return BoundaryWalkRefinement(VerifiedAccessWalk(137, 148, now, accessPins.start, accessPins.end),
                    VerifiedAccessWalk(73, 78, now, exitPins.start, observedExit),
                    mapOf(unchanged.single() to VerifiedWalkingTime(157, now)))
            }
        }
        val enriched = TransitWalkingTimeEnricher(listOf(provider)).enrich(original)
        assertEquals(listOf(0, 1, 2, 3, 4), enriched.legs.map { it.sequence })
        assertEquals(listOf(137L, 157L, 73L), enriched.legs.filterNot { it.isRide }.map { it.movementDurationSeconds })
        assertEquals(TransitWalkingTimingSource.ODSAY_EXIT_WALK_SECONDS, enriched.legs.last().walkingTiming?.source)
        val exitGeometry = requireNotNull(enriched.legs.last().walkingGeometry)
        assertEquals(TransitWalkingEndpointBasis.PROVIDER_PATH_ENDPOINTS, exitGeometry.endpointBasis)
        assertEquals(exitPins.start, exitGeometry.from)
        assertEquals(observedExit, exitGeometry.to)
        assertEquals(exitPins, exitGeometry.providerQueryEndpoints)
        assertEquals(accessPins.start, enriched.legs.first().walkingGeometry?.from)
    }

    @Test
    fun `원래 경로와 일치한 접근 도보와 마지막 도보 모두 초를 사용한다`() {
        val accessGeometry = geometry.copy(distanceMeters = 203, providerQueryEndpoints = TransitWalkingQueryEndpoints(
            TransitWalkingCoordinate(37.476563, 126.981746), TransitWalkingCoordinate(37.476793, 126.981673)))
        val egress = geometry.copy(distanceMeters = 293, providerQueryEndpoints = TransitWalkingQueryEndpoints(
            TransitWalkingCoordinate(37.316821, 126.823414), TransitWalkingCoordinate(37.315381, 126.823090)))
        val original = journey().copy(legs = listOf(
            TransitJourneyLeg(0, TransitLegMode.WALK, 4, walkingGeometry = accessGeometry),
            journey().legs[1],
            TransitJourneyLeg(2, TransitLegMode.WALK, 5, walkingGeometry = egress),
        ))
        val provider = object : TransitWalkingTimeProvider {
            override val providerId = "odsay"
            override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int): VerifiedWalkingTime = error("Use one batch")
            override fun refineBoundaries(access: TransitWalkingQueryEndpoints?, exit: TransitWalkingQueryEndpoints?,
                                          unchanged: List<WalkingPathRefinement>): BoundaryWalkRefinement {
                assertEquals(listOf(WalkingPathRefinement(accessGeometry, 4), WalkingPathRefinement(egress, 5)), unchanged)
                return BoundaryWalkRefinement(null, null, unchanged.zip(listOf(188L, 268L)).associate { (walk, seconds) ->
                    walk to VerifiedWalkingTime(seconds, now)
                })
            }
        }
        val verified = TransitWalkingTimeEnricher(listOf(provider)).enrich(original)
        assertEquals(listOf(188L, 268L), verified.legs.filterNot { it.isRide }.map { it.movementDurationSeconds })
        assertSame(accessGeometry, verified.legs.first().walkingGeometry)
    }

    @Test
    fun `다른 공급자 역 호선과 누락된 방향을 공식 정확한 승강장값으로 가장하지 않는다`() {
        val original = journey()
        assertEquals(180L, enricher(157).enrich(original.copy(provider = "tmap")).legs[2].movementDurationSeconds)
        val different = original.copy(legs = original.legs.mapIndexed { index, leg ->
            if (index == 3) leg.copy(from = TransitStop(name = "다른역")) else leg
        })
        assertNull(enricher(null).enrich(different).legs[2].walkingTiming?.transferReference)
        assertNull(SeoulTransferReferenceCatalog().find("사당", SeoulSubwayLine.LINE_4, SeoulSubwayLine.LINE_3))
    }

    @Test
    fun `안전 여유는 도보에 포함시키지 않고 max 규칙과 실제 준비시각을 보존한다`() {
        val audit = TransitTransferFeasibilityEvaluator(30, 60).describeTransfer(now, 157, now.plusSeconds(240))
        assertEquals(157L, audit.walkingSeconds)
        assertEquals(60L, audit.requiredMarginSeconds)
        assertEquals(now.plusSeconds(217), audit.confidenceReadyAt)
        assertEquals(23L, audit.remainingSlackSeconds)
    }

    private fun enricher(seconds: Long?) = TransitWalkingTimeEnricher(listOf(object : TransitWalkingTimeProvider {
        override val providerId = "odsay"
        override fun refine(geometry: TransitWalkingGeometry, originalMinutes: Int) = seconds?.let { VerifiedWalkingTime(it, now) }
    }), listOf(SeoulTransferReferenceCatalog()))
}
