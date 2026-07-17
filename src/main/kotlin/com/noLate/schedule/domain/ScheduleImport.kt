package com.noLate.schedule.domain

/**
 * FE가 일정 원본을 읽은 경로다.
 *
 * 기기 캘린더와 Google Calendar는 원본 id 체계가 다르므로 provider까지
 * 멱등 키에 포함해야 서로 다른 원본이 잘못 합쳐지지 않는다.
 */
enum class ScheduleImportProvider {
    APPLE_DEVICE,
    ANDROID_DEVICE,
    GOOGLE,
}

/**
 * 외부 캘린더의 한 발생 건을 식별하는 값이다.
 *
 * 기기 캘린더의 반복 일정은 같은 eventId를 공유할 수 있어 occurrenceStartAt을 함께 받는다.
 * Google은 각 발생 건의 eventId가 고유하므로 Service에서 공급자별 키 규칙을 적용한다.
 * 원본 값은 DB에 직접 저장하지 않고 Service에서 SHA-256 키로 변환한다.
 */
data class ScheduleImportSource(
    val provider: ScheduleImportProvider,
    val calendarId: String,
    val eventId: String,
    val occurrenceStartAt: String,
)

/**
 * 같은 원본을 다시 가져오면 기존 일정을 반환하고 created=false로 알린다.
 */
data class ScheduleImportResultDto(
    val schedule: ScheduleDto,
    val created: Boolean,
)
