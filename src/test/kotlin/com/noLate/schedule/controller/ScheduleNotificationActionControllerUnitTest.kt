package com.noLate.schedule.controller

import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleUseCase
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleNotificationActionControllerUnitTest {
    @Mock
    lateinit var scheduleUseCase: ScheduleUseCase

    private val principal = MemberPrincipal(7L, "member@example.com", "member")
    private val schedule = ScheduleDto(
        id = 71L,
        ownerMemberId = 7L,
        title = "notification action",
        startAt = "2026-07-24T10:00:00Z",
        category = ScheduleCategoryDto(id = "1", title = "일정", color = "#246BFE"),
    )

    @Test
    fun `depart-now forwards action-specific Idempotency-Key`() {
        val key = "departNow:key:" + "c".repeat(64)
        whenever(scheduleUseCase.markDeparted(7L, 71L, key)).thenReturn(schedule)
        val controller = ScheduleController(scheduleUseCase)

        val response = controller.markScheduleDeparted(principal, 71L, key)

        assertEquals(schedule, response.data)
        verify(scheduleUseCase).markDeparted(7L, 71L, key)
    }

    @Test
    fun `snooze forwards key and missing header stays legacy-compatible`() {
        val key = "snooze:key:" + "d".repeat(64)
        val controller = ScheduleController(scheduleUseCase)

        controller.snoozeDepartureReminder(principal, 71L, key)
        controller.snoozeDepartureReminder(principal, 71L, null)

        verify(scheduleUseCase).snoozeDepartureReminder(7L, 71L, key)
        verify(scheduleUseCase).snoozeDepartureReminder(7L, 71L, null)
    }
}
