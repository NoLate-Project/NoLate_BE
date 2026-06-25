package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategorySettingDto
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class ScheduleCategoryService(
    private val categoryRepository: ScheduleCategoryRepository,
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
    fun getCategories(memberId: Long): List<ScheduleCategorySettingDto> {
        ensureDefaultCategories(memberId)
        return categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId)
            .map { it.toDto() }
    }

    @Transactional
    fun createCategory(
        memberId: Long,
        title: String?,
        color: String?,
        iconKey: String?,
        sortOrder: Int?,
    ): ScheduleCategorySettingDto {
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
    ): ScheduleCategorySettingDto {
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
    fun deleteCategory(memberId: Long, categoryId: Long) {
        val entity = findCategory(memberId, categoryId)
        entity.softDelete()
        categoryRepository.save(entity)
    }

    @Transactional
    fun reorderCategories(memberId: Long, items: List<ScheduleCategoryReorderItem>): List<ScheduleCategorySettingDto> {
        items.forEach { item ->
            val entity = findCategory(memberId, item.id)
            entity.sortOrder = item.sortOrder
            categoryRepository.save(entity)
        }

        return getCategories(memberId)
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

data class ScheduleCategoryReorderItem(
    val id: Long,
    val sortOrder: Int,
)

private data class DefaultScheduleCategory(
    val title: String,
    val color: String,
    val iconKey: String,
)
