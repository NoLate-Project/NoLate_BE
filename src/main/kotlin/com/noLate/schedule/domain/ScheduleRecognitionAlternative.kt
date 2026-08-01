package com.noLate.schedule.domain

/**
 * 음성 인식기가 같은 발화에 대해 제공한 대체 전사 후보다.
 *
 * confidence는 STT 공급자가 제공할 때만 전달하며, 0~1 범위 밖의 값은 애플리케이션
 * 계층에서 무시한다. 서버는 이 후보를 새 문장 생성의 재료로 사용하지 않고, 전달된
 * 문장 중 하나를 그대로 선택하는 데만 사용한다.
 */
data class ScheduleRecognitionAlternative(
    val text: String,
    val confidence: Double? = null,
)
