package com.noLate.schedule.controller

import com.noLate.global.common.ApiResponse
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleCategoryReorderItem
import com.noLate.schedule.application.service.ScheduleCategoryService
import com.noLate.schedule.domain.ScheduleCategorySettingDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/schedule-categories")
@Tag(name = "Schedule Category", description = "일정 카테고리 API")
class ScheduleCategoryController(
    private val scheduleCategoryService: ScheduleCategoryService,
) {
    @Operation(summary = "일정 카테고리 목록 조회")
    @GetMapping
    fun getCategories(
        @AuthenticationPrincipal principal: MemberPrincipal?,
    ): ApiResponse<List<ScheduleCategorySettingDto>> {
        return ApiResponse.success(scheduleCategoryService.getCategories(requireMemberId(principal)))
    }

    @Operation(summary = "일정 카테고리 생성")
    @PostMapping
    fun createCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: CreateScheduleCategoryRequest,
    ): ApiResponse<ScheduleCategorySettingDto> {
        val result = scheduleCategoryService.createCategory(
            memberId = requireMemberId(principal),
            title = request.title,
            color = request.color,
            iconKey = request.iconKey,
            sortOrder = request.sortOrder,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "일정 카테고리 수정")
    @PatchMapping("/{categoryId}")
    fun updateCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
        @RequestBody request: UpdateScheduleCategoryRequest,
    ): ApiResponse<ScheduleCategorySettingDto> {
        val result = scheduleCategoryService.updateCategory(
            memberId = requireMemberId(principal),
            categoryId = categoryId,
            title = request.title,
            color = request.color,
            iconKey = request.iconKey,
            sortOrder = request.sortOrder,
        )
        return ApiResponse.success(result)
    }

    @Operation(summary = "일정 카테고리 삭제")
    @DeleteMapping("/{categoryId}")
    fun deleteCategory(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @PathVariable categoryId: Long,
    ): ApiResponse<Unit> {
        scheduleCategoryService.deleteCategory(requireMemberId(principal), categoryId)
        return ApiResponse.success(Unit)
    }

    @Operation(summary = "일정 카테고리 정렬 순서 변경")
    @PatchMapping("/reorder")
    fun reorderCategories(
        @AuthenticationPrincipal principal: MemberPrincipal?,
        @RequestBody request: ReorderScheduleCategoriesRequest,
    ): ApiResponse<List<ScheduleCategorySettingDto>> {
        return ApiResponse.success(
            scheduleCategoryService.reorderCategories(
                memberId = requireMemberId(principal),
                items = request.items.map { it.toServiceItem() },
            )
        )
    }
}

data class CreateScheduleCategoryRequest(
    val title: String,
    val color: String? = null,
    val iconKey: String? = null,
    val sortOrder: Int? = null,
)

data class UpdateScheduleCategoryRequest(
    val title: String? = null,
    val color: String? = null,
    val iconKey: String? = null,
    val sortOrder: Int? = null,
)

data class ReorderScheduleCategoriesRequest(
    val items: List<ReorderScheduleCategoryItemRequest>,
)

data class ReorderScheduleCategoryItemRequest(
    val id: Long,
    val sortOrder: Int,
) {
    fun toServiceItem(): ScheduleCategoryReorderItem {
        return ScheduleCategoryReorderItem(id = id, sortOrder = sortOrder)
    }
}

private fun requireMemberId(principal: MemberPrincipal?): Long =
    principal?.id ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
