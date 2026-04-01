package com.noLate.global.common

import org.springframework.data.domain.Page

data class PageRequestDto(
    val page: Int = 0,
    val size: Int = 20,
    val sort: String? = null
)

data class PageResponseDto<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    companion object {
        fun <T> from(page: Page<T>): PageResponseDto<T> {
            return PageResponseDto(
                content = page.content,
                page = page.number,
                size = page.size,
                totalElements = page.totalElements,
                totalPages = page.totalPages
            )
        }
    }
}