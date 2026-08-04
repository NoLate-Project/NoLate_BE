package com.noLate.schedule.application.cache

import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleCalendarCacheAudienceResolverTest {
    @Mock
    lateinit var scheduleShareRepository: ScheduleShareRepository

    @Mock
    lateinit var categoryShareRepository: ScheduleCategoryShareRepository

    @Mock
    lateinit var calendarMemberRepository: ScheduleCalendarMemberRepository

    @Test
    fun `ScheduleDto는 이동 기능이 비활성이어도 일정 가시성 전체 audience를 합친다`() {
        val resolver = ScheduleCalendarCacheAudienceResolver(
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            calendarMemberRepository = calendarMemberRepository,
        )
        val schedule = ScheduleDto(
            id = 303L,
            ownerMemberId = 101L,
            calendarId = 404L,
            scheduleType = ScheduleType.ROUTE,
            shareContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
            travelCollaborationEnabled = false,
            title = "일정만 공유한 약속",
            startAt = "2026-08-04T01:00:00Z",
            category = ScheduleCategoryDto(
                id = "202",
                title = "공유 카테고리",
                color = "#112233",
            ),
            travelPlanParticipants = emptyList(),
        )
        whenever(
            scheduleShareRepository
                .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                    303L,
                    ScheduleShareStatus.ACTIVE,
                )
        ).thenReturn(
            listOf(
                ScheduleShare(
                    scheduleId = 303L,
                    ownerMemberId = 101L,
                    targetMemberId = 102L,
                    permission = ScheduleSharePermission.VIEWER,
                    contentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
                )
            )
        )
        whenever(
            categoryShareRepository
                .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                    202L,
                    ScheduleShareStatus.ACTIVE,
                )
        ).thenReturn(
            listOf(
                ScheduleCategoryShare(
                    categoryId = 202L,
                    ownerMemberId = 101L,
                    targetMemberId = 103L,
                    permission = ScheduleSharePermission.VIEWER,
                )
            )
        )
        whenever(
            calendarMemberRepository
                .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(404L)
        ).thenReturn(
            listOf(
                ScheduleCalendarMember(
                    calendarId = 404L,
                    memberId = 101L,
                    role = ScheduleCalendarRole.OWNER,
                ),
                ScheduleCalendarMember(
                    calendarId = 404L,
                    memberId = 104L,
                    role = ScheduleCalendarRole.VIEWER,
                ),
                ScheduleCalendarMember(
                    calendarId = 404L,
                    memberId = 105L,
                    role = ScheduleCalendarRole.EDITOR,
                ),
            )
        )

        val audience = resolver.resolve(schedule)

        assertEquals(setOf(101L, 102L, 103L, 104L, 105L), audience)
        verify(scheduleShareRepository)
            .findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                303L,
                ScheduleShareStatus.ACTIVE,
            )
        verify(categoryShareRepository)
            .findAllByCategoryIdAndStatusAndDeletedFalseOrderByIdAsc(
                202L,
                ScheduleShareStatus.ACTIVE,
            )
        verify(calendarMemberRepository)
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(404L)
    }
}
