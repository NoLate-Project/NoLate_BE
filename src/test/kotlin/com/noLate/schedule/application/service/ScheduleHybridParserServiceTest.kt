package com.noLate.schedule.application.service

import com.noLate.schedule.application.ScheduleAiParseOutcome
import com.noLate.schedule.application.ScheduleAiParseResult
import com.noLate.schedule.application.ScheduleAiParser
import com.noLate.schedule.application.ScheduleTranscriptCorrectionOutcome
import com.noLate.schedule.application.ScheduleTranscriptCorrectionResult
import com.noLate.schedule.domain.ScheduleParseConfidenceLevel
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.ScheduleParseSource
import com.noLate.schedule.domain.ScheduleRecognitionAlternative
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
    fun `removes exhibition purpose from voice destination`() {
        val service = ScheduleHybridParserService(
            ruleParser,
            RecordingAiParser(ScheduleAiParseOutcome(attempted = false)),
        )

        val result = service.parse(
            text = "8월 24일 오후 2시 코엑스 전시",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-08-01",
            defaultDurationMinutes = 60,
            recognitionConfidence = 0.95,
        )

        assertEquals("2026-08-24", result.date)
        assertEquals("14:00", result.time)
        assertEquals("코엑스", result.destination?.name)
    }

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
        assertEquals("15:00 향상교회", result.title)
        assertEquals(ScheduleParseSource.RULE, result.parseSource)
        assertFalse(result.aiAttempted)
        assertFalse(result.needsReview)
    }

    @Test
    fun `does not need AI for a natural shared drinking plan`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "잘못된 장소", destinationConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "금요일 7시 강남역 술약속",
            inputType = ScheduleParseInputType.SHARE_TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(0, aiParser.calls)
        assertEquals("강남역 술약속", result.title)
        assertEquals("19:00", result.time)
        assertEquals("강남역", result.destination?.name)
        assertEquals(ScheduleParseSource.RULE, result.parseSource)
        assertTrue(result.needsReview)
    }

    @Test
    fun `does not call AI when purpose precedes place in a pasted quick plan`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "잘못된 장소", destinationConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "금요일 7시 술약속 신촌역",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(0, aiParser.calls)
        assertEquals("신촌역", result.destination?.name)
        assertEquals("신촌역 술약속", result.title)
        assertEquals(ScheduleParseSource.RULE, result.parseSource)
    }

    @Test
    fun `keeps explicit end evidence when AI only fills a missing destination`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    destinationName = "강남역",
                    destinationConfidence = 0.99,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "금요일 오후 3시부터 5시까지 회의",
            inputType = ScheduleParseInputType.TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(1, aiParser.calls)
        assertEquals("2026-07-17T06:00:00Z", result.startAt)
        assertEquals("2026-07-17T08:00:00Z", result.endAt)
        assertTrue(result.hasExplicitEndTime)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
    }

    @Test
    fun `keeps corrected quick input in review even when every field exists`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(date = "2026-07-17", dateConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "금요일 아니 토요일 7시 아니 8시 신촌역 술약속",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(0, aiParser.calls)
        assertEquals("2026-07-18", result.date)
        assertEquals("20:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertTrue(result.needsReview)
    }

    @Test
    fun `lets confident AI override canceled date and time in unnormalized input`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    date = "2026-07-18",
                    dateConfidence = 0.99,
                    // 모델이 정정된 시각의 시(hour)는 찾았지만 술약속의 오후 문맥을 놓친 상황도
                    // 애플리케이션 병합 단계에서 20:00으로 일관되게 보정해야 한다.
                    time = "08:00",
                    timeConfidence = 0.99,
                    destinationName = "신촌역",
                    destinationConfidence = 0.99,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        // 범용 TEXT에는 빠른 입력 정규화를 적용하지 않는다. 이 경로에서도 `아니` 앞의 첫 후보를
        // 규칙 우선 정책이 고정하지 않고, Groq가 찾은 정정 이후 값을 반영해야 한다.
        val result = service.parse(
            text = "금요일 아니 토요일 7시 아니 8시 신촌역 술약속",
            inputType = ScheduleParseInputType.TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(1, aiParser.calls)
        assertEquals("2026-07-18", result.date)
        assertEquals("20:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.needsReview)
    }

    @Test
    fun `moves AI only location from origin to destination without route evidence`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    originName = "신촌역",
                    originConfidence = 0.95,
                    summary = "친구와 만남",
                    summaryConfidence = 0.9,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "2026-07-17 오후 7시 친구와 만남",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(1, aiParser.calls)
        assertNull(result.origin)
        assertEquals("신촌역", result.destination?.name)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
    }

    @Test
    fun `requires review when AI fills an hour without meridiem`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    date = "2026-07-18",
                    dateConfidence = 0.98,
                    time = "08:00",
                    timeConfidence = 0.9,
                    destinationName = "강남 용용선생",
                    destinationConfidence = 0.98,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "토욜 여덟시 강남 용용선생",
            inputType = ScheduleParseInputType.TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals(1, aiParser.calls)
        assertEquals("08:00", result.time)
        assertEquals("강남 용용선생", result.destination?.name)
        assertTrue(result.needsReview)
        assertTrue(result.warnings.any { "오전·오후가 없어 AI가" in it })
    }

    @Test
    fun `requires review without AI when complete OCR fields contain conflicting weekdays`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "다른 장소", destinationConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "금요일 토요일 오후 7시 강남역 >> 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals(0, aiParser.calls)
        assertEquals(ScheduleParseSource.RULE, result.parseSource)
        assertTrue(result.needsReview)
        assertTrue(result.warnings.any { "서로 다른 요일" in it })
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
        assertEquals("20:30 강남", result.title)
        assertEquals("2026-05-30", result.date)
        assertEquals("20:30", result.time)
        assertEquals("강남", result.destination?.name)
        assertEquals("일정 내용: 고등학교 친구와 회식", result.notes)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.aiAttempted)
        // 모델 자기평가만으로 채운 필드는 현장 보정 데이터가 쌓이기 전까지 90% 이상으로
        // 자동 확정하지 않는다. 값은 채우되 사용자가 한 번 확인하게 하는 것이 현재 계약이다.
        assertTrue(result.needsReview)
        assertEquals(ScheduleParseConfidenceLevel.MEDIUM, result.confidence?.level)
    }

    @Test
    fun `fills missing origin with confident AI result`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(
                    originName = "강남역",
                    originConfidence = 0.95,
                    destinationName = "판교 네이버",
                    destinationConfidence = 0.95,
                    summary = "이동",
                    summaryConfidence = 0.9,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            "20260701 7시 이동",
            "2026-07-01",
            60,
        )

        assertEquals(1, aiParser.calls)
        assertEquals("강남역", result.origin?.name)
        assertEquals("판교 네이버", result.destination?.name)
        assertEquals("07:00 판교 네이버", result.title)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.needsReview)
        assertTrue(result.warnings.any { "오전·오후가 없어" in it })
        assertFalse("origin" in result.missingFields)
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
    fun `uses AI to fill a missing field in a voice transcript`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "AI가 추론한 장소", destinationConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        // 원본 음성은 서버에 올리지 않고 개인정보가 마스킹된 전사문만 AI에 전달한다.
        // 규칙으로 찾지 못한 목적지는 신뢰도 기준을 통과한 결과로 보완한다.
        val result = service.parse(
            text = "다음 수요일 오후 7시 약속 등록해줘",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals(1, aiParser.calls)
        assertTrue(result.aiAttempted)
        assertEquals("AI가 추론한 장소", result.destination?.name)
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `selects an exact supplied voice candidate before rule parsing`() {
        val aiParser = RecordingAiParser(
            outcome = ScheduleAiParseOutcome(attempted = true),
            correctionOutcome = ScheduleTranscriptCorrectionOutcome(
                attempted = true,
                result = ScheduleTranscriptCorrectionResult(
                    selectedIndex = 1,
                    confidence = 0.96,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "내일 강남역 회의",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
            recognitionConfidence = 0.71,
            recognitionAlternatives = listOf(
                ScheduleRecognitionAlternative(
                    text = "내일 오후 3시 강남역 회의",
                    confidence = 0.93,
                ),
            ),
        )

        assertEquals(1, aiParser.correctionCalls)
        assertEquals(0, aiParser.calls)
        assertEquals("2026-07-03", result.date)
        assertEquals("15:00", result.time)
        assertEquals("강남역", result.destination?.name)
        assertEquals(ScheduleParseSource.AI_ASSISTED, result.parseSource)
        assertTrue(result.aiAttempted)
        assertTrue(result.needsReview)
        assertTrue(result.warnings.any { "다른 음성 인식 후보" in it })
    }

    @Test
    fun `keeps original voice transcript when candidate selection is uncertain`() {
        val aiParser = RecordingAiParser(
            outcome = ScheduleAiParseOutcome(attempted = true),
            correctionOutcome = ScheduleTranscriptCorrectionOutcome(
                attempted = true,
                result = ScheduleTranscriptCorrectionResult(
                    selectedIndex = 1,
                    confidence = 0.64,
                ),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "내일 강남역 회의",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
            recognitionAlternatives = listOf(
                ScheduleRecognitionAlternative("내일 오후 3시 강남역 회의", 0.93),
            ),
        )

        assertEquals(1, aiParser.correctionCalls)
        // 원문에는 시간이 없으므로 기존 음성 정책대로 일정 필드 AI 추론은 호출하지 않는다.
        assertEquals(0, aiParser.calls)
        assertNull(result.time)
        assertTrue("time" in result.missingFields)
        assertEquals(ScheduleParseSource.RULE_FALLBACK, result.parseSource)
        assertTrue(result.aiAttempted)
        assertTrue(result.warnings.any { "불확실해 첫 번째 인식 결과" in it })
    }

    @Test
    fun `masks every voice candidate before AI selection`() {
        val aiParser = RecordingAiParser(
            outcome = ScheduleAiParseOutcome(attempted = true),
            correctionOutcome = ScheduleTranscriptCorrectionOutcome(
                attempted = true,
                result = ScheduleTranscriptCorrectionResult(selectedIndex = 0, confidence = 0.95),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        service.parse(
            text = "내일 오후 3시 강남역 010-1234-5678 회의",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
            recognitionAlternatives = listOf(
                ScheduleRecognitionAlternative(
                    "내일 오후 3시 강남역 test@example.com 회의",
                    0.81,
                ),
            ),
        )

        val sentTexts = aiParser.lastCorrectionCandidates.map { it.text }
        assertTrue(sentTexts.any { "[PHONE]" in it })
        assertTrue(sentTexts.any { "[EMAIL]" in it })
        assertTrue(sentTexts.none { "010-1234-5678" in it })
        assertTrue(sentTexts.none { "test@example.com" in it })
    }

    @Test
    fun `does not attempt transcript selection outside voice input`() {
        val aiParser = RecordingAiParser(
            outcome = ScheduleAiParseOutcome(attempted = true),
            correctionOutcome = ScheduleTranscriptCorrectionOutcome(
                attempted = true,
                result = ScheduleTranscriptCorrectionResult(selectedIndex = 1, confidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        service.parse(
            text = "내일 오후 3시 강남역 회의",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
            recognitionAlternatives = listOf(
                ScheduleRecognitionAlternative("내일 오후 5시 서울역 회의", 1.0),
            ),
        )

        assertEquals(0, aiParser.correctionCalls)
    }

    @Test
    fun `low confidence media recognition is always returned for review`() {
        val aiParser = RecordingAiParser(ScheduleAiParseOutcome(attempted = true))
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "2026년 7월 24일 오후 7시 강남역 회의",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-22",
            defaultDurationMinutes = 60,
            recognitionConfidence = 0.52,
        )

        assertEquals(1, aiParser.calls)
        assertTrue(result.needsReview)
        assertTrue(result.warnings.any { "인식 신뢰도가 낮아" in it })
    }

    @Test
    fun `zero voice confidence means score unavailable and does not poison rule confidence`() {
        val aiParser = RecordingAiParser(ScheduleAiParseOutcome(attempted = true))
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "2026년 8월 4일 오후 9시 강남역 회의",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-08-04",
            defaultDurationMinutes = 60,
            recognitionConfidence = 0.0,
        )

        assertEquals(0, aiParser.calls)
        assertNull(result.confidence?.recognition)
        assertTrue((result.confidence?.overall ?: 0.0) >= 0.90)
        assertFalse(result.needsReview)
        assertTrue(result.warnings.none { "인식 신뢰도" in it })
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

    @Test
    fun `does not call AI fallback for one shot voice transcript`() {
        val aiParser = RecordingAiParser(
            ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "강남역", destinationConfidence = 1.0),
            ),
        )
        val service = ScheduleHybridParserService(ruleParser, aiParser)

        val result = service.parse(
            text = "내일 미팅 추가해줘",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
        )

        assertEquals(0, aiParser.calls)
        assertEquals("2026-07-03", result.date)
        assertEquals(ScheduleParseSource.RULE_FALLBACK, result.parseSource)
        assertFalse(result.aiAttempted)
        assertTrue(result.needsReview)
        assertTrue("time" in result.missingFields)
        assertTrue("destination" in result.missingFields)
    }

    /**
     * 실제 외부 API 없이 호출 횟수와 전달된 텍스트를 검증하기 위한 테스트 대역이다.
     */
    private class RecordingAiParser(
        private val outcome: ScheduleAiParseOutcome,
        private val correctionOutcome: ScheduleTranscriptCorrectionOutcome =
            ScheduleTranscriptCorrectionOutcome(attempted = false),
    ) : ScheduleAiParser {
        var calls: Int = 0
        var lastText: String? = null
        var correctionCalls: Int = 0
        var lastCorrectionCandidates: List<ScheduleRecognitionAlternative> = emptyList()

        override fun parse(text: String, referenceDate: String): ScheduleAiParseOutcome {
            calls += 1
            lastText = text
            return outcome
        }

        override fun correctTranscript(
            candidates: List<ScheduleRecognitionAlternative>,
            referenceDate: String,
        ): ScheduleTranscriptCorrectionOutcome {
            correctionCalls += 1
            lastCorrectionCandidates = candidates
            return correctionOutcome
        }
    }
}
