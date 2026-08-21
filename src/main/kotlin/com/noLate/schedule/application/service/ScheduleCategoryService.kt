package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheAudienceResolver
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategorySettingDto
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCalendarStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.sharing.application.SharingBlockPolicy
import jakarta.transaction.Transactional
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service

@Service
class ScheduleCategoryService(
    private val categoryRepository: ScheduleCategoryRepository,
    private val memberRepository: MemberRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository? = null,
    private val invitationRepository: ScheduleShareInvitationRepository? = null,
    private val travelAccessCleanupService: ScheduleTravelAccessCleanupService? = null,
    private val categoryDeleteCoordinator: ScheduleCategoryDeleteCoordinator? = null,
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { _ -> },
    private val sharingAvailabilityPolicy: ScheduleSharingAvailabilityPolicy,
    private val sharingBlockPolicy: SharingBlockPolicy? = null,
    private val calendarRepository: ScheduleCalendarRepository? = null,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository? = null,
    private val scheduleRepository: ScheduleRepository? = null,
    private val calendarCacheAudienceResolver: ScheduleCalendarCacheAudienceResolver? = null,
) {
    private val defaultCategories = listOf(
        DefaultScheduleCategory("업무", "#f44336", "briefcase-outline"),
        DefaultScheduleCategory("개인", "#2196f3", "person-outline"),
        DefaultScheduleCategory("기타", "#4caf50", "ellipsis-horizontal-outline"),
    )
    private val colorPalette = listOf(
        "#f44336",
        "#ff9500",
        "#4caf50",
        "#2196f3",
        "#5856d6",
        "#af52de",
        "#ff2d55",
    )

    @Transactional
    fun getCategories(
        memberId: Long,
        presentedSessionGeneration: Long,
    ): List<ScheduleCategorySettingDto> {
        lockMutationMembers(memberId, presentedSessionGeneration)
        return getCategoriesAfterFence(memberId)
    }

    private fun getCategoriesAfterFence(memberId: Long): List<ScheduleCategorySettingDto> {
        ensureDefaultCategories(memberId)
        val visibleCategories = if (sharingAvailabilityPolicy.enabled) {
            categoryRepository.findVisibleCategories(memberId)
        } else {
            // 공유 row는 재활성화 가능한 dormant 상태로 남겨 두되, off 동안에는 그 grant를
            // 조회 조건에 사용하지 않아 다른 회원의 카테고리 UGC가 응답에 섞이지 않게 한다.
            categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
                .filter { it.calendarId == null }
        }
        val calendarIds = visibleCategories.mapNotNull { it.calendarId }.distinct()
        val activeCalendars = calendarRepository
            ?.findAllById(calendarIds)
            ?.filter { !it.deleted && it.status == ScheduleCalendarStatus.ACTIVE }
            ?.associateBy { requireNotNull(it.id) }
            .orEmpty()
        val calendarRoles = calendarMemberRepository
            ?.findAllByCalendarIdInAndStatusAndDeletedFalseOrderByCalendarIdAscIdAsc(calendarIds)
            ?.filter { it.memberId == memberId }
            ?.associate { it.calendarId to it.role }
            .orEmpty()
        val blockedOwnerIds = sharingBlockPolicy
            ?.blockedCounterpartIds(
                memberId,
                visibleCategories.map { category ->
                    category.calendarId?.let(activeCalendars::get)?.ownerMemberId ?: category.memberId
                },
            )
            .orEmpty()
        val allowedCategories = visibleCategories.filter {
            val calendarId = it.calendarId
            if (calendarId != null && calendarRepository != null && calendarMemberRepository != null) {
                val calendar = activeCalendars[calendarId]
                calendar != null && calendarRoles[calendarId] != null &&
                    (calendar.ownerMemberId == memberId || calendar.ownerMemberId !in blockedOwnerIds)
            } else {
                it.memberId == memberId || it.memberId !in blockedOwnerIds
            }
        }
        val hasReceivedCategory = allowedCategories.any { it.memberId != memberId }
        val permissionByCategoryId = if (hasReceivedCategory) {
            categoryShareRepository
                ?.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                    targetMemberId = memberId,
                    status = ScheduleShareStatus.ACTIVE,
                )
                ?.associate { it.categoryId to it.permission }
                .orEmpty()
        } else {
            emptyMap()
        }

        return allowedCategories
            .map { category ->
                val calendarRole = category.calendarId?.let(calendarRoles::get)
                val shared = category.calendarId != null || category.memberId != memberId
                category.toDto().copy(
                    shared = shared,
                    // The visibility query proves access. If a legacy service
                    // instance has no share repository, fall back to the least
                    // privileged label rather than falsely claiming edit access.
                    sharePermission = if (calendarRole != null) {
                        calendarRole.toCategoryPermission()
                    } else if (shared) {
                        category.id?.let(permissionByCategoryId::get)
                            ?: ScheduleSharePermission.VIEWER
                    } else {
                        null
                    },
                )
            }
    }

    @Transactional
    fun createCategory(
        memberId: Long,
        title: String?,
        color: String?,
        iconKey: String?,
        sortOrder: Int?,
        presentedSessionGeneration: Long,
        calendarId: Long? = null,
    ): ScheduleCategorySettingDto {
        lockMutationMembers(memberId, presentedSessionGeneration)
        val calendarRole = calendarId?.let { requireWritableCalendar(memberId, it) }
        val entity = ScheduleCategory(
            memberId = memberId,
            calendarId = calendarId,
            title = normalizeRequiredText(title, "title", maxLength = 80),
            color = normalizeOptionalText(color, maxLength = 32) ?: defaultCategoryColor(memberId, calendarId),
            iconKey = normalizeOptionalText(iconKey, maxLength = 40),
            sortOrder = sortOrder ?: nextCategorySortOrder(memberId, calendarId),
        )

        return categoryRepository.save(entity).toDto().copy(
            shared = calendarId != null,
            sharePermission = calendarRole?.toCategoryPermission(),
        )
    }

    @Transactional
    fun updateCategory(
        memberId: Long,
        categoryId: Long,
        title: String?,
        color: String?,
        iconKey: String?,
        sortOrder: Int?,
        presentedSessionGeneration: Long,
    ): ScheduleCategorySettingDto {
        lockMutationMembers(memberId, presentedSessionGeneration)
        val entity = findCategoryForMutation(memberId, categoryId)
        entity.update(
            title = title?.let { normalizeRequiredText(it, "title", maxLength = 80) } ?: entity.title,
            color = normalizeOptionalText(color, maxLength = 32) ?: entity.color,
            iconKey = if (iconKey != null) normalizeOptionalText(iconKey, maxLength = 40) else entity.iconKey,
            sortOrder = sortOrder ?: entity.sortOrder,
        )

        return categoryRepository.save(entity).toDto().copy(
            shared = entity.calendarId != null,
            sharePermission = entity.calendarId
                ?.let { calendarMemberRepository?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(it, memberId) }
                ?.role
                ?.toCategoryPermission(),
        )
    }

    @Transactional
    fun previewMove(
        memberId: Long,
        categoryId: Long,
        calendarId: Long,
        presentedSessionGeneration: Long,
    ): ScheduleCategoryMovePreviewDto {
        // Calendar mutations use member -> calendar globally. Keeping the same order here avoids
        // a preview holding the destination calendar while an add/remove/archive path holds this
        // member row and waits for that calendar.
        lockMutationMembers(memberId, presentedSessionGeneration)
        val access = requireWritableCalendarAccess(memberId, calendarId)
        val source = categoryRepository.findOwnedActiveForShareUpdate(categoryId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        requireMovablePersonalCategory(source, memberId)

        val schedules = requireScheduleRepository()
            .findAllByCategoryIdIncludingSnapshotAndDeletedFalseOrderByIdAsc(categoryId)
        requireNoForeignOwnedSchedules(schedules, memberId)
        val sameNameCategory = categoryRepository
            .findFirstByCalendarIdAndTitleIgnoreCaseAndDeletedFalseOrderByIdAsc(calendarId, source.title)

        return ScheduleCategoryMovePreviewDto(
            sourceCategory = source.toDto(),
            destinationCalendarId = calendarId,
            destinationCalendarTitle = access.calendar.title,
            activeScheduleCount = schedules.count { it.memberId == memberId },
            sameNameCategory = sameNameCategory?.toDto()?.copy(
                shared = true,
                sharePermission = access.role.toCategoryPermission(),
            ),
        )
    }

    /**
     * Moves one private category and every active schedule linked to it as one transaction.
     *
     * The initial schedule/share reads only discover the lock set. Every authoritative row is
     * re-read after its parent lock, and a participant expansion fails with a retryable 409 rather
     * than acquiring a newly discovered member lock out of order.
     */
    @Transactional
    fun moveCategory(
        memberId: Long,
        categoryId: Long,
        calendarId: Long,
        mergeIntoCategoryId: Long?,
        presentedSessionGeneration: Long,
    ): ScheduleCategoryMoveResultDto {
        sharingAvailabilityPolicy.requireEnabled()
        val schedules = requireScheduleRepository()
        val shares = requireCategoryShareRepository()
        val invitations = requireInvitationRepository()
        val calendarMembers = requireCalendarMemberRepository()

        // A scalar existence check rejects unrelated category ids without attaching source state
        // before the owner member fence. The locked entity below remains the sole authority.
        if (!categoryRepository.existsByIdAndMemberIdAndCalendarIdIsNullAndDeletedFalse(categoryId, memberId)) {
            throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        }
        val sharePreview = shares.findAllByCategoryIdAndDeletedFalse(categoryId)
        val previewTargetMemberIds = sharePreview.map { it.targetMemberId }.distinct()
        // Scalar previews avoid retaining stale membership roles in the persistence context while
        // waiting for member locks. Authorization is evaluated from fresh state after calendar lock.
        val destinationMemberPreviewIds = calendarMembers.findActiveMemberIdsByCalendarId(calendarId)

        // All related writers begin with member rows. In particular, normal schedule edits use
        // member -> push-job -> schedule -> calendar, while calendar mutations use member ->
        // calendar. Taking schedule/calendar first here would allow a classic two-row deadlock.
        lockMutationMembers(
            memberId,
            presentedSessionGeneration,
            previewTargetMemberIds + destinationMemberPreviewIds,
        )

        // Re-discover after the member fence: an owner/editor creation that committed while this
        // transaction waited must be included before any schedule row is locked. Once the known
        // writer members are held, the set can only expand through a concurrently committed new
        // share, which is rejected by the authoritative participant check below.
        val scheduleIdsAfterMemberFence = schedules
            .findActiveIdsByCategoryIdIncludingSnapshotOrderByIdAsc(categoryId)
        val lockedSchedulesById = lockSchedulesById(
            schedules,
            scheduleIdsAfterMemberFence,
        ).associateBy { requireNotNull(it.id) }

        // Schedules precede their destination calendar, matching the schedule-edit move order.
        // The destination member preview is validated again while the calendar parent is locked.
        val access = requireWritableCalendarAccess(memberId, calendarId)
        val destinationMemberIds = calendarMembers.findActiveMemberIdsByCalendarId(calendarId)
        val lockedMemberIds = (previewTargetMemberIds + destinationMemberPreviewIds + memberId).toSet()
        if (destinationMemberIds.any { it !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Calendar membership changed while acquiring the category move fence.",
            )
        }
        // Invitation acceptance serializes on the invitation before granting its category share.
        // Lock pending invitations before the category so an acceptance cannot appear after the
        // authoritative share re-read below.
        val pendingInvitations = invitations.findAllPendingByResourceForUpdate(
            resourceType = ScheduleShareResourceType.CATEGORY,
            resourceId = categoryId,
        )
        val source = categoryRepository.findOwnedActiveForShareUpdate(categoryId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        requireMovablePersonalCategory(source, memberId)

        val currentShares = shares.findAllByCategoryIdForUpdate(categoryId)
        if (currentShares.any { it.targetMemberId !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Category participant set changed while acquiring the move fence.",
            )
        }

        // Holding the category row prevents new ScheduleService assignments. Never acquire a new
        // schedule lock after it: a newly discovered id means the preview fence expanded and the
        // caller must retry from the global member -> schedule order.
        val linkedScheduleIds = schedules
            .findActiveIdsByCategoryIdIncludingSnapshotOrderByIdAsc(categoryId)
            .toSet()
        if (!lockedSchedulesById.keys.containsAll(linkedScheduleIds)) {
            throw ConcurrencyFailureException(
                "Category schedule set changed while acquiring the move fence.",
            )
        }
        val lockedSchedules = linkedScheduleIds
            .sorted()
            .mapNotNull(lockedSchedulesById::get)
            .filter { it.referencesCategory(categoryId) }
        requireNoForeignOwnedSchedules(lockedSchedules, memberId)

        val destinationCategories = categoryRepository.findAllActiveByCalendarIdForUpdate(calendarId)
        val sameNameCategories = destinationCategories.filter { it.title.equals(source.title, ignoreCase = true) }
        val destinationCategory = resolveMoveDestinationCategory(
            source = source,
            destinationCategories = destinationCategories,
            sameNameCategories = sameNameCategories,
            mergeIntoCategoryId = mergeIntoCategoryId,
            calendarId = calendarId,
        )
        val merged = destinationCategory !== source

        val beforeAudience = buildSet {
            add(memberId)
            addAll(currentShares.map { it.targetMemberId })
            lockedSchedules.forEach { schedule ->
                addAll(calendarCacheAudienceResolver?.resolve(schedule).orEmpty())
            }
        }

        currentShares.forEach { it.revoke() }
        shares.saveAllAndFlush(currentShares)
        pendingInvitations.forEach { it.revoke() }

        // Give the cleanup policy the post-move calendar access while the old category link is
        // still queryable. In the merge branch, repointing the snapshot first would make the
        // source-category cleanup query empty and could leave revoked travel jobs behind.
        lockedSchedules.forEach { schedule ->
            schedule.calendarId = calendarId
        }
        schedules.saveAllAndFlush(lockedSchedules)
        travelAccessCleanupService?.cancelRevokedForCategory(
            categoryId,
            currentShares.map { it.targetMemberId },
        )

        lockedSchedules.forEach { schedule ->
            schedule.updateCategorySnapshot(
                categoryId = requireNotNull(destinationCategory.id).toString(),
                title = destinationCategory.title,
                color = destinationCategory.color,
            )
        }
        schedules.saveAll(lockedSchedules)

        if (merged) {
            source.softDelete()
        } else {
            source.moveToCalendar(
                calendarId = calendarId,
                sortOrder = destinationCategories.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0,
            )
        }
        categoryRepository.saveAndFlush(source)
        ensurePersonalCategoryRemains(memberId)

        val afterAudience = buildSet {
            add(memberId)
            addAll(destinationMemberIds)
            lockedSchedules.forEach { schedule ->
                addAll(calendarCacheAudienceResolver?.resolve(schedule).orEmpty())
            }
        }
        publishCategoryInvalidation(beforeAudience + afterAudience, "category-moved-to-calendar")

        return ScheduleCategoryMoveResultDto(
            sourceCategoryId = categoryId.toString(),
            category = destinationCategory.toDto().copy(
                shared = true,
                sharePermission = access.role.toCategoryPermission(),
            ),
            movedScheduleCount = lockedSchedules.size,
            merged = merged,
        )
    }

    @Transactional
    fun deleteCategory(
        memberId: Long,
        categoryId: Long,
        presentedSessionGeneration: Long,
    ) {
        val existingCategory = categoryRepository.findById(categoryId).orElse(null)
            ?.takeUnless { it.deleted }
        if (existingCategory?.calendarId != null) {
            lockMutationMembers(memberId, presentedSessionGeneration)
            val calendarId = requireNotNull(existingCategory.calendarId)
            requireWritableCalendar(memberId, calendarId)
            existingCategory.softDelete()
            categoryRepository.save(existingCategory)
            val affectedMemberIds = calendarMemberRepository
                ?.findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                ?.map { it.memberId }
                .orEmpty()
            publishCategoryInvalidation(affectedMemberIds, "calendar-category-deleted")
            return
        }
        categoryDeleteCoordinator?.let {
            it.delete(memberId, categoryId, presentedSessionGeneration)
            return
        }
        val affectedShares = categoryShareRepository
            ?.findAllByCategoryIdAndDeletedFalse(categoryId)
            .orEmpty()
        val affectedMemberIds = affectedShares.map { it.targetMemberId }
        lockMutationMembers(memberId, presentedSessionGeneration, affectedMemberIds)
        // Parent category is the serialization point for share/invitation creation. Re-read
        // every dependent row only after it is locked; never acquire a newly discovered
        // participant member lock after the category lock.
        val entity = categoryRepository.findOwnedActiveForShareUpdate(categoryId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        val currentShares = categoryShareRepository
            ?.findAllByCategoryIdAndDeletedFalse(categoryId)
            .orEmpty()
        val unprelockedMemberIds = currentShares
            .map { it.targetMemberId }
            .filterNot((affectedMemberIds + memberId).toSet()::contains)
        if (unprelockedMemberIds.isNotEmpty()) {
            throw ConcurrencyFailureException(
                "Category participant set changed while acquiring the mutation fence.",
            )
        }
        currentShares.forEach { it.revoke() }
        invitationRepository
            ?.findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
                ownerMemberId = memberId,
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = categoryId,
            )
            ?.forEach { it.revoke() }
        entity.softDelete()
        categoryRepository.save(entity)
        travelAccessCleanupService?.cancelRevokedForCategory(categoryId, affectedMemberIds)
        if (affectedMemberIds.isNotEmpty()) {
            eventPublisher.publishEvent(
                ScheduleCalendarCacheInvalidationEvent(
                    memberIds = affectedMemberIds.toSet(),
                    reason = "category-deleted",
                )
            )
        }
    }

    @Transactional
    fun reorderCategories(
        memberId: Long,
        items: List<ScheduleCategoryReorderItem>,
        presentedSessionGeneration: Long,
    ): List<ScheduleCategorySettingDto> {
        lockMutationMembers(memberId, presentedSessionGeneration)
        items.forEach { item ->
            val entity = findCategoryForMutation(memberId, item.id)
            entity.sortOrder = item.sortOrder
            categoryRepository.save(entity)
        }

        return getCategoriesAfterFence(memberId)
    }

    /**
     * Account-transition mutations use one global member-row lock order. The actor is
     * generation-checked while every affected recipient is already locked, so category
     * cleanup cannot invert owner/participant locks used by withdrawal or push writers.
     */
    private fun lockMutationMembers(
        actorMemberId: Long,
        presentedSessionGeneration: Long,
        affectedMemberIds: Collection<Long> = emptyList(),
    ) {
        val lockedById = (affectedMemberIds + actorMemberId)
            .distinct()
            .sorted()
            .associateWith(memberRepository::findByIdForUpdate)
        val actor = lockedById[actorMemberId]
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.INVALID_TOKEN, "종료되었거나 존재하지 않는 로그인 세션입니다.")
        if (actor.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }
    }

    private fun ensureDefaultCategories(memberId: Long) {
        val existing = categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
            .filter { it.calendarId == null }
        if (existing.isNotEmpty()) return

        defaultCategories.forEachIndexed { index, category ->
            categoryRepository.save(
                ScheduleCategory(
                    memberId = memberId,
                    title = category.title,
                    color = category.color,
                    iconKey = category.iconKey,
                    sortOrder = index,
                )
            )
        }
    }

    private fun findCategoryForMutation(memberId: Long, categoryId: Long): ScheduleCategory {
        val category = categoryRepository.findByIdAndMemberIdAndDeletedFalse(categoryId, memberId)
            ?: categoryRepository.findById(categoryId).orElse(null)?.takeUnless { it.deleted }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        val calendarId = category.calendarId
        if (calendarId == null) {
            if (category.memberId != memberId) {
                throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
            }
        } else {
            requireWritableCalendar(memberId, calendarId)
        }
        return category
    }

    private fun requireWritableCalendar(memberId: Long, calendarId: Long): ScheduleCalendarRole {
        return requireWritableCalendarAccess(memberId, calendarId).role
    }

    private fun requireWritableCalendarAccess(memberId: Long, calendarId: Long): WritableCalendarAccess {
        sharingAvailabilityPolicy.requireEnabled()
        val calendar = calendarRepository?.findActiveForUpdate(calendarId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        if (calendar.deleted || calendar.status != ScheduleCalendarStatus.ACTIVE) {
            throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        }
        val membership = calendarMemberRepository
            ?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                calendarId,
                memberId,
                ScheduleCalendarMemberStatus.ACTIVE,
            )
            ?: throw BusinessException(ErrorCode.FORBIDDEN, "공유 캘린더 멤버가 아닙니다.")
        if (membership.role !in setOf(ScheduleCalendarRole.OWNER, ScheduleCalendarRole.EDITOR)) {
            throw BusinessException(ErrorCode.FORBIDDEN, "공유 캘린더 카테고리를 편집할 권한이 없습니다.")
        }
        return WritableCalendarAccess(calendar, membership.role)
    }

    private fun requireMovablePersonalCategory(category: ScheduleCategory, memberId: Long) {
        if (category.memberId != memberId) {
            throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        }
        if (category.calendarId != null) {
            throw BusinessException(ErrorCode.INVALID_STATE, "개인 캘린더 카테고리만 이동할 수 있습니다.")
        }
        val categoryId = requireNotNull(category.id)
        if (calendarRepository?.findByLegacyCategoryIdAndDeletedFalse(categoryId) != null) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "기존 공유 캘린더로 전환된 카테고리는 다시 이동할 수 없습니다.",
            )
        }
    }

    private fun requireNoForeignOwnedSchedules(schedules: Collection<Schedule>, memberId: Long) {
        if (schedules.any { it.memberId != memberId }) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "다른 사용자가 만든 일정이 포함된 기존 공유 카테고리는 이동할 수 없습니다.",
            )
        }
    }

    private fun lockSchedulesById(
        schedules: ScheduleRepository,
        scheduleIds: Collection<Long>,
    ): List<Schedule> {
        val normalizedIds = scheduleIds.distinct().sorted()
        if (normalizedIds.isEmpty()) return emptyList()
        return schedules.findAllActiveByIdsForUpdate(normalizedIds)
    }

    private fun resolveMoveDestinationCategory(
        source: ScheduleCategory,
        destinationCategories: List<ScheduleCategory>,
        sameNameCategories: List<ScheduleCategory>,
        mergeIntoCategoryId: Long?,
        calendarId: Long,
    ): ScheduleCategory {
        if (sameNameCategories.isEmpty()) {
            if (mergeIntoCategoryId != null) {
                throw BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "합칠 같은 이름의 카테고리가 대상 캘린더에 없습니다.",
                )
            }
            return source
        }
        if (mergeIntoCategoryId == null) {
            throw BusinessException(
                ErrorCode.INVALID_STATE,
                "대상 캘린더에 같은 이름의 카테고리가 있습니다. 합칠 카테고리를 선택해 주세요.",
            )
        }
        val destination = destinationCategories.firstOrNull { it.id == mergeIntoCategoryId }
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        if (destination.calendarId != calendarId ||
            sameNameCategories.none { it.id == destination.id }
        ) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "같은 이름의 대상 캘린더 카테고리만 합칠 수 있습니다.",
            )
        }
        return destination
    }

    private fun ensurePersonalCategoryRemains(memberId: Long) {
        val personalCategories = categoryRepository
            .findByMemberIdAndCalendarIdIsNullAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
        if (personalCategories.isNotEmpty()) return
        categoryRepository.save(
            ScheduleCategory(
                memberId = memberId,
                title = "개인",
                color = "#2196f3",
                iconKey = "person-outline",
                sortOrder = 0,
            )
        )
    }

    private fun requireScheduleRepository(): ScheduleRepository = scheduleRepository
        ?: throw BusinessException(ErrorCode.INVALID_STATE, "일정 저장소가 준비되지 않았습니다.")

    private fun requireCategoryShareRepository(): ScheduleCategoryShareRepository = categoryShareRepository
        ?: throw BusinessException(ErrorCode.INVALID_STATE, "카테고리 공유 저장소가 준비되지 않았습니다.")

    private fun requireInvitationRepository(): ScheduleShareInvitationRepository = invitationRepository
        ?: throw BusinessException(ErrorCode.INVALID_STATE, "공유 초대 저장소가 준비되지 않았습니다.")

    private fun requireCalendarMemberRepository(): ScheduleCalendarMemberRepository = calendarMemberRepository
        ?: throw BusinessException(ErrorCode.INVALID_STATE, "공유 캘린더 멤버 저장소가 준비되지 않았습니다.")

    private fun nextCategorySortOrder(memberId: Long, calendarId: Long?): Int {
        return if (calendarId == null) {
            categoryRepository.findMaxSortOrder(memberId) + 1
        } else {
            categoryRepository.findMaxSortOrderByCalendarId(calendarId) + 1
        }
    }

    private fun defaultCategoryColor(memberId: Long, calendarId: Long?): String {
        val index = nextCategorySortOrder(memberId, calendarId).coerceAtLeast(0)
        return colorPalette[index % colorPalette.size]
    }

    private fun publishCategoryInvalidation(memberIds: Collection<Long>, reason: String) {
        if (memberIds.isEmpty()) return
        eventPublisher.publishEvent(
            ScheduleCalendarCacheInvalidationEvent(memberIds = memberIds.toSet(), reason = reason)
        )
    }

    private fun normalizeRequiredText(value: String?, field: String, maxLength: Int): String {
        val normalized = value?.trim()
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "$field is required.")

        if (normalized.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$field is required.")
        }
        if (normalized.length > maxLength) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "$field must be $maxLength characters or less.")
        }

        return normalized
    }

    private fun normalizeOptionalText(value: String?, maxLength: Int): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (normalized.length > maxLength) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "text must be $maxLength characters or less.")
        }
        return normalized
    }
}

