package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.application.ScheduleAiParseOutcome
import com.noLate.schedule.application.ScheduleAiParseResult
import com.noLate.schedule.application.ScheduleAiParser
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleParseInputType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScheduleSharedInputReliabilityTest {
    private val rules = ScheduleTextParserService()
    private val service = ScheduleHybridParserService(rules, object : ScheduleAiParser {
        override fun parse(text: String, referenceDate: String) = ScheduleAiParseOutcome(attempted = false)
    })

    @Test
    fun `written meridiem is preserved across common shared formats`() {
        val cases = listOf(
            "오후 5시" to "17:00", "오후5:30" to "17:30",
            "오후 12시" to "12:00", "오전 12시" to "00:00",
            "밤 12시" to "00:00", "저녁 7시" to "19:00",
            "17:30" to "17:30", "5:30 PM" to "17:30",
            "PM 5:30" to "17:30", "12:00 AM" to "00:00",
            "(오후) 5시" to "17:00", "5시 (오후)" to "17:00",
        )
        for ((time, expected) in cases) {
            val result = service.parse("날짜: 26.11.28\n시간: $time\n장소: 테스트웨딩", ScheduleParseInputType.SHARE_TEXT, "2026-11-26", 60)
            assertEquals(expected, result.time, time)
            assertFalse(result.needsReview, "$time: ${result.warnings}")
        }
    }

    @Test
    fun `AI place supplementation preserves an explicit PM end time`() {
        val aiService = ScheduleHybridParserService(rules, object : ScheduleAiParser {
            override fun parse(text: String, referenceDate: String) = ScheduleAiParseOutcome(
                attempted = true,
                result = ScheduleAiParseResult(destinationName = "테스트웨딩", destinationConfidence = 0.95),
            )
        })
        val result = aiService.parse("날짜: 26.11.28\n시간: 5:00 PM~6:00 PM", ScheduleParseInputType.SHARE_TEXT, "2026-11-26", 60)
        assertEquals("17:00", result.time)
        assertEquals("2026-11-28T09:00:00Z", result.endAt)
        assertTrue(result.hasExplicitEndTime)
    }

    @Test
    fun `conflicting explicit dates and mismatching free form weekdays require review`() {
        for (text in listOf(
            "날짜: 2026-11-28\n다른 날짜: 2026-11-29\n시간: 오후 5시\n장소: 테스트웨딩",
            "2026-11-28 금요일 오후 5시 테스트웨딩",
        )) {
            val result = service.parse(text, ScheduleParseInputType.SHARE_TEXT, "2026-11-26", 60)
            assertTrue(result.needsReview, "$text: ${result.warnings}")
        }
    }

    @Test
    fun `missing or ambiguous clocks are never considered ready to save`() {
        for (time in listOf("", "5시", "25:00", "오후 5시 또는 오후 6시")) {
            val result = service.parse("날짜: 26.11.28\n시간: $time\n장소: 테스트웨딩", ScheduleParseInputType.SHARE_TEXT, "2026-11-26", 60)
            assertTrue(result.needsReview, "$time: ${result.warnings}")
        }
    }

    @Test
    fun `parsed afternoon and full memo survive schedule entity and API DTO roundtrip`() {
        val text = "예약자: 테스트가\n전화번호: 010 0000 0001\n이메일: test@example.com\n촬영종류: 메인스냅,DVD1캠\n날짜: 26.11.28\n시간: 오후 5시\n장소: 테스트웨딩\n안내: 배터리 챙기기"
        val parsed = service.parse(text, ScheduleParseInputType.SHARE_TEXT, "2026-11-26", 60)
        val entity = ScheduleDto(
            title = requireNotNull(parsed.title), startAt = requireNotNull(parsed.startAt),
            hasEndTime = false, notes = parsed.notes,
            category = ScheduleCategoryDto(id = "1", title = "일정", color = "#246BFE"),
        ).toEntity(1L)
        val saved = entity.toDto(jacksonObjectMapper())
        assertEquals("2026-11-28T08:00:00Z", saved.startAt)
        assertEquals(saved.startAt, saved.endAt)
        assertEquals(false, saved.hasEndTime)
        assertEquals(text, saved.notes)
    }
}
