package com.noLate.schedule.application.service

import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.sharing.application.SharingBlockPolicy
import org.springframework.stereotype.Component

data class ScheduleAccessDecision(
    val canView: Boolean,
    val canEdit: Boolean,
    val travelEnabled: Boolean,
    val canViewAllTravelPlans: Boolean,
    val effectivePermission: ScheduleSharePermission? = null,
    val effectiveContentMode: ScheduleShareContentMode? = null,
    val categoryPermission: ScheduleSharePermission? = null,
    val calendarRole: ScheduleCalendarRole? = null,
    /** 현재 container 기준 소유자. schedules.memberId(작성자)와 다를 수 있다. */
    val resourceOwnerMemberId: Long? = null,
    val canManageAudience: Boolean = false,
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
    private val categoryRepository: ScheduleCategoryRepository? = null,
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
    private val sharingBlockPolicy: SharingBlockPolicy? = null,
) {
    /**
     * 보조 travel/participant 서비스도 dormant grant를 포함하는 native detail query를
     * 선택하지 않도록 중앙 availability 결정을 그대로 노출한다. 별도 설정 해석은 하지 않는다.
     */
    fun isSharingDisabled(): Boolean = !sharingAvailabilityPolicy.enabled

    fun resolve(memberId: Long, schedule: Schedule): ScheduleAccessDecision =
        resolve(memberId, schedule, enforceOwnerBlock = true)

    private fun resolve(
        memberId: Long,
        schedule: Schedule,
        enforceOwnerBlock: Boolean,
    ): ScheduleAccessDecision {
        val category = categoryLineage(schedule)
        if (schedule.categoryId != null && categoryRepository != null && category == null) return NO_ACCESS
        val activeCategoryId = category?.id?.takeIf { !category.deleted }
        val effectiveCalendarId = schedule.calendarId ?: category?.calendarId
        val calendar = effectiveCalendarId
            ?.let { calendarRepository.findByIdAndStatusAndDeletedFalse(it) }
            ?.takeIf(::isActive)
        if (effectiveCalendarId != null && calendar == null) return NO_ACCESS
        val resourceOwnerMemberId = resourceOwnerMemberId(schedule, category, calendar)
        if (resourceOwnerMemberId == memberId) return ownerDecision(schedule, resourceOwnerMemberId)
        if (!sharingAvailabilityPolicy.enabled) return NO_ACCESS
        if (enforceOwnerBlock &&
            sharingBlockPolicy?.isInteractionBlocked(memberId, resourceOwnerMemberId) == true
        ) {
            return NO_ACCESS
        }

        val scheduleId = requireNotNull(schedule.id)
        val direct = scheduleShareRepository.findByScheduleIdAndTargetMemberId(scheduleId, memberId)
            ?.takeIf(::isActive)
        val categoryShare = activeCategoryId
            ?.let { categoryShareRepository.findByCategoryIdAndTargetMemberId(it, memberId) }
            ?.takeIf(::isActive)
        val calendarMember = effectiveCalendarId
            ?.let { calendarMemberRepository.findByCalendarIdAndMemberId(it, memberId) }
            ?.takeIf(::isActive)

        return decide(
            schedule,
            direct,
            categoryShare,
            calendarMember,
            calendar,
            resourceOwnerMemberId,
        )
    }

    /**
     * 목록 API가 일정마다 네 저장소를 다시 조회하지 않도록 공유 정보를 회원 단위로 한 번씩
     * 읽는다. 캘린더에 일정이 100개 있어도 direct/category/member/calendar 조회 횟수는 일정
     * 개수에 비례하지 않는다.
     */
    fun resolveAll(memberId: Long, schedules: Collection<Schedule>): Map<Long, ScheduleAccessDecision> =
        resolveAll(memberId, schedules, enforceOwnerBlock = true)

    /**
     * 차단으로 dormant 처리할 개인 이동계획을 판별할 때만 사용한다. 이 결과는 API 접근 허용에
     * 쓰면 안 되며, 실제 접근 경계는 항상 [resolve] 또는 [resolveAll]로 다시 검사해야 한다.
     */
    internal fun resolveAllIgnoringOwnerBlock(
        memberId: Long,
        schedules: Collection<Schedule>,
    ): Map<Long, ScheduleAccessDecision> =
        resolveAll(memberId, schedules, enforceOwnerBlock = false)

    private fun resolveAll(
        memberId: Long,
        schedules: Collection<Schedule>,
        enforceOwnerBlock: Boolean,
    ): Map<Long, ScheduleAccessDecision> {
        if (schedules.isEmpty()) return emptyMap()
        if (!sharingAvailabilityPolicy.enabled) {
            return schedules.mapNotNull { schedule ->
                val scheduleId = schedule.id ?: return@mapNotNull null
                scheduleId to resolve(memberId, schedule, enforceOwnerBlock)
            }.toMap()
        }

        val directBySchedule = scheduleShareRepository
            .findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(memberId, ScheduleShareStatus.ACTIVE)
            .associateBy { it.scheduleId }
        val categoryById = categoryShareRepository
            .findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(memberId, ScheduleShareStatus.ACTIVE)
            .associateBy { it.categoryId }
        val categoryIds = schedules.mapNotNull(::rawCategoryId).distinct()
        val categoriesById = categoryRepository
            ?.findAllById(categoryIds)
            ?.associateBy { requireNotNull(it.id) }
            .orEmpty()
        val calendarMembershipById = calendarMemberRepository
            .findAllByMemberIdAndStatusAndDeletedFalseOrderByIdAsc(memberId)
            .associateBy { it.calendarId }
        val effectiveCalendarIds = schedules.mapNotNull { schedule ->
            schedule.calendarId ?: categoryLineage(schedule, categoriesById)?.calendarId
        }
        val calendarsById = calendarRepository.findAllById(
            (calendarMembershipById.keys + effectiveCalendarIds).distinct(),
        )
            .filter(::isActive)
            .associateBy { requireNotNull(it.id) }
        val resourceOwnerIds = schedules.map { schedule ->
            val category = categoryLineage(schedule, categoriesById)
            val calendar = (schedule.calendarId ?: category?.calendarId)?.let(calendarsById::get)
            resourceOwnerMemberId(schedule, category, calendar)
        }
        val blockedOwnerIds = if (enforceOwnerBlock) {
            sharingBlockPolicy
                ?.blockedCounterpartIds(memberId, resourceOwnerIds)
                .orEmpty()
        } else {
            emptySet()
        }

        return schedules.mapNotNull { schedule ->
            val scheduleId = schedule.id ?: return@mapNotNull null
            val category = categoryLineage(schedule, categoriesById)
            val activeCategoryId = category?.id?.takeIf { !category.deleted }
            val effectiveCalendarId = schedule.calendarId ?: category?.calendarId
            val calendar = effectiveCalendarId?.let(calendarsById::get)
            val resourceOwnerMemberId = resourceOwnerMemberId(schedule, category, calendar)
            val parentUnavailable =
                (schedule.categoryId != null && categoryRepository != null && category == null) ||
                    (effectiveCalendarId != null && calendar == null)
            val decision = if (parentUnavailable) {
                NO_ACCESS
            } else if (resourceOwnerMemberId == memberId) {
                ownerDecision(schedule, resourceOwnerMemberId)
            } else if (resourceOwnerMemberId in blockedOwnerIds) {
                NO_ACCESS
            } else {
                val membership = effectiveCalendarId?.let(calendarMembershipById::get)
                decide(
                    schedule = schedule,
                    direct = directBySchedule[scheduleId],
                    category = activeCategoryId?.let(categoryById::get),
                    calendarMember = membership,
                    calendar = calendar,
                    resourceOwnerMemberId = resourceOwnerMemberId,
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
        if (!sharingAvailabilityPolicy.enabled) {
            val ownerMemberId = currentResourceOwnerMemberId(schedule) ?: return emptyList()
            return if (resolve(ownerMemberId, schedule).travelEnabled) {
                listOf(ownerMemberId)
            } else {
                emptyList()
            }
        }

        val category = categoryLineage(schedule)
        val calendar = (schedule.calendarId ?: category?.calendarId)
            ?.let { calendarRepository.findByIdAndStatusAndDeletedFalse(it) }
            ?.takeIf(::isActive)
        val ids = linkedSetOf(resourceOwnerMemberId(schedule, category, calendar))
        val scheduleId = requireNotNull(schedule.id)

        scheduleShareRepository
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(scheduleId, ScheduleShareStatus.ACTIVE)
            .filter { it.contentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL }
            .mapTo(ids) { it.targetMemberId }

        // Legacy category sharing always behaved as travel collaboration. It remains in the union
        // until production backfill and the client migration have both been verified.
        category?.id?.takeIf { !category.deleted }?.let { categoryId ->
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(categoryId, ScheduleShareStatus.ACTIVE)
                .mapTo(ids) { it.targetMemberId }
        }

        val calendarId = schedule.calendarId
        if (calendarId != null) {
            val calendarForMode = calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId)
            val effectiveMode = schedule.calendarContentModeOverride ?: calendarForMode?.defaultContentMode
            if (calendarForMode != null && effectiveMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL) {
                calendarMemberRepository
                    .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                    .mapTo(ids) { it.memberId }
            }
        }
        // Creator ids are not privileged after a category/calendar grant is revoked. Reusing the
        // central decision here prevents stale travel jobs from treating schedules.member_id as a
        // permanent ownership grant.
        return ids.filter { candidateMemberId ->
            resolve(candidateMemberId, schedule).travelEnabled
        }
    }

    /**
     * Schedule PUT may detach or move a schedule away from its current calendar. Cleanup must
     * freeze the whole current calendar audience, including members whose stale notification rows
     * outlive a previous content-mode change, before it changes the schedule's calendar id.
     */
    fun activeCalendarMemberIds(calendarId: Long): List<Long> {
        if (!sharingAvailabilityPolicy.enabled) return emptyList()

        val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(
                calendarId,
                ScheduleCalendarStatus.ACTIVE,
            ) ?: return emptyList()
        val memberIds = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
            .map { it.memberId }
        return filterBlockedAudience(calendar.ownerMemberId, memberIds)
    }

    /**
     * 캘린더 멤버는 자신의 D-3 알림을 끌 수 있다. 같은 일정에 직접 공유나 legacy 카테고리
     * 공유가 겹치면 그 grant에는 별도 opt-out이 없으므로 활성 경로 공유를 우선한다.
     */
    fun routeReminderEnabled(memberId: Long, schedule: Schedule): Boolean {
        val access = resolve(memberId, schedule)
        if (!access.travelEnabled) return false

        val scheduleId = requireNotNull(schedule.id)
        val directTravel = scheduleShareRepository
            .findByScheduleIdAndTargetMemberId(scheduleId, memberId)
            ?.takeIf(::isActive)
            ?.contentMode == ScheduleShareContentMode.SCHEDULE_AND_TRAVEL
        if (directTravel) return true

        val categoryTravel = rawCategoryId(schedule)
            ?.let { categoryShareRepository.findByCategoryIdAndTargetMemberId(it, memberId) }
            ?.let(::isActive) == true
        if (categoryTravel) return true

        val category = categoryLineage(schedule)
        val calendarId = schedule.calendarId ?: category?.calendarId
            ?: return access.resourceOwnerMemberId == memberId
        val calendar = calendarRepository.findByIdAndStatusAndDeletedFalse(calendarId) ?: return false
        val effectiveMode = schedule.calendarContentModeOverride ?: calendar.defaultContentMode
        if (effectiveMode != ScheduleShareContentMode.SCHEDULE_AND_TRAVEL) return false
        return calendarMemberRepository.findByCalendarIdAndMemberId(calendarId, memberId)
            ?.takeIf(::isActive)
            ?.routeReminderEnabled
            ?: (access.resourceOwnerMemberId == memberId)
    }

    /**
     * D-3 스캐너용 배치 계산. 공유 캘린더에 경로 일정이 100개 있어도 grant 저장소 조회는
     * 종류별 한 번이며, 결과만 일정 id별로 조합한다. 캘린더 알림을 끈 멤버도 직접/카테고리
     * 이동 공유가 겹치면 해당 grant에 따라 포함된다.
     */
    fun routeReminderMemberIdsAll(schedules: Collection<Schedule>): Map<Long, List<Long>> {
        val routeSchedules = schedules.filter(::isRouteSchedule)
        if (routeSchedules.isEmpty()) return emptyMap()
        if (!sharingAvailabilityPolicy.enabled) {
            return routeSchedules.associate { schedule ->
                val ownerMemberId = currentResourceOwnerMemberId(schedule)
                requireNotNull(schedule.id) to if (
                    ownerMemberId != null && routeReminderEnabled(ownerMemberId, schedule)
                ) {
                    listOf(ownerMemberId)
                } else {
                    emptyList()
                }
            }
        }

        val scheduleIds = routeSchedules.mapNotNull { it.id }
        val categoryIds = routeSchedules.mapNotNull(::rawCategoryId).distinct()
        val categoriesById = categoryRepository
            ?.findAllById(categoryIds)
            ?.associateBy { requireNotNull(it.id) }
            .orEmpty()
        val calendarIds = routeSchedules.mapNotNull { schedule ->
            schedule.calendarId ?: categoryLineage(schedule, categoriesById)?.calendarId
        }.distinct()

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
        val allCandidateMemberIds = buildSet {
            directByScheduleId.values.flatten().forEach { add(it.targetMemberId) }
            categoryById.values.flatten().forEach { add(it.targetMemberId) }
            calendarMembersById.values.flatten().forEach { add(it.memberId) }
            routeSchedules.forEach { schedule ->
                val category = categoryLineage(schedule, categoriesById)
                val calendar = (schedule.calendarId ?: category?.calendarId)?.let(calendarsById::get)
                add(resourceOwnerMemberId(schedule, category, calendar))
            }
        }
        val ownerByScheduleId = routeSchedules.associate { schedule ->
            val category = categoryLineage(schedule, categoriesById)
            val calendar = (schedule.calendarId ?: category?.calendarId)?.let(calendarsById::get)
            requireNotNull(schedule.id) to resourceOwnerMemberId(schedule, category, calendar)
        }
        val blockedByOwner = ownerByScheduleId.values.distinct().associateWith { ownerMemberId ->
            sharingBlockPolicy
                ?.blockedCounterpartIds(ownerMemberId, allCandidateMemberIds)
                .orEmpty()
        }

        return routeSchedules.associate { schedule ->
            val scheduleId = requireNotNull(schedule.id)
            val memberIds = linkedSetOf<Long>()
            val category = categoryLineage(schedule, categoriesById)
            val calendarId = schedule.calendarId ?: category?.calendarId
            val parentUnavailable =
                (schedule.categoryId != null && category == null) ||
                    (calendarId != null && calendarsById[calendarId] == null)
            if (parentUnavailable) {
                return@associate scheduleId to emptyList()
            }
            val calendarMembers = calendarId?.let(calendarMembersById::get).orEmpty()
            val resourceOwnerMemberId = ownerByScheduleId.getValue(scheduleId)
            val ownerReminderEnabled = if (calendarId == null) {
                true
            } else {
                calendarMembers
                    .firstOrNull { it.memberId == resourceOwnerMemberId }
                    ?.routeReminderEnabled
                    ?: true
            }
            if (ownerReminderEnabled) memberIds += resourceOwnerMemberId
            directByScheduleId[scheduleId].orEmpty().mapTo(memberIds) { it.targetMemberId }
            category?.id?.takeIf { !category.deleted }?.let { sharedCategoryId ->
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
            memberIds.removeAll(blockedByOwner[resourceOwnerMemberId].orEmpty())
            scheduleId to memberIds.toList()
        }
    }

    private fun filterBlockedAudience(
        ownerMemberId: Long,
        memberIds: Collection<Long>,
    ): List<Long> {
        val blocked = sharingBlockPolicy
            ?.blockedCounterpartIds(ownerMemberId, memberIds)
            .orEmpty()
        return memberIds.filter { it == ownerMemberId || it !in blocked }
    }

    private fun decide(
        schedule: Schedule,
        direct: ScheduleShare?,
        category: ScheduleCategoryShare?,
        calendarMember: ScheduleCalendarMember?,
        calendar: ScheduleCalendar?,
        resourceOwnerMemberId: Long,
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
            categoryPermission = categoryPermission,
            calendarRole = calendarMember?.role,
            resourceOwnerMemberId = resourceOwnerMemberId,
            canManageAudience = false,
        )
    }

    private fun ownerDecision(
        schedule: Schedule,
        resourceOwnerMemberId: Long,
    ) = ScheduleAccessDecision(
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
        resourceOwnerMemberId = resourceOwnerMemberId,
        canManageAudience = true,
    )

    private fun isRouteSchedule(schedule: Schedule): Boolean =
        schedule.scheduleType == ScheduleType.ROUTE || schedule.route != null || schedule.routeSetupRequired

    private fun rawCategoryId(schedule: Schedule): Long? =
        schedule.categoryId ?: schedule.categorySnapshot?.categoryId?.toLongOrNull()

    /**
     * A deleted category is closed for category-level grants and new assignments, but its row is
     * retained as ownership/calendar lineage for existing schedules. This preserves the category
     * owner's and calendar/direct-schedule audiences while preventing a tombstoned category share
     * from remaining an access grant.
     */
    private fun categoryLineage(schedule: Schedule): ScheduleCategory? {
        val categoryId = rawCategoryId(schedule) ?: return null
        val category = categoryRepository
            ?.findById(categoryId)
            ?.orElse(null)
        // categoryId=null means the canonical association was deliberately detached. A remaining
        // deleted/missing snapshot is display history, not an ACL parent. Active snapshot fallback
        // is retained only for legacy rows created before schedules.category_id was populated.
        return if (schedule.categoryId == null) category?.takeUnless { it.deleted } else category
    }

    private fun categoryLineage(
        schedule: Schedule,
        categoriesById: Map<Long, ScheduleCategory>,
    ): ScheduleCategory? {
        val categoryId = rawCategoryId(schedule) ?: return null
        val category = categoriesById[categoryId]
        return if (schedule.categoryId == null) category?.takeUnless { it.deleted } else category
    }

    fun currentResourceOwnerMemberId(schedule: Schedule): Long? {
        val category = categoryLineage(schedule)
        if (schedule.categoryId != null && categoryRepository != null && category == null) return null
        val calendarId = schedule.calendarId ?: category?.calendarId
        val calendar = calendarId?.let {
            calendarRepository.findByIdAndStatusAndDeletedFalse(it)
                ?.takeIf(::isActive)
                ?: return null
        }
        return resourceOwnerMemberId(schedule, category, calendar)
    }

    private fun resourceOwnerMemberId(
        schedule: Schedule,
        category: ScheduleCategory?,
        calendar: ScheduleCalendar?,
    ): Long = when {
        calendar != null -> calendar.ownerMemberId
        category != null && category.calendarId == null -> category.memberId
        else -> schedule.memberId
    }

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
