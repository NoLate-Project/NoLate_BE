package com.noLate.schedule.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.ScheduleImportProvider
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

    @Test
    fun `deserializes quick share route setup marker`() {
        val json = """
            {
              "title": "금요일 저녁 약속",
              "startAt": "2026-07-17T10:00:00Z",
              "category": { "id": "1", "title": "일정", "color": "#246BFE" },
              "notes": "공유한 원문",
              "routeSetupRequired": true,
              "notificationEnabled": false
            }
        """.trimIndent()

        val request = objectMapper.readValue<AddScheduleRequest>(json)

        assertEquals(true, request.routeSetupRequired)
        assertEquals(true, request.toDto().routeSetupRequired)
    }

    @Test
    fun `deserializes calendar import source identity`() {
        val json = """
            {
              "schedule": {
                "title": "Google 회의",
                "startAt": "2026-07-17T10:00:00Z",
                "endAt": "2026-07-17T11:00:00Z",
                "category": { "id": "1", "title": "일정", "color": "#246BFE" }
              },
              "source": {
                "provider": "GOOGLE",
                "calendarId": "google:primary",
                "eventId": "event-10",
                "occurrenceStartAt": "2026-07-17T10:00:00Z"
              }
            }
        """.trimIndent()

        val request = objectMapper.readValue<ImportCalendarScheduleRequest>(json)

        assertEquals("Google 회의", request.schedule.title)
        assertEquals(ScheduleImportProvider.GOOGLE, request.source.provider)
        assertEquals("event-10", request.source.eventId)
        assertEquals("2026-07-17T10:00:00Z", request.source.occurrenceStartAt)
    }
}
