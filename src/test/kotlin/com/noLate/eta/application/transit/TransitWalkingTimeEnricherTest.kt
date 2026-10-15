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
