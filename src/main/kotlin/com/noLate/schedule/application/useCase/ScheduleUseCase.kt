package com.noLate.schedule.application.useCase

import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.domain.ScheduleDto
import org.springframework.stereotype.Component

@Component
class ScheduleUseCase(
    private val scheduleService: ScheduleService,
) {
    /**
     * 일정관리 앱의 기본 생성 유스케이스.
     * 일정 본문과 함께 출발지/도착지/선택 경로까지 하나의 일정으로 저장한다.
     */
    fun addSchedule(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        return scheduleService.addSchedule(memberId, scheduleDto)
    }

    /**
     * 일정 편집 유스케이스.
     * 시간, 카테고리, 장소, 경로 정보를 모두 같은 화면 모델 기준으로 교체한다.
     */
    fun updateSchedule(memberId: Long, scheduleId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        return scheduleService.updateSchedule(memberId, scheduleId, scheduleDto)
    }

    /**
     * 일정 삭제 유스케이스.
     * 복구 가능성을 남기기 위해 실제 삭제가 아니라 deleted flag를 변경한다.
     */
    fun deleteSchedule(memberId: Long, scheduleId: Long) {
        scheduleService.deleteSchedule(memberId, scheduleId)
    }

    /**
     * 전체 일정 목록 유스케이스.
     * 동기화나 단순 리스트 화면처럼 범위 제한 없이 사용자의 활성 일정을 가져올 때 쓴다.
     */
    fun getScheduleList(memberId: Long): List<ScheduleDto> {
        return scheduleService.getScheduleList(memberId)
    }

    /**
     * 일정 상세 조회 유스케이스.
     * 일정 편집 화면 진입 시 최신 저장 상태를 가져온다.
     */
    fun getScheduleDetail(memberId: Long, scheduleId: Long): ScheduleDto {
        return scheduleService.getScheduleDetail(memberId, scheduleId)
    }

    /**
     * 캘린더 범위 조회 유스케이스.
     * 월간/주간 캘린더가 화면에 표시하는 날짜 범위와 겹치는 일정을 가져온다.
     */
    fun getCalendarScheduleList(memberId: Long, startAt: String, endAt: String): List<ScheduleDto> {
        return scheduleService.getCalendarScheduleList(memberId, startAt, endAt)
    }

    /**
     * 하루 일정 조회 유스케이스.
     * 사용자가 캘린더에서 선택한 날짜의 타임라인/하단 리스트를 구성한다.
     */
    fun getDailyScheduleList(memberId: Long, date: String): List<ScheduleDto> {
        return scheduleService.getDailyScheduleList(memberId, date)
    }

    /**
     * 다가오는 일정 조회 유스케이스.
     * 홈 화면, 알림 준비, 다음 일정 카드에서 현재 이후 일정을 제한 개수만 가져온다.
     */
    fun getUpcomingScheduleList(memberId: Long, fromAt: String?, limit: Int?): List<ScheduleDto> {
        return scheduleService.getUpcomingScheduleList(memberId, fromAt, limit)
    }

    /**
     * 일정 검색/필터 유스케이스.
     * 제목, 장소, 메모 키워드와 카테고리/기간 조건으로 일정 목록을 좁힌다.
     */
    fun searchScheduleList(
        memberId: Long,
        keyword: String?,
        categoryId: String?,
        startAt: String?,
        endAt: String?,
    ): List<ScheduleDto> {
        return scheduleService.searchScheduleList(memberId, keyword, categoryId, startAt, endAt)
    }

    /**
     * 출발 준비 일정 조회 유스케이스.
     * NoLate의 핵심 기능인 이동 시간/경로 기반 출발 알림 후보 일정을 가져온다.
     */
    fun getDepartureReadyScheduleList(memberId: Long, fromAt: String?, toAt: String?): List<ScheduleDto> {
        return scheduleService.getDepartureReadyScheduleList(memberId, fromAt, toAt)
    }
}
