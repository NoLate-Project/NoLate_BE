package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleCategoryServiceUnitTest {

    @Mock
    lateinit var categoryRepository: ScheduleCategoryRepository

    private lateinit var service: ScheduleCategoryService

    @BeforeEach
    fun setUp() {
        service = ScheduleCategoryService(categoryRepository)
    }

    @Test
    fun `getCategories creates default schedule categories for a new member`() {
        val memberId = 7L
        val savedCategories = mutableListOf<ScheduleCategory>()
        stubStatefulRepository(memberId, savedCategories)

        val result = service.getCategories(memberId)

        assertEquals(listOf("업무", "개인", "기타"), result.map { it.title })
        assertEquals(listOf("#f44336", "#2196f3", "#4caf50"), result.map { it.color })
        assertEquals(listOf(0, 1, 2), result.map { it.sortOrder })
        verify(categoryRepository, times(3)).save(any<ScheduleCategory>())
    }

    @Test
    fun `createCategory assigns next sort order and returns saved dto`() {
        val memberId = 7L
        whenever(categoryRepository.findMaxSortOrder(memberId)).thenReturn(2)
        whenever(categoryRepository.save(any<ScheduleCategory>()))
            .thenAnswer { invocation ->
                invocation.getArgument<ScheduleCategory>(0).apply { id = 10L }
            }

        val result = service.createCategory(
            memberId = memberId,
            title = "운동",
            color = "#ff2d55",
            iconKey = "fitness-outline",
            sortOrder = null,
        )

        verify(categoryRepository).save(check {
            assertEquals(memberId, it.memberId)
            assertEquals("운동", it.title)
            assertEquals("#ff2d55", it.color)
            assertEquals("fitness-outline", it.iconKey)
            assertEquals(3, it.sortOrder)
        })
        assertEquals("10", result.id)
        assertEquals("운동", result.title)
    }

    @Test
    fun `updateCategory changes requested fields and preserves omitted fields`() {
        val memberId = 7L
        val category = ScheduleCategory(
            id = 3L,
            memberId = memberId,
            title = "기존",
            color = "#2196f3",
            iconKey = "person-outline",
            sortOrder = 4,
        )

        whenever(categoryRepository.findByIdAndMemberIdAndDeletedFalse(3L, memberId))
            .thenReturn(category)
        whenever(categoryRepository.save(category)).thenReturn(category)

        val result = service.updateCategory(
            memberId = memberId,
            categoryId = 3L,
            title = "수정됨",
            color = null,
            iconKey = null,
            sortOrder = null,
        )

        assertEquals("수정됨", result.title)
        assertEquals("#2196f3", result.color)
        assertEquals("person-outline", result.iconKey)
        assertEquals(4, result.sortOrder)
    }

    @Test
    fun `deleteCategory soft deletes category`() {
        val memberId = 7L
        val category = ScheduleCategory(id = 4L, memberId = memberId, title = "삭제 대상")

        whenever(categoryRepository.findByIdAndMemberIdAndDeletedFalse(4L, memberId))
            .thenReturn(category)
        whenever(categoryRepository.save(category)).thenReturn(category)

        service.deleteCategory(memberId, 4L)

        verify(categoryRepository).save(check {
            assertTrue(it.deleted)
            assertNotNull(it.deletedAt)
        })
    }

    @Test
    fun `reorderCategories updates sort order and returns sorted list`() {
        val memberId = 7L
        val categories = mutableListOf(
            ScheduleCategory(id = 1L, memberId = memberId, title = "업무", sortOrder = 0),
            ScheduleCategory(id = 2L, memberId = memberId, title = "개인", sortOrder = 1),
        )
        stubStatefulRepository(memberId, categories)

        val result = service.reorderCategories(
            memberId = memberId,
            items = listOf(
                ScheduleCategoryReorderItem(id = 1L, sortOrder = 2),
                ScheduleCategoryReorderItem(id = 2L, sortOrder = 0),
            ),
        )

        assertEquals(listOf("개인", "업무"), result.map { it.title })
        assertEquals(listOf(0, 2), result.map { it.sortOrder })
    }

    @Test
    fun `createCategory rejects blank title`() {
        assertThrows<BusinessException> {
            service.createCategory(
                memberId = 7L,
                title = "   ",
                color = "#ff2d55",
                iconKey = null,
                sortOrder = null,
            )
        }

        verify(categoryRepository, never()).save(any<ScheduleCategory>())
    }

    @Test
    fun `updateCategory throws when category does not exist`() {
        whenever(categoryRepository.findByIdAndMemberIdAndDeletedFalse(404L, 7L))
            .thenReturn(null)

        assertThrows<BusinessException> {
            service.updateCategory(
                memberId = 7L,
                categoryId = 404L,
                title = "없음",
                color = null,
                iconKey = null,
                sortOrder = null,
            )
        }

        verify(categoryRepository, never()).save(any<ScheduleCategory>())
    }

    private fun stubStatefulRepository(
        memberId: Long,
        categories: MutableList<ScheduleCategory>,
    ) {
        whenever(categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId))
            .thenAnswer {
                categories
                    .filter { !it.deleted }
                    .sortedWith(compareBy<ScheduleCategory> { it.sortOrder }.thenBy { it.id ?: Long.MAX_VALUE })
            }
        lenient().whenever(categoryRepository.findByIdAndMemberIdAndDeletedFalse(any(), any()))
            .thenAnswer { invocation ->
                val id = invocation.getArgument<Long>(0)
                val ownerId = invocation.getArgument<Long>(1)
                categories.firstOrNull { it.id == id && it.memberId == ownerId && !it.deleted }
            }
        lenient().whenever(categoryRepository.findMaxSortOrder(memberId))
            .thenAnswer { categories.filter { !it.deleted }.maxOfOrNull { it.sortOrder } ?: -1 }
        whenever(categoryRepository.save(any<ScheduleCategory>()))
            .thenAnswer { invocation ->
                val category = invocation.getArgument<ScheduleCategory>(0)
                if (category.id == null) {
                    category.id = ((categories.mapNotNull { it.id }.maxOrNull() ?: 0L) + 1L)
                }
                categories.removeAll { it.id == category.id }
                categories.add(category)
                category
            }
    }
}
