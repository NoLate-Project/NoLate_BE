package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.domain.TransitCityCodeNamespace
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class FirstBoardingRealtimeOverlayTest {
    private val evaluatedAt = Instant.parse("2026-07-29T03:00:00Z")
    private val arrivalService = mock<TransitArrivalService>()

    @Test
    fun `최신 목록에서 잠시 빠진 동일 열차는 원래 원천시각의 120초 경계까지만 유지한다`() {
        val clock = AdvancingClock(evaluatedAt)
        val original = canonicalArrival().copy(sourceUpdatedAt = evaluatedAt.minusSeconds(65).toString())
        val other = canonicalArrival().copy(trainNumber = "4999")
        whenever(arrivalService.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(listOf(original), listOf(other), listOf(other), listOf(other))
        val service = FirstBoardingRealtimeOverlay(arrivalService, clock)
        val journey = timedCanonicalJourney()
        assertNotNull(service.resolve(journey, evaluatedAt, 1440).overlay)

        for (seconds in listOf(22L, 55L)) {
            clock.current = evaluatedAt.plusSeconds(seconds)
            val result = service.resolve(journey, clock.current, 1440)
            assertNotNull(result.overlay)
            val observation = requireNotNull(result.firstBoardingObservation)
            assertEquals("4012", observation.trainNumber)
            assertEquals(evaluatedAt.minusSeconds(65), observation.sourceUpdatedAt)
            assertEquals(evaluatedAt.plusSeconds(360), observation.expectedBoardingAt)
        }
        // 캐시 수신시각을 갱신해도 공급자 원천시각은 새로 만들지 않는다.
        clock.current = evaluatedAt.plusSeconds(56)
        assertNull(service.resolve(journey, clock.current, 1440).firstBoardingObservation)
    }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "stale", "wrongDirection", "wrongClass", "unverified", "timeout", "quota"])
    fun `현재 응답 자체가 유효하지 않으면 이전 차량으로 실시간 성공을 만들지 않는다`(case: String) {
        val clock = AdvancingClock(evaluatedAt)
        val original = canonicalArrival()
        val next = original.copy(trainNumber = "4999").let { candidate -> when (case) {
            "stale" -> candidate.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString())
            "wrongDirection" -> candidate.copy(direction = "상행")
            "wrongClass" -> candidate.copy(express = true)
            "unverified" -> candidate.copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY)
            else -> candidate
        } }
        var calls = 0
        whenever(arrivalService.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenAnswer {
                if (++calls == 1) listOf(original) else when (case) {
                    "timeout" -> throw java.net.SocketTimeoutException()
                    "quota" -> throw com.noLate.transit.infrastructure.TransitProviderApplicationException(
                        com.noLate.transit.infrastructure.TransitWireProvider.SEOUL_SUBWAY, "ERROR-337")
                    "empty" -> emptyList()
                    else -> listOf(next)
                }
            }
        val service = FirstBoardingRealtimeOverlay(arrivalService, clock)
        val journey = timedCanonicalJourney()
        assertNotNull(service.resolve(journey, evaluatedAt, 1440).overlay)
        clock.current = evaluatedAt.plusSeconds(22)
        val rejected = service.resolve(journey, clock.current, 1440)
        assertNull(rejected.overlay)
        assertNull(rejected.firstBoardingObservation)
    }

    @ParameterizedTest
    @ValueSource(strings = ["departed", "shortTurn", "wrongDirection", "wrongClass", "unverified", "updated"])
    fun `같은 열차의 최신 정보가 있으면 이전 관측으로 덮어쓰지 않는다`(case: String) {
        val clock = AdvancingClock(evaluatedAt)
        val original = canonicalArrival()
        val latest = original.copy(sourceUpdatedAt = evaluatedAt.plusSeconds(22).toString(),
            expectedAt = evaluatedAt.plusSeconds(400).toString()).let { candidate -> when (case) {
            "departed" -> candidate.copy(arrivalStatus = TransitArrivalStatus.DEPARTED)
            "shortTurn" -> candidate.copy(destinationName = "동작")
            "wrongDirection" -> candidate.copy(direction = "상행")
            "wrongClass" -> candidate.copy(express = true)
            "unverified" -> candidate.copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY)
            else -> candidate
        } }
        whenever(arrivalService.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(listOf(original), listOf(latest, original.copy(trainNumber = "4999")))
        val service = FirstBoardingRealtimeOverlay(arrivalService, clock)
        val journey = timedCanonicalJourney()
        assertNotNull(service.resolve(journey, evaluatedAt, 1440).overlay)
        clock.current = evaluatedAt.plusSeconds(22)
        val result = service.resolve(journey, clock.current, 1440)
        if (case == "updated") {
            assertEquals(evaluatedAt.plusSeconds(400), result.firstBoardingObservation?.expectedBoardingAt)
            assertEquals(clock.current, result.firstBoardingObservation?.sourceUpdatedAt)
        } else {
            assertNull(result.overlay)
            assertNull(result.firstBoardingObservation)
        }
    }

    @Test
    fun `보존된 관측도 접근 도보와 승차 여유를 더하면 늦는 순간에는 제외한다`() {
        val clock = AdvancingClock(evaluatedAt)
        val original = canonicalArrival().copy(expectedAt = evaluatedAt.plusSeconds(202).toString())
        whenever(arrivalService.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(listOf(original), listOf(canonicalArrival().copy(trainNumber = "4999")))
        val service = FirstBoardingRealtimeOverlay(arrivalService, clock)
        val journey = timedCanonicalJourney().copy(departureAt = evaluatedAt)
        assertNotNull(service.resolve(journey, evaluatedAt, 1440).overlay)
        clock.current = evaluatedAt.plusSeconds(22)
        assertNotNull(service.resolve(journey, clock.current, 1440).overlay)
        clock.current = evaluatedAt.plusSeconds(23)
        assertNull(service.resolve(journey, clock.current, 1440).firstBoardingObservation)
    }

    private fun timedCanonicalJourney(): TransitJourney {
        val original = canonicalJourney()
        val table = com.noLate.eta.domain.TransitLegTimetable(
            "seoul-timetable", SeoulSubwayLine.LINE_4, java.time.LocalDate.of(2026, 7, 29),
            "K4012", "DOWN", TransitServiceClass.LOCAL, "FROM", "TO", "사당",
            evaluatedAt.plusSeconds(300), evaluatedAt.plusSeconds(1320), evaluatedAt,
            realtimeTrainNumber = "4012",
        )
        return original.copy(legs = original.legs.map { leg -> if (!leg.isRide) leg else leg.copy(
            waitingMinutes = 3, scheduledDepartureAt = evaluatedAt.plusSeconds(120),
            scheduledArrivalAt = table.arrivalAt,
            timingBasis = com.noLate.eta.domain.TransitLegTimingBasis.TIMETABLE, timetable = table,
        ) })
    }

    private class AdvancingClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }

    @ParameterizedTest
    @ValueSource(strings = ["future", "stale", "unverified", "badSource", "empty", "wrongDirection", "wrongClass", "badIdentity", "past"])
    fun `미래 동일 열차 미관측은 API 실패 및 원천 freshness 거절과 구분한다`(case: String) {
        val original = canonicalJourney()
        val table = com.noLate.eta.domain.TransitLegTimetable("seoul-timetable", SeoulSubwayLine.LINE_4,
            java.time.LocalDate.of(2026, 7, 29), "K4012", "DOWN", TransitServiceClass.LOCAL,
            "FROM", "TO", "사당", evaluatedAt.plusSeconds(if (case == "past") -30 else 300),
            evaluatedAt.plusSeconds(1320), evaluatedAt, realtimeTrainNumber = "4012")
        val journey = original.copy(legs = original.legs.map { leg -> if (!leg.isRide) leg else leg.copy(
            timetable = table, waitingMinutes = 3, scheduledDepartureAt = table.boardingAt.minusSeconds(180),
            scheduledArrivalAt = table.arrivalAt, timingBasis = com.noLate.eta.domain.TransitLegTimingBasis.TIMETABLE) })
        val other = canonicalArrival().copy(trainNumber = "4103").let { a -> when (case) {
            "stale" -> a.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString())
            "unverified" -> a.copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY)
            "badSource" -> a.copy(sourceUpdatedAt = "not-a-timestamp")
            "wrongDirection" -> a.copy(direction = "상행")
            "wrongClass" -> a.copy(express = true)
            "badIdentity" -> a.copy(trainNumber = "4012", destinationName = "동작")
            else -> a
        } }
        whenever(arrivalService.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any()))
            .thenReturn(if (case == "empty") emptyList() else listOf(other))
        val result = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
            .resolve(journey, evaluatedAt, 1440)
        val expected = when (case) {
            "future" -> "TRANSIT_TRAIN_NOT_YET_OBSERVED"
            "stale" -> "TRANSIT_ARRIVAL_SOURCE_STALE"
            "unverified", "badSource" -> "TRANSIT_ARRIVAL_SOURCE_UNVERIFIED"
            else -> "TRANSIT_ARRIVAL_UNAVAILABLE"
        }
        assertEquals(expected, result.failureReason?.substringBefore(':'))
        assertNull(result.overlay)
        assertNull(result.firstBoardingObservation)
    }

    @ParameterizedTest
    @ValueSource(strings = ["quota", "http429", "timeout", "wrappedTimeout", "error"])
    fun `현재 API 요청 예외는 빈 목록으로 숨기지 않고 안전 코드만 반환하고 캐시한다`(case: String) {
        val failure = when (case) {
            "quota" -> com.noLate.transit.infrastructure.TransitProviderApplicationException(
                com.noLate.transit.infrastructure.TransitWireProvider.SEOUL_SUBWAY, "ERROR-337")
            "http429" -> org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
            "timeout" -> java.net.SocketTimeoutException("private-secret")
            "wrappedTimeout" -> org.springframework.web.client.ResourceAccessException("private-secret", java.net.SocketTimeoutException())
            else -> IllegalStateException("private-secret")
        }
        whenever(arrivalService.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer { throw failure }
        val overlay = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
        val result = overlay.resolve(canonicalJourney(), evaluatedAt, 1440)
        assertEquals(result, overlay.resolve(canonicalJourney(), evaluatedAt, 1440))
        assertEquals(when (case) {
            "quota", "http429" -> "TRANSIT_ARRIVAL_RATE_LIMITED"
            "timeout", "wrappedTimeout" -> "TRANSIT_ARRIVAL_API_TIMEOUT"
            else -> "TRANSIT_ARRIVAL_API_ERROR"
        }, result.failureReason?.substringBefore(':'))
        verify(arrivalService, times(1)).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @ParameterizedTest
    @ValueSource(strings = ["matchedAlias", "noVerifiedAlias", "wrongTrain", "shortTurn", "wrongDirection", "express", "stale"])
    fun `서울 시간표 adapter가 검증한 K열차 별칭도 방향 종착역 종별 freshness를 모두 대조한다`(case: String) {
        val original = canonicalJourney()
        val timetable = com.noLate.eta.domain.TransitLegTimetable(
            "seoul-timetable", SeoulSubwayLine.LINE_4, java.time.LocalDate.of(2026, 7, 29),
            "K4012", "DOWN", TransitServiceClass.LOCAL, "FROM", "TO", "사당",
            evaluatedAt.plusSeconds(300), evaluatedAt.plusSeconds(1320), evaluatedAt,
            realtimeTrainNumber = if (case == "noVerifiedAlias") null else "4012",
        )
        val journey = original.copy(legs = original.legs.map { leg ->
            if (!leg.isRide) leg else leg.copy(waitingMinutes = 3,
                scheduledDepartureAt = evaluatedAt.plusSeconds(120), scheduledArrivalAt = timetable.arrivalAt,
                timingBasis = com.noLate.eta.domain.TransitLegTimingBasis.TIMETABLE, timetable = timetable)
        })
        val arrival = canonicalArrival().let { candidate -> when (case) {
            "wrongTrain" -> candidate.copy(trainNumber = "4999")
            "shortTurn" -> candidate.copy(destinationName = "동작")
            "wrongDirection" -> candidate.copy(direction = "상행")
            "express" -> candidate.copy(express = true)
            "stale" -> candidate.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString())
            else -> candidate
        } }
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "4호선", "사당 방면", "DOWN", 10))
            .thenReturn(listOf(arrival))
        val result = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
            .resolve(journey, evaluatedAt, 1440)
        if (case == "matchedAlias") {
            assertNotNull(result.overlay)
            assertEquals("4012", result.firstBoardingObservation?.trainNumber)
            assertEquals("K4012", journey.rideLegs.first().timetable?.trainNumber)
        } else {
            assertNull(result.overlay)
            assertNull(result.firstBoardingObservation)
        }
    }

    @Test
    fun `접근 도보 111초와 승차 여유 60초 경계의 차량은 분 올림으로 제외하지 않는다`() {
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "수도권 4호선", "사당 방면", "DOWN", 10))
            .thenReturn(listOf(subwayArrival("사당", false).copy(expectedAt = evaluatedAt.plusSeconds(171).toString())))
        val original = subwayJourney()
        val journey = original.copy(
            arrivalAt = evaluatedAt.plusSeconds(1491),
            legs = original.legs.map { leg ->
                if (leg.sequence == 0) leg.copy(durationSeconds = 111) else leg
            },
        )
        val service = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))

        val adjusted = requireNotNull(service.resolve(journey, evaluatedAt, 1440).overlay)

        assertEquals(evaluatedAt.plusSeconds(171), adjusted.boardingAt)
        assertEquals(evaluatedAt.plusSeconds(1371), adjusted.predictedArrivalAt)
        assertEquals(23, adjusted.travelMinutes)
        assertEquals(1, adjusted.firstBoardingWaitMinutes)
    }

    @Test
    fun `실시간 보정은 원본 전체 시각과 대기초로 계산하고 표시 분의 올림을 재누적하지 않는다`() {
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "수도권 4호선", "사당 방면", "DOWN", 10))
            .thenReturn(listOf(subwayArrival("사당", false).copy(expectedAt = evaluatedAt.plusSeconds(180).toString())))
        val original = subwayJourney()
        val journey = original.copy(
            arrivalAt = evaluatedAt.plusSeconds(1491),
            legs = original.legs.map { leg ->
                if (leg.sequence == 0) leg.copy(durationSeconds = 111) else leg
            },
        )
        val service = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))

        val adjusted = requireNotNull(service.resolve(journey, evaluatedAt, 1440).overlay)

        // 1491초 - 기존 대기 180초 + 실제 대기 (180 - 111)초 = 1380초.
        assertEquals(evaluatedAt.plusSeconds(1380), adjusted.predictedArrivalAt)
        assertEquals(23, adjusted.travelMinutes)
        assertEquals(2, adjusted.firstBoardingWaitMinutes)
    }

    @Test
    fun `대기시간이 분리되지 않은 경로는 초 소요시간이 있어도 대기를 임의로 더하지 않는다`() {
        val original = subwayJourney()
        val journey = original.copy(legs = original.legs.map { leg ->
            if (leg.isRide) leg.copy(waitingMinutes = null, durationSeconds = 1200) else leg
        })
        val service = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))

        val result = service.resolve(journey, evaluatedAt, 1440)

        assertNull(result.overlay)
        assertNull(result.firstBoardingObservation)
        verify(arrivalService).getSubwayArrivalsForEta("서울역", "수도권 4호선", "사당 방면", "DOWN", 10)
    }

    @Test
    fun `Tmap도 대기시간 없이 첫 차량을 확인하지만 전체 ETA에 더하거나 overlay로 승격하지 않는다`() {
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "4호선", "사당 방면", "DOWN", 10))
            .thenReturn(listOf(canonicalArrival()))
        val service = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))

        val result = service.resolve(canonicalJourney(), evaluatedAt, 1440)

        assertNull(result.overlay)
        assertEquals(com.noLate.schedule.application.TrafficFailureReasons.TRANSIT_ROUTE_TIMING_ESTIMATE_ONLY, result.failureReason)
        val observation = requireNotNull(result.firstBoardingObservation)
        assertEquals("seoul-openapi", observation.provider)
        assertEquals(evaluatedAt.plusSeconds(180), observation.readyAt)
        assertEquals(evaluatedAt.plusSeconds(360), observation.expectedBoardingAt)
        assertEquals(evaluatedAt, observation.sourceUpdatedAt)
        assertEquals("4012", observation.trainNumber)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrongLine", "wrongStation", "wrongDirection", "shortTurn", "express", "unknownClass", "stale", "receiptOnly", "wrongProvider"])
    fun `서로 다른 공급자를 연결할 때 식별자 방향 종별 freshness 불일치는 관측도 채택하지 않는다`(mismatch: String) {
        val original = canonicalArrival()
        val rejected = when (mismatch) {
            "wrongLine" -> original.copy(subwayLineId = "1002")
            "wrongStation" -> original.copy(stationName = "노원")
            "wrongDirection" -> original.copy(direction = "상행")
            "shortTurn" -> original.copy(destinationName = "동작")
            "express" -> original.copy(express = true)
            "unknownClass" -> original.copy(express = null)
            "stale" -> original.copy(sourceUpdatedAt = evaluatedAt.minusSeconds(121).toString())
            "receiptOnly" -> original.copy(freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY)
            else -> original.copy(provider = "other-provider")
        }
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "4호선", "사당 방면", "DOWN", 10))
            .thenReturn(listOf(rejected))

        val result = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
            .resolve(canonicalJourney(), evaluatedAt, 1440)

        assertNull(result.overlay)
        assertNull(result.firstBoardingObservation)
    }

    @Test
    fun `20분보다 먼 미래 승차는 현재 차량 도착정보를 조회하지 않는다`() {
        val original = canonicalJourney()
        val journey = original.copy(departureAt = evaluatedAt.plusSeconds(1200), arrivalAt = original.arrivalAt.plusSeconds(1200))

        val result = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
            .resolve(journey, evaluatedAt, 1440)

        assertNull(result.firstBoardingObservation)
        verify(arrivalService, never()).getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `Tmap에 방향 근거가 없으면 종착역만 같다고 첫 차량을 확정하지 않는다`() {
        val original = canonicalJourney()
        val journey = original.copy(legs = original.legs.map { if (it.isRide) it.copy(directionCode = null, directionName = null) else it })
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "4호선", null, null, 10))
            .thenReturn(listOf(canonicalArrival()))

        val result = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
            .resolve(journey, evaluatedAt, 1440)

        assertNull(result.firstBoardingObservation)
        assertNull(result.overlay)
    }

    @Test
    fun `첫 차량 도착이 20분을 넘으면 신선한 응답이어도 미래 승차 관측으로 제공하지 않는다`() {
        whenever(arrivalService.getSubwayArrivalsForEta("서울역", "4호선", "사당 방면", "DOWN", 10))
            .thenReturn(listOf(canonicalArrival().copy(expectedAt = evaluatedAt.plusSeconds(1201).toString())))

        val result = FirstBoardingRealtimeOverlay(arrivalService, Clock.fixed(evaluatedAt, ZoneOffset.UTC))
            .resolve(canonicalJourney(), evaluatedAt, 1440)

        assertNull(result.overlay)
        assertNull(result.firstBoardingObservation)
    }

    private fun canonicalJourney(): TransitJourney {
        val original = subwayJourney()
        return original.copy(provider = "tmap", legs = original.legs.map { leg ->
            if (!leg.isRide) leg else leg.copy(
                waitingMinutes = null,
                from = leg.from?.copy(cityCode = null, cityCodeNamespace = TransitCityCodeNamespace.UNKNOWN),
                line = requireNotNull(leg.line).copy(cityCode = null, seoulSubwayLine = SeoulSubwayLine.LINE_4),
            )
        })
    }

    private fun canonicalArrival() = subwayArrival("사당", false).copy(
        stationName = "서울역", subwayLineId = "1004", subwayStationId = "1004000426", trainNumber = "4012",
    )

    @Test
    fun `로컬 수신시각뿐인 앞 차량은 버리고 provider 원천시각이 있는 다음 차량을 고른다`() {
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "tago",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(6, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
                ),
                TransitArrivalDto(
                    provider = "verified-fixture",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                ),
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
        )

        val result = requireNotNull(
            overlay.resolve(transferJourney(), evaluatedAt, 1_440).overlay
        )

        assertEquals(evaluatedAt.plus(12, ChronoUnit.MINUTES), result.boardingAt)
    }

    @Test
    fun `로컬 수신시각만 있는 도착정보는 expectedAt이 미래여도 overlay를 적용하지 않는다`() {
        val registry = SimpleMeterRegistry()
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.LOCAL_RECEIPT_TIMESTAMP_ONLY,
                )
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
            operationalMetrics = NoLateOperationalMetrics(registry),
        )

        val result = overlay.resolve(transferJourney(), evaluatedAt, 1_440)
        val cachedResult = overlay.resolve(transferJourney(), evaluatedAt, 1_440)

        assertNull(result.overlay)
        assertNull(cachedResult.overlay)
        assertEquals(
            com.noLate.schedule.application.TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_UNVERIFIED,
            result.failureReason,
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.transit.provider.events")
                .tag("provider", "seoul_bus")
                .tag("outcome", "rejected_unverified_source")
                .counter()
                .count(),
        )
        verify(arrivalService, times(1)).getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `비서울 지하철은 같은 역명과 노선의 서울 도착정보가 있어도 조회하지 않는다`() {
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
        )

        val result = overlay.resolve(
            journey = subwayJourney(
                networkCityCode = "7000",
                stationName = "시청",
                alightingStationName = "서면",
                lineName = "부산 1호선",
            ),
            evaluatedAt = evaluatedAt,
            maxTravelMinutes = 1_440,
        )

        assertNull(result.overlay)
        assertEquals(
            "TRANSIT_ARRIVAL_UNAVAILABLE: 첫 승차 구간의 실시간 도착정보를 조회할 수 없습니다.",
            result.failureReason,
        )
        verify(arrivalService, never()).getSubwayArrivalsForEta(
            stationName = any(),
            lineName = anyOrNull(),
            directionName = anyOrNull(),
            directionCode = anyOrNull(),
            limit = any(),
        )
    }

    @Test
    fun `일반 노선명만 있고 선택 열차 종별을 입증하지 못하면 지하철 도착정보를 조회하지 않는다`() {
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
        )

        val result = overlay.resolve(
            journey = subwayJourney(
                lineName = "수도권 4호선",
                serviceClass = TransitServiceClass.UNKNOWN,
            ),
            evaluatedAt = evaluatedAt,
            maxTravelMinutes = 1_440,
        )

        assertNull(result.overlay)
        assertEquals(
            "TRANSIT_ARRIVAL_UNAVAILABLE: 첫 승차 구간의 실시간 도착정보를 조회할 수 없습니다.",
            result.failureReason,
        )
        verify(arrivalService, never()).getSubwayArrivalsForEta(
            stationName = any(),
            lineName = anyOrNull(),
            directionName = anyOrNull(),
            directionCode = anyOrNull(),
            limit = any(),
        )
    }

    @Test
    fun `서울권 지하철도 도착 열차의 종착역과 급행 여부가 선택 구간과 명시적으로 맞아야 한다`() {
        whenever(
            arrivalService.getSubwayArrivalsForEta(
                stationName = "서울역",
                lineName = "수도권 4호선",
                directionName = "사당 방면",
                directionCode = "DOWN",
                limit = 10,
            )
        ).thenReturn(
            listOf(
                subwayArrival(destinationName = "동작", express = false),
                subwayArrival(destinationName = "사당", express = true),
                subwayArrival(destinationName = "사당", express = null),
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
        )

        val result = overlay.resolve(
            journey = subwayJourney(),
            evaluatedAt = evaluatedAt,
            maxTravelMinutes = 1_440,
        )

        assertNull(result.overlay)
        assertEquals(
            "TRANSIT_ARRIVAL_UNAVAILABLE: 첫 승차 구간의 실시간 도착정보를 조회할 수 없습니다.",
            result.failureReason,
        )
    }

    @Test
    fun `서울권 일반 지하철의 종착역과 서비스 유형이 모두 맞으면 첫 승차 overlay를 허용한다`() {
        whenever(
            arrivalService.getSubwayArrivalsForEta(
                stationName = "서울역",
                lineName = "수도권 4호선",
                directionName = "사당 방면",
                directionCode = "DOWN",
                limit = 10,
            )
        ).thenReturn(listOf(subwayArrival(destinationName = "사당", express = false)))
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
        )

        val result = overlay.resolve(subwayJourney(), evaluatedAt, 1_440)

        val adjusted = requireNotNull(result.overlay)
        assertEquals(evaluatedAt.plus(6, ChronoUnit.MINUTES), adjusted.boardingAt)
        assertEquals(26, adjusted.travelMinutes)
        assertEquals(evaluatedAt.plus(26, ChronoUnit.MINUTES), adjusted.predictedArrivalAt)
    }

    @Test
    fun `선택 경로가 급행임을 명시한 경우 일반 열차 대신 급행 도착정보만 허용한다`() {
        whenever(
            arrivalService.getSubwayArrivalsForEta(
                stationName = "서울역",
                lineName = "수도권 4호선 급행",
                directionName = "사당 방면",
                directionCode = "DOWN",
                limit = 10,
            )
        ).thenReturn(
            listOf(
                subwayArrival(destinationName = "사당", express = false),
                subwayArrival(destinationName = "사당", express = true),
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
        )

        val result = overlay.resolve(
            subwayJourney(
                lineName = "수도권 4호선 급행",
                serviceClass = TransitServiceClass.EXPRESS,
            ),
            evaluatedAt,
            1_440,
        )

        assertNotNull(result.overlay)
    }

    @Test
    fun `환승 여정에서도 첫 승차만 현재 도착정보로 보정하고 미래 환승 지하철은 조회하지 않는다`() {
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
            boardingBufferSeconds = 60,
            arrivalLimit = 10,
        )

        val result = overlay.resolve(
            journey = transferJourney(),
            evaluatedAt = evaluatedAt,
            maxTravelMinutes = 1_440,
        )

        assertNotNull(result.overlay)
        val adjusted = requireNotNull(result.overlay)
        assertEquals(57, adjusted.travelMinutes)
        assertEquals(evaluatedAt.plus(57, ChronoUnit.MINUTES), adjusted.predictedArrivalAt)
        verify(arrivalService).getBusArrivals(
            arsId = eq("02005"),
            routeName = eq("402"),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = eq("서울역버스환승센터"),
            limit = eq(10),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
        verify(arrivalService, never()).getSubwayArrivalsForEta(
            stationName = any(),
            lineName = anyOrNull(),
            directionName = anyOrNull(),
            directionCode = anyOrNull(),
            limit = any(),
        )
    }

    @Test
    fun `실시간 첫 차량은 정류장 접근 뒤 승차 버퍼를 확보한 후보만 고른다`() {
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plusSeconds(5 * 60 + 30).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                ),
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plusSeconds(6 * 60).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                ),
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
            boardingBufferSeconds = 60,
            arrivalLimit = 10,
        )

        val resolved = requireNotNull(
            overlay.resolve(transferJourney(), evaluatedAt, 1_440).overlay
        )

        assertEquals(evaluatedAt.plusSeconds(6 * 60), resolved.boardingAt)
        assertEquals(51, resolved.travelMinutes)
    }

    @Test
    fun `같은 실시간 차량은 출발을 앞당겨 대기시간이 늘어도 절대 예측 도착시각이 빨라지지 않는다`() {
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.minus(10, ChronoUnit.MINUTES).toString(),
                    sourceUpdatedAt = evaluatedAt.minus(10, ChronoUnit.MINUTES).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
            boardingBufferSeconds = 60,
            arrivalLimit = 10,
        )
        val scheduled = transferJourney()
        val earlier = scheduled.copy(
            requestedDepartureAt = scheduled.requestedDepartureAt.minus(5, ChronoUnit.MINUTES),
            departureAt = scheduled.departureAt.minus(5, ChronoUnit.MINUTES),
            arrivalAt = scheduled.arrivalAt.minus(5, ChronoUnit.MINUTES),
        )
        val observationTime = evaluatedAt.minus(10, ChronoUnit.MINUTES)

        val scheduledOverlay = requireNotNull(
            overlay.resolve(scheduled, observationTime, 1_440).overlay
        )
        val earlierOverlay = requireNotNull(
            overlay.resolve(earlier, observationTime, 1_440).overlay
        )

        assertEquals(evaluatedAt.plus(57, ChronoUnit.MINUTES), scheduledOverlay.predictedArrivalAt)
        assertEquals(scheduledOverlay.predictedArrivalAt, earlierOverlay.predictedArrivalAt)
        assertEquals(57, scheduledOverlay.travelMinutes)
        assertEquals(62, earlierOverlay.travelMinutes)
        verify(arrivalService, times(1)).getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `sourceUpdatedAt이 freshness 상한보다 오래되면 expectedAt이 미래여도 overlay에서 제외한다`() {
        val registry = SimpleMeterRegistry()
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    expectedAt = evaluatedAt.plus(12, ChronoUnit.MINUTES).toString(),
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.minus(3, ChronoUnit.MINUTES).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
            boardingBufferSeconds = 60,
            arrivalLimit = 10,
            arrivalSourceMaxAgeSeconds = 120,
            operationalMetrics = NoLateOperationalMetrics(registry),
        )

        val result = overlay.resolve(transferJourney(), evaluatedAt, 1_440)
        val cachedResult = overlay.resolve(transferJourney(), evaluatedAt, 1_440)

        assertNull(result.overlay)
        assertNull(cachedResult.overlay)
        assertEquals(
            com.noLate.schedule.application.TrafficFailureReasons.TRANSIT_ARRIVAL_SOURCE_STALE,
            result.failureReason,
        )
        assertEquals(
            1.0,
            registry.get("nolate.eta.transit.provider.events")
                .tag("provider", "seoul_bus")
                .tag("outcome", "rejected_stale")
                .counter()
                .count(),
        )
        verify(arrivalService, times(1)).getBusArrivals(
            arsId = anyOrNull(),
            routeName = anyOrNull(),
            cityCode = anyOrNull(),
            nodeId = anyOrNull(),
            stationName = anyOrNull(),
            limit = any(),
            cityCodeNamespace = any(),
            providerCode = anyOrNull(),
        )
    }

    @Test
    fun `expectedAt이 없으면 대기초를 로컬 수신시각이 아니라 sourceUpdatedAt에 더한다`() {
        whenever(
            arrivalService.getBusArrivals(
                arsId = anyOrNull(),
                routeName = anyOrNull(),
                cityCode = anyOrNull(),
                nodeId = anyOrNull(),
                stationName = anyOrNull(),
                limit = any(),
                cityCodeNamespace = any(),
                providerCode = anyOrNull(),
            )
        ).thenReturn(
            listOf(
                TransitArrivalDto(
                    provider = "seoul-bus",
                    kind = "BUS",
                    routeName = "402",
                    waitSeconds = 390,
                    observedAt = evaluatedAt.toString(),
                    sourceUpdatedAt = evaluatedAt.minusSeconds(30).toString(),
                    freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
                )
            )
        )
        val overlay = FirstBoardingRealtimeOverlay(
            transitArrivalService = arrivalService,
            clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC),
            boardingBufferSeconds = 60,
            arrivalLimit = 10,
            arrivalSourceMaxAgeSeconds = 120,
        )

        val resolved = requireNotNull(
            overlay.resolve(transferJourney(), evaluatedAt, 1_440).overlay
        )

        assertEquals(evaluatedAt.plus(6, ChronoUnit.MINUTES), resolved.boardingAt)
        assertEquals(51, resolved.travelMinutes)
        assertEquals(evaluatedAt.plus(51, ChronoUnit.MINUTES), resolved.predictedArrivalAt)
    }

    private fun transferJourney() = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = evaluatedAt,
        departureAt = evaluatedAt,
        arrivalAt = evaluatedAt.plus(55, ChronoUnit.MINUTES),
        totalMinutes = 55,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.WALK,
                durationMinutes = 5,
            ),
            TransitJourneyLeg(
                sequence = 1,
                mode = TransitLegMode.BUS,
                durationMinutes = 25,
                waitingMinutes = 5,
                from = TransitStop(arsId = "02005", name = "서울역버스환승센터"),
                to = TransitStop(arsId = "02110", name = "충정로역"),
                line = TransitLine(providerRouteId = "bus-402", name = "402"),
                directionName = "강남역 방면",
                directionCode = "DOWN",
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.WALK,
                durationMinutes = 5,
            ),
            TransitJourneyLeg(
                sequence = 3,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = 15,
                waitingMinutes = 3,
                from = TransitStop(providerStopId = "430", name = "충정로"),
                to = TransitStop(providerStopId = "433", name = "강남"),
                line = TransitLine(providerRouteId = "1000:2", name = "2호선"),
                directionName = "강남 방면",
                directionCode = "DOWN",
            ),
            TransitJourneyLeg(
                sequence = 4,
                mode = TransitLegMode.WALK,
                durationMinutes = 5,
            ),
        ),
        fetchedAt = evaluatedAt,
    )

    private fun subwayJourney(
        networkCityCode: String = "1000",
        stationName: String = "서울역",
        alightingStationName: String = "사당",
        lineName: String = "수도권 4호선",
        serviceClass: TransitServiceClass = TransitServiceClass.LOCAL,
    ) = TransitJourney(
        provider = "odsay",
        requestedDepartureAt = evaluatedAt,
        departureAt = evaluatedAt,
        arrivalAt = evaluatedAt.plus(25, ChronoUnit.MINUTES),
        totalMinutes = 25,
        legs = listOf(
            TransitJourneyLeg(
                sequence = 0,
                mode = TransitLegMode.WALK,
                durationMinutes = 2,
            ),
            TransitJourneyLeg(
                sequence = 1,
                mode = TransitLegMode.SUBWAY,
                durationMinutes = 20,
                waitingMinutes = 3,
                from = TransitStop(
                    providerStopId = "426",
                    cityCode = networkCityCode,
                    cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
                    name = stationName,
                ),
                to = TransitStop(providerStopId = "433", name = alightingStationName),
                line = TransitLine(
                    providerRouteId = "$networkCityCode:4",
                    cityCode = networkCityCode,
                    name = lineName,
                    serviceClass = serviceClass,
                ),
                directionName = "$alightingStationName 방면",
                directionCode = "DOWN",
            ),
            TransitJourneyLeg(
                sequence = 2,
                mode = TransitLegMode.WALK,
                durationMinutes = 3,
            ),
        ),
        fetchedAt = evaluatedAt,
    )

    private fun subwayArrival(
        destinationName: String?,
        express: Boolean?,
    ) = TransitArrivalDto(
        provider = "seoul-openapi",
        kind = "SUBWAY",
        lineName = "4호선",
        direction = "하행",
        destinationName = destinationName,
        expectedAt = evaluatedAt.plus(6, ChronoUnit.MINUTES).toString(),
        observedAt = evaluatedAt.toString(),
        sourceUpdatedAt = evaluatedAt.toString(),
        freshnessEvidence = TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP,
        express = express,
    )
}
