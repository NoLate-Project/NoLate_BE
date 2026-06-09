package com.noLate.schedule.domain

/**
 * 출발지가 어떤 경로로 결정되었는지 프론트에 알려준다.
 */
enum class ScheduleOriginSource {
    /** 사용자가 입력한 원문에서 출발지를 찾았다. */
    TEXT,

    /** 사용자의 기본 즐겨찾기 장소를 출발지로 사용했다. */
    FAVORITE_DEFAULT,

    /** 출발지를 찾지 못해 사용자가 직접 선택해야 한다. */
    REQUIRED,
}

/**
 * 최종 분석 결과에 규칙 파서와 AI가 어느 정도 관여했는지 나타낸다.
 */
enum class ScheduleParseSource {
    /** 규칙 파서만으로 필수 일정 정보를 찾았다. */
    RULE,

    /** 규칙 파서가 놓친 값을 AI가 신뢰도 기준 이상으로 보완했다. */
    AI_ASSISTED,

    /** AI를 사용할 수 없거나 결과가 불충분해 규칙 결과만 반환했다. */
    RULE_FALLBACK,
}

/**
 * 자유 형식 일정 텍스트를 저장 폼에 반영하기 위한 미리보기 DTO다.
 *
 * 이 DTO는 일정을 즉시 저장하지 않는다. 프론트가 분석 결과와 경고를 보여주고
 * 사용자가 수정한 뒤 기존 일정 생성 API로 제출할 수 있도록 구조화된 값만 반환한다.
 */
data class ScheduleParseDto(
    /** 화면 제목에 사용할 "장소 + 시간" 형식의 값이다. */
    val title: String? = null,

    /** 예약자, 촬영 종류, 작가 배정처럼 제목에서 제외한 부가 정보다. */
    val notes: String? = null,

    /** 사용자가 확인하기 쉬운 서울 시간대 기준 날짜와 시간이다. */
    val date: String? = null,
    val time: String? = null,

    /** 일정 생성 payload에 바로 쓸 수 있는 UTC ISO 시각이다. */
    val startAt: String? = null,
    val endAt: String? = null,

    /** 원문에서 추출한 출발지와 출발지 결정 상태다. */
    val origin: SchedulePlaceDto? = null,
    val originSource: ScheduleOriginSource = ScheduleOriginSource.REQUIRED,
    val originRequired: Boolean = true,

    /** 원문 또는 AI 보완으로 결정된 목적지다. */
    val destination: SchedulePlaceDto? = null,

    /** 분석 경로와 AI 호출 여부를 화면에 설명하기 위한 진단 정보다. */
    val parseSource: ScheduleParseSource = ScheduleParseSource.RULE,
    val aiAttempted: Boolean = false,
    val needsReview: Boolean = false,

    /** 추정 또는 검토가 필요한 이유와 누락 필드 목록이다. */
    val warnings: List<String> = emptyList(),
    val missingFields: List<String> = emptyList(),
)