private fun ScheduleCalendarRole.toCategoryPermission(): ScheduleSharePermission = when (this) {
    ScheduleCalendarRole.OWNER -> ScheduleSharePermission.OWNER
    ScheduleCalendarRole.EDITOR -> ScheduleSharePermission.EDITOR
    ScheduleCalendarRole.VIEWER -> ScheduleSharePermission.VIEWER
}

private fun Schedule.referencesCategory(categoryId: Long): Boolean =
    this.categoryId == categoryId || this.categorySnapshot?.categoryId == categoryId.toString()

private data class WritableCalendarAccess(
    val calendar: ScheduleCalendar,
    val role: ScheduleCalendarRole,
)

/**
 * The first non-locking share snapshot can race a share transaction that already owns the actor
 * member row. A post-category-lock target expansion is deliberately fail-closed; this facade then
 * retries the whole mutation in a fresh transaction so share-first and delete-first both converge.
 */
@Service
class ScheduleCategoryDeleteCoordinator(
    private val writer: ScheduleCategoryDeleteWriter,
) {
    fun delete(
        memberId: Long,
        categoryId: Long,
        presentedSessionGeneration: Long,
    ) {
        var last: RuntimeException? = null
        repeat(CATEGORY_DELETE_MAX_ATTEMPTS) { attempt ->
            try {
                writer.deleteOnce(memberId, categoryId, presentedSessionGeneration)
                return
            } catch (failure: RuntimeException) {
                if (failure !is ConcurrencyFailureException &&
                    failure !is TransientDataAccessException
                ) {
                    throw failure
                }
                last = failure
                if (attempt == CATEGORY_DELETE_MAX_ATTEMPTS - 1) {
                    throw ConcurrencyFailureException(
                        "Category deletion did not converge after a participant-set race.",
                        failure,
                    )
                }
            }
        }
        throw ConcurrencyFailureException(
            "Category deletion did not converge after a participant-set race.",
            last,
        )
    }
}

