package com.noLate.schedule.application.cache

import com.noLate.schedule.application.service.ScheduleShareGrantedEvent
import com.noLate.schedule.domain.ScheduleShareResourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify

@ExtendWith(MockitoExtension::class)
class ScheduleCalendarCacheInvalidationListenerTest {
    @Mock
    lateinit var coordinator: ScheduleCalendarCacheInvalidationCoordinator

    @Test
    fun `일정 카테고리 캘린더 공유 승인은 모두 수신자 revision을 무효화한다`() {
        val listener = ScheduleCalendarCacheInvalidationListener(coordinator)

        ScheduleShareResourceType.entries.forEachIndexed { index, resourceType ->
            listener.onShareGranted(
                ScheduleShareGrantedEvent(
                    targetMemberId = 77L,
                    resourceType = resourceType,
                    resourceId = index + 1L,
                    resourceTitle = "공유 대상",
                )
            )
        }

        ScheduleShareResourceType.entries.forEach { resourceType ->
            verify(coordinator).register(
                listOf(77L),
                "${resourceType.name.lowercase()}-share-granted",
            )
        }
    }

    @Test
    fun `일정 수정 초대 수락과 멤버 변경 이벤트는 frozen audience 전체를 무효화한다`() {
        val listener = ScheduleCalendarCacheInvalidationListener(coordinator)
        val reasons = listOf(
            "schedule-updated",
            "schedule-share-updated",
            "share-invitation-accepted",
            "calendar-member-role-updated",
        )

        reasons.forEach { reason ->
            listener.onInvalidated(
                ScheduleCalendarCacheInvalidationEvent(
                    memberIds = setOf(10L, 20L),
                    reason = reason,
                )
            )
        }

        reasons.forEach { reason ->
            verify(coordinator).register(setOf(10L, 20L), reason)
        }
    }
}
