package com.noLate.schedule.domain

/**
 * 빠른 일정 분석으로 들어온 원문의 채널을 나타낸다.
 *
 * 같은 문자열이라도 OCR은 불필요한 줄바꿈과 구분 문자가 섞일 수 있고, 음성 전사는
 * "일정 추가해줘" 같은 명령형 꼬리가 붙을 수 있다. 입력 채널을 애플리케이션 계층까지
 * 전달해 채널별 정규화 정책을 적용하되, 실제 일정 추론 결과 모델과는 분리한다.
 */
enum class ScheduleParseInputType {
    TEXT,
    CONVERSATION,
    IMAGE_OCR,
    VOICE_TRANSCRIPT,
    SHARE_TEXT,
}
