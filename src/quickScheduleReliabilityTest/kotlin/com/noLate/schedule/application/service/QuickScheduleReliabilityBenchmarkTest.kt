package com.noLate.schedule.application.service

import com.noLate.schedule.application.ScheduleAiParseOutcome
import com.noLate.schedule.application.ScheduleAiParser
import com.noLate.schedule.domain.ScheduleParseConfidenceLevel
import com.noLate.schedule.domain.ScheduleParseInputType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * 빠른 일정의 릴리스 게이트다.
 *
 * 원본 이미지/음성 품질은 실제 단말 QA에서 별도로 측정해야 한다. 이 테스트는 각 채널에서
 * 추출된 문장이 서버에 도착한 이후 날짜·시간·장소가 동시에 맞는 비율과, HIGH로 자동
 * 승인한 결과 정밀도의 95% Wilson 하한이 모두 90% 이상인지 검증한다.
 */
class QuickScheduleReliabilityBenchmarkTest {
    private val service = ScheduleHybridParserService(
        ScheduleTextParserService(),
        object : ScheduleAiParser {
            override fun parse(text: String, referenceDate: String) =
                ScheduleAiParseOutcome(attempted = false)
        },
    )

    @Test
    fun `recognized input critical-field accuracy and high-confidence precision stay above 90 percent`() {
        val outcomes = fixtures.map { fixture ->
            val result = service.parse(
                text = fixture.text,
                inputType = fixture.inputType,
                referenceDate = fixture.referenceDate,
                defaultDurationMinutes = 60,
                recognitionConfidence = fixture.recognitionConfidence,
            )
            val exact = result.date == fixture.date &&
                result.time == fixture.time &&
                result.destination?.name == fixture.destination
            Outcome(
                fixture,
                exact,
                result.confidence?.level,
                actualDate = result.date,
                actualTime = result.time,
                actualDestination = result.destination?.name,
            )
        }

        val accuracy = outcomes.count { it.exact }.toDouble() / outcomes.size
        val accuracyLowerBound = wilsonLowerBound(outcomes.count { it.exact }, outcomes.size)
        val accepted = outcomes.filter { it.level == ScheduleParseConfidenceLevel.HIGH }
        val highConfidencePrecision = accepted.count { it.exact }.toDouble() / accepted.size
        val highConfidenceLowerBound = wilsonLowerBound(accepted.count { it.exact }, accepted.size)
        val unsafeAcceptances = accepted.filterNot { it.exact }
        val channelAccuracy = outcomes.groupBy { it.fixture.channel }.mapValues { (_, channelOutcomes) ->
            channelOutcomes.count { it.exact }.toDouble() / channelOutcomes.size
        }

        println(
            "quick-schedule benchmark: overall=${"%.1f".format(accuracy * 100)}%, " +
                "overall-95%-lower=${"%.1f".format(accuracyLowerBound * 100)}%, " +
                channelAccuracy.entries.joinToString { (channel, score) ->
                    "$channel=${"%.1f".format(score * 100)}%"
                } +
                ", high-precision=${"%.1f".format(highConfidencePrecision * 100)}%" +
                ", high-95%-lower=${"%.1f".format(highConfidenceLowerBound * 100)}%",
        )

        assertTrue(
            accuracy >= TARGET_RELIABILITY,
            report("핵심 필드 동시 정확도", accuracy, outcomes.filterNot { it.exact }),
        )
        assertTrue(
            accuracyLowerBound >= TARGET_RELIABILITY,
            "핵심 필드 동시 정확도의 95% Wilson 하한 " +
                "${"%.1f".format(accuracyLowerBound * 100)}%가 90% 미만입니다.\n" +
                report("오답", accuracy, outcomes.filterNot { it.exact }),
        )
        channelAccuracy.forEach { (channel, score) ->
            assertTrue(
                score >= TARGET_RELIABILITY,
                report(
                    "$channel 채널 핵심 필드 동시 정확도",
                    score,
                    outcomes.filter { it.fixture.channel == channel && !it.exact },
                ),
            )
        }
        assertTrue(accepted.isNotEmpty(), "HIGH 신뢰도 표본이 없어 자동 승인 정밀도를 계산할 수 없습니다.")
        assertTrue(
            highConfidencePrecision >= TARGET_RELIABILITY,
            report("HIGH 자동 승인 정밀도", highConfidencePrecision, unsafeAcceptances),
        )
        assertTrue(
            highConfidenceLowerBound >= TARGET_RELIABILITY,
            "HIGH 자동 승인 정밀도의 95% Wilson 하한 " +
                "${"%.1f".format(highConfidenceLowerBound * 100)}%가 90% 미만입니다.",
        )
        assertTrue(
            unsafeAcceptances.isEmpty(),
            report("잘못된 HIGH 자동 승인", highConfidencePrecision, unsafeAcceptances),
        )
    }

