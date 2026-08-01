package com.noLate.schedule.application

import com.noLate.schedule.domain.ScheduleRecognitionAlternative

/**
 * AI가 추출한 일정 후보와 각 필드의 신뢰도를 전달한다.
 *
 * 규칙 파서가 찾지 못한 값만 보완해야 하므로, 값과 신뢰도를 함께 받아
 * 애플리케이션 계층에서 실제 반영 여부를 결정할 수 있게 한다.
 */
data class ScheduleAiParseResult(
    val date: String? = null,
    val dateConfidence: Double = 0.0,
    val time: String? = null,
    val timeConfidence: Double = 0.0,
    val originName: String? = null,
    val originAddress: String? = null,
    val originConfidence: Double = 0.0,
    val destinationName: String? = null,
    val destinationAddress: String? = null,
    val destinationConfidence: Double = 0.0,
    val summary: String? = null,
    val summaryConfidence: Double = 0.0,
)

/**
 * 외부 AI 호출 여부와 결과를 함께 표현한다.
 *
 * AI 비활성화나 일시적 장애도 정상적인 규칙 파서 폴백으로 처리하기 위해
 * 예외 대신 호출 상태와 사용자에게 전달할 경고를 반환한다.
 */
data class ScheduleAiParseOutcome(
    val attempted: Boolean,
    val result: ScheduleAiParseResult? = null,
    val warning: String? = null,
)

/**
 * AI가 생성한 문장을 받지 않고, 전달된 음성 인식 후보 중 하나의 인덱스만 받는다.
 *
 * 애플리케이션 계층은 이 인덱스를 원래 후보 목록에 다시 매핑하므로 모델이 날짜, 시간,
 * 장소 또는 일정 내용을 새로 만들어 전사문에 끼워 넣을 수 없다.
 */
data class ScheduleTranscriptCorrectionResult(
    val selectedIndex: Int? = null,
    val confidence: Double = 0.0,
)

data class ScheduleTranscriptCorrectionOutcome(
    val attempted: Boolean,
    val result: ScheduleTranscriptCorrectionResult? = null,
    val warning: String? = null,
)

/**
 * 일정 AI 분석기의 애플리케이션 포트다.
 *
 * 서비스 계층이 Groq 같은 특정 공급자에 의존하지 않도록 추상화하며,
 * 테스트에서는 이 인터페이스의 대역으로 AI 호출 조건을 검증한다.
 */
interface ScheduleAiParser {
    /**
     * 개인정보가 제거된 원문과 기준 날짜를 받아 구조화된 일정 후보를 반환한다.
     */
    fun parse(text: String, referenceDate: String): ScheduleAiParseOutcome

    /**
     * 동일 발화의 음성 인식 후보 중 가장 신뢰할 수 있는 문장을 고른다.
     *
     * 기존 구현과 테스트 대역의 호환성을 위해 기본 구현은 호출하지 않은 폴백을 반환한다.
     * 구현체는 반드시 후보를 재작성하지 않고 [ScheduleTranscriptCorrectionResult.selectedIndex]만
     * 반환해야 한다.
     */
    fun correctTranscript(
        candidates: List<ScheduleRecognitionAlternative>,
        referenceDate: String,
    ): ScheduleTranscriptCorrectionOutcome = ScheduleTranscriptCorrectionOutcome(attempted = false)
}
