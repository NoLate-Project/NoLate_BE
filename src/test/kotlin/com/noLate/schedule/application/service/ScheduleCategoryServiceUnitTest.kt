package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleCategoryServiceUnitTest {

    @Mock
    lateinit var categoryRepository: ScheduleCategoryRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    private lateinit var service: ScheduleCategoryService

    @BeforeEach
    fun setUp() {
        service = ScheduleCategoryService(categoryRepository, memberRepository)
        lenient().whenever(memberRepository.findByIdForUpdate(7L)).thenReturn(
            Member(id = 7L, name = "Member", password = "Password1!", email = "member@example.com")
        )
    }

    @Test
    fun `getCategories creates default schedule categories for a new member`() {
        val memberId = 7L
        val savedCategories = mutableListOf<ScheduleCategory>()
        stubStatefulRepository(memberId, savedCategories)

        val result = service.getCategories(memberId, presentedSessionGeneration = 0L)

        assertEquals(listOf("업무", "개인", "기타"), result.map { it.title })
        assertEquals(listOf("#f44336", "#2196f3", "#4caf50"), result.map { it.color })
        assertEquals(listOf(0, 1, 2), result.map { it.sortOrder })
        verify(categoryRepository, times(3)).save(any<ScheduleCategory>())
    }

    @Test
    fun `getCategories includes the active permission for a received category`() {
        val memberId = 7L
        val shareRepository = mock<ScheduleCategoryShareRepository>()
        val permissionAwareService = ScheduleCategoryService(
            categoryRepository = categoryRepository,
            memberRepository = memberRepository,
            categoryShareRepository = shareRepository,
        )
        val owned = ScheduleCategory(id = 1L, memberId = memberId, title = "내 일정")
        val received = ScheduleCategory(id = 2L, memberId = 99L, title = "팀 일정")
        whenever(categoryRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(memberId))
            .thenReturn(listOf(owned))
        whenever(categoryRepository.findVisibleCategories(memberId))
            .thenReturn(listOf(owned, received))
        whenever(
            shareRepository.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                memberId,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                ScheduleCategoryShare(
                    id = 10L,
                    categoryId = 2L,
                    ownerMemberId = 99L,
                    targetMemberId = memberId,
                    permission = ScheduleSharePermission.EDITOR,
                    status = ScheduleShareStatus.ACTIVE,
                )
            )
        )

        val result = permissionAwareService.getCategories(memberId, presentedSessionGeneration = 0L)

        assertEquals(false, result.first { it.id == "1" }.shared)
        assertEquals(null, result.first { it.id == "1" }.sharePermission)
        assertEquals(true, result.first { it.id == "2" }.shared)
        assertEquals(ScheduleSharePermission.EDITOR, result.first { it.id == "2" }.sharePermission)
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
            presentedSessionGeneration = 0L,
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
            presentedSessionGeneration = 0L,
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

        whenever(categoryRepository.findOwnedActiveForShareUpdate(4L, memberId))
            .thenReturn(category)
        whenever(categoryRepository.save(category)).thenReturn(category)

        service.deleteCategory(memberId, 4L, presentedSessionGeneration = 0L)

        verify(categoryRepository).save(check {
            assertTrue(it.deleted)
            assertNotNull(it.deletedAt)
        })
    }

    @Test
    fun `deleteCategory locks affected targets before category and cleans revoked travel state`() {
        val shareRepository = mock<ScheduleCategoryShareRepository>()
        val cleanupService = mock<ScheduleTravelAccessCleanupService>()
        val deleteService = ScheduleCategoryService(
            categoryRepository = categoryRepository,
            memberRepository = memberRepository,
            categoryShareRepository = shareRepository,
            travelAccessCleanupService = cleanupService,
        )
        val category = ScheduleCategory(id = 4L, memberId = 7L, title = "삭제 대상")
        val share = ScheduleCategoryShare(
            id = 8L,
            categoryId = 4L,
            ownerMemberId = 7L,
            targetMemberId = 2L,
            permission = ScheduleSharePermission.VIEWER,
        )
        whenever(shareRepository.findAllByCategoryIdAndDeletedFalse(4L))
            .thenReturn(listOf(share))
        whenever(memberRepository.findByIdForUpdate(2L)).thenReturn(
            Member(id = 2L, name = "Target", password = "Password1!", email = "target@example.com")
        )
        whenever(categoryRepository.findOwnedActiveForShareUpdate(4L, 7L)).thenReturn(category)
        whenever(categoryRepository.save(category)).thenReturn(category)

        deleteService.deleteCategory(7L, 4L, presentedSessionGeneration = 0L)

        assertEquals(ScheduleShareStatus.REVOKED, share.status)
        inOrder(memberRepository, categoryRepository) {
            verify(memberRepository).findByIdForUpdate(2L)
            verify(memberRepository).findByIdForUpdate(7L)
            verify(categoryRepository).findOwnedActiveForShareUpdate(4L, 7L)
        }
        verify(cleanupService).cancelRevokedForCategory(4L, listOf(2L))
    }

    @Test
    fun `category delete retries a participant expansion in a fresh writer transaction`() {
        val writer = mock<ScheduleCategoryDeleteWriter>()
        var attempts = 0
        whenever(writer.deleteOnce(7L, 4L, 0L)).thenAnswer {
            attempts += 1
            if (attempts == 1) {
                throw org.springframework.dao.ConcurrencyFailureException("new share committed")
            }
            Unit
        }

        ScheduleCategoryDeleteCoordinator(writer).delete(7L, 4L, 0L)

        assertEquals(2, attempts)
        verify(writer, times(2)).deleteOnce(7L, 4L, 0L)
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
            presentedSessionGeneration = 0L,
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
                presentedSessionGeneration = 0L,
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
                presentedSessionGeneration = 0L,
            )
        }

        verify(categoryRepository, never()).save(any<ScheduleCategory>())
    }

    @Test
    fun `stale category mutation generation is rejected before lazy defaults or category writes`() {
        whenever(memberRepository.findByIdForUpdate(7L)).thenReturn(
            Member(
                id = 7L,
                name = "Member",
                password = "Password1!",
                email = "member@example.com",
                sessionGeneration = 2L,
            )
        )

        val error = assertThrows<BusinessException> {
            service.getCategories(memberId = 7L, presentedSessionGeneration = 1L)
        }

        assertEquals(ErrorCode.INVALID_TOKEN, error.errorCode)
        verify(categoryRepository, never()).findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(any())
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
        whenever(categoryRepository.findVisibleCategories(memberId))
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
