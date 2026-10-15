package com.noLate.route.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.route.domain.TransitRouteRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntercityRouteQualityTest {
    private val mapper = jacksonObjectMapper()
    private val request = TransitRouteRequest(127.0, 37.5, 127.0, 35.5)

    private fun quality(mode: String, seconds: Int) = TransitRouteResponseQualityEvaluator.evaluate(
        mapper.readTree("""{"metaData":{"plan":{"itineraries":[{
          "totalTime":$seconds,"legs":[{"mode":"$mode","sectionTime":$seconds}]
        }]}}}"""), request,
    )

    @Test
    fun `빠른 철도는 시내버스 속도 상한으로 거절하지 않으며 불가능한 경로는 제외한다`() {
        assertTrue(quality("TRAIN", 3600).isUsable) // ~222km / 1h
        assertFalse(quality("BUS", 3600).isUsable)
        assertFalse(quality("TRAIN", 600).isUsable)
    }

    @Test
    fun `선형이 없어도 정상 소요시간의 고속버스는 후보로 인정한다`() {
        assertTrue(quality("EXPRESSBUS", 10800).isUsable)
        assertFalse(quality("WALK", 10800).isUsable)
    }
}
