package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.schedule.domain.ScheduleOriginSource
import com.noLate.schedule.domain.ScheduleParseInputType
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
    fun `marks an explicit time range and preserves its real end time`() {
        val result = parser.parse(
            text = "금요일 오후 3시부터 5시까지 강남역 회의",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17T06:00:00Z", result.startAt)
        assertEquals("2026-07-17T08:00:00Z", result.endAt)
        assertTrue(result.hasExplicitEndTime)
        assertFalse(result.warnings.any { "서로 다른 시간" in it })
    }

    @Test
    fun `keeps a generated default end distinguishable from an explicit end`() {
        val result = parser.parse(
            text = "금요일 오후 3시 강남역 회의",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17T06:00:00Z", result.startAt)
        assertEquals("2026-07-17T07:00:00Z", result.endAt)
        assertFalse(result.hasExplicitEndTime)
    }

    @Test
    fun `resolves an omitted end meridiem to the closest future time`() {
        val result = parser.parse(
            text = "금요일 오후 11시부터 1시까지 강남역 회의",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17T14:00:00Z", result.startAt)
        assertEquals("2026-07-17T16:00:00Z", result.endAt)
        assertTrue(result.hasExplicitEndTime)
    }

    @Test
    fun `parses a labeled end time as explicit`() {
        val result = parser.parse(
            text = "행사 날짜: 2026-07-17\n시작 시간: 오후 3시\n종료 시간: 오후 5시\n장소: 강남역",
            inputType = ScheduleParseInputType.TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17T06:00:00Z", result.startAt)
        assertEquals("2026-07-17T08:00:00Z", result.endAt)
        assertTrue(result.hasExplicitEndTime)
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
    fun `parses one shot voice transcript without asking follow up questions`() {
        val result = parser.parse(
            text = "내일 오후 세 시 강남역에서 민수랑 미팅 추가해줘",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
        )

        assertEquals("민수랑 미팅", result.title)
        assertEquals("2026-07-03", result.date)
        assertEquals("15:00", result.time)
        assertEquals("2026-07-03T06:00:00Z", result.startAt)
        assertEquals("강남역", result.destination?.name)
        assertTrue(result.originRequired)
    }

    @Test
    fun `parses voice transcript with next week and Korean number time`() {
        val result = parser.parse(
            text = "다음 주 월요일 오전 열 시 병원 진료",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
        )

        assertEquals("병원 진료", result.title)
        assertEquals("2026-07-06", result.date)
        assertEquals("10:00", result.time)
        assertEquals("병원", result.destination?.name)
    }

    @Test
    fun `parses OCR text when colon separators are missing`() {
        val result = parser.parse(
            text = """
                예약일 7월 12일
                예약시간 오후 3시 반
                장소 강남역
            """.trimIndent(),
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-02",
            defaultDurationMinutes = 60,
        )

        assertEquals("강남역 15:30", result.title)
        assertEquals("2026-07-12", result.date)
        assertEquals("15:30", result.time)
        assertEquals("강남역", result.destination?.name)
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
    fun `parses shared drinking plan into evening destination and useful title`() {
        val result = parser.parse(
            text = "금요일 7시 강남역 술약속",
            inputType = ScheduleParseInputType.SHARE_TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("2026-07-17T10:00:00Z", result.startAt)
        assertEquals("강남역", result.destination?.name)
        assertEquals("강남역 술약속", result.title)
        assertNull(result.origin)
        assertTrue(result.originRequired)
        assertTrue(result.warnings.any { "오후 7시로 추정" in it })
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `parses pasted drinking plan from quick conversation input`() {
        // 빠른 일정의 텍스트 모드는 FE에서 TEXT가 아니라 CONVERSATION으로 전달된다.
        // 공유 입력 테스트만 있으면 실제 붙여넣기 화면에서 장소가 다시 누락될 수 있다.
        val result = parser.parse(
            text = "금요일 7시 강남역 술약속",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("강남역", result.destination?.name)
        assertEquals("강남역 술약속", result.title)
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `parses destination when purpose precedes place in pasted input`() {
        val result = parser.parse(
            text = "금요일 7시 술약속 신촌역",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertEquals("신촌역 술약속", result.title)
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `parses shared sentence when time precedes weekday and purpose precedes place`() {
        // 공유 확장은 빠른 일정 입력창과 같은 파서를 쓰지만 inputType은 SHARE_TEXT다.
        // 실제 공유 어순을 고정해 제목이 시각만 남거나 목적지가 다시 비는 회귀를 막는다.
        val result = parser.parse(
            text = "7시 금요일 술약속 신촌역",
            inputType = ScheduleParseInputType.SHARE_TEXT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertEquals("신촌역 술약속", result.title)
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `parses schedule words even when quick input has no spaces`() {
        val result = parser.parse(
            text = "금요일7시술약속신촌역",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertEquals("신촌역 술약속", result.title)
    }

    @Test
    fun `removes purpose particle before extracting destination`() {
        val result = parser.parse(
            text = "금요일 7시 술약속은 신촌역에서",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("신촌역", result.destination?.name)
        assertEquals("신촌역 술약속", result.title)
    }

    @Test
    fun `normalizes voice fillers and Korean hour words`() {
        val result = parser.parse(
            text = "금요일 저녁 일곱 시 어 그 신촌역에서 술 약속 잡아줘",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertEquals("19:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `uses corrected weekday and time after 아니`() {
        val result = parser.parse(
            text = "금요일 아니 토요일 7시 아니 8시 신촌역 술약속",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-18", result.date)
        assertEquals("20:00", result.time)
        assertEquals("신촌역", result.destination?.name)
        assertTrue(result.warnings.any { "정정 표현" in it && "확인이 필요" in it })
    }

    @Test
    fun `marks conflicting weekday and time candidates for review`() {
        val result = parser.parse(
            text = "금요일 7시 토요일 8시 신촌역",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertTrue(result.warnings.any { "서로 다른 요일" in it })
        assertTrue(result.warnings.any { "서로 다른 시간" in it })
        assertEquals("신촌역", result.destination?.name)
    }

    @Test
    fun `parses relative date without spaces`() {
        val result = parser.parse(
            text = "모레저녁7시 강남역",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-18", result.date)
        assertEquals("19:00", result.time)
        assertEquals("강남역", result.destination?.name)
    }

    @Test
    fun `keeps complete venue after abbreviated weekday`() {
        val result = parser.parse(
            text = "토욜 여덟시 강남 용용선생",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-18", result.date)
        assertEquals("08:00", result.time)
        assertEquals("강남 용용선생", result.destination?.name)
        assertTrue(result.warnings.any { "오전·오후가 없어" in it && "확인이 필요" in it })
    }

    @Test
    fun `parses tomorrow destination while leaving unspecified time empty`() {
        val result = parser.parse(
            text = "내일 퇴근하고 강남역에서 저녁 약속",
            inputType = ScheduleParseInputType.CONVERSATION,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-17", result.date)
        assertNull(result.time)
        assertEquals("강남역", result.destination?.name)
        assertTrue("time" in result.missingFields)
    }

    @Test
    fun `parses arrow route expression from handwriting OCR text`() {
        val result = parser.parse(
            text = "NoLate 손글씨 OCR QA #01\n수요일 7시 강남역 -> 판교 네이버",
            referenceDate = "2026-07-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-01", result.date)
        assertEquals("07:00", result.time)
        assertEquals("2026-06-30T22:00:00Z", result.startAt)
        assertEquals("강남역", result.origin?.name)
        assertEquals("판교 네이버", result.destination?.name)
        assertEquals("판교 네이버 07:00", result.title)
        assertEquals(ScheduleOriginSource.TEXT, result.originSource)
        assertFalse(result.originRequired)
        assertFalse("origin" in result.missingFields)
        assertFalse("destination" in result.missingFields)
    }

    @Test
    fun `parses evening time and trims memo word from arrow destination`() {
        val result = parser.parse(
            text = "7월 16일 저녁 7시 사당 -> 신촌 스터디",
            referenceDate = "2026-07-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-16", result.date)
        assertEquals("19:00", result.time)
        assertEquals("2026-07-16T10:00:00Z", result.startAt)
        assertEquals("사당", result.origin?.name)
        assertEquals("신촌", result.destination?.name)
        assertEquals("신촌 19:00", result.title)
        assertFalse(result.originRequired)
    }

    @Test
    fun `parses repeated chevrons produced by rotated handwriting OCR`() {
        // 첨부된 실제 손글씨 사진을 원래 방향으로 Vision OCR에 넣으면 화살표가 `>>`로
        // 추출된다. OCR 전용 입력에서도 이 표기를 이동 방향으로 유지하는지 검증한다.
        val result = parser.parse(
            text = "금분을 토요일 오후7시\n강남역 >> 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-11", result.date)
        assertEquals("19:00", result.time)
        assertEquals("강남역", result.origin?.name)
        assertEquals("내방역", result.destination?.name)
        assertFalse(result.originRequired)
    }

    @Test
    fun `parses single chevron produced after OCR image downsampling`() {
        val result = parser.parse(
            text = "금분을 토요일 오후 7시\n강남역 > 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals("19:00", result.time)
        assertEquals("강남역", result.origin?.name)
        assertEquals("내방역", result.destination?.name)
    }

    @Test
    fun `parses standalone three misrecognized from handwriting arrow`() {
        // 사진을 90도 보정하면 같은 화살표가 숫자 3으로 오인될 수 있다. 단독 3만 경로
        // 구분자로 취급하고 앞의 `7시` 숫자는 시간으로 남기는 회귀 테스트다.
        val result = parser.parse(
            text = "금분일 토요일 오후 7시 강남역 3 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-11", result.date)
        assertEquals("19:00", result.time)
        assertEquals("강남역", result.origin?.name)
        assertEquals("내방역", result.destination?.name)
        assertFalse(result.originRequired)
    }

    @Test
    fun `does not treat time digit three as an OCR route separator`() {
        val result = parser.parse(
            text = "토요일 오후 3시 강남역 회의",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals("15:00", result.time)
        assertNull(result.origin)
        assertTrue(result.originRequired)
    }

    @Test
    fun `warns when OCR reads crossed out and replacement weekdays together`() {
        val result = parser.parse(
            text = "금요일 토요일 오후 7시 강남역 >> 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertTrue(result.warnings.any { "서로 다른 요일" in it })
    }

    @Test
    fun `warns when OCR contains multiple dates or times`() {
        val result = parser.parse(
            text = "7월 18일 7월 19일 토요일 오후 7시 오후 8시 강남역 >> 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertTrue(result.warnings.any { "서로 다른 날짜" in it })
        assertTrue(result.warnings.any { "서로 다른 시간" in it })
    }

    @Test
    fun `warns when OCR date and weekday disagree`() {
        val result = parser.parse(
            text = "7월 19일 토요일 오후 7시 강남역 >> 내방역",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertTrue(result.warnings.any { "날짜와 요일이 일치하지 않아" in it })
    }

    @Test
    fun `parses Korean route expression without spaces`() {
        val result = parser.parse(
            text = "금요일 19:00 강남역에서 판교 네이버까지",
            referenceDate = "2026-07-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-03", result.date)
        assertEquals("19:00", result.time)
        assertEquals("2026-07-03T10:00:00Z", result.startAt)
        assertEquals("강남역", result.origin?.name)
        assertEquals("판교 네이버", result.destination?.name)
        assertFalse(result.originRequired)
    }

    @Test
    fun `parses one shot voice route and removes command suffix`() {
        // 실제 STT 문장처럼 이동 경로 뒤에 명령형 표현이 붙어도 출발지와 도착지에는
        // 장소 이름만 남아야 한다. 이 테스트는 음성 입력의 날짜·시간·경로를 한 번에 검증한다.
        val result = parser.parse(
            text = "수요일 저녁 7시 강남역에서 판교 네이버까지 일정 추가해줘",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-11",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-15", result.date)
        assertEquals("19:00", result.time)
        assertEquals("강남역", result.origin?.name)
        assertEquals("판교 네이버", result.destination?.name)
        assertEquals("판교 네이버 19:00", result.title)
        assertFalse(result.originRequired)
    }

    @Test
    fun `parses plain multi word destination from voice transcript`() {
        // 실제 STT는 "장소" 라벨이나 "까지" 조사를 붙이지 않고 날짜, 시간, 상호만 반환할 수 있다.
        // 일정 문맥을 제거한 뒤 남은 두 단어 상호 전체가 목적지로 유지되는지 검증한다.
        val result = parser.parse(
            text = "토요일 8시 강남 용용선생",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-18", result.date)
        assertEquals("08:00", result.time)
        assertEquals("강남 용용선생", result.destination?.name)
        assertEquals("강남 용용선생 08:00", result.title)
    }

    @Test
    fun `parses date time place companion and purpose from one shot voice transcript`() {
        // 사용자가 실제로 말한 문장을 그대로 고정한다. 날짜와 시각뿐 아니라 장소 조사 뒤의
        // 동행인은 메모로, 일정 목적은 제목으로 보존되어야 한다.
        val result = parser.parse(
            text = "8월 17일 6시30분 석촌호수에서 진욱이랑 데이트",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-18",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-08-17", result.date)
        assertEquals("18:30", result.time)
        assertEquals("석촌호수", result.destination?.name)
        assertEquals("석촌호수 데이트", result.title)
        assertEquals("동행: 진욱", result.notes)
    }

    @Test
    fun `normalizes common Korean speech transcription variations`() {
        // SFSpeechRecognizer는 OS 버전과 발화에 따라 숫자 서식, 띄어쓰기, 구두점이 달라진다.
        // 아래 문장들은 모두 같은 일정이며 규칙 파서가 AI 없이 같은 핵심 필드로 수렴해야 한다.
        val variants = listOf(
            "8월 17일 6시 30분에 석촌호수에서 진욱이랑 데이트",
            "팔월 십칠일 여섯시 삼십분 석촌호수에서 진욱이랑 데이트",
            "8월17일6시30분석촌호수에서진욱이랑데이트",
            "진욱이랑 8월 17일 6시 30분 석촌호수에서 데이트",
            "데이트 8월 17일 6시 30분 석촌호수에서 진욱이랑",
            "8월 17일 6시 30분 석촌호수에서, 진욱이랑 데이트",
            "8월 17일 월요일 6시 30분 석촌호수에서 진욱이랑 데이트",
            "8월 17일 6시30붐 석촌호수에서 진욱이랑 데이트",
            "8월 17일 6시 삼십 분 석촌호수에서 진욱이랑 데이트",
            "8월 십칠일 여섯 시 반 석촌호수에서 진욱이랑 데이트",
            "팔 월 십칠 일 저녁 여섯 시 반 석촌호수에서 진욱이랑 데이트",
            "진욱이랑 데이트 8월 17일 6시 30분 석촌호수에서",
            "8월 17일 6시 30분 진욱이랑 석촌호수 데이트",
        )

        variants.forEach { text ->
            val result = parser.parse(
                text = text,
                inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
                referenceDate = "2026-07-18",
                defaultDurationMinutes = 60,
            )

            assertEquals("2026-08-17", result.date, "date failed for: $text")
            assertEquals("18:30", result.time, "time failed for: $text")
            assertEquals("석촌호수", result.destination?.name, "destination failed for: $text")
        }
    }

    @Test
    fun `separates destinations from varied real world schedule purposes`() {
        // 장소명 뒤에 일정의 목적이 붙는 실제 빠른 입력을 폭넓게 고정한다. 파서는 `출국`,
        // `영화 관람`, `검진` 등을 장소 일부로 저장하지 않고 앞부분만 목적지로 반환해야 한다.
        data class StressCase(
            val text: String,
            val date: String,
            val time: String,
            val destination: String,
        )

        val cases = listOf(
            StressCase("오늘 오후 3시 서울대병원 진료", "2026-07-18", "15:00", "서울대병원"),
            StressCase("8월 1일 밤 11시 인천공항 출국", "2026-08-01", "23:00", "인천공항"),
            StressCase("금요일 6시 30분 잠실야구장 야구 관람", "2026-07-24", "18:30", "잠실야구장"),
            StressCase("토요일 정오 코엑스 점심", "2026-07-18", "12:00", "코엑스"),
            StressCase("일요일 자정 강남역 모임", "2026-07-19", "00:00", "강남역"),
            StressCase(
                "8월 10일 오후 4시 15분 수원 컨벤션센터 고객미팅",
                "2026-08-10",
                "16:15",
                "수원 컨벤션센터",
            ),
            StressCase("8월 11일 오전 7시 한강공원 러닝", "2026-08-11", "07:00", "한강공원"),
            StressCase("8월 12일 저녁 8시 롯데시네마 영화 관람", "2026-08-12", "20:00", "롯데시네마"),
            StressCase("8월 13일 오후 1시 학교 상담", "2026-08-13", "13:00", "학교"),
            StressCase("8월 14일 오전 9시 치과 검진", "2026-08-14", "09:00", "치과"),
            StressCase(
                "8월 15일 13:00 국립중앙박물관 전시 관람",
                "2026-08-15",
                "13:00",
                "국립중앙박물관",
            ),
            StressCase("8월 16일 오후 3시 아산병원 엄마 병문안", "2026-08-16", "15:00", "아산병원"),
            StressCase(
                "7월 20일 오전 10시 카카오 판교오피스 면접",
                "2026-07-20",
                "10:00",
                "카카오 판교오피스",
            ),
            StressCase("8월 20일 오후 2시 Zoom 프로젝트 회의", "2026-08-20", "14:00", "Zoom"),
            StressCase("다음 토요일 오전 11시 용산역 가족식사", "2026-07-25", "11:00", "용산역"),
            StressCase("낼 아침 7시 반 헬스장 운동", "2026-07-19", "07:30", "헬스장"),
            StressCase("8월 21일 오후 5시30분 김포공항 마중", "2026-08-21", "17:30", "김포공항"),
            StressCase("8월 22일 오전 10시 집 청소", "2026-08-22", "10:00", "집"),
            StressCase("8월 23일 저녁 6시 부모님과 명동교자 식사", "2026-08-23", "18:00", "명동교자"),
        )

        cases.forEach { case ->
            val result = parser.parse(
                text = case.text,
                inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
                referenceDate = "2026-07-18",
                defaultDurationMinutes = 60,
            )

            assertEquals(case.date, result.date, "date failed for: ${case.text}")
            assertEquals(case.time, result.time, "time failed for: ${case.text}")
            assertEquals(case.destination, result.destination?.name, "destination failed for: ${case.text}")
        }
    }

    @Test
    fun `keeps relationship phrase out of handwritten OCR destination`() {
        // 첨부 사진은 iOS에서 `오빠랑`으로 읽혔고 macOS Vision에서는 같은 획을 `오바람`으로
        // 읽었다. OCR 엔진별 두 결과 모두 동행 메모로 분리하고 장소에는 석촌호수만 남겨야 한다.
        val visionTranscripts = listOf(
            "8월 17일 진욱이 오빠랑 석촌호수에서 데이트 한다 6시에",
            "8월 17일 진욱이 오바람 석촌호수에서 데이트 한다 6시에",
        )

        visionTranscripts.forEach { text ->
            val result = parser.parse(
                text = text,
                inputType = ScheduleParseInputType.IMAGE_OCR,
                referenceDate = "2026-07-18",
                defaultDurationMinutes = 60,
            )

            assertEquals("2026-08-17", result.date, "date failed for: $text")
            assertEquals("18:00", result.time, "time failed for: $text")
            assertEquals("석촌호수", result.destination?.name, "destination failed for: $text")
            assertEquals("석촌호수 데이트", result.title, "title failed for: $text")
            assertEquals("동행: 진욱이 오빠", result.notes, "notes failed for: $text")
        }
    }

    @Test
    fun `parses transportation errands family events corrections and calendar boundaries`() {
        // 첫 번째 스트레스 묶음과 겹치지 않는 교통, 관공서, 가족 행사, 장소 정정, 연도 경계를
        // 고정한다. 각 문장은 AI를 사용하지 않는 음성 규칙 경로에서도 검색 가능한 장소명만 남겨야 한다.
        data class StressCase(
            val text: String,
            val date: String,
            val time: String,
            val destination: String,
        )

        val cases = listOf(
            StressCase(
                "9월 1일 오전 10시부터 11시 30분까지 삼성서울병원 건강검진",
                "2026-09-01",
                "10:00",
                "삼성서울병원",
            ),
            StressCase("9월 3일 18:40 광명역 KTX 탑승", "2026-09-03", "18:40", "광명역"),
            StressCase(
                "9월 4일 오전 6시 김포공항 제주도 여행 출발",
                "2026-09-04",
                "06:00",
                "김포공항",
            ),
            StressCase(
                "9월 5일 오후 1시 반려견이랑 올림픽공원 산책",
                "2026-09-05",
                "13:00",
                "올림픽공원",
            ),
            StressCase(
                "9월 6일 저녁 7시 장모님 모시고 한정식집 가족 모임",
                "2026-09-06",
                "19:00",
                "한정식집",
            ),
            StressCase("9월 7일 오후 4시 스타필드 하남 쇼핑", "2026-09-07", "16:00", "스타필드 하남"),
            StressCase(
                "9월 8일 오후 7시30분 잠실학생체육관 농구 경기 관람",
                "2026-09-08",
                "19:30",
                "잠실학생체육관",
            ),
            StressCase("9월 9일 오전 8시 어린이집 등원", "2026-09-09", "08:00", "어린이집"),
            StressCase("9월 11일 오전 11시 구청 여권 수령", "2026-09-11", "11:00", "구청"),
            StressCase("9월 12일 오후 3시 은행 대출 상담", "2026-09-12", "15:00", "은행"),
            StressCase("9월 13일 오전 9시 세무서 서류 제출", "2026-09-13", "09:00", "세무서"),
            StressCase(
                "9월 14일 12:30 회사 근처 김밥천국 점심 식사",
                "2026-09-14",
                "12:30",
                "회사 근처 김밥천국",
            ),
            StressCase("9월 15일 오후 6시 집들이 민수네 집", "2026-09-15", "18:00", "민수네 집"),
            StressCase("9월 16일 저녁 8시 생일파티 지수네", "2026-09-16", "20:00", "지수네"),
            StressCase("9월 17일 오후 2시 결혼식 더채플앳청담", "2026-09-17", "14:00", "더채플앳청담"),
            StressCase(
                "9월 18일 오전 10시 장례식 서울아산병원 장례식장",
                "2026-09-18",
                "10:00",
                "서울아산병원 장례식장",
            ),
            StressCase("9월 19일 오후 3시 돌잔치 롯데호텔", "2026-09-19", "15:00", "롯데호텔"),
            StressCase("9월 20일 오전 7시 북한산 등산 모임", "2026-09-20", "07:00", "북한산"),
            StressCase(
                "9월 22일 오후 7시 강남역 아니 신논현역에서 저녁",
                "2026-09-22",
                "19:00",
                "신논현역",
            ),
            StressCase(
                "9월 24일 오전 10시 병원 말고 회사에서 미팅",
                "2026-09-24",
                "10:00",
                "회사",
            ),
            StressCase("이번달 30일 오후 2시 주민센터 전입신고", "2026-07-30", "14:00", "주민센터"),
            StressCase("다음 달 1일 오전 9시 자동차검사소 차량검사", "2026-08-01", "09:00", "자동차검사소"),
            StressCase("1월 1일 오전 8시 해맞이공원 일출", "2027-01-01", "08:00", "해맞이공원"),
            StressCase("다음주 화요일 오후 4시 Teams 주간 회의", "2026-07-21", "16:00", "Teams"),
            StressCase(
                "9월 26일 오전 10시 국립현대미술관 전시 예매",
                "2026-09-26",
                "10:00",
                "국립현대미술관",
            ),
            StressCase("9월 29일 오후 4시 미용실 커트 예약", "2026-09-29", "16:00", "미용실"),
        )

        cases.forEach { case ->
            val result = parser.parse(
                text = case.text,
                inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
                referenceDate = "2026-07-18",
                defaultDurationMinutes = 60,
            )

            assertEquals(case.date, result.date, "date failed for: ${case.text}")
            assertEquals(case.time, result.time, "time failed for: ${case.text}")
            assertEquals(case.destination, result.destination?.name, "destination failed for: ${case.text}")
        }
    }

    @Test
    fun `parses plain multi word destination from OCR lines`() {
        // OCR이 요일·시간과 상호를 서로 다른 줄로 분리해도 장소 줄을 독립 후보로 읽어야 한다.
        val result = parser.parse(
            text = "토요일 8시\n강남 용용선생",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertEquals("2026-07-18", result.date)
        assertEquals("강남 용용선생", result.destination?.name)
    }

    @Test
    fun `does not treat person and purpose phrase as natural destination`() {
        val result = parser.parse(
            text = "토요일 8시 친구와 저녁",
            inputType = ScheduleParseInputType.VOICE_TRANSCRIPT,
            referenceDate = "2026-07-16",
            defaultDurationMinutes = 60,
        )

        assertNull(result.destination)
        assertTrue("destination" in result.missingFields)
    }

    @Test
    fun `does not treat rename arrow without date or time context as route`() {
        val result = parser.parse(
            text = "행사 날짜: 2026-06-13\n행사 시간: 14:30\n장소: 수원 더케이 웨딩컨벤션\n더시그너스 웨딩홀 -> 이름변경",
            referenceDate = "2026-01-01",
            defaultDurationMinutes = 60,
        )

        assertEquals("수원 더케이 웨딩컨벤션", result.destination?.name)
        assertNull(result.origin)
        assertTrue(result.originRequired)
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
