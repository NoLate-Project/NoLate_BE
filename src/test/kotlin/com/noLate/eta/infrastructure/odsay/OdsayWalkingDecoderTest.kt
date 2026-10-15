package com.noLate.eta.infrastructure.odsay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class OdsayWalkingDecoderTest {
    private val mapper = jacksonObjectMapper()
    private val link = """{"rseq":1,"LENGTH":170,"DURATION":157,
        "xyInfos":[{"x":126.981,"y":37.477},{"x":126.982,"y":37.478}]}"""
    private fun node(routes: String = "[$link]", distance: String = "170") =
        mapper.readTree("""{"duration":3,"distance":$distance,"routes":$routes}""")

    @Test
    fun `기록된 노원 사당 신림 원본은 거리와 상세를 복원하지만 연결시간을 임의 단축하지 않는다`() {
        val payload = requireNotNull(javaClass.getResourceAsStream("/eta/odsay-maas-walking-20261012.json"))
            .use { mapper.readTree(it) }
        val walks = payload.path("walks").toList()
        val decoded = walks.map { OdsayWalkingDecoder.decode(it, it.path("duration").intValue()) }
        assertEquals(listOf(2L, 170L, 14L), decoded.map { it.geometry?.distanceMeters })
        assertTrue(decoded.all { it.geometry?.pathEvidence?.status == TransitWalkingPathStatus.PROVIDED })
        assertTrue(decoded.all { it.durationSeconds == null })
        assertEquals(157L, walks[1].path("routes").sumOf { it.path("DURATION").longValue() })
        assertEquals(listOf(60L, 180L, 60L), walks.mapIndexed { index, walk ->
            TransitJourneyLeg(index, TransitLegMode.WALK, walk.path("duration").intValue(),
                durationSeconds = decoded[index].durationSeconds,
                walkingGeometry = decoded[index].geometry).movementDurationSeconds
        })
    }

    @Test
    fun `완전한 도보 링크는 미터 좌표와 초를 보존하되 승강장 포함은 확정하지 않는다`() {
        val decoded = OdsayWalkingDecoder.decode(node(), 3)
        assertEquals(157L, decoded.durationSeconds)
        val geometry = requireNotNull(decoded.geometry)
        assertEquals(170L, geometry.distanceMeters)
        assertNull(geometry.from)
        assertNull(geometry.to)
        assertEquals(TransitWalkingEndpointBasis.UNKNOWN, geometry.endpointBasis)
        val evidence = requireNotNull(geometry.pathEvidence)
        assertEquals("odsay", evidence.provider)
        assertEquals(TransitWalkingPathStatus.PROVIDED, evidence.status)
        assertEquals(TransitStationInteriorCoverage.UNKNOWN, evidence.stationInteriorCoverage)
        assertEquals(TransitWalkingCoordinate(37.477, 126.981), evidence.steps.single().coordinates.first())
        assertEquals(170.0, evidence.steps.single().distanceMeters)
    }

    @Test
    fun `실제 응답처럼 별도 연결 좌표가 있으면 링크 초 합으로 환승시간을 줄이지 않는다`() {
        val payload = node()
        (payload.path("routes").path(0) as com.fasterxml.jackson.databind.node.ObjectNode)
            .set<com.fasterxml.jackson.databind.JsonNode>("crossXYInfos",
                mapper.readTree("""[{"x":126.98,"y":37.47},{"x":126.981,"y":37.477}]"""))
        val decoded = OdsayWalkingDecoder.decode(payload, 3)
        assertNull(decoded.durationSeconds)
        assertEquals(TransitWalkingPathStatus.PROVIDED, decoded.geometry?.pathEvidence?.status)
        assertEquals(180L, TransitJourneyLeg(0, TransitLegMode.WALK, 3,
            durationSeconds = decoded.durationSeconds, walkingGeometry = decoded.geometry).movementDurationSeconds)
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "-1", "1.5", "true", "2147483648", "\"bad\""])
    fun `잘못된 초는 경로를 버리거나 0초로 바꾸지 않는다`(value: String) {
        val decoded = OdsayWalkingDecoder.decode(node("[${link.replace("157", value)}]"), 3)
        assertNull(decoded.durationSeconds)
        assertEquals(TransitWalkingPathStatus.PROVIDED, decoded.geometry?.pathEvidence?.status)
    }

    @Test
    fun `분 거리 순번 또는 링크 연결이 충돌하면 기존 분 계산을 보존한다`() {
        assertNull(OdsayWalkingDecoder.decode(node(), 2).durationSeconds)
        assertNull(OdsayWalkingDecoder.decode(node(distance = "169"), 3).durationSeconds)
        assertNull(OdsayWalkingDecoder.decode(node("[${link.replace("\"rseq\":1", "\"rseq\":2")}]"), 3).durationSeconds)
        val next = link.replace("\"rseq\":1", "\"rseq\":2")
        assertNull(OdsayWalkingDecoder.decode(node("[$link,$next]", "340"), 6).durationSeconds)
    }

    @Test
    fun `거리만 있으면 상세 미지원으로 남기고 누락 거리를 0으로 만들지 않는다`() {
        val missing = OdsayWalkingDecoder.decode(mapper.readTree("{}"), 3)
        assertNull(missing.geometry)
        assertNull(missing.durationSeconds)
        val distanceOnly = OdsayWalkingDecoder.decode(mapper.readTree("""{"distance":170}"""), 3)
        assertEquals(170L, distanceOnly.geometry?.distanceMeters)
        assertEquals(TransitWalkingPathStatus.UNAVAILABLE, distanceOnly.geometry?.pathEvidence?.status)
    }

    @Test
    fun `잘못된 좌표나 과도한 상세는 제한 상태로 남기고 초 계산에 사용하지 않는다`() {
        val invalid = OdsayWalkingDecoder.decode(node("[${link.replace("37.477", "137.477")}]"), 3)
        assertEquals(TransitWalkingPathStatus.INVALID, invalid.geometry?.pathEvidence?.status)
        assertNull(invalid.durationSeconds)
        val large = OdsayWalkingDecoder.decode(node(List(33) { link }.joinToString(",", "[", "]")), 3)
        assertEquals(TransitWalkingPathStatus.LIMIT_EXCEEDED, large.geometry?.pathEvidence?.status)
        assertTrue(requireNotNull(large.geometry?.pathEvidence).steps.isEmpty())
        assertNull(large.durationSeconds)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `실제 mapper는 상세 도보를 canonical 여정에 연결하고 경로 추정시각은 시간표로 승격하지 않는다`(connector: Boolean) {
        val walk = node()
        if (connector) {
            (walk.path("routes").path(0) as com.fasterxml.jackson.databind.node.ObjectNode)
                .putArray("crossXYInfos").addObject().put("x", 126.98).put("y", 37.47)
        }
        val response = mapper.readTree("""{"result":{"paths":[{
            "pathType":2,"totalTime":5,"startDateTime":"202610121200","endDateTime":"202610121205",
            "rps":[$walk,{"trafficType":2,"duration":2,"startDateTime":"202610121203",
            "endDateTime":"202610121205","startID":1,"endID":2}]}]}}""")
        (response.path("result").path("paths").path(0).path("rps").path(0)
            as com.fasterxml.jackson.databind.node.ObjectNode).put("trafficType", 3)
        val request = TransitJourneySearchRequest(37.6,127.0,37.5,126.9,
            Instant.parse("2026-10-12T03:00:00Z"),180)
        val journey = OdsayTransitJourneyMapper().map(response, request, request.departureAt).single()
        assertEquals(5, journey.totalMinutes)
        assertEquals(170L, journey.legs.first().walkingGeometry?.distanceMeters)
        assertEquals(if (connector) null else 157L, journey.legs.first().durationSeconds)
        assertEquals(TransitLegTimingBasis.ROUTE_ESTIMATE, journey.rideLegs.single().timingBasis)
    }
}
