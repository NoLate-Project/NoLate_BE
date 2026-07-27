package com.noLate.schedule.application.cache

import com.noLate.schedule.domain.ScheduleCalendarCacheRevision
import com.noLate.schedule.infrastructure.ScheduleCalendarCacheRevisionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ScheduleCalendarCacheRevisionServiceTest {
    private val revisionRepository = mock<ScheduleCalendarCacheRevisionRepository>()
    private val service = ScheduleCalendarCacheRevisionService(revisionRepository)

    @Test
    fun `영향 회원은 ID 오름차순으로 잠근 뒤 durable revision을 증가시킨다`() {
        val revisionTwo = ScheduleCalendarCacheRevision(memberId = 2L, revision = 3L)
        val revisionNine = ScheduleCalendarCacheRevision(memberId = 9L, revision = 7L)
        whenever(revisionRepository.findAllByMemberIdsForUpdate(listOf(2L, 9L)))
            .thenReturn(listOf(revisionTwo, revisionNine))

        service.incrementMembers(listOf(9L, 2L, 9L), "schedule-updated")

        assertEquals(4L, revisionTwo.revision)
        assertEquals(8L, revisionNine.revision)
        verify(revisionRepository).findAllByMemberIdsForUpdate(listOf(2L, 9L))
        verify(revisionRepository).saveAllAndFlush(listOf(revisionTwo, revisionNine))
    }

    @Test
    fun `revision row 누락 회원은 rev0을 만들지 않고 cache fail closed 상태를 유지한다`() {
        val revisionTwo = ScheduleCalendarCacheRevision(memberId = 2L)
        whenever(revisionRepository.findAllByMemberIdsForUpdate(listOf(2L, 9L)))
            .thenReturn(listOf(revisionTwo))

        service.incrementMembers(listOf(9L, 2L), "calendar-member-added")

        assertEquals(1L, revisionTwo.revision)
        verify(revisionRepository).saveAllAndFlush(listOf(revisionTwo))
    }

    @Test
    fun `현재 revision은 Redis가 아니라 독립 DB row에서 조회한다`() {
        whenever(revisionRepository.findRevisionByMemberId(42L)).thenReturn(12L)

        assertEquals(12L, service.currentRevision(42L))
    }
}
