package com.noLate.schedule.application.useCase

import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleHybridParserService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

@Component
class ScheduleUseCase(
    private val scheduleService: ScheduleService,
    private val schedulePushJobService : SchedulePushJobService,
    private val scheduleHybridParserService: ScheduleHybridParserService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")
    /**
     * 자유 형식 텍스트를 일정 입력 폼용 데이터로 분석한다.
     *
     * 이 단계에서는 DB에 저장하지 않으며, 규칙 우선 및 AI 폴백 정책은 전용 서비스에 위임한다.
     */
    fun parseScheduleText(
        text: String?,
        referenceDate: String?,
        defaultDurationMinutes: Int?,
    ): ScheduleParseDto {
        // 기존 호출자와 테스트가 사용하던 3개 인자 경로는 그대로 보존한다. 신규 API 요청만
        // 아래의 입력 타입 포함 오버로드를 사용하므로, 일반 텍스트 분석의 동작에는 영향이 없다.
        return scheduleHybridParserService.parse(text, referenceDate, defaultDurationMinutes)
    }

    /**
     * 입력 채널을 보존한 일정 분석 경로다.
     *
     * Controller에서 받은 enum을 문자열로 다시 변환하지 않고 그대로 넘겨, FE와 BE 사이의
     * VOICE_TRANSCRIPT 계약이 컴파일 시점에도 검증되도록 한다.
     */
    fun parseScheduleText(
        text: String?,
        inputType: ScheduleParseInputType,
        referenceDate: String?,
        defaultDurationMinutes: Int?,
    ): ScheduleParseDto {
        return scheduleHybridParserService.parse(text, inputType, referenceDate, defaultDurationMinutes)
    }

    /**
     * 일정관리 앱의 기본 생성 유스케이스.
     * 일정 본문과 함께 출발지/도착지/선택 경로까지 하나의 일정으로 저장한다.
     */
    @Transactional
    fun addSchedule(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        // 1. 스케줄 생성
        val addSchedule = scheduleService.addSchedule(memberId, scheduleDto);

        // 2. 스케줄 푸쉬 잡 생성
        if (canCreatePushJob(addSchedule)) {
            schedulePushJobService.registerFromScheduleDto(memberId, addSchedule)
        }

        return addSchedule;
    }

    /**
     * 일정 편집 유스케이스.
     * 시간, 카테고리, 장소, 경로 정보를 모두 같은 화면 모델 기준으로 교체한다.
     */
    @Transactional
    fun updateSchedule(memberId: Long, scheduleId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val updated = scheduleService.updateSchedule(memberId, scheduleId, scheduleDto)
        registerOrCancelPushJob(memberId, updated)
        return updated
    }

    private fun registerOrCancelPushJob(memberId: Long, scheduleDto: ScheduleDto) {
        val scheduleId = scheduleDto.id ?: return
        if (canCreatePushJob(scheduleDto)) {
            schedulePushJobService.registerFromScheduleDto(memberId, scheduleDto)
        } else {
            schedulePushJobService.cancelByScheduleId(scheduleId)
        }
    }

    private fun canCreatePushJob(scheduleDto: ScheduleDto): Boolean {
        return scheduleDto.notificationEnabled == true &&
            parseInstant(scheduleDto.startAt).isAfter(Instant.now(clock))
    }

    private fun parseInstant(value: String): Instant {
        return runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .recoverCatching { LocalDateTime.parse(value).atZone(seoulZone).toInstant() }
            .getOrThrow()
    }

    /**
     * 일정 삭제 유스케이스.
     * 복구 가능성을 남기기 위해 실제 삭제가 아니라 deleted flag를 변경한다.
     */
    @Transactional
    fun deleteSchedule(memberId: Long, scheduleId: Long) {
        scheduleService.deleteSchedule(memberId, scheduleId)
        schedulePushJobService.cancelByScheduleId(scheduleId)
    }

    /**
     * 사용자가 푸시의 "지금 출발" 액션을 선택했을 때 더 이상의 출발 알림을 중지한다.
     */
    @Transactional
    fun markDeparted(memberId: Long, scheduleId: Long): ScheduleDto {
        val updated = scheduleService.markDeparted(memberId, scheduleId)
        schedulePushJobService.cancelByScheduleId(scheduleId)
        return updated
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
