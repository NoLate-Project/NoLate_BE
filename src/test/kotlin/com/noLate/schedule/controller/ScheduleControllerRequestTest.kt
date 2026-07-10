package com.noLate.schedule.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScheduleControllerRequestTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `deserializes voice transcript parse request`() {
        // 컨트롤러 메서드가 호출되기 전에 Spring의 HTTP message converter가 요청 JSON을 DTO로 바꾼다.
        // 따라서 실제 앱이 보내는 inputType 문자열을 그대로 사용해, enum 계약 불일치로 400 응답이
        // 다시 발생하지 않는지 HTTP 경계와 같은 Jackson 역직렬화 단계에서 검증한다.
        val json = """
            {
              "text": "수요일 저녁 7시 강남역에서 판교 네이버까지",
              "inputType": "VOICE_TRANSCRIPT",
              "referenceDate": "2026-07-10",
              "defaultDurationMinutes": 60
            }
        """.trimIndent()

        val request = objectMapper.readValue<ParseScheduleTextRequest>(json)

        assertEquals("수요일 저녁 7시 강남역에서 판교 네이버까지", request.text)
        assertEquals(ScheduleParseInputType.VOICE_TRANSCRIPT, request.inputType)
        assertEquals("2026-07-10", request.referenceDate)
        assertEquals(60, request.defaultDurationMinutes)
    }
}
