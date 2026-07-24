package com.noLate.schedule.application.useCase

import com.noLate.favorite.application.service.FavoritePlaceService
import com.noLate.schedule.application.service.ScheduleService
import com.noLate.schedule.application.service.ScheduleDepartureStatusService
import com.noLate.schedule.application.service.ScheduleHybridParserService
import com.noLate.schedule.application.service.SchedulePushJobService
import com.noLate.schedule.application.service.ScheduleTravelPlanService
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportResultDto
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.domain.ScheduleOriginSource
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.SchedulePlaceDto
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
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
    private val scheduleDepartureStatusService: ScheduleDepartureStatusService,
    private val favoritePlaceService: FavoritePlaceService,
    private val clock: Clock = Clock.systemUTC(),
    private val scheduleTravelPlanService: ScheduleTravelPlanService? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")
    /**
     * 자유 형식 텍스트를 일정 입력 폼용 데이터로 분석한다.
     *
     * 이 단계에서는 DB에 저장하지 않으며, 규칙 우선 및 AI 폴백 정책은 전용 서비스에 위임한다.
     */
    fun parseScheduleText(
        text: String?,
        inputType: ScheduleParseInputType,
        referenceDate: String?,
        defaultDurationMinutes: Int?,
    ): ScheduleParseDto {
        val result = scheduleHybridParserService.parse(
            text,
            inputType,
            referenceDate,
            defaultDurationMinutes,
        )
        log.info(
            "Schedule parse completed. inputType={}, textLength={}, parseSource={}, aiAttempted={}, needsReview={}, missingFields={}",
            inputType,
            text?.length ?: 0,
            result.parseSource,
            result.aiAttempted,
            result.needsReview,
            result.missingFields,
        )
        return result
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
        recognitionConfidence: Double? = null,
    ): ScheduleParseDto {
        return scheduleHybridParserService.parse(
            text,
            inputType,
            referenceDate,
            defaultDurationMinutes,
            recognitionConfidence,
        )
    }

    /**
     * 로그인 회원의 설정까지 반영하는 HTTP 요청용 일정 분석 경로다.
     *
     * 규칙 파서가 원문에서 출발지를 찾았다면 그 값을 최우선으로 유지한다. 출발지가 없을 때만
     * 계정에 저장된 기본 주소(기본 출발지)를 조회해 채운다. 현재 기본 주소는 저장 구조상
     * favorite_places 테이블의 is_default_origin=true 레코드이지만, 일반 즐겨찾기 첫 항목을
     * 임의로 선택하지는 않는다. 기본 주소도 없는 회원은 기존처럼 REQUIRED 상태를 반환한다.
     */
    fun parseScheduleText(
        memberId: Long,
        text: String?,
        inputType: ScheduleParseInputType,
        referenceDate: String?,
        defaultDurationMinutes: Int?,
        recognitionConfidence: Double? = null,
    ): ScheduleParseDto {
        val parsed = scheduleHybridParserService.parse(
            text,
            inputType,
            referenceDate,
            defaultDurationMinutes,
            recognitionConfidence,
        )
        if (parsed.origin != null) return parsed

        val defaultOrigin = favoritePlaceService.getDefaultOrigin(memberId) ?: return parsed
        return parsed.copy(
            origin = SchedulePlaceDto(
                name = defaultOrigin.placeName?.takeIf { it.isNotBlank() } ?: defaultOrigin.label,
                address = defaultOrigin.address,
                lat = defaultOrigin.lat,
                lng = defaultOrigin.lng,
            ),
            originSource = ScheduleOriginSource.FAVORITE_DEFAULT,
            originRequired = false,
            missingFields = parsed.missingFields.filterNot { it == "origin" },
        )
    }

    /**
     * 일정관리 앱의 기본 생성 유스케이스.
     * 일정 본문과 함께 출발지/도착지/선택 경로까지 하나의 일정으로 저장한다.
     */
    @Transactional
    fun addSchedule(memberId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        // 1. 스케줄 생성
        val addSchedule = scheduleService.addSchedule(memberId, scheduleDto);

        // 기존 오너 ScheduleRoute 저장은 유지하면서 사용자별 계획 테이블에도 호환 기록한다.
        scheduleTravelPlanService?.syncOwnerTravelPlan(memberId, addSchedule)

        // 2. 스케줄 푸쉬 잡 생성
        if (canCreatePushJob(addSchedule)) {
            schedulePushJobService.registerFromScheduleDto(memberId, addSchedule)
        }

        return addSchedule;
    }

    /**
     * 외부 캘린더 가져오기 전용 생성 경로다.
     * 이미 저장된 원본에는 PushJob을 다시 등록하거나 구독 quota를 다시 소비하지 않는다.
     */
    @Transactional
    fun importSchedule(
        memberId: Long,
        scheduleDto: ScheduleDto,
        source: ScheduleImportSource,
    ): ScheduleImportResultDto {
        val result = scheduleService.importSchedule(memberId, scheduleDto, source)
        if (result.created) {
            scheduleTravelPlanService?.syncOwnerTravelPlan(memberId, result.schedule)
        }
        if (result.created && canCreatePushJob(result.schedule)) {
            schedulePushJobService.registerFromScheduleDto(memberId, result.schedule)
        }
        return result
    }

    /**
     * 일정 편집 유스케이스.
     * 시간, 카테고리, 장소, 경로 정보를 모두 같은 화면 모델 기준으로 교체한다.
     */
    @Transactional
    fun updateSchedule(memberId: Long, scheduleId: Long, scheduleDto: ScheduleDto): ScheduleDto {
        val updated = scheduleService.updateSchedule(memberId, scheduleId, scheduleDto)
        // 공유 EDITOR가 수정해도 평탄형 경로와 기존 오너 push job은 실제 일정 소유자 기준으로
        // 동기화한다. 요청자를 오너로 간주하면 공유 편집 직후 SCHEDULE_NOT_FOUND로 롤백된다.
        val ownerMemberId = updated.ownerMemberId ?: memberId
        scheduleTravelPlanService?.syncOwnerTravelPlan(ownerMemberId, updated)
        scheduleTravelPlanService?.findStaleNotificationMemberIds(scheduleId)
            .orEmpty()
            .forEach { staleMemberId ->
                schedulePushJobService.cancelByScheduleIdAndMemberId(scheduleId, staleMemberId)
            }
        registerOrCancelPushJob(ownerMemberId, updated)
        return updated
    }

    private fun registerOrCancelPushJob(memberId: Long, scheduleDto: ScheduleDto) {
        val scheduleId = scheduleDto.id ?: return
        if (canCreatePushJob(scheduleDto)) {
            schedulePushJobService.registerFromScheduleDto(memberId, scheduleDto)
        } else {
            schedulePushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId)
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
        val detail = scheduleService.getScheduleDetail(memberId, scheduleId)

        scheduleDepartureStatusService.markDeparted(memberId, scheduleId)
        scheduleTravelPlanService?.disableNotification(memberId, scheduleId)
        schedulePushJobService.cancelByScheduleIdAndMemberId(scheduleId, memberId)

        val updated = if (detail.ownerMemberId == memberId) {
            scheduleService.markDeparted(memberId, scheduleId)
        } else {
            scheduleService.getScheduleDetail(memberId, scheduleId)
        }

        return scheduleDepartureStatusService.attachDepartureParticipants(memberId, updated)
    }

    /**
     * 사용자가 푸시의 "5분 뒤 다시 알림" 액션을 선택했을 때 출발 알림 job을 다시 깨운다.
     */
    @Transactional
    fun snoozeDepartureReminder(memberId: Long, scheduleId: Long) {
        schedulePushJobService.snoozeDepartureReminder(memberId, scheduleId)
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
        val withDeparture = scheduleDepartureStatusService.attachDepartureParticipants(
            currentMemberId = memberId,
            scheduleDto = scheduleService.getScheduleDetail(memberId, scheduleId),
        )
        return scheduleTravelPlanService?.attachOverview(memberId, withDeparture) ?: withDeparture
    }

    /**
     * 캘린더 범위 조회 유스케이스.
     * 월간/주간 캘린더가 화면에 표시하는 날짜 범위와 겹치는 일정을 가져온다.
     */
    fun getCalendarScheduleList(memberId: Long, startAt: String, endAt: String): List<ScheduleDto> {
        return scheduleService.getCalendarScheduleList(memberId, startAt, endAt)
    }

    fun getCalendarCacheRevision(memberId: Long): Long =
        scheduleService.getCalendarCacheRevision(memberId)

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
