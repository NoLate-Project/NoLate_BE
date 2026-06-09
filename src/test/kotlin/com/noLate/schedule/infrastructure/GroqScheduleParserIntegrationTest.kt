package com.noLate.schedule.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.application.service.ScheduleHybridParserService
import com.noLate.schedule.application.service.ScheduleTextParserService
import com.noLate.schedule.domain.ScheduleParseSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * 실제 Groq API와 하이브리드 일정 파서의 연결을 확인하는 외부 통합 테스트다.
 *
 * 네트워크와 사용량을 소비하므로 GROQ_API_KEY 환경 변수가 있을 때만 실행한다.
 * 일반 단위 테스트는 외부 서비스 없이 동작하고, 이 테스트는 배포 전 수동 검증에 사용한다.
 */
@Tag("external")
class GroqScheduleParserIntegrationTest {

    @Test
    fun `Groq가 규칙 파서에서 누락된 자연어 목적지를 보완한다`() {
        val apiKey = System.getenv("GROQ_API_KEY").orEmpty()
        assumeTrue(
            apiKey.isNotBlank(),
            "실제 Groq 테스트를 실행하려면 GROQ_API_KEY 환경 변수가 필요합니다.",
        )
        val model = System.getenv("GROQ_MODEL")
            ?.takeIf { it.isNotBlank() }
            ?: "openai/gpt-oss-20b"
        val aiParser = GroqScheduleAiParser(
            objectMapper = jacksonObjectMapper(),
            enabled = true,
            apiKey = apiKey,
            model = model,
        )
        val parser = ScheduleHybridParserService(
            ruleParser = ScheduleTextParserService(),
            aiParser = aiParser,
        )

        val result = parser.parse(
            text = "20260530 강남 오후8시30분 고등학교친구 와 회식",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-05-30", result.date)
        assertEquals("20:30", result.time)
        assertEquals("강남", result.destination?.name)
        assertEquals("강남 20:30", result.title)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.aiAttempted)
        assertNotNull(result.notes)
        assertTrue(result.warnings.isEmpty(), "Groq 호출 경고: ${result.warnings}")
    }
}
