package com.noLate.eta.application.transit

import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitJourneyLeg
import com.noLate.eta.domain.TransitLegMode
import com.noLate.eta.domain.TransitLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitStop
import com.noLate.global.observability.NoLateOperationalMetrics
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence
import com.noLate.transit.domain.TransitCityCodeNamespace
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
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
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class FirstBoardingRealtimeOverlayTest {
    private val evaluatedAt = Instant.parse("2026-07-29T03:00:00Z")
    private val arrivalService = mock<TransitArrivalService>()

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
            "TRANSIT_ARRIVAL_UNAVAILABLE: 첫 승차 구간의 실시간 도착정보를 조회할 수 없습니다.",
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
        verify(arrivalService, never()).getSubwayArrivals(
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
        verify(arrivalService, never()).getSubwayArrivals(
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
            arrivalService.getSubwayArrivals(
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
            arrivalService.getSubwayArrivals(
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
            arrivalService.getSubwayArrivals(
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
        verify(arrivalService, never()).getSubwayArrivals(
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
            "TRANSIT_ARRIVAL_UNAVAILABLE: 첫 승차 구간의 실시간 도착정보를 조회할 수 없습니다.",
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
