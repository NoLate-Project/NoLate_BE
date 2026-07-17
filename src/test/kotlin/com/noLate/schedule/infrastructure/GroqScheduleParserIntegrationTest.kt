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

    @Test
    fun `Groq가 일정 목적어 앞뒤의 역 이름을 도착지로 구분한다`() {
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

        // TEXT는 의도적으로 빠른 입력 전용 자연어 규칙을 적용하지 않는다. 따라서 이 테스트는
        // 같은 문장을 Groq 폴백으로 보내 프롬프트 자체가 어순을 이해하는지 검증한다.
        val result = parser.parse(
            text = "금요일 7시 술약속 신촌역",
            inputType = com.noLate.schedule.domain.ScheduleParseInputType.TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.aiAttempted)
        assertTrue(
            result.warnings.none { "AI 분석" in it },
            "Groq 호출 경고: ${result.warnings}",
        )
    }
}
