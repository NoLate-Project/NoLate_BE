package com.noLate.schedule.domain

import com.fasterxml.jackson.databind.JsonNode

/**
 * 일정 카테고리 화면 모델.
 *
 * 현재는 별도 카테고리 테이블이 없으므로 일정 저장 시점의 카테고리 값을 함께 저장한다.
 */
data class ScheduleCategoryDto(
    /** 카테고리 식별자 */
    val id: String? = null,

    /** 카테고리 이름 */
    val title: String? = null,

    /** 캘린더와 리스트에서 사용할 색상 코드 */
    val color: String? = null,
)

/**
 * 일정의 출발지 또는 도착지 위치 모델.
 *
 * 지도 검색 결과가 없을 수 있어 이름/주소/좌표는 모두 nullable로 유지한다.
 */
data class SchedulePlaceDto(
    /** 장소 이름 */
    val name: String? = null,

    /** 장소 주소 */
    val address: String? = null,

    /** WGS84 위도 */
    val lat: Double? = null,

    /** WGS84 경도 */
    val lng: Double? = null,
)

/**
 * 일정 API와 UseCase 사이에서 사용하는 일정 데이터 모델.
 *
 * 생성/수정 요청, 목록 응답, 상세 응답에서 공통으로 쓰며,
 * route에는 선택한 경로의 path/환승/요금 정보를 JSON으로 보관한다.
 */
data class ScheduleDto(
    /** 일정 PK */
    val id: Long? = null,

    /** 일정 제목 */
    val title: String,

    /** 일정 시작 시각(ISO-8601) */
    val startAt: String,

    /** 일정 종료 시각(ISO-8601) */
    val endAt: String? = null,

    /** 사용자가 종료 시각을 직접 입력했는지 여부 */
    val hasEndTime: Boolean? = null,

    /** 종일 일정 여부 */
    val allDay: Boolean? = null,

    /** 이동 예상 시간(분) */
    val travelMinutes: Int? = null,

    /** 출발 예정 시각(ISO-8601) */
    val departAt: String? = null,

    /** 이동 수단 */
    val travelMode: ScheduleTravelMode? = null,

    /** 출발지 정보 */
    val origin: SchedulePlaceDto? = null,

    /** 도착지 정보 */
    val destination: SchedulePlaceDto? = null,

    /** 장소 또는 경로 요약명 */
    val locationName: String? = null,

    /** 일정 카테고리 */
    val category: ScheduleCategoryDto,

    /** 일정 메모 */
    val notes: String? = null,

    /** 선택 경로 상세 JSON */
    val route: JsonNode? = null,

    /** 실시간 ETA 기반 출발 알림 사용 여부 */
    val notificationEnabled: Boolean? = null,

    /** 권장 출발 시각 몇 분 전부터 알림을 시작할지 */
    val notificationLeadMinutes: Int? = null,

    /** 사용자 재알림 간격 */
    val notificationIntervalMinutes: Int? = null,

    /** 마지막 수정 시각(ISO-8601) */
    val updatedAt: String? = null,
)