    @Test
    fun `confidence contract separates recognition score and blocks results below 90 percent`() {
        val high = service.parse(
            text = "2026년 8월 3일 오후 2시 서울역 회의",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-08-01",
            defaultDurationMinutes = 60,
            recognitionConfidence = 0.95,
        )
        val medium = service.parse(
            text = "2026년 8월 3일 오후 2시 서울역 회의",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-08-01",
            defaultDurationMinutes = 60,
            recognitionConfidence = 0.89,
        )
        val unknown = service.parse(
            text = "2026년 8월 3일 오후 2시 서울역 회의",
            inputType = ScheduleParseInputType.IMAGE_OCR,
            referenceDate = "2026-08-01",
            defaultDurationMinutes = 60,
        )

        assertEquals(ScheduleParseConfidenceLevel.HIGH, high.confidence?.level)
        assertEquals(0.95, high.confidence?.recognition)
        assertEquals(0.95, high.confidence?.overall)
        assertTrue(!high.needsReview)

        assertEquals(ScheduleParseConfidenceLevel.MEDIUM, medium.confidence?.level)
        assertEquals(0.89, medium.confidence?.recognition)
        assertTrue(medium.needsReview)
        assertTrue(medium.confidence?.reasons?.any { "90%" in it } == true)

        assertEquals(ScheduleParseConfidenceLevel.REVIEW, unknown.confidence?.level)
        assertTrue(unknown.needsReview)
        assertTrue((unknown.confidence?.overall ?: 1.0) < TARGET_RELIABILITY)
    }

    private fun report(label: String, score: Double, failures: List<Outcome>): String = buildString {
        append(label)
        append(": ")
        append("%.1f%%".format(score * 100.0))
        if (failures.isNotEmpty()) {
            append("\n실패 표본:\n")
            failures.forEach { outcome ->
                append("- [")
                append(outcome.fixture.channel)
                append("] ")
                append(outcome.fixture.text)
                append(" => actual=")
                append(outcome.actualDate)
                append(' ')
                append(outcome.actualTime)
                append(" / ")
                append(outcome.actualDestination)
                append('\n')
            }
        }
    }

    private fun wilsonLowerBound(successes: Int, total: Int, z: Double = 1.96): Double {
        require(total > 0 && successes in 0..total)
        val observed = successes.toDouble() / total
        val zSquared = z * z
        val denominator = 1.0 + zSquared / total
        val center = observed + zSquared / (2.0 * total)
        val margin = z * sqrt(
            (observed * (1.0 - observed) + zSquared / (4.0 * total)) / total,
        )
        return (center - margin) / denominator
    }

    private data class Fixture(
        val channel: String,
        val inputType: ScheduleParseInputType,
        val text: String,
        val referenceDate: String,
        val date: String,
        val time: String,
        val destination: String,
        val recognitionConfidence: Double? = null,
    )

    private data class Outcome(
        val fixture: Fixture,
        val exact: Boolean,
        val level: ScheduleParseConfidenceLevel?,
        val actualDate: String?,
        val actualTime: String?,
        val actualDestination: String?,
    )

