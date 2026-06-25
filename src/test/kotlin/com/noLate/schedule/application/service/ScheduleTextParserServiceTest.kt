package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.schedule.domain.ScheduleOriginSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 실제로 수집되는 예약 양식과 축약형을 예제로 고정해 규칙 파서의 호환성을 검증한다.
 */
class ScheduleTextParserServiceTest {
    private val parser = ScheduleTextParserService()

    @Test
    fun `parses reservation text when year and date are on separate lines`() {
        val text = """
            ⭐️메인촬영⭐️

            1. 예약자 성함 : 김예은
            2. 전화번호 : 01076253013
            6. 촬영종류(메인스냅, 서브스냅, DVD1캠) : 웨딩본식 메인스냅
            7. 현금영수증번호(핸폰/사업자) : 01076253013
            8. 본식(촬영)년도 : 2026년
            9. 본식(촬영)날짜 : 5월30일
            10. 본식(촬영)요일 : 토요일
            11. 본식(시작)시간 : 12시
            12. 웨딩지역(시, 구, 동) : 용인시 기흥구 상하동
            13. 결혼식장 이름 : 향상교회
        """.trimIndent()

        val result = parser.parse(text, "2026-01-01", 60)

        assertEquals("향상교회 12:00", result.title)
        assertEquals(
            "예약자: 김예은\n촬영 종류: 웨딩본식 메인스냅",
            result.notes,
        )
        assertEquals("2026-05-30", result.date)
        assertEquals("12:00", result.time)
        assertEquals("2026-05-30T03:00:00Z", result.startAt)
        assertEquals("2026-05-30T04:00:00Z", result.endAt)
        assertEquals("향상교회", result.destination?.name)
        assertEquals("용인시 기흥구 상하동", result.destination?.address)
        assertEquals(ScheduleOriginSource.REQUIRED, result.originSource)
        assertTrue(result.originRequired)
        assertTrue("origin" in result.missingFields)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `parses wedding assignment message with spaced labels`() {
        val text = """
            화려한.(M)이정우/(s)백진욱.인천그랜드오스티엄.김혜림
            더화려한날엔 / 서브

            촬영은 원판 촬영까지입니다.

            먼저 받으신 일정 및 (★연회,폐백 추가 등) 내용이나 포지션이 다를 경우 바로 말씀해 주시고, 주말 및 공휴일은 작가님께 연락 주세요. 일정 확인하시면 카톡 텍스트로 꼭! 남겨주시기 바랍니다~

            예약자 성함 : 김혜림
            전화 번호 : 010-3955-3422
            이메일 주소 : wtj1221@naver.com
            배우자 성함 : 임완석
            전화 번호 : 010-8437-2023
            행사 연도 : 2026년
            행사 날짜 : 5월 16일
            행사 요일 : 토요일
            행사 시간 : 오후 3시 30분
            행사 지역 : 인천
            장  소  명 : 그랜드 오스티엄 웨딩홀 3층 블리스홀

            주례 x   예물 x   화동 x   축무 1   축사 1   덕담 x
        """.trimIndent()

        val result = parser.parse(text, "2026-01-01", 60)

        assertEquals("그랜드 오스티엄 웨딩홀 3층 블리스홀 15:30", result.title)
        assertEquals(
            "예약자: 김혜림\n작가 배정: (m)이정우 (s)백진욱",
            result.notes,
        )
        assertEquals("2026-05-16", result.date)
        assertEquals("15:30", result.time)
        assertEquals("2026-05-16T06:30:00Z", result.startAt)
        assertEquals("2026-05-16T07:30:00Z", result.endAt)
        assertEquals("그랜드 오스티엄 웨딩홀 3층 블리스홀", result.destination?.name)
        assertEquals("인천", result.destination?.address)
        assertEquals(ScheduleOriginSource.REQUIRED, result.originSource)
        assertTrue(result.originRequired)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `parses pasted wedding reservation form with venue name label`() {
        val text = """
            ★메인촬영★

            1. 배우자 성함 :  정원우
            2. 전화번호 : 010-2866-9137
            3. 이메일 : rhwrka54@naver.com
            4. 예약자성함 : 고은샘
            5. 전화번호 : 010-5124-6609

            돌스냅의 경우, 아기이름 :
            6. 촬영종류(메인스냅, 서브스냅, DVD1캠, DVD2캠, 웨딩야촬, 스튜디오, 돌스냅) : 메인스냅, 서브스냅, DVD2캠
            7.현금영수증번호(핸폰/사업자) : 핸폰 010-2866-9137
            8. 본식(촬영)년도 : 2026년
            9. 본식(촬영)날짜 : 6월 13일
            10. 본식(촬영)요일 : 토요일
            11. 본식(촬영)시간 : 14:30분
            12. 웨딩지역(시, 구, 동) : 경기도 수원 팔달두 권광로 178
            13. 웨딩(촬영)장소명 : 수원 더케이 웨딩컨벤션
            14. 본식메인업체명 :
            15. DVD업체명 :
            16. 만원의행복스냅 방문경로 : 인스타 광고

            축가(신랑)
            부케대신 닭날리기

            더시그너스 웨딩홀 -> 이름변경
        """.trimIndent()

        val result = parser.parse(text, "2026-01-01", 60)

        assertEquals("수원 더케이 웨딩컨벤션 14:30", result.title)
        assertEquals(
            "예약자: 고은샘\n촬영 종류: 메인스냅, 서브스냅, DVD2캠",
            result.notes,
        )
        assertEquals("2026-06-13", result.date)
        assertEquals("14:30", result.time)
        assertEquals("2026-06-13T05:30:00Z", result.startAt)
        assertEquals("2026-06-13T06:30:00Z", result.endAt)
        assertEquals("수원 더케이 웨딩컨벤션", result.destination?.name)
        assertEquals("경기도 수원 팔달두 권광로 178", result.destination?.address)
        assertTrue(result.originRequired)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `supports compact date and meridiem time formats`() {
        val result = parser.parse(
            text = "행사명: 가족 촬영\n일시: 2026-08-15 오후 2:30\n장소: 시민회관",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 90,
        )

        assertEquals("2026-08-15", result.date)
        assertEquals("14:30", result.time)
        assertEquals("2026-08-15T05:30:00Z", result.startAt)
        assertEquals("2026-08-15T07:00:00Z", result.endAt)
        assertEquals("시민회관 14:30", result.title)
        assertEquals("촬영 종류: 가족 촬영", result.notes)
        assertEquals("시민회관", result.destination?.name)
    }

    @Test
    fun `parses compact unlabeled reservation message`() {
        val result = parser.parse(
            text = "홍길동 20260630/14:30 인천빌라드컨벤션 예물O 화동O",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("인천빌라드컨벤션 14:30", result.title)
        assertEquals("예약자: 홍길동", result.notes)
        assertEquals("2026-06-30", result.date)
        assertEquals("14:30", result.time)
        assertEquals("인천빌라드컨벤션", result.destination?.name)
        assertTrue(result.originRequired)
    }

    @Test
    fun `keeps spaces in venue name in compact message`() {
        val result = parser.parse(
            text = "김혜림 2026-05-16 15:30 그랜드 오스티엄 웨딩홀 예물X",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("그랜드 오스티엄 웨딩홀 15:30", result.title)
        assertEquals("예약자: 김혜림", result.notes)
        assertEquals("그랜드 오스티엄 웨딩홀", result.destination?.name)
    }

    @Test
    fun `parses dot separated assignment shorthand`() {
        val result = parser.parse(
            text = "20260530/1030.베웨.(m)백진욱(s)강연지(d)이정우(i)이다희.엘마리노앳인천.박지낭.m",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("엘마리노앳인천 10:30", result.title)
        assertEquals(
            """
                예약자: 박지낭
                작가 배정: (m)백진욱 (s)강연지 (d)이정우 (i)이다희
                업체/구분: 베웨
                포지션: m
            """.trimIndent(),
            result.notes,
        )
        assertEquals("2026-05-30", result.date)
        assertEquals("10:30", result.time)
        assertEquals("2026-05-30T01:30:00Z", result.startAt)
        assertEquals("엘마리노앳인천", result.destination?.name)
        assertTrue(result.originRequired)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `does not infer date from legacy month day assignment shorthand`() {
        val result = parser.parse(
            text = "1030.베웨.(m)백진욱(s)강연지(d)이정우(i)이다희.엘마리노앳인천.박지낭.m",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertNull(result.date)
        assertNull(result.time)
        assertNull(result.title)
        assertNull(result.destination)
    }

    @Test
    fun `reports current parsing result for natural language schedule`() {
        val result = parser.parse(
            text = "20260530 강남 오후8시30분 고등학교친구 와 술먹기",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-05-30", result.date)
        assertEquals("20:30", result.time)
        assertNull(result.destination)
        assertEquals("20:30", result.title)
        assertNull(result.notes)
    }

    @Test
    fun `uses reference year and warns when source has no year`() {
        val result = parser.parse(
            text = "촬영 날짜: 7월 2일\n촬영 시간: 오전 9시",
            referenceDate = "2027-02-10",
            defaultDurationMinutes = null,
        )

        assertEquals("2027-07-02", result.date)
        assertEquals("09:00", result.time)
        assertTrue("연도가 없어 2027년으로 추정했습니다." in result.warnings)
    }

    @Test
    fun `does not interpret phone or receipt numbers as dates`() {
        val result = parser.parse(
            text = "예약자: 홍길동\n전화번호: 010-7625-3013\n현금영수증: 010-05-30",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = null,
        )

        assertNull(result.date)
        assertNull(result.time)
        assertTrue("date" in result.missingFields)
    }

    @Test
    fun `returns text origin when explicitly provided`() {
        val result = parser.parse(
            text = "일정명: 회의\n일시: 2026-06-10 10:30\n출발지: 집\n출발 주소: 서울시 강남구\n장소: 회사",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = null,
        )

        assertEquals("집", result.origin?.name)
        assertEquals("서울시 강남구", result.origin?.address)
        assertEquals(ScheduleOriginSource.TEXT, result.originSource)
        assertFalse(result.originRequired)
        assertEquals("회사", result.destination?.name)
        assertNull(result.destination?.address)
    }

    @Test
    fun `warns when written weekday differs from actual date`() {
        val result = parser.parse(
            text = "일정 날짜: 2026.05.30\n요일: 금요일",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = null,
        )

        assertTrue("입력된 금요일과 실제 토요일이 다릅니다." in result.warnings)
    }

    @Test
    fun `parses weekday relative to reference date`() {
        val result = parser.parse(
            text = "금요일 오후 7시 강남역에서 친구와 저녁",
            referenceDate = "2026-06-10",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-06-12", result.date)
        assertEquals("19:00", result.time)
        assertEquals("2026-06-12T10:00:00Z", result.startAt)
    }

    @Test
    fun `rejects blank text and invalid duration`() {
        assertThrows<BusinessException> {
            parser.parse(" ", "2026-01-01", null)
        }
        assertThrows<BusinessException> {
            parser.parse("일시: 2026-05-30 12:00", "2026-01-01", 0)
        }
    }
}