@Service
class ScheduleCategoryDeleteWriter(
    private val categoryRepository: ScheduleCategoryRepository,
    private val memberRepository: MemberRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val travelAccessCleanupService: ScheduleTravelAccessCleanupService,
    private val eventPublisher: ApplicationEventPublisher,
    private val observer: ScheduleCategoryDeleteObserver? = null,
) {
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
        isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
    )
    fun deleteOnce(
        memberId: Long,
        categoryId: Long,
        presentedSessionGeneration: Long,
    ) {
        val previewShares = categoryShareRepository.findAllByCategoryIdAndDeletedFalse(categoryId)
        observer?.afterSharePreview(categoryId)
        val previewMemberIds = previewShares.map { it.targetMemberId }
        val lockedMemberIds = (previewMemberIds + memberId).distinct().sorted()
        val lockedById = memberRepository.findAllByIdsForUpdate(lockedMemberIds)
            .associateBy { requireNotNull(it.id) }
        val actor = lockedById[memberId]
            ?.takeUnless { it.deleted }
            ?: throw BusinessException(
                ErrorCode.INVALID_TOKEN,
                "종료되었거나 존재하지 않는 로그인 세션입니다.",
            )
        if (actor.sessionGeneration != presentedSessionGeneration) {
            throw BusinessException(ErrorCode.INVALID_TOKEN, "종료된 로그인 세션입니다.")
        }

        val category = categoryRepository.findOwnedActiveForShareUpdate(categoryId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        val currentShares = categoryShareRepository.findAllByCategoryIdAndDeletedFalse(categoryId)
        if (currentShares.any { it.targetMemberId !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Category participant set changed while acquiring the mutation fence.",
            )
        }
        currentShares.forEach { it.revoke() }
        categoryShareRepository.saveAllAndFlush(currentShares)
        invitationRepository
            .findAllByOwnerMemberIdAndResourceTypeAndResourceIdAndDeletedFalseOrderByIdDesc(
                ownerMemberId = memberId,
                resourceType = ScheduleShareResourceType.CATEGORY,
                resourceId = categoryId,
            )
            .forEach { it.revoke() }
        category.softDelete()
        categoryRepository.saveAndFlush(category)
        travelAccessCleanupService.cancelRevokedForCategory(
            categoryId,
            currentShares.map { it.targetMemberId },
        )
        val affectedMemberIds = currentShares.map { it.targetMemberId }.toSet()
        if (affectedMemberIds.isNotEmpty()) {
            eventPublisher.publishEvent(
                ScheduleCalendarCacheInvalidationEvent(
                    memberIds = affectedMemberIds,
                    reason = "category-deleted",
                )
            )
        }
    }
}

fun interface ScheduleCategoryDeleteObserver {
    fun afterSharePreview(categoryId: Long)
}

private const val CATEGORY_DELETE_MAX_ATTEMPTS = 3

data class ScheduleCategoryReorderItem(
    val id: Long,
    val sortOrder: Int,
)

data class ScheduleCategoryMovePreviewDto(
    val sourceCategory: ScheduleCategorySettingDto,
    val destinationCalendarId: Long,
    val destinationCalendarTitle: String,
    val activeScheduleCount: Int,
    val sameNameCategory: ScheduleCategorySettingDto? = null,
)

data class ScheduleCategoryMoveResultDto(
    val sourceCategoryId: String,
    val category: ScheduleCategorySettingDto,
    val movedScheduleCount: Int,
    val merged: Boolean,
)

private data class DefaultScheduleCategory(
    val title: String,
    val color: String,
    val iconKey: String,
)
