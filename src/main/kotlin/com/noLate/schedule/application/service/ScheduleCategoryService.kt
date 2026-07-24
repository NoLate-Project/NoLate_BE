package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategorySettingDto
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareInvitationRepository
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
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
        val visibleCategories = categoryRepository.findVisibleCategories(memberId)
        val hasReceivedCategory = visibleCategories.any { it.memberId != memberId }
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

        return visibleCategories
            .map { category ->
                val shared = category.memberId != memberId
                category.toDto().copy(
                    shared = shared,
                    // The visibility query proves access. If a legacy service
                    // instance has no share repository, fall back to the least
                    // privileged label rather than falsely claiming edit access.
                    sharePermission = if (shared) {
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
    ): ScheduleCategorySettingDto {
        lockMutationMembers(memberId, presentedSessionGeneration)
        val entity = ScheduleCategory(
            memberId = memberId,
            title = normalizeRequiredText(title, "title", maxLength = 80),
            color = normalizeOptionalText(color, maxLength = 32) ?: defaultCategoryColor(memberId),
            iconKey = normalizeOptionalText(iconKey, maxLength = 40),
            sortOrder = sortOrder ?: nextCategorySortOrder(memberId),
        )

        return categoryRepository.save(entity).toDto()
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
        val entity = findCategory(memberId, categoryId)
        entity.update(
            title = title?.let { normalizeRequiredText(it, "title", maxLength = 80) } ?: entity.title,
            color = normalizeOptionalText(color, maxLength = 32) ?: entity.color,
            iconKey = if (iconKey != null) normalizeOptionalText(iconKey, maxLength = 40) else entity.iconKey,
            sortOrder = sortOrder ?: entity.sortOrder,
        )

        return categoryRepository.save(entity).toDto()
    }

    @Transactional
    fun deleteCategory(
        memberId: Long,
        categoryId: Long,
        presentedSessionGeneration: Long,
    ) {
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
            val entity = findCategory(memberId, item.id)
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

    private fun findCategory(memberId: Long, categoryId: Long): ScheduleCategory {
        return categoryRepository.findByIdAndMemberIdAndDeletedFalse(categoryId, memberId)
            ?: throw BusinessException(ErrorCode.SCHEDULE_CATEGORY_NOT_FOUND)
    }

    private fun nextCategorySortOrder(memberId: Long): Int {
        return categoryRepository.findMaxSortOrder(memberId) + 1
    }

    private fun defaultCategoryColor(memberId: Long): String {
        val index = nextCategorySortOrder(memberId).coerceAtLeast(0)
        return colorPalette[index % colorPalette.size]
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

private data class DefaultScheduleCategory(
    val title: String,
    val color: String,
    val iconKey: String,
)
