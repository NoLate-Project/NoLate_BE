package com.noLate.transit.application

import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.domain.TransitArrivalFreshnessEvidence.PROVIDER_SOURCE_TIMESTAMP
import com.noLate.transit.domain.TransitArrivalStatus
import com.noLate.transit.domain.TransitCityCodeNamespace
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import com.noLate.transit.infrastructure.TagoTransitArrivalClient
import com.noLate.transit.infrastructure.TransitProviderWireMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TransitArrivalServiceTest {
    private val seoulClient = mock<SeoulTransitArrivalClient>()
    private val tagoClient = mock<TagoTransitArrivalClient>()
    private val service = TransitArrivalService(seoulClient, tagoClient, TransitCityCodeResolver())

    @Test
    fun `요청한 방면 열차가 없으면 반대 방향을 역 안내에 노출하지 않는다`() {
        val wrong = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", direction = "상행")
        whenever(seoulClient.getSubwayArrivals(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), any())).thenReturn(listOf(wrong))

        assertTrue(service.getSubwayArrivals("노원", "4호선", null, "DOWN", 3).isEmpty())
        // ETA candidates have their own strict train matching; keep that input contract.
        assertEquals(listOf(wrong), service.getSubwayArrivalsForEta("노원", "4호선", null, "DOWN", 10))
    }

    @Test
    fun `역 안내는 동일 열차의 최신 관측만 남기고 시각이 같은 다른 열차는 보존한다`() {
        val now = Clock.fixed(Instant.parse("2026-11-15T03:07:10Z"), ZoneOffset.UTC)
        val board = TransitArrivalService(seoulClient, tagoClient, clock = now)
        val first = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", direction = "하행",
            subwayLineId = "1004", subwayStationId = "1004000411", trainNumber = "4012",
            expectedAt = "2026-11-15T03:10:00Z", sourceUpdatedAt = "2026-11-15T03:06:00Z",
            freshnessEvidence = PROVIDER_SOURCE_TIMESTAMP)
        val newer = first.copy(sourceUpdatedAt = "2026-11-15T03:07:00Z")
        val other = first.copy(trainNumber = "4013")
        whenever(seoulClient.getSubwayArrivals(eq("노원"), eq("4호선"), anyOrNull(), eq("DOWN"), any()))
            .thenReturn(listOf(first, newer, other))

        assertEquals(listOf(newer, other), board.getSubwayArrivals("노원", "4호선", null, "DOWN", 2))
    }

    @Test
    fun `사당역 실응답에서 6분 전 도착을 제외한 뒤 후속 열차의 예보를 반환한다`() {
        val time = Instant.parse("2026-11-20T02:40:03.449480Z")
        val board = TransitArrivalService(seoulClient, tagoClient, clock = Clock.fixed(time, ZoneOffset.UTC))
        val old = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", direction = "하행",
            stationName = "사당", subwayLineId = "1004", trainNumber = "4073", destinationName = "사당",
            sourceUpdatedAt = "2026-11-20T02:34:03Z", expectedAt = "2026-11-20T02:34:03Z",
            arrivalStatus = TransitArrivalStatus.ARRIVED, freshnessEvidence = PROVIDER_SOURCE_TIMESTAMP)
        val approaching = old.copy(trainNumber = "4579", destinationName = "오이도",
            sourceUpdatedAt = "2026-11-20T02:39:35Z", expectedAt = "2026-11-20T02:41:35Z",
            arrivalStatus = TransitArrivalStatus.PREVIOUS_STOP)
        val upcoming = approaching.copy(trainNumber = "4075", destinationName = "사당",
            expectedAt = "2026-11-20T02:48:05Z", arrivalStatus = TransitArrivalStatus.IN_TRANSIT)
        val captured = listOf(old, approaching, old.copy(trainNumber = "4071"), upcoming)
        whenever(seoulClient.getSubwayArrivals("사당", "4호선", null, "DOWN", 10)).thenReturn(captured)

        assertEquals(listOf(approaching, upcoming), board.getSubwayArrivals("사당", "4호선", null, "DOWN", 2))
        assertEquals(captured, board.getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10))
    }

    @Test
    fun `서울 버스의 지난 도착이 결과 한 자리를 채우지 않고 신선한 도착은 유지한다`() {
        val time = Instant.parse("2026-11-26T15:24:31Z")
        val board = TransitArrivalService(seoulClient, tagoClient, clock = Clock.fixed(time, ZoneOffset.UTC))
        val arrived = TransitArrivalDto(provider = "seoul-bus", kind = "BUS", routeName = "N37",
            arsId = "01234", arrivalStatus = TransitArrivalStatus.ARRIVED, freshnessEvidence = PROVIDER_SOURCE_TIMESTAMP,
            sourceUpdatedAt = time.minusSeconds(45).toString(), observedAt = time.toString())
        val older = arrived.copy(sourceUpdatedAt = time.minusSeconds(46).toString())
        whenever(seoulClient.getBusArrivals(eq("01234"), anyOrNull(), eq("N37"), any()))
            .thenAnswer { invocation -> listOf(older, arrived).take(invocation.getArgument(3)) }

        assertEquals(listOf(arrived),
            board.getBusArrivals("01234", "N37", "1000", null, null, 1, TransitCityCodeNamespace.ODSAY_CID, stationBoard = true))
        assertEquals(listOf(older),
            board.getBusArrivals("01234", "N37", "1000", null, null, 1, TransitCityCodeNamespace.ODSAY_CID))
    }

    @Test
    fun `사당역 4075편의 같은 예보는 게시 두 분 뒤 화면용 목록에서 만료된다`() {
        val source = Instant.parse("2026-11-20T02:39:35Z")
        val train = TransitArrivalDto(provider = "seoul-openapi", kind = "SUBWAY", direction = "하행",
            stationName = "사당", subwayLineId = "1004", trainNumber = "4075", destinationName = "사당",
            sourceUpdatedAt = source.toString(), observedAt = "2026-11-20T02:40:03.449480Z",
            expectedAt = "2026-11-20T02:48:05Z", arrivalStatus = TransitArrivalStatus.IN_TRANSIT,
            freshnessEvidence = PROVIDER_SOURCE_TIMESTAMP)
        val fresh = Instant.parse("2026-11-20T02:41:35Z")
        whenever(seoulClient.getSubwayArrivals("사당", "4호선", null, "DOWN", 10)).thenReturn(listOf(train))
        fun serviceAt(at: Instant) = TransitArrivalService(seoulClient, tagoClient, clock = Clock.fixed(at, ZoneOffset.UTC))

        assertEquals(listOf(train), serviceAt(fresh).getSubwayArrivals("사당", "4호선", null, "DOWN", 2))
        assertTrue(serviceAt(fresh.plusMillis(1)).getSubwayArrivals("사당", "4호선", null, "DOWN", 2).isEmpty())
        assertEquals(listOf(train), serviceAt(fresh.plusMillis(1)).getSubwayArrivalsForEta("사당", "4호선", null, "DOWN", 10))
    }

    @Test
    fun `서울 버스의 같은 도착 예보를 다시 받아도 공급자 게시시각 기준으로 만료된다`() {
        val source = Instant.parse("2026-11-26T15:23:20Z")
        val bus = TransitArrivalDto(provider = "seoul-bus", kind = "BUS", routeName = "N37", arsId = "01234",
            sourceUpdatedAt = source.toString(), observedAt = source.plusSeconds(50).toString(), expectedAt = source.plusSeconds(83).toString(),
            arrivalStatus = TransitArrivalStatus.IN_TRANSIT, freshnessEvidence = PROVIDER_SOURCE_TIMESTAMP)
        val newBus = bus.copy(sourceUpdatedAt = source.plusSeconds(50).toString(), expectedAt = source.plusSeconds(113).toString())
        val board = TransitArrivalService(seoulClient, tagoClient, clock = Clock.fixed(source.plusSeconds(51), ZoneOffset.UTC))
        whenever(seoulClient.getBusArrivals("01234", null, "N37", 10)).thenReturn(listOf(bus, newBus))

        assertEquals(listOf(newBus), board.getBusArrivals("01234", "N37", "1000", null, null, 1,
            TransitCityCodeNamespace.ODSAY_CID, stationBoard = true))
    }

    @Test
    fun `기존 목록 API는 빈 값으로 보호하지만 ETA 전용 조회는 같은 예외를 전달한다`() {
        val failure = IllegalStateException("provider private response")
        whenever(seoulClient.getSubwayArrivals("노원", "4호선", null, "DOWN", 10)).thenThrow(failure)
        assertTrue(service.getSubwayArrivals("노원", "4호선", null, "DOWN", 10).isEmpty())
        org.junit.jupiter.api.Assertions.assertSame(failure, org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            service.getSubwayArrivalsForEta("노원", "4호선", null, "DOWN", 10)
        })
    }

    @Test
    fun `direct TAGO namespace는 공식 2026 도시코드 34010을 허용하고 폐기된 29를 거부한다`() {
        val resolver = TransitCityCodeResolver()

        assertEquals(
            TransitCityResolution.Tago("34010"),
            resolver.resolve("34010", TransitCityCodeNamespace.TAGO),
        )
        assertEquals(
            TransitCityResolution.Unsupported,
            resolver.resolve("29", TransitCityCodeNamespace.TAGO),
        )
    }

    @Test
    fun `공식 명칭으로 검증된 전국 ODsay CID는 각 TAGO 도시코드로 변환한다`() {
        val resolver = TransitCityCodeResolver()
        val representatives = mapOf(
            "10170" to "32010", // 강원 춘천
            "11000" to "33010", // 충북 청주
            "3070" to "34010", // 충남 천안
            "9000" to "35010", // 전북 전주
            "5040" to "36030", // 전남 순천
            "4100" to "37010", // 경북 포항
            "7090" to "38010", // 경남 창원
            "8010" to "39", // 제주 서귀포
        )

        representatives.forEach { (odsayCid, tagoCode) ->
            assertEquals(
                TransitCityResolution.Tago(tagoCode),
                resolver.resolve(odsayCid, TransitCityCodeNamespace.ODSAY_CID),
            )
        }
    }

    @Test
    fun `ODsay 서울 CID 1000은 TAGO 코드로 위장하지 않고 서울 API를 사용한다`() {
        val expected = listOf(arrival("seoul-bus", "서울역버스환승센터", "402", null, arsId = "02005"))
        whenever(seoulClient.getBusArrivals("02005", "서울역버스환승센터", "402", 2))
            .thenReturn(expected)

        val result = service.getBusArrivals(
            arsId = "02-005",
            routeName = "402",
            cityCode = "1000",
            nodeId = null,
            stationName = "서울역버스환승센터",
            limit = 2,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
            providerCode = "4",
        )

        assertEquals(expected, result)
        verifyNoInteractions(tagoClient)
    }

    @Test
    fun `ODsay 세종 CID 3300은 현재 TAGO 세종 코드 12로 변환한다`() {
        val expected = listOf(
            arrival("tago", "정부세종청사", "B0", "12", nodeId = "SCB123", arsId = "51001")
        )
        whenever(tagoClient.getBusArrivals("51001", "SCB123", "12", "정부세종청사", "B0", 2))
            .thenReturn(expected)

        val result = service.getBusArrivals(
            arsId = "51001",
            routeName = "B0",
            cityCode = "3300",
            nodeId = "SCB123",
            stationName = "정부세종청사",
            limit = 2,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
        )

        assertEquals(expected, result)
        verifyNoInteractions(seoulClient)
    }

    @Test
    fun `ODsay 경기 성남 CID는 TAGO 시군 코드로 변환하고 서울 동명 정류장을 조회하지 않는다`() {
        val expected = listOf(
            arrival("tago", "서울역", "33", "31020", nodeId = "GGB123", arsId = "12345")
        )
        whenever(tagoClient.getBusArrivals("12345", "GGB123", "31020", "서울역", "33", 3))
            .thenReturn(expected)

        val result = service.getBusArrivals(
            arsId = "12345",
            routeName = "33",
            cityCode = "1010",
            nodeId = "GGB123",
            stationName = "서울역",
            limit = 3,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
            providerCode = "2",
        )

        assertEquals(expected, result)
        verifyNoInteractions(seoulClient)
    }

    @Test
    fun `city와 nodeId가 일치하면 provider 정류장 표기와 ARS 차이로 유효 결과를 버리지 않는다`() {
        val expected = listOf(
            arrival(
                "tago",
                "서울역(버스환승센터)",
                "33",
                "31020",
                nodeId = "GGB123",
                arsId = "99999",
            )
        )
        whenever(tagoClient.getBusArrivals("12345", "GGB123", "31020", "서울역", "33", 3))
            .thenReturn(expected)

        val result = service.getBusArrivals(
            arsId = "12345",
            routeName = "33",
            cityCode = "1010",
            nodeId = "GGB123",
            stationName = "서울역",
            limit = 3,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
        )

        assertEquals(expected, result)
    }

    @Test
    fun `ODsay 부산 CID는 TAGO 광역시 코드 21로 변환한다`() {
        val expected = listOf(
            arrival("tago", "부산역", "1001", "21", nodeId = "BSB100", arsId = "03001")
        )
        whenever(tagoClient.getBusArrivals("03001", "BSB100", "21", "부산역", "1001", 2))
            .thenReturn(expected)

        val result = service.getBusArrivals(
            arsId = "03001",
            routeName = "1001",
            cityCode = "7000",
            nodeId = "BSB100",
            stationName = "부산역",
            limit = 2,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
        )

        assertEquals(expected, result)
        verifyNoInteractions(seoulClient)
    }

    @Test
    fun `지원하지 않는 ODsay CID는 어떤 provider에도 잘못 전달하지 않고 fail closed한다`() {
        val result = service.getBusArrivals(
            arsId = "12345",
            routeName = "1",
            cityCode = "9999",
            nodeId = "NODE",
            stationName = "동명정류장",
            limit = 2,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
        )

        assertTrue(result.isEmpty())
        verifyNoInteractions(seoulClient, tagoClient)
    }

    @Test
    fun `지원하지 않는 CID는 원본 코드 없이 bounded namespace metric을 남긴다`() {
        val registry = SimpleMeterRegistry()
        val observedService = TransitArrivalService(
            seoulTransitArrivalClient = seoulClient,
            tagoTransitArrivalClient = tagoClient,
            cityCodeResolver = TransitCityCodeResolver(),
            wireMetrics = TransitProviderWireMetrics(registry),
        )

        observedService.getBusArrivals(
            arsId = "12345",
            routeName = "1",
            cityCode = "999999999",
            nodeId = "NODE",
            stationName = "정류장",
            limit = 1,
            cityCodeNamespace = TransitCityCodeNamespace.ODSAY_CID,
        )

        assertEquals(
            1.0,
            registry.get("nolate.eta.transit.mapping.unsupported")
                .tag("namespace", "odsay_cid")
                .counter()
                .count(),
        )
    }

    @Test
    fun `도시 metadata가 불명확하면 서울 stationName 검색을 하지 않고 TAGO 결과 식별자를 검증한다`() {
        whenever(tagoClient.getBusArrivals(null, null, null, "서울역", "402", 2))
            .thenReturn(
                listOf(
                    arrival("tago", "서울역", "402", null),
                    arrival("tago", "다른역", "402", null),
                )
            )

        val result = service.getBusArrivals(
            arsId = null,
            routeName = "402",
            cityCode = null,
            nodeId = null,
            stationName = "서울역",
            limit = 2,
        )

        assertEquals(1, result.size)
        assertEquals("서울역", result.single().stationName)
        verify(seoulClient, never()).getBusArrivals(anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    private fun arrival(
        provider: String,
        stationName: String,
        routeName: String,
        cityCode: String?,
        nodeId: String? = null,
        arsId: String? = null,
    ) = TransitArrivalDto(
        provider = provider,
        kind = "BUS",
        stationName = stationName,
        routeName = routeName,
        cityCode = cityCode,
        nodeId = nodeId,
        arsId = arsId,
    )
}
