package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.springframework.stereotype.Component

data class ScheduleAccessDecision(
    val canView: Boolean,
    val canEdit: Boolean,
    val travelEnabled: Boolean,
    val canViewAllTravelPlans: Boolean,
    val effectivePermission: ScheduleSharePermission? = null,
    val effectiveContentMode: ScheduleShareContentMode? = null,
    val calendarRole: ScheduleCalendarRole? = null,
)

/**
 * 일정 접근, 편집, 이동 기능 권한을 한 번에 계산하는 단일 정책 경계다.
 *
 * 기존 구현은 일정 조회, 이동 계획, 출발 상태가 각자 직접/카테고리 공유를 조회했다. 캘린더
 * 멤버십이 추가된 상태에서 같은 패턴을 반복하면 한 API는 허용하고 다른 API는 거부하는 drift가
 * 생기기 쉽다. 모든 서비스는 이 결정 객체를 사용하고, 저장소의 native visibility query는
 * 데이터 유출을 막는 첫 필터로만 유지한다.
 */
@Component
class ScheduleAccessPolicy(
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
) {

    fun resolve(memberId: Long, schedule: Schedule): ScheduleAccessDecision {
        if (schedule.memberId == memberId) return ownerDecision(schedule)

        val scheduleId = requireNotNull(schedule.id)
        val direct = scheduleShareRepository.findByScheduleIdAndTargetMemberId(scheduleId, memberId)
            ?.takeIf(::isActive)
        val category = categoryId(schedule)
            ?.let { categoryShareRepository.findByCategoryIdAndTargetMemberId(it, memberId) }
            ?.takeIf(::isActive)
        val calendarMember = schedule.calendarId
            ?.let { calendarMemberRepository.findByCalendarIdAndMemberId(it, memberId) }
            ?.takeIf(::isActive)
        val calendar = calendarMember
            ?.let { calendarRepository.findByIdAndStatusAndDeletedFalse(it.calendarId) }
            ?.takeIf(::isActive)

        return decide(schedule, direct, category, calendarMember, calendar)
    }

    /**
     * 목록 API가 일정마다 네 저장소를 다시 조회하지 않도록 공유 정보를 회원 단위로 한 번씩
     * 읽는다. 캘린더에 일정이 100개 있어도 direct/category/member/calendar 조회 횟수는 일정
     * 개수에 비례하지 않는다.
     */
    fun resolveAll(memberId: Long, schedules: Collection<Schedule>): Map<Long, ScheduleAccessDecision> {
        if (schedules.isEmpty()) return emptyMap()

        val directBySchedule = scheduleShareRepository
            .findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(memberId, ScheduleShareStatus.ACTIVE)
            .associateBy { it.scheduleId }
        val categoryById = categoryShareRepository
            .findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(memberId, ScheduleShareStatus.ACTIVE)
            .associateBy { it.categoryId }
        val calendarMembershipById = calendarMemberRepository
            .findAllByMemberIdAndStatusAndDeletedFalseOrderByIdAsc(memberId)
            .associateBy { it.calendarId }
        val calendarsById = calendarRepository.findAllById(calendarMembershipById.keys)
            .filter(::isActive)
            .associateBy { requireNotNull(it.id) }

        return schedules.mapNotNull { schedule ->
            val scheduleId = schedule.id ?: return@mapNotNull null
            val decision = if (schedule.memberId == memberId) {
                ownerDecision(schedule)
            } else {
                val membership = schedule.calendarId?.let(calendarMembershipById::get)
                decide(
                    schedule = schedule,
                    direct = directBySchedule[scheduleId],
                    category = categoryId(schedule)?.let(categoryById::get),
                    calendarMember = membership,
                    calendar = membership?.calendarId?.let(calendarsById::get),
                )
            }
            scheduleId to decision
        }.toMap()
    }

    /**
     * 이동 현황과 출발 알림에 포함할 회원을 유효 grant 기준으로 계산한다. 일정만 공유받은 회원은
     * 일정은 볼 수 있지만 이동 상태 집합에는 들어가지 않는다.
     */
    fun travelMemberIds(schedule: Schedule): List<Long> {
        if (!isRouteSchedule(schedule)) return emptyList()
        val ids = linkedSetOf(schedule.memberId)
        val scheduleId = requireNotNull(schedule.id)

        scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(scheduleId, ScheduleShareStatus.ACTIVE)
            .filter { it.contentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL }
            .mapTo(ids) { it.targetMemberId }

        // Legacy category sharing always behaved as travel collaboration. It remains in the union
        // until production backfill and the client migration have both been verified.
        categoryId(schedule)?.let { categoryId ->
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(categoryId, ScheduleShareStatus.ACTIVE)
                .mapTo(ids) { it.targetMemberId }
        }

        val calendarId = schedule.calendarId
        if (calendarId != null) {
            val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId)
            val effectiveMode = schedule.calendarContentModeOverride ?: calendar?.defaultContentMode
            if (calendar != null && effectiveMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL) {
                calendarMemberRepository
                    .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                    .mapTo(ids) { it.memberId }
            }
        }
        return ids.toList()
    }

    /**
     * 캘린더 멤버는 자신의 D-3 알림을 끌 수 있다. 같은 일정에 직접 공유나 legacy 카테고리
     * 공유가 겹치면 그 grant에는 별도 opt-out이 없으므로 활성 경로 공유를 우선한다.
     */
    fun routeReminderEnabled(memberId: Long, schedule: Schedule): Boolean {
        if (schedule.memberId == memberId) {
            // 개인 일정에는 캘린더별 수신 설정이 없으므로 기존 동작대로 알림을 허용한다.
            // 공유 캘린더 일정의 오너도 멤버이므로, 화면에서 끈 개인 설정을 동일하게 존중한다.
            val calendarId = schedule.calendarId ?: return true
            return calendarMemberRepository.findByCalendarIdAndMemberId(calendarId, memberId)
                ?.takeIf(::isActive)
                ?.routeReminderEnabled
                ?: true
        }
        val scheduleId = requireNotNull(schedule.id)
        val directTravel = scheduleShareRepository
            .findByScheduleIdAndTargetMemberId(scheduleId, memberId)
            ?.takeIf(::isActive)
            ?.contentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL
        if (directTravel) return true

        val categoryTravel = categoryId(schedule)
            ?.let { categoryShareRepository.findByCategoryIdAndTargetMemberId(it, memberId) }
            ?.let(::isActive) == true
        if (categoryTravel) return true

        val calendarId = schedule.calendarId ?: return false
        val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId) ?: return false
        val effectiveMode = schedule.calendarContentModeOverride ?: calendar.defaultContentMode
        if (effectiveMode != ScheduleShareContentMode.SCHEDULE_AND_TRAVEL) return false
        return calendarMemberRepository.findByCalendarIdAndMemberId(calendarId, memberId)
            ?.takeIf(::isActive)
            ?.routeReminderEnabled == true
    }

    /**
     * D-3 스캐너용 배치 계산. 공유 캘린더에 경로 일정이 100개 있어도 grant 저장소 조회는
     * 종류별 한 번이며, 결과만 일정 id별로 조합한다. 캘린더 알림을 끈 멤버도 직접/카테고리
     * 이동 공유가 겹치면 해당 grant에 따라 포함된다.
     */
    fun routeReminderMemberIdsAll(schedules: Collection<Schedule>): Map<Long, List<Long>> {
        val routeSchedules = schedules.filter(::isRouteSchedule)
        if (routeSchedules.isEmpty()) return emptyMap()
        val scheduleIds = routeSchedules.mapNotNull { it.id }
        val categoryIds = routeSchedules.mapNotNull(::categoryId).distinct()
        val calendarIds = routeSchedules.mapNotNull { it.calendarId }.distinct()

        val directByScheduleId = scheduleShareRepository
            .findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                scheduleIds,
                ScheduleShareStatus.ACTIVE,
            )
            .filter { it.contentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL }
            .groupBy { it.scheduleId }
        val categoryById = if (categoryIds.isEmpty()) {
            emptyMap()
        } else {
            categoryShareRepository
                .findAllByCategoryIdInAndStatusAndDeletedFalseOrderByCategoryIdAscIdAsc(
                    categoryIds,
                    ScheduleShareStatus.ACTIVE,
                )
                .groupBy { it.categoryId }
        }
        val calendarsById = calendarRepository.findAllById(calendarIds)
            .filter(::isActive)
            .associateBy { requireNotNull(it.id) }
        val calendarMembersById = if (calendarIds.isEmpty()) {
            emptyMap()
        } else {
            calendarMemberRepository
                .findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(calendarIds)
                .groupBy { it.calendarId }
        }

        return routeSchedules.associate { schedule ->
            val scheduleId = requireNotNull(schedule.id)
            val memberIds = linkedSetOf<Long>()
            val calendarId = schedule.calendarId
            val calendarMembers = calendarId?.let(calendarMembersById::get).orEmpty()
            val ownerReminderEnabled = if (calendarId == null) {
                true
            } else {
                calendarMembers
                    .firstOrNull { it.memberId == schedule.memberId }
                    ?.routeReminderEnabled
                    ?: true
            }
            if (ownerReminderEnabled) memberIds += schedule.memberId
            directByScheduleId[scheduleId].orEmpty().mapTo(memberIds) { it.targetMemberId }
            categoryId(schedule)?.let { sharedCategoryId ->
                categoryById[sharedCategoryId].orEmpty().mapTo(memberIds) { it.targetMemberId }
            }
            calendarId?.let {
                val calendar = calendarsById[calendarId]
                val mode = schedule.calendarContentModeOverride ?: calendar?.defaultContentMode
                if (calendar != null && mode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL) {
                    calendarMembers
                        .filter { it.routeReminderEnabled }
                        .mapTo(memberIds) { it.memberId }
                }
            }
            scheduleId to memberIds.toList()
        }
    }

    private fun decide(
        schedule: Schedule,
        direct: ScheduleShare?,
        category: ScheduleCategoryShare?,
        calendarMember: ScheduleCalendarMember?,
        calendar: ScheduleCalendar?,
    ): ScheduleAccessDecision {
        val calendarGrantActive = calendarMember != null && calendar != null
        val canView = direct != null || category != null || calendarGrantActive
        if (!canView) return NO_ACCESS

        val directPermission = direct?.permission
        val categoryPermission = category?.permission
        val calendarPermission = calendarMember?.role?.toSharePermission()
        val permission = strongestPermission(directPermission, categoryPermission, calendarPermission)

        val calendarMode = if (calendarGrantActive) {
            schedule.calendarContentModeOverride ?: calendar?.defaultContentMode
        } else {
            null
        }
        val legacyCategoryMode = category?.let { ScheduleShareContentMode.SCHEDULE_AND_TRAVEL }
        val contentMode = listOf(direct?.contentMode, legacyCategoryMode, calendarMode)
            .fold(null as ScheduleShareContentMode?) { current, next ->
                ScheduleShareContentMode.widest(current, next)
            }
        val privileged = permission in setOf(ScheduleSharePermission.EDITOR, ScheduleSharePermission.OWNER)

        return ScheduleAccessDecision(
            canView = true,
            canEdit = privileged,
            travelEnabled = isRouteSchedule(schedule) &&
                contentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            canViewAllTravelPlans = privileged,
            effectivePermission = permission,
            effectiveContentMode = contentMode,
            calendarRole = calendarMember?.role,
        )
    }

    private fun ownerDecision(schedule: Schedule) = ScheduleAccessDecision(
        canView = true,
        canEdit = true,
        travelEnabled = isRouteSchedule(schedule),
        canViewAllTravelPlans = true,
        effectivePermission = ScheduleSharePermission.OWNER,
        effectiveContentMode = if (isRouteSchedule(schedule)) {
            ScheduleShareContentMode.SCHEDULE_AND_TRAVEL
        } else {
            ScheduleShareContentMode.SCHEDULE_ONLY
        },
        calendarRole = ScheduleCalendarRole.OWNER,
    )

    private fun isRouteSchedule(schedule: Schedule): Boolean =
        schedule.scheduleType == ScheduleType.ROUTE || schedule.route != null || schedule.routeSetupRequired

    private fun categoryId(schedule: Schedule): Long? =
        schedule.categoryId ?: schedule.categorySnapshot?.categoryId?.toLongOrNull()

    private fun isActive(share: ScheduleShare): Boolean =
        !share.deleted && share.status == ScheduleShareStatus.ACTIVE

    private fun isActive(share: ScheduleCategoryShare): Boolean =
        !share.deleted && share.status == ScheduleShareStatus.ACTIVE

    private fun isActive(member: ScheduleCalendarMember): Boolean =
        !member.deleted && member.status == ScheduleCalendarMemberStatus.ACTIVE

    private fun isActive(calendar: ScheduleCalendar): Boolean =
        !calendar.deleted && calendar.status == ScheduleCalendarStatus.ACTIVE

    private fun ScheduleCalendarRole.toSharePermission(): ScheduleSharePermission = when (this) {
        ScheduleCalendarRole.VIEWER -> ScheduleSharePermission.VIEWER
        ScheduleCalendarRole.EDITOR -> ScheduleSharePermission.EDITOR
        ScheduleCalendarRole.OWNER -> ScheduleSharePermission.OWNER
    }

    private fun strongestPermission(vararg permissions: ScheduleSharePermission?): ScheduleSharePermission? =
        permissions.filterNotNull().maxByOrNull {
            when (it) {
                ScheduleSharePermission.VIEWER -> 0
                ScheduleSharePermission.COMMENTER -> 1
                ScheduleSharePermission.EDITOR -> 2
                ScheduleSharePermission.OWNER -> 3
            }
        }

    companion object {
        private val NO_ACCESS = ScheduleAccessDecision(
            canView = false,
            canEdit = false,
            travelEnabled = false,
            canViewAllTravelPlans = false,
        )
    }
}
