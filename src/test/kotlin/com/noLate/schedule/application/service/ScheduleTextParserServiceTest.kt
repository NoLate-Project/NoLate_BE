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
