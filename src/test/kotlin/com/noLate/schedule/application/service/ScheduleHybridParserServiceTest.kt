package com.noLate.schedule.application.service

import com.noLate.schedule.application.ScheduleAiParseOutcome
import com.noLate.schedule.application.ScheduleAiParseResult
import com.noLate.schedule.application.ScheduleAiParser
import com.noLate.schedule.domain.ScheduleParseSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 규칙 우선, 신뢰도 기반 AI 병합, 개인정보 마스킹 정책이 변경되지 않도록 보호하는 회귀 테스트다.
 */
class ScheduleHybridParserServiceTest {
    private val ruleParser = ScheduleTextParserService()

    @Test
    fun `does not call AI when rule parser found date time and destination`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "다른 장소", destinationConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            "행사 날짜: 2026-05-30\n행사 시간: 오후 3시\n장소: 향상교회",
            "2026-01-01",
            60,
        )

        assertEquals(0, aiParser.calls)
        assertEquals("향상교회 15:00", result.title)
        assertEquals(ScheduleParseSource.RULE, result.parseSource)
        assertFalse(result.aiAttempted)
        assertFalse(result.needsReview)
    }

    @Test
    fun `fills missing natural language fields with confident AI result`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    destinationName = "강남",
                    destinationConfidence = 0.95,
                    summary = "고등학교 친구와 회식",
                    summaryConfidence = 0.93,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            "20260530 강남 오후8시30분 고등학교친구 와 회식",
            "2026-01-01",
            60,
        )

        assertEquals(1, aiParser.calls)
        assertEquals("강남 20:30", result.title)
        assertEquals("2026-05-30", result.date)
        assertEquals("20:30", result.time)
        assertEquals("강남", result.destination?.name)
        assertEquals("일정 내용: 고등학교 친구와 회식", result.notes)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.aiAttempted)
        assertFalse(result.needsReview)
    }

    @Test
    fun `does not apply low confidence AI values`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    destinationName = "강남",
                    destinationConfidence = 0.4,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            "20260530 오후8시30분 친구와 회식",
            "2026-01-01",
            60,
        )

        assertNull(result.destination)
        assertEquals(ScheduleParseSource.RULE_FALLBACK, result.parseSource)
        assertTrue(result.needsReview)
        assertTrue("destination" in result.missingFields)
        assertTrue(result.warnings.any { "신뢰도가 낮아" in it })
    }

    @Test
    fun `keeps rule result when AI is unavailable`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                warning = "AI 분석에 실패해 규칙 분석 결과만 반환했습니다.",
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            "20260530 오후8시30분 친구와 회식",
            "2026-01-01",
            60,
        )

        assertEquals("2026-05-30", result.date)
        assertEquals("20:30", result.time)
        assertEquals(ScheduleParseSource.RULE_FALLBACK, result.parseSource)
        assertTrue(result.needsReview)
        assertTrue(result.warnings.any { "AI 분석에 실패" in it })
    }

    @Test
    fun `masks phone numbers and email before sending text to AI`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    destinationName = "강남",
                    destinationConfidence = 0.9,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        service.parse(
            "20260530 오후8시30분 강남 연락처 010-1234-5678 test@example.com",
            "2026-01-01",
            60,
        )

        assertTrue("[PHONE]" in aiParser.lastText.orEmpty())
        assertTrue("[EMAIL]" in aiParser.lastText.orEmpty())
        assertFalse("010-1234-5678" in aiParser.lastText.orEmpty())
        assertFalse("test@example.com" in aiParser.lastText.orEmpty())
    }

    /**
     * 실제 외부 API 없이 호출 횟수와 전달된 텍스트를 검증하기 위한 테스트 대역이다.
     */
    private class RecordingAiParser(
        private val outcome: ScheduleAiParseOutcome,
    ) : ScheduleAiParser {
        var calls: Int = 0
        var lastText: String? = null

        override fun parse(text: String, referenceDate: String): ScheduleAiParseOutcome {
            calls += 1
            lastText = text
            return outcome
        }
    }
}
