package com.noLate.eta.infrastructure.tmap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TmapWalkingPathEvidenceDecoderTest {
    private val mapper = jacksonObjectMapper()
    private fun decode(steps: String) = TmapWalkingPathEvidenceDecoder.decode(mapper.readTree("""{"steps":$steps}"""))
    private val valid = """{"distance":17.5,"description":"노원역 10번출구에서 이동",
        "linestring":"127.06343,37.65635 127.063614,37.65639"}"""

    @Test
    fun `이전에 수집한 공개 경로 원본을 HTTP 없이 재생해 실제 steps 형태를 확인한다`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/eta/tmap-walking-public-station-audit.json"))
        val document = resource.use { mapper.readTree(it) }
        val walks = document.path("matches")[0].path("legs").filter { it.path("mode").asText() == "WALK" }
        val evidence = walks.map(TmapWalkingPathEvidenceDecoder::decode)
        assertEquals(listOf(TransitWalkingPathStatus.PROVIDED,TransitWalkingPathStatus.UNAVAILABLE,
            TransitWalkingPathStatus.PROVIDED),evidence.map { it.status })
        assertEquals(listOf(3,0,6),evidence.map { it.steps.size })
        assertEquals(128.0,evidence[0].steps.sumOf { requireNotNull(it.distanceMeters) })
        assertEquals(126.0,evidence[2].steps.sumOf { requireNotNull(it.distanceMeters) })
        assertTrue(evidence.all { it.stationInteriorCoverage == TransitStationInteriorCoverage.UNKNOWN })
        assertEquals(listOf(111L,243L,171L),walks.map { it.path("sectionTime").asLong() })
    }

    @Test
    fun `순서와 좌표 거리 출구 설명을 보존해도 승강장 포함 여부는 UNKNOWN이다`() {
        val result = decode("[$valid,$valid]")
        assertEquals("tmap", result.provider)
        assertEquals(TransitWalkingPathStatus.PROVIDED, result.status)
        assertEquals(TransitStationInteriorCoverage.UNKNOWN, result.stationInteriorCoverage)
        assertEquals(2, result.steps.size)
        assertEquals(17.5, result.steps.first().distanceMeters)
        assertEquals("노원역 10번출구에서 이동", result.steps.first().description)
        assertEquals(listOf(TransitWalkingCoordinate(37.65635,127.06343),
            TransitWalkingCoordinate(37.65639,127.063614)), result.steps.first().coordinates)
        assertEquals(result, mapper.readValue(mapper.writeValueAsString(result), TransitWalkingPathEvidence::class.java))
    }

    @Test
    fun `누락 null 빈 배열은 정상적인 상세 미제공이며 0초 경로를 생성하지 않는다`() {
        for (payload in listOf("{}", """{"steps":null}""", """{"steps":[]}""")) {
            val result = TmapWalkingPathEvidenceDecoder.decode(mapper.readTree(payload))
            assertEquals(TransitWalkingPathStatus.UNAVAILABLE, result.status)
            assertTrue(result.steps.isEmpty())
        }
    }

    @Test
    fun `잘못된 상세 한 구간도 부분 경로로 채택하지 않는다`() {
        val invalid = listOf("null", "{}", """{"linestring":"127,37"}""",
            """{"linestring":"NaN,37 127,38"}""", """{"linestring":"127,91 127,38"}""",
            """{"linestring":"127,37,1 127,38"}""", """{"linestring":123}""",
            valid.replace("17.5", "-1"), valid.replace("17.5", "true"),
            valid.replace("17.5", "\"Infinity\""), valid.replace("노원역 10번출구에서 이동", "bad\\ntext"))
        for (step in invalid) {
            val result = decode("[$valid,$step]")
            assertEquals(TransitWalkingPathStatus.INVALID, result.status, step)
            assertTrue(result.steps.isEmpty())
        }
        assertEquals(TransitWalkingPathStatus.INVALID, decode("{}").status)
    }

    @Test
    fun `큰 상세는 잘라 저장하지 않고 제한 초과로 구분한다`() {
        val tooManySteps = List(33) { valid }.joinToString(",", "[", "]")
        val tooManyPoints = List(129) { "127,37" }.joinToString(" ")
        val withinStepLimit = List(128) { "127,37" }.joinToString(" ")
        for (steps in listOf(tooManySteps,
            """[{"linestring":"$tooManyPoints"}]""",
            List(5) { """{"linestring":"$withinStepLimit"}""" }.joinToString(",", "[", "]"),
            "[${valid.replace("노원역 10번출구에서 이동", "가".repeat(201))}]",
            """[{"linestring":"${"0".repeat(8193)}"}]""")) {
            val result = decode(steps)
            assertEquals(TransitWalkingPathStatus.LIMIT_EXCEEDED, result.status)
            assertTrue(result.steps.isEmpty())
        }
    }

    @Test
    fun `분명하지 않은 거리 설명은 null이며 polyline은 그대로 남긴다`() {
        val result = decode("""[{"linestring":"127,37 127.001,37.001"}]""")
        assertEquals(TransitWalkingPathStatus.PROVIDED, result.status)
        assertNull(result.steps.single().distanceMeters)
        assertNull(result.steps.single().description)
    }
}
