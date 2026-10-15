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
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRouteSetupReminderRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
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
    private val scheduleShareRepository: ScheduleShareRepository? = null,
    private val schedulePushJobRepository: SchedulePushJobRepository? = null,
    private val scheduleTravelPlanRepository: ScheduleTravelPlanRepository? = null,
    private val scheduleRouteSetupReminderRepository: ScheduleRouteSetupReminderRepository? = null,
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
            categoryRepository.findResourceOwnedCategories(memberId)
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
        val directPermissions = if (sharingAvailabilityPolicy.enabled) {
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
                calendar != null &&
                    (calendar.ownerMemberId == memberId ||
                        calendarRoles[calendarId] != null ||
                        it.id?.let(directPermissions::get) != null) &&
                    (calendar.ownerMemberId == memberId || calendar.ownerMemberId !in blockedOwnerIds)
            } else {
                it.memberId == memberId || it.memberId !in blockedOwnerIds
            }
        }

        return allowedCategories
            .map { category ->
                val calendarRole = category.calendarId?.let { calendarId ->
                    if (activeCalendars[calendarId]?.ownerMemberId == memberId) {
                        ScheduleCalendarRole.OWNER
                    } else {
                        calendarRoles[calendarId]
                    }
                }
                val directPermission = category.id?.let(directPermissions::get)
                val shared = category.calendarId != null || category.memberId != memberId
                category.toDto().copy(
                    shared = shared,
                    sharePermission = strongestCategoryPermission(
                        calendarRole?.toCategoryPermission(),
                        directPermission,
                    ) ?: ScheduleSharePermission.VIEWER.takeIf { shared },
                    canManageMetadata = if (category.calendarId == null) {
                        category.memberId == memberId
                    } else {
                        calendarRole in setOf(ScheduleCalendarRole.OWNER, ScheduleCalendarRole.EDITOR)
                    },
                    canManageAudience = if (category.calendarId == null) {
                        category.memberId == memberId
                    } else {
                        calendarRole == ScheduleCalendarRole.OWNER
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
            canManageMetadata = true,
            canManageAudience = calendarId == null || calendarRole == ScheduleCalendarRole.OWNER,
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
            canManageMetadata = true,
            canManageAudience = entity.calendarId == null || entity.calendarId
                ?.let { calendarMemberRepository?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(it, memberId) }
                ?.role == ScheduleCalendarRole.OWNER,
        )
    }

    @Transactional
    fun previewMove(
        memberId: Long,
        categoryId: Long,
        calendarId: Long,
        presentedSessionGeneration: Long,
    ): ScheduleCategoryMovePreviewDto {
        lockMutationMembers(memberId, presentedSessionGeneration)
        val sourceScope = categoryRepository.findActiveScope(categoryId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        requireOwnedMoveCalendars(
            memberId = memberId,
            sourceCalendarId = sourceScope.getCalendarId(),
            destinationCalendarId = calendarId,
        )
        val source = categoryRepository.findActiveForScheduleAssignment(categoryId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        validateMoveSource(source, sourceScope, memberId)

        val schedules = requireScheduleRepository()
            .findAllByCategoryIdIncludingSnapshotAndDeletedFalseOrderByIdAsc(categoryId)
        val destinationCalendar = calendarRepository?.findByIdAndStatusAndDeletedFalse(calendarId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
        val sourceCalendar = sourceScope.getCalendarId()
            ?.let { calendarRepository.findByIdAndStatusAndDeletedFalse(it) }
        val directShares = categoryShareRepository
            ?.findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                categoryId,
                ScheduleShareStatus.ACTIVE,
            )
            .orEmpty()
        val directScheduleShares = scheduleShareRepository
            ?.findAllByScheduleIdInAndStatusAndDeletedFalseOrderByScheduleIdAscIdAsc(
                schedules.mapNotNull { it.id },
                ScheduleShareStatus.ACTIVE,
            )
            .orEmpty()
        val sourceCalendarMemberIds = sourceScope.getCalendarId()
            ?.let { calendarMemberRepository?.findActiveMemberIdsByCalendarId(it) }
            .orEmpty()
        val destinationCalendarMemberIds = calendarMemberRepository
            ?.findActiveMemberIdsByCalendarId(calendarId)
            .orEmpty()
        val directRecipientIds = directShares.map { it.targetMemberId }.toSet()
        val sourceOwnerMemberId = sourceCalendar?.ownerMemberId ?: source.memberId
        val rawBeforeAudience = buildSet {
            add(sourceOwnerMemberId)
            addAll(directRecipientIds)
            addAll(sourceCalendarMemberIds)
        }
        val rawAfterAudience = buildSet {
            add(destinationCalendar.ownerMemberId)
            addAll(directRecipientIds)
            addAll(destinationCalendarMemberIds)
        }
        val beforeAudience = filterBlockedAudience(sourceOwnerMemberId, rawBeforeAudience)
        val afterAudience = filterBlockedAudience(destinationCalendar.ownerMemberId, rawAfterAudience)
        val destinationRole = calendarMemberRepository
            ?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(calendarId, memberId)
            ?.role
        val sameNameCategory = categoryRepository
            .findFirstByCalendarIdAndTitleIgnoreCaseAndDeletedFalseOrderByIdAsc(calendarId, source.title)
            ?.takeUnless { category -> category.id == source.id }

        return ScheduleCategoryMovePreviewDto(
            sourceCategory = source.toDto().copy(
                shared = source.calendarId != null,
                canManageMetadata = true,
                canManageAudience = true,
            ),
            destinationCalendarId = calendarId,
            destinationCalendarTitle = destinationCalendar.title,
            activeScheduleCount = schedules.size,
            retainedDirectShareCount = directShares.size,
            retainedDirectScheduleShareCount = directScheduleShares.size,
            sourceCalendarMemberCount = sourceCalendarMemberIds.size,
            destinationCalendarMemberCount = destinationCalendarMemberIds.size,
            gainedAccessMemberCount = (afterAudience - beforeAudience).size,
            lostAccessMemberCount = (beforeAudience - afterAudience).size,
            sameNameCategory = sameNameCategory?.toDto()?.copy(
                shared = true,
                sharePermission = destinationRole?.toCategoryPermission(),
                canManageMetadata = true,
                canManageAudience = destinationRole == ScheduleCalendarRole.OWNER,
            ),
        )
    }

    /**
     * Moves one category and every active schedule linked to it as one transaction.
     *
     * Category identity is stable across the move. Direct category grants and pending invitation
     * tokens therefore remain attached to the same category id, while source/destination calendar
     * membership changes only the additional calendar-derived audience.
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

        val sourceScope = categoryRepository.findActiveScope(categoryId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        if (sourceScope.getCalendarId() == calendarId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "이미 선택한 캘린더에 속한 카테고리입니다.")
        }
        val sharePreview = shares.findAllByCategoryIdAndDeletedFalse(categoryId)
        val previewTargetMemberIds = sharePreview.map { it.targetMemberId }.distinct()
        val sourceMemberPreviewIds = sourceScope.getCalendarId()
            ?.let(calendarMembers::findActiveMemberIdsByCalendarId)
            .orEmpty()
        val destinationMemberPreviewIds = calendarMembers.findActiveMemberIdsByCalendarId(calendarId)
        val scheduleIdsPreview = schedules
            .findActiveIdsByCategoryIdIncludingSnapshotOrderByIdAsc(categoryId)
        val scheduleSharePreview = scheduleShareRepository
            ?.findAllByScheduleIdInAndDeletedFalseOrderByScheduleIdAscIdAsc(scheduleIdsPreview)
            .orEmpty()
        val scheduleSharePreviewTargetIds = scheduleSharePreview.map { it.targetMemberId }.distinct()
        val artifactMemberPreviewIds = buildSet {
            schedulePushJobRepository
                ?.findDistinctMemberIdsByScheduleIdIn(scheduleIdsPreview)
                ?.let(::addAll)
            scheduleTravelPlanRepository
                ?.findDistinctMemberIdsByScheduleIdIn(scheduleIdsPreview)
                ?.let(::addAll)
            scheduleRouteSetupReminderRepository
                ?.findDistinctMemberIdsByScheduleIdIn(scheduleIdsPreview)
                ?.let(::addAll)
        }
        val scheduleOwnerPreviewIds = schedules
            .findActiveOwnerMemberIdsByCategoryIdIncludingSnapshotOrderByMemberIdAsc(categoryId)

        // All related writers begin with member rows. In particular, normal schedule edits use
        // member -> push-job -> schedule -> calendar, while calendar mutations use member ->
        // calendar. Taking schedule/calendar first here would allow a classic two-row deadlock.
        // Include dormant artifact owners as well: jobs/plans/reminders can outlive a just-revoked
        // grant and cleanup must not acquire their member rows after schedule/category locks.
        lockMutationMembers(
            memberId,
            presentedSessionGeneration,
            previewTargetMemberIds + sourceMemberPreviewIds +
                destinationMemberPreviewIds + scheduleOwnerPreviewIds +
                scheduleSharePreviewTargetIds + artifactMemberPreviewIds,
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

        // Schedule edits lock schedule -> calendars. Both source and destination parents use the
        // same ascending-id lock order so opposite-direction category moves cannot deadlock.
        val lockedCalendars = requireOwnedMoveCalendars(
            memberId = memberId,
            sourceCalendarId = sourceScope.getCalendarId(),
            destinationCalendarId = calendarId,
        )
        val destinationOwnerMemberId = lockedCalendars.getValue(calendarId).ownerMemberId
        val sourceMemberIds = sourceScope.getCalendarId()
            ?.let(calendarMembers::findActiveMemberIdsByCalendarId)
            .orEmpty()
        val destinationMemberIds = calendarMembers.findActiveMemberIdsByCalendarId(calendarId)
        val lockedMemberIds = (
            previewTargetMemberIds + sourceMemberPreviewIds + destinationMemberPreviewIds +
                scheduleOwnerPreviewIds + scheduleSharePreviewTargetIds +
                artifactMemberPreviewIds + memberId
            ).toSet()
        if ((sourceMemberIds + destinationMemberIds).any { it !in lockedMemberIds }) {
            throw ConcurrencyFailureException("Calendar membership changed while acquiring the category move fence.")
        }
        // Global dependent lock order is category -> invitation -> share. Acceptance, deletion,
        // ownership transfer and this move all follow it so opposite operations cannot form a
        // category/invitation cycle.
        val source = categoryRepository.findActiveForScheduleAssignment(categoryId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
        validateMoveSource(source, sourceScope, memberId)
        val resourceInvitations = buildList {
            addAll(
                invitations.findAllByResourceForUpdate(
                    resourceType = ScheduleShareResourceType.CATEGORY,
                    resourceId = categoryId,
                )
            )
            lockedSchedulesById.keys.sorted().forEach { scheduleId ->
                addAll(
                    invitations.findAllByResourceForUpdate(
                        resourceType = ScheduleShareResourceType.SCHEDULE,
                        resourceId = scheduleId,
                    )
                )
            }
        }

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
        val scheduleShares = scheduleShareRepository
            ?.findAllByScheduleIdInAndDeletedFalseOrderByScheduleIdAscIdAsc(linkedScheduleIds)
            .orEmpty()
        if (scheduleShares.any { it.targetMemberId !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Schedule direct-share audience changed while acquiring the category move fence.",
            )
        }
        val currentArtifactMemberIds = buildSet {
            schedulePushJobRepository
                ?.findDistinctMemberIdsByScheduleIdIn(linkedScheduleIds)
                ?.let(::addAll)
            scheduleTravelPlanRepository
                ?.findDistinctMemberIdsByScheduleIdIn(linkedScheduleIds)
                ?.let(::addAll)
            scheduleRouteSetupReminderRepository
                ?.findDistinctMemberIdsByScheduleIdIn(linkedScheduleIds)
                ?.let(::addAll)
        }
        if (currentArtifactMemberIds.any { it !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Schedule travel-artifact audience changed while acquiring the category move fence.",
            )
        }
        if (mergeIntoCategoryId != null) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "공유 권한과 초대 링크를 보존하기 위해 카테고리 병합은 지원하지 않습니다.",
            )
        }

        val beforeAudience = buildSet {
            add(memberId)
            addAll(currentShares.map { it.targetMemberId })
            lockedSchedules.forEach { schedule ->
                addAll(calendarCacheAudienceResolver?.resolve(schedule).orEmpty())
            }
        }

        // A category can move into a calendar where the mover is an EDITOR and another member is
        // OWNER. The grants and every token keep their resource id, but their management owner
        // follows the destination calendar owner so accepted-token retries and future revoke/update
        // requests remain valid after the move.
        currentShares.forEach { it.ownerMemberId = destinationOwnerMemberId }
        scheduleShares.forEach { it.ownerMemberId = destinationOwnerMemberId }
        resourceInvitations.forEach { it.ownerMemberId = destinationOwnerMemberId }
        shares.saveAllAndFlush(currentShares)
        scheduleShareRepository?.saveAllAndFlush(scheduleShares)
        invitations.saveAllAndFlush(resourceInvitations)

        lockedSchedules.forEach { schedule ->
            schedule.calendarId = calendarId
            schedule.updateCategorySnapshot(
                categoryId = categoryId.toString(),
                title = source.title,
                color = source.color,
            )
        }
        schedules.saveAllAndFlush(lockedSchedules)

        val destinationSortOrder = categoryRepository.findMaxSortOrderByCalendarId(calendarId) + 1
        source.moveToCalendar(calendarId, destinationSortOrder)
        categoryRepository.saveAndFlush(source)
        if (sourceScope.getCalendarId() == null) {
            ensurePersonalCategoryRemains(memberId)
        }

        // Re-evaluate every fenced audience/artifact member after the owner/container switch.
        // Direct grants normally survive, while source-only members and recipients blocked by the
        // destination owner lose their jobs/native alarms in this same transaction. A block is
        // reversible, so only those blocked recipients keep their personal travel plan dormant;
        // members who genuinely lost a grant still have that plan retired.
        val blockedByDestinationOwner = sharingBlockPolicy
            ?.blockedCounterpartIds(destinationOwnerMemberId, lockedMemberIds)
            .orEmpty()
        lockedSchedules.forEach { schedule ->
            travelAccessCleanupService?.cancelRevokedForSchedule(
                scheduleId = requireNotNull(schedule.id),
                memberIds = lockedMemberIds,
                preserveTravelPlanMemberIds = blockedByDestinationOwner,
            )
        }

        val afterAudience = buildSet {
            add(memberId)
            addAll(destinationMemberIds)
            lockedSchedules.forEach { schedule ->
                addAll(calendarCacheAudienceResolver?.resolve(schedule).orEmpty())
            }
        }
        publishCategoryInvalidation(
            beforeAudience + afterAudience + lockedMemberIds,
            "category-moved-to-calendar",
        )

        return ScheduleCategoryMoveResultDto(
            sourceCategoryId = categoryId.toString(),
            category = source.toDto().copy(
                shared = true,
                sharePermission = calendarMemberRepository
                    ?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(calendarId, memberId)
                    ?.role
                    ?.toCategoryPermission(),
                canManageMetadata = true,
                canManageAudience = calendarMemberRepository
                    ?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(calendarId, memberId)
                    ?.role == ScheduleCalendarRole.OWNER,
            ),
            movedScheduleCount = lockedSchedules.size,
            merged = false,
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
            val calendarId = requireNotNull(existingCategory.calendarId)
            val previewShares = categoryShareRepository
                ?.findAllByCategoryIdAndDeletedFalse(categoryId)
                .orEmpty()
            val previewTargetMemberIds = previewShares.map { it.targetMemberId }
            lockMutationMembers(memberId, presentedSessionGeneration, previewTargetMemberIds)
            requireWritableCalendar(memberId, calendarId)
            // Invitation acceptance follows calendar -> category -> invitation. Deletion takes
            // the same locks so a link cannot grant a category after the tombstone linearizes.
            val category = categoryRepository.findActiveForScheduleAssignment(categoryId)
                ?.takeIf { it.calendarId == calendarId }
                ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
            val invitations = invitationRepository
                ?.findAllByResourceForUpdate(ScheduleShareResourceType.CATEGORY, categoryId)
                .orEmpty()
            val currentShares = categoryShareRepository
                ?.findAllByCategoryIdForUpdate(categoryId)
                .orEmpty()
            if (currentShares.any { it.targetMemberId !in (previewTargetMemberIds + memberId).toSet() }) {
                throw ConcurrencyFailureException(
                    "Category participant set changed while acquiring the deletion fence.",
                )
            }
            currentShares.forEach { it.revoke() }
            invitations.forEach { it.revoke() }
            categoryShareRepository?.saveAllAndFlush(currentShares)
            invitationRepository?.saveAllAndFlush(invitations)
            category.softDelete()
            categoryRepository.saveAndFlush(category)
            val affectedMemberIds = calendarMemberRepository
                ?.findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendarId)
                ?.map { it.memberId }
                .orEmpty() + previewTargetMemberIds + memberId
            travelAccessCleanupService?.cancelRevokedForCategory(categoryId, previewTargetMemberIds)
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

    private fun requireOwnedMoveCalendars(
        memberId: Long,
        sourceCalendarId: Long?,
        destinationCalendarId: Long,
    ): Map<Long, ScheduleCalendar> {
        if (sourceCalendarId == destinationCalendarId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "서로 다른 캘린더를 선택해 주세요.")
        }
        val calendarIds = listOfNotNull(sourceCalendarId, destinationCalendarId)
            .distinct()
            .sorted()
        if (calendarIds.isEmpty()) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "이동할 캘린더를 선택해 주세요.")
        }
        val calendars = calendarRepository
            ?.findAllForUpdate(calendarIds)
            ?.associateBy { requireNotNull(it.id) }
            ?: throw BusinessException(ErrorCode.INVALID_STATE, "공유 캘린더 저장소가 준비되지 않았습니다.")
        calendarIds.forEach { calendarId ->
            val calendar = calendars[calendarId]
                ?.takeIf { !it.deleted && it.status == ScheduleCalendarStatus.ACTIVE }
                ?: throw BusinessException(ErrorCode.SCHEDULE_CALENDAR_NOT_FOUND)
            val membership = calendarMemberRepository
                ?.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                    calendarId,
                    memberId,
                    ScheduleCalendarMemberStatus.ACTIVE,
                )
                ?: throw BusinessException(ErrorCode.FORBIDDEN, "공유 캘린더 멤버가 아닙니다.")
            if (calendarId == sourceCalendarId &&
                (calendar.ownerMemberId != memberId || membership.role != ScheduleCalendarRole.OWNER)
            ) {
                throw BusinessException(ErrorCode.FORBIDDEN, "카테고리 이동은 캘린더 소유자만 할 수 있습니다.")
            }
            if (calendarId == destinationCalendarId &&
                membership.role !in setOf(ScheduleCalendarRole.OWNER, ScheduleCalendarRole.EDITOR)
            ) {
                throw BusinessException(ErrorCode.FORBIDDEN, "대상 캘린더에 카테고리를 추가할 권한이 없습니다.")
            }
        }
        return calendars
    }

    private fun validateMoveSource(
        category: ScheduleCategory,
        preview: com.noLate.schedule.infrastructure.ScheduleCategoryScope,
        memberId: Long,
    ) {
        if (category.calendarId != preview.getCalendarId() || category.memberId != preview.getMemberId()) {
            throw ConcurrencyFailureException("Category scope changed while acquiring the move fence.")
        }
        if (category.calendarId == null && category.memberId != memberId) {
            throw BusinessException(ErrorCode.FORBIDDEN, "카테고리 소유자만 이동할 수 있습니다.")
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

    private fun filterBlockedAudience(ownerMemberId: Long, memberIds: Collection<Long>): Set<Long> {
        val blocked = sharingBlockPolicy
            ?.blockedCounterpartIds(ownerMemberId, memberIds)
            .orEmpty()
        return memberIds.filterTo(linkedSetOf()) {
            it == ownerMemberId || it !in blocked
        }
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

private fun strongestCategoryPermission(
    first: ScheduleSharePermission?,
    second: ScheduleSharePermission?,
): ScheduleSharePermission? = listOfNotNull(first, second).maxByOrNull {
    when (it) {
        ScheduleSharePermission.VIEWER -> 0
        ScheduleSharePermission.COMMENTER -> 1
        ScheduleSharePermission.EDITOR -> 2
        ScheduleSharePermission.OWNER -> 3
    }
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
        val invitations = invitationRepository.findAllByResourceForUpdate(
            ScheduleShareResourceType.CATEGORY,
            categoryId,
        )
        val currentShares = categoryShareRepository.findAllByCategoryIdForUpdate(categoryId)
        if (currentShares.any { it.targetMemberId !in lockedMemberIds }) {
            throw ConcurrencyFailureException(
                "Category participant set changed while acquiring the mutation fence.",
            )
        }
        currentShares.forEach { it.revoke() }
        categoryShareRepository.saveAllAndFlush(currentShares)
        invitations.forEach { it.revoke() }
        invitationRepository.saveAllAndFlush(invitations)
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
    val retainedDirectShareCount: Int = 0,
    val retainedDirectScheduleShareCount: Int = 0,
    val sourceCalendarMemberCount: Int = 0,
    val destinationCalendarMemberCount: Int = 0,
    val gainedAccessMemberCount: Int = 0,
    val lostAccessMemberCount: Int = 0,
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