    private companion object {
        const val TARGET_RELIABILITY = 0.90

        val fixtures = listOf(
            // 텍스트 입력: 앱이 실제로 보내는 CONVERSATION 타입이다.
            Fixture("text", ScheduleParseInputType.CONVERSATION, "금요일 오후 3시 강남역 회의", "2026-07-16", "2026-07-17", "15:00", "강남역"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "오늘 오후 3시 서울대병원 진료", "2026-07-18", "2026-07-18", "15:00", "서울대병원"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 1일 밤 11시 인천공항 출국", "2026-07-18", "2026-08-01", "23:00", "인천공항"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "금요일 오후 6시 30분 잠실야구장 야구 관람", "2026-07-18", "2026-07-24", "18:30", "잠실야구장"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "토요일 정오 코엑스 점심", "2026-07-18", "2026-07-18", "12:00", "코엑스"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "일요일 자정 강남역 모임", "2026-07-18", "2026-07-19", "00:00", "강남역"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 10일 오후 4시 15분 수원 컨벤션센터 고객미팅", "2026-07-18", "2026-08-10", "16:15", "수원 컨벤션센터"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 11일 오전 7시 한강공원 러닝", "2026-07-18", "2026-08-11", "07:00", "한강공원"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 12일 저녁 8시 롯데시네마 영화 관람", "2026-07-18", "2026-08-12", "20:00", "롯데시네마"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 15일 13:00 국립중앙박물관 전시 관람", "2026-07-18", "2026-08-15", "13:00", "국립중앙박물관"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 20일 오전 9시 서울역 회의", "2026-08-01", "2026-08-20", "09:00", "서울역"),
            Fixture("text", ScheduleParseInputType.CONVERSATION, "8월 21일 오후 2시 코엑스 전시 관람", "2026-08-01", "2026-08-21", "14:00", "코엑스"),

            // 사진 입력: OCR이 자주 만드는 라벨, 줄바꿈, 화살표, 압축 문자열을 포함한다.
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "예약일 2026년 7월 12일\n예약시간 오후 3시 반\n장소 강남역", "2026-07-02", "2026-07-12", "15:30", "강남역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "행사명: 가족 촬영\n일시: 2026-08-15 오후 2:30\n장소: 시민회관", "2026-01-01", "2026-08-15", "14:30", "시민회관", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "홍길동 20260630/14:30 인천빌라드컨벤션 예물O 화동O", "2026-01-01", "2026-06-30", "14:30", "인천빌라드컨벤션", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "김혜림 2026-05-16 15:30 그랜드 오스티엄 웨딩홀 예물X", "2026-01-01", "2026-05-16", "15:30", "그랜드 오스티엄 웨딩홀", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "20260530/1030.베웨.(m)백진욱(s)강연지.엘마리노앳인천.박지낭.m", "2026-01-01", "2026-05-30", "10:30", "엘마리노앳인천", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 7월 1일 수요일 오전 7시\n강남역 -> 판교 네이버", "2026-07-01", "2026-07-01", "07:00", "판교 네이버", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 7월 11일 토요일 오후 7시\n강남역 >> 내방역", "2026-07-11", "2026-07-11", "19:00", "내방역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 7월 11일 토요일 오후 7시\n강남역 > 내방역", "2026-07-11", "2026-07-11", "19:00", "내방역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 7월 11일 토요일 오후 7시 강남역 3 내방역", "2026-07-11", "2026-07-11", "19:00", "내방역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 7월 18일 토요일 오전 8시\n강남 용용선생", "2026-07-16", "2026-07-18", "08:00", "강남 용용선생", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "예약일 2026년 8월 22일\n예약시간 오전 11시\n장소 부산역", "2026-08-01", "2026-08-22", "11:00", "부산역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "행사명: 디자인 리뷰\n일시: 2026-08-23 오후 4:30\n장소: 서울시청", "2026-08-01", "2026-08-23", "16:30", "서울시청", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "예약일 2026년 8월 24일\n예약시간 오후 1시\n장소 대전역", "2026-08-01", "2026-08-24", "13:00", "대전역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "행사명: 고객 미팅\n일시: 2026-08-25 오전 10:30\n장소: 광화문", "2026-08-01", "2026-08-25", "10:30", "광화문", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 8월 27일 목요일 오후 6시\n서울역 -> 잠실역", "2026-08-01", "2026-08-27", "18:00", "잠실역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "일시: 2026-08-28 오전 9:30\n장소: 롯데호텔", "2026-08-01", "2026-08-28", "09:30", "롯데호텔", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "날짜 2026년 8월 29일\n시간 오후 1시\n장소 판교역", "2026-08-01", "2026-08-29", "13:00", "판교역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "2026년 8월 30일 일요일 저녁 7시\n강남역 >> 홍대입구역", "2026-08-01", "2026-08-30", "19:00", "홍대입구역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "예약일 2026년 9월 1일\n예약시간 오후 3시\n장소 서울역", "2026-08-01", "2026-09-01", "15:00", "서울역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "행사명: 정기 회의\n일시: 2026-09-02 오전 10시\n장소: 강남역", "2026-08-01", "2026-09-02", "10:00", "강남역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "날짜 2026년 9월 3일\n시간 오전 11시\n장소 부산역", "2026-08-01", "2026-09-03", "11:00", "부산역", 0.95),
            Fixture("photo", ScheduleParseInputType.IMAGE_OCR, "일시: 2026-09-04 오후 5:30\n장소: 코엑스", "2026-08-01", "2026-09-04", "17:30", "코엑스", 0.95),

            // 음성 입력: 한국어 숫자, 띄어쓰기, 상대 날짜, 이동 경로를 포함한다.
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "내일 오후 세 시 강남역에서 민수랑 미팅 추가해줘", "2026-07-02", "2026-07-03", "15:00", "강남역", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "다음 주 월요일 오전 열 시 병원 진료", "2026-07-02", "2026-07-06", "10:00", "병원", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "수요일 저녁 7시 강남역에서 판교 네이버까지 일정 추가해줘", "2026-07-11", "2026-07-15", "19:00", "판교 네이버", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 17일 6시30분 석촌호수에서 진욱이랑 데이트", "2026-07-18", "2026-08-17", "18:30", "석촌호수", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "팔월 십칠일 여섯시 삼십분 석촌호수에서 진욱이랑 데이트", "2026-07-18", "2026-08-17", "18:30", "석촌호수", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월17일6시30분석촌호수에서진욱이랑데이트", "2026-07-18", "2026-08-17", "18:30", "석촌호수", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 17일 6시30붐 석촌호수에서 진욱이랑 데이트", "2026-07-18", "2026-08-17", "18:30", "석촌호수", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 십칠일 여섯 시 반 석촌호수에서 진욱이랑 데이트", "2026-07-18", "2026-08-17", "18:30", "석촌호수", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "낼 아침 7시 반 헬스장 운동", "2026-07-18", "2026-07-19", "07:30", "헬스장", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "다음주 화요일 오후 4시 Teams 주간 회의", "2026-07-18", "2026-07-21", "16:00", "Teams", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "내일 오전 아홉 시 서울역 미팅 추가해줘", "2026-08-01", "2026-08-02", "09:00", "서울역", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 24일 오후 2시 코엑스 전시", "2026-08-01", "2026-08-24", "14:00", "코엑스", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "내일 오후 세 시 강남역에서 팀 회의 추가해줘", "2026-08-03", "2026-08-04", "15:00", "강남역", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "다음 주 월요일 오전 열 시 서울대병원 진료", "2026-08-04", "2026-08-10", "10:00", "서울대병원", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 27일 오후 6시 잠실역에서 저녁 약속", "2026-08-01", "2026-08-27", "18:00", "잠실역", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 28일 아침 7시 한강공원 러닝", "2026-08-01", "2026-08-28", "07:00", "한강공원", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 29일 13:30 국립중앙박물관 전시", "2026-08-01", "2026-08-29", "13:30", "국립중앙박물관", 0.95),
            Fixture("voice", ScheduleParseInputType.VOICE_TRANSCRIPT, "8월 30일 저녁 8시 롯데시네마 영화 관람", "2026-08-01", "2026-08-30", "20:00", "롯데시네마", 0.95),
        )
    }
}
